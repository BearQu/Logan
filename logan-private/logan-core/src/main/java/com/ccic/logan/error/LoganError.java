package com.ccic.logan.error;

import android.app.Application;
import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;

/**
 * 非致命错误采集（错误分析通道唯一入口，零第三方依赖，与崩溃/网络通道同一档稳定性要求）。
 *
 * 定位：崩溃通道抓的是"进程没了"，本通道抓的是"进程还在但出错了"——业务 try/catch 吞掉的异常、
 * 兜住的空指针、Promise/回调失败、资源加载失败。这类问题在崩溃平台上永远看不见，但它们是
 * 线上最主要的体验损伤来源，所以对标 Bugly 的"错误分析"必须单独一条通道。
 *
 * 通用性（硬约束）：不依赖任何业务类、不依赖任何第三方库，只用 Android 框架 API；
 * 地址与身份复用 LoganHttp 已配好的那一份，宿主不需要为本通道多写一行接线。
 *
 * 开关（硬约束）：setEnabled(false) 一行关闭整条通道，默认开启自动采集；
 * 关闭后 report() 直接返回，不排队不占内存。
 *
 * 稳定性红线：任何异常都在内部吞掉，绝不向业务线程抛出；队列满丢最旧，上报失败丢事件不堆积；
 * 自动钩子（低内存等）注册失败也只是少一个采集点，不影响显式入口。
 */
public final class LoganError {

    private static final int QUEUE_CAPACITY = 300;
    private static final int BATCH_SIZE = 40;
    private static final long FLUSH_WAIT_MS = 5000L;
    private static final int MSG_MAX = 4000;
    private static final int STACK_MAX = 24000;
    private static final int NAME_MAX = 256;
    /** 同类错误风暴抑制：同一 name 在这段时间内最多记一条，避免循环里的异常把队列打满。 */
    private static final long STORM_SUPPRESS_MS = 3000L;
    private static final int STORM_MEMORY_MAX = 200;
    /** 身份去重表上限（只影响极短时间窗内的重复计数判定，超出即淘汰最旧，不会漏报新异常）。 */
    private static final int SEEN_MAX = 256;

    private static volatile LoganError sInstance;

    private final ArrayBlockingQueue<JSONObject> queue = new ArrayBlockingQueue<JSONObject>(QUEUE_CAPACITY);
    private final java.util.HashMap<String, Long> recent = new java.util.HashMap<String, Long>();

    private volatile boolean enabled = true;
    private volatile boolean running;
    private volatile boolean autoHooksInstalled;
    private volatile Context appContext;
    private Thread worker;

    private LoganError() {}

    public static LoganError get() {
        if (sInstance == null) {
            synchronized (LoganError.class) {
                if (sInstance == null) {
                    sInstance = new LoganError();
                }
            }
        }
        return sInstance;
    }

