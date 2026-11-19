//
//  LoganBehaviorKit.m
//  Logan PoC
//
#import "LoganBehaviorKit.h"
#import "LoganHttp.h"
#import "LoganHttpProtocol.h"
#import <objc/runtime.h>
#import <UIKit/UIKit.h>

static BOOL gBehEnabled = YES;
static BOOL gBehAutoUI = YES;
static BOOL gBehInstalled = NO;
static NSMutableArray<NSDictionary *> *gBehQueue = nil;
static NSLock *gBehLock = nil;
static NSString *gBehLastKey = nil;
static long long gBehLastAt = 0;
static BOOL gBehTimerScheduled = NO;

static NSUInteger const kBehQueueMax = 300;
static NSUInteger const kBehBatch = 40;
static long long const kBehDedupMs = 400;      // 与安卓端 DUP_WINDOW_MS 同值：同键抖动只记一条
static NSString *const kBehReportPath = @"/logan/behavior/report.json";

static NSArray<NSString *> *kBehKindWhitelist = nil;

static void LoganBehEnsureKindList(void) {
    static dispatch_once_t once;
    dispatch_once(&once, ^{
        kBehKindWhitelist = @[@"page", @"click", @"custom", @"foreground", @"background",
                               @"launch", @"exit", @"input", @"scroll", @"refresh",
                               @"tab", @"dialog", @"other"];
    });
}

static NSString *LoganBehNormalizeKind(NSString *kind) {
    LoganBehEnsureKindList();
    if (![kind isKindOfClass:[NSString class]] || kind.length == 0) { return @"other"; }
    NSString *k = kind.lowercaseString;
    for (NSString *allow in kBehKindWhitelist) {
        if ([k isEqualToString:allow]) { return k; }
    }
    return @"other";
}

static NSString *LoganBehClip(NSString *v, NSUInteger max) {
    if (![v isKindOfClass:[NSString class]] || v.length == 0) { return nil; }
    NSString *s = [v stringByTrimmingCharactersInSet:[NSCharacterSet whitespaceAndNewlineCharacterSet]];
    if (s.length == 0) { return nil; }
    return s.length > max ? [s substringToIndex:max] : s;
}

static long long LoganBehNow(void) {
    return (long long)([[NSDate date] timeIntervalSince1970] * 1000);
}

@implementation LoganBehaviorKit

+ (void)setEnabled:(BOOL)on { gBehEnabled = on; }
+ (BOOL)isEnabled { return gBehEnabled && [[LoganHttp shared] isEnabled]; }
+ (void)setAutoUIEnabled:(BOOL)on { gBehAutoUI = on; }

#pragma mark - 自动源

+ (void)install {
    @synchronized (self) {
        if (gBehInstalled) { return; }
        gBehInstalled = YES;
        if (gBehQueue == nil) { gBehQueue = [NSMutableArray array]; gBehLock = [NSLock new]; }
        // 前后台：走系统通知，不 swizzle UIApplication，零副作用
        NSNotificationCenter *nc = [NSNotificationCenter defaultCenter];
        [nc addObserver:self selector:@selector(loganBehForeground:)
                     name:UIApplicationDidBecomeActiveNotification object:nil];
        [nc addObserver:self selector:@selector(loganBehBackground:)
                     name:UIApplicationWillResignActiveNotification object:nil];
        [nc addObserver:self selector:@selector(loganBehLaunch:)
                     name:UIApplicationDidFinishLaunchingNotification object:nil];
        [self loganBehSwizzlePageAndClick];
    }
}

+ (void)loganBehForeground:(NSNotification *)n {
    (void)n;
    if (![self isEnabled] || !gBehAutoUI) { return; }
    [self enqueueWithKind:@"foreground" name:[self loganBehTopViewControllerName] label:nil pageUrl:nil traceId:nil extra:nil occurredAt:LoganBehNow()];
}

