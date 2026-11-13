package com.ccic.logan.crash;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class CrashFiles {
    private CrashFiles() {}

    /** 读取 tombstone，UTF-8，超大截断（服务端 content 列 mediumtext，客户端先保险）。 */
    static String read(File f) throws Exception {
        long len = f.length();
        if (len <= 0 || len > 2 * 1024 * 1024) return "";
        InputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream((int) len);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), "UTF-8");
        } finally {
            in.close();
        }
    }

    /** 今天日期串（与 Logan 文件命名 YYYY-MM-DD 对齐）。 */
    static String[] todayDates() {
        return new String[]{ new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date()) };
    }
}
