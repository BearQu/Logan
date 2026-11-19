/**
 * LoganAnrWatchdog.ets —— 鸿蒙主线程卡顿看门狗（对标 Bugly 收费版 ANR 大盘，对齐安卓
 * com.ccic.logan.crash.AnrWatchdog 与 iOS LoganAnrWatchdog 的判定语义）。
 *
 * 判定链（对齐安卓主线程 Handler 心跳 + 子线程轮询；鸿蒙 ArkTS 主线程是单事件循环，
 * 卡顿期间主线程定时器整体饿死——所以「事后补报」而非「事中轮询」）：
 *  1) 主线程 200ms 心跳自链 setTimeout：每次跑 tick++（卡顿开始瞬间最后一个 tick 后停走）；
 *  2) 主线程 250ms 轮询近似（同被饿死——但卡顿结束事件循环恢复的**第一轮**里它必跑）：
 *     checkOnce 读上次执行时刻与当前时刻差 gap、以及「自旋/霸占结束后 tick 恢复」事实：
 *     gap >= anrTimeoutMs 且本次卡顿未报过 → 判卡，补报 kind='anr' 封套（直传 + type99 落盘）。
 *     与安卓差异说明：安卓是卡顿进行中 5s 内即报；鸿蒙受限于主线程单循环，卡顿结束瞬间补报——
 *     对「卡顿 20s」这类真实 ANR 事件语义等价（事件本身照样落库/上大盘），PoC 权衡记录在此。
 *  3) 风暴抑制：同一指纹 300s 内只报一次；任意上报后 60s 冷却；崩溃通道刚处理过 15s 内避让
 *     （markCrashHandledForAnr 由 LoganCrash.recordCrash 尾部调用，防双报）。
 * 零新增依赖：复用 LoganCrash 的 envelope/postCrash 封套与上报通道。
 */
import hilog from '@ohos.hilog';
import hiAppEvent from '@ohos.hiviewdfx.hiAppEvent';
import bundleManager from '@ohos.bundle.bundleManager';
import fs from '@ohos.file.fs';
import { LoganCore } from './LoganCore';
import { CrashEnvelope } from './CrashEnvelope';
import { envelope, postCrash } from './LoganCrash';

const TAG = 'LoganAnr';
const DOMAIN = 0x0001;

const BEAT_INTERVAL_MS = 200;        // 主线程心跳
const POLL_INTERVAL_MS = 250;        // 轮询近似（饿死后恢复首轮必跑）
const SAME_FP_SUPPRESS_MS = 300000;  // 同指纹抑制 5min（对齐安卓 ANR_SAME_FP_SUPPRESS）
const ANY_REPORT_COOLDOWN_MS = 60000;// 任意上报后冷却 60s
const CRASH_QUIET_MS = 15000;        // 崩溃通道避让窗口 15s（对齐安卓 lastSignalAnrAt）

/** 崩溃/ANR 配置快照（由 LoganCrash.init 注入 getter，热读开关与阈值） */
export interface AnrConfig {
  anrWatchdog: boolean;
  anrTimeoutMs: number;
}

type GetCfg = () => AnrConfig;

let getCfg: GetCfg | null = null;
let started = false;
let beatTimer = 0;
let pollTimer = 0;
let lastTick = 0;          // 主线程心跳计数（最后推进到的值）
let lastTickAtMs = 0;      // 最后一次心跳时刻
let lastPollAtMs = 0;      // 上一次轮询执行时刻（恢复后第一轮判 gap 用）
let blockedOnce = false;   // 同一次卡顿只报一次；恢复后由「连续两轮心跳准点（非冻结）」才重置（实测教训：恢复后第一轮心跳抢先清标记会抹掉刚抓到的现场）
let lastFp = '';
let lastReportAtMs = 0;
let lastCrashHandledAtMs = 0;
let hiWatcher: hiAppEvent.AppEventPackageHolder | null = null;
let lastSysDumpLen = 0;       // 去重：同一份系统 dump 只报一次（按内容长度判同）

