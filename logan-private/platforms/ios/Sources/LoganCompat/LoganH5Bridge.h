//
//  LoganH5Bridge.h
//  Logan PoC —— H5 监控的容器侧装配器（业务页面零改动）。
//
//  做法：swizzle -[WKWebView initWithFrame:configuration:]（以及 initWithFrame:），
//  在 configuration.userContentController 上追加 document-start 用户脚本 +
//  注册名为 LoganH5 的 JS 消息处理器；注入脚本先造 window.LoganH5
//  （postJson → 原生批量上报；config → 返回运行时配置），再执行三端共用的
//  logan-h5.js（hook fetch / XHR / window.onerror / unhandledrejection / Resource Timing）。
//
//  通用性：只要业务用系统 WKWebView（含 WKWebView 子类、微信/支付宝授权页之外的自研 H5 容器）
//  就自动生效；业务侧不需要任何改动。
//  一行关闭：[LoganH5Bridge setEnabled:NO]；单页面关：页面里 window.__LOGAN_H5_OFF__ = true。
//
#import <Foundation/Foundation.h>
@class WKUserContentController;

NS_ASSUME_NONNULL_BEGIN

@interface LoganH5Bridge : NSObject
/// 安装 WKWebView 自动注入（幂等）。由 LoganHttp start 调用。
+ (void)install;
/// H5 采集单独一行开关（默认开，且跟随 LoganHttp 总开关）。
+ (void)setEnabled:(BOOL)on;
+ (BOOL)isEnabled;
/// H5 侧 JS 异常 → 原生错误通道（由消息处理器调用）。
+ (void)reportH5Error:(NSDictionary *)raw pageUrl:(nullable NSString *)pageUrl;
/// 供业务用非默认 configuration 创建 WKWebView 时手动补注入（一般用不到）。
+ (void)attachToUserContentController:(WKUserContentController *)ucc;
@end

NS_ASSUME_NONNULL_END
