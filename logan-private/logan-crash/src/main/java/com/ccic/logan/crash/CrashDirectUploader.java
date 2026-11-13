package com.ccic.logan.crash;

import java.util.HashMap;
import java.util.Map;

/**
 * 通道 A：崩溃当场在独立子进程里的直传器。
 * 约束（教训来自 Logan issue #190）：这里绝不能碰主进程的 Logan 实例（mmap 非多进程安全），
 * 一切自包含，失败静默，让通道 B 兜底。
 */
final class CrashDirectUploader {
    static int connectTimeoutMs = 3000;   // 崩溃当场要快
    static int readTimeoutMs = 5000;

    /** Bugly 防风暴：同 fingerprint 短窗口内只直传一次（进程被反复杀时避免打爆服务端）。 */
    private static final Map<String, Long> recent = new HashMap<>();
    private static final long STORM_WINDOW_MS = 10_000;

    private final CrashConfig cfg;

    CrashDirectUploader(CrashConfig cfg) { this.cfg = cfg; }

    static String endpoint(CrashConfig cfg) { return cfg.crashUploadUrl(); }

    void upload(String payload) {
        String fp = parseFp(payload);
        long now = System.currentTimeMillis();
        synchronized (recent) {
            Long last = recent.get(fp);
            if (last != null && now - last < STORM_WINDOW_MS) return; // 风暴节流
            recent.put(fp, now);
        }
        CrashHttp.postString(endpoint(cfg), payload, cfg.uploadHeaders(), connectTimeoutMs, readTimeoutMs);
    }

    private static String parseFp(String payload) {
        int i = payload.indexOf("\"fingerprint\":\"");
        if (i < 0) return "na";
        int j = payload.indexOf('"', i + 15);
        return j < 0 ? "na" : payload.substring(i + 15, j);
    }
}
