//
//  LoganHttp.m
//  Logan PoC —— 业务网络监控上报通道实现（零第三方依赖）。
//
#import "LoganHttp.h"
#import "LoganHttpProtocol.h"

static NSString *gHBaseUrl = nil;
static NSString *gHAppId = nil;
static NSString *gHDeviceId = nil;
static NSString *gHAppVersion = nil;
static NSString *gHBuildVersion = nil;
static NSString *gHAuthHeader = nil;
static long long gHSlowMs = 3000;
static BOOL gHEnabled = YES;
static BOOL gHStarted = NO;
static NSMutableArray<LoganHttpEvent *> *gHQueue = nil;
static NSLock *gHLock = nil;
static NSMutableArray<NSString *> *gHExcludeHosts = nil;
static NSMutableArray<NSString *> *gHExcludeSubs = nil;
static BOOL (^gHObserveFilter)(NSURLRequest *) = nil;
static NSTimer *gHFlushTimer = nil;
static NSString *const kHReportPath = @"/logan/http/report.json";
static NSUInteger const kHQueueMax = 500;
static NSUInteger const kHBatch = 50;

static NSString *LoganTrim(NSString *s) {
    if (s.length == 0) { return nil; }
    NSString *t = [s stringByTrimmingCharactersInSet:[NSCharacterSet whitespaceAndNewlineCharacterSet]];
    return t.length > 0 ? t : nil;
}

@implementation LoganHttpEvent

- (NSDictionary *)toJsonDictionary {
    NSMutableDictionary *o = [NSMutableDictionary dictionary];
    o[@"kind"] = self.kind.length > 0 ? self.kind : @"native";
    if (self.method.length > 0) { o[@"method"] = self.method; }
    o[@"url"] = self.url ?: @"";
    if (self.host.length > 0) { o[@"host"] = self.host; }
    if (self.path.length > 0) { o[@"path"] = self.path; }
    o[@"status"] = @(self.status);
    if (self.errKind.length > 0) { o[@"errKind"] = self.errKind; }
    if (self.errMsg.length > 0) { o[@"errMsg"] = self.errMsg.length > 2000 ? [self.errMsg substringToIndex:2000] : self.errMsg; }
    o[@"durationMs"] = @(self.durationMs);
    if (self.connectMs) { o[@"connectMs"] = self.connectMs; }
    if (self.dnsMs) { o[@"dnsMs"] = self.dnsMs; }
    if (self.ttfbMs) { o[@"ttfbMs"] = self.ttfbMs; }
    if (self.reqBytes) { o[@"reqBytes"] = self.reqBytes; }
    if (self.respBytes) { o[@"respBytes"] = self.respBytes; }
    o[@"slow"] = @(self.slow);
    if (self.pageUrl.length > 0) { o[@"pageUrl"] = self.pageUrl; }
    if (self.traceId.length > 0) { o[@"traceId"] = self.traceId; }
    if (self.os.length > 0) { o[@"os"] = self.os; }
    if (self.osVersion.length > 0) { o[@"osVersion"] = self.osVersion; }
    if (self.brand.length > 0) { o[@"brand"] = self.brand; }
    if (self.model.length > 0) { o[@"model"] = self.model; }
    if (self.networkType.length > 0) { o[@"networkType"] = self.networkType; }
    if (self.extra.length > 0) { o[@"extra"] = self.extra.length > 4000 ? [self.extra substringToIndex:4000] : self.extra; }
    if (self.occurredAt > 0) { o[@"occurredAt"] = @(self.occurredAt); }
    return o;
}

@end

@implementation LoganHttp

+ (instancetype)shared {
    static LoganHttp *instance;
    static dispatch_once_t once;
    dispatch_once(&once, ^{
        instance = [LoganHttp new];
        gHQueue = [NSMutableArray array];
        gHExcludeHosts = [NSMutableArray array];
        gHExcludeSubs = [NSMutableArray array];
        gHLock = [NSLock new];
    });
    return instance;
}

