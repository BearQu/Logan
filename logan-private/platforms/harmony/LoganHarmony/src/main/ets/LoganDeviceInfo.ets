/*
 * LoganDeviceInfo —— 设备与运行环境快照（对齐 Android com.ccic.logan.device.LoganDeviceInfo）。
 *
 * 原则：每一项取不到就整个键不写（宁缺毋假，绝不伪造数值）；只用免权限 API；
 * 序列号类（serial/udid/ODID/diskSN）需要权限且属敏感标识，一律不采集。
 * GPU 利用率鸿蒙无公开 API，只报显存占用（getGraphicsMemorySync），不伪造 GPU 使用率。
 *
 * 用途：随命令通道收到平台下发的 collect_info 时上报，并在冷启动后自动上报一次，
 * 供平台「设备清单」页展示内存 / 磁盘 / CPU / 温度 / 电量等真实运行状况。
 */
import deviceInfo from '@ohos.deviceInfo';
import hidebug from '@ohos.hidebug';
import batteryInfo from '@ohos.batteryInfo';
import statvfs from '@ohos.file.statvfs';
import thermal from '@ohos.thermal';
import netConn from '@ohos.net.connection';
import display from '@ohos.display';
import i18n from '@ohos.i18n';
import bundleManager from '@ohos.bundle.bundleManager';
import hilog from '@ohos.hilog';
import { common } from '@kit.AbilityKit';

const TAG = 'LoganDeviceInfo';

type InfoValue = string | number | boolean;
export type DeviceInfo = Record<string, InfoValue>;

function put(obj: DeviceInfo, key: string, value: InfoValue | undefined | null): void {
  if (value === undefined || value === null) {
    return;
  }
  if (typeof value === 'number' && !Number.isFinite(value)) {
    return;
  }
  if (typeof value === 'string' && value.length === 0) {
    return;
  }
  obj[key] = value;
}

function tryNum(what: string, fn: () => number | bigint): number | undefined {
  try {
    const raw = fn();
    const n = typeof raw === 'bigint' ? Number(raw) : raw;
    return Number.isFinite(n) ? n : undefined;
  } catch (e) {
    hilog.warn(0x00FF, TAG, '%{public}s unavailable: %{public}s', what, String(e));
    return undefined;
  }
}

function tryStr(what: string, fn: () => string): string | undefined {
  try {
    const s = fn();
    return (typeof s === 'string' && s.length > 0) ? s : undefined;
  } catch (e) {
    hilog.warn(0x00FF, TAG, '%{public}s unavailable: %{public}s', what, String(e));
    return undefined;
  }
}

function round1(v: number): number {
  return Math.round(v * 10) / 10;
}

function kBToBytes(kb: number | undefined): number | undefined {
  return kb === undefined ? undefined : Math.round(kb * 1024);
}

const THERMAL_NAMES: string[] = ['cool', 'normal', 'warm', 'hot', 'overheated', 'warning', 'emergency', 'escape'];

export class LoganDeviceInfo {
  /**
   * 采集一次快照。ctx 可为空（此时跳过依赖上下文的应用信息），返回 JSON 字符串；
   * 采集彻底失败返回空串，由调用方决定 ack 成败。
   */
  static snapshot(ctx?: common.UIAbilityContext): string {
    const obj: DeviceInfo = {};
    try {
      LoganDeviceInfo.putSystem(obj);
      LoganDeviceInfo.putApp(obj, ctx);
      LoganDeviceInfo.putMemory(obj);
      LoganDeviceInfo.putStorage(obj, ctx);
      LoganDeviceInfo.putCpu(obj);
      LoganDeviceInfo.putBattery(obj);
      LoganDeviceInfo.putThermal(obj);
      LoganDeviceInfo.putNetwork(obj);
      LoganDeviceInfo.putScreen(obj);
      put(obj, 'os', 'harmonyos');
      put(obj, 'platform', 3);
      put(obj, 'collectedAt', Date.now());
      if (Object.keys(obj).length <= 3) {
        hilog.warn(0x00FF, TAG, 'snapshot empty, skip');
        return '';
      }
      return JSON.stringify(obj);
    } catch (e) {
      hilog.warn(0x00FF, TAG, 'snapshot failed: %{public}s', String(e));
      return '';
    }
  }

