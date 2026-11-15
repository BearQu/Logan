/*
 * LoganCore —— clogan 内核 ArkTS 门面（napi 单例），与 Android Logan.java / iOS Logan.m 同语义。
 * 注意：非多进程安全（同官方），应用主 Ability 初始化一次。
 */
import napi from 'liblogan_napi.so';

const AES_KEY = '0123456789012345';
const AES_IV = '0123456789012345';

export interface LogFileInfo {
  name: string;
  size: number;
}

let inited = false;
let cacheDir = '';
let logDir = '';

function todayName(): string {
  const d = new Date();
  const m = (d.getMonth() + 1).toString().padStart(2, '0');
  const day = d.getDate().toString().padStart(2, '0');
  return d.getFullYear() + '-' + m + '-' + day + '.logan';
}

function parseList(json: string): LogFileInfo[] {
  try {
    const arr = JSON.parse(json) as LogFileInfo[];
    return Array.isArray(arr) ? arr : [];
  } catch {
    return [];
  }
}

function parseNames(json: string): string[] {
  try {
    const arr = JSON.parse(json) as string[];
    return Array.isArray(arr) ? arr : [];
  } catch {
    return [];
  }
}

export class LoganCore {
  /** 与官方同规则：按天一个 .logan 文件，mmap 缓存目录 + 日志目录分离 */
  static init(filesDir: string, maxFile?: number): number {
    if (inited) return 0;
    cacheDir = filesDir + '/logan_cache';
    logDir = filesDir + '/logan_logs';
    const max = maxFile ?? 5 * 1024 * 1024;
    const r: number = napi.loganInit(cacheDir, logDir, max, AES_KEY, AES_IV) as number;
    // clogan_open 内部是 _dir_path + pathname 拼接（与官方 Logan.open 同语义）→ 必须只传文件名，传绝对路径会拼出不存在的嵌套目录
    napi.loganOpen(todayName()) as number;
    inited = true;
    return r;
  }

  /** flag 与官方 type 同义：2 常规 3 警告 4 错误 99 崩溃证据 */
  static w(flag: number, content: string): void {
    if (!inited) return;
    napi.loganWrite(flag, Date.now(), content) as number;
  }

  static flush(): void {
    if (!inited) return;
    napi.loganFlush() as number;
  }

  static listLogFiles(): LogFileInfo[] {
    if (!logDir) return [];
    return parseList(napi.listLogFiles(logDir) as string);
  }

  static logDirPath(): string { return logDir; }

  static deleteFile(path: string): void { napi.deleteFile(path) as number; }

  static readFileText(path: string): string { return napi.readFileText(path) as string; }

  /** 通用目录列举（faultlogger 补读用） */
  static listFilesInDir(dir: string): string[] {
    try { return parseNames(napi.listDir(dir) as string); } catch { return []; }
  }

  static writeSeenList(path: string, json: string): void { napi.writeFileText(path, json) as number; }

  /** 上传改名当天文件后重新打开当天文件（mmap 不跟随改名，必须 reopen 否则丢日志） */
  static reopenToday(): void {
    if (!inited || !logDir) return;
    napi.loganOpen(todayName()) as number;
  }

  /** 真实 native 崩溃探针（napi 写空指针） */
  static testNativeCrash(): void { napi.testCppCrash(); }

  static isReady(): boolean { return inited; }
}
