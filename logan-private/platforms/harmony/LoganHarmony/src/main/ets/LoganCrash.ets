/*
 * LoganCrash —— 鸿蒙崩溃层。不引第三方：订阅官方系统通道（API12+ 已核实 d.ts 签名）。
 *  - JS 未捕获异常：errorManager.on('error', ErrorObserver) → 当场写 type99 + flush + 裸 HTTPS POST（A 通道）
 *  - native crash / freeze：崩溃当场进程没机会写 → 下次启动 @ohos.faultLogger.querySelfFaultLog 补读（B 通道）
 *  - 未处理 Promise：errorManager.on('unhandledRejection') 记常规 type4（非致命）
 * 防风暴：同 fingerprint 10s 窗口节流（对齐 Android CrashDirectUploader）。
 */
import errorManager from '@ohos.app.ability.errorManager';
import faultLogger from '@ohos.faultLogger';
import http from '@ohos.net.http';
import deviceInfo from '@ohos.deviceInfo';

import { LoganCore } from './LoganCore';
import { CrashEnvelope, extractTopFrames, makeFingerprint } from './CrashEnvelope';

export class CrashConfig {
  baseUrl = 'https://ceshixiaozhan.xyz:18443/logan-web';
  appId = '';
  appVersion = '';
  buildVersion = '';
  deviceId = '';
  unionId = '';
  userId = '';
  authHeader = ''; // "Basic xxx"，与 PoC nginx basic-auth 对齐
}

const lastPost = new Map<string, number>();
let cfg: CrashConfig | null = null;
let started = false;

function envelope(kind: string, signal: string, excType: string, text: string, deferred: boolean): CrashEnvelope {
  const top = extractTopFrames(text);
  const c = cfg!;
  return {
    kind, crashTime: Date.now(), deferred,
    appId: c.appId, appVersion: c.appVersion, buildVersion: c.buildVersion,
    deviceId: c.deviceId, unionId: c.unionId, userId: c.userId,
    brand: deviceInfo.brand ?? 'unknown', model: deviceInfo.productModel ?? 'unknown',
    sdkInt: deviceInfo.sdkApiVersion ?? 0, release: deviceInfo.osFullName ?? 'unknown',
    fingerprint: makeFingerprint(kind, signal, excType, top),
    topFrames: top, signal, content: text.slice(0, 56 * 1024),
    os: 'harmony', platform: 3
  };
}

function throttled(fp: string): boolean {
  const now = Date.now();
  const last = lastPost.get(fp) ?? 0;
  if (now - last < 10000) return true;
  lastPost.set(fp, now);
  return false;
}

/** A 通道：独立裸 HTTPS POST，不依赖 Logan 上传队列（对齐 Android CrashHttp） */
function postCrash(env: CrashEnvelope): void {
  try {
    const req = http.createHttp();
    const headers: Record<string, string> = { 'Content-Type': 'application/json' };
    if (cfg!.authHeader) headers['Authorization'] = cfg!.authHeader;
    req.request(cfg!.baseUrl + '/logan/crash/upload.json', {
      method: http.RequestMethod.POST, header: headers, extraData: JSON.stringify(env),
      connectTimeout: 10000, readTimeout: 15000
    }, () => { req.destroy(); });
  } catch { /* 失败由 B 通道兜底 */ }
}

function recordCrash(env: CrashEnvelope): void {
  if (throttled(env.fingerprint)) return;
  if (LoganCore.isReady()) { LoganCore.w(99, JSON.stringify(env)); LoganCore.flush(); }
  postCrash(env);
}

// 官方 d.ts: ErrorObserver = class { onUnhandledException(errMsg: string): void }
// ArkTS 严格模式：自定义类实现同签名方法即可传给 errorManager.on('error', ...)
class LoganErrorObserver {
  onUnhandledException(errMsg: string): void {
    try {
      const text = String(errMsg ?? '');
      const probes: string[] = ['Error', 'Exception'];
      let name = 'Error';
      for (const p of probes) { if (text.includes(p)) { name = p === 'Error' ? 'Error' : 'Exception'; break; } }
      recordCrash(envelope('jscrash', '', name, text, false));
    } catch { /* observer 内绝不抛出 */ }
  }
}