/** 由崩溃层在 recordCrash 尾部调用：崩溃刚处理，ANR 看门狗避让（防 signal/ANR 双报）。 */
export function markCrashHandledForAnr(): void {
  lastCrashHandledAtMs = Date.now();
}

/** 订阅 HiAppEvent OS 域 THREAD_BLOCK_3S/6S：系统冻结检测线程在卡顿现场把 dump 落盘，
 *  主线程恢复后回调投递——富化进我们的 tombstone，恢复瞬间立刻补报（事中证据通道）。 */
function subscribeSystemFreeze(): void {
  try {
    const watcher: hiAppEvent.Watcher = {
      name: 'LoganAnr',
      // 系统冻结事件实际发在 AAFWK 域（hilog 实证 EventLogger OnEvent: domain=AAFWK, eventName=THREAD_BLOCK_3S），
      // OS 域订阅收不到——OS 域留作 APP_FREEZE 兜底，两个域都订。
      appEventFilters: [
        { domain: 'AAFWK', names: ['THREAD_BLOCK_3S', 'THREAD_BLOCK_6S', 'THREAD_BLOCK_10S', 'APP_FREEZE'] },
        { domain: hiAppEvent.domain.OS, names: ['THREAD_BLOCK_3S', 'THREAD_BLOCK_6S', 'APP_FREEZE'] }
      ],
      onReceive: (domain: string, groups: Array<hiAppEvent.AppEventGroup>): void => {
        try {
          const cfg = getCfg ? getCfg() : null;
          if (!cfg || !cfg.anrWatchdog) return;
          let dumpText = '';
          hilog.info(DOMAIN, TAG, 'onReceive enter domain=%{public}s groups=%{public}d', domain, groups.length);
          for (const g of groups) {
            for (const e of g.appEventInfos) {
              const txt = JSON.stringify(e.params ?? {});
              if (txt.length > dumpText.length) dumpText = txt;
              hilog.info(DOMAIN, TAG, 'hiAppEvent %{public}s', String(e.name));
            }
          }
          if (!dumpText || dumpText.length === lastSysDumpLen) return;
          lastSysDumpLen = dumpText.length;
          const now = Date.now();
          const blockedMs = Math.max(cfg.anrTimeoutMs, now - lastTickAtMs);
          blockedOnce = true;
          lastReportAtMs = now;
          reportBlockedWithDump(blockedMs, dumpText);
        } catch (err) {
          hilog.warn(DOMAIN, TAG, 'onReceive throw: %{public}s', String(err));
        }
      }
    };
    hiWatcher = hiAppEvent.addWatcher(watcher);
    hilog.info(DOMAIN, TAG, 'hiAppEvent system-freeze watcher added');
  } catch (e) {
    hilog.warn(DOMAIN, TAG, 'addWatcher throw (no system sample channel): %{public}s', String(e));
  }
}

