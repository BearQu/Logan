/*
 * LoganRemoteConfig —— 远程配置（对标 Bugly 收费版「远程配置」），对齐 Android LoganRemoteConfig / iOS LoganRemoteConfig。
 *
 * 语义：平台「远程配置」页维护 appId 维度键值；本类本地 preferences 缓存即时可读，
 * 后台按增量拉取（带本地 version，服务端只回更大的行；removed 数组的键本地删除=墓碑下发）。
 * 存储 = @ohos.data.preferences（logan_remote_config），与安卓 SharedPreferences 同套键。
 * 鉴权 = configure 时传入与崩溃/行为通道同一份 authHeader，否则被平台 nginx basic 拒收。
 */
import http from '@ohos.net.http';
import hilog from '@ohos.hilog';
import dataPreferences from '@ohos.data.preferences';
import { common } from '@kit.AbilityKit';

const TAG = 'logan';
const PREF_NAME = 'logan_remote_config';
const KEY_VERSION = 'logan_cfg_version';
const FETCH_INTERVAL_MS = 15 * 60 * 1000; // 推荐周期
const MIN_INTERVAL_MS = 30 * 1000;        // 手动刷新节流

type ChangeHandler = (configs: Record<string, string>) => void;

let baseUrl = '';
let appId = '';
let deviceId = '';
let authHeader = '';
let platform = 3;
let onChange: ChangeHandler | null = null;
let appCtx: common.Context | null = null;
let lastFetchAt = 0;
let fetching = false;
let lastAccessAt = 0;                      // 宿主最近一次读取/显式刷新：后台周期拉取只为「有人在用」服务
const ACTIVE_WINDOW_MS = 30 * 60 * 1000;   // 超过此窗口无人使用则停止后台拉取
const DRIVE_INTERVAL_MS = 5 * 60 * 1000;   // LoganHttp start 周期驱动检查节拍
let driveTimer = -1;

// 单例缓存：每次 getPreferencesSync 拿到的若是新实例，后一轮的内存态会覆盖前一轮未落盘的写入
// （真机坑：changed=false 分支不 flush，上一轮 new_put 只在旧实例内存里，新实例 flush 反而把磁盘旧值写回）。
let spCache: dataPreferences.Preferences | null = null;

function sp(): dataPreferences.Preferences | null {
  const ctx = appCtx;
  if (ctx === null) {
    return null;
  }
  if (spCache !== null) {
    return spCache;
  }
  try {
    spCache = dataPreferences.getPreferencesSync(ctx, { name: PREF_NAME });
    return spCache;
  } catch (e) {
    hilog.warn(0x00FF, TAG, 'getPreferences failed: %{public}s', String(e));
    return null;
  }
}

function postJson(path: string, obj: Record<string, Object>): Promise<string> {
  return new Promise<string>((resolve) => {
    if (baseUrl.length === 0 || appId.length === 0) {
      resolve('{}');
      return;
    }
    const req = http.createHttp();
    const h: Record<string, string> = {
      'Content-Type': 'application/json',
      'appId': appId,
      'deviceId': deviceId,
      'platform': String(platform),
      'sdkVersion': '0.1.0'
    };
    if (authHeader.length > 0) {
      h['Authorization'] = authHeader;
    }
    req.request(baseUrl + path, {
      method: http.RequestMethod.POST,
      header: h,
      extraData: JSON.stringify(obj),
      connectTimeout: 10000,
      readTimeout: 15000
    }, (err, resp) => {
      req.destroy();
      if (err || !resp) {
        hilog.warn(0x00FF, TAG, 'request failed err=%{public}s', String(err && err.message ? err.message : 'no-resp'));
        resolve('{}');
        return;
      }
      const code = resp.responseCode;
      const body = typeof resp.result === 'string' ? (resp.result as string) : '{}';
      if (code !== 200) {
        hilog.warn(0x00FF, TAG, 'http %{public}d %{public}s', code, body.substring(0, 160));
        resolve('{}');
        return;
      }
      resolve(body);
    });
  });
}

export class LoganRemoteConfig {
  /** 与 LoganHttp.configure 同阶段调用一次（同一份身份与凭据），ctx 需 ApplicationContext/UIAbilityContext。 */
  static configure(context: common.Context, cBaseUrl: string, cAppId: string, cDeviceId: string,
    cAuthHeader: string, cPlatform: number): void {
    appCtx = context.getApplicationContext();
    baseUrl = cBaseUrl.replace(/\/+$/, '');
    appId = cAppId;
    deviceId = cDeviceId;
    authHeader = cAuthHeader;
    platform = cPlatform > 0 ? cPlatform : 3;
  }

  /** 键值变化回调（后台）；宿主据此即时生效（开关、阈值）。 */
  static setOnChangeHandler(h: ChangeHandler | null): void {
    onChange = h;
  }

  /** 本地缓存即时读（不触发网络）。未命中返回 def。 */
  static getString(key: string, def: string): string {
    const p = sp();
    if (p === null) {
      return def;
    }
    lastAccessAt = Date.now();
    const v = p.getSync(key, '') as string;
    return typeof v === 'string' && v.length > 0 ? v : def;
  }

  static getBoolean(key: string, def: boolean): boolean {
    const v = LoganRemoteConfig.getString(key, '');
    if (v.length === 0) {
      return def;
    }
    return v === 'true' || v === '1';
  }

