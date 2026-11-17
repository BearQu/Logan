package com.ccic.logan.http;

import android.util.Log;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.lang.reflect.Method;

/**
 * 编译期桥改写 setWebViewClient 调用点的静态跳板。
 *
 * 参数类型刻意用 Object：宿主可能用 android.webkit.WebView，也可能用 TBS(X5)/UC 的自绘类
 * （com.tencent.smtt.sdk.WebView 等，不是 android.webkit 子类）。用 Object 才能一条规则通吃，
 * 也保证栈形状与原 INVOKEVIRTUAL 等价（两个引用操作数 + void）。
 *
 * 行为：先把这个 WebView 登记到 LoganH5Watch（内核无关注入）；android.webkit 且可包装时再多层
 * LoganWebViewClient 以拿原生错误回调；其余情况原样反射回调宿主的 setWebViewClient，业务零感知。
 */
public final class LoganWebViewInstaller {

    private static final String TAG = "LoganHttp";

    /** 重入闸：改写后 super.setWebViewClient(...) 也会跳进来，同线程内直接走原生转发，避免递归。 */
    private static final ThreadLocal<Boolean> IN_HOOK = new ThreadLocal<Boolean>() {
        @Override protected Boolean initialValue() {
            return Boolean.FALSE;
        }
    };

    private LoganWebViewInstaller() {}

    public static void setWebViewClient(Object view, Object client) {
        if (view == null) {
            return;
        }
        if (Boolean.TRUE.equals(IN_HOOK.get())) {
            // 改写后的 super.setWebViewClient(...) 会二次跳进来：同线程直接原生转发，防无限递归
            callOriginal(view, client);
            return;
        }
        if (client instanceof LoganWebViewClient) {
            // 已经是我们的包装类，不再二次包装
            callOriginal(view, client);
            return;
        }
        IN_HOOK.set(Boolean.TRUE);
        try {
            doHook(view, client);
        } catch (Throwable t) {
            Log.d(TAG, "webview hook skipped: " + t);
            callOriginal(view, client);
        } finally {
            IN_HOOK.set(Boolean.FALSE);
        }
    }

    private static void doHook(Object view, Object client) {
        try {
            LoganH5Watch.watch(view);
        } catch (Throwable t) {
            Log.d(TAG, "h5 watch skipped: " + t);
        }
        try {
            if (view instanceof WebView && LoganHttp.get().isEnabled() && LoganH5Bridge.enabled()) {
                WebView wv = (WebView) view;
                WebViewClient wrap = new LoganWebViewClient(client instanceof WebViewClient ? (WebViewClient) client : null);
                wv.setWebViewClient(wrap);
                return;
            }
        } catch (Throwable t) {
            Log.d(TAG, "webview wrap skipped: " + t);
        }
        callOriginal(view, client);
    }

    /** 反射调宿主自身的 setWebViewClient（TBS/UC 内核的 client 类型只能靠反射匹配）。 */
    private static void callOriginal(Object view, Object client) {
        try {
            Class<?> vc = view.getClass();
            while (vc != null && vc != Object.class) {
                for (Method m : vc.getDeclaredMethods()) {
                    if (!"setWebViewClient".equals(m.getName()) || m.getParameterTypes().length != 1) {
                        continue;
                    }
                    if (client != null && !m.getParameterTypes()[0].isInstance(client)) {
                        continue;
                    }
                    m.setAccessible(true);
                    m.invoke(view, client);
                    return;
                }
                vc = vc.getSuperclass();
            }
        } catch (Throwable t) {
            Log.d(TAG, "setWebViewClient forward skipped: " + t);
        }
    }
}