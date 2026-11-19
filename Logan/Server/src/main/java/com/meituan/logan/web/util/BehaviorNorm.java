package com.meituan.logan.web.util;

import org.apache.commons.codec.digest.DigestUtils;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

// 行为轨迹的服务端归一化：类别白名单、页面名折叠、URL 去敏感参数、幂等键。
// 与 HttpNorm / ErrorNorm 同一套原则 —— 客户端零逻辑，平台负责归一，因此任何项目都能直接上报。
public final class BehaviorNorm {

    private BehaviorNorm() {
    }

    /** 白名单外的类别一律归到 other，避免上报方随意扩张污染看板。 */
    private static final Set<String> KINDS = new HashSet<String>(Arrays.asList(
            "page", "click", "custom", "foreground", "background", "launch", "exit",
            "input", "scroll", "refresh", "tab", "dialog", "other"));

    /** 页面名里会把我劈成无数个聚合项的动态片段。 */
    private static final Set<String> DROP_WORDS = new HashSet<String>(Arrays.asList(
            "android", "java", "kotlin", "uiactivity", "uiviewcontroller", "ability", "arkui", "unknown"));

    public static String kindOf(String raw) {
        String k = trimToEmpty(raw).toLowerCase();
        return KINDS.contains(k) ? k : "other";
    }

    /** 页面/控件名：剥包名、剥 \$ 匿名后缀、剥十六进制与数字尾巴（Fragment\$1、Activity 哈希后缀等）。 */
    public static String normName(String name) {
        String v = trimToEmpty(name);
        if (v.isEmpty()) {
            return "";
        }
        int colon = v.indexOf(':');
        if (colon > 0) {
            v = v.substring(0, colon);
        }
        int dollar = v.indexOf('$');
        if (dollar > 0) {
            v = v.substring(0, dollar);
        }
        v = v.replaceAll("[0-9a-fA-F]{8,}", "#");
        v = v.replaceAll("\\d+", "#");
        v = v.trim();
        if (v.length() > 200) {
            v = v.substring(0, 200);
        }
        String key = v.toLowerCase().replaceAll("[^a-z]", "");
        for (String w : DROP_WORDS) {
            if (key.equals(w)) {
                return "";
            }
        }
        return v;
    }

    /** 标签：折叠长数字/令牌/十六进制，保留中文文案，限长。 */
    public static String normLabel(String label) {
        String v = trimToEmpty(label);
        if (v.isEmpty()) {
            return "";
        }
        v = v.replaceAll("0x[0-9a-fA-F]{1,16}", "#");
        v = v.replaceAll("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}", "#");
        v = v.replaceAll("\\d{6,}", "#");
        v = v.replaceAll("\\s+", " ").trim();
        return v.length() > 500 ? v.substring(0, 500) : v;
    }

    /** 页面 URL：剥 query 里的敏感键与 fragment，只留可读骨架。 */
    public static String normPageUrl(String url) {
        String v = trimToEmpty(url);
        if (v.isEmpty()) {
            return "";
        }
        int hash = v.indexOf('#');
        if (hash >= 0) {
            v = v.substring(0, hash);
        }
        int q = v.indexOf('?');
        if (q < 0) {
            return v.length() > 1000 ? v.substring(0, 1000) : v;
        }
        String base = v.substring(0, q);
        String query = v.substring(q + 1);
        StringBuilder keep = new StringBuilder();
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            String k = (eq > 0 ? pair.substring(0, eq) : pair).toLowerCase();
            if (k.isEmpty() || k.contains("token") || k.contains("sign") || k.contains("auth")
                    || k.contains("key") || k.contains("secret") || k.contains("pwd") || k.contains("password")) {
                continue;
            }
            if (keep.length() > 0) {
                keep.append('&');
            }
            keep.append(eq > 0 ? pair.substring(0, eq) + "=v" : pair);
            if (keep.length() > 200) {
                break;
            }
        }
        String full = keep.length() == 0 ? base : base + "?" + keep;
        return full.length() > 1000 ? full.substring(0, 1000) : full;
    }

    /** 单条幂等键：客户端断网重传、轮询重复调用都不会写出多行。 */
    public static String dedupHash(String appId, String deviceId, String kind, String name,
            String label, long occurredAt, String traceId) {
        String raw = def(appId, "") + "|" + def(deviceId, "") + "|" + def(kind, "") + "|"
                + def(name, "") + "|" + def(label, "") + "|" + (occurredAt / 1000L) + "|" + def(traceId, "");
        return DigestUtils.sha1Hex(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static String def(String v, String f) {
        String s = trimToEmpty(v);
        return s.isEmpty() ? f : s;
    }

    private static String trimToEmpty(String v) {
        return v == null ? "" : v.trim();
    }
}