+ (void)loganBehBackground:(NSNotification *)n {
    (void)n;
    if (![self isEnabled] || !gBehAutoUI) { return; }
    [self enqueueWithKind:@"background" name:[self loganBehTopViewControllerName] label:nil pageUrl:nil traceId:nil extra:nil occurredAt:LoganBehNow()];
    [self flushNow];   // 退到后台是最后的送达窗口
}

+ (void)loganBehLaunch:(NSNotification *)n {
    (void)n;
    if (![self isEnabled] || !gBehAutoUI) { return; }
    [self enqueueWithKind:@"launch" name:@"app" label:nil pageUrl:nil traceId:nil extra:@"cold=ios" occurredAt:LoganBehNow()];
}

static IMP gBehOrigDidAppear = NULL;
static IMP gBehOrigSendAction = NULL;

+ (void)loganBehSwizzlePageAndClick {
    Class vc = NSClassFromString(@"UIViewController");
    if (vc != nil) {
        SEL sel = @selector(viewDidAppear:);
        Method m = class_getInstanceMethod(vc, sel);
        if (m != NULL && gBehOrigDidAppear == NULL) {
            gBehOrigDidAppear = method_getImplementation(m);
            NSString *nm = [NSString stringWithFormat:@"logan_%s", sel_getName(sel)];
            SEL swz = NSSelectorFromString(nm);
            if (class_getInstanceMethod(vc, swz) == NULL) {
                IMP blk = imp_implementationWithBlock(^void(id target, BOOL animated) {
                    if ([[self class] isEnabled] && gBehAutoUI) {
                        @try {
                            NSString *cn = NSStringFromClass([target class]) ?: @"";
                            [[self class] enqueueWithKind:@"page" name:cn label:nil pageUrl:nil traceId:nil extra:@"phase=appeared" occurredAt:LoganBehNow()];
                        } @catch (NSException *e) {}
                    }
                    if (gBehOrigDidAppear != NULL) {
                        ((void (*)(id, SEL, BOOL))gBehOrigDidAppear)(target, sel, animated);
                    }
                });
                if (class_addMethod(vc, swz, blk, method_getTypeEncoding(m))) {
                    method_exchangeImplementations(m, class_getInstanceMethod(vc, swz));
                }
            }
        }
    }
    Class ctl = NSClassFromString(@"UIControl");
    if (ctl != nil) {
        SEL sel = @selector(sendAction:to:forEvent:);
        Method m = class_getInstanceMethod(ctl, sel);
        if (m != NULL && gBehOrigSendAction == NULL) {
            gBehOrigSendAction = method_getImplementation(m);
            NSString *nm2 = [NSString stringWithFormat:@"logan_%s", sel_getName(sel)];
            SEL swz2 = NSSelectorFromString(nm2);
            if (class_getInstanceMethod(ctl, swz2) == NULL) {
                IMP blk2 = imp_implementationWithBlock(^void(id target, SEL act, id to, UIEvent *ev) {
                    if ([[self class] isEnabled] && gBehAutoUI) {
                        @try {
                            NSString *cn = NSStringFromClass([target class]) ?: @"";
                            NSString *lb = [[self class] loganBehControlLabel:target];
                            if (lb.length == 0) {
                                NSString *sn = act ? NSStringFromSelector(act) : @"";
                                lb = [NSString stringWithFormat:@"action:%@", sn.length > 0 ? sn : @"unknown"];
                            }
                            [[self class] enqueueWithKind:@"click" name:cn label:lb pageUrl:nil traceId:nil extra:nil occurredAt:LoganBehNow()];
                        } @catch (NSException *e) {}
                    }
                    if (gBehOrigSendAction != NULL) {
                        ((void (*)(id, SEL, SEL, id, id))gBehOrigSendAction)(target, sel, act, to, ev);
                    }
                });
                if (class_addMethod(ctl, swz2, blk2, method_getTypeEncoding(m))) {
                    method_exchangeImplementations(m, class_getInstanceMethod(ctl, swz2));
                }
            }
        }
    }
}

