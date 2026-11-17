package com.ccic.logan.http;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;

/**
 * HTTP 观测上报器（网络监控采集的唯一入口，零第三方依赖，与崩溃/命令通道同一档稳定性要求）。
 *
 * 通用性：本类不依赖任何网络库，OkHttp/Retrofit/HttpURLConnection/WebView 都只需要把
 * LoganHttpEvent 交给 report()；SDK 侧另有编译期桥自动装配 OkHttp 拦截器（见 logan-log-bridge 插件）。
 * 开关：setEnabled(false) 一行即可整条通道关闭（默认开启）；关闭后 report() 直接返回，不排队不占内存。
 * 稳定性红线：任何异常都在内部吞掉，绝不向业务线程抛出；上报失败丢事件，不重试堆积。
 */
public final class LoganHttp {

    private static final String TAG = "LoganHttp";
    private static final int QUEUE_CAPACITY = 500;
    private static final int BATCH_SIZE = 50;
    /** 攒够一批或等满这段时间才发，避免每个请求一次网络。 */
    private static final long FLUSH_WAIT_MS = 5000L;
    private static final long DEFAULT_SLOW_MS = 3000L;

    private static volatile LoganHttp sInstance;

    private String baseUrl = "";
    private String appId = "";
    private String deviceId = "";
    private String appVersion = "";
    private String buildVersion = "";
    private final Map<String, String> headers = new HashMap<String, String>();
    private final ArrayBlockingQueue<LoganHttpEvent> queue = new ArrayBlockingQueue<LoganHttpEvent>(QUEUE_CAPACITY);

    private volatile boolean enabled = true;
    private volatile boolean running;
    private volatile long slowThresholdMs = DEFAULT_SLOW_MS;
    private volatile Context appContext;
    private Thread worker;

    private LoganHttp() {}

    public static LoganHttp get() {
        if (sInstance == null) {
            synchronized (LoganHttp.class) {
                if (sInstance == null) {
                    sInstance = new LoganHttp();
                }
            }
        }
        return sInstance;
    }

    /** 配置上报地址与身份，必须在 start() 之前。baseUrl 末尾斜杠自动去掉。 */
    public LoganHttp configure(String baseUrl, String appId, String deviceId, String appVersion,
                              String buildVersion, Map<String, String> extraHeaders) {
        this.baseUrl = baseUrl == null ? "" : (baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl);
        this.appId = appId == null ? "" : appId;
        this.deviceId = deviceId == null ? "" : deviceId;
        this.appVersion = appVersion == null ? "" : appVersion;
        this.buildVersion = buildVersion == null ? "" : buildVersion;
        this.headers.clear();
        if (extraHeaders != null) {
            this.headers.putAll(extraHeaders);
        }
        return this;
    }

    /** 注入 ApplicationContext，只用于读网络类型；读不到就不上报该字段，不影响采集。 */
    public LoganHttp attachContext(Context ctx) {
        this.appContext = ctx == null ? null : ctx.getApplicationContext();
        return this;
    }

