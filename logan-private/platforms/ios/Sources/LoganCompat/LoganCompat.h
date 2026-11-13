/*
 * LoganCompat —— 存量 NSLog 零改动兼容层。
 *
 * 用法（宿主工程 PCH 或 xcconfig GCC_PREFIX_HEADER 一行接入）：
 *   #import <LoganCompat/LoganCompat.h>
 * 本头文件重定义 NSLog 为 LoganCompatLog：先 os_log 原样输出（系统日志逐字节保持），
 * 再格式化进 clogan mmap 通道。LoganCompatSetEnabled(NO) 即回原样。
 *
 * 与 Android 端编译期字节码改写同语义——原输出原样保留，双写非替换。
 */
#import <Foundation/Foundation.h>

#ifdef __cplusplus
extern "C" {
#endif

// 采集总开关（release 服务端开关止损用）；关 = NSLog 纯直通
extern void LoganCompatSetEnabled(BOOL enabled);
// Logan 内核初始化成功后由宿主调用；未 ready 前双写自动旁路
extern void LoganCompatMarkReady(void);

// NSLog 重定向落点：NSLog 与 Logan 双写
extern void LoganCompatLog(NSString *format, ...) NS_FORMAT_FUNCTION(1, 2);

#ifdef __cplusplus
}
#endif

#undef NSLog
#define NSLog(...) LoganCompatLog(__VA_ARGS__)