- (instancetype)configureWithBaseUrl:(NSString *)baseUrl
                               appId:(NSString *)appId
                            deviceId:(NSString *)deviceId
                          appVersion:(NSString *)appVersion
                        buildVersion:(NSString *)buildVersion
                          authHeader:(NSString *)authHeader {
    @synchronized (self) {
        NSString *b = LoganTrim(baseUrl);
        if ([b hasSuffix:@"/"]) { b = [b substringToIndex:b.length - 1]; }
        gHBaseUrl = b;
        gHAppId = LoganTrim(appId);
        gHDeviceId = LoganTrim(deviceId);
        gHAppVersion = LoganTrim(appVersion);
        gHBuildVersion = LoganTrim(buildVersion);
        gHAuthHeader = LoganTrim(authHeader);
    }
    return self;
}

- (LoganHttp *)setSlowThresholdMs:(long long)ms {
    @synchronized (self) { gHSlowMs = ms > 0 ? ms : 3000; }
    return self;
}

- (LoganHttp *)setEnabled:(BOOL)on {
    gHEnabled = on;
    if (!on) { [self flushNow]; }
    return self;
}

- (BOOL)isEnabled { return gHEnabled; }

- (void)start {
    if (!gHEnabled || gHStarted) { return; }
    gHStarted = YES;
    @try { [LoganHttpProtocol installObserver]; } @catch (NSException *e) {}
    gHFlushTimer = [NSTimer scheduledTimerWithTimeInterval:5.0
                                                    target:self
                                                  selector:@selector(flushNow)
                                                  userInfo:nil
                                                   repeats:YES];
}

- (void)report:(LoganHttpEvent *)event {
    if (!gHEnabled || event.url.length == 0) { return; }
    if ([self isSelfUrl:event.url]) { return; }
    if (event.occurredAt <= 0) {
        event.occurredAt = (long long)([[NSDate date] timeIntervalSince1970] * 1000);
    }
    if (!event.slow && event.status < 400 && event.durationMs >= gHSlowMs) {
        event.slow = YES;  // 客户端标一份，最终以服务端 HttpNorm 判定为准
    }
    if (event.os.length == 0) { event.os = @"iOS"; }
    [gHLock lock];
    if (gHQueue.count < kHQueueMax) { [gHQueue addObject:event]; }
    NSUInteger n = gHQueue.count;
    [gHLock unlock];
    if (n >= kHBatch) { [self flushNow]; }
}

- (void)excludeHost:(NSString *)host {
    NSString *h = LoganTrim(host);
    if (h.length == 0) { return; }
    h = h.lowercaseString;
    if (![gHExcludeHosts containsObject:h]) { [gHExcludeHosts addObject:h]; }
}

- (void)excludeUrlContains:(NSString *)substring {
    NSString *s = LoganTrim(substring);
    if (s.length == 0) { return; }
    if (![gHExcludeSubs containsObject:s]) { [gHExcludeSubs addObject:s]; }
}

- (void)setObserveFilter:(BOOL (^)(NSURLRequest *))filter { gHObserveFilter = [filter copy]; }

// 观测层判定入口：任何异常一律返回 YES 之外的保守值 NO（不接管），业务永远能正常走
- (BOOL)shouldObserveRequest:(NSURLRequest *)request {
    @try {
        NSString *host = request.URL.host.lowercaseString ?: @"";
        for (NSString *h in gHExcludeHosts) {
            if ([host isEqualToString:h] || [host hasSuffix:[@"." stringByAppendingString:h]]) { return NO; }
        }
        NSString *full = request.URL.absoluteString ?: @"";
        for (NSString *s in gHExcludeSubs) {
            if ([full rangeOfString:s options:NSCaseInsensitiveSearch].location != NSNotFound) { return NO; }
        }
        if (gHObserveFilter != nil) { return gHObserveFilter(request) ? YES : NO; }
        return YES;
    } @catch (NSException *e) {
        return NO;
    }
}