  private static putSystem(obj: DeviceInfo): void {
    put(obj, 'brand', tryStr('brand', () => deviceInfo.brand));
    put(obj, 'manufacturer', tryStr('manufacture', () => deviceInfo.manufacture));
    put(obj, 'model', tryStr('model', () => deviceInfo.productModel));
    put(obj, 'marketName', tryStr('marketName', () => deviceInfo.marketName));
    put(obj, 'deviceType', tryStr('deviceType', () => deviceInfo.deviceType));
    put(obj, 'hardwareModel', tryStr('hardwareModel', () => deviceInfo.hardwareModel));
    put(obj, 'hardwareProfile', tryStr('hardwareProfile', () => deviceInfo.hardwareProfile));
    put(obj, 'abi', tryStr('abiList', () => deviceInfo.abiList));
    put(obj, 'bootloader', tryStr('bootloader', () => deviceInfo.bootloaderVersion));
    put(obj, 'securityPatch', tryStr('securityPatch', () => deviceInfo.securityPatchTag));
    put(obj, 'osFullName', tryStr('osFullName', () => deviceInfo.osFullName));
    put(obj, 'osVersion', tryStr('displayVersion', () => deviceInfo.displayVersion));
    put(obj, 'osReleaseType', tryStr('osReleaseType', () => deviceInfo.osReleaseType));
    put(obj, 'incremental', tryStr('incremental', () => deviceInfo.incrementalVersion));
    put(obj, 'sdkInt', tryNum('sdkApiVersion', () => deviceInfo.sdkApiVersion));
    put(obj, 'buildVersionId', tryNum('buildVersion', () => deviceInfo.buildVersion));
    put(obj, 'buildType', tryStr('buildType', () => deviceInfo.buildType));
    put(obj, 'distributionOSName', tryStr('distName', () => deviceInfo.distributionOSName));
    put(obj, 'distributionOSVersion', tryStr('distVersion', () => deviceInfo.distributionOSVersion));
    put(obj, 'locale', tryStr('locale', () => i18n.getSystemLocale()));
    put(obj, 'timezone', tryStr('timezone', () => {
      const d = new Date();
      const off = -d.getTimezoneOffset();
      const sign = off >= 0 ? '+' : '-';
      const abs = Math.abs(off);
      const hh = Math.floor(abs / 60);
      const mm = abs % 60;
      return 'UTC' + sign + (hh < 10 ? '0' + hh : String(hh)) + ':' + (mm < 10 ? '0' + mm : String(mm));
    }));
    // 开机时长：鸿蒙无公开 API → 宁缺毋假，不写该键
  }

  private static putApp(obj: DeviceInfo, ctx?: common.UIAbilityContext): void {
    try {
      const info = bundleManager.getBundleInfoForSelfSync(bundleManager.BundleFlag.GET_BUNDLE_INFO_WITH_APPLICATION);
      // 版本在 BundleInfo 顶层（BundleInfo.d.ts: versionCode/versionName/targetVersion），
      // appInfo 只有 label/debug 等，别去 appInfo 上取版本号
      put(obj, 'packageName', info.name);
      put(obj, 'appVersion', info.versionName);
      if (typeof info.versionCode === 'number') {
        put(obj, 'buildVersion', String(info.versionCode));
      }
      if (typeof info.targetVersion === 'number') {
        put(obj, 'targetVersion', String(info.targetVersion));
      }
      if (info.buildVersion) {
        put(obj, 'osBuildVersion', info.buildVersion);
      }
      const app = info.appInfo;
      if (app) {
        // app.label 可能是未解析的资源引用（如 '$string:app_name'），需用 resourceManager 解成真实文案；
        // 解不出来就整个键不写（宁缺毋假），平台侧再回落应用清单里的登记名。
        let label: string | undefined = undefined;
        const raw = app.label;
        if (typeof raw === 'string' && raw.length > 0) {
          if (raw.startsWith('$string:')) {
            const resName = raw.substring('$string:'.length);
            if (ctx) {
              try {
                const resolved = ctx.resourceManager.getStringByNameSync(resName);
                if (typeof resolved === 'string' && resolved.length > 0) { label = resolved; }
              } catch (e) {
                hilog.warn(0x00FF, TAG, 'appName resource unavailable: %{public}s', String(e));
              }
            }
          } else if (!raw.startsWith('$')) {
            label = raw;
          }
        }
        put(obj, 'appName', label);
        put(obj, 'appDebug', app.debug ? true : false);
      }
    } catch (e) {
      hilog.warn(0x00FF, TAG, 'bundleInfo unavailable: %{public}s', String(e));
    }
    if (ctx) {
      put(obj, 'filesDir', ctx.filesDir);
    }
  }

  private static putMemory(obj: DeviceInfo): void {
    const total = kBToBytes(tryNum('totalMem', () => hidebug.getSystemMemInfo().totalMem));
    const free = kBToBytes(tryNum('freeMem', () => hidebug.getSystemMemInfo().freeMem));
    const avail = kBToBytes(tryNum('availableMem', () => hidebug.getSystemMemInfo().availableMem));
    put(obj, 'totalMemBytes', total);
    put(obj, 'availMemBytes', avail);
    put(obj, 'freeMemBytes', free);
    if (total !== undefined && avail !== undefined && total > 0) {
      put(obj, 'memUsagePercent', round1((total - avail) * 100 / total));
    }
    put(obj, 'appPssKb', tryNum('pss', () => hidebug.getPss()));
    put(obj, 'nativeHeapAllocatedBytes', tryNum('nativeHeap', () => hidebug.getNativeHeapAllocatedSize()));
    put(obj, 'graphicsMemoryBytes', kBToBytes(tryNum('graphicsMem', () => hidebug.getGraphicsMemorySync())));
    try {
      const limit = hidebug.getAppMemoryLimit();
      if (limit) {
        // 实测模拟器会把 rssLimit 返回成 2^64 级「无限制」哨兵（1.8e19 KB），
        // 合理上限取 1TB（KB 口径），超界视为无限制、整个键不写。
        const SENTINEL_KB = 1099511627776;
        const rss = Number(limit.rssLimit);
        if (Number.isSafeInteger(rss) && rss > 0 && rss < SENTINEL_KB) {
          put(obj, 'appMemoryLimitBytes', Math.round(rss * 1024));
        }
        const vm = Number(limit.vmHeapLimit);
        if (Number.isSafeInteger(vm) && vm > 0 && vm < SENTINEL_KB) {
          put(obj, 'jsHeapLimitBytes', Math.round(vm * 1024));
        }
      }
    } catch (e) {
      hilog.warn(0x00FF, TAG, 'memoryLimit unavailable: %{public}s', String(e));
    }
  }

