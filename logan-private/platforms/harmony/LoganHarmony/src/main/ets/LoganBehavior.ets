/*
 * LoganBehavior —— 崩溃前行为轨迹采集（对齐 Android LoganBehavior.java / iOS LoganBehaviorKit.m）。
 *
 * 定位：崩溃堆栈只说清「死在哪一行」，说不清「用户是怎么走到这一行的」。本通道记录页面进出、
 * 点击、前后台切换与业务自定义节点，平台在证据包里按时间轴与业务日志、网络事件、非致命错误
 * 一起混排，形成「崩溃前到底发生了什么」的完整现场（对标 Bugly「崩溃前行为轨迹」）。
 *
 * 通用性（硬约束）：只用 HarmonyOS 公开框架 API，不依赖任何业务类与三方库：
 *   1) 前后台 —— ApplicationContext.on('abilityLifecycle') 的 onAbilityForeground/onAbilityBackground
 *   2) 页面轨迹 —— uiObserver.on('routerPageUpdate', context, cb)（官方 API，宿主零改动）；
 *      Navigation 形态的应用由宿主一行 LoganBehavior.pageShown(name) 补点，语义/队列/通道同源
 *   3) H5 —— 注入脚本经 LoganH5 代理回传（LoganHttp.reportH5Payload 的 type=behavior 分支）
 *   4) 显式入口 —— click/custom/dialog/report，业务想埋哪埋哪，零强制
 *
 * 开关（硬约束）：LoganBehavior.setEnabled(false) 一行关闭整条通道，默认开启自动采集。
 * 稳定性红线：所有采集点整体 try/catch，异常内部吞掉，绝不向业务抛出；队列满丢最旧。
 * 宁缺毋假：name/label/pageUrl 三要素全空的条目不入队；取不到的键整个不写。
 */
import hilog from '@ohos.hilog';
import { common, UIAbility } from '@kit.AbilityKit';
import AbilityLifecycleCallback from '@ohos.app.ability.AbilityLifecycleCallback';
import { window } from '@kit.ArkUI';
import { uiObserver } from '@kit.ArkUI';
import { LoganHttp } from './LoganHttp';

const TAG = 'LoganBehavior';
const QUEUE_MAX = 400;
const BATCH_MAX = 60;
const FLUSH_MS = 5000;
const DUP_WINDOW_MS = 400;
const NAME_MAX = 256;
const LABEL_MAX = 1000;
const PAGE_MAX = 1000;
const TRACE_MAX = 128;
const EXTRA_MAX = 4000;
const KINDS: string[] = ['page', 'click', 'custom', 'foreground', 'background', 'launch', 'exit', 'input', 'scroll', 'refresh', 'tab', 'dialog', 'other'];

export class LoganBehaviorEvent {
  kind: string = 'other';
  name: string = '';
  label: string = '';
  pageUrl: string = '';
  traceId: string = '';
  intervalMs: number = 0;
  extra: string = '';
  occurredAt: number = 0;
}

let queue: LoganBehaviorEvent[] = [];
let timer: number = -1;
let enabled: boolean = true;
let installed: boolean = false;
let launched: boolean = false;
let lastKey: string = '';
let lastAt: number = 0;

function norm(s: string | undefined | null, max: number): string {
  if (typeof s !== "string") { return ""; }
  const t = (s as string).trim();
  return t.length > max ? t.substring(0, max) : t;
}

function pickKind(kind: string | undefined | null): string {
  const k = norm(kind, 32).toLowerCase();
  return KINDS.indexOf(k) >= 0 ? k : "other";
}

class LoganBehaviorLifecycle extends AbilityLifecycleCallback {
  onAbilityCreate(ability: UIAbility): void {}
  onWindowStageCreate(ability: UIAbility, windowStage: window.WindowStage): void {}
  onWindowStageActive(ability: UIAbility, windowStage: window.WindowStage): void {}
  onWindowStageInactive(ability: UIAbility, windowStage: window.WindowStage): void {}
  onWindowStageDestroy(ability: UIAbility, windowStage: window.WindowStage): void {}
  onAbilityDestroy(ability: UIAbility): void {}
  onAbilityForeground(ability: UIAbility): void {
    try { LoganBehavior.raw("foreground", LoganBehavior.abilityName(ability), "", "", "", "", 0); } catch (e) {}
  }
  onAbilityBackground(ability: UIAbility): void {
    try {
      LoganBehavior.raw("background", LoganBehavior.abilityName(ability), "", "", "", "", 0);
      LoganBehavior.flushNow();
    } catch (e) {}
  }
  onAbilityContinue(ability: UIAbility): void {}
}

