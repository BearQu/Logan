//
//  LoganCommands.m
//  Logan PoC —— 平台主动拉日志的客户端轮询器（零第三方依赖，仅 NSURLSession）。
//
#import "LoganCommands.h"
#import "LoganDeviceInfo.h"

static NSString *gBaseUrl = nil;
static NSString *gAppId = nil;
static NSString *gDeviceId = nil;
static NSString *gAppVersion = nil;
static NSString *gBuildVersion = nil;
static NSString *gAuthHeader = nil;
static LoganCommandHandler gHandler = nil;
static NSTimeInterval gInterval = 60;
static BOOL gRunning = NO;
static BOOL gLoganDiInfoReported = NO;
static BOOL gLoganDiInfoInFlight = NO;
static NSTimer *gTimer = nil;

static NSMutableURLRequest *LoganCmdRequest(NSString *path) {
    NSMutableURLRequest *req = [NSMutableURLRequest requestWithURL:[NSURL URLWithString:[gBaseUrl stringByAppendingString:path]]];
    req.HTTPMethod = @"POST";
    req.timeoutInterval = 20;
    [req setValue:gAppId forHTTPHeaderField:@"appId"];
    if (gDeviceId.length > 0) { [req setValue:gDeviceId forHTTPHeaderField:@"deviceId"]; }
    [req setValue:@"2" forHTTPHeaderField:@"platform"];
    if (gAppVersion.length > 0) { [req setValue:gAppVersion forHTTPHeaderField:@"appVersion"]; }
    if (gBuildVersion.length > 0) { [req setValue:gBuildVersion forHTTPHeaderField:@"buildVersion"]; }
    [req setValue:@"0.1.0" forHTTPHeaderField:@"sdkVersion"];
    if (gAuthHeader.length > 0) { [req setValue:gAuthHeader forHTTPHeaderField:@"Authorization"]; }
    return req;
}

static void LoganCmdPost(NSString *path, NSDictionary *body, void (^done)(NSDictionary *json)) {
    if (gBaseUrl.length == 0 || gAppId.length == 0) { if (done != nil) { done(nil); } return; }
    NSMutableURLRequest *req = LoganCmdRequest(path);
    [req setValue:@"application/json" forHTTPHeaderField:@"Content-Type"];
    req.HTTPBody = [NSJSONSerialization dataWithJSONObject:(body != nil ? body : @{}) options:0 error:NULL];
    NSURLSession *session = [NSURLSession sessionWithConfiguration:[NSURLSessionConfiguration ephemeralSessionConfiguration]];
    [[session dataTaskWithRequest:req completionHandler:^(NSData *data, NSURLResponse *resp, NSError *error) {
        NSDictionary *json = nil;
        NSInteger code = [(NSHTTPURLResponse *)resp statusCode];
        if (data.length > 0 && code == 200) {
            id parsed = [NSJSONSerialization JSONObjectWithData:data options:0 error:NULL];
            if ([parsed isKindOfClass:[NSDictionary class]]) { json = (NSDictionary *)parsed; }
        } else {
            NSLog(@"[LoganCommands] http=%ld err=%@", (long)code, error.localizedDescription ?: @"-");
        }
        if (done != nil) { done(json); }
    }] resume];
}


static void LoganCmdAck(long long commandId, BOOL ok, NSString *note);

/// 设备信息快照上报（平台侧只接受合法 JSON 对象字符串，>64KB 会被拒）。
/// trigger=boot 随心跳顺带一次；trigger=command 由平台 collect_info 命令触发。
static void LoganDiReportInfo(long long commandId, NSString *trigger) {
    if (gAppId.length == 0 || gDeviceId.length == 0) { return; }
    if (gLoganDiInfoInFlight) { return; }
    gLoganDiInfoInFlight = YES;
    NSString *json = [LoganDeviceInfo snapshotJSON];
    if (json.length == 0) {
        gLoganDiInfoInFlight = NO;
        NSLog(@"[LoganCommands] device info snapshot empty, skip trigger=%@", trigger);
        if (commandId > 0) { LoganCmdAck(commandId, NO, @"snapshot empty"); }
        return;
    }
    NSMutableDictionary *body = [NSMutableDictionary dictionary];
    body[@"appId"] = gAppId;
    body[@"deviceId"] = gDeviceId;
    body[@"platform"] = @2;
    if (gAppVersion.length > 0) { body[@"appVersion"] = gAppVersion; }
    if (gBuildVersion.length > 0) { body[@"buildVersion"] = gBuildVersion; }
    body[@"sdkVersion"] = @"0.1.0";
    body[@"trigger"] = (trigger != nil ? trigger : @"command");
    body[@"info"] = json; // 以字符串上送，服务端 String.valueOf 原样收下并校验 JSON
    LoganCmdPost(@"/logan/device/info/report.json", body, ^(NSDictionary *resp) {
        gLoganDiInfoInFlight = NO;
        BOOL ok = (resp != nil && [resp[@"code"] integerValue] == 200);
        NSLog(@"[LoganCommands] device info report trigger=%@ ok=%d bytes=%lu", trigger, ok, (unsigned long)json.length);
        if (commandId > 0) { LoganCmdAck(commandId, ok, ok ? @"info reported" : @"report failed"); }
    });
}
static void LoganCmdRunNext(NSArray *cmds, NSUInteger i);

static void LoganCmdAck(long long commandId, BOOL ok, NSString *note) {
    NSMutableDictionary *body = [NSMutableDictionary dictionary];
    body[@"id"] = @(commandId);
    body[@"ok"] = ok ? @(YES) : @(NO);
    if (note.length > 0) { body[@"resultNote"] = note; }
    LoganCmdPost(@"/logan/device/command/ack.json", body, nil);
}

