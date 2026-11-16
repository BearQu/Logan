//
//  LoganDeviceInfo.m
//  见 LoganDeviceInfo.h 的设计约束：只用公开 API（Foundation + libc + Mach），零新增链接框架；
//  任一字段取不到就整个键不写，绝不编造数值。GPU 利用率无公开 API → 不采。
//
#import "LoganDeviceInfo.h"
#import <TargetConditionals.h>
#import <sys/utsname.h>
#import <sys/sysctl.h>
#import <sys/param.h>
#import <sys/mount.h>
#import <sys/time.h>
#import <mach/mach.h>
#import <mach/mach_host.h>
#import <ifaddrs.h>
#import <arpa/inet.h>
#import <net/if.h>
#if TARGET_OS_IPHONE
#import <UIKit/UIKit.h>
#endif

static void LoganDiPut(NSMutableDictionary *obj, NSString *key, id _Nullable value) {
    if (value == nil || value == (id)[NSNull null]) { return; }
    if ([value isKindOfClass:[NSString class]] && [(NSString *)value length] == 0) { return; }
    if ([value isKindOfClass:[NSNumber class]]) {
        double d = [(NSNumber *)value doubleValue];
        if (isnan(d) || isinf(d)) { return; }
        if (d < 0) { return; } // 采集类指标没有负数语义，负值当失败
    }
    obj[key] = value;
}

static NSNumber *LoganDiRound1(double v) {
    return [NSNumber numberWithDouble:round(v * 10.0) / 10.0];
}

static NSString * _Nullable LoganDiSysctlString(const char *name) {
    char buf[256] = {0};
    size_t len = sizeof(buf) - 1;
    if (sysctlbyname(name, buf, &len, NULL, 0) != 0) { return nil; }
    if (len <= 0) { return nil; }
    buf[sizeof(buf) - 1] = 0;
    return [NSString stringWithUTF8String:buf];
}

static uint64_t LoganDiSysctlUInt(const char *name, BOOL *ok) {
    uint64_t value = 0;
    size_t len = sizeof(value);
    if (sysctlbyname(name, &value, &len, NULL, 0) != 0) { if (ok) { *ok = NO; } return 0; }
    if (ok) { *ok = YES; }
    return value;
}

static NSString * _Nullable LoganDiMachine(void) {
    struct utsname info;
    if (uname(&info) != 0) { return nil; }
    return [NSString stringWithUTF8String:info.machine];
}

// CPU 占用：host_cpu_load_info 两次采样取区间差（与 Android 读 /proc/stat 同一思路）。
// 静态缓存让「冷启动随心跳」与「平台下发命令」两次采集之间自然形成采样窗口；首采无上一轮则不写键。
static BOOL gLoganDiCpuPrevValid = NO;
static natural_t gLoganDiCpuPrevTicks[CPU_STATE_MAX];

static NSNumber * _Nullable LoganDiSystemCpuPercent(void) {
    host_cpu_load_info_data_t stats;
    mach_msg_type_number_t count = HOST_CPU_LOAD_INFO_COUNT;
    kern_return_t kr = host_statistics(mach_host_self(), HOST_CPU_LOAD_INFO, (host_info_t)&stats, &count);
    if (kr != KERN_SUCCESS || count < HOST_CPU_LOAD_INFO_COUNT) { return nil; }
    natural_t cur[CPU_STATE_MAX];
    cur[CPU_STATE_USER] = stats.cpu_ticks[CPU_STATE_USER];
    cur[CPU_STATE_SYSTEM] = stats.cpu_ticks[CPU_STATE_SYSTEM];
    cur[CPU_STATE_IDLE] = stats.cpu_ticks[CPU_STATE_IDLE];
    cur[CPU_STATE_NICE] = stats.cpu_ticks[CPU_STATE_NICE];
    NSNumber *result = nil;
    if (gLoganDiCpuPrevValid) {
        double deltaTotal = 0;
        double deltaBusy = 0;
        BOOL wrapped = NO;
        for (int i = 0; i < CPU_STATE_MAX; i++) {
            if (cur[i] < gLoganDiCpuPrevTicks[i]) { wrapped = YES; break; }
            deltaTotal += (double)(cur[i] - gLoganDiCpuPrevTicks[i]);
            if (i != CPU_STATE_IDLE) { deltaBusy += (double)(cur[i] - gLoganDiCpuPrevTicks[i]); }
        }
        if (!wrapped && deltaTotal > 0) { result = LoganDiRound1(deltaBusy * 100.0 / deltaTotal); }
    }
    for (int i = 0; i < CPU_STATE_MAX; i++) { gLoganDiCpuPrevTicks[i] = cur[i]; }
    gLoganDiCpuPrevValid = YES;
    return result;
}

