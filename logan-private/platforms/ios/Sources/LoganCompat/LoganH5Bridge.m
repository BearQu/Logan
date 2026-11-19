//
//  LoganH5Bridge.m
//  Logan PoC
//
#import "LoganH5Bridge.h"
#import "LoganHttp.h"
#import "LoganH5Script.h"
#import "LoganErrorKit.h"
#import "LoganBehaviorKit.h"
#import <WebKit/WebKit.h>
#import <objc/runtime.h>

static BOOL gH5Enabled = YES;
static BOOL gH5Installed = NO;
static NSString *const kLoganH5BridgeName = @"LoganH5";

@class WKUserContentController;
@interface LoganH5MessageHandler : NSObject <WKScriptMessageHandler>
@end

/// 注入顺序：先建桥（同步、必须在业务脚本之前），再跑共用采集脚本。
/// 桥的 postJson 同步返回 true 表示"已接管"，脚本据此不再走降级 fetch。
static NSString *LoganH5BootstrapJS(NSString *configJson) {
    NSMutableString *s = [NSMutableString string];
    [s appendString:@"(function(){'use strict';"];
    [s appendString:@"if(window.__LOGAN_H5_BRIDGE__){return;}"];
    [s appendString:@"window.__LOGAN_H5_BRIDGE__=true;"];
    [s appendString:@"var CFG="];
    [s appendString:(configJson.length > 0 ? configJson : @"{}")];
    [s appendString:@";"];
    [s appendString:@"window.LoganH5={"];
    [s appendString:@"config:function(){try{return JSON.stringify(CFG);}catch(e){return '{}';}},"];
    [s appendString:@"postJson:function(p){try{"];
    [s appendString:@"if(window.webkit&&window.webkit.messageHandlers&&window.webkit.messageHandlers.LoganH5){"];
    [s appendString:@"window.webkit.messageHandlers.LoganH5.postMessage(String(p));return true;"];
    [s appendString:@"}"];
    [s appendString:@"}catch(e){}"];
    [s appendString:@"return false;"];// 桥不可用 → 脚本降级直传
    [s appendString:@"}"];
    [s appendString:@"};"];
    [s appendString:@"})();"];
    return s;
}

static BOOL LoganIsErrorKind(NSString *k) {
    if (![k isKindOfClass:[NSString class]] || k.length == 0) { return NO; }
    static NSSet *kinds = nil;
    static dispatch_once_t onceToken;
    dispatch_once(&onceToken, ^{
        kinds = [NSSet setWithArray:@[@"error", @"caught", @"business", @"promise",
                                      @"ui", @"resource", @"other"]];
    });
    return [kinds containsObject:k];
}

@implementation LoganH5Bridge

+ (void)setEnabled:(BOOL)on { gH5Enabled = on; }
+ (BOOL)isEnabled { return gH5Enabled && [LoganHttp shared].isEnabled; }

+ (void)attachToUserContentController:(WKUserContentController *)ucc {
    @try {
        if (![self isEnabled] || ucc == nil) { return; }
        [ucc removeScriptMessageHandlerForName:kLoganH5BridgeName]; // 幂等：宿主复用同一 configuration 也不炸
        LoganH5MessageHandler *handler = [LoganH5MessageHandler new];
        [ucc addScriptMessageHandler:handler name:kLoganH5BridgeName];
        objc_setAssociatedObject(ucc, "loganH5Handler", handler, OBJC_ASSOCIATION_RETAIN_NONATOMIC);
        NSString *boot = LoganH5BootstrapJS([[LoganHttp shared] h5ConfigJson]);
        [ucc addUserScript:[[WKUserScript alloc] initWithSource:boot
                                                   injectionTime:WKUserScriptInjectionTimeAtDocumentStart
                                                forMainFrameOnly:NO]];
        NSString *main = [NSString stringWithUTF8String:kLoganH5JS];
        [ucc addUserScript:[[WKUserScript alloc] initWithSource:main
                                                   injectionTime:WKUserScriptInjectionTimeAtDocumentStart
                                                forMainFrameOnly:NO]];
    } @catch (NSException *e) {}
}

+ (void)install {
    @synchronized (self) {
        if (gH5Installed) { return; }
        gH5Installed = YES;
        Class cls = NSClassFromString(@"WKWebView");
        if (cls == nil) { return; }
        // 只 hook initWithFrame:configuration:：宿主自建 configuration 时也会经过它
        [self swizzleInitOn:cls sel:@selector(initWithFrame:configuration:)];
    }
}