static void LoganCmdSettle(long long cid, BOOL ok, NSString *note, NSArray *cmds, NSUInteger i) {
    LoganCmdAck(cid, ok, note);
    LoganCmdRunNext(cmds, i + 1);
}

static void LoganCmdRunNext(NSArray *cmds, NSUInteger i) {
    if (cmds == nil || i >= cmds.count) { return; }
    id item = cmds[i];
    if (![item isKindOfClass:[NSDictionary class]]) { LoganCmdRunNext(cmds, i + 1); return; }
    NSDictionary *cmd = (NSDictionary *)item;
    long long cid = [cmd[@"id"] longLongValue];
    NSString *action = [cmd[@"action"] isKindOfClass:[NSString class]] ? (NSString *)cmd[@"action"] : @"";
    NSString *param = [cmd[@"param"] isKindOfClass:[NSString class]] ? (NSString *)cmd[@"param"] : nil;
    if (cid <= 0) { LoganCmdRunNext(cmds, i + 1); return; }
    NSLog(@"[LoganCommands] command received id=%lld action=%@", cid, action);
    if ([action isEqualToString:@"collect_info"]) {
        // 设备信息采集是 SDK 自身职责，不下放宿主
        LoganDiReportInfo(cid, @"command");
        LoganCmdRunNext(cmds, i + 1);
        return;
    }
    LoganCommandHandler handler = gHandler;
    if (handler == nil) { LoganCmdSettle(cid, NO, @"no handler", cmds, i); return; }
    __block BOOL settled = NO;
    void (^complete)(BOOL, NSString *) = ^(BOOL ok, NSString *note) {
        if (settled) { return; }
        settled = YES;
        LoganCmdSettle(cid, ok, note, cmds, i);
    };
    dispatch_async(dispatch_get_main_queue(), ^{
        handler(action, cid, param, ^(BOOL ok, NSString *note) {
            if ([NSThread isMainThread]) { complete(ok, note); }
            else { dispatch_async(dispatch_get_main_queue(), ^{ complete(ok, note); }); }
        });
    });
}

static void LoganCmdPollOnce(void) {
    if (gAppId.length == 0 || gDeviceId.length == 0) { return; }
    NSMutableDictionary *body = [NSMutableDictionary dictionary];
    body[@"appId"] = gAppId;
    body[@"deviceId"] = gDeviceId;
    body[@"platform"] = @2;
    if (gAppVersion.length > 0) { body[@"appVersion"] = gAppVersion; }
    if (gBuildVersion.length > 0) { body[@"buildVersion"] = gBuildVersion; }
    body[@"sdkVersion"] = @"0.1.0";
    if (!gLoganDiInfoReported) {
        gLoganDiInfoReported = YES;
        LoganDiReportInfo(0, @"boot"); // 冷启动随心跳带一次，平台设备清单立即有数据
    }
    LoganCmdPost(@"/logan/device/poll.json", body, ^(NSDictionary *json) {
        if (json == nil) { return; }
        if ([json[@"code"] integerValue] != 200) { return; }
        NSArray *cmds = [json[@"data"] isKindOfClass:[NSArray class]] ? (NSArray *)json[@"data"] : nil;
        if (cmds.count == 0) { return; }
        LoganCmdRunNext(cmds, 0);
    });
}

@implementation LoganCommands

+ (void)configureWithBaseUrl:(NSString *)baseUrl
                       appId:(NSString *)appId
                    deviceId:(NSString *)deviceId
                  appVersion:(NSString *)appVersion
                buildVersion:(NSString *)buildVersion
                  authHeader:(NSString *)authHeader {
    gBaseUrl = [baseUrl copy];
    gAppId = [appId copy];
    gDeviceId = [deviceId copy];
    gAppVersion = [appVersion copy];
    gBuildVersion = [buildVersion copy];
    gAuthHeader = [authHeader copy];
}

+ (void)setHandler:(LoganCommandHandler)handler {
    gHandler = [handler copy];
}

+ (void)setIntervalSeconds:(NSTimeInterval)seconds {
    gInterval = seconds < 15 ? 15 : seconds;
}

+ (void)tick:(NSTimer *)timer {
    (void)timer;
    dispatch_async(dispatch_get_global_queue(QOS_CLASS_UTILITY, 0), ^{
        LoganCmdPollOnce();
    });
}

+ (void)start {
    @synchronized ([LoganCommands class]) {
        if (gRunning) { return; }
        if (gAppId.length == 0 || gDeviceId.length == 0) { return; }
        gRunning = YES;
    }
    NSLog(@"[LoganCommands] command poller started interval=%lds", (long)gInterval);
    dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(8 * NSEC_PER_SEC)), dispatch_get_global_queue(QOS_CLASS_UTILITY, 0), ^{
        LoganCmdPollOnce();
    });
    dispatch_async(dispatch_get_main_queue(), ^{
        if (gTimer != nil) { return; }
        gTimer = [NSTimer scheduledTimerWithTimeInterval:(gInterval < 15 ? 15 : gInterval)
                                                  target:[LoganCommands class]
                                                selector:@selector(tick:)
                                                userInfo:nil
                                                 repeats:YES];
    });
}

+ (void)reportDeviceInfoNow {
    gLoganDiInfoReported = YES;
    LoganDiReportInfo(0, @"manual");
}
+ (void)stop {
    @synchronized ([LoganCommands class]) {
        gRunning = NO;
    }
    dispatch_async(dispatch_get_main_queue(), ^{
        if (gTimer != nil) { [gTimer invalidate]; gTimer = nil; }
    });
}

@end
