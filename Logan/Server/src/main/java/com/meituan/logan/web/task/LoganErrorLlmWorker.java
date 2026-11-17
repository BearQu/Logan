package com.meituan.logan.web.task;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.meituan.logan.web.dto.LoganErrorDTO;
import com.meituan.logan.web.dto.LoganErrorIssueDTO;
import com.meituan.logan.web.mapper.LoganErrorMapper;
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
 * 非致命错误（错误分析）的 LLM 定性摘要 worker（可选，llm.enabled=true 才跑，复用崩溃侧同一份 llm.properties）。
 * 聚类永远用服务端确定性指纹，模型只做「这个错误大概是什么、多严重」的定性；
 * 同时把同设备同时段的网络事件摘要喂进去，让模型能说出「该错误伴随 xx 接口超时」这类互证结论。
 */
@Component
public class LoganErrorLlmWorker {

    private static final Logger LOGGER = Logger.getLogger(LoganErrorLlmWorker.class);
    private static final int BATCH = 3;
    private static final long SCAN_PERIOD_MS = 60_000L;
    private static final int MAX_STACK = 6000;
    private static final int MAX_HTTP_BLOCK = 1800;
    private static final long AROUND_MS = 120_000L;

    @Resource
    private LoganErrorMapper errorMapper;

    @Resource
    private com.meituan.logan.web.mapper.LoganHttpMapper httpMapper;

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
            LOGGER.warn("llm.properties missing, error llm disabled");
        }
        if (!"true".equalsIgnoreCase(cfg.getProperty("llm.enabled", "false"))) {
            LOGGER.info("error llm worker disabled by config");
            return;
        }
        running = true;
        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                loop();
            }
        }, "logan-error-llm-worker");
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
        sleepQuiet(20_000L); // 首轮延后，等容器与 DB 就绪
        while (running) {
            try {
                List<LoganErrorIssueDTO> issues = null;
                try {
                    issues = errorMapper.queryIssuesWithoutSummary(Integer.valueOf(BATCH));
                } catch (Exception e) {
                    // logan_error_issue 还没建（未接入非致命采集）时静默等待，不刷错误日志
                    LOGGER.info("error llm scan skipped: " + e.getMessage());
                    sleepQuiet(SCAN_PERIOD_MS);
                    continue;
                }
                if (issues == null || issues.isEmpty()) {
                    sleepQuiet(SCAN_PERIOD_MS);
                    continue;
                }
                for (LoganErrorIssueDTO issue : issues) {
                    summarizeOne(issue);
                    sleepQuiet(500L);
                }
            } catch (Exception e) {
                LOGGER.warn("error llm scan failed: " + e.getMessage());
                sleepQuiet(SCAN_PERIOD_MS);
            }
        }
    }

    private void summarizeOne(LoganErrorIssueDTO issue) {
        try {
            String http = httpAround(issue);
            String prompt = "你是移动端稳定性分析助手。给你一个「非致命错误」（App 未崩溃，但代码捕获到异常或资源失败）"
                    + "的错误名、消息与堆栈骨架。"
                    + (http.isEmpty() ? "" : "另外附上同设备该时刻前后的网络请求记录，请与错误互相印证（时间接近的失败或慢请求往往就是触发路径）。")
                    + "用中文输出两行：\n"
                    + "严重性: <致命|严重|一般|轻微>\n摘要: <两三句话：错在哪、可能原因、影响面、缺什么证据"
                    + (http.isEmpty() ? "" : "、与网络请求的印证关系") + ">\n\n"
                    + "kind=" + issue.getKind() + "\nname=" + nvl(issue.getName())
                    + "\ntitle=" + nvl(issue.getTitle())
                    + "\nfingerprint=" + issue.getFingerprint()
                    + "\n发生次数=" + issue.getEventCount() + " 影响设备数=" + deviceCount(issue.getAffectedDevices())
                    + "\n消息:\n" + truncate(issue.getSampleMessage(), 1500)
                    + "\n堆栈骨架:\n" + truncate(issue.getSampleStack(), MAX_STACK)
                    + (http.isEmpty() ? "" : "\n\n同设备前后网络请求:\n" + http);
            String answer = chat(prompt);
            if (StringUtils.isBlank(answer)) {
                return;
            }
            String severity = "";
            String summary = answer.trim();
            for (String line : answer.split("\\r?\\n")) {
                if (line.startsWith("严重性")) {
                    severity = line.replaceAll("^严重性[:：]\\s*", "").trim();
                } else if (line.startsWith("摘要")) {
                    summary = line.replaceAll("^摘要[:：]\\s*", "").trim();
                }
            }
            errorMapper.updateIssueLlm(Long.valueOf(issue.getId()), truncate(summary, 4000), truncate(severity, 32));
            LOGGER.info("error issue " + issue.getId() + " summarized: " + severity);
        } catch (Exception e) {
            LOGGER.warn("error issue " + issue.getId() + " summarize failed: " + e.getMessage());
        }
    }

    /** 同设备同时间窗的网络事件（互证用）；失败不影响摘要主流程。 */
    private String httpAround(LoganErrorIssueDTO issue) {
        try {
            if (httpMapper == null || StringUtils.isBlank(issue.getSampleDeviceId())
                    || issue.getLastSeen() <= 0) {
                return "";
            }
            List<com.meituan.logan.web.dto.LoganHttpDTO> rows = httpMapper.listAround(issue.getAppId(),
                    issue.getSampleDeviceId(), Long.valueOf(issue.getLastSeen() - AROUND_MS),
                    Long.valueOf(issue.getLastSeen() + 15_000L), Integer.valueOf(30));
            if (rows == null || rows.isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            for (com.meituan.logan.web.dto.LoganHttpDTO d : rows) {
                sb.append(d.getOccurredAt() - issue.getLastSeen()).append("ms ")
                        .append(d.getMethod()).append(' ').append(d.getHost()).append(d.getPath() == null ? "" : d.getPath())
                        .append(" -> ").append(d.getStatus() > 0 ? String.valueOf(d.getStatus()) : nvl(d.getErrKind()))
                        .append(" (").append(d.getDurationMs()).append("ms)");
                if (StringUtils.isNotBlank(d.getErrMsg())) {
                    sb.append(' ').append(cut(d.getErrMsg(), 120));
                }
                sb.append('\n');
                if (sb.length() > MAX_HTTP_BLOCK) {
                    break;
                }
            }
            return sb.toString();
        } catch (Exception e) {
            LOGGER.warn("attach http events failed for error issue " + issue.getId() + ": " + e.getMessage());
            return "";
        }
    }

    /** OpenAI 兼容 /v1/chat/completions 最小客户端，零新依赖（与崩溃侧同一实现口径）。 */
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

    private void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static int deviceCount(String csv) {
        if (StringUtils.isBlank(csv)) {
            return 0;
        }
        int n = 0;
        for (String s : csv.split(",")) {
            if (StringUtils.isNotBlank(s)) {
                n++;
            }
        }
        return n;
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

    private static String cut(String v, int max) {
        if (v == null) {
            return "";
        }
        String s = v.trim();
        return s.length() <= max ? s : s.substring(0, max);
    }
}
