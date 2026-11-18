//
//  LoganHttpProtocol.m
//  Logan PoC
//
#import "LoganHttpProtocol.h"
#import "LoganH5Bridge.h"
#import <objc/runtime.h>
#import "LoganHttp.h"

static NSString *const kLoganObservedKey = @"LoganHTTPObservedKey";

@class LoganSessionDelegateProxy;

// 类工厂钩在建会话时需要构造代理，接口必须前置声明
@interface LoganSessionDelegateProxy : NSObject <NSURLSessionDataDelegate>
@property(strong, nonatomic) id realDelegate;
- (instancetype)initWithDelegate:(id)delegate;
@end

// 任务级观察要用到观测判定，补类方法声明（实现在本文件 @implementation 内）
@interface LoganHttpProtocol (LoganTaskObserve)
+ (BOOL)isStaticResource:(NSString *)path method:(NSString *)method;
@end
static BOOL gObserverInstalled = NO;

@interface LoganHttpProtocol () <NSURLSessionDataDelegate>
@property(strong, nonatomic) NSURLSession *fwdSession;
@property(strong, nonatomic) NSURLSessionDataTask *fwdTask;
@property(strong, nonatomic) NSHTTPURLResponse *pendingResponse;
@property(assign, nonatomic) CFAbsoluteTime startTime;
@property(assign, nonatomic) long long ttfbMs;
@property(assign, nonatomic) long long respBytes;
@property(assign, nonatomic) BOOL finished;
@end


// 追加（不替换）protocolClasses：保留调用方原有的自定义 protocol 顺序，本类排最后，
// 且 canInitWithRequest 里对已被打过标记的请求直接放行，不会重复接管。
static NSArray<Class> *LoganAppendProtocolClass(NSURLSessionConfiguration *cfg) {
  NSMutableArray<Class> *arr = [NSMutableArray array];
  for (Class c in cfg.protocolClasses) { if (c != [LoganHttpProtocol class]) { [arr addObject:c]; } }
  [arr addObject:[LoganHttpProtocol class]];
  return arr;
}

static IMP gOrigDefaultCfg = NULL;
static IMP gOrigEphemeralCfg = NULL;

static NSURLSessionConfiguration *LoganDefaultCfg(id self, SEL _cmd) {
  NSURLSessionConfiguration *cfg = ((NSURLSessionConfiguration *(*)(id, SEL))gOrigDefaultCfg)(self, _cmd);
  @try { if (cfg) { cfg.protocolClasses = LoganAppendProtocolClass(cfg); } } @catch (NSException *e) {}
  return cfg;
}

static NSURLSessionConfiguration *LoganEphemeralCfg(id self, SEL _cmd) {
  NSURLSessionConfiguration *cfg = ((NSURLSessionConfiguration *(*)(id, SEL))gOrigEphemeralCfg)(self, _cmd);
  @try { if (cfg) { cfg.protocolClasses = LoganAppendProtocolClass(cfg); } } @catch (NSException *e) {}
  return cfg;
}

static void LoganPatchSessionConfigurationFactory(void) {
  @try {
    Class cls = [NSURLSessionConfiguration class];
    Method m1 = class_getClassMethod(cls, @selector(defaultSessionConfiguration));
    Method m2 = class_getClassMethod(cls, @selector(ephemeralSessionConfiguration));
    if (m1) { gOrigDefaultCfg = method_getImplementation(m1); method_setImplementation(m1, (IMP)LoganDefaultCfg); }
    if (m2) { gOrigEphemeralCfg = method_getImplementation(m2); method_setImplementation(m2, (IMP)LoganEphemeralCfg); }
  } @catch (NSException *e) {}
}

// ── NSURLSession 工厂钩 ──────────────────────────────────────────────
// 实测教训：只改 +defaultSessionConfiguration/+ephemeralSessionConfiguration 的返回值，
// NSURLSession 仍然不走我们的 NSURLProtocol（Foundation 内部对配置对象有私有子类与快照，
// 外部赋值的 protocolClasses 会被绕过）。业内 APM 通行做法是在「建会话」这一刻补
// protocolClasses —— 连第三方 SDK 提前缓存复用的 configuration 也能覆盖到。
static const void *const kLoganNoObserveKey = (const void *)&kLoganNoObserveKey;

void LoganMarkSessionNoObserve(NSURLSessionConfiguration *cfg) {
  @try {
    if (cfg) { objc_setAssociatedObject(cfg, kLoganNoObserveKey, @(YES), OBJC_ASSOCIATION_RETAIN_NONATOMIC); }
  } @catch (NSException *e) {}
}

