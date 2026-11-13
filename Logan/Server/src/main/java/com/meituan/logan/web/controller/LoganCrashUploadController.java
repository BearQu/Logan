package com.meituan.logan.web.controller;

import com.meituan.logan.web.model.response.LoganResponse;
import com.meituan.logan.web.service.LoganCrashService;
import org.apache.commons.io.IOUtils;
import org.apache.log4j.Logger;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;

/**
 * 崩溃当场直传通道（SDK 通道 A）：崩溃进程独立子进程裸 HTTPS POST，gzip 压缩，零 Logan 实例依赖。
 * 通道 B（下次启动补传）走正常 /logan/upload.json，type=99 行由 LoganLogDetailServiceImpl 钩子入库。
 * 两条通道靠 content_hash 唯一键天然去重。
 */
@CrossOrigin(origins = "*", maxAge = 3600)
@Controller
@RequestMapping("/logan")
public class LoganCrashUploadController {
    private static final Logger LOGGER = Logger.getLogger(LoganCrashUploadController.class);
    private static final int MAX_BODY = 2 * 1024 * 1024;

    @Resource
    private LoganCrashService crashService;

    @PostMapping("/crash/upload.json")
    @ResponseBody
    public LoganResponse<String> upload(HttpServletRequest request) {
        try {
            byte[] raw = readBody(request, MAX_BODY);
            if (raw == null || raw.length == 0) {
                return LoganResponse.badParam("empty body");
            }
            String json = new String(maybeGunzip(request, raw), "UTF-8");
            int r = crashService.ingest(json);
            if (r < 0) {
                return LoganResponse.badParam("invalid crash envelope");
            }
            return LoganResponse.success(r == 1 ? "saved" : "duplicate");
        } catch (Exception e) {
            LOGGER.error("crash upload failed: " + e.getMessage());
            return LoganResponse.exception("crash upload failed");
        }
    }

    private static byte[] readBody(HttpServletRequest request, int max) throws Exception {
        InputStream in = request.getInputStream();
        ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.min(max, 16384));
        byte[] buf = new byte[8192];
        int n, total = 0;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > max) {
                throw new IllegalArgumentException("body too large");
            }
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    private static byte[] maybeGunzip(HttpServletRequest request, byte[] raw) throws Exception {
        String enc = request.getHeader("Content-Encoding");
        if (enc != null && enc.toLowerCase().contains("gzip")) {
            GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(raw));
            ByteArrayOutputStream bos = new ByteArrayOutputStream(raw.length * 4);
            byte[] buf = new byte[8192];
            int n;
            while ((n = gz.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            gz.close();
            return bos.toByteArray();
        }
        return raw;
    }
}
