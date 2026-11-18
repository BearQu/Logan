/*
 * LoganHttp —— 业务 HTTP 观测通道（对齐 Android LoganHttp.java / iOS LoganHttp.m）。
 *
 * 通用性（不做单项目适配）：SDK 不依赖任何三方网络库，采集点通过「显式入口」接入：
 *   - LoganHttp.report(event)                      业务/自有网络栈一行上报
 *   - LoganHttp.begin(method,url) + finish(h,res)  成对使用，SDK 负责计时/慢判定/去自采
 *   - axios 等三方库：宿主在自己的装配文件里把这两个入口挂到拦截器（见宿主 LoganHttpWiring.ets）
 * 一行开关：LoganHttp.setEnabled(false) 全量关闭（默认开启自动上报）。
 * 宁缺毋假：取不到的指标（dns/connect/ttfb 鸿蒙无公开 API）整个键不写，交服务端归类。
 */
import http from '@ohos.net.http';
import hilog from '@ohos.hilog';

const TAG = 'LoganHttp';
const QUEUE_MAX = 500;
const BATCH_MAX = 50;
const FLUSH_MS = 5000;
const DEFAULT_SLOW_MS = 3000;

export class HttpConfig {
  baseUrl = 'https://ceshixiaozhan.xyz:18443/logan-web';
  appId = '';
  deviceId = '';
  appVersion = '';
  buildVersion = '';
  authHeader = '';
  /** 平台号：1=Android 2=iOS 3=HarmonyOS */
  platform = 3;
}

export class LoganHttpEvent {
  kind = 'native';           // native|h5（服务端归一）
  method = '';
  url = '';                  // 必填，服务端空则整条丢弃
  host = '';
  path = '';
  status = 0;                // 0=未知
  errKind = '';              // 服务端 HttpNorm 重算，客户端留空
  errMsg = '';
  durationMs = 0;
  slow = false;
  reqBytes = 0;
  respBytes = 0;
  pageUrl = '';
  traceId = '';
  networkType = '';
  extra = '';
  occurredAt = 0;            // 毫秒；0 → 服务端兜底
}

/** begin/finish 成对句柄（SDK 内部计时，宿主只需回填结果） */
export class LoganHttpHandle {
  event: LoganHttpEvent = new LoganHttpEvent();
  startMs = 0;
  done = false;
}

let cfg: HttpConfig | null = null;
let queue: LoganHttpEvent[] = [];
let timer: number = -1;
let enabled = true;
let slowMs = DEFAULT_SLOW_MS;
const excludeHosts: string[] = [];
const excludeSubs: string[] = [];
let seenIds: string[] = [];

let h5ErrorHandler: ((name: string, message: string, stack: string, pageUrl: string, kind: string) => void) | null = null;


function trim(s: string | undefined | null): string {
  return typeof s === 'string' ? s.trim() : '';
}

function hostOf(url: string): string {
  const i = url.indexOf('://');
  if (i < 0) { return ''; }
  const rest = url.substring(i + 3);
  const j = rest.indexOf('/');
  const k = rest.indexOf('?');
  let h = j < 0 ? rest : rest.substring(0, j);
  if (k >= 0 && (j < 0 || k < j)) { h = rest.substring(0, k); }
  const c = h.indexOf(':');
  return c < 0 ? h : h.substring(0, c);
}

function pathOf(url: string): string {
  const i = url.indexOf('://');
  if (i < 0) { return ''; }
  const rest = url.substring(i + 3);
  const j = rest.indexOf('/');
  if (j < 0) { return '/'; }
  const after = rest.substring(j);
  const q = after.indexOf('?');
  return q < 0 ? after : after.substring(0, q);
}