static BOOL LoganSessionMarkedNoObserve(NSURLSessionConfiguration *cfg) {
  @try {
    if (!cfg) { return NO; }
    id v = objc_getAssociatedObject(cfg, kLoganNoObserveKey);
    return [v isKindOfClass:[NSNumber class]] ? [v boolValue] : NO;
  } @catch (NSException *e) {
    return YES;   // 判定不了就当自家会话：宁可漏采也不让采集自环
  }
}

static int gLoganSessionHookLog = 0;

static NSURLSessionConfiguration *LoganPrepareConfigForObserve(NSURLSessionConfiguration *cfg) {
  @try {
    if (!cfg || LoganSessionMarkedNoObserve(cfg)) { return cfg; }
    cfg.protocolClasses = LoganAppendProtocolClass(cfg);
  } @catch (NSException *e) {}
  return cfg;
}

typedef NSURLSession *(*LoganSession2Fn)(id, SEL, NSURLSessionConfiguration *);
typedef NSURLSession *(*LoganSession3Fn)(id, SEL, NSURLSessionConfiguration *, id, NSOperationQueue *);
static LoganSession2Fn gOrigSession2 = NULL;
static LoganSession3Fn gOrigSession3 = NULL;

static NSURLSession *LoganSessionWithCfg(id self, SEL _cmd, NSURLSessionConfiguration *cfg) {
  return gOrigSession2(self, _cmd, LoganPrepareConfigForObserve(cfg));
}

static NSURLSession *LoganSessionWithCfgDelegateQueue(id self, SEL _cmd, NSURLSessionConfiguration *cfg,
                                                     id delegate, NSOperationQueue *queue) {
  // NSURLSession 不咨询 configuration.protocolClasses（实测否证）——业务会话改走
  // 「观察代理 delegate」路线：把调用方 delegate 包一层透传代理，旁路计时上报。
  id watchDelegate = delegate;
  @try {
    if (cfg && !LoganSessionMarkedNoObserve(cfg) && delegate &&
        ![delegate isKindOfClass:[LoganSessionDelegateProxy class]]) {
      watchDelegate = [[LoganSessionDelegateProxy alloc] initWithDelegate:delegate];
    }
  } @catch (NSException *e) { watchDelegate = delegate; }
  return gOrigSession3(self, _cmd, LoganPrepareConfigForObserve(cfg), watchDelegate, queue);
}

static void LoganPatchSessionFactories(void) {
  @try {
    Class cls = [NSURLSession class];
    Method s2 = class_getClassMethod(cls, @selector(sessionWithConfiguration:));
    Method s3 = class_getClassMethod(cls, @selector(sessionWithConfiguration:delegate:delegateQueue:));
    if (s2) { gOrigSession2 = (LoganSession2Fn)method_getImplementation(s2); method_setImplementation(s2, (IMP)LoganSessionWithCfg); }
    if (s3) { gOrigSession3 = (LoganSession3Fn)method_getImplementation(s3); method_setImplementation(s3, (IMP)LoganSessionWithCfgDelegateQueue); }
  } @catch (NSException *e) {
    NSLog(@"[LoganHttp] patch session factories exception %@", e.name);
  }
}

// ── NSURLSession 任务级观察（定案兜底路线）──────────────────────────
// 实测否证：该 Foundation 上 NSURLSession 从不咨询 configuration.protocolClasses，
// 追加 NSURLProtocol 后 canInitWithRequest 对业务请求一次都不触发。改为零侵入旁路观察：
//  1) -dataTaskWithRequest:completionHandler: 钩上包一层业务回调 → 覆盖所有 completionHandler 形态；
//  2) 建会话时把调用方 delegate 包 LoganSessionDelegateProxy 透传 → 覆盖 AFNetworking 3.x 等
//     「无 completionHandler + session delegate」形态（其 delegate 语义原样保留，含
//     didReceiveResponse 的 completionHandler 唯一调用权、challenge 与重定向全部先转发）；
//  3) 双路以 task 维度去重，绝不双计；自家上报会话（marked config）整段放行。
// 稳定性红线：观察代码全部 @try 吞异常，判定不了就不上报；不改写请求、不接管转发。
static NSMapTable *gLoganTaskStart = nil;    // task -> createdAt(ms) 弱键
static NSMapTable *gLoganTaskBytes = nil;    // task -> 累计响应字节
static NSMapTable *gLoganTaskResp = nil;     // task -> NSHTTPURLResponse
static NSMapTable *gLoganTaskTtfb = nil;     // task -> ttfb(ms)
static NSMapTable *gLoganTaskReported = nil; // task -> 已上报标记（双路去重）
static NSLock *gLoganTaskLock = nil;

