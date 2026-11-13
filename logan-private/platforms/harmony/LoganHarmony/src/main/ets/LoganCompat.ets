/*
 * LoganCompat —— 存量日志零改动兼容（对齐 Android 编译期字节码改写的语义）。
 *
 * 核实事实：本 SDK(API12-26 d.ts) 的 @ohos.hilog 没有 hilog.on 订阅 API（官方 d.ts 全量
 * 函数表已核），所以鸿蒙端不做全局截获——目标工程 ccic-harmonyos 本就有自写 Log 封装，
 * 接入 = 在 Log 封装内部加一行 LoganCompat 双写（49 个调用点零改动），原 hilog 输出保留。
 * 双写非替换：LoganCompat 内部照常 hilog.info/warn/error 原样输出，宿主 Log 封装可只调这里。
 */
import hilog from '@ohos.hilog';
import { LoganCore } from './LoganCore';

const DOMAIN = 0x0001;
let enabled = true;
const lastMap = new Map<string, number>();

function dedup(key: string): boolean {
  const now = Date.now();
  const last = lastMap.get(key) ?? 0;
  if (now - last < 1000) return false;
  lastMap.set(key, now);
  return true;
}

export class LoganCompat {
  static setEnabled(on: boolean): void { enabled = on; }

  // type 映射与 Android 桥一致：v/d/i→2 w→3 e→4
  static d(tag: string, msg: string): void {
    hilog.debug(DOMAIN, tag, '%{public}s', msg);
    if (enabled && LoganCore.isReady() && dedup(tag + msg)) LoganCore.w(2, `D/${tag}: ${msg}`);
  }

  static i(tag: string, msg: string): void {
    hilog.info(DOMAIN, tag, '%{public}s', msg);
    if (enabled && LoganCore.isReady() && dedup(tag + msg)) LoganCore.w(2, `I/${tag}: ${msg}`);
  }

  static w(tag: string, msg: string): void {
    hilog.warn(DOMAIN, tag, '%{public}s', msg);
    if (enabled && LoganCore.isReady() && dedup(tag + msg)) LoganCore.w(3, `W/${tag}: ${msg}`);
  }

  static e(tag: string, msg: string): void {
    hilog.error(DOMAIN, tag, '%{public}s', msg);
    if (enabled && LoganCore.isReady() && dedup(tag + msg)) LoganCore.w(4, `E/${tag}: ${msg}`);
  }
}
