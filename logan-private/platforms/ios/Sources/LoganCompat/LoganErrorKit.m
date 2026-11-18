//
//  LoganErrorKit.m
//  Logan PoC
//
#import "LoganErrorKit.h"
#import "LoganHttp.h"
#import "LoganHttpProtocol.h"
#import <objc/runtime.h>

static BOOL gErrEnabled = YES;
static BOOL gErrAutoNSError = YES;
static BOOL gErrInstalled = NO;
static NSMutableArray<NSDictionary *> *gErrQueue = nil;
static NSLock *gErrLock = nil;
static NSTimer *gErrTimer = nil;
static NSMutableDictionary<NSString *, NSNumber *> *gErrStorm = nil;   // key -> lastMs
static NSString *gExecutableName = nil;

static NSUInteger const kErrQueueMax = 300;
static NSUInteger const kErrBatch = 40;
static long long const kErrStormMs = 3000;
static NSString *const kErrReportPath = @"/logan/error/report.json";

static NSArray<NSString *> *kErrKindWhitelist = nil;

static void LoganErrEnsureKindList(void) {
    static dispatch_once_t once;
    dispatch_once(&once, ^{
        kErrKindWhitelist = @[@"business", @"caught", @"ui", @"promise", @"resource", @"other"];
    });
}

static NSString *LoganErrNormalizeKind(NSString *kind) {
    LoganErrEnsureKindList();
    if (![kind isKindOfClass:[NSString class]] || kind.length == 0) { return @"other"; }
    NSString *k = kind.lowercaseString;
    for (NSString *allow in kErrKindWhitelist) {
        if ([k isEqualToString:allow]) { return k; }
    }
    return @"other";
}

// 传输层错误域黑名单：这类 NSError 由网络/加密栈自己构造（NSURLSession、CFNetwork、
// Socket/SSL 类第三方 SDK 等），没有业务归因价值，只会淹没真实业务错误。
// 判定按语义 token 通用匹配，不针对任何具体 SDK；宿主可用 excludeNSErrorDomain: 一行追加。
static NSMutableArray<NSString *> *gErrDomainBlacklist = nil;
static void LoganErrEnsureDomainBlacklist(void) {
    static dispatch_once_t once;
    dispatch_once(&once, ^{
        gErrDomainBlacklist = [NSMutableArray arrayWithArray:@[
            @"cfnetwork", @"securetransport", @"errsecstatus", @"posix", @"socket",
            @"nsurl", @"urlerror", @"urlsession", @"corefoundation", @"dns",
            @"tls", @"ssl", @"upload", @"download", @"stream", @"websocket"]];
    });
}
static BOOL LoganErrIsTransportDomain(NSString *domain) {
    if (![domain isKindOfClass:[NSString class]] || domain.length == 0) { return NO; }
    LoganErrEnsureDomainBlacklist();
    NSString *d = domain.lowercaseString;
    @synchronized (gErrDomainBlacklist) {
        for (NSString *tok in gErrDomainBlacklist) {
            if ([d containsString:tok]) { return YES; }
        }
    }
    return NO;
}

static NSString *LoganErrExecutableName(void) {
    if (gExecutableName == nil) {
        gExecutableName = [[NSProcessInfo processInfo] processName] ?: @"";
    }
    return gExecutableName;
}

/// 取调用栈里第一个"属于主可执行文件"的帧 —— 业务构造点。
/// 返回 nil 表示整条栈都在系统库/三方动态库里：这类 NSError 没有业务归因价值，直接丢弃。
static NSString *LoganErrBusinessTopFrame(void) {
    @try {
        NSString *exe = LoganErrExecutableName();
        if (exe.length == 0) { return nil; }
        NSArray<NSString *> *syms = [NSThread callStackSymbols];
        for (NSString *s in syms) {
            if ([s containsString:exe]) {
                // 只保留 "0  App 0x1000 ... + 12" 里的符号部分，去掉地址（ASLR 会让同崩溃各异）
                NSRange r = [s rangeOfString:@"0x"];
                if (r.location == NSNotFound) { continue; }
                NSString *tail = [s substringFromIndex:r.location];
                NSRange sp = [tail rangeOfString:@" "];
                NSString *sym = (sp.location == NSNotFound) ? tail : [tail substringFromIndex:(sp.location + sp.length)];
                sym = [sym stringByTrimmingCharactersInSet:[NSCharacterSet whitespaceCharacterSet]];
                if (sym.length > 0 && ![sym containsString:@"LoganErrorKit"]
                    && ![sym containsString:@"LoganHttp"] && ![sym containsString:@"LoganH5Bridge"]) {
                    return sym;
                }
            }
        }
    } @catch (NSException *e) {}
    return nil;
}