static void LoganTaskTablesOnce(void) {
  static dispatch_once_t once;
  dispatch_once(&once, ^{
    gLoganTaskLock = [[NSLock alloc] init];
    gLoganTaskStart = [NSMapTable mapTableWithKeyOptions:NSMapTableWeakMemory valueOptions:NSMapTableStrongMemory];
    gLoganTaskBytes = [NSMapTable mapTableWithKeyOptions:NSMapTableWeakMemory valueOptions:NSMapTableStrongMemory];
    gLoganTaskResp = [NSMapTable mapTableWithKeyOptions:NSMapTableWeakMemory valueOptions:NSMapTableStrongMemory];
    gLoganTaskTtfb = [NSMapTable mapTableWithKeyOptions:NSMapTableWeakMemory valueOptions:NSMapTableStrongMemory];
    gLoganTaskReported = [NSMapTable mapTableWithKeyOptions:NSMapTableWeakMemory valueOptions:NSMapTableStrongMemory];
  });
}

static long long LoganNowMs(void) { return (long long)([[NSDate date] timeIntervalSince1970] * 1000.0); }

static void LoganRememberTask(NSURLSessionTask *t, long long atMs) {
  if (!t) { return; }
  LoganTaskTablesOnce();
  [gLoganTaskLock lock];
  [gLoganTaskStart setObject:@(atMs) forKey:t];
  [gLoganTaskLock unlock];
}

static void LoganPutTaskInfo(NSURLSessionTask *t, NSMapTable *table, id value) {
  if (!t || !table) { return; }
  LoganTaskTablesOnce();
  [gLoganTaskLock lock];
  if (value) { [table setObject:value forKey:t]; } else { [table removeObjectForKey:t]; }
  [gLoganTaskLock unlock];
}

static id LoganGetTaskInfo(NSURLSessionTask *t, NSMapTable *table) {
  if (!t || !table) { return nil; }
  LoganTaskTablesOnce();
  [gLoganTaskLock lock];
  id v = [table objectForKey:t];
  [gLoganTaskLock unlock];
  return v;
}

// 返回 YES = 本次已有人报过，调用方不再上报
static BOOL LoganTaskReported(NSURLSessionTask *t) {
  if (!t) { return YES; }
  LoganTaskTablesOnce();
  BOOL dup = YES;
  [gLoganTaskLock lock];
  if (![gLoganTaskReported objectForKey:t]) { [gLoganTaskReported setObject:@(1) forKey:t]; dup = NO; }
  [gLoganTaskLock unlock];
  return dup;
}

static BOOL LoganShouldObserveTaskRequest(NSURLRequest *req) {
  @try {
    if (!req || ![[LoganHttp shared] isEnabled]) { return NO; }
    NSString *scheme = req.URL.scheme.lowercaseString;
    if (![scheme isEqualToString:@"http"] && ![scheme isEqualToString:@"https"]) { return NO; }
    if ([[LoganHttp shared] isSelfUrl:req.URL.absoluteString ?: @""]) { return NO; }
    if (![[LoganHttp shared] shouldObserveRequest:req]) { return NO; }
    NSString *m = req.HTTPMethod.uppercaseString ?: @"GET";
    if ([m isEqualToString:@"CONNECT"]) { return NO; }
    if ([LoganHttpProtocol isStaticResource:req.URL.path ?: @"" method:m]) { return NO; }
    return YES;
  } @catch (NSException *e) { return NO; }
}

static BOOL LoganSessionOf(NSURLSession *session) {
  @try { return session && !LoganSessionMarkedNoObserve(session.configuration); }
  @catch (NSException *e) { return NO; }   // 判定不了当自家：宁漏采不自环
}

