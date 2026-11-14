/*
 * LoganCrashKit.m —— iOS 崩溃层实现（对齐 Android logan-crash 契约，见 LoganCrashKit.h）。
 */
#import "LoganCrashKit.h"
// 必须早于任何系统头文件：Darwin 的 <ucontext.h> 仅在 _XOPEN_SOURCE 下暴露 uc_mcontext。
#define _XOPEN_SOURCE 700

#import <execinfo.h>
#import <signal.h>
#import <unistd.h>
#import <fcntl.h>
#import <time.h>
#import <sys/utsname.h>
#include <dlfcn.h>
#include <ucontext.h>
#import <zlib.h>

#if TARGET_OS_IPHONE
#import <UIKit/UIKit.h>
#endif

static LoganCrashConfig *gCfg = nil;
static NSString *gPendingDir = nil;
static struct sigaction gOldActions[NSIG];
static NSUncaughtExceptionHandler *gOldExceptionHandler = nil;
static volatile sig_atomic_t gCrashHandling = 0;
static volatile sig_atomic_t gHandledSigMask = 0;

@implementation LoganCrashConfig
- (instancetype)init {
    if (self = [super init]) {
        _baseUrl = @"https://ceshixiaozhan.xyz:18443/logan-web";
        _maxTombstoneBytes = 512 * 1024;
    }
    return self;
}
@end

static NSString *LoganDjb2Hex(NSString *s) {
    unsigned long hash = 5381;
    NSUInteger len = s.length;
    for (NSUInteger i = 0; i < len; i++) {
        hash = ((hash << 5) + hash) + (unsigned long)[s characterAtIndex:i];
        hash &= 0xFFFFFFFF;
    }
    return [NSString stringWithFormat:@"%lx:%lu", hash, (unsigned long)len];
}

static const char *LoganSignalName(int sig) {
    switch (sig) {
        case SIGSEGV: return "SIGSEGV";
        case SIGABRT: return "SIGABRT";
        case SIGBUS:  return "SIGBUS";
        case SIGILL:  return "SIGILL";
        case SIGTRAP: return "SIGTRAP";
        case SIGFPE:  return "SIGFPE";
        default: return "SIG?";
    }
}

static NSString *LoganMachine(void) {
    struct utsname info;
    if (uname(&info) == 0) {
        return [NSString stringWithUTF8String:info.machine];
    }
    return @"unknown";
}

static NSString *LoganDeviceModel(void) {
#if TARGET_OS_IPHONE
    return [UIDevice currentDevice].model;
#else
    return @"macOS";
#endif
}

static NSString *LoganOsVersion(void) {
#if TARGET_OS_IPHONE
    return [UIDevice currentDevice].systemVersion;
#else
    return [NSProcessInfo processInfo].operatingSystemVersionString;
#endif
}

// 把崩溃现场的 PC 解析成一行符号（与 backtrace_symbols 同形，便于本地符号化）。
// 说明：dladdr 非严格 async-signal-safe，与 backtrace 同属 PoC 权衡（早期 Bugly/KSCrash 简易模式同样做法）。
static NSString *LoganSymbolicatePC(uintptr_t pc) {
    Dl_info info;
    memset(&info, 0, sizeof(info));
    if (pc == 0) return nil;
    if (dladdr((void *)pc, &info) && info.dli_fname) {
        const char *img = strrchr(info.dli_fname, '/');
        img = img ? img + 1 : info.dli_fname;
        unsigned long long off = info.dli_fbase ? (unsigned long long)(pc - (uintptr_t)info.dli_fbase) : 0ULL;
        if (info.dli_sname) {
            unsigned long long symoff = (unsigned long long)(pc - (uintptr_t)info.dli_saddr);
            return [NSString stringWithFormat:@"%-33s 0x%016llx %s + %llu", img, (unsigned long long)pc, info.dli_sname, symoff];
        }
        return [NSString stringWithFormat:@"%-33s 0x%016llx + %llu", img, (unsigned long long)pc, off];
    }
    return [NSString stringWithFormat:@"0x%016llx", (unsigned long long)pc];
}

