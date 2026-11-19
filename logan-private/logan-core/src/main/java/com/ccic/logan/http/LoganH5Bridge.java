package com.ccic.logan.http;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import com.ccic.logan.error.LoganError;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 自研 H5 SDK 的容器侧装配器（业务页零改动，由原生在 document-start 注入 logan-h5.js）。
 *
 * 通道选择：优先 window.LoganH5.postJson(...) 走 JS 桥交给原生上报（没有跨域问题，
 * 复用原生批量/压缩/身份，也不要求平台开 CORS）；桥不可用（X5/老内核/被禁用）时，
 * 注入脚本自己降级为 fetch keepalive 直传平台，两种结局数据都不丢。
 *
 * 一行关闭：LoganH5Bridge.setEnabled(false)（默认跟随 LoganHttp 总开关）。
 */
public final class LoganH5Bridge {

    private static final String TAG = "LoganHttp";
    /** 注入脚本随 SDK 一起发布，宿主不需要拷 assets。 */
    private static final String ASSET_JS = "logan/h5/logan-h5.js";
    private static final String BRIDGE_NAME = "LoganH5";
    private static final int TAG_KEY = 0x4C614831; // 标记同一文档已注入，避免 onPageStarted/Finished 重复注入
    private static final int MAX_EVENTS_PER_POST = 200;

    private static volatile Context appContext;
    private static volatile boolean h5Enabled = true;
    private static volatile String cachedJs;
    private static volatile long lastAssetTry;
    private static volatile boolean assetMissing;
    private static final Map<String, Boolean> INJECTED_PAGES = new HashMap<String, Boolean>();

    private LoganH5Bridge() {}

    public static void attachContext(Context ctx) {
        appContext = ctx == null ? null : ctx.getApplicationContext();
    }

    /** H5 采集单独一行开关（默认开）。关闭后停止注入，已注入页面里的脚本会因为桥返回 false 而静默。 */
    public static void setEnabled(boolean on) {
        h5Enabled = on;
        if (!on) {
            synchronized (INJECTED_PAGES) {
                INJECTED_PAGES.clear();
            }
        }
    }

    public static boolean enabled() {
        return h5Enabled && LoganHttp.get().isEnabled();
    }

    /** 装 JS 桥。必须在 loadUrl 之前；重复调用无害（同名覆盖）。 */
    public static void attach(WebView view) {
        try {
            if (view == null || !enabled()) {
                return;
            }
            view.setTag(TAG_KEY, null);
            view.addJavascriptInterface(new Bridge(), BRIDGE_NAME);
        } catch (Throwable t) {
            Log.d(TAG, "h5 bridge skipped: " + t);
        }
    }

    /** 每次页面开始/结束时调用；同一 URL 只注一次，避免 SPA 路由里反复执行。 */
    public static void inject(final WebView view) {
        try {
            if (view == null || !enabled()) {
                return;
            }
            final String js = script();
            if (js == null || js.length() == 0) {
                return;
            }
            String url = safeGetUrl(view);
            if (url != null && LoganHttp.get().isSelfUrlPublic(url)) {
                return;
            }
            String key = url == null ? "" : url;
            synchronized (INJECTED_PAGES) {
                if (Boolean.TRUE.equals(INJECTED_PAGES.get(key))) {
                    return;
                }
                if (INJECTED_PAGES.size() > 200) {
                    INJECTED_PAGES.clear();
                }
                INJECTED_PAGES.put(key, Boolean.TRUE);
            }
            view.post(new Runnable() {
                @Override public void run() {
                    try {
                        view.evaluateJavascript(js, null);
                    } catch (Throwable ignore) {
                    }
                }
            });
        } catch (Throwable t) {
            Log.d(TAG, "h5 inject skipped: " + t);
        }
    }

    /** WebView 主文档/子资源加载失败（原生侧看到的），直接补一条 native 事件。 */
    public static void reportResourceError(String failingUrl, int errorCode, String description) {
        try {
            if (failingUrl == null || failingUrl.length() == 0 || !enabled()) {
                return;
            }
            LoganHttpEvent e = new LoganHttpEvent();
            e.kind = "native";
            e.method = "GET";
            e.url = failingUrl;
            e.status = 0;
            e.errKind = "connect";
            e.errMsg = "webview error " + errorCode + " " + description;
            e.extra = "source=webview;code=" + errorCode;
            LoganHttp.get().report(e);
        } catch (Throwable ignore) {
        }
    }

    /** JS 侧调进来：{events:[{...},{...}]}，交给统一队列。 */
    private static final class Bridge {