static void LoganReportTaskEvent(NSURLSessionTask *task, NSData *data, NSError *err, long long startMs) {
  @try {
    if (!task) { return; }
    NSURLRequest *req = task.originalRequest ?: task.currentRequest;
    if (!LoganShouldObserveTaskRequest(req)) { return; }
    if (LoganTaskReported(task)) { return; }
    LoganHttpEvent *e = [LoganHttpEvent new];
    e.kind = @"native";
    e.method = req.HTTPMethod ?: @"GET";
    e.url = req.URL.absoluteString;
    long long now = LoganNowMs();
    e.durationMs = (startMs > 0 && now > startMs) ? (now - startMs) : 0;
    e.occurredAt = startMs > 0 ? startMs : now;
    NSHTTPURLResponse *resp = nil;
    id cached = LoganGetTaskInfo(task, gLoganTaskResp);
    if ([cached isKindOfClass:[NSHTTPURLResponse class]]) { resp = (NSHTTPURLResponse *)cached; }
    long long bytes = data ? (long long)data.length : 0;
    NSNumber *cachedBytes = (NSNumber *)LoganGetTaskInfo(task, gLoganTaskBytes);
    if ([cachedBytes isKindOfClass:[NSNumber class]] && cachedBytes.longLongValue > bytes) { bytes = cachedBytes.longLongValue; }
    if (resp) {
      e.status = (int)resp.statusCode;
      if (bytes <= 0 && resp.expectedContentLength > 0) { bytes = (long long)resp.expectedContentLength; }
    }
    if (bytes > 0) { e.respBytes = @(bytes); }
    NSNumber *ttfb = (NSNumber *)LoganGetTaskInfo(task, gLoganTaskTtfb);
    if ([ttfb isKindOfClass:[NSNumber class]] && ttfb.longLongValue >= 0) { e.ttfbMs = ttfb; }   // 取不到的键整个不写
    if (err) {
      e.errMsg = [NSString stringWithFormat:@"%@ domain=%@ code=%ld",
                  err.localizedDescription ?: @"", err.domain ?: @"", (long)err.code];
    } else if (resp && resp.statusCode >= 400) {
      e.errMsg = [NSString stringWithFormat:@"HTTP %ld", (long)resp.statusCode];
    }
    if (req.HTTPBody.length > 0) { e.reqBytes = @((long long)req.HTTPBody.length); }
    [[LoganHttp shared] report:e];
    LoganPutTaskInfo(task, gLoganTaskResp, nil);
    LoganPutTaskInfo(task, gLoganTaskBytes, nil);
    LoganPutTaskInfo(task, gLoganTaskTtfb, nil);
    LoganPutTaskInfo(task, gLoganTaskStart, nil);
  } @catch (NSException *e) {}
}

@implementation LoganSessionDelegateProxy

- (instancetype)initWithDelegate:(id)delegate {
  self = [super init];
  if (self) { _realDelegate = delegate; }
  return self;
}

static BOOL LoganIsInterceptedSel(SEL sel) {
  return sel == @selector(URLSession:task:didReceiveResponse:completionHandler:) ||
         sel == @selector(URLSession:task:didReceiveData:) ||
         sel == @selector(URLSession:task:didCompleteWithError:) ||
         sel == @selector(URLSession:task:willPerformHTTPRedirection:newRequest:completionHandler:);
}

- (BOOL)respondsToSelector:(SEL)sel {
  if (LoganIsInterceptedSel(sel)) { return YES; }   // 让 NSURLSession 一定回调本代理
  id real = _realDelegate;
  return real && [real respondsToSelector:sel];
}

- (id)forwardingTargetForSelector:(SEL)sel {
  return _realDelegate;   // 其余 delegate 回调原样透传（challenge/上传/缓存等零改写）
}

- (NSMethodSignature *)methodSignatureForSelector:(SEL)sel {
  NSMethodSignature *sig = [super methodSignatureForSelector:sel];
  if (!sig && _realDelegate) { sig = [_realDelegate methodSignatureForSelector:sel]; }
  return sig;
}

- (void)forwardInvocation:(NSInvocation *)inv {
  if (_realDelegate) { [inv invokeWithTarget:_realDelegate]; }
}