// 从信号上下文取崩溃现场的 PC / LR（编译器不会把崩溃指令帧留在 backtrace() 的调用链里）。
static void LoganCrashPCs(void *uctx, uintptr_t *outPc, uintptr_t *outLr) {
    *outPc = 0; *outLr = 0;
    if (!uctx) return;
    mcontext_t mc = ((ucontext_t *)uctx)->uc_mcontext;
    if (!mc) return;
#if defined(__arm64__) || defined(__aarch64__)
    *outPc = (uintptr_t)mc->__ss.__pc;
    *outLr = (uintptr_t)mc->__ss.__lr;
#elif defined(__x86_64__)
    *outPc = (uintptr_t)mc->__ss.__rip;
#elif defined(__i386__)
    *outPc = (uintptr_t)mc->__ss.__eip;
#endif
}
static NSArray<NSString *> *LoganTopFrames(NSArray<NSString *> *symbols) {
    NSMutableArray<NSString *> *picked = [NSMutableArray array];
    for (NSString *s in symbols) {
        if ([s rangeOfString:@"libsystem"].location != NSNotFound) continue;
        if ([s rangeOfString:@"CoreFoundation"].location != NSNotFound) continue;
        if ([s rangeOfString:@"Foundation"].location != NSNotFound) continue;
        if ([s rangeOfString:@"UIKitCore"].location != NSNotFound) continue;
        if ([s rangeOfString:@"LoganCrashKit"].location != NSNotFound) continue;
        if ([s rangeOfString:@"LoganSignalHandler"].location != NSNotFound) continue;
        if ([s rangeOfString:@"LoganUncaughtExceptionHandler"].location != NSNotFound) continue;
        if ([s rangeOfString:@"BLYBSD"].location != NSNotFound) continue;
        if ([s rangeOfString:@"_sigtramp"].location != NSNotFound) continue;
        if ([s rangeOfString:@"libobjc.A.dylib"].location != NSNotFound) continue;
        if ([s rangeOfString:@"libc++abi"].location != NSNotFound) continue;
        [picked addObject:s];
        if (picked.count >= 3) break;
    }
    if (picked.count == 0) {
        // 兜底：优先带主二进制名的帧（崩溃点几乎总在 app 镜像里），仍空才退回原始前 3 帧。
        for (NSString *s in symbols) {
            if ([s rangeOfString:@".debug.dylib"].location != NSNotFound || [s rangeOfString:@"CCIC"].location != NSNotFound) {
                [picked addObject:s];
                if (picked.count >= 3) break;
            }
        }
    }
    if (picked.count == 0) {
        for (NSString *s in symbols) {
            [picked addObject:s];
            if (picked.count >= 3) break;
        }
    }
    return picked;
}

static NSData *LoganGzip(NSData *plain) {
    if (plain.length == 0) return nil;
    z_stream strm;
    memset(&strm, 0, sizeof(strm));
    if (deflateInit2(&strm, Z_DEFAULT_COMPRESSION, Z_DEFLATED, 15 + 16, 8, Z_DEFAULT_STRATEGY) != Z_OK) return nil;
    strm.next_in = (Bytef *)plain.bytes;
    strm.avail_in = (uInt)plain.length;
    NSMutableData *out = [NSMutableData dataWithLength:65536];
    int ret;
    do {
        if (strm.total_out >= out.length) { out.length = out.length + 65536; }
        strm.next_out = (Bytef *)out.mutableBytes + strm.total_out;
        strm.avail_out = (uInt)(out.length - strm.total_out);
        ret = deflate(&strm, Z_FINISH);
    } while (ret == Z_OK);
    NSData *result = nil;
    if (ret == Z_STREAM_END) { result = [out subdataWithRange:NSMakeRange(0, strm.total_out)]; }
    deflateEnd(&strm);
    return result;
}

static void LoganWritePending(NSString *envelope) {
    if (!gPendingDir || envelope.length == 0) return;
    NSFileManager *fm = [NSFileManager defaultManager];
    [fm createDirectoryAtPath:gPendingDir withIntermediateDirectories:YES attributes:nil error:nil];
    long long ts = (long long)([[NSDate date] timeIntervalSince1970] * 1000);
    NSString *path = [gPendingDir stringByAppendingPathComponent:[NSString stringWithFormat:@"crash_%lld.json", ts]];
    [envelope writeToFile:path atomically:YES encoding:NSUTF8StringEncoding error:nil];
}

