/*
 * LoganIdentity —— 鸿蒙端统一机器码的唯一出处（对齐 Android LoganIdentity / iOS LoganDeviceId）。
 * 日志通道、崩溃通道、应用画像上报必须同源，否则平台按 device_id 的崩溃↔业务日志互证会被打乱。

 * 存储策略（等价 iOS Keychain / Android ANDROID_ID 的「尽量跨卸载」语义）：
 *  1) 权威源 Asset Store Kit（@ohos.security.asset，Tag.IS_PERSISTENT=true）——卸载重装仍可读回，
 *     鸿蒙上唯一跨卸载的本地安全存储。需要宿主 entry 模块声明
 *     ohos.permission.STORE_PERSISTENT_DATA（system_grant / normal 级，声明即授予）
 *     且签名 profile 覆盖该权限，否则 addSync 抛 201 → 自动退化 preferences-only。
 *  2) 同时镜像一份到 preferences：Asset 服务异常/未授权时的兜底，也避免每次冷启动都走 HUKS。
 *  3) 两处皆空 → util.generateRandomUUID 生成，先落 preferences 再尽力写 Asset。

 * 一致性规则：Asset 有值即以 Asset 为准（跨卸载权威），并顺手把 preferences 修复成同值。
 * 宿主不要再自己 generateRandomUUID 当 deviceId：一律调 LoganIdentity.deviceId(context)。
 */
import asset from "@ohos.security.asset";
import dataPreferences from "@ohos.data.preferences";
import hilog from "@ohos.hilog";
import util from "@ohos.util";
import { BusinessError } from "@ohos.base";
import common from "@ohos.app.ability.common";

const TAG = "LoganIdentity";
const ALIAS = "logan_device_id";
const PREF_NAME = "logan_prefs";
const PREF_KEY = "logan_device_id";
const ERR_NOT_FOUND = 24000002;
const ERR_DUPLICATED = 24000003;

export class LoganIdentity {
  private static cached: string = "";

  /** 取本机稳定机器码；进程内缓存，重复调用同值。永不返回空串。同步实现，可在 init 里直接调。 */
  static deviceId(context: common.Context): string {
    if (LoganIdentity.cached.length > 0) {
      return LoganIdentity.cached;
    }
    const pref = LoganIdentity.readPref(context);
    const vault = LoganIdentity.readAsset();
    let id = vault.length > 0 ? vault : pref;
    if (id.length === 0) {
      id = util.generateRandomUUID(true);
    }
    if (pref !== id) {
      LoganIdentity.writePref(context, id);
    }
    if (vault !== id) {
      const wrote = LoganIdentity.writeAsset(id);
      const verify = LoganIdentity.readAsset();
      hilog.info(0x0001, TAG, "asset persist wrote=%{public}d verify=%{public}d", wrote ? 1 : 0, verify === id ? 1 : 0);
    }
    LoganIdentity.cached = id;
    hilog.info(0x0001, TAG, "deviceId=%{public}s (asset=%{public}d pref=%{public}d)",
      id, vault.length > 0 ? 1 : 0, pref.length > 0 ? 1 : 0);
    return id;
  }

  /** 把机器码钉成指定值（PoC / 自动化验收用，生产别调）。 */
  static pin(context: common.Context, value: string): string {
    if (value.length === 0) {
      return LoganIdentity.deviceId(context);
    }
    LoganIdentity.writePref(context, value);
    LoganIdentity.writeAsset(value);
    LoganIdentity.cached = value;
    return value;
  }

  private static readPref(context: common.Context): string {
    try {
      const sp = dataPreferences.getPreferencesSync(context, { name: PREF_NAME });
      const v = sp.getSync(PREF_KEY, "") as string;
      return typeof v === "string" ? v : "";
    } catch (e) {
      return "";
    }
  }

  private static writePref(context: common.Context, value: string): void {
    try {
      const sp = dataPreferences.getPreferencesSync(context, { name: PREF_NAME });
      sp.putSync(PREF_KEY, value);
      sp.flush();
    } catch (e) {
      hilog.warn(0x0001, TAG, "writePref failed: %{public}s", String(e));
    }
  }

  private static readAsset(): string {
    try {
      const query = new Map<asset.Tag, asset.Value>();
      query.set(asset.Tag.ALIAS, LoganIdentity.bytes(ALIAS));
      // 关键：不带 RETURN_TYPE=ALL 时 querySync 只回属性集、SECRET 为 undefined（官方 ReturnType 契约）
      query.set(asset.Tag.RETURN_TYPE, asset.ReturnType.ALL);
      const rows: Array<asset.AssetMap> = asset.querySync(query);
      if (rows.length === 0) {
        return "";
      }
      const secret: asset.Value | undefined = rows[0].get(asset.Tag.SECRET);
      if (secret instanceof Uint8Array) {
        return LoganIdentity.str(secret);
      }
      if (typeof secret === "string") {
        return secret;
      }
      if (typeof secret === "number") {
        return String(secret);
      }
      hilog.warn(0x0001, TAG, "asset query returned unexpected SECRET type=%{public}s", typeof secret);
      return "";
    } catch (e) {
      const err = e as BusinessError;
      // 24000002 = 没有这条资产（首次安装），属正常分支；其余告警后走兜底
      if (err.code !== ERR_NOT_FOUND) {
        hilog.warn(0x0001, TAG, "asset query failed code=%{public}d", err.code ?? -1);
      }
      return "";
    }
  }

  private static writeAsset(value: string): boolean {
    try {
      const attrs = new Map<asset.Tag, asset.Value>();
      attrs.set(asset.Tag.ALIAS, LoganIdentity.bytes(ALIAS));
      attrs.set(asset.Tag.SECRET, LoganIdentity.bytes(value));
      // 设备标识非高敏：开机即可读，避免锁屏前初始化读不到
      attrs.set(asset.Tag.ACCESSIBILITY, asset.Accessibility.DEVICE_POWERED_ON);
      attrs.set(asset.Tag.IS_PERSISTENT, true);
      asset.addSync(attrs);
      hilog.info(0x0001, TAG, "asset addSync ok alias=%{public}s", ALIAS);
      return true;
    } catch (e) {
      const err = e as BusinessError;
      if (err.code === ERR_DUPLICATED) {
        try {
          const query = new Map<asset.Tag, asset.Value>();
          query.set(asset.Tag.ALIAS, LoganIdentity.bytes(ALIAS));
          const upd = new Map<asset.Tag, asset.Value>();
          upd.set(asset.Tag.SECRET, LoganIdentity.bytes(value));
          asset.updateSync(query, upd);
          hilog.info(0x0001, TAG, "asset updateSync ok alias=%{public}s", ALIAS);
          return true;
        } catch (e2) {
          hilog.warn(0x0001, TAG, "asset update failed: %{public}s", String(e2));
        }
        return false;
      } else {
        // 典型：201 未授予 ohos.permission.STORE_PERSISTENT_DATA → 退化为 preferences-only
        hilog.warn(0x0001, TAG, "asset add failed code=%{public}d msg=%{public}s (fallback preferences)",
          err.code ?? -1, err.message ?? "");
      }
      return false;
    }
  }

  private static bytes(s: string): Uint8Array {
    return new util.TextEncoder().encodeInto(s);
  }

  private static str(b: Uint8Array): string {
    try {
      return new util.TextDecoder("utf-8").decodeToString(b);
    } catch (e) {
      return "";
    }
  }
}