- (void)URLSession:(NSURLSession *)session task:(NSURLSessionTask *)task
   didReceiveResponse:(NSURLResponse *)response
    completionHandler:(void (^)(NSURLSessionResponseDisposition))completionHandler {
  @try {
    long long startMs = 0;
    NSNumber *st = (NSNumber *)LoganGetTaskInfo(task, gLoganTaskStart);
    if ([st isKindOfClass:[NSNumber class]]) { startMs = st.longLongValue; }
    else { startMs = LoganNowMs(); LoganRememberTask(task, startMs); }   // 非 dataTask 系创建的任务：以首次响应为起点
    if ([response isKindOfClass:[NSHTTPURLResponse class]]) {
      LoganPutTaskInfo(task, gLoganTaskResp, response);
      if (startMs > 0) { LoganPutTaskInfo(task, gLoganTaskTtfb, @(LoganNowMs() - startMs)); }
    }
  } @catch (NSException *e) {}
  id real = _realDelegate;
  if (real && [real respondsToSelector:@selector(URLSession:task:didReceiveResponse:completionHandler:)]) {
    // completionHandler 唯一调用权交业务 delegate（语义不变）
    [real URLSession:session task:task didReceiveResponse:response completionHandler:completionHandler];
  } else if (completionHandler) {
    completionHandler(NSURLSessionResponseAllow);   // 业务没实现 → 与不实现时的系统默认一致
  }
}

- (void)URLSession:(NSURLSession *)session task:(NSURLSessionTask *)task didReceiveData:(NSData *)data {
  @try {
    if (data.length > 0) {
      long long acc = 0;
      NSNumber *old = (NSNumber *)LoganGetTaskInfo(task, gLoganTaskBytes);
      if ([old isKindOfClass:[NSNumber class]]) { acc = old.longLongValue; }
      LoganPutTaskInfo(task, gLoganTaskBytes, @(acc + (long long)data.length));
    }
  } @catch (NSException *e) {}
  id real = _realDelegate;
  if (real && [real respondsToSelector:@selector(URLSession:task:didReceiveData:)]) {
    [real URLSession:session task:task didReceiveData:data];
  }
}

- (void)URLSession:(NSURLSession *)session task:(NSURLSessionTask *)task didCompleteWithError:(NSError *)error {
  long long startMs = 0;
  @try {
    NSNumber *st = (NSNumber *)LoganGetTaskInfo(task, gLoganTaskStart);
    if ([st isKindOfClass:[NSNumber class]]) { startMs = st.longLongValue; }
    LoganReportTaskEvent(task, nil, error, startMs);
  } @catch (NSException *e) {}
  id real = _realDelegate;
  if (real && [real respondsToSelector:@selector(URLSession:task:didCompleteWithError:)]) {
    [real URLSession:session task:task didCompleteWithError:error];
  }
}

- (void)URLSession:(NSURLSession *)session task:(NSURLSessionTask *)task
    willPerformHTTPRedirection:(NSHTTPURLResponse *)response
                    newRequest:(NSURLRequest *)request
             completionHandler:(void (^)(NSURLRequest *_Nullable))completionHandler {
  id real = _realDelegate;
  if (real && [real respondsToSelector:_cmd]) {
    [real URLSession:session task:task willPerformHTTPRedirection:response newRequest:request completionHandler:completionHandler];
  } else if (completionHandler) {
    completionHandler(request);   // 与不实现时的系统默认一致
  }
}

@end

// ── dataTask 钩（completionHandler 形态包回调；无回调形态只记创建时刻）──
typedef NSURLSessionDataTask *(*LoganDataTask2Fn)(id, SEL, NSURLRequest *);
typedef NSURLSessionDataTask *(*LoganDataTask3Fn)(id, SEL, NSURLRequest *, void (^)(NSData *, NSURLResponse *, NSError *));
static LoganDataTask2Fn gOrigDataTask2 = NULL;
static LoganDataTask3Fn gOrigDataTask3 = NULL;

static NSURLSessionDataTask *LoganDataTaskNoHandler(id self, SEL _cmd, NSURLRequest *request) {
  NSURLSessionDataTask *t = gOrigDataTask2(self, _cmd, request);
  @try {
    if (LoganSessionOf((NSURLSession *)self) && LoganShouldObserveTaskRequest(request)) {
      LoganRememberTask(t, LoganNowMs());
    }
  } @catch (NSException *e) {}
  return t;
}

