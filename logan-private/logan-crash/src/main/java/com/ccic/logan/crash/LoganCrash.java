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
    }

    /** 冷启动扫描遗留 tombstone，经 Logan 加密通道补传（通道 B）。 */
    public static void scanPendingTombstones(Context context) {
        if (config == null) return;
        File dir = new File(context.getFilesDir(), "logan_crash");
        File[] files = dir.listFiles();
        if (files == null) return;
        final List<File> uploaded = new ArrayList<>();
        for (File f : files) {
            String name = f.getName();
            if (!name.endsWith(".log")) continue;
            try {
                String content = CrashFiles.read(f);
                if (content == null || content.trim().isEmpty()) { uploaded.add(f); continue; }
                String kind = name.startsWith("java") ? "java"
                        : name.startsWith("native") ? "native" : "anr";
                String payload = CrashEnvelope.wrap(config, kind, content, true);
                Logan.w(payload, CRASH_TYPE);
                uploaded.add(f);
            } catch (Throwable ignore) {
                // 单条失败保留文件下次重试
            }
        }
        if (uploaded.isEmpty()) return;
        Logan.f(); // flush 到文件
        // 立即定向上传（不等待常规调度）
        try {
            String[] dates = CrashFiles.todayDates();
            Logan.s(dates, new com.dianping.logan.SendLogRunnable() {
                @Override
                public void sendLog(File logFile) {
                    CrashHttp.post(config.crashUploadUrl(), logFile, config.uploadHeaders(), null);
                }
            });
            for (File f : uploaded) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        } catch (Throwable ignore) {
            // 上传失败：tombstone 已转成 logan 文件，常规通道还会再试，这里不删
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