static NSString *LoganErrStackString(void) {
    @try {
        NSArray<NSString *> *syms = [NSThread callStackSymbols];
        NSMutableArray<NSString *> *kept = [NSMutableArray array];
        NSString *exe = LoganErrExecutableName();
        for (NSString *s in syms) {
            NSString *t = [s stringByTrimmingCharactersInSet:[NSCharacterSet whitespaceCharacterSet]];
            if (t.length == 0) { continue; }
            // 剥掉地址列（ASLR 每次不同，留着会让同一错误指纹各异、永远不聚合）
            NSRange r = [t rangeOfString:@"0x"];
            if (r.location != NSNotFound) {
                NSRange sp = [t rangeOfString:@" " options:0 range:NSMakeRange(r.location, t.length - r.location)];
                if (sp.location != NSNotFound) { t = [t substringFromIndex:sp.location + 1]; }
            }
            // 自家 SDK 帧不入栈，避免污染归因
            if ([t containsString:@"LoganErrorKit"] || [t containsString:@"LoganHttp"]
                || [t containsString:@"LoganH5Bridge"] || [t containsString:@"__25+"] && ![t containsString:exe]) { continue; }
            [kept addObject:t];
            if (kept.count >= 24) { break; }
        }
        return kept.count > 0 ? [kept componentsJoinedByString:@"\n"] : nil;
    } @catch (NSException *e) {
        return nil;
    }
}

@implementation LoganErrorKit

+ (void)setEnabled:(BOOL)on {
    gErrEnabled = on;
    if (!on) { [self flushNow]; }
}
+ (BOOL)isEnabled { return gErrEnabled && [LoganHttp shared].isEnabled; }
+ (void)setAutoNSErrorEnabled:(BOOL)on { gErrAutoNSError = on; }
+ (void)excludeNSErrorDomain:(NSString *)domainOrToken {
    if (![domainOrToken isKindOfClass:[NSString class]] || domainOrToken.length == 0) { return; }
    LoganErrEnsureDomainBlacklist();
    NSString *t = domainOrToken.lowercaseString;
    @synchronized (gErrDomainBlacklist) {
        if (![gErrDomainBlacklist containsObject:t]) { [gErrDomainBlacklist addObject:t]; }
    }
}
+ (void)clearNSErrorDomainExcludes {
    LoganErrEnsureDomainBlacklist();
    @synchronized (gErrDomainBlacklist) { [gErrDomainBlacklist removeAllObjects]; }
}