static NSURLSessionDataTask *LoganDataTaskWithHandler(id self, SEL _cmd, NSURLRequest *request,
                                                      void (^handler)(NSData *, NSURLResponse *, NSError *)) {
  BOOL observe = NO;
  long long startMs = LoganNowMs();
  @try { observe = LoganSessionOf((NSURLSession *)self) && LoganShouldObserveTaskRequest(request); }
  @catch (NSException *e) { observe = NO; }
  if (!observe || !handler) { return gOrigDataTask3(self, _cmd, request, handler); }
  __block NSURLSessionDataTask *task = nil;
  void (^wrapped)(NSData *, NSURLResponse *, NSError *) = ^(NSData *d, NSURLResponse *r, NSError *err) {
    @try {
      // completionHandler 形态没有 delegate 回调，响应与 ttfb 在这里补缓存（否则 status=0）
      if ([r isKindOfClass:[NSHTTPURLResponse class]] && !LoganGetTaskInfo(task, gLoganTaskResp)) {
        LoganPutTaskInfo(task, gLoganTaskResp, r);
        if (startMs > 0 && !LoganGetTaskInfo(task, gLoganTaskTtfb)) { LoganPutTaskInfo(task, gLoganTaskTtfb, @(LoganNowMs() - startMs)); }
      }
      LoganReportTaskEvent(task, d, err, startMs);
    } @catch (NSException *e) {}
    handler(d, r, err);   // 业务回调永远执行（即便观察层出异常）
  };
  task = gOrigDataTask3(self, _cmd, request, wrapped);
  @try { LoganRememberTask(task, startMs); } @catch (NSException *e) {}
  return task;
}

static void LoganPatchDataTasks(void) {
  @try {
    Class cls = [NSURLSession class];
    Method d2 = class_getInstanceMethod(cls, @selector(dataTaskWithRequest:));
    Method d3 = class_getInstanceMethod(cls, @selector(dataTaskWithRequest:completionHandler:));
    if (d2) { gOrigDataTask2 = (LoganDataTask2Fn)method_getImplementation(d2); method_setImplementation(d2, (IMP)LoganDataTaskNoHandler); }
    if (d3) { gOrigDataTask3 = (LoganDataTask3Fn)method_getImplementation(d3); method_setImplementation(d3, (IMP)LoganDataTaskWithHandler); }
  } @catch (NSException *e) {}
}

@implementation LoganHttpProtocol

+ (void)installObserver {
    @synchronized (self) {
        if (gObserverInstalled) { return; }
        gObserverInstalled = YES;
        // 全局注册：覆盖 NSURLConnection / CFNetwork 系（第三方 SDK 常见）
        [NSURLProtocol registerClass:[LoganHttpProtocol class]];
        // NSURLSession 只认 configuration.protocolClasses（+registerClass 对它无效，
        //  实测：AFNetworking/URLSession 默认配置的请求一条都不进），
        //  所以把本类追加进三个配置工厂的返回值里 —— 显式赋 protocolClasses 的调用方仍然说了算。
        LoganPatchSessionConfigurationFactory();
        LoganPatchSessionFactories();
        LoganPatchDataTasks();
        // H5 容器注入钩子随观察器一起装（宿主零接线，m06933 通用性）
        @try { [LoganH5Bridge install]; } @catch (NSException *e) {}
    }
}

/// 只观测"业务 API 调用"，把静态资源与流式上传排除在接管之外：
///  - 图片/字体/媒体等 GET 由系统直连，既无观测价值也不该被我们二次转发；
///  - HTTPBodyStream（分片上传/表单流）不接管 —— 这类 body 无法安全重放，
///    强行接管会让业务上传静默失败，属于稳定性红线，宁缺毋假。
+ (BOOL)isStaticResource:(NSString *)path method:(NSString *)method {
    if (path.length == 0) { return NO; }
    NSString *p = path.lowercaseString;
    static NSArray<NSString *> *exts = nil;
    static dispatch_once_t once;
    dispatch_once(&once, ^{
        exts = @[@".png", @".jpg", @".jpeg", @".gif", @".webp", @".bmp", @".svg", @".ico",
                 @".css", @".woff", @".woff2", @".ttf", @".otf", @".eot",
                 @".mp3", @".mp4", @".m4a", @".wav", @".aac", @".mov",
                 @".bundle", @".js", @".map"];
    });
    (void)method;
    for (NSString *e in exts) {
        if ([p hasSuffix:e]) { return YES; }
    }
    return NO;
}

