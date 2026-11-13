package com.ccic.logan.crash;

import android.os.Build;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * tombstone 封成结构化 JSON 行；服务端 CRASH handler 按字段拆分。
 * fingerprint 借鉴 Bugly 公开算法：异常类型 + 栈顶三帧业务帧（Java）/ signal + 顶3帧 so（native），
 * 确定性字符串供服务端聚类；LLM 只做摘要定性，不做聚类。
 */
final class CrashEnvelope {
    private CrashEnvelope() {}

    private static final Pattern JAVA_FRAME = Pattern.compile("^\\s*at ([\\w.$]+)\\(([^)]*)\\)");
    private static final Pattern NATIVE_FRAME = Pattern.compile("^#\\d+\\s+pc\\s+([0-9a-fA-F]+)\\s+(\\S+)");
    private static final Pattern SIGNAL = Pattern.compile("signal\\s+\\d+\\s*\\([^)]*\\)");
    private static final Pattern EXCEPTION_TYPE = Pattern.compile("^(?:java|javax|android|kotlin|com|org)\\.([\\w.$]+?(?:Exception|Error|Throwable))");

    static String wrap(CrashConfig cfg, String kind, String content, boolean deferred) {
        try {
            JSONObject o = new JSONObject();
            o.put("kind", kind);
            o.put("crashTime", System.currentTimeMillis());
            o.put("deferred", deferred);
            o.put("appId", cfg.appId);
            o.put("appVersion", cfg.appVersion);
            o.put("buildVersion", cfg.buildVersion);
            o.put("deviceId", cfg.deviceId);
            o.put("unionId", cfg.unionId);
            o.put("userId", cfg.userId);
            o.put("brand", Build.BRAND);
            o.put("model", Build.MODEL);
            o.put("manufacturer", Build.MANUFACTURER);
            o.put("sdkInt", Build.VERSION.SDK_INT);
            o.put("release", Build.VERSION.RELEASE);
            o.put("abi", android.os.Process.is64Bit() ? "arm64-v8a" : "armeabi-v7a");
            o.put("fingerprint", fingerprint(kind, content));
            o.put("topFrames", topFramesList(kind, content));
            o.put("signal", signalOf(content));
            o.put("content", truncate(content, cfg.maxTombstoneBytes));
            return o.toString();
        } catch (Exception e) {
            // JSON 组装失败也不能丢现场
            return "{" + (char) 34 + "kind" + (char) 34 + ":" + (char) 34 + kind + (char) 34 + "}";
        }
    }

    /** 确定性聚类键。绝不进时间戳/内存地址，否则永远聚不上。 */
    static String fingerprint(String kind, String content) {
        StringBuilder sb = new StringBuilder(kind).append("|");
        String sig = signalOf(content);
        if (sig != null) sb.append(sig).append("|");
        if ("java".equals(kind) || "anr".equals(kind)) {
            String ex = exceptionType(content);
            sb.append(ex == null ? "?" : ex).append("|");
            for (String f : businessFrames(content, 3)) sb.append(f).append("|");
        } else {
            for (String f : nativeFrames(content, 3)) sb.append(f).append("|");
        }
        return Integer.toHexString(sb.toString().hashCode()) + ":" + sb.length();
    }

    static List<String> topFramesList(String kind, String content) {
        return ("java".equals(kind) || "anr".equals(kind))
                ? businessFrames(content, 3) : nativeFrames(content, 3);
    }

    private static String signalOf(String content) {
        Matcher m = SIGNAL.matcher(content);
        return m.find() ? m.group().replaceAll("\\s+", " ") : null;
    }

    private static String exceptionType(String content) {
        for (String line : splitLines(content)) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("at ") || t.startsWith("Caused by:")) continue;
            Matcher m = EXCEPTION_TYPE.matcher(t);
            if (m.find()) return m.group();
            if (t.contains("Exception") || t.contains("Error")) return t.split(":")[0].trim();
        }
        return null;
    }

    /** 顶 N 个业务帧：跳过 JDK/Android/ART/xcrash 框架帧（对齐 Bugly 从栈顶提取业务帧）。 */
    private static List<String> businessFrames(String content, int max) {
        List<String> out = new ArrayList<>();
        Matcher m = JAVA_FRAME.matcher("");
        for (String line : splitLines(content)) {
            m.reset(line);
            if (!m.find()) continue;
            String cls = m.group(1);
            if (cls.startsWith("java.") || cls.startsWith("javax.") || cls.startsWith("android.")
                    || cls.startsWith("androidx.") || cls.startsWith("dalvik.") || cls.startsWith("libcore.")
                    || cls.startsWith("kotlin.") || cls.startsWith("kotlinx.") || cls.startsWith("com.android.")
                    || cls.startsWith("xcrash.")) continue;
            out.add(cls + "." + safeMethod(m.group(2)));
            if (out.size() >= max) break;
        }
        return out;
    }

    /** native 帧：地址随 ASLR 变，只用 so 名+符号（有则用）做键，保证可聚类。 */
    private static List<String> nativeFrames(String content, int max) {
        List<String> out = new ArrayList<>();
        Matcher m = NATIVE_FRAME.matcher("");
        for (String line : splitLines(content)) {
            m.reset(line.trim());
            if (!m.find()) continue;
            String so = m.group(2);
            String sym = so.contains("(") ? so.substring(so.indexOf('(')) : "";
            so = so.substring(so.lastIndexOf('/') + 1);
            so = so.replaceAll("\\.so.*$", ".so");
            out.add(so + sym);
            if (out.size() >= max) break;
        }
        return out;
    }

    private static String safeMethod(String args) {
        if (args == null) return "?";
        int p = args.indexOf(':');
        return p > 0 ? args.substring(0, p) : args;
    }

    private static String[] splitLines(String s) {
        return s.split("\\r?\\n");
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "\n...(truncated)";
    }
}