/// 点击目标的人话描述：无障碍标签 > 按钮标题 > 占位/文本（ UITextField 只记占位符不记内容）。
+ (nullable NSString *)loganBehControlLabel:(id)target {
    @try {
        if (![target isKindOfClass:[NSObject class]]) { return nil; }
        if ([target respondsToSelector:@selector(accessibilityLabel)]) {
            NSString *a = [(id)target accessibilityLabel];
            NSString *s = LoganBehClip(a, 200);
            if (s.length) { return s; }
        }
        if ([target isKindOfClass:NSClassFromString(@"UIButton")]) {
            NSString *t = [(id)target currentTitle];
            NSString *s = LoganBehClip(t, 200);
            if (s.length) { return s; }
        }
        if ([target isKindOfClass:NSClassFromString(@"UITextField")]) {
            NSString *ph = [(id)target placeholder];   // 绝不取 text：用户输入内容不入轨迹
            return [NSString stringWithFormat:@"input:%@", LoganBehClip(ph, 120) ?: @"text"];
        }
    } @catch (NSException *e) {}
    return nil;
}

+ (nullable NSString *)loganBehTopViewControllerName {
    @try {
        UIWindow *key = nil;
        for (UIWindow *w in [UIApplication sharedApplication].windows) {
            if (w.isKeyWindow) { key = w; break; }
        }
        UIViewController *vc = key.rootViewController;
        while (vc.presentedViewController) { vc = vc.presentedViewController; }
        if ([vc isKindOfClass:[UINavigationController class]]) {
            vc = [(UINavigationController *)vc topViewController] ?: vc;
        }
        if ([vc isKindOfClass:[UITabBarController class]]) {
            vc = [(UITabBarController *)vc selectedViewController] ?: vc;
        }
        return vc ? (NSStringFromClass([vc class]) ?: @"") : @"";
    } @catch (NSException *e) {
        return @"";
    }
}

#pragma mark - 显式入口

+ (void)reportBehaviorWithKind:(NSString *)kind name:(NSString *)name label:(NSString *)label
                   pageUrl:(NSString *)pageUrl extra:(NSString *)extra {
    @try {
        if (![self isEnabled]) { return; }
        [self enqueueWithKind:LoganBehNormalizeKind(kind) name:LoganBehClip(name, 256) label:LoganBehClip(label, 500)
                      pageUrl:LoganBehClip(pageUrl, 512) traceId:nil extra:LoganBehClip(extra, 500) occurredAt:LoganBehNow()];
    } @catch (NSException *e) {}
}

+ (void)reportLaunch:(NSString *)name intervalMs:(long long)intervalMs {
    @try {
        if (intervalMs <= 0 || intervalMs > 60000) { return; } // 明显不合理丢弃（>60s 多半挂机，对齐安卓）
        if (![self isEnabled]) { return; }
        [self enqueueWithKind:@"launch" name:LoganBehClip(name.length > 0 ? name : @"cold_start", 256) label:nil
                      pageUrl:nil traceId:nil extra:nil intervalMs:intervalMs occurredAt:LoganBehNow()];
        [self flushNow]; // 启动事件即时送达，不等攒批
    } @catch (NSException *e) {}
}

+ (void)reportBehaviorFromH5WithKind:(NSString *)kind name:(NSString *)name label:(NSString *)label
                         pageUrl:(NSString *)pageUrl traceId:(NSString *)traceId
                           extra:(NSString *)extra occurredAt:(long long)occurredAt {
    @try {
        if (![self isEnabled]) { return; }
        long long ts = occurredAt > 0 ? occurredAt : LoganBehNow();
        [self enqueueWithKind:LoganBehNormalizeKind(kind) name:LoganBehClip(name, 256) label:LoganBehClip(label, 500)
                      pageUrl:LoganBehClip(pageUrl, 512) traceId:LoganBehClip(traceId, 64) extra:LoganBehClip(extra, 500) occurredAt:ts];
    } @catch (NSException *e) {}
}

