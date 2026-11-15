//
//  LoganIdentity.m
//  Logan SDK (iOS)
//
//  策略说明见 LoganIdentity.h：Keychain 为权威源（卸载重装仍是同一份），
//  NSUserDefaults 只做镜像与降级兜底。日志通道 / 崩溃通道 / 应用画像上报必须同源。
//

#import "LoganIdentity.h"
#import <Security/Security.h>
#import <UIKit/UIKit.h>

static NSString *const kLoganIdentityService = @"com.ccic.logan.identity";
static NSString *const kLoganIdentityDefaultsKey = @"logan_device_id";
static NSString *const kLoganIdentityLock = @"logan_identity_lock_token";
static NSString *gLoganCachedDeviceId = nil;

static BOOL LoganIdentityValid(NSString *v) {
    if (v == nil) {
        return NO;
    }
    NSString *t = [v stringByTrimmingCharactersInSet:[NSCharacterSet whitespaceAndNewlineCharacterSet]];
    return t.length >= 8;
}

static NSString *LoganIdentityAccount(void) {
    NSString *bid = [[NSBundle mainBundle] bundleIdentifier];
    if (bid.length == 0) {
        bid = @"com.ccic.logan.unknown-host";
    }
    return bid;
}

static NSMutableDictionary *LoganIdentityBaseQuery(void) {
    return [NSMutableDictionary dictionaryWithDictionary:@{
        (__bridge id)kSecClass: (__bridge id)kSecClassGenericPassword,
        (__bridge id)kSecAttrService: kLoganIdentityService,
        (__bridge id)kSecAttrAccount: LoganIdentityAccount(),
        (__bridge id)kSecAttrAccessible: (__bridge id)kSecAttrAccessibleAfterFirstUnlock,
    }];
}

static NSString *LoganIdentityReadKeychain(void) {
    NSMutableDictionary *query = LoganIdentityBaseQuery();
    query[(__bridge id)kSecReturnData] = @YES;
    query[(__bridge id)kSecMatchLimit] = (__bridge id)kSecMatchLimitOne;
    CFTypeRef result = NULL;
    OSStatus st = SecItemCopyMatching((__bridge CFDictionaryRef)query, &result);
    if (st != errSecSuccess || result == NULL) {
        return nil;
    }
    NSData *data = (__bridge_transfer NSData *)result;
    NSString *value = [[NSString alloc] initWithData:data encoding:NSUTF8StringEncoding];
    return LoganIdentityValid(value) ? value : nil;
}

static BOOL LoganIdentityWriteKeychain(NSString *value) {
    NSData *data = [value dataUsingEncoding:NSUTF8StringEncoding];
    if (data == nil) {
        return NO;
    }
    NSMutableDictionary *query = LoganIdentityBaseQuery();
    OSStatus st = SecItemAdd((__bridge CFDictionaryRef)query, NULL);
    if (st == errSecSuccess) {
        return YES;
    }
    if (st == errSecDuplicateItem) {
        NSMutableDictionary *update = [NSMutableDictionary dictionary];
        update[(__bridge id)kSecValueData] = data;
        OSStatus up = SecItemUpdate((__bridge CFDictionaryRef)query, (__bridge CFDictionaryRef)update);
        return up == errSecSuccess;
    }
    return NO;
}

static NSString *LoganIdentityFreshValue(void) {
    @try {
        NSString *vendor = [[[UIDevice currentDevice] identifierForVendor] UUIDString];
        if (LoganIdentityValid(vendor)) {
            return vendor;
        }
    } @catch (NSException *ignored) {
    }
    CFUUIDRef uuid = CFUUIDCreate(NULL);
    CFStringRef str = CFUUIDCreateString(NULL, uuid);
    NSString *fallback = (__bridge_transfer NSString *)str;
    CFRelease(uuid);
    return fallback;
}

static void LoganIdentityWriteMirror(NSString *value) {
    [[NSUserDefaults standardUserDefaults] setObject:value forKey:kLoganIdentityDefaultsKey];
}

static NSString *LoganIdentityReadMirror(void) {
    return [[NSUserDefaults standardUserDefaults] stringForKey:kLoganIdentityDefaultsKey];
}

NSString *LoganKeychainDeviceId(void) {
    return LoganIdentityReadKeychain();
}

void LoganPinDeviceId(NSString *value) {
    if (!LoganIdentityValid(value)) {
        return;
    }
    NSString *v = [value stringByTrimmingCharactersInSet:[NSCharacterSet whitespaceAndNewlineCharacterSet]];
    @synchronized (kLoganIdentityLock) {
        LoganIdentityWriteKeychain(v);
        LoganIdentityWriteMirror(v);
        NSString *back = LoganIdentityReadKeychain();
        gLoganCachedDeviceId = LoganIdentityValid(back) ? [back copy] : [v copy];
    }
}

NSString *LoganStableDeviceId(void) {
    if (LoganIdentityValid(gLoganCachedDeviceId)) {
        return gLoganCachedDeviceId;
    }
    @synchronized (kLoganIdentityLock) {
        if (LoganIdentityValid(gLoganCachedDeviceId)) {
            return gLoganCachedDeviceId;
        }
        NSString *fromKeychain = LoganIdentityReadKeychain();
        NSString *fromMirror = LoganIdentityReadMirror();
        NSString *chosen = nil;
        if (LoganIdentityValid(fromKeychain)) {
            // Keychain 权威：卸载重装后仍然读得到同一份，这是 iOS 上唯一稳定的机器码来源。
            chosen = fromKeychain;
            if (![fromKeychain isEqualToString:(fromMirror ?: @"")]) {
                LoganIdentityWriteMirror(fromKeychain);
            }
        } else if (LoganIdentityValid(fromMirror)) {
            // 镜像有、Keychain 没有（首次升级或 Keychain 被清）：补种回 Keychain，之后仍以 Keychain 为准。
            LoganIdentityWriteKeychain(fromMirror);
            NSString *back = LoganIdentityReadKeychain();
            chosen = LoganIdentityValid(back) ? back : fromMirror;
        } else {
            // 全新安装：种一份 identifierForVendor（拿不到就用 CFUUID），之后永不变更。
            chosen = LoganIdentityFreshValue();
            LoganIdentityWriteKeychain(chosen);
            NSString *back = LoganIdentityReadKeychain();
            if (LoganIdentityValid(back)) {
                chosen = back;
            }
            LoganIdentityWriteMirror(chosen);
        }
        gLoganCachedDeviceId = [chosen copy];
    }
    return gLoganCachedDeviceId;
}