static NSMutableURLRequest *LoganCrashRequest(NSString *urlStr, NSString *authHeader, LoganCrashConfig *cfg) {
    NSMutableURLRequest *req = [[NSMutableURLRequest alloc] initWithURL:[NSURL URLWithString:urlStr]
                                                            cachePolicy:NSURLRequestReloadIgnoringCacheData
                                                        timeoutInterval:30];
    [req setHTTPMethod:@"POST"];
    [req setValue:@"application/json" forHTTPHeaderField:@"Content-Type"];
    [req setValue:@"gzip" forHTTPHeaderField:@"Content-Encoding"];
    if (cfg.appId.length) [req setValue:cfg.appId forHTTPHeaderField:@"appId"];
    if (cfg.deviceId.length) [req setValue:cfg.deviceId forHTTPHeaderField:@"deviceId"];
    if (cfg.unionId.length) [req setValue:cfg.unionId forHTTPHeaderField:@"unionId"];
    if (cfg.appVersion.length) [req setValue:cfg.appVersion forHTTPHeaderField:@"appVersion"];
    if (cfg.buildVersion.length) [req setValue:cfg.buildVersion forHTTPHeaderField:@"buildVersion"];
    [req setValue:@"2" forHTTPHeaderField:@"platform"];
    if (authHeader.length) [req setValue:authHeader forHTTPHeaderField:@"Authorization"];
    return req;
}

static BOOL LoganPostSync(NSString *envelope) {
    if (!gCfg) return NO;
    NSData *gz = LoganGzip([envelope dataUsingEncoding:NSUTF8StringEncoding]);
    if (!gz) return NO;
    NSMutableURLRequest *req = LoganCrashRequest([gCfg.baseUrl stringByAppendingString:@"/logan/crash/upload.json"], gCfg.authHeader, gCfg);
    __block BOOL ok = NO;
    dispatch_semaphore_t sem = dispatch_semaphore_create(0);
    NSURLSessionConfiguration *conf = [NSURLSessionConfiguration ephemeralSessionConfiguration];
    conf.timeoutIntervalForRequest = 8;
    conf.timeoutIntervalForResource = 8;
    NSURLSession *session = [NSURLSession sessionWithConfiguration:conf];
    NSURLSessionUploadTask *task = [session uploadTaskWithRequest:req fromData:gz completionHandler:^(NSData *data, NSURLResponse *response, NSError *error) {
        if (!error && [response isKindOfClass:[NSHTTPURLResponse class]]) {
            long code = ((NSHTTPURLResponse *)response).statusCode;
            ok = (code >= 200 && code < 300);
        }
        dispatch_semaphore_signal(sem);
    }];
    [task resume];
    if (dispatch_semaphore_wait(sem, dispatch_time(DISPATCH_TIME_NOW, 9 * NSEC_PER_SEC)) != 0) {
        [task cancel];
        return NO;
    }
    return ok;
}

// 指纹归一化：剥掉地址与数字偏移（ASLR 每次不同，否则同一崩溃产生不同指纹），
// 只留镜像名 + 符号名，与 Android 端 CrashEnvelope 的「不含时间戳与地址」约定对齐。
static NSString *LoganFpNormalize(NSString *frame) {
    NSMutableString *out = [NSMutableString string];
    NSUInteger n = frame.length;
    for (NSUInteger i = 0; i < n; i++) {
        unichar ch = [frame characterAtIndex:i];
        if (ch == '0' && i + 1 < n && ([frame characterAtIndex:i + 1] == 'x' || [frame characterAtIndex:i + 1] == 'X')) {
            NSUInteger j = i + 2;
            while (j < n) {
                unichar h = [frame characterAtIndex:j];
                BOOL hex = (h >= '0' && h <= '9') || (h >= 'a' && h <= 'f') || (h >= 'A' && h <= 'F');
                if (!hex) break;
                j++;
            }
            i = j - 1;
            continue;
        }
        if (ch >= '0' && ch <= '9') continue;
        [out appendFormat:@"%C", ch];
    }
    // 压缩空白，进一步消除列宽差异
    NSArray<NSString *> *parts = [out componentsSeparatedByString:@" "];
    NSMutableArray<NSString *> *keep = [NSMutableArray array];
    for (NSString *p in parts) { if (p.length) [keep addObject:p]; }
    return [keep componentsJoinedByString:@" "];
}
static NSString *LoganEnvelope(NSString *kind, NSString *signal, NSArray<NSString *> *topFrames, NSString *content, long long crashTime, BOOL deferred) {
    NSMutableDictionary *o = [NSMutableDictionary dictionary];
    o[@"kind"] = kind;
    o[@"crashTime"] = @(crashTime);
    o[@"deferred"] = @(deferred);
    o[@"platform"] = @2;
    o[@"os"] = @"iOS";
    o[@"brand"] = @"Apple";
    o[@"manufacturer"] = @"Apple";
    o[@"model"] = LoganDeviceModel();
    o[@"abi"] = LoganMachine();
    o[@"release"] = LoganOsVersion();
    o[@"appId"] = gCfg.appId ?: @"";
    o[@"appVersion"] = gCfg.appVersion ?: @"";
    o[@"buildVersion"] = gCfg.buildVersion ?: @"";
    o[@"deviceId"] = gCfg.deviceId ?: @"";
    o[@"unionId"] = gCfg.unionId ?: @"";
    o[@"userId"] = gCfg.userId ?: @"";
    NSMutableString *fpSeed = [NSMutableString stringWithString:kind];
    if (signal.length) { [fpSeed appendString:@"|"]; [fpSeed appendString:signal]; }
    for (NSString *f in topFrames) { [fpSeed appendString:@"|"]; [fpSeed appendString:LoganFpNormalize(f)]; }
    o[@"fingerprint"] = LoganDjb2Hex(fpSeed);
    o[@"topFrames"] = topFrames ?: @[];
    if (signal.length) o[@"signal"] = signal;
    if (content.length > gCfg.maxTombstoneBytes) content = [content substringToIndex:gCfg.maxTombstoneBytes];
    o[@"content"] = content ?: @"";
    NSError *err = nil;
    NSData *json = [NSJSONSerialization dataWithJSONObject:o options:0 error:&err];
    if (!json) return nil;
    return [[NSString alloc] initWithData:json encoding:NSUTF8StringEncoding];
}

