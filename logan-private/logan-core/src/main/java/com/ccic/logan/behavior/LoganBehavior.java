package com.ccic.logan.behavior;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;

/**
 * 崩溃前行为轨迹采集（对标 Bugly「崩溃前行为轨迹」，零第三方依赖，只用 Android 框架 API）。
 *
 * 定位：崩溃堆栈只说清「死在哪一行」，说不清「用户是怎么走到这一行的」。本通道记录页面进出、
 * 控件点击、前后台切换与业务自定义节点，崩溃平台在证据包里按时间轴与业务日志、网络事件、非致命
 * 错误一起混排，形成「崩溃前到底发生了什么」的完整现场。
 *
 * 通用性（硬约束）：不依赖任何业务类与第三方库。页面轨迹靠 ActivityLifecycleCallbacks 自动拿，
 * 点击靠编译期桥在 View#setOnClickListener 处包装监听器 —— 业务代码零改动。
 *
 * 开关（硬约束）：setEnabled(false) 一行关闭整条通道，默认开启自动采集；关闭后不入队不占内存。
 *
 * 稳定性红线：所有采集点整体 try/catch，任何异常内部吞掉，绝不向主线程与业务抛出；
 * 页面进入用 post 移出 onResume 关键路径；队列满丢最旧；上报失败丢事件不堆积。
 */
public final class LoganBehavior {

    private static final String TAG = "LoganBehaviorDbg";   // TODO 验收后删除
    private static final int QUEUE_CAPACITY = 400;
    private static final int BATCH_SIZE = 60;
    private static final long FLUSH_WAIT_MS = 5000L;
    private static final long DUP_WINDOW_MS = 400L;
    private static final int DUP_MEMORY_MAX = 128;
    private static final int NAME_MAX = 256;
    private static final int LABEL_MAX = 1000;

    private static volatile LoganBehavior sInstance;

    private final ArrayBlockingQueue<JSONObject> queue = new ArrayBlockingQueue<JSONObject>(QUEUE_CAPACITY);
    private final java.util.HashMap<String, Long> recent = new java.util.HashMap<String, Long>();

    private volatile boolean enabled = true;
    private volatile boolean running;
    private volatile boolean autoHooksInstalled;
    private volatile Application app;
    private volatile String currentPage;
    private volatile long lastActivityAt;
    private volatile int startedCount;
    private volatile boolean foreground;
    private volatile boolean launched;
    private Handler mainHandler;
    private Thread worker;

    private LoganBehavior() {}

    public static LoganBehavior get() {
        if (sInstance == null) {
            synchronized (LoganBehavior.class) {
                if (sInstance == null) {
                    sInstance = new LoganBehavior();
                }
            }
        }
        return sInstance;
    }