- (BOOL)isSelfUrl:(NSString *)url {
    if (gHBaseUrl.length == 0 || ![url hasPrefix:gHBaseUrl]) { return NO; }
    NSString *rest = [url substringFromIndex:gHBaseUrl.length];
    return [rest hasPrefix:@"/logan/"] || [rest hasPrefix:@"/logan?"];
}

- (NSString *)h5ConfigJson {
    NSMutableDictionary *o = [NSMutableDictionary dictionary];
    o[@"baseUrl"] = gHBaseUrl ?: @"";
    o[@"appId"] = gHAppId ?: @"";
    o[@"deviceId"] = gHDeviceId ?: @"";
    o[@"appVersion"] = gHAppVersion ?: @"";
    o[@"buildVersion"] = gHBuildVersion ?: @"";
    o[@"platform"] = @(2);
    o[@"slowThresholdMs"] = @(gHSlowMs);
    o[@"enabled"] = @(gHEnabled);
    NSData *d = [NSJSONSerialization dataWithJSONObject:o options:0 error:NULL];
    return d ? [[NSString alloc] initWithData:d encoding:NSUTF8StringEncoding] : @"";
}

#pragma mark - 非致命错误通道借用配置

- (BOOL)isConfiguredForError {
    return gHBaseUrl.length > 0 && gHAppId.length > 0 && gHDeviceId.length > 0;
}
- (NSString *)errorBaseUrl { return gHBaseUrl ?: @""; }
- (NSString *)errorAppId { return gHAppId ?: @""; }
- (NSString *)errorDeviceId { return gHDeviceId ?: @""; }
- (NSString *)errorAppVersion { return gHAppVersion; }
- (NSString *)errorBuildVersion { return gHBuildVersion; }

- (NSDictionary<NSString *, NSString *> *)errorHeaders {
    NSMutableDictionary *h = [NSMutableDictionary dictionary];
    if (gHAppId.length) { h[@"appId"] = gHAppId; }
    if (gHDeviceId.length) { h[@"deviceId"] = gHDeviceId; }
    h[@"platform"] = @"2";
    if (gHAppVersion.length) { h[@"appVersion"] = gHAppVersion; }
    if (gHBuildVersion.length) { h[@"buildVersion"] = gHBuildVersion; }
    h[@"sdkVersion"] = @"0.1.0";
    if (gHAuthHeader.length) { h[@"Authorization"] = gHAuthHeader; }
    return h;
}

#pragma mark - 上报

