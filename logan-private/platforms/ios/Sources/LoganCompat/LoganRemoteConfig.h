//
//  LoganRemoteConfig.h
//  Logan PoC —— 远程配置（对标 Bugly 收费版「远程配置」），对齐 Android LoganRemoteConfig。
//
//  语义：平台「远程配置」页维护 appId 维度键值；本类本地 NSUserDefaults 缓存即时可读，
//  后台按增量拉取（带本地 version，服务端只回更大的行；removed 数组的键本地删除=墓碑下发）。
//  存储 = NSUserDefaults(suiteName logan_remote_config)，与安卓 SharedPreferences 同套键。
//  鉴权 = configure 时传入与崩溃/行为通道同一份 authHeader，否则被平台 nginx basic 拒收。
//
#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

typedef void (^LoganRemoteConfigChangeHandler)(NSDictionary<NSString *, NSString *> *configs);

@interface LoganRemoteConfig : NSObject

/// 与 LoganHttp/LoganCommands 同阶段配置一次（baseUrl/appId/deviceId 同值，authHeader 同一份）。
+ (void)configureWithBaseUrl:(NSString *)baseUrl
                       appId:(NSString *)appId
                    deviceId:(NSString *)deviceId
                  authHeader:(nullable NSString *)authHeader;

/// 键值变化回调（后台线程）；宿主据此即时生效（开关、阈值）。
+ (void)setOnChangeHandler:(nullable LoganRemoteConfigChangeHandler)handler;

/// 本地缓存即时读（不触发网络）。未命中返回 nil。
+ (nullable NSString *)stringForKey:(NSString *)key;
+ (BOOL)boolForKey:(NSString *)key defaultValue:(BOOL)def;
+ (long long)longLongForKey:(NSString *)key defaultValue:(long long)def;
+ (NSDictionary<NSString *, NSString *> *)allConfigs;
+ (long long)localVersion;

/// 后台刷新（30s 内重复调用自动跳过；force=YES 忽略节流——冷启动传 YES 保证装包后立刻拿到首轮配置）。
+ (void)refresh;
+ (void)refreshForcing:(BOOL)force;

/// 宿主自有定时器按此周期调用 refresh 即可（SDK 不自起常驻定时器，省流量）。
+ (NSTimeInterval)recommendedFetchInterval;

/// 由 LoganHttp start 调用的周期驱动（utility 队列定时器+kActiveWindow 停摆守卫）；
/// 宿主另有自己的驱动时可不依赖它。重复调用幂等。
+ (void)startPeriodicDrive;

@end

NS_ASSUME_NONNULL_END