export class LoganCrash {
  private static filesDir = '';

  static init(config: CrashConfig, filesDir: string): void {
    if (started) return;
    cfg = config;
    started = true;
    LoganCrash.filesDir = filesDir;
    // JS 未捕获异常（A 通道，当场）
    try { errorManager.on('error', new LoganErrorObserver()); } catch { /* API 面差异容错 */ }
    // 未处理 Promise 记常规日志（非致命）
    try {
      errorManager.on('unhandledRejection', (info: Object) => {
        if (LoganCore.isReady()) LoganCore.w(4, 'UnhandledRejection: ' + String(info));
      });
    } catch {}
    // 官方 faultLogger 事件登记（触发系统侧记录；内容由下方目录扫描补读）
    try {
      faultLogger.querySelfFaultLog(faultLogger.FaultType.NO_SPECIFIC).then(() => { LoganCrash.sweepFaultLogs(); }).catch(() => { LoganCrash.sweepFaultLogs(); });
    } catch { LoganCrash.sweepFaultLogs(); }
  }

  /**
   * B 通道：下次启动扫应用沙箱 faultlog 目录，逐条补传，seen 名单去重。
   * 核实：本 SDK d.ts 的 FaultLogInfo 无内容字段（仅 pid/uid/type/timestamp/reason），
   * 官方内容通道 = 沙箱 /data/storage/el2/log/faultlog/faultlogger/ 下文件。
   */
  static sweepFaultLogs(): void {
    const dir = '/data/storage/el2/log/faultlog/faultlogger';
    let names: string[] = [];
    try { names = LoganCore.listFilesInDir(dir); } catch { return; }
    if (names.length === 0) return;
    const seen = LoganCrash.loadSeen();
    for (const name of names) {
      if (!name.endsWith('.log')) continue;
      if (seen.includes(name)) continue;
      let text = '';
      try { text = LoganCore.readFileText(dir + '/' + name); } catch { continue; }
      if (!text) continue;
      const kind = name.indexOf('cppcrash') >= 0 ? 'cppcrash' : (name.indexOf('jscrash') >= 0 ? 'jscrash' : (name.indexOf('freeze') >= 0 ? 'freeze' : 'app'));
      const sig = LoganCrash.parseSignal(text);
      recordCrash(envelope(kind, sig, '', text, true));
      seen.push(name);
      LoganCrash.saveSeen(seen);
    }
  }

  private static seenFile(): string { return LoganCrash.filesDir + '/logan_fault_seen.json'; }
  private static loadSeen(): string[] {
    try { const t = LoganCore.readFileText(LoganCrash.seenFile()); return t ? (JSON.parse(t) as string[]) : []; } catch { return []; }
  }
  private static saveSeen(list: string[]): void {
    try { LoganCore.writeSeenList(LoganCrash.seenFile(), JSON.stringify(list)); } catch { /* 容错 */ }
  }

  private static parseSignal(text: string): string {
    const probes: string[] = ['SIGSEGV', 'SIGABRT', 'SIGBUS', 'SIGFPE', 'SIGILL', 'SIGTRAP'];
    for (const p of probes) { if (text.includes(p)) { return p; } }
    const idx = text.indexOf('Signal (sig');
    if (idx >= 0) {
      const seg = text.slice(idx, idx + 40);
      return seg.split(String.fromCharCode(10))[0];
    }
    return '';
  }

  /** 手动测试钩子（对齐 Android testJavaCrash/testNativeCrash） */
  static testJsCrash(): void {
    throw new Error('logan-private harmony test js crash');
  }
}
