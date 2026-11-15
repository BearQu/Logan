/*
 * LoganIdentity —— iOS 端统一机器码的唯一出处（对齐 Android LoganIdentity / 鸿蒙 LoganIdentity）。
 * 日志通道、崩溃通道、应用画像上报必须同源，否则平台按 device_id 的崩溃↔业务日志互证会被打乱。
 *
 * 存储策略（Keychain 是 iOS 上唯一跨卸载的本地存储 —— 卸载重装仍读得到）：
 *  1) 权威源：Keychain（kSecClassGenericPassword，固定 service + 本 bundleId 作 account，
 *     kSecAttrAccessibleAfterFirstUnlock，不云同步）。首启把 identifierForVendor（没有则 UUID）
 *     种进去，此后无论 vendor 标识怎么变、卸载重装，都读回同一份。
 *  2) 镜像一份到 NSUserDefaults：Keychain 异常（Data Protection 损坏 / 少数企业环境）时兜底，
 *     也便于日志与自动化验收读取。
 *  3) 两处皆空 → 现生成一份。
 *
 * 一致性规则：Keychain 有值即以 Keychain 为准，并顺手把镜像修成同值（与鸿蒙 Asset Store 同策略）。
 * 宿主不要再自己读 identifierForVendor 当 deviceId：一律调 LoganStableDeviceId()。
 */
#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

/** 取本机稳定机器码；进程内缓存，重复调用同值。永不为空。 */
NSString *LoganStableDeviceId(void);

/** 把机器码钉成指定值（PoC / 自动化验收用，生产别调）。传空串等价不钉。 */
void LoganPinDeviceId(NSString * _Nullable value);

/** 只读探一次 Keychain 里已存的机器码（不生成、不落盘），供断言用。 */
NSString * _Nullable LoganKeychainDeviceId(void);

NS_ASSUME_NONNULL_END
