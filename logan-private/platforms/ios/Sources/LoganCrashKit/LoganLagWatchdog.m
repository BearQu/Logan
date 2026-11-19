/*
 * LoganLagWatchdog.m —— 见头文件。对齐 Android LoganCrash.LagWatchdog 语义的 iOS 实现。
 * 心跳/抓栈/封包与 LoganAnrWatchdog.m 同一套符号（同 target，C linkage extern 声明，不动公开头文件）。
 */
#import "LoganLagWatchdog.h"
#import "LoganCrashKit.h"
#import <mach/mach.h>
#import <stdatomic.h>
#import <string.h>

// 与 LoganAnrWatchdog.m 风格一致：简单 volatile 标志即可（PoC 权衡，单写者读多者）。
#define LOGAN_ATOMIC_STORE(p, v) do { *(p) = (v); } while (0)
#define LOGAN_ATOMIC_EXCHANGE(p, v) ({ __typeof__(*(p)) __old = *(p); *(p) = (v); __old; })

// —— 与 LoganCrashKit.m / LoganAnrWatchdog.m 共用的符号（C linkage）。
extern NSString *LoganSymbolicatePC(uintptr_t pc);            // LoganCrashKit.m
extern NSString *LoganEnvelope(NSString *kind, NSString *signal, NSArray<NSString *> *topFrames,
                               NSString *content, long long crashTime, BOOL deferred);
extern BOOL LoganPostSync(NSString *envelope);                // LoganCrashKit.m
extern void LoganWritePending(NSString *envelope);            // LoganCrashKit.m
extern NSString *LoganDjb2Hex(NSString *s);                   // LoganCrashKit.m
extern NSString *LoganFpNormalize(NSString *frame);           // LoganCrashKit.m
extern NSUInteger LoganWatchdogSilenceWindowMs(void);         // LoganCrashKit.m（崩溃通道避让窗）
// ANR 通道避让：ANR 看门狗最近一次上报时刻（ms，0=从未）。LoganAnrWatchdog.m 导出。
extern int64_t LoganAnrLastReportedAtMs(void);
// 主线程心跳自链（LoganAnrWatchdog.m 导出；本看门狗启动时若 ANR 侧未起，补起同一条心跳）。
extern void LoganBeatSchedule(void);
// 主线程心跳计数（LoganAnrWatchdog.m 全局；两边共用，勿另起一条）。
extern volatile uint64_t gBeatTick;
// 主线程 PC/LR 符号化帧（LoganAnrWatchdog.m 导出；两边共用同一抓栈姿势）。
extern NSArray<NSString *> *LoganMainThreadSymbolizedFrames(void);

static LoganCrashConfig *gLagCfg = nil;
static volatile int gLagStarted = 0;
static volatile int gLagBusy = 0;             // 防重入：抓栈窗口内跳过轮询

static const int64_t kPollIntervalMs = 250;        // 轮询周期（同 ANR/安卓）
static const int64_t kSameFpSuppressMs = 600000;   // 同指纹风暴抑制 10min（比 ANR 保守）
static const int64_t kAnyReportCooldownMs = 120000; // 任意上报后 120s 静默（比 ANR 保守）
static const int64_t kAnrQuietMs = 15000;          // ANR 通道刚报过则同一窗口让位（同安卓 15s 避让）
static const int64_t kCrashQuietMs = 15000;        // 崩溃通道刚处理过则静默（同 ANR）

static int64_t LoganNowMs(void) {
    return (int64_t)([[NSDate date] timeIntervalSince1970] * 1000);
}

@interface LoganLagWatchdog ()
@property (nonatomic, strong) NSThread *watchThread;
@property (nonatomic, assign) uint64_t lastTick;
@property (nonatomic, assign) int64_t lastTickAtMs;
@property (nonatomic, assign) BOOL blocked;          // 同一次卡顿只报一次（闸门在确认上报处置位）
@property (nonatomic, copy, nullable) NSString *lastFp;
@property (nonatomic, assign) int64_t lastReportAtMs;
@end

@implementation LoganLagWatchdog

+ (void)startWithConfig:(LoganCrashConfig *)config {
    if (!config.lagWatchdog) { NSLog(@"[logan][lag] disabled by config"); return; }
    if (config.lagTimeoutMs == 0 || config.lagTimeoutMs >= config.anrTimeoutMs) {
        NSLog(@"[logan][lag] invalid thresholds lag=%llu anr=%llu, skip",
              (unsigned long long)config.lagTimeoutMs, (unsigned long long)config.anrTimeoutMs);
        return;
    }
    if (LOGAN_ATOMIC_EXCHANGE(&gLagStarted, 1)) return; // 幂等
    NSLog(@"[logan][lag] started timeout=%llums", (unsigned long long)config.lagTimeoutMs);
    gLagCfg = config;
    LoganBeatSchedule();   // 心跳自链幂等（ANR 看门狗若已开，重复调用安全：tick 只加一）
    LoganLagWatchdog *w = [[LoganLagWatchdog alloc] init];
    w.watchThread = [[NSThread alloc] initWithTarget:w selector:@selector(loop) object:nil];
    w.watchThread.name = @"logan-lag-watchdog";
    [w.watchThread start];
}

