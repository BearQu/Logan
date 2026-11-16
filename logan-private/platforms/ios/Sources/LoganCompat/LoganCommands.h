//
//  LoganCommands.h
//  Logan PoC —— 平台侧主动拉日志的客户端轮询器（对齐 Android LoganCommandPoller / 鸿蒙 LoganCommands）。
//
//  语义：平台「设备清单」页点「拉取日志」写入待下发命令；本类按固定间隔带机器码领任务，
//  领到 upload_logs 回调宿主上传，完成后 ack（pending→delivered→acked 闭环）。
//  每次轮询同时上报版本信息，平台据此判定设备在线（logan_device_poll.last_poll_time）。
//
#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

typedef void (^LoganCommandHandler)(NSString *action, long long commandId, NSString *_Nullable param, void (^completion)(BOOL ok, NSString *_Nullable note));

@interface LoganCommands : NSObject

+ (void)configureWithBaseUrl:(NSString *)baseUrl
                       appId:(NSString *)appId
                    deviceId:(NSString *)deviceId
                  appVersion:(nullable NSString *)appVersion
                buildVersion:(nullable NSString *)buildVersion
                  authHeader:(nullable NSString *)authHeader;

/// 命令处理回调（宿主设置）。action 未知时应回 ok=NO，平台侧标 failed。
+ (void)setHandler:(LoganCommandHandler)handler;

/// 轮询间隔，最小 15 秒。
+ (void)setIntervalSeconds:(NSTimeInterval)seconds;

/// 启动后台轮询（幂等）；冷启动调用一次即可，首次延迟 8 秒避开启动高峰。
+ (void)start;

/// 立即上报一次设备信息快照（平台「采集设备信息」按钮下发 collect_info 时 SDK 内部调用；宿主也可主动调）。
+ (void)reportDeviceInfoNow;
+ (void)stop;

@end

NS_ASSUME_NONNULL_END