+ (void)install {
    @synchronized (self) {
        if (gErrInstalled) { return; }
        gErrInstalled = YES;
        if (gErrQueue == nil) { gErrQueue = [NSMutableArray array]; gErrLock = [NSLock new]; gErrStorm = [NSMutableDictionary dictionary]; }
        Class cls = [NSError class];
        Method orig = class_getClassMethod(cls, @selector(errorWithDomain:code:userInfo:));
        if (orig == NULL) { return; }
        // 关键：exchange 之前抓原始 IMP，否则 block 内取到的就是 block 自身 → 无限递归
        const IMP origNSErrorImp = method_getImplementation(orig);
        const SEL origSel = @selector(errorWithDomain:code:userInfo:);
        SEL swz = NSSelectorFromString(@"logan_errorWithDomain:code:userInfo:");
        if (class_getClassMethod(cls, swz) != NULL) { return; }
        IMP impl = imp_implementationWithBlock(^id(id klass, NSString *domain, NSInteger code, NSDictionary *userInfo) {
            NSError *err = ((NSError *(*)(id, SEL, NSString *, NSInteger, NSDictionary *))origNSErrorImp)(klass, origSel, domain, code, userInfo);
            @try {
                if (gErrEnabled && gErrAutoNSError) {
                    NSString *top = LoganErrBusinessTopFrame();
                    if (top.length > 0 && !LoganErrIsTransportDomain(domain)) {   // 系统库/传输层构造的 NSError 一律不采（宁缺毋假）
                        NSString *key = [NSString stringWithFormat:@"%@|%ld|%@", domain ?: @"", (long)code, top];
                        long long now = (long long)([[NSDate date] timeIntervalSince1970] * 1000);
                        BOOL pass = YES;
                        [gErrLock lock];
                        NSNumber *last = gErrStorm[key];
                        if (last != nil && now - last.longLongValue < kErrStormMs) { pass = NO; }
                        else {
                            if (gErrStorm.count > 512) { [gErrStorm removeAllObjects]; }
                            gErrStorm[key] = @(now);
                        }
                        [gErrLock unlock];
                        if (pass) {
                            NSString *msg = [userInfo[NSLocalizedDescriptionKey] isKindOfClass:[NSString class]]
                                ? userInfo[NSLocalizedDescriptionKey]
                                : ([err.localizedDescription isKindOfClass:[NSString class]] ? err.localizedDescription : @"");
                            NSMutableDictionary *extra = [NSMutableDictionary dictionary];
                            extra[@"domain"] = domain ?: @"";
                            extra[@"code"] = @(code);
                            if (userInfo.count > 0) {
                                NSData *d = [NSJSONSerialization dataWithJSONObject:[self jsonable:userInfo] options:0 error:NULL];
                                if (d.length > 0 && d.length < 4000) {
                                    extra[@"userInfo"] = [[NSString alloc] initWithData:d encoding:NSUTF8StringEncoding];
                                }
                            }
                            NSData *ed = [NSJSONSerialization dataWithJSONObject:extra options:0 error:NULL];
                            [self enqueueWithKind:@"caught"
                                             name:[NSString stringWithFormat:@"%@(%ld)", domain ?: @"NSError", (long)code]
                                          message:msg ?: @""
                                            stack:LoganErrStackString()
                                          pageUrl:nil
                                            extra:ed ? [[NSString alloc] initWithData:ed encoding:NSUTF8StringEncoding] : nil];
                        }
                    }
                }
            } @catch (NSException *e) {}
            return err;
        });
        class_addMethod(object_getClass(cls), swz, impl, method_getTypeEncoding(orig));
        Method swzMethod = class_getClassMethod(cls, swz);
        method_exchangeImplementations(orig, swzMethod);
    }
}

+ (NSDictionary *)jsonable:(NSDictionary *)dict {
    @try {
        NSData *d = [NSJSONSerialization dataWithJSONObject:dict options:NSJSONWritingFragmentsAllowed error:NULL];
        if (d == nil) { return @{}; }
        NSDictionary *back = [NSJSONSerialization JSONObjectWithData:d options:0 error:NULL];
        return [back isKindOfClass:[NSDictionary class]] ? back : @{};
    } @catch (NSException *e) {
        return @{};
    }
}

+ (void)reportErrorWithKind:(NSString *)kind name:(NSString *)name message:(NSString *)message
                      stack:(NSString *)stack pageUrl:(NSString *)pageUrl extra:(NSString *)extra {
    @try {
        if (![self isEnabled]) { return; }
        if (name.length == 0 && message.length == 0 && stack.length == 0) { return; }  // 三要素全空 → 不造假数据
        [self enqueueWithKind:LoganErrNormalizeKind(kind)
                         name:name.length > 0 ? name : @"Error"
                      message:message ?: @""
                        stack:stack.length > 0 ? stack : LoganErrStackString()
                      pageUrl:pageUrl
                        extra:extra];
    } @catch (NSException *e) {}
}

+ (void)reportNSError:(NSError *)error where:(NSString *)where {
    @try {
        if (![self isEnabled] || error == nil) { return; }
        NSString *name = [NSString stringWithFormat:@"%@(%ld)", error.domain.length > 0 ? error.domain : @"NSError", (long)error.code];
        if (where.length > 0) { name = [name stringByAppendingFormat:@" @%@", where]; }
        [self enqueueWithKind:@"caught"
                         name:name
                      message:error.localizedDescription ?: @""
                        stack:LoganErrStackString()
                      pageUrl:nil
                        extra:nil];
    } @catch (NSException *e) {}
}

#pragma mark - 队列与上报