- (instancetype)init {
    if (self = [super init]) {
        _lastTick = 0;
        _lastTickAtMs = LoganNowMs();
        _blocked = NO;
        _lastReportAtMs = 0;
    }
    return self;
}

- (void)loop {
    @autoreleasepool {
        NSRunLoop *rl = [NSRunLoop currentRunLoop];
        [rl addPort:[NSMachPort port] forMode:NSDefaultRunLoopMode];
        while (YES) {
            [rl runMode:NSDefaultRunLoopMode beforeDate:[NSDate dateWithTimeIntervalSinceNow:(kPollIntervalMs / 1000.0)]];
            [self checkOnce];
        }
    }
}

- (void)checkOnce {
    LoganCrashConfig *cfg = gLagCfg;
    if (!cfg) return;
    if (LOGAN_ATOMIC_EXCHANGE(&gLagBusy, 1)) return;
    @try {
        uint64_t t = gBeatTick;
        int64_t now = LoganNowMs();
        if (t != _lastTick) {
            _lastTick = t;
            _lastTickAtMs = now;
            _blocked = NO;
            return;
        }
        int64_t idle = now - _lastTickAtMs;
        if (idle < (int64_t)cfg.lagTimeoutMs) return;   // 还没到卡顿阈值
        if (idle >= (int64_t)cfg.anrTimeoutMs) return;  // 已达 ANR 阈值：让位 ANR 通道，不双报
        if (_blocked) return;                            // 本次卡顿已上报过
        // 热路径不打诊断（NSLog 不进模拟器统一日志，取证一律以 DB 行为准——PoC 教训）
        if (now - (int64_t)LoganWatchdogSilenceWindowMs() < kCrashQuietMs) { return; }
        if (now - LoganAnrLastReportedAtMs() < kAnrQuietMs) { return; }
        if (_lastFp && now - _lastReportAtMs < kAnyReportCooldownMs) { return; }
        [self reportBlockedMs:idle cfg:cfg];
    } @finally {
        LOGAN_ATOMIC_STORE(&gLagBusy, 0);
    }
}

- (void)reportBlockedMs:(int64_t)blockedMs cfg:(LoganCrashConfig *)cfg {
    NSArray<NSString *> *frames = LoganMainThreadSymbolizedFrames();
    // 抓栈窗口跨过恢复瞬间的复查：tick 恢复说明只是临界抖动，误报直接放弃。
    if (gBeatTick == _lastTick) { /* 仍然没心跳，确认卡顿未恢复 */ } else { return; }
    _blocked = YES;   // 「同一次卡顿只报一次」闸门只在确认上报处关闭，心跳恢复分支重开（同 ANR 教训）
    NSMutableString *content = [NSMutableString string];
    [content appendString:@"*** *** *** *** *** *** *** *** *** *** *** *** *** *** *** ***\n"];
    [content appendString:@"Tombstone maker: 'logan-lag-watchdog'\n"];
    [content appendString:@"Crash type: 'lag'\n"];
    [content appendFormat:@"App ID: '%@'\n", cfg.appId ?: @""];
    [content appendFormat:@"App version: '%@'\n", cfg.appVersion ?: @""];
    [content appendFormat:@"Lag duration: '%lldms'\n", blockedMs];
    [content appendString:@"main thread state: 'running'\n\n"];
    [content appendString:@"\"main\" prio=5 tid=1 running\n"];
    for (NSString *f in frames) [content appendFormat:@"    at %@\n", f];
    long long ts = LoganNowMs();
    // 指纹种子绝不进 blockedMs/时间戳；只 kind+主线程帧符号（LoganFpNormalize 剥地址/数字）——同卡顿点同指纹。
    NSMutableString *fpSeed = [NSMutableString stringWithString:@"lag|main-thread-blocked"];
    for (NSString *f in frames) { [fpSeed appendString:@"|"]; [fpSeed appendString:LoganFpNormalize(f)]; }
    NSString *fp = LoganDjb2Hex(fpSeed);
    if (_lastFp && [fp isEqualToString:_lastFp] && LoganNowMs() - _lastReportAtMs < kSameFpSuppressMs) return;
    NSString *env = LoganEnvelope(@"lag", @"main-thread-blocked", frames, content, ts, NO);
    if (!env) { NSLog(@"[logan][lag] envelope null, skip"); return; }
    _lastFp = fp;
    _lastReportAtMs = LoganNowMs();
    BOOL sent = LoganPostSync(env);
    NSLog(@"[logan][lag] reported blockedMs=%lld fp=%@ post=%@", (long long)blockedMs, fp, sent ? @"ok" : @"pending");
    if (!sent) LoganWritePending(env);
}

@end
