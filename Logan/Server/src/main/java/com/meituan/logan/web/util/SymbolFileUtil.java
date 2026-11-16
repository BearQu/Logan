package com.meituan.logan.web.util;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.Logger;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

/**
 * 符号表落盘目录与校验。目录用 -Dlogan.symbol.dir=&lt;dir&gt; 指定，默认 catalina.base 下
 * 的 logan-symbols，与图标目录分开，便于单独挂载备份。
 */
public final class SymbolFileUtil {

    private static final Logger LOGGER = Logger.getLogger(SymbolFileUtil.class);

    /** 单个符号包上限 60MB（mapping.txt 通常 2-10MB，dSYM 压缩包可到几十 MB）。 */
    private static final long MAX_SYMBOL_BYTES = 60L * 1024 * 1024;

    private static final Set<String> KINDS = new HashSet<String>(Arrays.asList(
            
"mapping", 
"dsym", 
"so", 
"sourcemap"));

    private SymbolFileUtil() {
    }

    public static boolean isKind(String kind) {
        return StringUtils.isNotBlank(kind) && KINDS.contains(kind.trim().toLowerCase());
    }

    public static File symbolDir() {
        String conf = System.getProperty(
"logan.symbol.dir");
        String base = StringUtils.isNotBlank(conf) ? conf
                : new File(
"").getAbsolutePath() + File.separator + 
"logan-symbols";
        File dir = new File(base);
        if (!dir.exists() && !dir.mkdirs()) {
            LOGGER.warn(
"cannot create symbol dir " + base);
        }
        return dir;
    }

    /** 纯文件名，防目录穿越。 */
    public static String safeName(String name) {
        if (StringUtils.isBlank(name)) {
            return null;
        }
        String n = name.replaceAll(
"[^A-Za-z0-9._@-]", 
"_");
        return n.length() > 160 ? n.substring(n.length() - 160) : n;
    }

    public static class Write {
        public final String sha1;
        public final String fileName;
        public final long size;

        public Write(String sha1, String fileName, long size) {
            this.sha1 = sha1;
            this.fileName = fileName;
            this.size = size;
        }
    }

    /** base64 落盘（已存在同 hash 直接复用），失败返回 null。 */
    public static Write write(String base64, String prefix, String fileName) {
        try {
            if (StringUtils.isBlank(base64)) {
                return null;
            }
            String data = base64.replaceAll(
"[ \\t\\r\\n\\f]", 
"");
            byte[] bytes = Base64.getDecoder().decode(data);
            if (bytes.length == 0 || bytes.length > MAX_SYMBOL_BYTES) {
                LOGGER.warn(
"symbol size rejected: " + bytes.length);
                return null;
            }
            String sha1 = DigestUtils.sha1Hex(bytes);
            String safe = safeName(StringUtils.defaultIfBlank(fileName, 
"symbol.bin"));
            String out = safeName(prefix) + 
"-" + sha1.substring(0, 12) + 
"-" + safe;
            File target = new File(symbolDir(), out);
            if (!target.exists()) {
                File parent = target.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    LOGGER.warn(
"cannot create symbol subdir " + parent);
                    return null;
                }
                FileOutputStream fos = new FileOutputStream(target);
                fos.write(bytes);
                fos.close();
            }
            return new Write(sha1, out, bytes.length);
        } catch (Exception e) {
            LOGGER.warn(
"write symbol file failed: " + e.getMessage());
            return null;
        }
    }

    public static File resolve(String relativeName) {
        if (StringUtils.isBlank(relativeName) || relativeName.contains(
"..")) {
            return null;
        }
        File f = new File(symbolDir(), relativeName);
        return f.exists() ? f : null;
    }
}