    /** 一句话开关（默认开启）。关闭 = 立即停止排队与上报，已有未发送事件直接丢弃。 */
    public LoganHttp setEnabled(boolean on) {
        this.enabled = on;
        if (!on) {
            queue.clear();
        }
        return this;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** 慢请求阈值（毫秒），默认 3000；小于等于 0 视为不改默认值。 */
    public LoganHttp setSlowThresholdMs(long ms) {
        if (ms > 0) {
            this.slowThresholdMs = ms;
        }
        return this;
    }

    /** 启动后台上报线程（幂等）。configure 之后调用。 */
    public synchronized void start() {
        if (running || baseUrl.length() == 0 || appId.length() == 0 || deviceId.length() == 0) {
            return;
        }
        running = true;
        worker = new Thread(new Runnable() {
            @Override public void run() {
                while (running) {
                    List<LoganHttpEvent> batch = new ArrayList<LoganHttpEvent>();
                    try {
                        LoganHttpEvent first = queue.poll(FLUSH_WAIT_MS, TimeUnit.MILLISECONDS);
                        if (first == null) {
                            continue;
                        }
                        batch.add(first);
                        queue.drainTo(batch, BATCH_SIZE - 1);
                    } catch (InterruptedException e) {
                        return;
                    } catch (Throwable t) {
                        continue;
                    }
                    try {
                        postBatch(batch);
                    } catch (Throwable ignore) {
                        // 观测通道不能影响业务，失败即丢
                    }
                }
            }
        }, "logan-http-report");
        worker.setDaemon(true);
        worker.start();
    }

    public synchronized void stop() {
        running = false;
        if (worker != null) {
            try {
                worker.interrupt();
            } catch (Throwable ignore) {}
            worker = null;
        }
        queue.clear();
    }

    /** 采集入口：任意线程可调，非阻塞，永不抛出。 */
    public void report(LoganHttpEvent event) {
        try {
            if (!enabled || event == null || !running) {
                return;
            }
            if (event.url == null || event.url.length() == 0) {
                return;
            }
            if (isSelfUrl(event.url)) {
                // 不上报平台自身的上报请求，避免自反馈
                return;
            }
            if (event.occurredAt <= 0) {
                event.occurredAt = System.currentTimeMillis();
            }
            if (event.kind == null || event.kind.length() == 0) {
                event.kind = "native";
            }
            if (event.method == null || event.method.length() == 0) {
                event.method = "GET";
            }
            if (event.slow == null) {
                event.slow = Boolean.valueOf(event.durationMs >= slowThresholdMs);
            }
            if (event.networkType == null || event.networkType.length() == 0) {
                event.networkType = networkType();
            }
            if (!queue.offer(event)) {
                // 队列满：丢最旧的一条，保证新数据能进（观测宁缺勿堵）
                queue.poll();
                queue.offer(event);
            }
        } catch (Throwable ignore) {
        }
    }

    /** 批量入口：WebView 注入的 H5 一次性交一批。 */
    public void report(List<LoganHttpEvent> events) {
        try {
            if (events == null) {
                return;
            }
            for (LoganHttpEvent e : events) {
                report(e);
            }
        } catch (Throwable ignore) {
        }
    }

    /** 立即把队列里的残留发出去（退后台/退出前调用）。异步，不阻塞调用方。 */
    public void flush() {
        if (queue.isEmpty() || !running) {
            return;
        }
        final List<LoganHttpEvent> batch = new ArrayList<LoganHttpEvent>();
        queue.drainTo(batch, QUEUE_CAPACITY);
        if (batch.isEmpty()) {
            return;
        }
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    postBatch(batch);
                } catch (Throwable ignore) {}
            }
        }, "logan-http-flush");
        t.setDaemon(true);
        t.start();
    }

    /** 供 H5 注入侧判断：平台自身地址不发（避免自反馈）。 */
    /**
     * 显式装配入口（不依赖编译期桥）：宿主自己造 okhttp3.OkHttpClient.Builder 时调一行即可。
     * 与编译期桥自动装配可共存（内部按拦截器实例去重，不会重复上报）。未装 OkHttp 返回 false。
     */
    public static boolean install(Object okHttpClientBuilder) {
        return LoganOkHttpBridge.install(okHttpClientBuilder);
    }

    /**
     * 显式上报入口：自研网络层（HttpURLConnection、自写 socket 封装、其他 HTTP 库）在自己的
     * finally 里调这一行即可，归一化/脱敏/指纹/聚合全在平台侧，客户端零逻辑。
     */
    public static void record(String url, String method, int status, String errKind, String errMsg,
                              long durationMs, Long reqBytes, Long respBytes, String source) {
        try {
            LoganHttpEvent ev = new LoganHttpEvent();
            ev.kind = "native";
            ev.url = url;
            ev.method = (method == null || method.length() == 0) ? "GET" : method.toUpperCase();
            ev.status = status;
            ev.errKind = errKind;
            ev.errMsg = errMsg;
            ev.durationMs = durationMs < 0 ? 0 : durationMs;
            ev.reqBytes = reqBytes;
            ev.respBytes = respBytes;
            ev.slow = null; // 由 SDK 按全局阈值判定
            ev.traceId = "manual-" + System.currentTimeMillis() + "-" + Math.abs(String.valueOf(url).hashCode());
            ev.extra = "source=" + (source == null ? "manual" : source);
            get().report(ev);
        } catch (Throwable ignore) {
            // 观测绝不影响业务
        }
    }

    /** 显式上报入口（异常版）：把 Throwable 交给 SDK 归类 cancel/timeout/ssl/parse/connect/other。 */
    public static void record(String url, String method, long durationMs, Throwable t, String source) {
        try {
            record(url, method, 0, LoganOkHttpBridge.classifyName(t),
                    t == null ? null : String.valueOf(t), durationMs, null, null, source);
        } catch (Throwable ignore) {
        }
    }
    /**
     * 容器侧看到的页面级失败（HTTP >=400 / 渲染进程被杀）补一条 native 事件。
     * 与 H5 脚本互斥：页面里 fetch/XHR 的失败由脚本报，这里只补原生才看得见的部分。
     */
    public static void recordNativeWebError(String url, int status, String method, String message) {
        try {
            if (url == null || url.length() == 0) {
                return;
            }
            LoganHttpEvent ev = new LoganHttpEvent();
            ev.kind = "native";
            ev.url = url;
            ev.method = (method == null || method.length() == 0) ? "GET" : method.toUpperCase();
            ev.status = status;
            ev.errKind = status >= 400 ? "other" : "connect";
            ev.errMsg = message == null ? "" : (message.length() > 500 ? message.substring(0, 500) : message);
            ev.durationMs = 0L;
            ev.extra = "source=webview";
            get().report(ev);
        } catch (Throwable ignore) {
        }
    }

    // ---------------------------------------------------------------- 错误通道复用（LoganError 走同一份配置，宿主零额外接线）

    /** 错误分析通道是否已具备上报条件（与网络通道同一份地址与身份）。 */
    public boolean isConfiguredForError() {
        return baseUrl.length() > 0 && appId.length() > 0 && deviceId.length() > 0;
    }

    public String errorBaseUrl() {
        return baseUrl;
    }

    public String errorAppId() {
        return appId;
    }

    public String errorDeviceId() {
        return deviceId;
    }

    public String errorAppVersion() {
        return appVersion;
    }

    public String errorBuildVersion() {
        return buildVersion;
    }

    /** 鉴权头等附加头：错误通道与上报通道必须同一份，否则会被平台当未授权拒收。 */
    public Map<String, String> errorHeaders() {
        return new HashMap<String, String>(headers);
    }
    public boolean isSelfUrlPublic(String url) {
        return url != null && isSelfUrl(url);
    }

    /** 给注入脚本的降级配置：平台地址 + 是否允许直传 + 慢阈值。桥可用时脚本用不到它。 */
    public String h5ConfigJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("baseUrl", baseUrl);
            o.put("appId", appId);
            o.put("deviceId", deviceId);
            o.put("appVersion", appVersion);
            o.put("buildVersion", buildVersion);
            o.put("slowThresholdMs", slowThresholdMs);
            o.put("enabled", enabled);
            return o.toString();
        } catch (Throwable t) {
            return "";
        }
    }
    private boolean isSelfUrl(String url) {
        // 只过滤"SDK 自己的上报接口"，不能按整个 baseUrl 前缀过滤：
        // 很多业务的 H5 页面就部署在与平台同一域名（或宿主把 H5 放在平台网关下），
        // 整域过滤会把 window.onerror / unhandledrejection 里 url=location.href 的事件全误杀。
        if (baseUrl.length() == 0) {
            return false;
        }
        if (!url.startsWith(baseUrl)) {
            return false;
        }
        String rest = url.substring(baseUrl.length());
        return rest.startsWith("/logan/") || rest.startsWith("/logan?");
    }

    private void postBatch(List<LoganHttpEvent> batch) throws Exception {
        JSONArray arr = new JSONArray();
        for (LoganHttpEvent e : batch) {
            JSONObject o = new JSONObject();
            o.put("kind", e.kind);
            o.put("method", e.method);
            o.put("url", e.url);
            o.put("status", e.status);
            if (e.errKind != null && e.errKind.length() > 0) o.put("errKind", e.errKind);
            if (e.errMsg != null && e.errMsg.length() > 0) o.put("errMsg", e.errMsg);
            o.put("durationMs", e.durationMs);
            if (e.connectMs != null) o.put("connectMs", e.connectMs.longValue());
            if (e.dnsMs != null) o.put("dnsMs", e.dnsMs.longValue());
            if (e.ttfbMs != null) o.put("ttfbMs", e.ttfbMs.longValue());
            if (e.reqBytes != null) o.put("reqBytes", e.reqBytes.longValue());
            if (e.respBytes != null) o.put("respBytes", e.respBytes.longValue());
            o.put("slow", e.slow != null && e.slow.booleanValue());
            if (e.pageUrl != null && e.pageUrl.length() > 0) o.put("pageUrl", e.pageUrl);
            if (e.traceId != null && e.traceId.length() > 0) o.put("traceId", e.traceId);
            o.put("occurredAt", e.occurredAt);
            if (e.networkType != null && e.networkType.length() > 0) o.put("networkType", e.networkType);
            if (e.extra != null && e.extra.length() > 0) o.put("extra", e.extra);
            arr.put(o);
        }
        JSONObject body = new JSONObject();
        body.put("appId", appId);
        body.put("deviceId", deviceId);
        body.put("platform", 1);
        body.put("appVersion", appVersion);
        body.put("buildVersion", buildVersion);
        body.put("os", "android");
        body.put("osVersion", Build.VERSION.RELEASE == null ? "" : Build.VERSION.RELEASE);
        body.put("brand", Build.BRAND == null ? "" : Build.BRAND);
        body.put("model", Build.MODEL == null ? "" : Build.MODEL);
        body.put("networkType", networkType());
        body.put("events", arr);

        byte[] gz = gzip(body.toString().getBytes("UTF-8"));
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(baseUrl + "/logan/http/report.json").openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(10000);
            conn.setDoOutput(true);
            conn.setUseCaches(false);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Content-Encoding", "gzip");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("appId", appId);
            conn.setRequestProperty("deviceId", deviceId);
            conn.setRequestProperty("platform", "1");
            conn.setRequestProperty("appVersion", appVersion);
            conn.setRequestProperty("buildVersion", buildVersion);
            for (Map.Entry<String, String> en : headers.entrySet()) {
                conn.setRequestProperty(en.getKey(), en.getValue());
            }
            DataOutputStream out = new DataOutputStream(conn.getOutputStream());
            out.write(gz);
            out.flush();
            out.close();
            int code = conn.getResponseCode();
            InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            if (is != null) {
                try { readAll(is); } catch (Throwable ignore) {}
                try { is.close(); } catch (Throwable ignore) {}
            }
            if (code < 200 || code >= 300) {
                Log.w(TAG, "http report failed code=" + code + " n=" + batch.size());
            }
        } finally {
            if (conn != null) {
                try { conn.disconnect(); } catch (Throwable ignore) {}
            }
        }
    }

    private String networkType() {
        try {
            Context c = appContext;
            if (c == null) {
                return "";
            }
            Object svc = c.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (!(svc instanceof ConnectivityManager)) {
                return "";
            }
            NetworkInfo info = ((ConnectivityManager) svc).getActiveNetworkInfo();
            if (info == null || !info.isAvailable()) {
                return "none";
            }
            switch (info.getType()) {
                case ConnectivityManager.TYPE_WIFI:
                    return "wifi";
                case ConnectivityManager.TYPE_MOBILE:
                    return "cellular";
                case ConnectivityManager.TYPE_ETHERNET:
                    return "ethernet";
                default:
                    return "other";
            }
        } catch (Throwable t) {
            return "";
        }
    }

    private static byte[] gzip(byte[] raw) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream(Math.max(256, raw.length / 4));
        GZIPOutputStream gz = new GZIPOutputStream(bo);
        gz.write(raw);
        gz.finish();
        gz.close();
        return bo.toByteArray();
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[2048];
        int n;
        while ((n = in.read(buf)) > 0) {
            bo.write(buf, 0, n);
        }
        return new String(bo.toByteArray(), "UTF-8");
    }
}