// HiAppEvent 通道专用：现场 dump 文本直接进 content（不走 readLatestFreezeDump 文件读）。
function reportBlockedWithDump(blockedMs: number, dumpText: string): void {
  const now = Date.now();
  hilog.info(DOMAIN, TAG, 'reportBlockedWithDump enter blockedMs=%{public}d dump=%{public}d', blockedMs, dumpText.length);
  if (lastCrashHandledAtMs > 0 && now - lastCrashHandledAtMs < CRASH_QUIET_MS) { hilog.warn(DOMAIN, TAG, 'skip: crash quiet window'); return; }
  blockedOnce = true;
  lastReportAtMs = now;
  const stack = new Error('logan-anr-sample').stack ?? '';
  const frames: string[] = [];
  for (const line of stack.split('\n')) {
    const t = line.trim();
    if (t.startsWith('at ')) frames.push(t);
    if (frames.length >= 16) break;
  }
  const content = 'Tombstone maker: logan-anr-watchdog\n'
    + 'Crash type: anr\n'
    + 'Anr duration: ' + String(blockedMs) + 'ms\n'
    + 'detection: HiAppEvent THREAD_BLOCK (system sampling thread)\n'
    + '"main" prio=5 tid=1 blocked\n'
    + frames.map((f) => '  + ' + f).join('\n')
    + '\n--- system freeze dump (HiAppEvent) ---\n' + dumpText.substring(0, 6000);
  let env: CrashEnvelope | null = null;
  try { env = envelope('anr', 'main-thread-blocked', 'app_freeze', content, false); } catch (e) {
    // envelope 里 cfg! 在非空断言下仍可能 undefined（init 竞态）——ArkTS 会 throw 而非 null
    hilog.warn(DOMAIN, TAG, 'hiAppEvent envelope throw (crash cfg not ready?): %{public}s', String(e));
    return;
  }
  if (!env) return;
  if (env.fingerprint === lastFp && now - lastReportAtMs < SAME_FP_SUPPRESS_MS) return;
  lastFp = env.fingerprint || '';
  if (LoganCore.isReady()) { LoganCore.w(99, JSON.stringify(env)); LoganCore.flush(); }
  postCrash(env);
  hilog.info(DOMAIN, TAG, 'anr reported (hiAppEvent channel) blockedMs=%{public}d', blockedMs);
}

function scheduleBeat(): void {
  // 卡顿开始瞬间最后一个 tick 后，主线程没机会再执行任何主线程 JS——
  // 「卡顿进行中」的事中上报必须借系统工作线程：hiAppEvent.setEventInfo 订阅
  // THREAD_BLOCK_3S/6S（事件检测跑在系统检测线程，进程还活着时先于 kill 回调我们），
  // 回调在主线程恢复后才投递——恢复瞬间把「系统已判卡 + 卡顿现场 dump 路径」组装上报，
  // 比事后心跳补报更准（阈值由系统判，我们只做证据富化），且与自有 5s 判定互为兜底。
  // 冻结标记必须打在**回调真正执行**的那一刻（setTimeout 在霸占期间到点也执行不了，
  // 注册时刻不算）：beatOverdueAtMs>0 即「注册后超过 2 个 beat 周期还没跑过」= 卡顿证据。
  // blockedOnce 不能由心跳清除（恢复后第一轮心跳先于 checkOnce 跑会把刚冻结的现场抹掉）。
  beatTimer = setTimeout(() => {
    const now = Date.now();
    // 冻结现场必须在这里、由心跳自己抓（恢复后轮询通道来不及判定，实测教训）。
    // blockedOnce 挡住轮询通道后，这里也必须能报——所以判定独立于轮询那份。
    if (beatDueAtMs > 0 && now >= beatDueAtMs) {
      const gap = now - (beatDueAtMs - BEAT_INTERVAL_MS * 2);
      const c = getCfg ? getCfg() : null;
      if (c && c.anrWatchdog && !blockedOnce && gap >= c.anrTimeoutMs) {
        hilog.info(DOMAIN, TAG, 'blocked detected in beat gap=%{public}d', gap);
        reportBlockedMs(gap, c);
      }
      beatDueAtMs = 0;
    }
    lastTick++;
    lastTickAtMs = now;
    // 冻结恢复确认：连续两轮准点（beatDueAtMs 到期判定没命中）才放开下一次卡顿的上报闸。
    if (beatDueAtMs > 0 && now < beatDueAtMs) { onTimeBeats++; } else { onTimeBeats = 0; }
    if (onTimeBeats >= 2) blockedOnce = false;
    scheduleBeat();
  }, BEAT_INTERVAL_MS);
  // 「注册未执行」到期标记：+2 拍宽限；霸占期间到点执行不了则保持到期态 = 冻结证据。
  beatDueAtMs = Date.now() + BEAT_INTERVAL_MS * 2;
}