#pragma mark - 队列与上报

+ (void)enqueueWithKind:(NSString *)kind name:(NSString *)name label:(NSString *)label
              pageUrl:(NSString *)pageUrl traceId:(NSString *)traceId
                extra:(NSString *)extra occurredAt:(long long)occurredAt {
    [self enqueueWithKind:kind name:name label:label pageUrl:pageUrl traceId:traceId
                    extra:extra intervalMs:0 occurredAt:occurredAt];
}

+ (void)enqueueWithKind:(NSString *)kind name:(NSString *)name label:(NSString *)label
              pageUrl:(NSString *)pageUrl traceId:(NSString *)traceId
                extra:(NSString *)extra intervalMs:(long long)intervalMs occurredAt:(long long)occurredAt {
    @try {
        if (gBehQueue == nil) { gBehQueue = [NSMutableArray array]; gBehLock = [NSLock new]; }
        // 三要素全空 → 不造数据（与安卓/鸿蒙端同语义，与服务端 BehaviorNorm 双保险）
        if (name.length == 0 && label.length == 0) { return; }
        NSString *key = [NSString stringWithFormat:@"%@|%@|%@", kind, name ?: @"", label ?: @""];
        long long now = LoganBehNow();
        [gBehLock lock];
        if (gBehLastKey != nil && [gBehLastKey isEqualToString:key] && now - gBehLastAt < kBehDedupMs) {
            [gBehLock unlock];
            return;                                  // 抖动抑制：同一次交互系统会回调多次
        }
        gBehLastKey = key;
        gBehLastAt = now;
        NSMutableDictionary *ev = [NSMutableDictionary dictionary];
        ev[@"kind"] = LoganBehNormalizeKind(kind);
        if (name.length > 0) { ev[@"name"] = name; }
        if (label.length > 0) { ev[@"label"] = label; }
        if (pageUrl.length > 0) { ev[@"pageUrl"] = pageUrl; }
        if (traceId.length > 0) { ev[@"traceId"] = traceId; }
        if (extra.length > 0) { ev[@"extra"] = extra; }
        if (intervalMs > 0) { ev[@"intervalMs"] = @(intervalMs); } // 启动耗时等数值型事件（对齐安卓 enqueue）
        ev[@"occurredAt"] = @(occurredAt > 0 ? occurredAt : now);
        BOOL full = gBehQueue.count >= kBehQueueMax;
        if (!full) { [gBehQueue addObject:ev]; }
        NSUInteger n = gBehQueue.count;
        [gBehLock unlock];
        if (full) { return; }                       // 队列满丢新事件：稳定性优先
        if (n >= kBehBatch) { [self flushNow]; }
        else if (gBehTimerScheduled == NO) {
            // 同 LoganErrorKit：WKWebView JS 桥回调不在主 runloop，NSTimer 在那儿永不触发，必须用 GCD 延时
            gBehTimerScheduled = YES;
            dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(5 * NSEC_PER_SEC)), dispatch_get_global_queue(QOS_CLASS_UTILITY, 0), ^{
                [self flushNow];
            });
        }
    } @catch (NSException *e) {}
}

+ (void)flushNow {
    @try {
        gBehTimerScheduled = NO;
        LoganHttp *http = [LoganHttp shared];
        if (![http isConfiguredForError]) { return; }
        NSMutableArray<NSDictionary *> *batch = nil;
        [gBehLock lock];
        if (gBehQueue == nil || gBehQueue.count == 0) { [gBehLock unlock]; return; }
        NSUInteger take = MIN(kBehBatch * 4, gBehQueue.count);
        batch = [[gBehQueue subarrayWithRange:NSMakeRange(0, take)] mutableCopy];
        [gBehQueue removeObjectsInRange:NSMakeRange(0, take)];
        [gBehLock unlock];

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
            [NSURL URLWithString:[[http errorBaseUrl] stringByAppendingString:kBehReportPath]]];
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