static NSNumber * _Nullable LoganDiCoreCount(void) {
    processor_info_array_t cpuInfo = NULL;
    mach_msg_type_number_t cpuCount = 0, numCpuInfo = 0;
    kern_return_t kr = host_processor_info(mach_host_self(), PROCESSOR_CPU_LOAD_INFO, &cpuCount, &cpuInfo, &numCpuInfo);
    if (kr != KERN_SUCCESS || cpuCount == 0) {
        if (cpuInfo != NULL) { vm_deallocate(mach_task_self(), (vm_address_t)cpuInfo, (vm_size_t)(numCpuInfo * sizeof(integer_t))); }
        return nil;
    }
    NSNumber *result = [NSNumber numberWithUnsignedInt:cpuCount];
    vm_deallocate(mach_task_self(), (vm_address_t)cpuInfo, (vm_size_t)(numCpuInfo * sizeof(integer_t)));
    return result;
}

/// 本进程实时物理内存占用（与 Xcode 内存报表同源的 phys_footprint）。
static NSNumber * _Nullable LoganDiAppFootprintBytes(void) {
    task_vm_info_data_t info;
    mach_msg_type_number_t count = TASK_VM_INFO_COUNT;
    kern_return_t kr = task_info(mach_task_self(), TASK_VM_INFO, (task_info_t)&info, &count);
    if (kr != KERN_SUCCESS) { return nil; }
    if (info.phys_footprint <= 0) { return nil; }
    return [NSNumber numberWithUnsignedLongLong:info.phys_footprint];
}

/// 系统内存：hw.memsize 总量 + vm_statistics64 free 口径（与鸿蒙 availableMem 语义近似，跨端只作趋势对比）。
static void LoganDiSystemMemory(NSMutableDictionary *obj) {
    BOOL ok = NO;
    uint64_t total = LoganDiSysctlUInt("hw.memsize", &ok);
    if (ok && total > 0) { LoganDiPut(obj, @"totalMemBytes", [NSNumber numberWithUnsignedLongLong:total]); }
    vm_size_t pageSize = 0;
    if (host_page_size(mach_host_self(), &pageSize) != KERN_SUCCESS || pageSize == 0) { return; }
    vm_statistics64_data_t vmStats;
    mach_msg_type_number_t count = HOST_VM_INFO64_COUNT;
    kern_return_t kr = host_statistics64(mach_host_self(), HOST_VM_INFO64, (host_info64_t)&vmStats, &count);
    if (kr != KERN_SUCCESS) { return; }
    uint64_t freeBytes = (uint64_t)vmStats.free_count * (uint64_t)pageSize;
    LoganDiPut(obj, @"freeMemBytes", [NSNumber numberWithUnsignedLongLong:freeBytes]);
    LoganDiPut(obj, @"availMemBytes", [NSNumber numberWithUnsignedLongLong:freeBytes]);
    if (ok && total > 0) {
        uint64_t wired = (uint64_t)vmStats.wire_count * (uint64_t)pageSize;
        uint64_t compressed = (uint64_t)vmStats.compressor_page_count * (uint64_t)pageSize;
        LoganDiPut(obj, @"wiredMemBytes", [NSNumber numberWithUnsignedLongLong:wired]);
        LoganDiPut(obj, @"compressedMemBytes", [NSNumber numberWithUnsignedLongLong:compressed]);
    }
}