+ (void)swizzleInitOn:(Class)cls sel:(SEL)sel {
    Method orig = class_getInstanceMethod(cls, sel);
    if (orig == NULL) { return; }
    // 关键：必须在 exchange 之前把原始 IMP 抓进静态变量。
    // exchange 之后 sel 上挂的已经是我们的 block，再用 method_getImplementation(orig) 取会拿到 block 自身 → 无限递归。
    static IMP origInitImp = NULL;
    origInitImp = method_getImplementation(orig);
    NSString *name = [NSString stringWithFormat:@"logan_%s", sel_getName(sel)];
    SEL swz = NSSelectorFromString(name);
    if (class_getInstanceMethod(cls, swz) != NULL) { return; }
    IMP block_imp = imp_implementationWithBlock(^id(id target, CGRect frame, WKWebViewConfiguration *cfg) {
        @try {
            if ([LoganH5Bridge isEnabled] && cfg != nil) {
                [LoganH5Bridge attachToUserContentController:cfg.userContentController];
            }
        } @catch (NSException *e) {}
        if (origInitImp != NULL) {
            return ((id (*)(id, SEL, CGRect, WKWebViewConfiguration *))origInitImp)(target, sel, frame, cfg);
        }
        return target;
    });
    if (!class_addMethod(cls, swz, block_imp, method_getTypeEncoding(orig))) { return; }
    Method swzMethod = class_getInstanceMethod(cls, swz);
    method_exchangeImplementations(orig, swzMethod);
}


/// H5 侧上报的 JS 异常（window.onerror / unhandledrejection / 业务显式 report）走原生错误通道。
/// 必须在 LoganH5Bridge 自身实现块内 —— 曾因误放进 LoganH5MessageHandler 而永远调不到（pbxproj 的 -w 吞了警告）。
+ (void)reportH5Error:(NSDictionary *)raw pageUrl:(nullable NSString *)pageUrl {
    @try {
        if (![raw isKindOfClass:[NSDictionary class]]) { return; }
        NSString *name = [raw[@"name"] isKindOfClass:[NSString class]] ? raw[@"name"] : @"H5Error";
        NSString *msg = [raw[@"message"] isKindOfClass:[NSString class]] ? raw[@"message"] : @"";
        NSString *stack = [raw[@"stack"] isKindOfClass:[NSString class]] ? raw[@"stack"] : nil;
        NSString *kind = [raw[@"kind"] isKindOfClass:[NSString class]] ? raw[@"kind"] : @"other";
        if ([kind isEqualToString:@"error"]) { kind = @"caught"; }
        NSString *extra = [raw[@"extra"] isKindOfClass:[NSString class]] ? raw[@"extra"] : nil;
        [LoganErrorKit reportErrorWithKind:kind ?: @"other"
                                      name:name ?: @"H5Error"
                                   message:msg ?: @""
                                     stack:stack
                                   pageUrl:pageUrl
                                     extra:extra];
    } @catch (NSException *e) {}
}

@end




#pragma mark - JS → 原生 消息处理

@implementation LoganH5MessageHandler

