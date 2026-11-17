package com.meituan.logan.web.util;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 网络观测归一化：把「同一个接口」的不同 URL 塌成一个指纹，并对 query 脱敏。
 * 规则全部集中在服务端，客户端只管上报原始事实 —— 这是「通用能力、不绑定某个项目」的关键。
 */
public final class HttpNorm {

    /** 常见敏感参数名：命中后只保留键名，值换成 ***(长度) */
    private static final Set<String> SECRET_KEYS = new HashSet<String>(Arrays.asList(
            "token", "access_token", "accesstoken", "authtoken", "authorization",
            "password", "passwd", "pwd", "secret", "sessionid", "sid", "cookie", "sign", "apisign",
            "idcard", "idno", "idnumber", "cardno", "bankcard", "bankcardno", "mobile", "phone", "tel",
            "username", "userid", "unionid", "openid", "imei", "oaid", "androidid"));

    /** 有诊断价值的分页/枚举参数：保留明文 */
    private static final Set<String> KEEP_KEYS = new HashSet<String>(Arrays.asList(
            "page", "pageno", "pagenum", "pagesize", "size", "limit", "offset", "type", "status",
            "version", "channel", "platform", "sort", "order", "keyword", "q", "code", "scene", "biztype"));

    private HttpNorm() {
    }

    /** host（小写，去端口、去 userinfo） */
    public static String host(String url) {
        String h = strip(url);
        if (StringUtils.isBlank(h)) {
            return null;
        }
        int i = h.indexOf("://");
        if (i >= 0) {
            h = h.substring(i + 3);
        }
        int cut = h.length();
        char[] stops = {'/', '?', '#'};
        for (int k = 0; k < stops.length; k++) {
            int p = h.indexOf(stops[k]);
            if (p >= 0 && p < cut) {
                cut = p;
            }
        }
        h = h.substring(0, cut);
        int at = h.lastIndexOf('@');
        if (at >= 0) {
            h = h.substring(at + 1);
        }
        int colon = h.indexOf(':');
        if (colon > 0) {
            h = h.substring(0, colon);
        }
        h = h.toLowerCase(Locale.ROOT);
        return StringUtils.isBlank(h) ? null : abbrev(h, 255);
    }

    /** 路径（不含 query），并折叠易变段 */
    public static String path(String url) {
        String h = strip(url);
        if (StringUtils.isBlank(h)) {
            return "/";
        }
        int scheme = h.indexOf("://");
        if (scheme >= 0) {
            int slash = h.indexOf('/', scheme + 3);
            h = slash < 0 ? "/" : h.substring(slash);
        }
        char[] stops = {'?', '#'};
        for (int k = 0; k < stops.length; k++) {
            int p = h.indexOf(stops[k]);
            if (p >= 0) {
                h = h.substring(0, p);
            }
        }
        if (StringUtils.isBlank(h)) {
            return "/";
        }
        if (!h.startsWith("/")) {
            h = "/" + h;
        }
        String[] segs = h.split("/", -1);
        StringBuilder sb = new StringBuilder(h.length() + 8);
        for (int i = 0; i < segs.length; i++) {
            sb.append('/').append(collapseSeg(segs[i]));
        }
        String outStr = sb.toString();
        while (outStr.contains(":n/:n")) {
            outStr = outStr.replace(":n/:n", ":n");
        }
        return abbrev(outStr, 512);
    }

    private static String collapseSeg(String seg) {
        if (StringUtils.isBlank(seg)) {
            return seg;
        }
        if (isAllDigits(seg)) {
            return ":n";
        }
        if (seg.length() == 36 && seg.charAt(8) == '-' && seg.charAt(13) == '-'
                && seg.charAt(18) == '-' && seg.charAt(23) == '-' && isHexDash(seg)) {
            return ":uuid";
        }
        if (seg.length() >= 16 && isAllHex(seg)) {
            return ":hex";
        }
        if (seg.matches("\\d{4}-\\d{2}-\\d{2}") || seg.matches("\\d{8}") || seg.matches("\\d{13}")) {
            return ":t";
        }
        return seg;
    }

    /** query 脱敏后的 URL：客户端可以上报完整 URL，平台落库前统一洗一遍 */
    public static String scrubUrl(String url) {
        String h = strip(url);
        if (StringUtils.isBlank(h)) {
            return null;
        }
        int q = h.indexOf('?');
        if (q < 0) {
            return abbrev(h, 2000);
        }
        String base = h.substring(0, q);
        String query = h.substring(q + 1);
        String tail = "";
        int frag = query.indexOf('#');
        if (frag >= 0) {
            tail = query.substring(frag);
            query = query.substring(0, frag);
        }
        String[] pairs = query.split("&");
        StringBuilder sb = new StringBuilder(base).append('?');
        boolean first = true;
        for (int i = 0; i < pairs.length; i++) {
            String pair = pairs[i];
            if (StringUtils.isBlank(pair)) {
                continue;
            }
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            String value = eq < 0 ? "" : pair.substring(eq + 1);
            if (!first) {
                sb.append('&');
            }
            first = false;
            sb.append(key);
            String keyLower = key.toLowerCase(Locale.ROOT);
            if (SECRET_KEYS.contains(keyLower)) {
                sb.append("=***(").append(value.length()).append(")");
            } else if (KEEP_KEYS.contains(keyLower) || value.length() <= 24) {
                sb.append('=').append(abbrev(value, 64));
            } else {
                sb.append("=***(").append(value.length()).append(")");
            }
        }
        if (tail.length() > 0) {
            sb.append(abbrev(tail, 64));
        }
        return abbrev(sb.toString(), 2000);
    }