static void LoganDiDisk(NSMutableDictionary *obj) {
    NSString *home = NSHomeDirectory();
    if (home.length == 0) { return; }
    struct statfs st;
    if (statfs([home fileSystemRepresentation], &st) != 0) { return; }
    if (st.f_blocks <= 0 || st.f_bsize <= 0) { return; }
    uint64_t block = (uint64_t)st.f_bsize;
    uint64_t total = (uint64_t)st.f_blocks * block;
    uint64_t avail = (uint64_t)st.f_bavail * block;
    LoganDiPut(obj, @"dataTotalBytes", [NSNumber numberWithUnsignedLongLong:total]);
    LoganDiPut(obj, @"dataAvailBytes", [NSNumber numberWithUnsignedLongLong:avail]);
    if (total >= avail) {
        LoganDiPut(obj, @"dataUsedBytes", [NSNumber numberWithUnsignedLongLong:(total - avail)]);
        LoganDiPut(obj, @"diskUsagePercent", LoganDiRound1((double)(total - avail) * 100.0 / (double)total));
    }
}

/// 网络类型：getifaddrs 判 en*(Wi-Fi)/pdp_ip*(蜂窝)，纯 libc，不引入 Network.framework。
static void LoganDiNetwork(NSMutableDictionary *obj) {
    struct ifaddrs *addrs = NULL;
    if (getifaddrs(&addrs) != 0) { return; }
    BOOL hasWifi = NO;
    BOOL hasCellular = NO;
    BOOL hasAny = NO;
    for (struct ifaddrs *p = addrs; p != NULL; p = p->ifa_next) {
        if (p->ifa_addr == NULL) { continue; }
        if ((p->ifa_flags & IFF_UP) == 0 || (p->ifa_flags & IFF_RUNNING) == 0) { continue; }
        if (p->ifa_addr->sa_family != AF_INET && p->ifa_addr->sa_family != AF_INET6) { continue; }
        const char *name = p->ifa_name;
        if (name == NULL) { continue; }
        NSString *n = [NSString stringWithUTF8String:name];
        if (n == nil) { continue; }
        hasAny = YES;
        if ([n hasPrefix:@"en"]) { hasWifi = YES; }
        if ([n hasPrefix:@"pdp_ip"]) { hasCellular = YES; }
    }
    freeifaddrs(addrs);
    if (!hasAny) { return; } // 一个可用接口都没有时不写键，避免把「枚举失败」报成「无网络」
    LoganDiPut(obj, @"networkAvailable", @YES);
    LoganDiPut(obj, @"network", hasWifi ? @"wifi" : (hasCellular ? @"cellular" : @"other"));
}

static void LoganDiUptime(NSMutableDictionary *obj) {
    struct timeval boot;
    size_t len = sizeof(boot);
    int mib[2] = { CTL_KERN, KERN_BOOTTIME };
    if (sysctl(mib, 2, &boot, &len, NULL, 0) != 0) { return; }
    struct timeval now;
    gettimeofday(&now, NULL);
    long long secs = (long long)now.tv_sec - (long long)boot.tv_sec;
    if (secs <= 0) { return; }
    LoganDiPut(obj, @"uptimeSeconds", [NSNumber numberWithLongLong:secs]);
}

@implementation LoganDeviceInfo