+ (BOOL)canInitWithRequest:(NSURLRequest *)request {
    static int gLoganCanInitLog = 0;
    BOOL dbg = (gLoganCanInitLog < 40);
    NSString *why = @"yes";
    BOOL verdict = NO;
    @try {
        if (![LoganHttp shared].isEnabled) { why = @"disabled"; }
        else if ([NSURLProtocol propertyForKey:kLoganObservedKey inRequest:request] != nil) { why = @"already-observed"; }
        else if (request.HTTPBodyStream != nil) { why = @"body-stream"; }
        else {
            NSString *scheme = request.URL.scheme.lowercaseString;
            if (![scheme isEqualToString:@"http"] && ![scheme isEqualToString:@"https"]) { why = @"scheme"; }
            else if ([[LoganHttp shared] isSelfUrl:request.URL.absoluteString ?: @""]) { why = @"self-url"; }
            else if (![[LoganHttp shared] shouldObserveRequest:request]) { why = @"business-exclude"; }
            else {
                NSString *m = request.HTTPMethod.uppercaseString ?: @"GET";
                if ([m isEqualToString:@"CONNECT"]) { why = @"connect"; }
                else if ([self isStaticResource:request.URL.path ?: @"" method:m]) { why = @"static"; }
                else { verdict = YES; }
            }
        }
    } @catch (NSException *e) {
        why = @"exception";
        verdict = NO;   // 判定不了就交还系统，业务请求永远能正常走
    }
    if (dbg) {
        gLoganCanInitLog++;
    }
    return verdict;
}

+ (NSURLRequest *)canonicalRequestForRequest:(NSURLRequest *)request {
    @try {
        NSMutableURLRequest *m = [request mutableCopy];
        [NSURLProtocol setProperty:@(YES) forKey:kLoganObservedKey inRequest:m];
        return m;
    } @catch (NSException *e) {
        return request;
    }
}

- (void)startLoading {
    @try {
        self.startTime = CFAbsoluteTimeGetCurrent();
        self.ttfbMs = -1;
        self.respBytes = 0;
        self.finished = NO;
        NSURLSessionConfiguration *cfg = [NSURLSessionConfiguration ephemeralSessionConfiguration];
        cfg.protocolClasses = @[];
        LoganMarkSessionNoObserve(cfg);   // 关键：转发会话不再经过任何自定义 protocol，杜绝递归
        cfg.requestCachePolicy = self.request.cachePolicy;
        cfg.HTTPShouldSetCookies = NO;
        self.fwdSession = [NSURLSession sessionWithConfiguration:cfg delegate:self delegateQueue:nil];
        self.fwdTask = [self.fwdSession dataTaskWithRequest:self.request];
        [self.fwdTask resume];
    } @catch (NSException *e) {
        NSError *err = [NSError errorWithDomain:NSURLErrorDomain code:NSURLErrorUnknown userInfo:nil];
        [self.client URLProtocol:self didFailWithError:err];  // 失败回落原始语义，不让业务挂起
    }
}

- (void)stopLoading {
    self.finished = YES;
    @try { [self.fwdTask cancel]; } @catch (NSException *e) {}
    @try { [self.fwdSession invalidateAndCancel]; } @catch (NSException *e) {}
}

#pragma mark - NSURLSessionDataDelegate（逐条转发给原始 client，业务语义完全不变）

- (void)URLSession:(NSURLSession *)session dataTask:(NSURLSessionDataTask *)dataTask
    didReceiveResponse:(NSURLResponse *)response
     completionHandler:(void (^)(NSURLSessionResponseDisposition))completionHandler {
    if ([response isKindOfClass:[NSHTTPURLResponse class]]) {
        self.pendingResponse = (NSHTTPURLResponse *)response;
        if (self.ttfbMs < 0) {
            self.ttfbMs = (long long)((CFAbsoluteTimeGetCurrent() - self.startTime) * 1000);
        }
    }
    [self.client URLProtocol:self didReceiveResponse:response cacheStoragePolicy:NSURLCacheStorageNotAllowed];
    completionHandler(NSURLSessionResponseAllow);
}

- (void)URLSession:(NSURLSession *)session dataTask:(NSURLSessionDataTask *)dataTask didReceiveData:(NSData *)data {
    self.respBytes += (long long)data.length;
    [self.client URLProtocol:self didLoadData:data];
}

- (void)URLSession:(NSURLSession *)session task:(NSURLSessionTask *)task
                didCompleteWithError:(nullable NSError *)error {
    if (self.finished) {
        [session finishTasksAndInvalidate];
        return;
    }
    self.finished = YES;
    BOOL cancelled = (error.code == NSURLErrorCancelled);
    if (error && !cancelled) {
        [self.client URLProtocol:self didFailWithError:error];
    } else if (cancelled) {
        [self.client URLProtocol:self didFailWithError:error];
    } else {
        [self.client URLProtocolDidFinishLoading:self];
    }
    if (!cancelled) {   // 主动取消（列表复用/页面销毁）量大且无归因价值：不入库
        [self reportEventWithError:error status:self.pendingResponse.statusCode];
    }
    [session finishTasksAndInvalidate];
}

