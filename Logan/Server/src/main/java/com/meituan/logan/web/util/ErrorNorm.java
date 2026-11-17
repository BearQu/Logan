package com.meituan.logan.web.util;

import org.apache.commons.codec.digest.DigestUtils;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

// 非致命错误的服务端归一化：指纹、去重哈希、标题、错误类别。
// 设计原则与 HttpNorm 一致 —— 上报方零逻辑，平台负责聚类，因此任何项目都能直接上报。
public final class ErrorNorm {

    private ErrorNorm() {
    }

    /** 会把同一次事故劈成多个指纹的动态片段，全部折叠。 */
    private static final Set<String> DROP_WORDS = new HashSet<String>(Arrays.asList(
            "android", "java", "kotlin", "objc", "uiapplication", "uikit", "corefoundation",
            "libsystem", "libc", "libc++", "dalvik", "art", "ohos", "arkui", "unknown",
            "nativeframe", "function", "anonymous", "promise", "then", "catch", "invoke",
            "run", "call", "handle", "dispatch", "main", "start", "thread", "handler",
            "looper", "binder", "messagequeue", "activitythread", "runtime"));

    /** 归一化异常名：剥包名、剥泛型与尾部噪声。 */
    public static String normName(String name) {
        String v = trimToEmpty(name);
        if (v.isEmpty()) {
            return "Error";
        }
        int slash = v.indexOf('/');
        if (slash > 0 && slash < v.length() - 1) {
            // iOS 常见 "NSUnknownKeyException: key..." 形式，只留冒号前
            v = v.substring(0, slash);
        }
        int colon = v.indexOf(':');
        if (colon > 0) {
            v = v.substring(0, colon);
        }
        int lt = v.indexOf('<');
        if (lt > 0) {
            v = v.substring(0, lt);
        }
        v = v.trim();
        int dot = v.lastIndexOf('.');
        if (dot > 0 && dot < v.length() - 1) {
            v = v.substring(dot + 1);
        }
        return v.length() > 120 ? v.substring(0, 120) : (v.isEmpty() ? "Error" : v);
    }

    /** 归一化 message：十六进制地址、数字、UUID、长 token 全部占位，避免同错误裂成上百个指纹。 */
    public static String normMessage(String msg) {
        String v = trimToEmpty(msg);
        if (v.isEmpty()) {
            return "";
        }
        v = v.replaceAll("0x[0-9a-fA-F]{1,16}", "#");
        v = v.replaceAll("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}", "#");
        v = v.replaceAll("\\d+", "#");
        v = v.replaceAll("\\s+", " ");
        v = v.trim();
        return v.length() > 400 ? v.substring(0, 400) : v;
    }

    /** 取堆栈里前若干「有信息量」的帧：剥系统帧、剥地址与偏移，只留 类#方法 / 文件:行 的骨架。 */
    public static String topFrames(String stack, int max) {
        String v = trimToEmpty(stack);
        if (v.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        String[] parts = v.split("\\r?\\n|(?<=\\))\\s+at\\s|<+");
        int taken = 0;
        for (String raw : parts) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            line = line.replaceAll("^at\\s+", "");
            line = line.replaceAll("0x[0-9a-fA-F]{1,16}", "");
            line = line.replaceAll("\\+\\s*\\d+", "");
            line = line.replaceAll("\\(\\s*\\)", "");
            line = line.replaceAll("\\s+", " ").trim();
            if (line.isEmpty()) {
                continue;
            }
            String key = line.toLowerCase().replaceAll("[^a-z]", "");
            if (key.isEmpty()) {
                continue;
            }
            boolean sys = false;
            for (String w : DROP_WORDS) {
                if (key.startsWith(w) || key.endsWith(w)) {
                    sys = true;
                    break;
                }
            }
            if (sys && taken > 0) {
                continue;
            }
            if (line.length() > 160) {
                line = line.substring(0, 160);
            }
            if (sb.length() > 0) {
                sb.append('|');
            }
            sb.append(line.toLowerCase());
            taken++;
            if (taken >= max) {
                break;
            }
        }
        return sb.toString();
    }

    /** 指纹：平台 + 类别 + 归一化名 + 归一化 message + 堆栈骨架。同一错误跨版本/跨机型只有一条。 */
    public static String fingerprint(int platform, String kind, String name, String message, String stack) {
        String raw = platform + "|" + def(kind, "business") + "|" + normName(name) + "|"
                + normMessage(message) + "|" + topFrames(stack, 6);
        return "err:" + DigestUtils.sha1Hex(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** 单条幂等键：客户端断网重传、重复调用不会写出多行。 */
    public static String dedupHash(String appId, String deviceId, String fingerprint, long occurredAt, String traceId) {
        String raw = def(appId, "") + "|" + def(deviceId, "") + "|" + def(fingerprint, "") + "|"
                + (occurredAt / 1000L) + "|" + def(traceId, "");
        return DigestUtils.sha1Hex(raw.getBytes(StandardCharsets.UTF_8));
    }

    /** 列表标题：有名字用名字，否则用堆栈首帧。 */
    public static String title(String name, String message, String stack) {
        String n = normName(name);
        String m = normMessage(message);
        if (!m.isEmpty()) {
            return n + ": " + (m.length() > 160 ? m.substring(0, 160) : m);
        }
        String tf = topFrames(stack, 1);
        if (!tf.isEmpty()) {
            return n + " @ " + (tf.length() > 160 ? tf.substring(0, 160) : tf);
        }
        return n;
    }

    /** 错误类别兜底：上报方给了就尊重，没给按名字/堆栈猜。 */
    public static String kindOf(String rawKind, String name, String pageUrl) {
        String k = trimToEmpty(rawKind).toLowerCase();
        if ("business".equals(k) || "caught".equals(k) || "ui".equals(k)
                || "promise".equals(k) || "resource".equals(k) || "other".equals(k)) {
            return k;
        }
        if (!trimToEmpty(pageUrl).isEmpty() && trimToEmpty(name).toLowerCase().contains("type")) {
            return "promise";
        }
        return "business";
    }

    private static String def(String v, String f) {
        String s = trimToEmpty(v);
        return s.isEmpty() ? f : s;
    }

    private static String trimToEmpty(String v) {
        return v == null ? "" : v.trim();
    }
}
