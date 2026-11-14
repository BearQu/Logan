package com.meituan.logan.web.util;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.Logger;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Base64;

/**
 * 应用图标的落盘工具。目录可用 -Dlogan.app.dir=&lt;dir&gt; 指定，默认 catalina.base 下的
 * logan-app-icons，与日志文件目录（logfile）分离，便于单独挂载备份。
 */
public class AppFileUtil {
    private static final Logger LOGGER = Logger.getLogger(AppFileUtil.class);
    private static final long MAX_ICON_BYTES = 512 * 1024L;

    public static File iconDir() {
        String conf = System.getProperty("logan.app.dir");
        String base = StringUtils.isNotBlank(conf) ? conf
                : new File("").getAbsolutePath() + File.separator + "logan-app-icons";
        File dir = new File(base);
        if (!dir.exists() && !dir.mkdirs()) {
            LOGGER.warn("cannot create app icon dir " + base);
        }
        return dir;
    }

    /** 纯文件名，防目录穿越。 */
    public static String safeName(String name) {
        if (StringUtils.isBlank(name)) {
            return null;
        }
        String n = name.replaceAll("[^A-Za-z0-9._@-]", "_");
        return n.length() > 96 ? n.substring(0, 96) : n;
    }

    /** base64 图标（可带 data:image/png;base64, 前缀）落盘，返回 {sha1, 相对文件名}；失败返回 null。 */
    public static IconWrite writeIcon(String base64, String appId, String mime) {
        try {
            if (StringUtils.isBlank(base64)) {
                return null;
            }
            String data = base64;
            int comma = data.indexOf(',');
            if (data.startsWith("data:") && comma > 0) {
                data = data.substring(comma + 1);
            }
            data = data.replaceAll("[ \\t\\r\\n\\f]", "");
            byte[] bytes = Base64.getDecoder().decode(data);
            if (bytes.length == 0 || bytes.length > MAX_ICON_BYTES) {
                LOGGER.warn("icon size rejected: " + bytes.length);
                return null;
            }
            String sha1 = DigestUtils.sha1Hex(bytes);
            String ext = extOf(mime, data);
            String fileName = safeName(appId) + "-" + sha1.substring(0, 12) + ext;
            File target = new File(iconDir(), fileName);
            if (!target.exists()) {
                FileOutputStream fos = new FileOutputStream(target);
                fos.write(bytes);
                fos.close();
            }
            return new IconWrite(sha1, fileName);
        } catch (Exception e) {
            LOGGER.warn("write app icon failed: " + e.getMessage());
            return null;
        }
    }

    public static File resolve(String relativeName) {
        if (StringUtils.isBlank(relativeName) || relativeName.contains("..")) {
            return null;
        }
        File f = new File(iconDir(), relativeName);
        return f.exists() ? f : null;
    }

    private static String extOf(String mime, String data) {
        String m = mime == null ? "" : mime.toLowerCase();
        if (m.contains("png") || data.startsWith("iVBOR")) {
            return ".png";
        }
        if (m.contains("jpeg") || m.contains("jpg")) {
            return ".jpg";
        }
        if (m.contains("webp")) {
            return ".webp";
        }
        if (m.contains("gif") || data.startsWith("R0lGOD")) {
            return ".gif";
        }
        return ".png";
    }

    public static class IconWrite {
        public final String sha1;
        public final String fileName;

        public IconWrite(String sha1, String fileName) {
            this.sha1 = sha1;
            this.fileName = fileName;
        }
    }
}