static void LoganSignalHandler(int sig, siginfo_t *info, void *ucontext) {
    // 每个信号只处理一次：崩溃处理中若第三方 SDK（Bugly）再抛 SIGABRT，仍能各自留档一次，
    // 避免首个信号处理失败导致整个崩溃没有任何记录。
    if (sig >= 0 && sig < NSIG && (gHandledSigMask & (((sig_atomic_t)1) << sig))) goto chain;
    if (sig >= 0 && sig < NSIG) gHandledSigMask |= (((sig_atomic_t)1) << sig);
    if (gCrashHandling && sig != SIGABRT) goto chain;
    gCrashHandling = 1;
    {
        long long ts = (long long)(time(NULL)) * 1000;
        uintptr_t crashPc = 0, crashLr = 0;
        LoganCrashPCs(ucontext, &crashPc, &crashLr);
        void *addrs[64];
        int n = backtrace(addrs, 64);
        char **syms = backtrace_symbols(addrs, n);
        NSMutableString *content = [NSMutableString string];
        [content appendString:@"Tombstone maker: 'logan-crash-kit-ios'\n"];
        [content appendString:@"Crash type: 'native'\n"];
        [content appendFormat:@"signal: %d (%s)\n", sig, LoganSignalName(sig)];
        if (info) {
            [content appendFormat:@"si_code: %d  fault_addr: %p\n", info->si_code, info->si_addr];
        }
        [content appendFormat:@"crash_time: %lld\n", ts];
        [content appendString:@"backtrace:\n"];
        [content appendFormat:@"fault_pc: 0x%llx"
, (unsigned long long)crashPc];
        NSMutableArray<NSString *> *frames = [NSMutableArray array];
        // 崩溃现场优先：信号上下文里的 PC 才是真正执行崩溃指令的那条指令，
        // 它不会出现在 handler 内调用 backtrace() 的结果里（信号帧被打断）。
        NSString *pcLine = LoganSymbolicatePC(crashPc);
        if (pcLine) {
            [content appendFormat:@"#crash_pc %@"
, pcLine];
            [frames addObject:pcLine];
        }
        NSString *lrLine = LoganSymbolicatePC(crashLr);
        if (lrLine && ![lrLine isEqualToString:pcLine]) {
            [content appendFormat:@"#crash_lr %@"
, lrLine];
            [frames addObject:lrLine];
        }
        for (int i = 0; i < n; i++) {
            NSString *line = syms ? [NSString stringWithUTF8String:syms[i]] : [NSString stringWithFormat:@"#%d pc ???", i];
            [content appendFormat:@"#%d %@\n", i, line];
            [frames addObject:line];
        }
        if (syms) free(syms);
        NSString *signalDesc = [NSString stringWithFormat:@"signal %s si_code=%d", LoganSignalName(sig), info ? info->si_code : 0];
        NSString *env = LoganEnvelope(@"native", signalDesc, LoganTopFrames(frames), content, ts, NO);
        if (env) {
            LoganWritePending(env);
            LoganPostSync(env);
        }
    }
chain:
    {
        struct sigaction *old = &gOldActions[sig];
        if ((old->sa_flags & SA_SIGINFO) && old->sa_sigaction) {
            old->sa_sigaction(sig, info, ucontext);
        } else if (old->sa_handler && old->sa_handler != SIG_DFL && old->sa_handler != SIG_IGN) {
            old->sa_handler(sig);
        }
    }
    signal(sig, SIG_DFL);
    raise(sig);
}

