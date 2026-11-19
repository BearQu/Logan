/*
 * LoganCrashKit —— iOS 崩溃层（对齐 Android logan-crash 契约）。
 *
 * 捕获：signal(SIGSEGV/SIGABRT/SIGBUS/SIGILL/SIGTRAP/SIGFPE) + NSSetUncaughtExceptionHandler。
 * 上报：通道A 当场直传 POST /logan/crash/upload.json（gzip + Content-Encoding gzip）；
 *       通道B 崩溃时先落 pending 文件，下次启动 scanPending 补传（2xx 才删）。
 * 共存：安装前保存原 handler 并在本 handler 尾部链式回调（与 Bugly 等互不吞钩）。
 * 封包字段与 fingerprint 算法对齐 Android CrashEnvelope（kind|signal|异常类型|top3 帧，djb2 hex:length）。
 */
#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

@interface LoganCrashConfig : NSObject
@property (nonatomic, copy) NSString *baseUrl;          // 例 https://host/logan-web
@property (nonatomic, copy) NSString *appId;
@property (nonatomic, copy, nullable) NSString *appVersion;
@property (nonatomic, copy, nullable) NSString *buildVersion;
@property (nonatomic, copy, nullable) NSString *deviceId;
@property (nonatomic, copy, nullable) NSString *unionId;
@property (nonatomic, copy, nullable) NSString *userId;
@property (nonatomic, copy, nullable) NSString *authHeader;   // 例 Basic xxx（无则不带）
@property (nonatomic, assign) NSUInteger maxTombstoneBytes;   // 默认 512KB
/// 主线程卡顿看门狗（对标 Bugly 收费版 ANR 大盘的采集端）；独立开关，关掉即零开销。
@property (nonatomic, assign) BOOL anrWatchdog;               // 默认 YES（对齐安卓 CrashConfig.anrWatchdog）
@property (nonatomic, assign) NSUInteger anrTimeoutMs;        // 心跳停多久判定卡顿，默认 5000（对齐安卓）
/// 主线程卡顿采集（对标 Bugly 收费版卡顿大盘）；心跳停走 ∈[lagTimeoutMs, anrTimeoutMs) 抓栈上报 kind='lag'。
@property (nonatomic, assign) BOOL lagWatchdog;               // 默认 YES（对齐安卓 CrashConfig.lagWatchdog）
@property (nonatomic, assign) NSUInteger lagTimeoutMs;        // 卡顿判定阈值，默认 500（须 < anrTimeoutMs；对齐安卓）
@end

/// 距最近一次崩溃/信号处理完成已过去多少 ms（0=从未）。ANR 看门狗避让双报用。
FOUNDATION_EXPORT NSUInteger LoganWatchdogSilenceWindowMs(void);
/// 崩溃/异常处理完成后标记（同 LoganWatchdogSilenceWindowMs 配套；供外部通道对齐语义）。
FOUNDATION_EXPORT void LoganMarkCrashHandled(void);

@interface LoganCrashKit : NSObject
+ (void)initWithConfig:(LoganCrashConfig *)config;   // 幂等；装钩子 + 扫 pending 补传
+ (void)scanPending;                                  // 冷启动补传（init 尾部自动调，也可手工调）
@end

NS_ASSUME_NONNULL_END

