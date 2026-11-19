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
import hilog from '@ohos.hilog';

import { LoganCore } from './LoganCore';
import { CrashEnvelope, extractTopFrames, makeFingerprint } from './CrashEnvelope';
import { startAnrWatchdog, markCrashHandledForAnr } from './LoganAnrWatchdog';

export class CrashConfig {
  baseUrl = 'https://ceshixiaozhan.xyz:18443/logan-web';
  appId = '';
  appVersion = '';
  buildVersion = '';
  deviceId = '';
  unionId = '';
  userId = '';
  authHeader = ''; // "Basic xxx"，与 PoC nginx basic-auth 对齐
  anrWatchdog = true;      // 独立主线程卡顿看门狗（对齐 Android CrashConfig.anrWatchdog）
  anrTimeoutMs = 5000;     // 心跳停多久判定卡顿（对齐安卓 anrTimeoutMs=5000L）
}

const TAG = 'LoganCrash';
const DOMAIN = 0x0001;

const lastPost = new Map<string, number>();
let cfg: CrashConfig | null = null;
let started = false;

export function envelope(kind: string, signal: string, excType: string, text: string, deferred: boolean): CrashEnvelope {
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
export function postCrash(env: CrashEnvelope): void {
  try {
    const req = http.createHttp();
    const headers: Record<string, string> = { 'Content-Type': 'application/json' };
    if (cfg!.authHeader) headers['Authorization'] = cfg!.authHeader;
    // 必须 await/处理 Promise：裸调用不发起请求（前会话同款坑，deferred 重试链路同款写法），
    // 失败 catch 静默——B 通道 sweepFaultLogs 兜底。
    req.request(cfg!.baseUrl + '/logan/crash/upload.json', {
      method: http.RequestMethod.POST, header: headers, extraData: JSON.stringify(env),
      connectTimeout: 10000, readTimeout: 15000
    }).then(() => { req.destroy(); }).catch(() => { req.destroy(); });
  } catch { /* 失败由 B 通道兜底 */ }
}

function recordCrash(env: CrashEnvelope): void {
  if (throttled(env.fingerprint)) return;
  if (LoganCore.isReady()) { LoganCore.w(99, JSON.stringify(env)); LoganCore.flush(); }
  postCrash(env);
  try { markCrashHandledForAnr(); } catch { /* 避让逻辑失败不影响崩溃主链路 */ }
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
        if (LoganCore.isReady()) LoganCore.w(3, 'UnhandledRejection: ' + String(info));
      });
    } catch {}
    // B 通道：官方 faultLogger.query 取 fullLog（FaultLogInfo 带完整日志文本，已核 d.ts）为主，
    // 沙箱目录扫描为兜底（某些版本 fullLog 截断或 query 空时仍能拿到原始文件）。
    LoganCrash.sweepAll();
    // 独立主线程卡顿看门狗（对标 Android AnrWatchdog）：鸿蒙系统 freeze 通道（hiAppEvent WatchFreeze）
    // 判定阈值不受我们控制且依赖系统检测节奏，PoC 用自有心跳近似判定，独立开关可关。
    try { const cfg2 = cfg!; startAnrWatchdog(() => cfg2); } catch (e) { hilog.warn(DOMAIN, TAG, 'anr watchdog start throw: %{public}s', String(e)); }
  }

  /**
   * B 通道：官方 faultLogger.query 取 fullLog（FaultLogInfo 带完整日志文本，已核 d.ts）为主，
   * 沙箱目录扫描为兜底（某些版本 fullLog 截断或 query 返回空时仍能拿到原始文件）。
   * 必须串行执行：seen 名单落盘做跨启动去重，多 type 并发各自 loadSeen/saveSeen 会互相
   * 覆盖后写入的 key，导致下次冷启动重复补传同一 faultlog（平台侧 crash_count 虚增）。
   */
  static sweepAll(): void {
    LoganCrash.runSweep().catch((e: Object) => { hilog.warn(DOMAIN, TAG, 'sweepAll throw: %{public}s', String(e)); });
  }

  private static async runSweep(): Promise<void> {
    await LoganCrash.queryFaultLogs();
    try { LoganCrash.sweepFaultLogs(); } catch (e) { hilog.warn(DOMAIN, TAG, 'sweepFaultLogs throw: %{public}s', String(e)); }
  }

  private static async queryFaultLogs(): Promise<void> {
    const types: Array<faultLogger.FaultType> = [faultLogger.FaultType.CPP_CRASH, faultLogger.FaultType.JS_CRASH, faultLogger.FaultType.APP_FREEZE];
    const seen = LoganCrash.loadSeen();
    for (const t of types) {
      let infos: Array<faultLogger.FaultLogInfo> | null = null;
      try { infos = await faultLogger.query(t); } catch (e) { hilog.warn(DOMAIN, TAG, 'faultLogger.query throw: %{public}s', String(e)); continue; }
      const n = infos ? infos.length : 0;
      hilog.info(DOMAIN, TAG, 'faultLogger.query type=%{public}d -> %{public}d items', t, n);
      if (!infos) continue;
      for (const info of infos) {
        try {
          const key = 'q:' + t + ':' + info.pid + ':' + info.timestamp + ':' + (info.reason || '').slice(0, 40);
          if (seen.indexOf(key) >= 0) continue;
          const body = info.fullLog && info.fullLog.length > 0 ? info.fullLog : ((info.summary || '') + String.fromCharCode(10) + (info.reason || ''));
          if (!body || body.length < 8) continue;
          const kind = t === faultLogger.FaultType.CPP_CRASH ? 'cppcrash' : (t === faultLogger.FaultType.JS_CRASH ? 'jscrash' : 'freeze');
          const reason = String(info.reason || '');
          const sig = LoganCrash.parseSignal(reason + String.fromCharCode(10) + body);
          const excType = kind === 'jscrash' ? 'Error' : '';
          const env = envelope(kind, sig, excType, body, true);
          env.crashTime = info.timestamp > 0 ? info.timestamp : Date.now();
          recordCrash(env);
          seen.push(key);
          LoganCrash.saveSeen(seen);
          hilog.info(DOMAIN, TAG, 'faultlog uploaded via query, kind=%{public}s len=%{public}d', kind, body.length);
        } catch (e) { hilog.warn(DOMAIN, TAG, 'query item skip: %{public}s', String(e)); }
      }
    }
  }
  static sweepFaultLogs(): void {
    const dir = '/data/storage/el2/log/faultlog/faultlogger';
    let names: string[] = [];
    try { names = LoganCore.listFilesInDir(dir); } catch (e) { hilog.warn(DOMAIN, TAG, 'listFilesInDir(%{public}s) failed: %{public}s', dir, String(e)); return; }
    hilog.info(DOMAIN, TAG, 'sweepFaultLogs dir=%{public}s names=%{public}d', dir, names.length);
    if (names.length === 0) return;
    const seen = LoganCrash.loadSeen();
    for (const name of names) {
      // faultlogger 落盘文件名没有 .log 后缀（jscrash-<bundle>-<uid>-<yyyymmddhhmmss>），
      // 只认 .log 会让 B 通道永远扫不到（与 Android 通道 B 只认 .log 而 xCrash 写 .xcrash 同类 bug）
      if (!LoganCrash.isFaultLogName(name)) continue;
      if (seen.includes(name)) continue;
      let text = '';
      try { text = LoganCore.readFileText(dir + '/' + name); } catch { continue; }
      if (!text) continue;
      const lower = name.toLowerCase();
      const kind = lower.indexOf('cppcrash') >= 0 ? 'cppcrash' : (lower.indexOf('jscrash') >= 0 || lower.indexOf('unhandled') >= 0 ? 'jscrash' : (lower.indexOf('freeze') >= 0 ? 'freeze' : 'app'));
      const sig = LoganCrash.parseSignal(text);
      recordCrash(envelope(kind, sig, '', text, true));
      seen.push(name);
      LoganCrash.saveSeen(seen);
    }
  }

  // 官方 faultlogger 命名：jscrash / cppcrash / freeze 前缀 + bundle + uid + 时间戳（一般无 .log 后缀，兼容有后缀）
  private static isFaultLogName(name: string): boolean {
    const n = name.toLowerCase();
    if (n.endsWith('.temp') || n.indexOf('.') === 0) return false;
    const kinds: string[] = ['jscrash-', 'cppcrash-', 'freeze-', 'jsunhandled-', 'appfreeze-', 'ohjscrash-'];
    for (const p of kinds) { if (n.startsWith(p)) return true; }
    // 兜底：官方某些版本形如 <kind>-bundle-uid-time.log
    return n.endsWith('.log') && (n.indexOf('crash') >= 0 || n.indexOf('freeze') >= 0);
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
