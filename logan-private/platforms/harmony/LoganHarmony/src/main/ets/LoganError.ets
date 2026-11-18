/*
 * LoganError —— 非致命错误采集通道（对齐 Android LoganError.java / iOS LoganErrorKit.m）。
 *
 * 鸿蒙没有字节码增强能力，自动源只有两个（都真实，不伪造）：
 *   1) 显式入口 LoganError.reportCaught(t, where) —— 宿主在自己的 catch 块里一行调用（可选，零强制）
 *   2) H5 侧 window.onerror / unhandledrejection —— 经 LoganH5 代理回传 reportFromH5
 * 一行开关 LoganError.setEnabled(false) 关闭（默认开启）。与崩溃通道不双计：未捕获异常归 LoganCrash。
 */
import hilog from '@ohos.hilog';
import { LoganHttp } from './LoganHttp';

const TAG = 'LoganError';
const QUEUE_MAX = 300;
const BATCH_MAX = 40;
const FLUSH_MS = 5000;
const STORM_MS = 3000;
const KINDS = ['business','caught','ui','promise','resource','other'];

export class LoganErrorEvent {
  kind = 'caught';
  name = '';
  message = '';
  stack = '';
  pageUrl = '';
  traceId = '';
  fatal = false;
  extra = '';
  occurredAt = 0;
}

let queue: LoganErrorEvent[] = [];
let timer: number = -1;
let enabled = true;
const storm: Map<string, number> = new Map<string, number>();

// 注册 H5 错误钩子（单向依赖：LoganHttp 不认识 LoganError）
LoganHttp.setH5ErrorHandler((name: string, message: string, stack: string, pageUrl: string, kind: string): void => {
  LoganError.reportFromH5(name, message, stack, pageUrl, kind);
});

function norm(s: string | undefined | null, max: number): string {
  if (typeof s !== 'string') { return ''; }
  const t = s.trim();
  return t.length > max ? t.substring(0, max) : t;
}

export class LoganError {
  static setEnabled(v: boolean): void {
    enabled = v;
    if (!v) { LoganError.flushNow(); }
  }

  static isEnabled(): boolean { return enabled; }

  /** 显式入口：业务 catch 块里一行上报（t 为真实 Error / BusinessError） */
  static reportCaught(t: Object | null, where: string, kind?: string): void {
    const e = new LoganErrorEvent();
    // 显式 catch 上报未指定分类时归 caught（不是 other）；服务端 kindOf 白名单同样认 caught
    e.kind = LoganError.pickKind(kind === undefined || kind === null ? 'caught' : kind);
    e.name = LoganError.nameOf(t);
    e.message = LoganError.messageOf(t);
    e.stack = LoganError.stackOf(t);
    e.name = norm(where, 300) || e.name;
    LoganError.report(e);
  }

  /** 显式入口：完整字段版（业务想带上页面、链路号时用） */
  static reportError(kind: string, name: string, message: string, stack: string, pageUrl?: string, extra?: string): void {
    const e = new LoganErrorEvent();
    e.kind = LoganError.pickKind(kind);
    e.name = name;
    e.message = message;
    e.stack = stack;
    e.pageUrl = pageUrl ?? '';
    e.extra = extra ?? '';
    LoganError.report(e);
  }

  /** H5 桥入口：window.onerror / unhandledrejection 的真实错误 */
  static reportFromH5(name: string, message: string, stack: string, pageUrl: string, kind: string): void {
    const e = new LoganErrorEvent();
    e.kind = LoganError.pickKind(kind);   // 白名单原样透传（caught/business/promise/ui/resource/other）
    e.name = name;
    e.message = message;
    e.stack = stack;
    e.pageUrl = pageUrl;
    LoganError.report(e);
  }

  static report(e: LoganErrorEvent): void {
    if (!enabled) { return; }
    // 三要素全空 → 丢弃，不造空数据
    if (norm(e.name, 300).length === 0 && norm(e.message, 2000).length === 0 && norm(e.stack, 8000).length === 0) { return; }
    const c = LoganHttp.config();
    if (!c || c.appId.length === 0 || c.deviceId.length === 0) { return; }
    const now = Date.now();
    if (e.occurredAt <= 0) { e.occurredAt = now; }
    // 同 kind|name 风暴抑制（循环里反复抛同一错误不打爆上报）
    const key = e.kind + '|' + norm(e.name, 120);
    const last = storm.get(key);
    if (last !== undefined && now - last < STORM_MS) { return; }
    storm.set(key, now);
    if (storm.size > 512) { storm.clear(); storm.set(key, now); }
    e.name = norm(e.name, 300);
    e.message = norm(e.message, 2000);
    e.stack = norm(e.stack, 8000);
    e.extra = norm(e.extra, 4000);
    if (queue.length >= QUEUE_MAX) { return; }
    queue.push(e);
    LoganError.ensureTimer();
    if (queue.length >= BATCH_MAX) { LoganError.flushNow(); }
  }

  static flushNow(): void {
    if (timer >= 0) {
      clearTimeout(timer);
      timer = -1;
    }
    if (queue.length === 0) { return; }
    const c = LoganHttp.config();
    if (!c) { return; }
    const batch = queue.splice(0, BATCH_MAX);
    const body: Record<string, Object> = {
      'appId': c.appId,
      'deviceId': c.deviceId,
      'platform': c.platform,
      'appVersion': c.appVersion,
      'buildVersion': c.buildVersion,
      'events': batch.map((e: LoganErrorEvent): Record<string, Object> => {
        const b: Record<string, Object> = {
          'kind': e.kind,
          'occurredAt': e.occurredAt,
          'fatal': e.fatal
        };
        if (e.name.length > 0) { b['name'] = e.name; }
        if (e.message.length > 0) { b['message'] = e.message; }
        if (e.stack.length > 0) { b['stack'] = e.stack; }
        if (norm(e.pageUrl, 1000).length > 0) { b['pageUrl'] = norm(e.pageUrl, 1000); }
        if (norm(e.traceId, 128).length > 0) { b['traceId'] = norm(e.traceId, 128); }
        if (e.extra.length > 0) { b['extra'] = e.extra; }
        return b;
      })
    };
    LoganHttp.post(c, '/logan/error/report.json', body);
  }

  private static pickKind(kind: string | undefined): string {
    const k = norm(kind, 32).toLowerCase();
    return KINDS.indexOf(k) >= 0 ? k : 'other';
  }

  private static nameOf(t: Object | null): string {
    if (!t) { return ''; }
    try {
      const r = t as Record<string, Object>;
      const n = r['name'];
      if (typeof n === 'string' && (n as string).length > 0) { return n as string; }
      const code = r['code'];
      if (typeof code === 'number') { return 'BusinessError#' + String(code); }
      return t.constructor ? t.constructor.name : 'Error';
    } catch (e) {
      return 'Error';
    }
  }

  private static messageOf(t: Object | null): string {
    if (!t) { return ''; }
    try {
      const r = t as Record<string, Object>;
      const m = r['message'];
      if (typeof m === 'string') { return m as string; }
      return String(t);
    } catch (e) {
      return '';
    }
  }

  private static stackOf(t: Object | null): string {
    if (!t) { return ''; }
    try {
      const r = t as Record<string, Object>;
      const s = r['stack'];
      return typeof s === 'string' ? (s as string) : '';
    } catch (e) {
      return '';
    }
  }

  private static ensureTimer(): void {
    if (timer >= 0) { return; }
    timer = setTimeout(() => {
      timer = -1;
      LoganError.flushNow();
    }, FLUSH_MS);
  }
}