// 「注册未执行」标记相关：见 scheduleBeat——beatDueAtMs 到期未跑即冻结证据，
// frozenBeatGapMs 恢复执行瞬间由心跳自己记下（真凭实据：回调真正执行的那一刻）。
let beatDueAtMs = 0;   // 本拍注册到期时刻（+2 拍宽限）；到期未执行=冻结证据，命中判定后清零
let onTimeBeats = 0;   // 连续准点数：>=2 才认定卡顿结束放开下一次上报闸

function checkOnce(): void {
  pollTimer = setTimeout(() => {
    try {
      const cfg = getCfg ? getCfg() : null;
      if (!cfg || !cfg.anrWatchdog) { scheduleCheck(); return; }
      const now = Date.now();
      // 判卡核心（对齐安卓 AnrWatchdog 事中判定语义）：
      //   事件循环被霸占 → 心跳与轮询一起饿死；恢复后第一轮里「tick 没推进 && now-lastTickAt >= 阈值」
      //   成立。安卓是卡顿进行中 5s 即报；鸿蒙受限于主线程单循环，改为
      //   **卡顿进行中用系统工作线程做采样上报**（见 scheduleSystemSample 挂 THREAD_BLOCK_3S 事件），
      //   自有心跳通道负责 6s 级真实卡顿（系统 THREAD_BLOCK_6S 杀进程前已抢先上报）与事后补报。
      const gap = now - lastPollAtMs;
      if (tickFrozen() && !blockedOnce && gap >= cfg.anrTimeoutMs && now - lastTickAtMs >= cfg.anrTimeoutMs) {
        const blockedMs = now - lastTickAtMs;
        hilog.info(DOMAIN, TAG, 'blocked detected blockedMs=%{public}d gap=%{public}d', blockedMs, gap);
        if (blockedMs >= cfg.anrTimeoutMs) {
          if (lastReportAtMs > 0 && now - lastReportAtMs < 30000) {
            // 系统采样通道已在卡顿进行中报过，事后补报只补「还没报出去」的
            hilog.info(DOMAIN, TAG, 'skip post-block: system sample channel reported %dms ago', now - lastReportAtMs);
          } else {
            reportBlockedMs(blockedMs, cfg);
          }
        }
      }
      lastPollAtMs = now;
    } catch (e) {
      hilog.warn(DOMAIN, TAG, 'checkOnce throw: %{public}s', String(e));
    }
    scheduleCheck();
  }, POLL_INTERVAL_MS);
}

// 心跳是否冻结：与上一次轮询相比 tick 没有推进（恢复后第一轮里，卡顿期间心跳零推进）。
let lastPollTickSnapshot = -1;
function tickFrozen(): boolean {
  if (lastPollTickSnapshot < 0) { lastPollTickSnapshot = lastTick; return false; }
  const frozen = lastTick === lastPollTickSnapshot;
  lastPollTickSnapshot = lastTick;
  return frozen;
}

function scheduleCheck(): void {
  checkOnce();
}

