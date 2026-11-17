package com.ccic.logan.http;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * H5 采集的内核无关装配器。
 *
 * 为什么不用 android.webkit.WebViewClient 包装：国内大量 App 用 TBS(X5)/UC 内核，
 * 它们的 WebView / WebViewClient 是自绘类（com.tencent.smtt.sdk.*），既不是 android.webkit 的子类，
 * 也不吃 android.webkit.JavascriptInterface 注解。写死任一种内核都会漏采 —— 与「通用性」硬约束冲突。
 *
 * 做法：只靠两样每个内核都有的能力 —— getUrl() 与 evaluateJavascript(String[, callback])，
 * 用弱引用登记 WebView + URL 轮询在页面切换时注入 logan-h5.js；上报通道由页面里的 JS 自己选
 * （有原生桥走桥，没有就 fetch 直传平台，logan-h5.js 内部已实现降级）。
 *
 * 通用性：不判断具体类名，反射探测方法；没装任何 WebView 内核时是干净的空操作。
 * 一行关闭：LoganH5Bridge.setEnabled(false)（同时关掉本类，见 LoganH5Bridge#enabled）。
 */
public final class LoganH5Watch {

    private static final String TAG = "LoganHttp";
    private static final long POLL_MS = 1500L;
    private static final int MAX_WATCHED = 32;

    private static final Map<Object, String> WATCHED =
            Collections.synchronizedMap(new WeakHashMap<Object, String>());
    private static volatile Handler handler;
    // evaluateJavascript 的探测结果必须按 Class 缓存：不同内核的方法签名各自不同
    private static final HashMap<Class<?>, Method> EVAL_CACHE = new HashMap<Class<?>, Method>();
    private static final java.util.Set<Class<?>> EVAL_MISS = new java.util.HashSet<Class<?>>();
    private static volatile boolean polling;

    private LoganH5Watch() {}

    /** 宿主每次建 WebView 都可以调（重复调无害）；编译期桥也会自动在 setWebViewClient 处调它。 */
    public static void watch(Object webView) {
        try {
            if (webView == null || !LoganH5Bridge.enabled()) {
                return;
            }
            if (probe(webView) == null) {
                return;
            }
            synchronized (WATCHED) {
                if (WATCHED.size() >= MAX_WATCHED) {
                    WATCHED.clear();
                }
                WATCHED.put(webView, null);
            }
            ensurePolling(webView);
            injectNow(webView);
        } catch (Throwable t) {
            Log.d(TAG, "h5 watch skipped: " + t);
        }
    }

    /**
     * 反射确认这个对象具备 evaluateJavascript 并缓存到「按 Class」的表里。
     * 不能按 android.webkit.ValueCallback 精确取方法：TBS 的 com.tencent.smtt.sdk.WebView 用的是自家
     * com.tencent.smtt.sdk.ValueCallback，UC 亦然 —— 精确签名会永久漏采（违反内核无关的通用性要求）。
     * 也不能把探测结果存成单个全局 Method：第一个被观察的 WebView 是哪个内核，后面的别的内核就会拿错方法。
     */
    private static Method probe(Object webView) {
        Class<?> key = webView.getClass();
        synchronized (EVAL_CACHE) {
            if (EVAL_MISS.contains(key)) {
                return null;
            }
            Method cached = EVAL_CACHE.get(key);
            if (cached != null) {
                return cached;
            }
        }
        Method found = null;
        try {
            java.lang.reflect.Method[] ms = key.getMethods();
            for (java.lang.reflect.Method m : ms) {
                if (!"evaluateJavascript".equals(m.getName())) {
                    continue;
                }
                Class<?>[] ps = m.getParameterTypes();
                if (ps.length == 1 && ps[0] == String.class) {
                    found = m; // 无回调版最省事，优先
                    break;
                }
                if (ps.length == 2 && ps[0] == String.class && ps[1].isInterface()) {
                    if (found == null) {
                        found = m; // 带回调版：回调传 null 即可
                    }
                }
            }
        } catch (Throwable ignore) {
        }
        if (found != null) {
            try {
                found.setAccessible(true);
            } catch (Throwable ignore) {
            }
            synchronized (EVAL_CACHE) {
                EVAL_CACHE.put(key, found);
            }
        } else {
            synchronized (EVAL_CACHE) {
                EVAL_MISS.add(key);
            }
            Log.d(TAG, "h5 watch: no evaluateJavascript on " + key.getName());
        }
        return found;
    }

