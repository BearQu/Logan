package com.ccic.logan.crash;

import android.content.Context;

import com.dianping.logan.Logan;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import xcrash.XCrash;

/**
 * logan-private 崩溃收集总入口。
 *
 * 双通道设计（对齐 Bugly「崩溃当场尽量上报，失败下次启动补报」+ 规避 Logan 非多进程安全）：
 *  A. 崩溃当场：xCrash 回调在独立子进程执行，绝不触碰主进程 Logan 实例，
 *     用 {@link CrashDirectUploader} 裸 HTTPS POST 直传服务端崩溃端点。
 *  B. 冷启动兜底：tombstone 文件留存，下次进程启动 {@link #scanPendingTombstones}
 *     读出后以 Logan.w(payload, CRASH_TYPE) + Logan.s 走加密正规通道补传，成功即删文件。
 *
 * CRASH_TYPE=99 与服务端 LogTypeEnum.CRASH 对齐（Logan 内部占用 type=1）。
 */
public final class LoganCrash {

    public static final int CRASH_TYPE = 99;

    private static volatile boolean inited = false;
    private static CrashConfig config;

    private LoganCrash() {}

    /** 主进程 Application.onCreate 中、Logan.init 之后调用。 */
    public static synchronized void init(Context context, CrashConfig cfg) {
        if (inited) return;
        config = cfg;
        Context app = context.getApplicationContext();

        XCrash.InitParameters p = new XCrash.InitParameters()
                .setLogDir(new File(app.getFilesDir(), "logan_crash").getAbsolutePath())
                .setJavaLogCountMax(20)
                .setNativeLogCountMax(20)
                .setAnrLogCountMax(10)
                .setJavaRethrow(cfg.rethrow)
                .setNativeRethrow(cfg.rethrow)
                .setAnrRethrow(false)
                .setJavaCallback(new CrashCallback("java"))
                .setNativeCallback(new CrashCallback("native"))
                .setAnrCallback(new CrashCallback("anr"));
        if (cfg.javaHandler) p.enableJavaCrashHandler(); else p.disableJavaCrashHandler();
        if (cfg.nativeHandler) p.enableNativeCrashHandler(); else p.disableNativeCrashHandler();
        if (cfg.anrHandler) p.enableAnrCrashHandler(); else p.disableAnrCrashHandler();
        XCrash.init(app, p);

        inited = true;
        if (cfg.uploadOnStartup) scanPendingTombstones(app);
        // 独立主线程看门狗：ANR 信号(SIGQUIT)钩子可能被其它 SDK(如 Bugly)后装抢占，
        // 看门狗不依赖信号，主线程真实卡死超阈值即抓栈直传，保证 ANR 通道不被第三方 SDK 吞掉。
        if (cfg.anrWatchdog) AnrWatchdog.start(app, cfg);
        if (cfg.lagWatchdog) LagWatchdog.start(app, cfg);
    }

    /** 冷启动扫描遗留 tombstone，后台线程逐条 gzip 直传 crash 端点（通道 B）。 */
    public static void scanPendingTombstones(final Context context) {
        if (config == null) return;
        final CrashConfig cfg = config;
        new Thread(new Runnable() {
            @Override public void run() {
                try { scanPendingNow(context, cfg); } catch (Throwable ignore) {}
            }
        }, "logan-crash-scan").start();
    }