static void LoganUncaughtExceptionHandler(NSException *exception) {
    long long ts = (long long)([[NSDate date] timeIntervalSince1970] * 1000);
    NSString *name = exception.name ?: @"NSException";
    NSString *reason = exception.reason ?: @"";
    NSArray<NSString *> *symbols = exception.callStackSymbols ?: @[];
    NSMutableString *content = [NSMutableString string];
    [content appendString:@"Tombstone maker: 'logan-crash-kit-ios'\n"];
    [content appendString:@"Crash type: 'nsexception'\n"];
    [content appendFormat:@"exception: %@\n", name];
    [content appendFormat:@"reason: %@\n", reason];
    [content appendFormat:@"crash_time: %lld\n", ts];
    [content appendString:@"backtrace:\n"];
    for (NSUInteger i = 0; i < symbols.count; i++) {
        [content appendFormat:@"#%lu %@\n", (unsigned long)i, symbols[i]];
    }
    NSString *signalDesc = [NSString stringWithFormat:@"NSException:%@", name];
    NSString *env = LoganEnvelope(@"nsexception", signalDesc, LoganTopFrames(symbols), content, ts, NO);
    if (env) {
        LoganWritePending(env);
        LoganPostSync(env);
    }
    if (gOldExceptionHandler) gOldExceptionHandler(exception);
}

@implementation LoganCrashKit

+ (void)initWithConfig:(LoganCrashConfig *)config {
    static dispatch_once_t once;
    dispatch_once(&once, ^{
        gCfg = config;
        NSString *caches = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, YES).firstObject;
        gPendingDir = [caches stringByAppendingPathComponent:@"logan_crash_pending"];
        gOldExceptionHandler = NSGetUncaughtExceptionHandler();
        NSSetUncaughtExceptionHandler(&LoganUncaughtExceptionHandler);
        const int sigs[] = { SIGSEGV, SIGABRT, SIGBUS, SIGILL, SIGTRAP, SIGFPE };
        for (int i = 0; i < 6; i++) {
            struct sigaction act;
            memset(&act, 0, sizeof(act));
            act.sa_sigaction = &LoganSignalHandler;
            act.sa_flags = SA_SIGINFO | SA_ONSTACK;
            sigemptyset(&act.sa_mask);
            sigaction(sigs[i], &act, &gOldActions[sigs[i]]);
        }
        [self scanPending];
    });
}

+ (void)scanPending {
    if (!gCfg || !gPendingDir) return;
    NSFileManager *fm = [NSFileManager defaultManager];
    NSArray<NSString *> *files = [fm contentsOfDirectoryAtPath:gPendingDir error:nil];
    for (NSString *f in files) {
        if (![f hasSuffix:@".json"]) continue;
        NSString *path = [gPendingDir stringByAppendingPathComponent:f];
        NSString *env = [NSString stringWithContentsOfFile:path encoding:NSUTF8StringEncoding error:nil];
        if (env.length == 0) { [fm removeItemAtPath:path error:nil]; continue; }
        NSData *data = [env dataUsingEncoding:NSUTF8StringEncoding];
        NSMutableDictionary *o = [NSJSONSerialization JSONObjectWithData:data options:NSJSONReadingMutableContainers error:nil];
        if (![o isKindOfClass:[NSMutableDictionary class]]) { [fm removeItemAtPath:path error:nil]; continue; }
        o[@"deferred"] = @YES;
        NSData *json2 = [NSJSONSerialization dataWithJSONObject:o options:0 error:nil];
        NSString *env2 = [[NSString alloc] initWithData:json2 encoding:NSUTF8StringEncoding];
        if (LoganPostSync(env2)) {
            [fm removeItemAtPath:path error:nil];
        }
    }
}

@end
