//
//  LoganDeviceInfo.h
//  Logan PoC —— 设备与运行环境快照（对齐 Android com.ccic.logan.device.LoganDeviceInfo 与鸿蒙 LoganDeviceInfo.ets）。
//
//  原则：取不到的项整个键不写（宁缺毋假，绝不编造数值）；只用公开 API，零新增链接框架（Foundation + libc + Mach）。
//  GPU 利用率在 iOS 无公开 API（Metal/IOKit 属图形框架，SDK 不该依赖）→ 只报显存/内存占用，不伪造 GPU 使用率。
//  采集项跨端同名：brand/model/os/osVersion/appVersion/buildVersion/totalMemBytes/availMemBytes/
//  appPhysFootprintBytes/disk 系列/cpu 系列/battery 系列/network/screen/locale/timezone。
//
#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

@interface LoganDeviceInfo : NSObject

/// 采集一次快照，返回可直接 JSON 化的字典；全部项都拿不到时返回 nil。
+ (nullable NSDictionary *)snapshotDictionary;

/// 采集并返回 JSON 字符串；失败返回空串（调用方据此判断 ack 成败）。
+ (NSString *)snapshotJSON;

@end

NS_ASSUME_NONNULL_END