+ (NSDictionary *)snapshotDictionary {
    @try {
        NSMutableDictionary *obj = [NSMutableDictionary dictionary];
        // —— 系统 / 机型（与 Android Build.* 与鸿蒙 deviceInfo 同名键）——
        LoganDiPut(obj, @"os", @"ios");
        LoganDiPut(obj, @"platform", @2);
        LoganDiPut(obj, @"collectedAt", @((long long)([[NSDate date] timeIntervalSince1970] * 1000)));
        NSProcessInfo *proc = [NSProcessInfo processInfo];
        LoganDiPut(obj, @"osVersion", proc.operatingSystemVersionString);
        NSString *machine = LoganDiMachine();
        LoganDiPut(obj, @"machine", machine);
        NSString *brand = LoganDiSysctlString("hw.model");
        LoganDiPut(obj, @"brand", brand);
        NSString *vendor = LoganDiSysctlString("hw.optional.arm64");
        if (vendor != nil) { LoganDiPut(obj, @"abi", @"arm64"); }
#if TARGET_OS_IPHONE
        UIDevice *device = [UIDevice currentDevice];
        LoganDiPut(obj, @"systemName", device.systemName);
        LoganDiPut(obj, @"systemVersion", device.systemVersion);
        LoganDiPut(obj, @"deviceName", device.name);
        LoganDiPut(obj, @"model", device.model);
#endif
        // —— 应用版本（宿主不必再传，平台以头里的为准，这里冗余备份）——
        NSBundle *bundle = [NSBundle mainBundle];
        LoganDiPut(obj, @"appVersion", [bundle objectForInfoDictionaryKey:@"CFBundleShortVersionString"]);
        LoganDiPut(obj, @"buildVersion", [bundle objectForInfoDictionaryKey:@"CFBundleVersion"]);
        LoganDiPut(obj, @"bundleIdentifier", bundle.bundleIdentifier);
        // —— CPU ——
        LoganDiPut(obj, @"cpuCount", [self coreCountNumber]);
        LoganDiPut(obj, @"cpuUsagePercent", LoganDiSystemCpuPercent());
        LoganDiPut(obj, @"activeProcessorCount", @([proc activeProcessorCount]));
        LoganDiPut(obj, @"processorCount", @([proc processorCount]));
        // —— 内存 ——
        LoganDiSystemMemory(obj);
        LoganDiPut(obj, @"appPhysFootprintBytes", LoganDiAppFootprintBytes());
#if TARGET_OS_IPHONE
        LoganDiPut(obj, @"thermalState", @([proc thermalState])); // 0..3 nominal/fair/serious/critical
#endif
        // —— 存储 ——
        LoganDiDisk(obj);
        // —— 网络 / 时间 / 语言 ——
        LoganDiNetwork(obj);
        LoganDiUptime(obj);
        NSTimeZone *tz = [NSTimeZone localTimeZone];
        LoganDiPut(obj, @"timezone", tz.name);
        LoganDiPut(obj, @"timezoneSeconds", @((long long)tz.secondsFromGMT));
        NSString *localeId = [[NSLocale currentLocale] localeIdentifier];
        LoganDiPut(obj, @"locale", localeId);
        LoganDiPut(obj, @"language", [NSLocale preferredLanguages].firstObject);
#if TARGET_OS_IPHONE
        LoganDiPut(obj, @"screenScale", @([UIScreen mainScreen].scale));
        CGSize size = [UIScreen mainScreen].bounds.size;
        if (size.width > 0 && size.height > 0) {
            LoganDiPut(obj, @"screenWidthPt", LoganDiRound1(size.width));
            LoganDiPut(obj, @"screenHeightPt", LoganDiRound1(size.height));
        }
        // 电量：需要宿主先开 UIDeviceBatteryMonitoringEnabled，未开启时 maxValue 为 -1 → LoganDiPut 自动丢键
        BOOL monitoring = device.isBatteryMonitoringEnabled;
        LoganDiPut(obj, @"batteryMonitoringEnabled", monitoring ? @YES : @NO);
        if (monitoring) {
            CGFloat level = device.batteryLevel;
            if (level >= 0) { LoganDiPut(obj, @"batteryLevelPercent", LoganDiRound1((double)level * 100.0)); }
            NSInteger state = (NSInteger)device.batteryState;
            if (state >= 0) { LoganDiPut(obj, @"batteryState", @(state)); }
        }
#endif
        if (obj.count <= 3) { return nil; } // 只有 os/platform/collectedAt → 视为采集失败
        return [obj copy];
    } @catch (NSException *exception) {
        return nil;
    }
}

+ (NSNumber *)coreCountNumber {
    return LoganDiCoreCount();
}

+ (NSString *)snapshotJSON {
    NSDictionary *dict = [self snapshotDictionary];
    if (dict == nil) { return @""; }
    NSError *error = nil;
    NSData *data = [NSJSONSerialization dataWithJSONObject:dict options:0 error:&error];
    if (data == nil || error != nil) { return @""; }
    NSString *json = [[NSString alloc] initWithData:data encoding:NSUTF8StringEncoding];
    return json ?: @"";
}

@end