export class LoganBehavior {
  /** 一行开关（默认开启）。关闭 = 立即停止入队与上报，未发送轨迹直接丢弃。 */
  static setEnabled(v: boolean): void {
    enabled = v;
    if (!v) {
      queue = [];
      if (timer >= 0) { clearTimeout(timer); timer = -1; }
    }
  }

  static isEnabled(): boolean { return enabled; }

  /**
   * 安装自动采集钩子（幂等）。与网络/错误通道共用 LoganHttp 配置，宿主零额外接线。
   * @param ctx UIAbilityContext —— 用于 getApplicationContext() 挂前后台与 router 页面观察
   */
  static install(ctx?: common.UIAbilityContext): void {
    try {
      if (installed) { return; }
      installed = true;
      // H5 轨迹回传钩子（单向依赖：LoganHttp 不认识本模块，避免循环 import）
      LoganHttp.setH5BehaviorHandler((kind: string, name: string, label: string, pageUrl: string, traceId: string, extra: string, occurredAt: number): void => {
        LoganBehavior.raw(kind, name, label, pageUrl, traceId, extra, occurredAt);
      });
      if (ctx) { LoganBehavior.bindAbilityHooks(ctx); }
      LoganBehavior.raw('launch', 'app', "", "", "", 'cold=harmony', 0);
      launched = true;
      LoganBehavior.ensureTimer();
    } catch (e) {
      hilog.warn(0x00FF, TAG, 'install failed %{public}s', String(e));
    }
  }

  /** 显式入口：页面进入（Navigation 宿主一行补点，与 router 自动通道完全同源） */
  static pageShown(name: string, extra?: string): void {
    LoganBehavior.raw('page', name, "", "", "", extra ?? "", 0);
  }

  /** 显式入口：控件点击（label 用人话文案，如「提交理赔」） */
  static click(name: string, label?: string, extra?: string): void {
    LoganBehavior.raw('click', name, label ?? "", "", "", extra ?? "", 0);
  }

  /** 显式入口：业务自定义节点（弹窗、支付发起等里程碑） */
  static custom(name: string, label?: string, extra?: string): void {
    LoganBehavior.raw('custom', name, label ?? "", "", "", extra ?? "", 0);
  }

  /** 显式入口：完整字段版 */
  static report(kind: string, name: string, label?: string, pageUrl?: string, extra?: string): void {
    LoganBehavior.raw(kind, name, label ?? "", pageUrl ?? "", "", extra ?? "", 0);
  }

  /** 启动耗时（对标 Bugly 启动分析）：宿主在首帧回调处调用，intervalMs=EntryAbility.onCreate→首帧。
   *  走 behavior 通道落 kind='launch' name 固定（cold_start/hot_start），<=0 或 >60s 丢弃（对齐安卓/iOS）。 */
  static reportLaunch(name: string, intervalMs: number): void {
    if (intervalMs <= 0 || intervalMs > 60000) { return; }
    const n = norm(name, NAME_MAX);
    const e = new LoganBehaviorEvent();
    e.kind = 'launch';
    e.name = n.length > 0 ? n : 'cold_start';
    e.intervalMs = Math.floor(intervalMs);
    e.occurredAt = Date.now();
    const c = LoganHttp.config();
    if (!c || c.appId.length === 0 || c.deviceId.length === 0) { return; }
    queue.push(e);
    LoganBehavior.flushNow(); // 启动事件即时送达，不等攒批
  }

  /** H5 桥入口：注入脚本回传的真实轨迹 */
  static reportFromH5(kind: string, name: string, label: string, pageUrl: string, traceId: string, extra: string, occurredAt: number): void {
    LoganBehavior.raw(kind, name, label, pageUrl, traceId, extra, occurredAt);
  }

