/**
 * 全局当前应用上下文：多应用共用一套平台，所有列表/详情/崩溃页都按 appId 过滤。
 * 存 localStorage，跨刷新保留；订阅机制让顶栏切换后各页面自动重载。
 */
import { fetchAppListApi } from './api';

const KEY = 'logan_cu…';
let listeners = [];
let cache = null;
let loading = null;

export function getCurrentAppId() {
  try {
    return window.localStorage.getItem(KEY) || '';
  } catch (e) {
    return '';
  }
}

export function setCurrentAppId(appId) {
  try {
    if (appId) {
      window.localStorage.setItem(KEY, appId);
    } else {
      window.localStorage.removeItem(KEY);
    }
  } catch (e) { /* ignore */ }
  emit();
}

export function subscribe(fn) {
  listeners.push(fn);
  return () => {
    listeners = listeners.filter((f) => f !== fn);
  };
}

function emit() {
  const id = getCurrentAppId();
  listeners.forEach((f) => {
    try { f(id); } catch (e) { /* ignore */ }
  });
}

/** 拉应用清单（带缓存），并保证 currentAppId 一定在清单里 */
export function ensureApp() {
  if (!loading) {
    loading = fetchAppListApi()
      .then((list) => {
        cache = Array.isArray(list) ? list : [];
        const cur = getCurrentAppId();
        const hit = cache.some((a) => a.appId === cur);
        if (!hit) {
          const first = cache.length ? cache[0].appId : '';
          if (first) {
            try { window.localStorage.setItem(KEY, first); } catch (e) { /* ignore */ }
          }
          emit();
        }
        return cache;
      })
      .finally(() => { loading = null; });
  }
  return loading.then(() => cache || []);
}

export function getCachedApps() {
  return cache || [];
}

/** 给查询参数带上当前应用（appId 为空时不带，退化为全部） */
export function withApp(params) {
  const p = Object.assign({}, params || {});
  const id = getCurrentAppId();
  if (id) {
    p.appId = id;
  }
  return p;
}