  static getLong(key: string, def: number): number {
    const v = LoganRemoteConfig.getString(key, '');
    if (v.length === 0) {
      return def;
    }
    const n = Number(v);
    return Number.isFinite(n) ? Math.trunc(n) : def;
  }

  /** 本地全部键值快照（不含版本键）。 */
  static getAll(): Record<string, string> {
    const out: Record<string, string> = {};
    const p = sp();
    if (p === null) {
      return out;
    }
    try {
      const all = p.getAllSync() as Record<string, Object>;
      const keys = Object.keys(all);
      for (let i = 0; i < keys.length; i++) {
        const k = keys[i];
        if (k === KEY_VERSION) {
          continue;
        }
        const v = all[k];
        if (typeof v === 'string') {
          out[k] = v as string;
        }
      }
    } catch (e) {
      hilog.warn(0x00FF, TAG, 'getAll failed: %{public}s', String(e));
    }
    return out;
  }

  static localVersion(): number {
    const p = sp();
    if (p === null) {
      return 0;
    }
    const v = p.getSync(KEY_VERSION, 0);
    return typeof v === 'number' ? v : 0;
  }

  /** 宿主自有定时器按此周期调 refresh 即可（SDK 不自起常驻定时器，省流量）。 */
  static recommendedFetchIntervalMs(): number {
    return FETCH_INTERVAL_MS;
  }

  /**
   * 由 LoganHttp start 调用的周期驱动：setInterval 每 DRIVE_INTERVAL_MS 检查一次，
   * 带 ACTIVE_WINDOW_MS 停摆守卫不空转（宿主近半小时无读取则后台不拉取）。重复调用幂等。
   */
  static startPeriodicDrive(): void {
    if (driveTimer >= 0) {
      return;
    }
    driveTimer = setInterval(() => {
      if (Date.now() - lastAccessAt > ACTIVE_WINDOW_MS) {
        return;
      }
      LoganRemoteConfig.refreshForcing(true);
    }, DRIVE_INTERVAL_MS);
  }

  static refresh(): void {
    LoganRemoteConfig.refreshForcing(false);
  }

  /** force=true 忽略节流（冷启动第一条推荐传 true，保证装包后立刻拿到首轮配置）。 */
  static refreshForcing(force: boolean): void {
    if (fetching || baseUrl.length === 0 || appId.length === 0 || appCtx === null) {
      return;
    }
    const now = Date.now();
    if (force) {
      lastAccessAt = now;
    } else if (now - lastAccessAt > ACTIVE_WINDOW_MS) {
      return; // 宿主近 30 分钟没有任何读取/显式刷新：不为无人使用的场景后台联网
    }
    if (!force && now - lastFetchAt < MIN_INTERVAL_MS) {
      return;
    }
    fetching = true;
    const since = LoganRemoteConfig.localVersion();
    hilog.info(0x00FF, TAG, 'fetch start since=%{public}d baseUrl=%{public}s appId=%{public}s hasAuth=%{public}d', since, baseUrl, appId, authHeader.length > 0 ? 1 : 0);
    const body: Record<string, Object> = { 'appId': appId, 'sinceVersion': since };
    postJson('/logan/config/get.json', body).then((text: string) => {
      lastFetchAt = Date.now();
      fetching = false;
      let parsed: Record<string, Object> | null = null;
      try {
        parsed = JSON.parse(text) as Record<string, Object>;
      } catch (e) {
        return;
      }
      if (parsed === null) {
        return;
      }
      const rc = Number(parsed['code']);
      hilog.info(0x00FF, TAG, 'fetch done code=%{public}d msg=%{public}s', rc, String(parsed['msg']));
      if (rc !== 200) {
        return;
      }
      const data = parsed['data'] as Record<string, Object> | null;
      if (data === null || typeof data !== 'object') {
        return;
      }
      const p = sp();
      if (p === null) {
        return;
      }
      let changed = false;
      const configs = data['configs'] as Record<string, Object> | null;
      if (configs !== null && typeof configs === 'object') {
        const keys = Object.keys(configs);
        for (let i = 0; i < keys.length; i++) {
          const k = keys[i];
          const v = String(configs[k]);
          const old = p.getSync(k, null);
          p.putSync(k, v);
          if (typeof old !== 'string' || old !== v) {
            changed = true;
          }
        }
      }
      const removed = data['removed'] as Array<Object> | null;
      if (removed !== null && Array.isArray(removed)) {
        for (let i = 0; i < removed.length; i++) {
          const k = String(removed[i]);
          if (k.length > 0 && p.getSync(k, null) !== null) {
            p.deleteSync(k);
            changed = true;
          }
        }
      }
      const serverVersion = Number(data['version']);
      if (Number.isFinite(serverVersion) && serverVersion !== LoganRemoteConfig.localVersion()) {
        p.putSync(KEY_VERSION, serverVersion);
        changed = true;
      }
      try {
        p.flush();
      } catch (e) {
        hilog.warn(0x00FF, TAG, 'flush failed: %{public}s', String(e));
      }
      if (changed && onChange !== null) {
        try {
          onChange(LoganRemoteConfig.getAll());
        } catch (e) {
          hilog.warn(0x00FF, TAG, 'onChange failed: %{public}s', String(e));
        }
      }
    }).catch(() => {
      fetching = false;
    });
  }
}