    /**
     * 聚合指纹：kind + 方法 + host + 归一化 path + 失败类别（HTTP 状态或传输错误类型）。
     * 不含设备、时间、query —— 保证同接口同类问题只聚成一条。
     */
    public static String fingerprint(String kind, String method, String host, String normPath,
                                     int status, String errKind) {
        String bucket;
        if (StringUtils.isNotBlank(errKind)) {
            bucket = "err:" + errKind.trim().toLowerCase(Locale.ROOT);
        } else if (status >= 400) {
            bucket = "http:" + status;
        } else {
            bucket = "ok";
        }
        String raw = def(kind, "native") + "|"
                + def(method, "GET").toUpperCase(Locale.ROOT) + "|"
                + def(host, "-") + "|"
                + def(normPath, "/") + "|"
                + bucket;
        return "http:" + DigestUtils.sha1Hex(raw.getBytes(StandardCharsets.UTF_8));
    }

    /** 明细幂等键：客户端重试、断网重传都不会重复入库 */
    public static String dedupHash(String appId, String deviceId, String traceId, long occurredAt,
                                   String url, String method, int status) {
        String raw = def(appId, "") + "|" + def(deviceId, "") + "|" + def(traceId, "") + "|"
                + occurredAt + "|" + def(url, "") + "|" + def(method, "") + "|" + status;
        return DigestUtils.sha1Hex(raw.getBytes(StandardCharsets.UTF_8));
    }

    /** 展示标题：METHOD host/path -> 状态/错误 */
    public static String title(String method, String host, String normPath, int status, String errKind) {
        String left = def(method, "GET").toUpperCase(Locale.ROOT) + " "
                + def(host, "-") + def(normPath, "/");
        String right;
        if (StringUtils.isNotBlank(errKind)) {
            right = " · " + errKind.trim();
        } else if (status > 0) {
            right = " · HTTP " + status;
        } else {
            right = " · 无响应";
        }
        return abbrev(left + right, 512);
    }

    /** 传输层错误归类：SDK 只给原始异常名/文案，这里统一成六个桶 */
    public static String errKindOf(String rawKind, String rawMsg, Integer statusCode) {
        if (statusCode != null && statusCode.intValue() >= 400) {
            return null;
        }
        String s = (def(rawKind, "") + " " + def(rawMsg, "")).toLowerCase(Locale.ROOT);
        if (StringUtils.isBlank(s)) {
            return null;
        }
        if (s.contains("cancel") || s.contains("abort")) {
            return "cancel";
        }
        if (s.contains("timeout") || s.contains("timed out") || s.contains("etimedout") || s.contains("超时")) {
            return "timeout";
        }
        if (s.contains("ssl") || s.contains("cert") || s.contains("handshake") || s.contains("ATS")) {
            return "ssl";
        }
        if (s.contains("parse") || s.contains("json") || s.contains("decode") || s.contains("malformed")
                || s.contains("syntax")) {
            return "parse";
        }
        if (s.contains("connect") || s.contains("unreachable") || s.contains("refused")
                || s.contains("unknownhost") || s.contains("name not resolved") || s.contains("nodename")
                || s.contains("network") || s.contains("offline") || s.contains("no route")
                || s.contains("dns") || s.contains("主机")) {
            return "connect";
        }
        return "other";
    }

    private static String def(String v, String fallback) {
        return StringUtils.defaultString(v, fallback);
    }

    private static String abbrev(String v, int max) {
        if (v == null) {
            return null;
        }
        return v.length() <= max ? v : v.substring(0, max);
    }

    private static String strip(String url) {
        if (url == null) {
            return null;
        }
        String t = url.trim();
        while (t.length() > 1 && (t.charAt(0) == '<' || t.charAt(0) == '"' || t.charAt(0) == '\''
                || t.charAt(0) == '[')) {
            t = t.substring(1);
        }
        while (t.length() > 1 && (t.charAt(t.length() - 1) == '>' || t.charAt(t.length() - 1) == '"'
                || t.charAt(t.length() - 1) == '\'' || t.charAt(t.length() - 1) == ']')) {
            t = t.substring(0, t.length() - 1);
        }
        return t;
    }

    private static boolean isAllDigits(String s) {
        if (s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    private static boolean isAllHex(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (!hexChar(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isHexDash(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '-' && !hexChar(c)) {
                return false;
            }
        }
        return true;
    }

    private static boolean hexChar(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }
}
