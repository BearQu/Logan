/*
 * CrashEnvelope —— 与 Android logan-crash 同一 schema：同一后端 handler 零改动吃两端数据。
 * fingerprint 确定性聚类算法一致：kind|signal|异常类型|顶3业务帧。
 */
export interface CrashEnvelope {
  kind: string;          // js | cpp | freeze
  crashTime: number;
  deferred: boolean;     // true = 下次启动补传（faultLogger 通道）
  appId: string;
  appVersion: string;
  buildVersion: string;
  deviceId: string;
  unionId: string;
  userId: string;
  brand: string;
  model: string;
  sdkInt: number;
  release: string;
  fingerprint: string;
  topFrames: string[];
  signal: string;
  content: string;
  /** 平台标识：服务端 LoganCrashServiceImpl.platformOf 优先读显式 platform 数字，os 作兜底（harmony→3） */
  os: string;
  platform: number;
}

const SKIP_PREFIXES = ['at Function.', 'app Spawn', '-----', 'Device info', 'Fault thread info', 'Tid:', 'Last message', 'Back traces'];

/** 从任意崩溃文本提顶 3 业务帧（对齐 Android：跳过系统帧），全抓不到则退异常首行 */
export function extractTopFrames(text: string): string[] {
  const frames: string[] = [];
  for (const raw of text.split('\n')) {
    const line = raw.trim();
    if (!line) continue;
    if (line.startsWith('at ') && !line.includes('ohos.') && !line.includes('internal.') && !line.includes('@ohos.')) {
      frames.push(line);
      if (frames.length >= 3) break;
    }
  }
  if (frames.length === 0) {
    for (const raw of text.split('\n')) {
      const line = raw.trim();
      if (line && !SKIP_PREFIXES.some(p => line.startsWith(p))) { frames.push(line.slice(0, 200)); break; }
    }
  }
  return frames;
}

/** 确定性 fingerprint（与 Java CrashEnvelope.fingerprint 同规则） */
export function makeFingerprint(kind: string, signal: string, excType: string, topFrames: string[]): string {
  return kind + '|' + signal + '|' + excType + '|' + topFrames.join('<');
}