    /** 一句话开关（默认开启）。关闭 = 立即停止排队与上报，未发送事件直接丢弃。 */
    public LoganError setEnabled(boolean on) {
        this.enabled = on;
        if (!on) {
            queue.clear();
            synchronized (recent) {
                recent.clear();
            }
        }
        return this;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** 注入 ApplicationContext，用于注册框架级自动采集钩子（低内存等）；不注入则只有显式入口。 */
    public LoganError attachContext(Context ctx) {
        this.appContext = ctx == null ? null : ctx.getApplicationContext();
        return this;
    }

    /**
     * 启动后台上报线程（幂等）。地址与身份复用 LoganHttp 的配置：
     * LoganHttp 未配置时本通道保持休眠，等 LoganHttp.configure/start 之后再调一次 start 即可。
     */
    public synchronized void start() {
        if (running || !configured()) {
            return;
        }
        running = true;
        installAutoHooks();
        worker = new Thread(new Runnable() {
            @Override public void run() {
                List<JSONObject> batch = new ArrayList<JSONObject>();
                while (running) {
                    JSONObject first = null;
                    try {
                        first = queue.poll(FLUSH_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
                    } catch (InterruptedException e) {
                        return;
                    } catch (Throwable t) {
                        continue;
                    }
                    if (first == null) {
                        continue;
                    }
                    batch.clear();
                    batch.add(first);
                    try {
                        queue.drainTo(batch, BATCH_SIZE - 1);
                    } catch (Throwable ignore) {
                    }
                    try {
                        postBatch(batch);
                    } catch (Throwable ignore) {
                        // 观测通道不能影响业务，失败即丢
                    }
                }
            }
        }, "logan-error-report");
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

    /** 与网络通道共用一套配置，宿主少一处接线，也避免两边地址漂移。 */
    private boolean configured() {
        try {
            com.ccic.logan.http.LoganHttp h = com.ccic.logan.http.LoganHttp.get();
            return h != null && h.isConfiguredForError();
        } catch (Throwable t) {
            return false;
        }
    }

    // ---------------------------------------------------------------- 显式入口

    /** 最常用：业务 catch 到的异常交给平台。任意线程可调，非阻塞，永不抛出。 */
    public static void reportError(Throwable t) {
        get().report("caught", null, null, t, null);
    }

    /** 业务语义更强的入口：自己定 kind 与 name（kind 建议 business/promise/resource/ui/other）。 */
    public static void reportError(String kind, String name, String message, Throwable t) {
        get().report(kind, name, message, t, null);
    }

    /** 没有异常对象、只有一个错误事实（如接口返回业务码失败、资源没加载出来）。 */
    public static void reportMessage(String kind, String name, String message) {
        get().report(kind, name, message, null, null);
    }

    /** 带上下文：pageUrl（页面/路由）、traceId（链路号）、extra（自定义附加 JSON 串）。 */
    public static void reportError(String kind, String name, String message, Throwable t,
                                   String pageUrl, String traceId, String extra) {
        get().report(kind, name, message, t, pageUrl, traceId, extra, false);
    }

    /** H5 专用：JS 侧已把 error.stack 采集成字符串随包上报，这里没有 Throwable，只有裸堆栈。 */
    public static void reportErrorFromH5(String kind, String name, String message, String stack,
                                         String pageUrl, String traceId, String extra) {
        get().report(kind, name, message, null, stack, pageUrl, traceId, extra, false);
    }

    /**
     * 崩溃通道镜像（默认关）。崩溃通道已经覆盖未捕获异常，默认不重复计入错误分析，
     * 免得同一个问题在两个页面各算一次；确实要在错误页一起看时再打开。
     */
    public LoganError setMirrorUncaught(boolean on) {
        if (on) {
            installUncaughtMirror();
        }
        return this;
    }

    /** 显式上报一条未捕获异常（供宿主自己的崩溃钩子调用；平台不劫持宿主 handler）。 */
    public static void reportUncaught(Thread thread, Throwable t) {
        get().report("uncaught", thread == null ? null : thread.getName(),
                t == null ? null : safeMessage(t), t, null, null, null, true);
    }

    /** 包一层 Runnable：主线程 post 出去的任务最容易吞异常，用它就不丢。 */
    public static Runnable wrap(final Runnable r, final String tag) {
        return new Runnable() {
            @Override public void run() {
                try {
                    if (r != null) {
                        r.run();
                    }
                } catch (Throwable t) {
                    reportMessage("ui", tag == null ? "runnable" : tag, safeMessage(t));
                    throw t instanceof RuntimeException ? (RuntimeException) t : new RuntimeException(t);
                }
            }
        };
    }

    /**
     * 编译期桥专用入口（业务代码零改动）：字节码在每个 catch handler 入口注入本方法的调用。
     * where 形如 com.foo.Bar#doThing，是「异常在哪里被吞掉」的定位信息，进 extra 供平台展示。
     * 任意线程、永不抛出；与显式 reportError 走同一套风暴抑制与身份去重，保证同一异常只计一次。
     */
    public static void reportCaught(Throwable t, String where) {
        if (t == null) {
            return;
        }
        if (seen(t)) {
            // 同一个异常对象已经上报过（嵌套 catch 层层重抛 / 宿主自己显式调过），不再重复计数
            return;
        }
        String extra = null;
        if (where != null && where.length() > 0) {
            try {
                JSONObject e = new JSONObject();
                e.put("where", abbreviate(where, 200));
                extra = e.toString();
            } catch (Throwable ignore) {
            }
        }
        get().report("caught", null, null, t, null, null, extra, false);
    }

    /** 已上报异常对象身份表（有界 LRU，弱引用不参与，只用于短时间内的重复计数判定）。 */
    private static final java.util.LinkedHashMap<Throwable, Boolean> SEEN = new java.util.LinkedHashMap<Throwable, Boolean>(64, 0.75f, false) {
        @Override protected boolean removeEldestEntry(java.util.Map.Entry<Throwable, Boolean> eldest) {
            return size() > SEEN_MAX;
        }
    };

    private static boolean seen(Throwable t) {
        try {
            synchronized (SEEN) {
                if (SEEN.containsKey(t)) {
                    return true;
                }
                SEEN.put(t, Boolean.TRUE);
                return false;
            }
        } catch (Throwable ignore) {
            return false;
        }
    }

    /** 立即把队列残留发出去（退后台前调用）。异步，不阻塞调用方。 */
    public void flush() {
        if (queue.isEmpty() || !running) {
            return;
        }
        final List<JSONObject> batch = new ArrayList<JSONObject>();
        queue.drainTo(batch, QUEUE_CAPACITY);
        if (batch.isEmpty()) {
            return;
        }
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    postBatch(batch);
                } catch (Throwable ignore) {
                }
            }
        }, "logan-error-flush");
        t.setDaemon(true);
        t.start();
    }

    // ---------------------------------------------------------------- 组包

    public void report(String kind, String name, String message, Throwable t, String extra) {
        report(kind, name, message, t, null, null, extra, false);
    }

    public void report(String kind, String name, String message, Throwable t, String pageUrl,
                       String traceId, String extra, boolean fatal) {
        report(kind, name, message, t, null, pageUrl, traceId, extra, fatal);
    }

    /** rawStack：调用方已持有字符串堆栈（H5 侧无 Throwable 对象）时直接采用；t 非空时优先由 t 生成堆栈。 */
    public void report(String kind, String name, String message, Throwable t, String rawStack,
                       String pageUrl, String traceId, String extra, boolean fatal) {
        try {
            if (!enabled) {
                return;
            }
            if (!running && !configured()) {
                // 平台还没配置好，直接不接，避免无意义堆积
                return;
            }
            String thName = null;
            String thMsg = null;
            String stack = null;
            if (t != null) {
                thName = t.getClass().getName();
                thMsg = safeMessage(t);
                stack = stackOf(t);
            } else if (rawStack != null && rawStack.trim().length() > 0) {
                // H5 / 业务已持有字符串堆栈（无 Throwable）时直接采用，供平台聚类与 AI 摘要
                stack = rawStack;
            }
            String finalName = pick(name, thName);
            String finalMsg = pick(message, thMsg);
            if (finalName == null && finalMsg == null && stack == null) {
                // 三要素全空：没有可分析的事实，不造空记录
                return;
            }
            if (finalName == null) {
                finalName = abbreviate(finalMsg != null ? finalMsg : "error", NAME_MAX);
            }
            String key = String.valueOf(kind) + "|" + finalName;
            if (storm(key)) {
                return;
            }
            JSONObject o = new JSONObject();
            o.put("kind", normalizeKind(kind));
            o.put("name", abbreviate(finalName, NAME_MAX));
            if (finalMsg != null) {
                o.put("message", abbreviate(finalMsg, MSG_MAX));
            }
            if (stack != null) {
                o.put("stack", abbreviate(stack, STACK_MAX));
            }
            if (pageUrl != null && pageUrl.length() > 0) {
                o.put("pageUrl", pageUrl);
            }
            if (traceId != null && traceId.length() > 0) {
                o.put("traceId", traceId);
            }
            if (extra != null && extra.length() > 0) {
                o.put("extra", extra);
            }
            if (fatal) {
                o.put("fatal", Boolean.TRUE);
            }
            o.put("occurredAt", System.currentTimeMillis());
            if (Looper.getMainLooper() == Looper.myLooper()) {
                o.put("thread", "main");
            } else {
                Thread cur = Thread.currentThread();
                if (cur != null && cur.getName() != null) {
                    o.put("thread", cur.getName());
                }
            }
            if (t != null) {
                try {
                    synchronized (SEEN) {
                        SEEN.put(t, Boolean.TRUE);
                    }
                } catch (Throwable ignore) {
                }
            }
            if (!running) {
                start();
            }
            if (!queue.offer(o)) {
                // 队列满丢最旧，保证新数据能进（观测宁缺勿堵）
                queue.poll();
                queue.offer(o);
            }
        } catch (Throwable ignore) {
        }
    }

    private static String normalizeKind(String kind) {
        String k = kind == null ? "" : kind.trim().toLowerCase();
        if (k.equals("business") || k.equals("caught") || k.equals("ui")
                || k.equals("promise") || k.equals("resource") || k.equals("other") || k.equals("uncaught")) {
            return k;
        }
        // 白名单之外统一落 other，宁缺毋假，不自造分类
        return "other";
    }

    private boolean storm(String key) {
        long now = System.currentTimeMillis();
        synchronized (recent) {
            Long last = recent.get(key);
            if (last != null && now - last.longValue() < STORM_SUPPRESS_MS) {
                return true;
            }
            recent.put(key, Long.valueOf(now));
            if (recent.size() > STORM_MEMORY_MAX) {
                Iterator<Map.Entry<String, Long>> it = recent.entrySet().iterator();
                while (it.hasNext() && recent.size() > STORM_MEMORY_MAX) {
                    Map.Entry<String, Long> en = it.next();
                    if (now - en.getValue().longValue() > STORM_SUPPRESS_MS * 4) {
                        it.remove();
                    } else {
                        break;
                    }
                }
                if (recent.size() > STORM_MEMORY_MAX) {
                    recent.clear();
                }
            }
            return false;
        }
    }

    private static String pick(String a, String b) {
        if (a != null && a.trim().length() > 0) {
            return a.trim();
        }
        if (b != null && b.trim().length() > 0) {
            return b.trim();
        }
        return null;
    }

    private static String safeMessage(Throwable t) {
        try {
            String m = t.getMessage();
            String cls = t.getClass().getName();
            int dot = cls.lastIndexOf('.');
            if (dot > 0 && dot < cls.length() - 1) {
                cls = cls.substring(dot + 1);
            }
            if (m == null || m.length() == 0) {
                return cls;
            }
            return cls + ": " + m;
        } catch (Throwable e) {
            return "Throwable";
        }
    }

    /** 打印完整因果链（含 Caused by），系统帧由平台侧统一过滤，SDK 不做归一化以免与服务端口径打架。 */
    private static String stackOf(Throwable t) {
        StringBuilder sb = new StringBuilder();
        try {
            Throwable cur = t;
            int depth = 0;
            while (cur != null && depth < 6) {
                if (depth > 0) {
                    sb.append("Caused by: ");
                }
                sb.append(cur.getClass().getName());
                String m = cur.getMessage();
                if (m != null) {
                    sb.append(": ").append(m);
                }
                sb.append('\n');
                StackTraceElement[] frames = cur.getStackTrace();
                if (frames != null) {
                    for (int i = 0; i < frames.length && i < 40; i++) {
                        sb.append("    at ").append(String.valueOf(frames[i])).append('\n');
                    }
                }
                cur = cur.getCause();
                depth++;
                if (cur == t) {
                    break;
                }
            }
        } catch (Throwable ignore) {
        }
        String s = sb.toString();
        return s.trim().length() == 0 ? null : s;
    }

    private static String abbreviate(String s, int max) {
        if (s == null) {
            return null;
        }
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + "...";
    }

    // ---------------------------------------------------------------- 框架级自动采集（默认开，全程 try 包裹）

    private void installAutoHooks() {
        if (autoHooksInstalled) {
            return;
        }
        autoHooksInstalled = true;
        try {
            final Context ctx = appContext;
            if (!(ctx instanceof Application)) {
                return;
            }
            // 低内存是典型的"进程还在但已经出问题"：崩溃平台看不见，但它直接对应卡顿与被杀。
            ((Application) ctx).registerComponentCallbacks(new ComponentCallbacks2() {
                @Override public void onTrimMemory(int level) {
                    try {
                        if (level < ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
                            // 只有影响到用户可感知的前台档位才报，避免后台常态刷屏
                            return;
                        }
                        reportMessage("resource", "onTrimMemory/" + trimLabel(level),
                                "系统回收内存，level=" + level);
                    } catch (Throwable ignore) {
                    }
                }
                @Override public void onConfigurationChanged(Configuration newConfig) {}
                @Override public void onLowMemory() {
                    try {
                        reportMessage("resource", "onLowMemory", "系统低内存告警");
                    } catch (Throwable ignore) {
                    }
                }
            });
        } catch (Throwable ignore) {
        }
    }

    private static String trimLabel(int level) {
        switch (level) {
            case ComponentCallbacks2.TRIM_MEMORY_COMPLETE: return "complete";
            case ComponentCallbacks2.TRIM_MEMORY_MODERATE: return "moderate";
            case ComponentCallbacks2.TRIM_MEMORY_BACKGROUND: return "background";
            case ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN: return "ui-hidden";
            case ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL: return "running-critical";
            case ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW: return "running-low";
            case ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE: return "running-moderate";
            default: return "level-" + level;
        }
    }

    private volatile Thread.UncaughtExceptionHandler chained;

    private void installUncaughtMirror() {
        try {
            if (chained != null) {
                return;
            }
            chained = Thread.getDefaultUncaughtExceptionHandler();
            Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
                @Override public void uncaughtException(Thread t, Throwable e) {
                    try {
                        if (enabled) {
                            reportUncaught(t, e);
                            // 镜像通道也要把队列里的东西送出去，进程马上要没了
                            flushBlockingNow();
                        }
                    } catch (Throwable ignore) {
                    } finally {
                        Thread.UncaughtExceptionHandler next = chained;
                        if (next != null) {
                            // 链式转发，绝不吞掉宿主/其它 SDK 的崩溃处理
                            next.uncaughtException(t, e);
                        }
                    }
                }
            });
        } catch (Throwable ignore) {
        }
    }

    /** 崩溃前的最后一搏：同步发一次，最多等 1.2 秒，发不出去就放弃。 */
    private void flushBlockingNow() {
        final List<JSONObject> batch = new ArrayList<JSONObject>();
        try {
            queue.drainTo(batch, QUEUE_CAPACITY);
        } catch (Throwable ignore) {
        }
        if (batch.isEmpty()) {
            return;
        }
        final Object lock = new Object();
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    postBatch(batch);
                } catch (Throwable ignore) {
                } finally {
                    synchronized (lock) {
                        lock.notifyAll();
                    }
                }
            }
        }, "logan-error-final");
        t.setDaemon(true);
        t.start();
        try {
            synchronized (lock) {
                lock.wait(1200L);
            }
        } catch (Throwable ignore) {
        }
    }

    // ---------------------------------------------------------------- 上报

    private void postBatch(List<JSONObject> batch) throws Exception {
        String baseUrl = com.ccic.logan.http.LoganHttp.get().errorBaseUrl();
        if (baseUrl == null || baseUrl.length() == 0) {
            return;
        }
        JSONObject body = new JSONObject();
        body.put("appId", nz(com.ccic.logan.http.LoganHttp.get().errorAppId()));
        body.put("deviceId", nz(com.ccic.logan.http.LoganHttp.get().errorDeviceId()));
        body.put("platform", 1);
        body.put("appVersion", nz(com.ccic.logan.http.LoganHttp.get().errorAppVersion()));
        body.put("buildVersion", nz(com.ccic.logan.http.LoganHttp.get().errorBuildVersion()));
        body.put("os", "android");
        body.put("osVersion", Build.VERSION.RELEASE == null ? "" : Build.VERSION.RELEASE);
        body.put("brand", Build.BRAND == null ? "" : Build.BRAND);
        body.put("model", Build.MODEL == null ? "" : Build.MODEL);
        JSONArray arr = new JSONArray();
        for (int i = 0; i < batch.size(); i++) {
            arr.put(batch.get(i));
        }
        body.put("events", arr);

        byte[] gz = gzip(body.toString().getBytes("UTF-8"));
        java.net.HttpURLConnection conn = null;
        try {
            conn = (java.net.HttpURLConnection) new java.net.URL(baseUrl + "/logan/error/report.json").openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(10000);
            conn.setDoOutput(true);
            conn.setUseCaches(false);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Content-Encoding", "gzip");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("appId", nz(com.ccic.logan.http.LoganHttp.get().errorAppId()));
            conn.setRequestProperty("deviceId", nz(com.ccic.logan.http.LoganHttp.get().errorDeviceId()));
            conn.setRequestProperty("platform", "1");
            Map<String, String> hs = com.ccic.logan.http.LoganHttp.get().errorHeaders();
            if (hs != null) {
                for (Map.Entry<String, String> en : hs.entrySet()) {
                    if (en.getKey() != null && en.getValue() != null) {
                        conn.setRequestProperty(en.getKey(), en.getValue());
                    }
                }
            }
            java.io.DataOutputStream out = new java.io.DataOutputStream(conn.getOutputStream());
            try {
                out.write(gz);
                out.flush();
            } finally {
                try {
                    out.close();
                } catch (Throwable ignore) {
                }
            }
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IllegalStateException("error report http " + code);
            }
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Throwable ignore) {
                }
            }
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static byte[] gzip(byte[] raw) throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        java.util.zip.GZIPOutputStream gz = new java.util.zip.GZIPOutputStream(bos);
        try {
            gz.write(raw);
        } finally {
            gz.close();
        }
        return bos.toByteArray();
    }
}
