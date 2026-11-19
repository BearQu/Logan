/*
 * LoganAnrWatchdog.m —— 见头文件。对齐 Android LoganCrash.AnrWatchdog 语义的 iOS 实现。
 */
#import "LoganAnrWatchdog.h"
#import "LoganCrashKit.h"
#import <mach/mach.h>
#import <pthread/pthread.h>
#import <stdatomic.h>
#import <dlfcn.h>
#import <string.h>

// 与 LoganCrashKit.m 风格一致：简单 volatile 标志即可（PoC 权衡，单写者读多者）。
#define LOGAN_ATOMIC_STORE(p, v) do { *(p) = (v); } while (0)
#define LOGAN_ATOMIC_EXCHANGE(p, v) ({ __typeof__(*(p)) __old = *(p); *(p) = (v); __old; })

// —— 与 LoganCrashKit.m 共用的三个符号。崩溃通道与本文件同在 LoganCrashKit target，
//    为避免动公开头文件，这里 extern 声明（C linkage）。
extern NSString *LoganSymbolicatePC(uintptr_t pc);            // LoganCrashKit.m
extern NSString *LoganEnvelope(NSString *kind, NSString *signal, NSArray<NSString *> *topFrames,
                               NSString *content, long long crashTime, BOOL deferred);
extern BOOL LoganPostSync(NSString *envelope);
extern void LoganWritePending(NSString *envelope);
extern NSString *LoganDjb2Hex(NSString *s);
extern NSString *LoganFpNormalize(NSString *frame);
extern NSUInteger LoganWatchdogSilenceWindowMs(void);          // 崩溃/信号通道最近一次处理时间窗（0=无）

static LoganCrashConfig *gWatchCfg = nil;
static volatile uint64_t gBeatTick = 0;        // 主线程心跳计数（主线程唯一写，看门狗读）
static volatile int gWatchStarted = 0;
static volatile int gWatchdogBusy = 0;         // 防重入：抓栈窗口内跳过轮询

static const int64_t kBeatIntervalMs = 200;     // 主线程心跳周期（同安卓 200ms）
static const int64_t kPollIntervalMs = 250;     // 看门狗轮询周期（同安卓 250ms）
static const int64_t kSameFpSuppressMs = 300000; // 同指纹风暴抑制 5min（同安卓）
static const int64_t kAnyReportCooldownMs = 60000; // 任意上报后 60s 静默（同安卓）
static const int64_t kCrashQuietMs = 15000;     // 崩溃通道刚处理过则静默（同安卓 xcrash 避让窗）

// 主线程心跳：dispatch_after 自链。主线程一旦卡在业务代码里，这个块排不进来，tick 停走。
static void LoganBeatSchedule(void) {
    dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(kBeatIntervalMs * NSEC_PER_MSEC)),
                   dispatch_get_main_queue(), ^{
        gBeatTick++;
        LoganBeatSchedule();
    });
}

// 抓主线程 PC 列表（看门狗线程调用，非信号上下文；thread_get_state 对挂起线程读取是安全的，
// 这里不挂起——读到瞬时旧帧可接受，PoC 权衡与早期 Bugly/KSCrash 简易模式一致）。
// 主线程 mach 端口：task_threads() 第一项即主线程（POSIX 保证线程创建顺序；iOS 无
// pthread_main_thread_np 公开声明，不依赖私有 API）。
static NSArray<NSString *> *LoganMainThreadSymbolizedFrames(void) {
    thread_act_array_t threads = NULL;
    mach_msg_type_number_t nthreads = 0;
    kern_return_t tkr = task_threads(mach_task_self(), &threads, &nthreads);
    if (tkr != KERN_SUCCESS || nthreads == 0) return @[];
    mach_port_t port = threads[0];  // 主线程
    for (mach_msg_type_number_t i = 1; i < nthreads; i++) {
        mach_port_deallocate(mach_task_self(), threads[i]);
    }
    vm_deallocate(mach_task_self(), (vm_address_t)threads, nthreads * sizeof(thread_act_t));
    if (!MACH_PORT_VALID(port)) return @[];
    arm_thread_state64_t state;
    memset(&state, 0, sizeof(state));
    mach_msg_type_number_t count = ARM_THREAD_STATE64_COUNT;
    kern_return_t kr = thread_get_state(port, ARM_THREAD_STATE64, (thread_state_t)&state, &count);
    if (kr != KERN_SUCCESS) return @[];
    // 只读栈内存取返回地址链太脆（帧指针未固定），PoC 采用安全下限：仅报 PC/LR 两帧 +
    // backtrace 语义由平台侧 AI 摘要补充。真实 iOS 全线程栈由后续接入 KSCrash 级别能力增强。
    NSMutableArray<NSString *> *frames = [NSMutableArray array];
    NSString *pcLine = LoganSymbolicatePC((uintptr_t)arm_thread_state64_get_pc(state));
    if (pcLine) [frames addObject:pcLine];
    NSString *lrLine = LoganSymbolicatePC((uintptr_t)arm_thread_state64_get_lr(state));
    if (lrLine && ![lrLine isEqualToString:pcLine]) [frames addObject:lrLine];
    return frames;
}

@interface LoganAnrWatchdog ()
@property (nonatomic, strong) NSThread *watchThread;
@property (nonatomic, assign) uint64_t lastTick;
@property (nonatomic, assign) int64_t lastTickAtMs;
@property (nonatomic, assign) BOOL blocked;          // 同一次卡顿只报一次
@property (nonatomic, copy, nullable) NSString *lastFp;
@property (nonatomic, assign) int64_t lastReportAtMs;
@end