- (void)URLSession:(NSURLSession *)session
                              task:(NSURLSessionTask *)task
              willPerformHTTPRedirection:(NSHTTPURLResponse *)response
                       newRequest:(NSURLRequest *)request
                completionHandler:(void (^)(NSURLRequest *_Nullable))completionHandler {
  @try {
    if (response) {
      self.pendingResponse = response;
      [self.client URLProtocol:self wasRedirectedToRequest:request redirectResponse:response];
      return;   // 重定向后由系统继续，原任务不再回调 didFinish
    }
  } @catch (NSException *e) {}
  if (completionHandler) { completionHandler(request); }
}

#pragma mark - 事件产出

- (void)reportEventWithError:(nullable NSError *)error status:(NSInteger)status {
    @try {
        NSURLRequest *req = self.request;
        LoganHttpEvent *ev = [LoganHttpEvent new];
        ev.kind = @"native";
        ev.method = req.HTTPMethod ?: @"GET";
        ev.url = req.URL.absoluteString ?: @"";
        ev.host = req.URL.host ?: @"";
        ev.path = req.URL.path ?: @"";
        ev.status = (int)status;
        long long dur = (long long)((CFAbsoluteTimeGetCurrent() - self.startTime) * 1000);
        ev.durationMs = dur < 0 ? 0 : dur;
        if (self.ttfbMs >= 0) { ev.ttfbMs = @(self.ttfbMs); }
        if (self.respBytes > 0) { ev.respBytes = @(self.respBytes); }
        if (req.HTTPBody.length > 0) { ev.reqBytes = @((long long)req.HTTPBody.length); }
        if (error) {
            NSMutableDictionary *ui = [NSMutableDictionary dictionary];
            if (error.domain.length > 0) { ui[@"domain"] = error.domain; }
            ui[@"code"] = @(error.code);
            NSString *msg = error.localizedDescription ?: @"";
            NSData *d = [NSJSONSerialization dataWithJSONObject:ui options:0 error:NULL];
            ev.errMsg = [msg length] > 0 ? [NSString stringWithFormat:@"%@ %@", msg,
                        ([[NSString alloc] initWithData:d encoding:NSUTF8StringEncoding] ?: @"")]
                        : [NSString stringWithFormat:@"domain=%@ code=%ld", error.domain, (long)error.code];
            // errKind 不在客户端猜：服务端 HttpNorm.errKindOf 统一归类（cancel/timeout/ssl/connect/parse/other）
        } else if (status >= 400) {
            ev.errMsg = [NSString stringWithFormat:@"HTTP %ld", (long)status];
        }
        [[LoganHttp shared] report:ev];
    } @catch (NSException *e) {
        // 观测异常绝不影响已转发的业务请求
    }
}



// 自签/内网部署证书放行（仅 Logan 自家域名与局域网验收通道，其余交回系统默认评估）。
- (void)URLSession:(NSURLSession *)session
didReceiveAuthenticationChallenge:(NSURLAuthenticationChallenge *)challenge
 completionHandler:(void (^)(NSURLSessionAuthChallengeDisposition disposition, NSURLCredential * _Nullable credential))completionHandler {
  @try {
    NSString *method = challenge.protectionSpace.authenticationMethod;
    NSString *host = challenge.protectionSpace.host ?: @"";
    if ([method isEqualToString:NSURLAuthenticationMethodServerTrust]) {
      NSString *base = [LoganHttp shared].baseUrlLowercased ?: @"";
      NSString *h = host.lowercaseString;
      BOOL selfHost = (base.length > 0 && ([h isEqualToString:base] || [h hasSuffix:[@"." stringByAppendingString:base]]))
        || [h isEqualToString:@"127.0.0.1"] || [h isEqualToString:@"localhost"]
        || [h hasPrefix:@"10."] || [h hasPrefix:@"192.168."] || [h hasPrefix:@"100."];
      if (selfHost) {
        SecTrustRef trust = challenge.protectionSpace.serverTrust;
        completionHandler(NSURLSessionAuthChallengeUseCredential,
                          trust ? [NSURLCredential credentialForTrust:trust] : nil);
        return;
      }
    }
  } @catch (NSException *e) { }
  completionHandler(NSURLSessionAuthChallengePerformDefaultHandling, nil);
}

@end
