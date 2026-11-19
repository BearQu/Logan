/*
 * LoganAnrWatchdog —— iOS 主线程卡顿看门狗（对齐 Android LoganCrash.AnrWatchdog 语义）。
 *
 * 为什么要独立看门狗：iOS 没有 Android 的 SIGQUIT/ANR 信号钩子可抢，第三方 SDK（Bugly 等）
 * 也不会在主线程卡死时给你回调；主线程真卡死超过阈值，由我们自己在独立 runloop 上
 * 抓主线程堆栈、组 Tombstone、直传 /logan/crash/upload.json（kind=anr），
 * 保证概览页「ANR 率」大盘有采集端（对标 Bugly 收费版三大指标之一）。
 *
 * 语义与 Android 端逐项对齐：
 *  - 主线程 200ms 心跳（dispatch_after 链），看门狗在独立 NSThread 的 runloop 上轮询；
 *  - 心跳停超过 cfg.anrTimeoutMs 判定卡顿，同一次卡顿只报一次；
 *  - 风暴抑制：同指纹 5 分钟、任何上报后 60 秒静默；
 *  - 崩溃处理中（gCrashHandling）静默，避免与 signal handler 双报。
 * 零第三方依赖：主线程堆栈用 thread_get_all_ranges/`thread_suspend+backtrace` 太重，
 * PoC 采用第三方崩溃框架简易模式同款——从崩溃旁路线程调 `thread_get_state` 抓主线程
 * 64 帧 PC，再用与崩溃通道同一套 LoganSymbolicatePC 组行（非 async-signal-safe，
 * 但看门狗跑在普通线程，不是信号上下文，安全性远高于崩溃 handler）。
 */
#import <Foundation/Foundation.h>

@class LoganCrashConfig;

NS_ASSUME_NONNULL_BEGIN

@interface LoganAnrWatchdog : NSObject

/// 在 LoganCrashKit initWithConfig 尾部调用；cfg.anrWatchdog=NO 时直接返回。幂等。
+ (void)startWithConfig:(LoganCrashConfig *)config;

@end

NS_ASSUME_NONNULL_END