static int64_t LoganNowMs(void) {
    return (int64_t)([[NSDate date] timeIntervalSince1970] * 1000);
}

@implementation LoganAnrWatchdog

+ (void)startWithConfig:(LoganCrashConfig *)config {
    if (!config.anrWatchdog) { NSLog(@"[logan][anr] disabled by config"); return; }
    if (LOGAN_ATOMIC_EXCHANGE(&gWatchStarted, 1)) return; // 幂等
    NSLog(@"[logan][anr] started timeout=%lldms", (long long)config.anrTimeoutMs);
    gWatchCfg = config;
    LoganBeatSchedule();
    LoganAnrWatchdog *w = [[LoganAnrWatchdog alloc] init];
    w.watchThread = [[NSThread alloc] initWithTarget:w selector:@selector(loop) object:nil];
    w.watchThread.name = @"logan-anr-watchdog";
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
    LoganCrashConfig *cfg = gWatchCfg;
    if (!cfg) return;
    if (LOGAN_ATOMIC_EXCHANGE(&gWatchdogBusy, 1)) return;
    @try {
        uint64_t t = gBeatTick;
        int64_t now = LoganNowMs();
        if (t != _lastTick) {
            _lastTick = t;
            _lastTickAtMs = now;
            _blocked = NO;
            return;
        }
        if (now - _lastTickAtMs < cfg.anrTimeoutMs) return;
        if (_blocked) return; // 本次卡顿已上报过（闸门在 reportBlockedMs 关、心跳恢复分支开）
        // 「同一次卡顿只报一次」闸门 _blocked 的置位**只在 reportBlockedMs 内、复查确要上报时**；
        // 若在入口置位，LoganPostSync 同步阻塞秒级期间每个 250ms 轮询都会重复触发判定，
        // 且静默窗/冷却 skip 分支留下的置位会把恢复后下一个真卡顿一起吞掉
        // （PoC 验收实证：首轮 pid 33597 因此零上报）。
        NSLog(@"[logan][anr] blocked detected tick idle %lldms", (long long)(now - _lastTickAtMs));
        if (now - (int64_t)LoganWatchdogSilenceWindowMs() < kCrashQuietMs) { NSLog(@"[logan][anr] skip: crash quiet window"); return; } // 崩溃通道刚处理过
        if (_lastFp && now - _lastReportAtMs < kAnyReportCooldownMs) { NSLog(@"[logan][anr] skip: cooldown"); return; }      // 风暴抑制
        [self reportBlockedMs:(now - _lastTickAtMs) cfg:cfg];
    } @finally {
        LOGAN_ATOMIC_STORE(&gWatchdogBusy, 0);
    }
}

- (void)reportBlockedMs:(int64_t)blockedMs cfg:(LoganCrashConfig *)cfg {
    NSArray<NSString *> *frames = LoganMainThreadSymbolizedFrames();
    // 抓栈窗口可能正好跨过卡顿恢复瞬间：复查 tick 仍在停走才继续上报，
    // 否则主线程已经活过来，这条 ANR 是误报，直接放弃。
    if (gBeatTick == _lastTick) { /* 仍然没心跳，确认卡死 */ } else { return; }
    // 「同一次卡顿只报一次」闸门在这里、确认要上报时才关（见 checkOnce 注释）；
    // 恢复后由「心跳重新推进」分支（checkOnce t != _lastTick）重新打开。
    _blocked = YES;
    NSMutableString *content = [NSMutableString string];
    [content appendString:@"*** *** *** *** *** *** *** *** *** *** *** *** *** *** *** ***\n"];
    [content appendString:@"Tombstone maker: 'logan-anr-watchdog'\n"];
    [content appendString:@"Crash type: 'anr'\n"];
    [content appendFormat:@"App ID: '%@'\n", cfg.appId ?: @""];
    [content appendFormat:@"App version: '%@'\n", cfg.appVersion ?: @""];
    [content appendFormat:@"Anr duration: '%lldms'\n", blockedMs];
    [content appendString:@"main thread state: 'running'\n\n"];
    [content appendString:@"\"main\" prio=5 tid=1 running\n"];
    for (NSString *f in frames) [content appendFormat:@"    at %@\n", f];
    long long ts = LoganNowMs();
    // 指纹种子绝不进 blockedMs/时间戳（否则永远聚不上），只 kind+主线程帧符号——LoganEnvelope 内部
    // 用 LoganFpNormalize 剥地址/数字，同卡顿点必然同指纹。
    NSMutableString *fpSeed = [NSMutableString stringWithString:@"anr|main-thread-blocked"];
    for (NSString *f in frames) { [fpSeed appendString:@"|"]; [fpSeed appendString:LoganFpNormalize(f)]; }
    NSString *fp = LoganDjb2Hex(fpSeed);
    if (_lastFp && [fp isEqualToString:_lastFp] && LoganNowMs() - _lastReportAtMs < kSameFpSuppressMs) return;
    NSString *env = LoganEnvelope(@"anr", @"main-thread-blocked", frames, content, ts, NO);
    if (!env) { NSLog(@"[logan][anr] envelope null, skip"); return; }
    _lastFp = fp;
    _lastReportAtMs = LoganNowMs();
    // 当场直传；失败落 pending 由下次冷启动 scanPending 补传（通道 B 复用崩溃层）
    BOOL sent = LoganPostSync(env);
    NSLog(@"[logan][anr] reported blockedMs=%lld fp=%@ post=%@", (long long)blockedMs, fp, sent ? @"ok" : @"pending");
    if (!sent) {
        extern void LoganWritePending(NSString *);
        LoganWritePending(env);
    }
}

@end
