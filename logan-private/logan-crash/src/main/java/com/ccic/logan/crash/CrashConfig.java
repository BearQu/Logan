package com.ccic.logan.crash;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** logan-crash 配置；字段全公开便于 Kotlin/Java 两侧构造。 */
public final class CrashConfig {
    public String baseUrl = "https://ceshixiaozhan.xyz:18443/logan-web"; // PoC 默认；生产注入
    public String appId = "";
    public String appVersion = "";
    public String buildVersion = "";
    public String deviceId = "";
    public String unionId = "";
    public String userId = "";                 // 对齐 Bugly setUserId
    public boolean javaHandler = true;
    public boolean nativeHandler = true;
    public boolean anrHandler = true;
    public boolean rethrow = true;             // 崩溃后继续走系统默认处理
    public boolean uploadOnStartup = true;
    public Map<String, String> headers = new HashMap<>();
    public int maxTombstoneBytes = 512 * 1024;  // 单包 tombstone 上限（服务端列 will be mediumtext）

    public CrashConfig() {}

    /** 崩溃当场直传端点（服务端 fork 新增；不存在时 CrashHttp 会 404 静默失败走通道 B）。 */
    public String crashUploadUrl() { return baseUrl + "/logan/crash/upload.json"; }

    public Map<String, String> uploadHeaders() {
        Map<String, String> h = new HashMap<>();
        h.put("appId", appId);
        h.put("appVersion", appVersion);
        h.put("buildVersion", buildVersion);
        h.put("deviceId", deviceId);
        h.put("unionId", unionId);
        h.put("userId", userId);
        if (headers != null) h.putAll(headers);
        return Collections.unmodifiableMap(h);
    }
}