- (void)flushNow {
    @try {
        if (gHBaseUrl.length == 0 || gHAppId.length == 0) { return; }
        NSMutableArray<LoganHttpEvent *> *batch = nil;
        [gHLock lock];
        if (gHQueue.count == 0) { [gHLock unlock]; return; }
        NSUInteger take = MIN(kHBatch * 4, gHQueue.count);
        batch = [[gHQueue subarrayWithRange:NSMakeRange(0, take)] mutableCopy];
        [gHQueue removeObjectsInRange:NSMakeRange(0, take)];
        [gHLock unlock];

        NSMutableArray *arr = [NSMutableArray arrayWithCapacity:batch.count];
        for (LoganHttpEvent *e in batch) { [arr addObject:[e toJsonDictionary]]; }
        NSMutableDictionary *body = [NSMutableDictionary dictionary];
        body[@"appId"] = gHAppId;
        body[@"deviceId"] = gHDeviceId ?: @"";
        body[@"platform"] = @"2";
        if (gHAppVersion.length) { body[@"appVersion"] = gHAppVersion; }
        if (gHBuildVersion.length) { body[@"buildVersion"] = gHBuildVersion; }
        body[@"os"] = @"iOS";
        body[@"events"] = arr;

        NSData *payload = [NSJSONSerialization dataWithJSONObject:body options:0 error:NULL];
        if (payload.length == 0) { return; }
        NSMutableURLRequest *req = [NSMutableURLRequest requestWithURL:
            [NSURL URLWithString:[gHBaseUrl stringByAppendingString:kHReportPath]]];
        req.HTTPMethod = @"POST";
        req.timeoutInterval = 20;
        [req setValue:@"application/json" forHTTPHeaderField:@"Content-Type"];
        NSDictionary *hd = [self errorHeaders];
        for (NSString *k in hd) { [req setValue:hd[k] forHTTPHeaderField:k]; }

        // ephemeral + 空 protocolClasses：不被自家 NSURLProtocol 观测，杜绝上报回环
        NSURLSessionConfiguration *cfg = [NSURLSessionConfiguration ephemeralSessionConfiguration];
        cfg.protocolClasses = @[];
        LoganMarkSessionNoObserve(cfg);   // 关键：上报会话绝不再进自家 protocol（否则上报被自采）
        cfg.HTTPShouldSetCookies = NO;
        cfg.URLCache = nil;
        NSURLSession *session = [NSURLSession sessionWithConfiguration:cfg delegate:(id)[NSClassFromString(@"LoganTrustSessionDelegate") shared] delegateQueue:nil];
        // 类作为 delegate 仅为拿 URLSessionTaskMetrics（connect/dns/ttfb 分段），见 LoganHttpProtocol 说明
        NSURLSessionUploadTask *task = [session uploadTaskWithRequest:req fromData:payload
                                                    completionHandler:^(NSData *data, NSURLResponse *resp, NSError *error) {
            (void)data; (void)resp; (void)error;  // 失败静默：采集绝不干扰业务
            [session finishTasksAndInvalidate];
        }];
        [task resume];
    } @catch (NSException *e) {
        // 采集内部任何异常都吞掉
    }
}


@end


// 自签/企业内网证书放行（仅 Logan 自家上报域名，其余域名一律交回系统默认评估）。
// 平台公网入口是自签证书（ECS nginx 18443），NSURLSession 默认会 -1202 拒掉 → 全部上报静默丢失。
@interface LoganTrustSessionDelegate : NSObject <NSURLSessionDelegate>
+ (instancetype)shared;
@end

@implementation LoganTrustSessionDelegate
+ (instancetype)shared {
  static LoganTrustSessionDelegate *g = nil;
  static dispatch_once_t tok;
  dispatch_once(&tok, ^{ g = [[LoganTrustSessionDelegate alloc] init]; });
  return g;
}

- (BOOL)loganIsSelfHost:(NSString *)host {
  if (host.length == 0) return NO;
  NSString *base = [LoganHttp shared].baseUrlLowercased;   // 见 LoganHttp.h（新增只读）
  NSString *h = host.lowercaseString;
  if (base.length > 0 && ([h hasSuffix:[@"." stringByAppendingString:base]] || [h isEqualToString:base])) return YES;
  // 局域网自测通道（验收用 http/https 直连 NAS，属自家部署主机）
  return [h isEqualToString:@"127.0.0.1"] || [h isEqualToString:@"localhost"] || [h hasPrefix:@"10."] || [h hasPrefix:@"192.168."] || [h hasPrefix:@"100."];
}

- (void)URLSession:(NSURLSession *)session
didReceiveAuthenticationChallenge:(NSURLAuthenticationChallenge *)challenge
 completionHandler:(void (^)(NSURLSessionAuthChallengeDisposition disposition, NSURLCredential * _Nullable credential))completionHandler {
  NSString *host = challenge.protectionSpace.host ?: @"";
  if ([challenge.protectionSpace.authenticationMethod isEqualToString:NSURLAuthenticationMethodServerTrust]) {
    if ([[LoganTrustSessionDelegate shared] loganIsSelfHost:host]) {
      SecTrustRef trust = challenge.protectionSpace.serverTrust;
      completionHandler(NSURLSessionAuthChallengeUseCredential, trust ? [NSURLCredential credentialForTrust:trust] : nil);
      return;
    }
  }
  completionHandler(NSURLSessionAuthChallengePerformDefaultHandling, nil);
}
@end