  /** 立即上报队列（宿主在退出/崩溃前主动收口用） */
  static flushNow(): void {
    if (timer >= 0) { clearTimeout(timer); timer = -1; }
    if (queue.length === 0) { return; }
    const c = LoganHttp.config();
    if (!c) { queue = []; return; }
    const batch: LoganBehaviorEvent[] = queue.splice(0, BATCH_MAX);
    const events: Record<string, Object>[] = [];
    for (const e of batch) {
      const b: Record<string, Object> = { 'kind': e.kind, 'occurredAt': e.occurredAt };
      if (e.name.length > 0) { b['name'] = e.name; }
      if (e.label.length > 0) { b['label'] = e.label; }
      if (e.pageUrl.length > 0) { b['pageUrl'] = e.pageUrl; }
      if (e.traceId.length > 0) { b['traceId'] = e.traceId; }
      if (e.intervalMs > 0) { b['intervalMs'] = e.intervalMs; }
      if (e.extra.length > 0) { b['extra'] = e.extra; }
      events.push(b);
    }
    const body: Record<string, Object> = {
      'appId': c.appId,
      'deviceId': c.deviceId,
      'platform': c.platform,
      'appVersion': c.appVersion,
      'buildVersion': c.buildVersion,
      'events': events
    };
    LoganHttp.post(c, '/logan/behavior/report.json', body);
  }

  // ---------- 内部 ----------

  // 生命周期回调类（LoganBehaviorLifecycle）在类外调用它们，ArkTS 的 private 不放过——
  // 2026-11-19 PoC 实测编译错 10505001，去掉 private 修饰（仍只在 SDK 内部使用）。
  static raw(kind: string, name: string, label: string, pageUrl: string, traceId: string, extra: string, occurredAt: number): void {
    try {
      if (!enabled) { return; }
      const e = new LoganBehaviorEvent();
      e.kind = pickKind(kind);
      e.name = norm(name, NAME_MAX);
      e.label = norm(label, LABEL_MAX);
      e.pageUrl = norm(pageUrl, PAGE_MAX);
      e.traceId = norm(traceId, TRACE_MAX);
      e.extra = norm(extra, EXTRA_MAX);
      // 三要素全空 → 丢弃，不造空数据
      if (e.name.length === 0 && e.label.length === 0 && e.pageUrl.length === 0) { return; }
      const c = LoganHttp.config();
      if (!c || c.appId.length === 0 || c.deviceId.length === 0) { return; }
      const now = Date.now();
      const key = e.kind + "|" + e.name + "|" + e.label;
      if (key === lastKey && now - lastAt < DUP_WINDOW_MS) { return; }
      lastKey = key;
      lastAt = now;
      e.occurredAt = occurredAt > 0 ? occurredAt : now;
      if (queue.length >= QUEUE_MAX) { queue.shift(); }
      queue.push(e);
      LoganBehavior.ensureTimer();
      if (queue.length >= BATCH_MAX) { LoganBehavior.flushNow(); }
    } catch (t) {
      hilog.warn(0x00FF, TAG, 'raw failed %{public}s', String(t));
    }
  }

  private static ensureTimer(): void {
    if (timer >= 0 || !enabled) { return; }
    timer = setTimeout(() => {
      timer = -1;
      try { LoganBehavior.flushNow(); } catch (e) {}
    }, FLUSH_MS);
  }

  /** 前后台 + 页面轨迹：全部走官方公开 API，一次绑定（9 个回调是官方接口的必选成员，空实现即不参与） */
  private static bindAbilityHooks(ctx: common.UIAbilityContext): void {
    try {
      const appCtx = ctx.getApplicationContext();
      const cb: AbilityLifecycleCallback = new LoganBehaviorLifecycle();
      appCtx.on('abilityLifecycle', cb);
    } catch (e) {
      hilog.warn(0x00FF, TAG, 'bind lifecycle failed %{public}s', String(e));
    }
    try {
      // router 形态应用自动拿页面轨迹；纯 Navigation 应用官方会抛错 → 降级为宿主 pageShown 补点
      uiObserver.on('routerPageUpdate', ctx, (info: uiObserver.RouterPageInfo): void => {
        try {
          if (!info || info.state !== uiObserver.RouterPageState.ABOUT_TO_APPEAR) { return; }
          const n = norm(info.name, NAME_MAX);
          const p = norm(info.path, PAGE_MAX);
          const nm = n.length > 0 ? n : ('router#' + String(info.index));
          LoganBehavior.raw('page', nm, "", p, "", 'via=router', 0);
        } catch (e) {}
      });
    } catch (e) {
      hilog.info(0x00FF, TAG, 'router observer unavailable %{public}s', String(e));
    }
  }

  static abilityName(ability: UIAbility): string {
    try {
      return ability && ability.context && ability.context.abilityInfo ? norm(ability.context.abilityInfo.name, NAME_MAX) : "app";
    } catch (e) {
      return "app";
    }
  }
}
