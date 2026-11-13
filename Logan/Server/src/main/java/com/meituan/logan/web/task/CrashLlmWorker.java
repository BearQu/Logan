package com.meituan.logan.web.task;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.meituan.logan.web.dto.LoganCrashIssueDTO;
import com.meituan.logan.web.mapper.LoganCrashMapper;
import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.Logger;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;

/**
 * 崩溃问题 LLM 定性摘要 worker（可选，llm.enabled=true 才跑）。
 * 只负责「这个崩溃大概是什么、多严重」的定性摘要；聚类永远用 SDK 端确定性指纹，LLM 不参与。
 * 模型走 OpenAI 兼容接口（PoC = DGX Spark qwen，tailscale 内网）。
 */
@Component
public class CrashLlmWorker {
    private static final Logger LOGGER = Logger.getLogger(CrashLlmWorker.class);
    private static final int BATCH = 3;
    private static final long SCAN_PERIOD_MS = 60_000L;
    private static final int MAX_SAMPLE = 6000; // 喂模型的 tombstone 截断，防 prompt 爆炸

    @Resource
    private LoganCrashMapper crashMapper;

    private volatile boolean running;
    private Thread worker;
    private final Properties cfg = new Properties();

    @PostConstruct
    public void init() {
        try {
            InputStream in = getClass().getClassLoader().getResourceAsStream("llm.properties");
            if (in != null) {
                cfg.load(in);
                in.close();
            }
        } catch (Exception e) {
            LOGGER.warn("llm.properties missing, crash llm disabled");
        }
        if (!"true".equalsIgnoreCase(cfg.getProperty("llm.enabled", "false"))) {
            LOGGER.info("crash llm worker disabled by config");
            return;
        }
        running = true;
        worker = new Thread(this::loop, "crash-llm-worker");
        worker.setDaemon(true);
        worker.start();
    }

    @PreDestroy
    public void shutdown() {
        running = false;
        if (worker != null) {
            worker.interrupt();
        }
    }

    private void loop() {
        while (running) {
            try {
                List<LoganCrashIssueDTO> issues = crashMapper.queryIssuesWithoutSummary(BATCH);
                if (issues == null || issues.isEmpty()) {
                    Thread.sleep(SCAN_PERIOD_MS);
                    continue;
                }
                for (LoganCrashIssueDTO issue : issues) {
                    summarizeOne(issue);
                    Thread.sleep(500);
                }
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                LOGGER.warn("crash llm scan failed: " + e.getMessage());
                try {
                    Thread.sleep(SCAN_PERIOD_MS);
                } catch (InterruptedException ie) {
                    return;
                }
            }
        }
    }

    private void summarizeOne(LoganCrashIssueDTO issue) {
        try {
            String prompt = "你是 Android/iOS/鸿蒙 崩溃分析助手。给你一个崩溃问题的指纹、顶层帧与 tombstone 样本"
                    + "（可能无 Java 栈，属于 native 崩溃）。用中文输出两行：\n"
                    + "严重性: <致命|严重|一般|轻微>\n摘要: <两三句话：崩在哪、可能原因、缺什么证据>\n\n"
                    + "kind=" + issue.getKind() + "\nsignal=" + nvl(issue.getSignalInfo())
                    + "\nfingerprint=" + issue.getFingerprint() + "\ntopFrames=" + nvl(issue.getTopFrames())
                    + "\n样本:\n" + truncate(issue.getSampleContent(), MAX_SAMPLE);
            String answer = chat(prompt);
            if (StringUtils.isBlank(answer)) {
                return;
            }
            String severity = "", summary = answer.trim();
            for (String line : answer.split("\\r?\\n")) {
                if (line.startsWith("严重性")) {
                    severity = line.replaceAll("^严重性[:：]\\s*", "").trim();
                } else if (line.startsWith("摘要")) {
                    summary = line.replaceAll("^摘要[:：]\\s*", "").trim();
                }
            }
            crashMapper.updateLlmResult(issue.getId(), truncate(summary, 4000), truncate(severity, 32));
            LOGGER.info("crash issue " + issue.getId() + " summarized: " + severity);
        } catch (Exception e) {
            LOGGER.warn("crash issue " + issue.getId() + " summarize failed: " + e.getMessage());
        }
    }

    /** OpenAI 兼容 /v1/chat/completions 最小客户端，零新依赖。 */
    private String chat(String prompt) throws Exception {
        String base = cfg.getProperty("llm.base.url", "").trim();
        if (base.isEmpty()) {
            return null;
        }
        String url = (base.endsWith("/") ? base : base + "/") + "chat/completions";
        JSONObject body = new JSONObject();
        body.put("model", cfg.getProperty("llm.model", "qwen"));
        JSONArray messages = new JSONArray();
        JSONObject user = new JSONObject();
        user.put("role", "user");
        user.put("content", prompt);
        messages.add(user);
        body.put("messages", messages);
        body.put("temperature", 0.2);
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(60000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        String key = cfg.getProperty("llm.api.key", "");
        if (StringUtils.isNotBlank(key)) {
            conn.setRequestProperty("Authorization", "Bearer " + key);
        }
        byte[] payload = body.toJSONString().getBytes(StandardCharsets.UTF_8);
        conn.setFixedLengthStreamingMode(payload.length);
        DataOutputStream out = new DataOutputStream(conn.getOutputStream());
        out.write(payload);
        out.flush();
        out.close();
        if (conn.getResponseCode() != 200) {
            LOGGER.warn("llm http " + conn.getResponseCode());
            return null;
        }
        InputStream in = conn.getInputStream();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        in.close();
        conn.disconnect();
        JSONObject resp = JSON.parseObject(new String(bos.toByteArray(), StandardCharsets.UTF_8));
        return resp.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content");
    }

    private static String nvl(String s) {
        return s == null ? "-" : s;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