    /** 一句话开关（默认开启）。关闭 = 立即停止入队与上报，未发送轨迹直接丢弃。 */
    public LoganBehavior setEnabled(boolean on) {
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

    /** 注入 ApplicationContext（拿不到 Application 时只有显式入口，没有自动页面轨迹）。 */
    public LoganBehavior attachContext(Context ctx) {
        try {
            if (ctx == null) {
                return this;
            }
            Context a = ctx.getApplicationContext();
            if (a instanceof Application) {
                this.app = (Application) a;
            }
        } catch (Throwable ignore) {
        }
        return this;
    }

    /** 与网络/错误通道共用一套配置，宿主零额外接线，也避免三个通道地址漂移。 */
    private boolean configured() {
        try {
            com.ccic.logan.http.LoganHttp h = com.ccic.logan.http.LoganHttp.get();
            return h != null && h.isConfiguredForError();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 启动后台上报线程（幂等）。LoganHttp 未配置时保持休眠，配置好后调一次即起。 */
    public synchronized void start() {
        Log.d(TAG, "start() running=" + running + " configured=" + configured() + " enabled=" + enabled);
        if (running || !configured()) {
            Log.d(TAG, "start() noop");
            return;
        }
        running = true;
        if (Looper.myLooper() == Looper.getMainLooper()) {
            mainHandler = new Handler(Looper.getMainLooper());
        } else {
            mainHandler = new Handler(Looper.getMainLooper());
        }
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
        }, "logan-behavior");
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

    // ---------------------------------------------------------------- 显式入口

    /** 业务自定义节点（如「点击提交保单」「风控校验通过」），平台轨迹里以 custom 类型混排。 */
    public static void reportCustom(String name, String label) {
        get().enqueue("custom", name, label, null, null, null, 0L, System.currentTimeMillis());
    }

    public static void reportCustom(String name, String label, String extra) {
        get().enqueue("custom", name, label, null, null, extra, 0L, System.currentTimeMillis());
    }

    /** 业务想显式记一条页面轨迹（自定义路由、Fragment 切页等非 Activity 场景）。 */
    public static void reportPage(String name, String label) {
        get().enqueue("page", name, label, null, null, null, 0L, System.currentTimeMillis());
    }

    /**
     * H5 侧上报的裸字段轨迹（原生桥调进来）：H5 事件已在浏览器侧成型，这里不再取宿主上下文与
     * 当前页面，原样入队由服务端 BehaviorNorm 统一脱敏归一化。参照 LoganError.reportErrorFromH5 先例。
     */
    public static void reportFromH5(String kind, String name, String label, String pageUrl,
                                    String traceId, String extra, long occurredAt) {
        String k = (kind == null || kind.length() == 0) ? "other" : kind;
        long at = occurredAt > 0 ? occurredAt : System.currentTimeMillis();
        get().enqueue(k, name, label, pageUrl, traceId, extra, 0L, at);
    }

    /** 立即把队列残留发出去（退后台/退出前调），异步不阻塞。 */
    public void flush() {
        try {
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
                    } catch (Throwable ignore) {}
                }
            }, "logan-behavior-flush");
            t.setDaemon(true);
            t.start();
        } catch (Throwable ignore) {
        }
    }

    // ---------------------------------------------------------------- 自动采集

    private void installAutoHooks() {
        if (autoHooksInstalled) {
            return;
        }
        try {
            if (app == null) {
                Log.d(TAG, "installAutoHooks skip: app==null");
                return;
            }
            autoHooksInstalled = true;
            app.registerActivityLifecycleCallbacks(new Lifecycle());
            Log.d(TAG, "auto hooks installed");
        } catch (Throwable ignore) {
            // 少一个自动采集点，不影响显式入口
        }
    }

    private final class Lifecycle implements Application.ActivityLifecycleCallbacks {
        @Override public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
            try {
                if (enabled && !launched) {
                    launched = true;
                    enqueue("launch", simple(activity), null, null, null,
                            savedInstanceState == null ? "cold" : "restore", 0L, System.currentTimeMillis());
                }
            } catch (Throwable ignore) {}
            safePage(activity, "created");
        }

        @Override public void onActivityResumed(Activity activity) {
            safePage(activity, "resumed");
        }

        @Override public void onActivityStarted(Activity activity) {
            try {
                startedCount++;
                if (!foreground && startedCount > 0) {
                    foreground = true;
                    enqueue("foreground", simple(activity), null, null, null, null, 0L, System.currentTimeMillis());
                }
            } catch (Throwable ignore) {}
        }

        @Override public void onActivityStopped(Activity activity) {
            try {
                if (startedCount > 0) {
                    startedCount--;
                }
                if (foreground && startedCount <= 0) {
                    foreground = false;
                    enqueue("background", simple(activity), null, null, null, null, 0L, System.currentTimeMillis());
                    flush();
                }
            } catch (Throwable ignore) {}
        }

        @Override public void onActivityDestroyed(Activity activity) {}
        @Override public void onActivityPaused(Activity activity) {}
        @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}
    }

    private void safePage(final Activity activity, final String phase) {
        try {
            if (!enabled) {
                return;
            }
            if (activity == null) {
                return;
            }
            final String name = simple(activity);
            if (name == null || name.length() == 0) {
                return;
            }
            final long now = System.currentTimeMillis();
            Runnable r = new Runnable() {
                @Override public void run() {
                    try {
                        if (!enabled) {
                            return;
                        }
                        long prev = lastActivityAt;
                        lastActivityAt = now;
                        String from = currentPage;
                        currentPage = name;
                        long stay = (prev > 0 && now > prev) ? (now - prev) : 0L;
                        String extra = (from != null && !from.equals(name)) ? ("from=" + from + ";phase=" + phase) : ("phase=" + phase);
                        enqueue("page", name, null, pageUrlOf(activity), null, extra, stay, now);
                    } catch (Throwable ignore) {}
                }
            };
            if (mainHandler != null) {
                mainHandler.post(r);
            } else {
                r.run();
            }
        } catch (Throwable ignore) {
        }
    }

    private static String simple(Activity a) {
        try {
            return a == null ? "" : a.getClass().getSimpleName();
        } catch (Throwable t) {
            return "";
        }
    }

    /** 只在宿主自己挂过 WebView 时取当前 URL，取不到整个键不写（宁缺毋假）。 */
    private static String pageUrlOf(Activity a) {
        return null;
    }

    // ---------------------------------------------------------------- 点击包装

    /** 编译期桥在 View#setOnClickListener 处调本方法；任何异常都原样返回原监听器。 */
    public static View.OnClickListener wrapClick(final View.OnClickListener listener, final String owner) {
        try {
            if (listener == null) {
                return null;
            }
            if (listener instanceof Wrapped) {
                return listener;
            }
            return new Wrapped(listener, owner);
        } catch (Throwable t) {
            return listener;
        }
    }

    /** 编译期桥在 View#setOnLongClickListener 处调本方法。 */
    public static View.OnLongClickListener wrapLongClick(final View.OnLongClickListener listener, final String owner) {
        try {
            if (listener == null) {
                return null;
            }
            if (listener instanceof WrappedLong) {
                return listener;
            }
            return new WrappedLong(listener, owner);
        } catch (Throwable t) {
            return listener;
        }
    }

    private static final class Wrapped implements View.OnClickListener {
        private final View.OnClickListener real;
        private final String owner;

        Wrapped(View.OnClickListener real, String owner) {
            this.real = real;
            this.owner = owner;
        }

        @Override public void onClick(final View v) {
            try {
                final LoganBehavior self = get();
                if (self.enabled) {
                    final String desc = describe(v);
                    Runnable r = new Runnable() {
                        @Override public void run() {
                            try {
                                self.enqueue("click", owner, desc, null, null, null, 0L, System.currentTimeMillis());
                            } catch (Throwable ignore) {}
                        }
                    };
                    // 轨迹入队不占点击线程，但它本就极轻：有主线程 handler 就 post，没有就当场入队（都只是写队列）
                    if (self.mainHandler != null) {
                        self.mainHandler.post(r);
                    } else {
                        r.run();
                    }
                }
            } catch (Throwable ignore) {
            }
            try {
                real.onClick(v);
            } catch (Throwable t) {
                // 业务监听器自己抛的原样抛出，交宿主既有崩溃钩子处理，本通道不吞业务异常
                throwUnchecked(t);
            }
        }

        @Override public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o instanceof Wrapped) {
                return real.equals(((Wrapped) o).real);
            }
            return real.equals(o);
        }

        @Override public int hashCode() {
            return real.hashCode();
        }

        @Override public String toString() {
            return "LoganClick{" + owner + "}";
        }
    }

    private static final class WrappedLong implements View.OnLongClickListener {
        private final View.OnLongClickListener real;
        private final String owner;

        WrappedLong(View.OnLongClickListener real, String owner) {
            this.real = real;
            this.owner = owner;
        }

        @Override public boolean onLongClick(View v) {
            try {
                LoganBehavior self = get();
                if (self.enabled) {
                    self.enqueue("click", owner, describe(v) + "#long", null, null, null, 0L, System.currentTimeMillis());
                }
            } catch (Throwable ignore) {
            }
            try {
                return real.onLongClick(v);
            } catch (Throwable t) {
                throwUnchecked(t);
                return false;
            }
        }
    }

    private static void throwUnchecked(Throwable t) {
        if (t instanceof RuntimeException) {
            throw (RuntimeException) t;
        }
        if (t instanceof Error) {
            throw (Error) t;
        }
        throw new RuntimeException(t);
    }

    /** 点击目标的人话描述：文本 > 内容描述 > hint > 资源名 > 类名；输入框只记类名不记内容（隐私）。 */
    private static String describe(View v) {
        try {
            if (v == null) {
                return "";
            }
            if (v instanceof EditText) {
                return "input:" + v.getClass().getSimpleName();
            }
            CharSequence t = null;
            if (v instanceof TextView) {
                t = ((TextView) v).getText();
            }
            if (t != null && t.length() > 0) {
                return clip(t.toString(), LABEL_MAX);
            }
            Object cd = v.getContentDescription();
            if (cd != null && cd.toString().length() > 0) {
                return clip(cd.toString(), LABEL_MAX);
            }
            if (v instanceof TextView) {
                CharSequence h = ((TextView) v).getHint();
                if (h != null && h.length() > 0) {
                    return clip(h.toString(), LABEL_MAX);
                }
            }
            int id = v.getId();
            if (id != View.NO_ID) {
                try {
                    Context c = v.getContext();
                    if (c != null && c.getResources() != null) {
                        String rn = c.getResources().getResourceEntryName(id);
                        if (rn != null && rn.length() > 0) {
                            return "id:" + rn;
                        }
                    }
                } catch (Throwable ignore) {
                }
            }
            return v.getClass().getSimpleName();
        } catch (Throwable t) {
            return "";
        }
    }

    private static String clip(String s, int max) {
        if (s == null) {
            return "";
        }
        String r = s.replaceAll("\\s+", " ").trim();
        if (r.length() > max) {
            r = r.substring(0, max);
        }
        return r;
    }

    // ---------------------------------------------------------------- 入队

    private void enqueue(String kind, String name, String label, String pageUrl, String traceId,
                         String extra, long intervalMs, long occurredAt) {
        try {
            if (!enabled) {
                return;
            }
            // 与 LoganError 同一懒启动语义：宿主先 init 后配置凭据时，首条事件到达即把通道带起来，
            // 不因 init 那一刻未配置就永久静默丢轨迹。
            if (!running) {
                start();
                if (!running) {
                    Log.d(TAG, "drop kind=" + kind + " reason=not-running(unconfigured?)");
                    return;
                }
            }
            if ((name == null || name.length() == 0) && (label == null || label.length() == 0)
                    && (pageUrl == null || pageUrl.length() == 0)) {
                return;
            }
            String key = (kind == null ? "" : kind) + "|" + (name == null ? "" : name) + "|" + (label == null ? "" : label);
            long now = System.currentTimeMillis();
            synchronized (recent) {
                Long last = recent.get(key);
                if (last != null && now - last.longValue() < DUP_WINDOW_MS) {
                    return;
                }
                if (recent.size() > DUP_MEMORY_MAX) {
                    recent.clear();
                }
                recent.put(key, Long.valueOf(now));
            }
            JSONObject o = new JSONObject();
            o.put("kind", kind == null ? "other" : kind);
            if (name != null && name.length() > 0) {
                o.put("name", clip(name, NAME_MAX));
            }
            if (label != null && label.length() > 0) {
                o.put("label", clip(label, LABEL_MAX));
            }
            if (pageUrl != null && pageUrl.length() > 0) {
                o.put("pageUrl", clip(pageUrl, 1000));
            }
            if (traceId != null && traceId.length() > 0) {
                o.put("traceId", clip(traceId, 128));
            }
            if (extra != null && extra.length() > 0) {
                o.put("extra", clip(extra, 2000));
            }
            if (intervalMs > 0 && intervalMs <= 86400000L) {
                o.put("intervalMs", intervalMs);
            }
            o.put("occurredAt", occurredAt > 0 ? occurredAt : now);
            boolean ok = queue.offer(o);
            if (!ok) {
                queue.poll();
                ok = queue.offer(o);
            }
            Log.d(TAG, "enqueue kind=" + kind + " name=" + name + " ok=" + ok + " qsize=" + queue.size());
        } catch (Throwable ignore) {
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

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
            conn = (java.net.HttpURLConnection) new java.net.URL(baseUrl + "/logan/behavior/report.json").openConnection();
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
            Log.d(TAG, "postBatch events=" + batch.size() + " http=" + code);
            if (code < 200 || code >= 300) {
                throw new IllegalStateException("behavior report http " + code);
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
