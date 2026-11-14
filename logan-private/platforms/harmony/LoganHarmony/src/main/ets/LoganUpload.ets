/*
 * LoganUpload —— 业务日志包上传通道（对齐 Android LoganKit.uploadToday / iOS LoganKit.uploadToday）。
 *
 * 核实事实：clogan 落盘的 .logan 文件本身已是旧协议格式（0x01 + BE32 长度 + AES-CBC(gzip(\u000a 分隔行))，
 * key/iv 在 LoganCore.init 传入），服务端 LegacyLoganProtocol 可直接解 —— 所以上传 = 原样 POST 文件字节，
 * 元数据全靠 header（服务端 RequestContextParser.parse 只认 header：appId/unionId/platform/buildVersion/
 * appVersion/deviceId/fileDate/log_file_name）。服务端不做任务去重，因此 2xx 后把文件改名 .uploaded 防重复
 * 上传（安卓靠 Logan 的 .copy 已发送标记，鸿蒙用改名，语义等价）；改名后必须重新 loganOpen 新文件，
 * 否则 mmap 仍写旧 inode 会丢日志。
 */
import fs from '@ohos.file.fs';
import http from '@ohos.net.http';
import { LoganCore } from './LoganCore';

export class UploadConfig {
  baseUrl = 'https://ceshixiaozhan.xyz:18443/logan-web';
  appId = '';
  appName = '';
  appVersion = '';
  buildVersion = '';
  deviceId = '';
  unionId = '';
  userId = '';
  authHeader = '';
  /** 平台号：1=Android 2=iOS 3=HarmonyOS（与 LoganCrashServiceImpl.platformOf 约定一致） */
  platform = 3;
}

let ucfg: UploadConfig | null = null;
let uploading = false;

function pad2(n: number): string { return n < 10 ? '0' + n : '' + n; }

function todayStr(): string {
  const d = new Date();
  return d.getFullYear() + '-' + pad2(d.getMonth() + 1) + '-' + pad2(d.getDate());
}

function readBytes(path: string): ArrayBuffer {
  const st = fs.statSync(path);
  const buf = new ArrayBuffer(st.size);
  const file = fs.openSync(path, fs.OpenMode.READ_ONLY);
  try {
    const got = fs.readSync(file.fd, buf);
    if (got !== st.size) {
      // 尺寸不一致（正在被写）→ 按实际读到的长度裁剪
      const slice = new ArrayBuffer(got);
      new Uint8Array(slice).set(new Uint8Array(buf, 0, got));
      return slice;
    }
    return buf;
  } finally {
    fs.closeSync(file);
  }
}

function buildHeaders(c: UploadConfig, date: string, size: number): Record<string, string> {
  const h: Record<string, string> = {
    'Content-Type': 'binary/octet-stream',
    'appId': c.appId,
    'unionId': c.unionId,
    'platform': String(c.platform),
    'buildVersion': c.buildVersion,
    'appVersion': c.appVersion,
    'deviceId': c.deviceId,
    'fileDate': date,
    'log_file_name': c.appId + '_' + c.deviceId + '_' + date,
    'Content-Length': String(size)
  };
  if (c.appName) { h['appName'] = c.appName; }
  if (c.userId) { h['userId'] = c.userId; }
  if (c.authHeader) { h['Authorization'] = c.authHeader; }
  return h;
}

function postFileBytes(c: UploadConfig, path: string, date: string): Promise<number> {
  return new Promise<number>((resolve) => {
    let buf: ArrayBuffer;
    try { buf = readBytes(path); } catch { resolve(-1); return; }
    if (buf.byteLength <= 0) { resolve(-2); return; }
    const req = http.createHttp();
    req.request(c.baseUrl + '/logan/upload.json', {
      method: http.RequestMethod.POST,
      header: buildHeaders(c, date, buf.byteLength),
      extraData: buf,
      connectTimeout: 10000,
      readTimeout: 20000
    }, (err, resp) => {
      req.destroy();
      if (err) { resolve(-3); return; }
      resolve(resp ? resp.responseCode : -4);
    });
  });
}

export class LoganUpload {
  static configure(config: UploadConfig): void { ucfg = config; }

  /**
   * 上传全部未发送日志包（含当天），逐条串行，2xx 才改名 .uploaded 并重新打开当天文件。
   * 返回成功上传的包数；失败保留原文件，下次冷启动或再次调用自动重试（对齐安卓通道 B 的容错语义）。
   */
  static async uploadAll(date?: string): Promise<number> {
    const c = ucfg;
    if (!c || !c.appId || !LoganCore.isReady()) return 0;
    if (uploading) return 0;
    uploading = true;
    let sent = 0;
    try {
      LoganCore.flush();
      const d = date ?? todayStr();
      const dir = LoganCore.logDirPath();
      let names: string[] = [];
      try { names = LoganCore.listFilesInDir(dir); } catch { names = []; }
      for (const name of names) {
        if (!name.endsWith('.logan')) continue;
        if (name.endsWith('.uploaded')) continue;
        const p = dir + '/' + name;
        const code = await postFileBytes(c, p, d);
        if (code >= 200 && code < 300) {
          try { fs.renameSync(p, p + '.uploaded'); } catch { continue; }
          sent += 1;
          LoganCore.reopenToday();
        }
      }
    } finally {
      uploading = false;
    }
    return sent;
  }

  /** 应用画像自动登记（服务端 LoganAppController，凭据走 header，明文不落源码） */
  static async reportAppProfile(iconBase64?: string, mime?: string): Promise<number> {
    const c = ucfg;
    if (!c || !c.appId) return -1;
    return await new Promise<number>((resolve) => {
      const body: Record<string, string | number | undefined> = {
        'appId': c.appId, 'appName': c.appName, 'platform': c.platform, 'os': 'harmony',
        'appVersion': c.appVersion, 'buildVersion': c.buildVersion, 'deviceId': c.deviceId,
        'source': 'auto_client', 'iconBase64': iconBase64, 'mime': mime
      };
      const req = http.createHttp();
      const h: Record<string, string> = { 'Content-Type': 'application/json' };
      if (c.authHeader) { h['Authorization'] = c.authHeader; }
      req.request(c.baseUrl + '/logan/app/save.json', {
        method: http.RequestMethod.POST, header: h, extraData: JSON.stringify(body),
        connectTimeout: 10000, readTimeout: 15000
      }, (err, resp) => {
        req.destroy();
        if (err) { resolve(-3); return; }
        resolve(resp ? resp.responseCode : -4);
      });
    });
  }
}
