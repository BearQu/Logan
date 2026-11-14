package com.ccic.logan.crash;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

/** 极简 HTTPS POST。崩溃通道零依赖是硬要求：崩溃现场不容许任何第三方库再崩。 */
final class CrashHttp {
    private CrashHttp() {}

    interface Done { void onResult(int code, boolean ok); }

    /** multipart 上传一个 tombstone 文件（通道 B 手动兜底路径用）。 */
    static void post(String url, File file, Map<String, String> headers, Done done) {
        try {
            byte[] body = readFile(file);
            send(url, body, "application/octet-stream", headers, 10000, 15000, done);
        } catch (Throwable t) {
            if (done != null) done.onResult(-1, false);
        }
    }

    /** 直接 POST 一段文本（通道 A 当场直传用），gzip 压缩省流量。 */
    static void postString(String url, String text, Map<String, String> headers, int connectMs, int readMs) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            GZIPOutputStream gz = new GZIPOutputStream(bos);
            gz.write(text.getBytes("UTF-8"));
            gz.finish();
            gz.close();
            byte[] body = bos.toByteArray();
            Map<String, String> h = new java.util.HashMap<>(headers);
            h.put("Content-Encoding", "gzip");
            send(url, body, "application/json; charset=utf-8", h, connectMs, readMs, null);
        } catch (Throwable ignore) {
        }
    }

    /** 同步 POST 一段 gzip 文本，返回是否 2xx（通道 B 冷启动补传用，调用方须在后台线程）。 */
    static boolean postStringSync(String url, String text, Map<String, String> headers) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            GZIPOutputStream gz = new GZIPOutputStream(bos);
            gz.write(text.getBytes("UTF-8"));
            gz.finish();
            gz.close();
            Map<String, String> h = new java.util.HashMap<>(headers);
            h.put("Content-Encoding", "gzip");
            return sendRet(url, bos.toByteArray(), "application/json; charset=utf-8", h, 10000, 30000);
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean sendRet(String url, byte[] body, String contentType, Map<String, String> headers,
                             int connectMs, int readMs) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(connectMs);
            conn.setReadTimeout(readMs);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", contentType);
            conn.setRequestProperty("X-Logan-Crash", "1");
            if (headers != null) for (Map.Entry<String, String> e : headers.entrySet()) {
                if (e.getValue() != null) conn.setRequestProperty(e.getKey(), e.getValue());
            }
            conn.setFixedLengthStreamingMode(body.length);
            DataOutputStream out = new DataOutputStream(conn.getOutputStream());
            out.write(body);
            out.flush();
            out.close();
            int code = conn.getResponseCode();
            return code >= 200 && code < 300;
        } catch (Throwable t) {
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static void send(String url, byte[] body, String contentType, Map<String, String> headers,
                             int connectMs, int readMs, Done done) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(connectMs);
            conn.setReadTimeout(readMs);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", contentType);
            conn.setRequestProperty("X-Logan-Crash", "1");
            if (headers != null) for (Map.Entry<String, String> e : headers.entrySet()) {
                if (e.getValue() != null) conn.setRequestProperty(e.getKey(), e.getValue());
            }
            conn.setFixedLengthStreamingMode(body.length);
            DataOutputStream out = new DataOutputStream(conn.getOutputStream());
            out.write(body);
            out.flush();
            out.close();
            int code = conn.getResponseCode();
            if (done != null) done.onResult(code, code >= 200 && code < 300);
        } catch (Throwable t) {
            if (done != null) done.onResult(-1, false);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static byte[] readFile(File f) throws Exception {
        InputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream((int) f.length());
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        } finally {
            in.close();
        }
    }
}