export class LoganHttp {
  /** 一行配置（宿主在 LoganKit.init 里调用，与上传/崩溃通道共用同一份身份与凭据） */
  static configure(c: HttpConfig): void {
    const n = new HttpConfig();
    n.baseUrl = trim(c.baseUrl).replace(/\/+$/, '');
    n.appId = trim(c.appId);
    n.deviceId = trim(c.deviceId);
    n.appVersion = trim(c.appVersion);
    n.buildVersion = trim(c.buildVersion);
    n.authHeader = trim(c.authHeader);
    n.platform = c.platform > 0 ? c.platform : 3;
    cfg = n;
  }

  /** 一行开关（默认开启） */
  static setEnabled(v: boolean): void {
    enabled = v;
    if (!v) { LoganHttp.flushNow(); }
  }

  static isEnabled(): boolean { return enabled; }

  /** 慢请求阈值（默认 3000ms，可配） */
  static setSlowThresholdMs(ms: number): void {
    if (ms > 0) { slowMs = ms; }
  }

  static slowThresholdMs(): number { return slowMs; }

  /** 通用排除：域名（等值或后缀）/ URL 子串，业务有证书锁定或自家上报时用它避免自采与干扰 */
  static excludeHost(host: string): void {
    const h = trim(host).toLowerCase();
    if (h.length > 0 && excludeHosts.indexOf(h) < 0) { excludeHosts.push(h); }
  }

  static excludeUrlContains(sub: string): void {
    const s = trim(sub);
    if (s.length > 0 && excludeSubs.indexOf(s) < 0) { excludeSubs.push(s); }
  }

  static isObserved(url: string): boolean {
    const u = trim(url);
    if (u.length === 0) { return false; }
    if (LoganHttp.isSelfUrl(u)) { return false; }
    const h = hostOf(u).toLowerCase();
    for (const e of excludeHosts) {
      if (h === e || h.endsWith('.' + e)) { return false; }
    }
    for (const s of excludeSubs) {
      if (u.indexOf(s) >= 0) { return false; }
    }
    if (!(u.startsWith('http://') || u.startsWith('https://'))) { return false; }
    return !LoganHttp.isStaticAsset(pathOf(u));
  }

  /** 只排自家平台接口（baseUrl 后 /logan/ 前缀），避免误杀同域业务 H5 */
  static isSelfUrl(url: string): boolean {
    const c = cfg;
    if (!c || c.baseUrl.length === 0) { return false; }
    const u = trim(url);
    const host = hostOf(u);
    const selfHost = hostOf(c.baseUrl);
    if (host.length === 0 || host !== selfHost) { return false; }
    const p = pathOf(u);
    const tail = c.baseUrl.substring(c.baseUrl.indexOf(selfHost) + selfHost.length);
    const base = tail.replace(/\/+$/, '');
    return p.startsWith(base + '/logan/') || p === base + '/logan';
  }

  private static isStaticAsset(path: string): boolean {
    const dot = path.lastIndexOf('.');
    if (dot < 0) { return false; }
    const ext = path.substring(dot + 1).toLowerCase();
    const list = ['png','jpg','jpeg','gif','webp','bmp','svg','ico','css','woff','woff2','ttf','otf','eot','mp3','mp4','mov','avi','wav','amr','js','map','json.zip'];
    return list.indexOf(ext) >= 0;
  }

