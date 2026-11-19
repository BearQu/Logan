/*
 * LoganLagWatchdog —— iOS 主线程卡顿采集（对标 Bugly 收费版卡顿大盘，对齐安卓 LoganCrash.LagWatchdog）。
 *
 * 与 LoganAnrWatchdog 的分工：共用同一条主线程心跳 gBeatTick（LoganBeatSchedule 只起一次，
 * 由 LoganAnrWatchdog startWithConfig 起，本看门狗启动时若还没起会补起——顺序无关）；
 * 心跳停走时长 ∈ [cfg.lagTimeoutMs, cfg.anrTimeoutMs) 归卡顿（kind='lag'），
 * >= anrTimeoutMs 归 ANR 通道——同一窗口两条通道互斥：
 *  - lag 侧见到时长已达 anrTimeoutMs 直接让位；
 *  - ANR 侧真报了（LoganMarkCrashHandled 同款全局静默窗 + gLastAnrReportedAtMs 避让窗）lag 不再补报。
 * 风暴抑制比 ANR 保守：任意上报后 120s 静默、同指纹 10 分钟（同卡顿点只报一条）。
 * 零第三方依赖，抓栈复用 LoganMainThreadSymbolizedFrames 同款姿势（thread_get_state 读 PC/LR）。
 */
#import <Foundation/Foundation.h>

@class LoganCrashConfig;

NS_ASSUME_NONNULL_BEGIN

@interface LoganLagWatchdog : NSObject

/// 在 LoganCrashKit initWithConfig 尾部调用（ANR 看门狗之后）；cfg.lagWatchdog=NO 或阈值非法直接返回。幂等。
+ (void)startWithConfig:(LoganCrashConfig *)config;

@end

NS_ASSUME_NONNULL_END
