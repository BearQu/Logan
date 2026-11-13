#import "LoganCompat.h"
#import "Logan.h"
#import <os/log.h>

static volatile BOOL gEnabled = YES;
static volatile BOOL gReady = NO;
// 同文案 1s 限频（写放大保护，与 Android 桥同规则）
static NSString *gLastMsg = nil;
static NSTimeInterval gLastAt = 0;

void LoganCompatSetEnabled(BOOL enabled) { gEnabled = enabled; }
void LoganCompatMarkReady(void) { gReady = YES; }

void LoganCompatLog(NSString *format, ...) {
    va_list args;
    va_start(args, format);
    NSString *body = [[NSString alloc] initWithFormat:format arguments:args];
    va_end(args);

    // 1) 原样输出：os_log 与 NSLog 同通道，系统日志逐字节保持
    os_log(OS_LOG_DEFAULT, "%{public}@", body);

    // 2) 双写进 Logan（type 2 常规；崩溃证据由崩溃层另走 99）
    if (gEnabled && gReady) {
        @autoreleasepool {
            NSTimeInterval now = [NSDate date].timeIntervalSince1970;
            @synchronized (@(0)) {
                if (!(gLastMsg && [gLastMsg isEqualToString:body] && now - gLastAt < 1.0)) {
                    gLastMsg = [body copy];
                    gLastAt = now;
                    logan(2, body);
                }
            }
        }
    }
}
