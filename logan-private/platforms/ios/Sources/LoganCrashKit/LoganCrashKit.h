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
@end

@interface LoganCrashKit : NSObject
+ (void)initWithConfig:(LoganCrashConfig *)config;   // 幂等；装钩子 + 扫 pending 补传
+ (void)scanPending;                                  // 冷启动补传（init 尾部自动调，也可手工调）
@end

NS_ASSUME_NONNULL_END

