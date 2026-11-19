//
//  LoganRemoteConfig.m
//  Logan PoC —— 远程配置（对标 Bugly 收费版），对齐 Android LoganRemoteConfig。
//  零第三方依赖，仅 NSURLSession；网络与鉴权姿势同 LoganCommands。
//
#import "LoganRemoteConfig.h"

static NSString *gBaseUrl = nil;
static NSString *gAppId = nil;
static NSString *gDeviceId = nil;
static NSString *gAuthHeader = nil;
static LoganRemoteConfigChangeHandler gOnChange = nil;
static NSTimeInterval gLastFetchAt = 0;
static BOOL gFetching = NO;
static NSTimeInterval gLastAccessAt = 0;   // 宿主最近一次读取/显式刷新：后台周期拉取只为「有人在用」服务

static const NSTimeInterval kFetchInterval = 15 * 60;   // 推荐周期
static const NSTimeInterval kMinInterval = 30;          // 手动刷新节流
static const NSTimeInterval kActiveWindow = 30 * 60;    // 超过此窗口无人使用则停止后台拉取
static const NSTimeInterval kDriveInterval = 5 * 60;    // LoganHttp start 周期驱动检查节拍
static NSString *const kSuite = @"logan_remote_config";
static NSString *const kKeyVersion = @"logan_cfg_version";

@implementation LoganRemoteConfig

+ (NSUserDefaults *)suite {
    static NSUserDefaults *s = nil;
    static dispatch_once_t once;
    dispatch_once(&once, ^{
        s = [[NSUserDefaults alloc] initWithSuiteName:kSuite];
    });
    return s;
}

+ (void)configureWithBaseUrl:(NSString *)baseUrl
                       appId:(NSString *)appId
                    deviceId:(NSString *)deviceId
                  authHeader:(NSString *_Nullable)authHeader {
    @synchronized (self) {
        gBaseUrl = [baseUrl copy];
        gAppId = [appId copy];
        gDeviceId = [deviceId copy];
        gAuthHeader = [authHeader copy];
    }
}

+ (void)setOnChangeHandler:(LoganRemoteConfigChangeHandler)handler {
    @synchronized (self) { gOnChange = [handler copy]; }
}

+ (NSString *_Nullable)stringForKey:(NSString *)key {
    if (key.length == 0) return nil;
    gLastAccessAt = [[NSProcessInfo processInfo] systemUptime];
    id v = [[self suite] objectForKey:key];
    return [v isKindOfClass:NSString.class] ? (NSString *)v : nil;
}

+ (BOOL)boolForKey:(NSString *)key defaultValue:(BOOL)def {
    NSString *v = [self stringForKey:key];
    if (v == nil) return def;
    return [v isEqualToString:@"true"] || [v isEqualToString:@"1"];
}

+ (long long)longLongForKey:(NSString *)key defaultValue:(long long)def {
    NSString *v = [self stringForKey:key];
    if (v == nil) return def;
    return [v longLongValue];
}

+ (NSDictionary<NSString *, NSString *> *)allConfigs {
    gLastAccessAt = [[NSProcessInfo processInfo] systemUptime];
    NSMutableDictionary *out = [NSMutableDictionary dictionary];
    NSDictionary *all = [[self suite] dictionaryRepresentation];
    for (NSString *k in all) {
        if ([k isEqualToString:kKeyVersion]) continue;
        id v = all[k];
        if ([v isKindOfClass:NSString.class]) out[k] = v;
    }
    return out;
}

+ (long long)localVersion {
    return (long long)[[self suite] doubleForKey:kKeyVersion];
}

+ (NSTimeInterval)recommendedFetchInterval { return kFetchInterval; }

+ (void)startPeriodicDrive {
    // LoganHttp start 调用：SDK 不自建常驻线程语义例外——这是 LoganHttp 生命周期自带的
    // dispatch 定时器，每 kDriveInterval 检查一次，带 kActiveWindow 停摆守卫不空转。
    static dispatch_once_t once;
    dispatch_once(&once, ^{
        dispatch_source_t timer = dispatch_source_create(DISPATCH_SOURCE_TYPE_TIMER, 0, 0,
                                                          dispatch_get_global_queue(QOS_CLASS_UTILITY, 0));
        dispatch_source_set_timer(timer, dispatch_time(DISPATCH_TIME_NOW, (int64_t)(kDriveInterval * NSEC_PER_SEC)),
                                  (int64_t)(kDriveInterval * NSEC_PER_SEC), (int64_t)(60 * NSEC_PER_SEC));
        dispatch_source_set_event_handler(timer, ^{
            NSTimeInterval now = [[NSProcessInfo processInfo] systemUptime];
            BOOL active = NO;
            @synchronized (self) { active = (now - gLastAccessAt) <= kActiveWindow; }
            if (!active) return;
            [self refreshForcing:YES];
        });
        dispatch_resume(timer);
    });
}

