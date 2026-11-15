/*
 * LoganCommands —— 平台侧主动拉日志的客户端轮询器（对齐 Android LoganCommandPoller）。
 *
 * 语义：平台「设备清单」页点「拉取日志」写入待下发命令；本类按固定间隔带机器码领任务，
 * 领到 upload_logs 回调宿主上传，完成后 ack，平台侧 pending→delivered→acked 闭环。
 * 每次轮询同时上报版本/机型，平台据此判定设备在线（logan_device_poll.last_poll_time）。
 */
import http from '@ohos.net.http';
import hilog from '@ohos.hilog';
import { UploadConfig } from './LoganUpload';

const TAG = 'LoganCommands';

export type LoganCommandHandler = (action: string, commandId: number, param: string) => Promise<boolean>;

let cfg: UploadConfig | null = null;
let handler: LoganCommandHandler | null = null;
let timer: number = -1;
let busy = false;
let intervalMs = 60000;

function postJson(c: UploadConfig, path: string, obj: Record<string, string | number | boolean>): Promise<string> {
  return new Promise<string>((resolve) => {
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
      extraData: JSON.stringify(obj),
      connectTimeout: 10000,
      readTimeout: 15000
    }, (err, resp) => {
      req.destroy();
      if (err || !resp) { resolve('{}'); return; }
      const code = resp.responseCode;
      const body = typeof resp.result === 'string' ? (resp.result as string) : '{}';
      if (code !== 200) {
        hilog.warn(0x00FF, TAG, 'http %{public}d %{public}s', code, body.substring(0, 160));
        resolve('{}');
        return;
      }
      resolve(body);
    });
  });
}

async function ack(c: UploadConfig, commandId: number, ok: boolean, note: string): Promise<void> {
  try {
    const body: Record<string, string | number | boolean> = { 'id': commandId, 'ok': ok };
    if (note) { body['resultNote'] = note; }
    await postJson(c, '/logan/device/command/ack.json', body);
  } catch (e) {
    hilog.warn(0x00FF, TAG, 'ack failed %{public}s', String(e));
  }
}

/** 领一次任务并执行；无任务时只当心跳上报。宿主也可手动调用做前台刷新。 */
export async function pollOnce(): Promise<number> {
  const c = cfg;
  if (!c || !c.appId || !c.deviceId || busy) return 0;
  busy = true;
  let ran = 0;
  try {
    const req: Record<string, string | number> = {
      'appId': c.appId, 'deviceId': c.deviceId, 'platform': c.platform,
      'appVersion': c.appVersion, 'buildVersion': c.buildVersion, 'sdkVersion': '0.1.0'
    };
    const text = await postJson(c, '/logan/device/poll.json', req);
    let obj: Record<string, Object> | null = null;
    try { obj = JSON.parse(text) as Record<string, Object>; } catch { obj = null; }
    if (!obj || Number(obj['code']) !== 200) return 0;
    const cmds = obj['data'] as Record<string, Object>[];
    if (!cmds || !cmds.length) return 0;
    for (const cmd of cmds) {
      const id = Number(cmd['id']);
      const action = String(cmd['action'] ?? '');
      const param = cmd['param'] === undefined ? '' : String(cmd['param']);
      hilog.info(0x00FF, TAG, 'command received id=%{public}d action=%{public}s', id, action);
      let ok = false;
      try {
        if (handler) { ok = await handler(action, id, param); }
      } catch (e) {
        hilog.warn(0x00FF, TAG, 'handler failed %{public}s', String(e));
        ok = false;
      }
      await ack(c, id, ok, ok ? '' : 'client handler returned false');
      ran += 1;
    }
  } catch (e) {
    hilog.warn(0x00FF, TAG, 'poll failed %{public}s', String(e));
  } finally {
    busy = false;
  }
  return ran;
}

export class LoganCommands {
  /** 复用上传通道同一份配置（同源 appId/deviceId/凭据，避免两套真值）。 */
  static configure(config: UploadConfig): void { cfg = config; }

  static onCommand(h: LoganCommandHandler): void { handler = h; }

  /** 轮询间隔，最小 15 秒。 */
  static setIntervalMs(ms: number): void { intervalMs = Math.max(15000, ms); }

  /** 启动后台轮询（幂等）。冷启动调用一次即可；首次延迟 8s 避开启动高峰。 */
  static start(): void {
    if (timer >= 0 || !cfg || !cfg.appId || !cfg.deviceId) return;
    const beat = () => {
      pollOnce().catch((e: Error) => hilog.warn(0x00FF, TAG, 'tick failed %{public}s', String(e)));
    };
    setTimeout(beat, 8000);
    timer = setInterval(beat, intervalMs);
    hilog.info(0x00FF, TAG, 'command poller started interval=%{public}d', intervalMs);
  }

  static stop(): void {
    if (timer >= 0) {
      clearInterval(timer);
      timer = -1;
    }
  }
}