+ (void)enqueueWithKind:(NSString *)kind name:(NSString *)name message:(NSString *)message
                  stack:(NSString *)stack pageUrl:(NSString *)pageUrl extra:(NSString *)extra {
    @try {
        NSMutableDictionary *ev = [NSMutableDictionary dictionary];
        ev[@"kind"] = LoganErrNormalizeKind(kind);
        ev[@"name"] = name.length > 300 ? [name substringToIndex:300] : name;
        if (message.length > 0) { ev[@"message"] = message.length > 2000 ? [message substringToIndex:2000] : message; }
        if (stack.length > 0) { ev[@"stack"] = stack.length > 8000 ? [stack substringToIndex:8000] : stack; }
        if (pageUrl.length > 0) { ev[@"pageUrl"] = pageUrl; }
        if (extra.length > 0) { ev[@"extra"] = extra.length > 4000 ? [extra substringToIndex:4000] : extra; }
        ev[@"fatal"] = @(NO);
        ev[@"occurredAt"] = @((long long)([[NSDate date] timeIntervalSince1970] * 1000));
        [gErrLock lock];
        if (gErrQueue == nil) { gErrQueue = [NSMutableArray array]; gErrLock = [NSLock new]; }
        BOOL full = gErrQueue.count >= kErrQueueMax;
        if (!full) { [gErrQueue addObject:ev]; }
        NSUInteger n = gErrQueue.count;
        [gErrLock unlock];
        if (full) { return; }              // 队列满丢新事件：稳定性优先
        if (n >= kErrBatch) { [self flushNow]; }
        else if (gErrTimer == nil) {
            // WKWebView 的 JS 桥回调不在主 runloop 线程：NSTimer scheduledTimer 在那里永不触发，
            // 会导致 H5 错误永久滞留队列。改用 GCD 延时（任意线程可用），gErrTimer 仅作「已排程」标记。
            gErrTimer = [NSTimer new];
            dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(5 * NSEC_PER_SEC)), dispatch_get_global_queue(QOS_CLASS_UTILITY, 0), ^{
                [self flushNow];
            });
        }
    } @catch (NSException *e) {}
}

+ (void)flushNow {
    @try {
        [gErrLock lock];
        NSUInteger dbg = gErrQueue == nil ? 0 : gErrQueue.count;
        [gErrLock unlock];
        gErrTimer = nil;
        LoganHttp *http = [LoganHttp shared];
        if (![http isConfiguredForError]) { return; }
        NSMutableArray<NSDictionary *> *batch = nil;
        [gErrLock lock];
        if (gErrQueue == nil || gErrQueue.count == 0) { [gErrLock unlock]; return; }
        NSUInteger take = MIN(kErrBatch * 4, gErrQueue.count);
        batch = [[gErrQueue subarrayWithRange:NSMakeRange(0, take)] mutableCopy];
        [gErrQueue removeObjectsInRange:NSMakeRange(0, take)];
        [gErrLock unlock];

        NSMutableDictionary *body = [NSMutableDictionary dictionary];
        body[@"appId"] = [http errorAppId];
        body[@"deviceId"] = [http errorDeviceId];
        body[@"platform"] = @"2";
        NSString *av = [http errorAppVersion]; if (av.length) { body[@"appVersion"] = av; }
        NSString *bv = [http errorBuildVersion]; if (bv.length) { body[@"buildVersion"] = bv; }
        body[@"os"] = @"iOS";
        body[@"events"] = batch;

        NSData *payload = [NSJSONSerialization dataWithJSONObject:body options:0 error:NULL];
        if (payload.length == 0) { return; }
        NSMutableURLRequest *req = [NSMutableURLRequest requestWithURL:
            [NSURL URLWithString:[[http errorBaseUrl] stringByAppendingString:kErrReportPath]]];
        req.HTTPMethod = @"POST";
        req.timeoutInterval = 20;
        [req setValue:@"application/json" forHTTPHeaderField:@"Content-Type"];
        NSDictionary *hd = [http errorHeaders];
        for (NSString *k in hd) { [req setValue:hd[k] forHTTPHeaderField:k]; }
        NSURLSessionConfiguration *cfg = [NSURLSessionConfiguration ephemeralSessionConfiguration];
        cfg.protocolClasses = @[];
        LoganMarkSessionNoObserve(cfg);   // 不被自家 NSURLProtocol 观测，无回环
        NSURLSession *session = [NSURLSession sessionWithConfiguration:cfg delegate:(id)[NSClassFromString(@"LoganTrustSessionDelegate") shared] delegateQueue:nil];
        [[session uploadTaskWithRequest:req fromData:payload completionHandler:^(NSData *d, NSURLResponse *r, NSError *e) {
            (void)d; (void)r; (void)e;
            [session finishTasksAndInvalidate];
        }] resume];
    } @catch (NSException *e) {}
}

@end