+ (void)refresh { [self refreshForcing:NO]; }

+ (void)refreshForcing:(BOOL)force {
    NSString *baseUrl, *appId, *deviceId, *auth;
    LoganRemoteConfigChangeHandler cb;
    @synchronized (self) {
        baseUrl = gBaseUrl; appId = gAppId; deviceId = gDeviceId; auth = gAuthHeader; cb = gOnChange;
        if (baseUrl.length == 0 || appId.length == 0 || gFetching) return;
        if (force) {
            gLastAccessAt = [[NSProcessInfo processInfo] systemUptime];
        } else if ([[NSProcessInfo processInfo] systemUptime] - gLastAccessAt > kActiveWindow) {
            return; // 宿主近 30 分钟没有任何读取/显式刷新：不为无人使用的场景后台联网
        }
        if (!force && ([[NSProcessInfo processInfo] systemUptime] - gLastFetchAt) < kMinInterval) return;
        gFetching = YES;
    }
    long long since = [self localVersion];
    NSMutableURLRequest *req = [NSMutableURLRequest requestWithURL:[NSURL URLWithString:[baseUrl stringByAppendingString:@"/logan/config/get.json"]]];
    req.HTTPMethod = @"POST";
    req.timeoutInterval = 15;
    [req setValue:appId forHTTPHeaderField:@"appId"];
    if (deviceId.length > 0) [req setValue:deviceId forHTTPHeaderField:@"deviceId"];
    [req setValue:@"2" forHTTPHeaderField:@"platform"];
    [req setValue:@"0.1.0" forHTTPHeaderField:@"sdkVersion"];
    if (auth.length > 0) [req setValue:auth forHTTPHeaderField:@"Authorization"];
    [req setValue:@"application/json" forHTTPHeaderField:@"Content-Type"];
    req.HTTPBody = [NSJSONSerialization dataWithJSONObject:@{@"appId": appId, @"sinceVersion": @(since)} options:0 error:NULL];
    NSURLSession *session = [NSURLSession sessionWithConfiguration:[NSURLSessionConfiguration ephemeralSessionConfiguration]];
    [[session dataTaskWithRequest:req completionHandler:^(NSData *data, NSURLResponse *resp, NSError *error) {
        NSInteger code = [(NSHTTPURLResponse *)resp statusCode];
        NSDictionary *json = nil;
        if (data.length > 0 && code == 200) {
            id parsed = [NSJSONSerialization JSONObjectWithData:data options:0 error:NULL];
            if ([parsed isKindOfClass:NSDictionary.class]) json = parsed;
        } else {
            NSLog(@"[logan][config] http=%ld err=%@", (long)code, error.localizedDescription ?: @"-");
        }
        @synchronized (self) { gLastFetchAt = [[NSProcessInfo processInfo] systemUptime]; gFetching = NO; }
        NSInteger biz = [json[@"code"] integerValue];
        NSDictionary *d = [json[@"data"] isKindOfClass:NSDictionary.class] ? json[@"data"] : nil;
        if (biz != 200 || d == nil) return;
        NSUserDefaults *ud = [self suite];
        BOOL changed = NO;
        NSMutableDictionary *snapshot = [NSMutableDictionary dictionary];
        NSDictionary *configs = [d[@"configs"] isKindOfClass:NSDictionary.class] ? d[@"configs"] : nil;
        for (NSString *k in configs) {
            NSString *v = [configs[k] description];
            NSString *old = [ud objectForKey:k];
            if (![old isKindOfClass:NSString.class] || ![old isEqualToString:v]) {
                [ud setObject:v forKey:k];
                changed = YES;
            }
        }
        NSArray *removed = [d[@"removed"] isKindOfClass:NSArray.class] ? d[@"removed"] : nil;
        for (id rk in removed) {
            NSString *k = [rk isKindOfClass:NSString.class] ? rk : nil;
            if (k.length > 0 && [ud objectForKey:k] != nil) {
                [ud removeObjectForKey:k];
                changed = YES;
            }
        }
        long long serverVersion = [d[@"version"] longLongValue];
        if (serverVersion != [self localVersion]) {
            [ud setDouble:(double)serverVersion forKey:kKeyVersion];
            changed = YES;
        }
        [ud synchronize];
        if (changed && cb != nil) {
            dispatch_async(dispatch_get_global_queue(QOS_CLASS_DEFAULT, 0), ^{
                cb([self allConfigs]);
            });
        }
    }] resume];
}

@end
