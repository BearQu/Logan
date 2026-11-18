//
//  LoganHttp.h
//  Logan PoC —— 业务网络监控 + H5 监控 + 非致命错误的统一上报通道（零第三方依赖，仅 NSURLSession/zlib）。
//
//  通用性：不绑定任何网络库 —— NSURLProtocol 观测走系统注册（AFNetworking/URLSession 默认配置都覆盖），
//  显式入口 reportEvent: 供任何自研栈一行接入；H5 走 WKWebView 容器自动注入。
//  稳定性红线：采集内部任何异常都不外抛；上报只走 ephemeral session（不被自家 NSURLProtocol 观测，无回环）。
//  一行开关：[LoganHttp.shared setEnabled:NO]（默认开）；H5 单独关 [LoganH5Bridge setEnabled:NO]。
//
#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

@interface LoganHttpEvent : NSObject
@property(copy, nonatomic) NSString *kind;        // native | h5（服务端还会归一，其它值会被归 native）
@property(copy, nonatomic) NSString *method;
@property(copy, nonatomic) NSString *url;         // 必填；空事件服务端直接丢弃
@property(copy, nonatomic, nullable) NSString *host;
@property(copy, nonatomic, nullable) NSString *path;
@property(assign, nonatomic) int status;          // 0 = 未知/网络层失败
@property(copy, nonatomic, nullable) NSString *errKind;   // 可留空，服务端 HttpNorm 重算
@property(copy, nonatomic, nullable) NSString *errMsg;
@property(assign, nonatomic) long long durationMs;
@property(strong, nonatomic, nullable) NSNumber *connectMs;
@property(strong, nonatomic, nullable) NSNumber *dnsMs;
@property(strong, nonatomic, nullable) NSNumber *ttfbMs;
@property(strong, nonatomic, nullable) NSNumber *reqBytes;
@property(strong, nonatomic, nullable) NSNumber *respBytes;
@property(assign, nonatomic) BOOL slow;
@property(copy, nonatomic, nullable) NSString *pageUrl;
@property(copy, nonatomic, nullable) NSString *traceId;
@property(copy, nonatomic, nullable) NSString *os;
@property(copy, nonatomic, nullable) NSString *osVersion;
@property(copy, nonatomic, nullable) NSString *brand;
@property(copy, nonatomic, nullable) NSString *model;
@property(copy, nonatomic, nullable) NSString *networkType;
@property(copy, nonatomic, nullable) NSString *extra;      // JSON 字符串或普通串，服务端原样存
@property(assign, nonatomic) long long occurredAt;         // 毫秒；0 = 服务端用接收时间兜底
- (NSDictionary *)toJsonDictionary;
@end

@interface LoganHttp : NSObject
+ (instancetype)shared;
// 与 LoganCrashKit / LoganCommands 同一份身份与鉴权，宿主只调一次
- (instancetype)configureWithBaseUrl:(NSString *)baseUrl
                               appId:(NSString *)appId
                            deviceId:(NSString *)deviceId
                          appVersion:(nullable NSString *)appVersion
                        buildVersion:(nullable NSString *)buildVersion
                          authHeader:(nullable NSString *)authHeader;
/** 上报域名（小写 host），供自签放行判定；未配置返回空串 */
@property (nonatomic, readonly, copy) NSString *baseUrlLowercased;
- (LoganHttp *)setSlowThresholdMs:(long long)ms;   // 默认 3000
- (LoganHttp *)setEnabled:(BOOL)on;                // 默认开；关 = 停采集+停上报（含 H5 桥）
- (BOOL)isEnabled;
- (void)start;                                     // 装 NSURLProtocol 观测 + 启动批量定时器（幂等）
- (void)report:(LoganHttpEvent *)event;
- (void)flushNow;                              // 手动立即批量上报（页面退出/验收时用）            // 显式入口：自研网络栈一行接入
- (BOOL)isSelfUrl:(NSString *)url;
/// 通用排除：业务有证书锁定（SSL pinning）、自有网络桩或不想被观测的域名时一行排除。
/// 观测层对命中域名直接放行不接管，避免二次转发破坏业务语义。
- (BOOL)shouldObserveRequest:(NSURLRequest *)request;  // 观测层判定：排除规则 + 自定义过滤，异常时保守不接管
- (void)excludeHost:(NSString *)host;
/// 更细粒度：命中 substring 即排除（如某条上报/健康检查路径）。
- (void)excludeUrlContains:(NSString *)substring;
/// 终极自定义：返回 NO 则不接管该请求（在子线程调用，必须快速无阻塞）。
- (void)setObserveFilter:(BOOL (^)(NSURLRequest *request))filter;                 // 只排自家上报接口，不误杀同域业务 H5
- (NSString *)h5ConfigJson;                        // 注入脚本的运行时配置
// —— 非致命错误通道（LoganErrorKit）借用同一份配置，两通道地址/身份/鉴权不漂移 ——
- (BOOL)isConfiguredForError;
- (NSString *)errorBaseUrl;
- (NSString *)errorAppId;
- (NSString *)errorDeviceId;
- (nullable NSString *)errorAppVersion;
- (nullable NSString *)errorBuildVersion;
- (NSDictionary<NSString *, NSString *> *)errorHeaders;
@end

NS_ASSUME_NONNULL_END