  /** 显式入口：一条完整事件（自有网络栈、SDK 无法自动观测的场景） */
  static report(e: LoganHttpEvent): void {
    if (!enabled) { return; }
    if (!cfg || trim(cfg.appId).length === 0 || trim(cfg.deviceId).length === 0) { return; }
    if (trim(e.url).length === 0) { return; }             // 服务端同样丢弃，不造空数据
    if (!LoganHttp.isObserved(e.url)) { return; }
    if (e.occurredAt <= 0) { e.occurredAt = Date.now(); }
    if (trim(e.kind) !== 'h5') { e.kind = 'native'; }
    if (e.durationMs > 0 && e.durationMs >= slowMs) { e.slow = true; }
    // 同一 URL 同一秒的重复事件去重（H5 Resource Timing 与显式上报可能双计）
    const key = e.method + '|' + e.url + '|' + e.status + '|' + Math.floor(e.occurredAt / 1000);
    if (seenIds.indexOf(key) >= 0) { return; }
    seenIds.push(key);
    if (seenIds.length > 400) { seenIds = seenIds.slice(-200); }
    if (trim(e.host).length === 0) { e.host = hostOf(e.url); }
    if (trim(e.path).length === 0) { e.path = pathOf(e.url); }
    if (e.errMsg.length > 2000) { e.errMsg = e.errMsg.substring(0, 2000); }
    if (e.extra.length > 4000) { e.extra = e.extra.substring(0, 4000); }
    if (queue.length >= QUEUE_MAX) { return; }             // 背压：满则丢新，不阻塞业务
    queue.push(e);
    LoganHttp.ensureTimer();
    if (queue.length >= BATCH_MAX) { LoganHttp.flushNow(); }
  }

  /** 显式入口（计时版）：业务发起请求前 begin，拿到结果后 finish，SDK 负责耗时/慢判定 */
  static begin(method: string, url: string): LoganHttpHandle {
    const h = new LoganHttpHandle();
    h.event.method = trim(method).toUpperCase();
    h.event.url = trim(url);
    h.startMs = Date.now();
    h.event.occurredAt = h.startMs;
    return h;
  }

  static finish(h: LoganHttpHandle | null, status: number, errMsg: string, respBytes: number): void {
    if (!h || h.done) { return; }
    h.done = true;
    const now = Date.now();
    h.event.durationMs = now - h.startMs;
    h.event.status = status > 0 ? status : 0;
    if (errMsg) { h.event.errMsg = errMsg; }
    if (respBytes > 0) { h.event.respBytes = respBytes; }
    LoganHttp.report(h.event);
  }

  static flushNow(): void {
    if (timer >= 0) {
      clearTimeout(timer);
      timer = -1;
    }
    if (queue.length === 0) { return; }
    const batch = queue.splice(0, BATCH_MAX);
    const c = cfg;
    if (!c) { return; }
    const body: Record<string, Object> = {
      'appId': c.appId,
      'deviceId': c.deviceId,
      'platform': c.platform,
      'appVersion': c.appVersion,
      'buildVersion': c.buildVersion,
      'events': batch.map((e: LoganHttpEvent): Record<string, Object> => LoganHttp.toBody(e))
    };
    LoganHttp.post(c, '/logan/http/report.json', body);
  }

  private static toBody(e: LoganHttpEvent): Record<string, Object> {
    const b: Record<string, Object> = {
      'kind': e.kind,
      'url': e.url,
      'status': e.status,
      'durationMs': e.durationMs,
      'slow': e.slow,
      'occurredAt': e.occurredAt
    };
    if (trim(e.method).length > 0) { b['method'] = e.method; }
    if (trim(e.host).length > 0) { b['host'] = e.host; }
    if (trim(e.path).length > 0) { b['path'] = e.path; }
    if (trim(e.errKind).length > 0) { b['errKind'] = e.errKind; }
    if (trim(e.errMsg).length > 0) { b['errMsg'] = e.errMsg; }
    if (e.reqBytes > 0) { b['reqBytes'] = e.reqBytes; }
    if (e.respBytes > 0) { b['respBytes'] = e.respBytes; }
    if (trim(e.pageUrl).length > 0) { b['pageUrl'] = e.pageUrl; }
    if (trim(e.traceId).length > 0) { b['traceId'] = e.traceId; }
    if (trim(e.networkType).length > 0) { b['networkType'] = e.networkType; }
    if (trim(e.extra).length > 0) { b['extra'] = e.extra; }
    return b;
  }

