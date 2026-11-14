package com.meituan.logan.web.util;

import org.apache.commons.lang3.StringUtils;

import java.nio.charset.StandardCharsets;

/**
 * HTTP header 只能承载 ISO-8859-1，客户端用 UTF-8 直接塞中文（应用名等）会显示成乱码。
 * 这里按「先按 ISO-8859-1 取回原始字节、再按 UTF-8 解码」还原，纯 ASCII 时零影响。
 */
public final class HeaderUtil {
    private HeaderUtil() {
    }

    public static String utf8(String headerValue) {
        if (StringUtils.isBlank(headerValue)) {
            return headerValue;
        }
        String v = headerValue.trim();
        // 已是纯 ASCII 直接返回，避免无谓转码
        if (isAscii(v)) {
            return v;
        }
        try {
            String fixed = new String(v.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
            // 转码后出现替换符说明本来就是 UTF-8 正确的，保留原值
            return containsReplacementChar(fixed) ? v : fixed;
        } catch (Exception e) {
            return v;
        }
    }

    private static boolean containsReplacementChar(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == 65533) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAscii(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) > 127) {
                return false;
            }
        }
        return true;
    }
}