    private static void scanPendingNow(Context context, CrashConfig cfg) {
        File dir = new File(context.getFilesDir(), "logan_crash");
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            String name = f.getName();
            // xcrash 实际落盘名：tombstone_<ts>_<ver>__<pkg>.<kind>.xcrash；兼容自写 .log
            if (!(name.endsWith(".log") || name.endsWith(".xcrash"))) continue;
            try {
                String content = CrashFiles.read(f);
                if (content == null || content.trim().isEmpty()) { f.delete(); continue; }
                String kind = name.contains(".java.") ? "java"
                        : name.contains(".native.") ? "native"
                        : name.contains(".anr.") ? "anr" : "native";
                String payload = CrashEnvelope.wrap(cfg, kind, content, true);
                if (CrashHttp.postStringSync(cfg.crashUploadUrl(), payload, cfg.uploadHeaders())) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete(); // 服务端确认收到才删
                }
                // 失败保留文件，下次冷启动再试
            } catch (Throwable ignore) {
                // 单条失败保留文件下次重试
            }
        }
    }

    /** xcrash ANR 回调最近触发时间：看门狗在其后短暂静默，避免同一 ANR 双报。 */
    static volatile long lastSignalAnrAt = 0L;

    /** 独立主线程看门狗（零第三方依赖：Handler+Thread.getStackTrace）。 */
    static final class AnrWatchdog extends Thread {
        private static AnrWatchdog instance;
        private static volatile long lastAnrReportAt = 0L; // 供 LagWatchdog 避让同一卡顿窗口
        private final android.os.Handler main;
        private final CrashConfig cfg;
        private volatile long tick = 0L;
        private volatile long lastReportAt = 0L;
        private volatile String lastFp = null;

        static synchronized void start(Context app, CrashConfig cfg) {
            if (instance != null) return;
            instance = new AnrWatchdog(app, cfg);
            instance.setDaemon(true);
            instance.setName("logan-anr-watchdog");
            instance.start();
        }

        AnrWatchdog(Context app, CrashConfig cfg) {
            this.main = new android.os.Handler(android.os.Looper.getMainLooper());
            this.cfg = cfg;
        }

        @Override public void run() {
            final Runnable beat = new Runnable() {
                @Override public void run() { tick++; main.postDelayed(this, 200L); }
            };
            main.postDelayed(beat, 200L);
            long lastTick = 0L;
            long lastTickAt = System.currentTimeMillis();
            boolean blocked = false;
            while (true) {
                try { Thread.sleep(250L); } catch (InterruptedException e) { return; }
                long t = tick;
                long now = System.currentTimeMillis();
                if (t != lastTick) { lastTick = t; lastTickAt = now; blocked = false; continue; }
                if (now - lastTickAt < cfg.anrTimeoutMs) continue;
                if (blocked) continue; // 同一次卡顿只报一次
                blocked = true;
                if (now - lastSignalAnrAt < 15_000L) continue; // xcrash 信号通道刚报过，静默
                if (now - lastReportAt < 60_000L && lastFp != null) continue; // 同指纹风暴抑制
                try { report(now - lastTickAt); } catch (Throwable ignore) {}
            }
        }

        private void report(long blockedMs) {
            Thread mainThread = android.os.Looper.getMainLooper().getThread();
            StackTraceElement[] st = mainThread.getStackTrace();
            String sep = System.lineSeparator();
            StringBuilder sb = new StringBuilder();
            sb.append("*** *** *** *** *** *** *** *** *** *** *** *** *** *** *** ***").append(sep);
            sb.append("Tombstone maker: 'logan-anr-watchdog'").append(sep);
            sb.append("Crash type: 'anr'").append(sep);
            sb.append("App ID: '").append(cfg.appId).append("'").append(sep);
            sb.append("App version: '").append(cfg.appVersion).append("'").append(sep);
            String ts = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", java.util.Locale.US).format(new java.util.Date());
            sb.append("Start time: '").append(ts).append("'").append(sep);
            sb.append("Anr duration: '").append(blockedMs).append("ms'").append(sep);
            sb.append("main thread state: '").append(mainThread.getState()).append("'").append(sep).append(sep);
            sb.append((char) 34).append("main").append((char) 34).append(" prio=5 tid=1 ").append(mainThread.getState()).append(sep);
            for (StackTraceElement f : st) sb.append("    at ").append(f).append(sep);
            String content = sb.toString();
            String fp = CrashEnvelope.fingerprint("anr", content);
            if (fp.equals(lastFp) && System.currentTimeMillis() - lastReportAt < 300_000L) return;
            lastFp = fp; lastReportAt = System.currentTimeMillis();
            lastAnrReportAt = lastReportAt;
            String payload = CrashEnvelope.wrap(cfg, "anr", content, false);
            CrashHttp.postStringSync(cfg.crashUploadUrl(), payload, cfg.uploadHeaders());
        }

        /** 最近一次 ANR 看门狗上报时刻（LagWatchdog 避让用）。 */
        static long lastReportAtMs() { return lastAnrReportAt; }
    }

    /** 卡顿看门狗（对标 Bugly 卡顿大盘）：心跳同 AnrWatchdog；判定链里 [lagTimeoutMs, anrTimeoutMs)
     *  另开一路抓栈上报 kind='lag'。风暴抑制比 ANR 保守（任意报冷却 120s、同指纹 10min）；
     *  同一次卡顿若最终升级到 ANR（xcrash 或 AnrWatchdog 报了 anr），lag 侧靠 lastSignalAnrAt 15s
     *  避让 + ANR 上报时刻避让，不双报。 */
    static final class LagWatchdog extends Thread {
        private static LagWatchdog instance;
        private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        private volatile long tick = 0L;
        private long lastTick = -1L, lastTickAt = 0L;
        private volatile long lastReportAt = 0L;   // lag 通道自己的上报闸门（volatile：与 ANR 侧同名不同字段）
        private String lastFp = null;
        private volatile boolean blocked = false;
        private final CrashConfig cfg;

        static synchronized void start(Context app, CrashConfig cfg) {
            if (instance != null || cfg.lagTimeoutMs <= 0 || cfg.lagTimeoutMs >= cfg.anrTimeoutMs) return;
            instance = new LagWatchdog(cfg);
            instance.setDaemon(true);
            instance.setName("logan-lag-watchdog");
            instance.start();
        }

        LagWatchdog(CrashConfig cfg) { this.cfg = cfg; }

        @Override public void run() {
            final Runnable beat = new Runnable() {
                @Override public void run() {
                    tick++;
                    main.postDelayed(this, 200L);
                }
            };
            main.postDelayed(beat, 200L);
            while (true) {
                try { Thread.sleep(250L); } catch (InterruptedException e) { return; }
                long t = tick, now = System.currentTimeMillis();
                if (t != lastTick) { lastTick = t; lastTickAt = now; blocked = false; continue; } // 活着
                if (now - lastTickAt < cfg.lagTimeoutMs) continue;      // 还没到卡顿阈值
                if (blocked) continue;                                    // 本轮卡顿已报过
                blocked = true;
                if (now - lastSignalAnrAt < 15_000L) continue;           // xcrash 的 ANR 刚报，同一窗口不双报
                if (now - AnrWatchdog.lastReportAtMs() < 15_000L) continue; // AnrWatchdog 刚报同一窗口，避让
                if (now - lastReportAt < 120_000L) continue;             // 风暴抑制：任意两次上报至少隔 120s
                report(now - lastTickAt);
            }
        }

        private void report(long blockedMs) {
            try {
                // 只有确认主线程还没恢复才上报（抓栈期间心跳若恢复说明只是临界抖动，弃报）
                long before = tick;
                Thread mainThread = android.os.Looper.getMainLooper().getThread();
                StackTraceElement[] trace = mainThread.getStackTrace();
                if (tick != before) return;
                Thread.State state = mainThread.getState();
                CrashConfig cfg = this.cfg;
                if (cfg == null) return;
                String sep = System.lineSeparator();
                StringBuilder sb = new StringBuilder();
                sb.append("*** *** *** *** *** *** *** *** *** *** *** *** *** *** *** ***").append(sep);
                sb.append("Tombstone maker: 'logan-lag-watchdog'").append(sep);
                sb.append("Crash type: 'lag'").append(sep);
                sb.append("App ID: '").append(cfg.appId).append("'").append(sep);
                sb.append("App version: '").append(cfg.appVersion).append("'").append(sep);
                String ts = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", java.util.Locale.US).format(new java.util.Date());
                sb.append("Start time: '").append(ts).append("'").append(sep);
                sb.append("Lag duration: '").append(blockedMs).append("ms'").append(sep);
                sb.append("main thread state: '").append(state).append("'").append(sep).append(sep);
                sb.append((char) 34).append("main").append((char) 34).append(" prio=5 tid=1 ").append(state).append(sep);
                for (StackTraceElement f : trace) sb.append("    at ").append(f).append(sep);
                String content = sb.toString();
                String fp = CrashEnvelope.fingerprint("lag", content);
                if (fp.equals(lastFp) && System.currentTimeMillis() - lastReportAt < 600_000L) return; // 同指纹风暴抑制
                lastFp = fp; lastReportAt = System.currentTimeMillis();
                String payload = CrashEnvelope.wrap(cfg, "lag", content, false);
                CrashHttp.postStringSync(cfg.crashUploadUrl(), payload, cfg.uploadHeaders());
            } catch (Throwable ignored) {
            }
        }
    }

    /** 业务主动写崩溃维度标记（进 CRASH_TYPE 流，随崩溃包聚合，非致命）。 */
    public static void logMark(String tag, String msg) {
        try { Logan.w("[" + tag + "] " + msg, CRASH_TYPE); } catch (Throwable ignore) {}
    }

    static CrashConfig config() { return config; }

    /** 崩溃当场回调（独立子进程！只做自包含的事，见类注释通道 A）。 */
    private static final class CrashCallback implements xcrash.ICrashCallback {
        private final String kind;
        CrashCallback(String kind) { this.kind = kind; }
        @Override
        public void onCrash(String logPath, String emergency) throws Exception {
            if ("anr".equals(kind)) lastSignalAnrAt = System.currentTimeMillis();
            try {
                File f = new File(logPath);
                String content = CrashFiles.read(f);
                if (content == null || content.trim().isEmpty()) return;
                String payload = CrashEnvelope.wrap(config, kind, content, false);
                CrashHttp.postString(CrashDirectUploader.endpoint(config), payload,
                        config.uploadHeaders(), CrashDirectUploader.connectTimeoutMs, CrashDirectUploader.readTimeoutMs);
            } catch (Throwable ignore) {
                // 当场失败无所谓，通道 B 冷启动兜底
            }
        }
    }
}