function reportBlockedMs(blockedMs: number, cfg: AnrConfig): void {
  const now = Date.now();
  if (now - lastReportAtMs < ANY_REPORT_COOLDOWN_MS) return;
  if (lastCrashHandledAtMs > 0 && now - lastCrashHandledAtMs < CRASH_QUIET_MS) {
    hilog.info(DOMAIN, TAG, 'skip: crash channel active (%{public}dms ago)', now - lastCrashHandledAtMs);
    return;
  }
  blockedOnce = true;
  lastReportAtMs = now;
  // 事中证据：系统冻结检测线程（HiAppEvent 订阅通道）在卡顿 3s/6s 已把现场 dump 落盘，
  // 读回来富化进 tombstone（对齐安卓子线程抓栈的事中语义）；主线程此刻已恢复，
  // 另附当前 JS 栈近似（对齐早期 Bugly 简化模式）。
  const sysDump = readLatestFreezeDump();
  const stack = new Error('logan-anr-sample').stack ?? '';
  const frames: string[] = [];
  for (const line of stack.split('\n')) {
    const t = line.trim();
    if (t.startsWith('at ')) frames.push(t);
    if (frames.length >= 16) break;
  }
  let content = 'Tombstone maker: logan-anr-watchdog\n'
    + 'Crash type: anr\n'
    + 'Anr duration: ' + String(blockedMs) + 'ms\n'
    + 'main thread state: running (post-block sample, ArkTS single-loop constraint)\n'
    + '"main" prio=5 tid=1 blocked\n'
    + frames.map((f) => '  + ' + f).join('\n');
  if (sysDump) {
    content += '\n--- system freeze dump (HiAppEvent) ---\n' + sysDump.substring(0, 6000);
  }
  let env: CrashEnvelope | null = null;
  try { env = envelope('anr', 'main-thread-blocked', 'app_freeze', content, false); } catch (e) {
    hilog.warn(DOMAIN, TAG, 'envelope throw (crash cfg not ready?): %{public}s', String(e));
    return;
  }
  if (!env) {
    hilog.warn(DOMAIN, TAG, 'envelope null (crash cfg missing?), skip');
    return;
  }
  // 指纹去重：同一处卡顿 5min 内只报一次（makeFingerprint 由 CrashEnvelope 提供确定性指纹）
  if (env.fingerprint === lastFp && now - lastReportAtMs < SAME_FP_SUPPRESS_MS) {
    hilog.info(DOMAIN, TAG, 'skip: same fingerprint within suppress window');
    return;
  }
  lastFp = env.fingerprint || '';
  // 先落盘再上报——上报走裸 HTTPS 异步，卡顿刚结束主线程可能再忙（对齐安卓先 tombstone 后 POST）
  if (LoganCore.isReady()) {
    LoganCore.w(99, JSON.stringify(env));
    LoganCore.flush();
  }
  postCrash(env);
  hilog.info(DOMAIN, TAG, 'anr reported blockedMs=%{public}d fp=%{public}s', blockedMs, env.fingerprint || '');
}

// 读系统冻结检测线程落的现场 dump（卡顿进行中由系统线程写入，主线程恢复后才读得到）。
// 目录权限受限时静默返回 ''（不影响自有判定，只是少一份现场）。
function readLatestFreezeDump(): string {
  const dirs: string[] = ['/data/log/eventlog/freeze', '/data/log/faultlog/freeze'];
  for (const d of dirs) {
    try {
      const names: string[] = fs.listFileSync(d);
      let best = '';
      let bestMtime = 0;
      for (const n of names) {
        if (!(n.includes('THREAD_BLOCK') || n.includes('appfreeze'))) continue;
        try {
          const st = fs.statSync(d + '/' + n);
          if (st.mtime * 1000 >= lastTickAtMs - BEAT_INTERVAL_MS && st.mtime * 1000 > bestMtime) {
            bestMtime = st.mtime * 1000;
            best = d + '/' + n;
          }
        } catch { /* 单文件失败跳过 */ }
      }
      if (!best) continue;
      try {
        const s = fs.readTextSync(best);
        if (s && s.length > 0) return s;
      } catch { /* 读不了继续下一目录 */ }
    } catch { /* 整目录无权访问，静默 */ }
  }
  return '';
}

/** 由 LoganCrash.init 尾部调用：启动看门狗（幂等；开关每次判定时热读）。 */
export function startAnrWatchdog(cfgGetter: GetCfg): void {
  if (started) return;
  const cfg = cfgGetter();
  if (!cfg.anrWatchdog) {
    hilog.info(DOMAIN, TAG, 'anr watchdog disabled by config');
    return;
  }
  started = true;
  getCfg = cfgGetter;
  lastTickAtMs = Date.now();
  lastPollAtMs = Date.now();
  hilog.info(DOMAIN, TAG, 'anr watchdog started timeout=%{public}dms (post-block detection)', cfg.anrTimeoutMs);
  scheduleBeat();
  checkOnce();
  subscribeSystemFreeze();
}