  private static putStorage(obj: DeviceInfo, ctx?: common.UIAbilityContext): void {
    const path = ctx ? ctx.filesDir : '/data/storage/el2/base/haps/entry/files';
    const total = tryNum('diskTotal', () => statvfs.getTotalSizeSync(path));
    const free = tryNum('diskFree', () => statvfs.getFreeSizeSync(path));
    put(obj, 'dataTotalBytes', total);
    put(obj, 'dataAvailBytes', free);
    if (total !== undefined && free !== undefined && total > 0 && total >= free) {
      put(obj, 'dataUsedBytes', total - free);
      put(obj, 'diskUsagePercent', round1((total - free) * 100 / total));
    }
  }

  private static putCpu(obj: DeviceInfo): void {
    // CPU 核数：鸿蒙无免权限公开 API（@ohos.process 未提供 cpNum）→ 宁缺毋假，不写该键
    const sys = tryNum('systemCpuUsage', () => hidebug.getSystemCpuUsage());
    if (sys !== undefined) {
      put(obj, 'cpuUsagePercent', round1(sys <= 1 ? sys * 100 : sys));
    }
    const proc = tryNum('processCpuUsage', () => hidebug.getCpuUsage());
    if (proc !== undefined) {
      put(obj, 'appCpuUsagePercent', round1(proc <= 1 ? proc * 100 : proc));
    }
  }

  private static putBattery(obj: DeviceInfo): void {
    const soc = tryNum('batterySOC', () => batteryInfo.batterySOC);
    put(obj, 'batteryPercent', soc);
    if (soc !== undefined) {
      put(obj, 'lowBattery', soc <= 15);
    }
    const state = tryNum('chargingStatus', () => batteryInfo.chargingStatus as number);
    if (state !== undefined) {
      // BatteryChargeState: 0=NONE 1=ENABLE(充电中) 2=DISABLE(未充电) 3=FULL
      put(obj, 'charging', state === 1 || state === 3);
      put(obj, 'batteryState', state);
    }
    const temp = tryNum('batteryTemperature', () => batteryInfo.batteryTemperature);
    if (temp !== undefined) {
      put(obj, 'batteryTempC', round1(temp / 10));
    }
    put(obj, 'batteryVoltageMv', tryNum('voltage', () => batteryInfo.voltage));
    put(obj, 'batteryTechnology', tryStr('technology', () => batteryInfo.technology));
  }

  private static putThermal(obj: DeviceInfo): void {
    const lv = tryNum('thermalLevel', () => thermal.getLevel() as number);
    if (lv === undefined) {
      return;
    }
    put(obj, 'thermalLevel', lv);
    put(obj, 'thermalLabel', lv >= 0 && lv < THERMAL_NAMES.length ? THERMAL_NAMES[lv] : String(lv));
  }

  private static putNetwork(obj: DeviceInfo): void {
    try {
      const has = netConn.hasDefaultNetSync();
      put(obj, 'networkAvailable', has);
      if (!has) {
        put(obj, 'network', 'none');
        return;
      }
      const handle = netConn.getDefaultNetSync();
      const cap = netConn.getNetCapabilitiesSync(handle);
      let kind = 'other';
      if (cap && cap.bearerTypes && cap.bearerTypes.length > 0) {
        const b = cap.bearerTypes[0] as number;
        // NetBearType: 0=CELLULAR 1=WIFI 3=ETHERNET 4=VPN
        if (b === 0) { kind = 'cellular'; } else if (b === 1) { kind = 'wifi'; } else if (b === 3) { kind = 'ethernet'; } else if (b === 4) { kind = 'vpn'; }
      }
      put(obj, 'network', kind);
      put(obj, 'downBandwidthKbps', cap ? cap.linkDownBandwidthKbps : undefined);
      put(obj, 'upBandwidthKbps', cap ? cap.linkUpBandwidthKbps : undefined);
    } catch (e) {
      hilog.warn(0x00FF, TAG, 'network unavailable: %{public}s', String(e));
    }
  }

  private static putScreen(obj: DeviceInfo): void {
    try {
      const d = display.getDefaultDisplaySync();
      put(obj, 'screenWidth', d.width);
      put(obj, 'screenHeight', d.height);
      put(obj, 'screenDensityDpi', d.densityDPI);
    } catch (e) {
      hilog.warn(0x00FF, TAG, 'display unavailable: %{public}s', String(e));
    }
  }
}