  /** H5 桥用：给注入脚本的配置（不含凭据，仅定位与阈值） */
  static h5ConfigJson(): string {
    const c = cfg;
    const o: Record<string, Object> = {
      'baseUrl': c ? c.baseUrl : '',
      'appId': c ? c.appId : '',
      'deviceId': c ? c.deviceId : '',
      'appVersion': c ? c.appVersion : '',
      'buildVersion': c ? c.buildVersion : '',
      'platform': cfg ? cfg.platform : 3,
      'slowThresholdMs': slowMs,
      'enabled': enabled
    };
    return JSON.stringify(o);
  }

  /** H5 事件入口：由 LoganH5 代理对象调用，统一归 kind=h5 */
  static reportH5Payload(json: string): boolean {
    try {
      const parsed = JSON.parse(json) as Record<string, Object>;
      const raws = parsed['events'];
      if (!raws || !Array.isArray(raws)) { return false; }
      let n = 0;
      for (const item of raws as Object[]) {
        const r = item as Record<string, Object>;
        const e = new LoganHttpEvent();
        e.kind = 'h5';
        const s = (k: string): string => {
          const v = r[k];
          return typeof v === 'string' ? (v as string) : '';
        };
        const num = (k: string): number => {
          const v = r[k];
          return typeof v === 'number' ? (v as number) : 0;
        };
        const jk = s('kind');
        if (jk === 'error' || jk === 'caught' || jk === 'business' || jk === 'promise'
          || jk === 'ui' || jk === 'resource' || jk === 'other') {
          // H5 的非致命错误走错误通道，不进 http 表（与 logan-h5.js 的 kind 语义一致）
          if (h5ErrorHandler !== null) {
            h5ErrorHandler(s('name') || 'H5Error', s('message'), s('stack'),
              s('pageUrl') || s('url'), jk === 'error' ? 'caught' : jk);
          }
          n++;
          continue;
        }
        e.method = s('method');
        e.url = s('url');
        e.status = num('status');
        e.durationMs = num('durationMs');
        e.pageUrl = s('pageUrl') || s('url');
        e.errMsg = s('errMsg');
        e.traceId = s('traceId');
        e.respBytes = num('respBytes');
        e.occurredAt = num('occurredAt');
        const ex = r['extra'];
        if (typeof ex === 'string') { e.extra = ex as string; }
        if (trim(e.url).length === 0) { continue; }
        LoganHttp.report(e);
        n++;
      }
      return n > 0;
    } catch (e2) {
      hilog.warn(0x00FF, TAG, 'h5 payload parse failed %{public}s', String(e2));
      return false;
    }
  }

  private static ensureTimer(): void {
    if (timer >= 0) { return; }
    timer = setTimeout(() => {
      timer = -1;
      LoganHttp.flushNow();
    }, FLUSH_MS);
  }

  static post(c: HttpConfig, path: string, body: Record<string, Object>): void {
    const req = http.createHttp();
    const h: Record<string, string> = {
      'Content-Type': 'application/json',
      'appId': c.appId,
      'deviceId': c.deviceId,
      'platform': String(c.platform),
      'appVersion': c.appVersion,
      'buildVersion': c.buildVersion,
      'sdkVersion': '0.1.0'
    };
    if (c.authHeader) { h['Authorization'] = c.authHeader; }
    req.request(c.baseUrl + path, {
      method: http.RequestMethod.POST,
      header: h,
      extraData: JSON.stringify(body),
      connectTimeout: 10000,
      readTimeout: 15000
    }, (err, resp) => {
      req.destroy();
      if (err || !resp) {
        hilog.warn(0x00FF, TAG, 'post %{public}s failed %{public}s', path, String(err));
        return;
      }
      if (resp.responseCode < 200 || resp.responseCode > 299) {
        hilog.warn(0x00FF, TAG, 'post %{public}s http %{public}d', path, resp.responseCode);
      }
    });
  }

  /** 由 LoganError 模块注册（避免两模块循环 import） */
  static setH5ErrorHandler(cb: (name: string, message: string, stack: string, pageUrl: string, kind: string) => void): void {
    h5ErrorHandler = cb;
  }

  static config(): HttpConfig | null { return cfg; }
}

export { trim as loganTrim };
