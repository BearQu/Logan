//
//  LoganBehaviorKit.h
//  Logan PoC —— 崩溃前行为轨迹采集（对标 Bugly「崩溃前行为/用户行为路径」）。
//
//  自动源三处，全部通用、不针对任何具体宿主项目：
//   1) UIViewController viewDidAppear: → 页面轨迹（类名，剥宿主无关前缀交给服务端归一）；
//   2) UIApplication 前后台通知 → foreground/background；
//   3) UIControl sendAction:forEvent: → 点击轨迹（只取标题/无障碍标签，绝不取输入内容）。
//  显式源两处：业务一行接入 + 容器注入的 H5 轨迹（经 LoganH5 桥归到这里）。
//
//  一行开关：+[LoganBehaviorKit setEnabled:]（默认开）；自动源可单独关 +setAutoUIEnabled:。
//  白名单、聚合、指纹、去重全在服务端 BehaviorNorm，客户端零逻辑。取不到的字段整个不写。
#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

@interface LoganBehaviorKit : NSObject

/// 安装自动源（幂等，由宿主 LoganKit 初始化时调用）
+ (void)install;

/// 通道总开关（默认开）。关 = 停采集 + 停上报，显式入口也一并静默。
+ (void)setEnabled:(BOOL)on;
+ (BOOL)isEnabled;

/// 自动 UI 源单独一行开关（默认开）。关掉后只剩显式入口与 H5 轨迹。
+ (void)setAutoUIEnabled:(BOOL)on;

/// 显式入口：业务主动记一条轨迹（自定义事件、向导步骤、弹窗曝光等）。
/// kind 白名单（与后端 BehaviorNorm 对齐）：page|click|custom|foreground|background|launch|exit|input|scroll|refresh|tab|dialog|other
+ (void)reportBehaviorWithKind:(nullable NSString *)kind
                          name:(nullable NSString *)name
                         label:(nullable NSString *)label
                       pageUrl:(nullable NSString *)pageUrl
                         extra:(nullable NSString *)extra;

/// H5 注入脚本经原生桥转来的轨迹事件（字段已由 JS 侧裁剪过，这里只做白名单与兜底）。
+ (void)reportBehaviorFromH5WithKind:(nullable NSString *)kind
                                name:(nullable NSString *)name
                               label:(nullable NSString *)label
                             pageUrl:(nullable NSString *)pageUrl
                             traceId:(nullable NSString *)traceId
                               extra:(nullable NSString *)extra
                          occurredAt:(long long)occurredAt;

/// 手动立即批量上报（退出/验收时用）。
+ (void)flushNow;

@end

NS_ASSUME_NONNULL_END