- (void)userContentController:(WKUserContentController *)ucc
      didReceiveScriptMessage:(WKScriptMessage *)message {
    @try {
        if (![message.name isEqualToString:kLoganH5BridgeName]) { return; }
        id body = message.body;
        if (![body isKindOfClass:[NSString class]]) { return; }
        NSData *d = [(NSString *)body dataUsingEncoding:NSUTF8StringEncoding];
        if (d.length == 0) { return; }
        NSDictionary *obj = [NSJSONSerialization JSONObjectWithData:d options:0 error:NULL];
        NSArray *events = [obj isKindOfClass:[NSDictionary class]] ? obj[@"events"] : nil;
        // 行为轨迹是独立语义（kind 白名单里有 other，会和错误通道抢），先按 type 整批分流，
        // 再让其余事件走既有的错误/网络两条通道 —— 与安卓 LoganH5Bridge 同构。
        NSString *batchType = [obj[@"type"] isKindOfClass:[NSString class]] ? obj[@"type"] : nil;
        if ([batchType isEqualToString:@"behavior"]) {
            if (![events isKindOfClass:[NSArray class]]) { return; }
            for (NSDictionary *raw in events) {
                if (![raw isKindOfClass:[NSDictionary class]]) { continue; }
                NSString *bk = [raw[@"kind"] isKindOfClass:[NSString class]] ? raw[@"kind"] : nil;
                NSString *bn = [raw[@"name"] isKindOfClass:[NSString class]] ? raw[@"name"] : nil;
                NSString *bl = [raw[@"label"] isKindOfClass:[NSString class]] ? raw[@"label"] : nil;
                NSString *bp = [raw[@"pageUrl"] isKindOfClass:[NSString class]] ? raw[@"pageUrl"] : nil;
                NSString *bt = [raw[@"traceId"] isKindOfClass:[NSString class]] ? raw[@"traceId"] : nil;
                NSString *bx = [raw[@"extra"] isKindOfClass:[NSString class]] ? raw[@"extra"] : nil;
                long long bo = [raw[@"occurredAt"] respondsToSelector:@selector(longLongValue)] ? [raw[@"occurredAt"] longLongValue] : 0;
                [[LoganBehaviorKit class] reportBehaviorFromH5WithKind:bk name:bn label:bl
                                                       pageUrl:bp traceId:bt extra:bx occurredAt:bo];
            }
            return;
        }
        if (![events isKindOfClass:[NSArray class]]) { return; }
        for (NSDictionary *raw in events) {
            if (![raw isKindOfClass:[NSDictionary class]]) { continue; }
            // 先分流 JS 异常类事件：这类事件没有 url，若先走下面的 url 判空会被 continue 吞掉，
            // 导致 window.onerror / unhandledrejection 永远进不了错误通道（实测缺陷）。
            NSString *earlyKind = [raw[@"kind"] isKindOfClass:[NSString class]] ? raw[@"kind"] : nil;
            if (LoganIsErrorKind(earlyKind)) {
                NSString *pUrl = [raw[@"pageUrl"] isKindOfClass:[NSString class]] ? raw[@"pageUrl"] : nil;
                if (pUrl.length == 0 && [raw[@"extra"] isKindOfClass:[NSString class]]) { pUrl = nil; }
                [[LoganH5Bridge class] reportH5Error:raw pageUrl:pUrl];
                continue;
            }
            LoganHttpEvent *ev = [LoganHttpEvent new];
            ev.kind = @"h5";                       // 容器内一律归 h5，与原生请求区分
            ev.method = [raw[@"method"] isKindOfClass:[NSString class]] ? raw[@"method"] : @"GET";
            ev.url = [raw[@"url"] isKindOfClass:[NSString class]] ? raw[@"url"] : @"";
            if (ev.url.length == 0) { continue; }  // 服务端也丢，这里先省一趟
            ev.status = [raw[@"status"] respondsToSelector:@selector(intValue)] ? [raw[@"status"] intValue] : 0;
            if ([raw[@"errMsg"] isKindOfClass:[NSString class]]) { ev.errMsg = raw[@"errMsg"]; }
            if ([raw[@"errKind"] isKindOfClass:[NSString class]]) { ev.errKind = raw[@"errKind"]; }
            if ([raw[@"pageUrl"] isKindOfClass:[NSString class]]) { ev.pageUrl = raw[@"pageUrl"]; }
            if ([raw[@"traceId"] isKindOfClass:[NSString class]]) { ev.traceId = raw[@"traceId"]; }
            if ([raw[@"extra"] isKindOfClass:[NSString class]]) { ev.extra = raw[@"extra"]; }
            if ([raw[@"durationMs"] respondsToSelector:@selector(longLongValue)]) { ev.durationMs = [raw[@"durationMs"] longLongValue]; }
            if ([raw[@"respBytes"] respondsToSelector:@selector(longLongValue)]) { ev.respBytes = @([raw[@"respBytes"] longLongValue]); }
            if ([raw[@"reqBytes"] respondsToSelector:@selector(longLongValue)]) { ev.reqBytes = @([raw[@"reqBytes"] longLongValue]); }
            if ([raw[@"occurredAt"] respondsToSelector:@selector(longLongValue)]) { ev.occurredAt = [raw[@"occurredAt"] longLongValue]; }
            if ([raw[@"slow"] boolValue]) { ev.slow = YES; }
            NSString *jsKind = [raw[@"kind"] isKindOfClass:[NSString class]] ? raw[@"kind"] : nil;
            if (LoganIsErrorKind(jsKind)) {
                // H5 JS 异常经桥交给错误通道，避免与网络事件混在一起
                [[LoganH5Bridge class] reportH5Error:raw pageUrl:ev.pageUrl];
                continue;
            }
            [[LoganHttp shared] report:ev];
        }
    } @catch (NSException *e) {}
}

@end