        @JavascriptInterface
        public boolean postJson(String payload) {
            try {
                if (!enabled() || payload == null || payload.length() == 0) {
                    return false;
                }
                JSONObject root = new JSONObject(payload);
                JSONArray arr = root.optJSONArray("events");
                if (arr == null || arr.length() == 0) {
                    return false;
                }
                // 行为轨迹是独立语义（kind 里有 other，会和错误通道抢），先按 type 整批分流，
                // 再让其余事件走既有的错误/网络两条通道。
                if ("behavior".equals(root.optString("type"))) {
                    int bn = Math.min(arr.length(), MAX_EVENTS_PER_POST);
                    for (int i = 0; i < bn; i++) {
                        JSONObject bo = arr.optJSONObject(i);
                        if (bo == null) {
                            continue;
                        }
                        com.ccic.logan.behavior.LoganBehavior.reportFromH5(
                                emptyToNull(bo.optString("kind")),
                                emptyToNull(bo.optString("name")),
                                emptyToNull(bo.optString("label")),
                                emptyToNull(bo.optString("pageUrl")),
                                emptyToNull(bo.optString("traceId")),
                                emptyToNull(bo.optString("extra")),
                                bo.optLong("occurredAt", 0L));
                    }
                    return true;
                }
                List<LoganHttpEvent> events = new ArrayList<LoganHttpEvent>();
                // 已消费计数：错误类事件交给 LoganError 后不进 http 列表，若只数 http 列表会把
                // 「全是 JS 异常」的批次误判成失败，JS 侧于是降级直传平台并撞业务 CORS。
                int consumed = 0;
                int n = Math.min(arr.length(), MAX_EVENTS_PER_POST);
                for (int i = 0; i < n; i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) {
                        continue;
                    }
                    String jsKind = o.optString("kind");
                    if (isErrorKind(jsKind)) {
                        // window.onerror / unhandledrejection / 显式错误：走错误通道，不进 http 表
                        String kind = "error".equals(jsKind) ? "caught" : jsKind;
                        String name = nonEmpty(o.optString("name"), "H5Error");
                        LoganError.reportErrorFromH5(kind, name,
                                emptyToNull(o.optString("message")),
                                emptyToNull(o.optString("stack")),
                                emptyToNull(o.optString("pageUrl")),
                                emptyToNull(o.optString("traceId")),
                                emptyToNull(o.optString("extra")));
                        consumed++;
                        continue;
                    }
                    LoganHttpEvent e = new LoganHttpEvent();
                    e.kind = "h5";
                    e.method = nonEmpty(o.optString("method"), "GET");
                    e.url = o.optString("url");
                    if (e.url == null || e.url.length() == 0) {
                        continue;
                    }
                    e.status = o.optInt("status", 0);
                    e.errKind = emptyToNull(o.optString("errKind"));
                    e.errMsg = emptyToNull(o.optString("errMsg"));
                    e.durationMs = o.optLong("durationMs", 0L);
                    if (o.has("connectMs")) e.connectMs = Long.valueOf(o.optLong("connectMs", 0L));
                    if (o.has("ttfbMs")) e.ttfbMs = Long.valueOf(o.optLong("ttfbMs", 0L));
                    if (o.has("respBytes")) e.respBytes = Long.valueOf(o.optLong("respBytes", 0L));
                    if (o.has("slow")) e.slow = Boolean.valueOf(o.optBoolean("slow", false));
                    e.pageUrl = emptyToNull(o.optString("pageUrl"));
                    e.traceId = emptyToNull(o.optString("traceId"));
                    e.occurredAt = o.optLong("occurredAt", 0L);
                    e.extra = emptyToNull(o.optString("extra"));
                    events.add(e);
                }
                if (events.isEmpty() && consumed == 0) {
                    return false;
                }
                if (!events.isEmpty()) {
                    LoganHttp.get().report(events);
                }
                return true;
            } catch (Throwable t) {
                Log.d(TAG, "h5 payload rejected: " + t);
                return false;
            }
        }

        /** 让注入脚本能拿到平台地址与开关，用于桥不可用时的降级直传。 */
        @JavascriptInterface
        public String config() {
            try {
                if (!enabled()) {
                    return "";
                }
                return LoganHttp.get().h5ConfigJson();
            } catch (Throwable t) {
                return "";
            }
        }

        private String nonEmpty(String v, String fallback) {
            if (v == null) return fallback;
            String s = v.trim();
            if (s.length() == 0 || "null".equals(s)) return fallback;
            return s;
        }

        private String emptyToNull(String v) {
            if (v == null) return null;
            String s = v.trim();
            if (s.length() == 0 || "null".equals(s)) return null;
            return s.length() > 1000 ? s.substring(0, 1000) : s;
        }
    }

    /** 注入脚本里非网络语义的 kind（与平台 ErrorNorm 白名单一致）。 */
    private static boolean isErrorKind(String k) {
        if (k == null || k.length() == 0 || "h5".equals(k) || "native".equals(k)) {
            return false;
        }
        return "error".equals(k) || "caught".equals(k) || "business".equals(k)
                || "promise".equals(k) || "ui".equals(k) || "resource".equals(k) || "other".equals(k);
    }

    private static String safeGetUrl(WebView view) {
        try {
            return view.getUrl();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 读 assets 里的注入脚本，进程内缓存一次；读不到就整条 H5 通道静默降级。 */
    /** 供内核无关的 LoganH5Watch 取注入脚本（同一份 assets 缓存）。 */
    public static String scriptForWatch() {
        return script();
    }

    private static String script() {
        if (cachedJs != null) {
            return cachedJs;
        }
        if (assetMissing) {
            long now = System.currentTimeMillis();
            if (now - lastAssetTry < 60000L) {
                return null;
            }
        }
        lastAssetTry = System.currentTimeMillis();
        Context c = appContext;
        if (c == null) {
            return null;
        }
        try {
            AssetManager am = c.getAssets();
            InputStream in = am.open(ASSET_JS);
            try {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) {
                    bo.write(buf, 0, n);
                }
                cachedJs = new String(bo.toByteArray(), "UTF-8");
                assetMissing = false;
                return cachedJs;
            } finally {
                try { in.close(); } catch (Throwable ignore) {}
            }
        } catch (Throwable t) {
            assetMissing = true;
            Log.d(TAG, "logan-h5.js not found in assets: " + t);
            return null;
        }
    }
}