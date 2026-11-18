//
//  LoganErrorKit.h
//  Logan PoC —— 非致命错误采集（对标 Bugly「自定义异常 / 错误分析」）。
//
//  两个来源，都不要求业务改造：
//   1) 自动：swizzle +[NSError errorWithDomain:code:userInfo:]，抓构造点并只保留
//      「构造帧属于主可执行文件」的错误 —— 系统库内部成千上万的 NSError 全部丢弃（宁缺毋假），
//      业务里自己构造的错误天然被采集；同 domain|code|topFrame 3000ms 风暴抑制。
//   2) 显式：+[reportErrorWithKind:name:message:stack:pageUrl:extra:] 一行接入，
//      以及容器注入的 H5 JS 异常（经 LoganH5 桥归到这里）。
//
//  与崩溃通道不双计：未捕获 NSException / 信号崩溃由 LoganCrashKit 负责，这里不碰。
//  指纹、聚合、去重、errKind 归类全在服务端 ErrorNorm，客户端零逻辑。
//  一行开关：+[LoganErrorKit setEnabled:]（默认开）；自动源可单独关 +setAutoNSErrorEnabled:。
//
#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

@interface LoganErrorKit : NSObject

/// 安装自动源（幂等，由 LoganHttp start 调用）
+ (void)install;

/// 总开关（默认开）
+ (void)setEnabled:(BOOL)on;
+ (BOOL)isEnabled;

/// 自动 NSError 采集单独一行开关（默认开）。关掉后只剩显式入口与 H5 异常。
+ (void)setAutoNSErrorEnabled:(BOOL)on;
/// 追加要忽略的 NSError 域名或语义 token（一行调用；不填则用 SDK 内置传输层黑名单）
+ (void)excludeNSErrorDomain:(NSString *)domainOrToken;
/// 清空黑名单（含内置项），需要全量观测时用
+ (void)clearNSErrorDomainExcludes;

/// 显式入口：业务 catch 到 NSError / 自研校验失败 / 服务端业务码异常时一行上报。
/// kind 白名单（与后端 ErrorNorm 对齐）：business | caught | ui | promise | resource | other
+ (void)reportErrorWithKind:(nullable NSString *)kind
                       name:(NSString *)name
                    message:(NSString *)message
                      stack:(nullable NSString *)stack
                    pageUrl:(nullable NSString *)pageUrl
                      extra:(nullable NSString *)extra;

/// 便捷：从 NSError 显式上报（不受"必须主 bundle 帧"限制，调用方已确认这是业务错误）
+ (void)flushNow;
+ (void)reportNSError:(NSError *)error where:(nullable NSString *)where;

@end

NS_ASSUME_NONNULL_END