    private static void ensurePolling(final Object webView) {
        if (polling) {
            return;
        }
        polling = true;
        Handler h = handler;
        if (h == null) {
            h = new Handler(Looper.getMainLooper());
            handler = h;
        }
        h.postDelayed(new Runnable() {
            @Override public void run() {
                boolean more;
                try {
                    more = sweep();
                } catch (Throwable ignore) {
                    more = false;
                }
                Handler hh = handler;
                if (more && hh != null) {
                    // 没有活着的 WebView 就停表，交给下一次 watch() 重新启动
                    hh.postDelayed(this, POLL_MS);
                } else {
                    polling = false;
                }
            }
        }, POLL_MS);
    }

    /**
     * 逐个检查登记的 WebView：URL 变了就重新注入（同 URL 不重复注，SPA 路由不漏）。
     * 返回是否仍有需要跟进的对象 —— 全空时调用方停止轮询。
     * 取不到 URL（已销毁的 WebView 调 getUrl() 会抛，Chromium 还会刷 Throwable 栈）就摘掉，
     * 不再反复戳它。
     */
    private static boolean sweep() {
        Map<Object, String> copy;
        synchronized (WATCHED) {
            copy = new HashMap<Object, String>(WATCHED);
        }
        boolean alive = false;
        for (Map.Entry<Object, String> en : copy.entrySet()) {
            Object view = en.getKey();
            if (view == null) {
                synchronized (WATCHED) {
                    WATCHED.remove(null);
                }
                continue;
            }
            String url = url(view);
            if (url == null) {
                synchronized (WATCHED) {
                    WATCHED.remove(view);
                }
                continue;
            }
            alive = true;
            if (url.startsWith("about:") || LoganHttp.get().isSelfUrlPublic(url)) {
                continue;
            }
            if (url.equals(en.getValue())) {
                continue;
            }
            synchronized (WATCHED) {
                WATCHED.put(view, url);
            }
            injectNow(view);
        }
        return alive;
    }

    private static void injectNow(Object view) {
        try {
            if (!LoganH5Bridge.enabled()) {
                return;
            }
            String js = LoganH5Bridge.scriptForWatch();
            if (js == null || js.length() == 0) {
                return;
            }
            Method m = probe(view);
            if (m == null) {
                return;
            }
            final Object target = view;
            final String code = js;
            Handler h = handler;
            Runnable job = new Runnable() {
                @Override public void run() {
                    try {
                        if (m.getParameterTypes().length == 2) {
                            m.invoke(target, code, null);
                        } else {
                            m.invoke(target, code);
                        }
                        Log.i(TAG, "h5 script injected into " + String.valueOf(url(target)));
                    } catch (Throwable t) {
                        Log.d(TAG, "h5 inject failed: " + t);
                    }
                }
            };
            if (h == null) {
                h = new Handler(Looper.getMainLooper());
                handler = h;
            }
            if (Looper.myLooper() == Looper.getMainLooper()) {
                job.run();
            } else {
                h.post(job);
            }
        } catch (Throwable t) {
            Log.d(TAG, "h5 inject skipped: " + t);
        }
    }

    private static String url(Object view) {
        try {
            Object u = view.getClass().getMethod("getUrl").invoke(view);
            return u == null ? null : String.valueOf(u);
        } catch (Throwable t) {
            return null;
        }
    }
}