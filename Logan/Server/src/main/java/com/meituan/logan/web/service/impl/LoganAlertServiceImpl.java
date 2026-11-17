package com.meituan.logan.web.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.meituan.logan.web.mapper.LoganAlertMapper;
import com.meituan.logan.web.mapper.LoganErrorMapper;
import com.meituan.logan.web.mapper.LoganOverviewMapper;
import com.meituan.logan.web.service.LoganAlertService;
import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.Logger;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 报警实现（对标 Bugly 报警，但通知走通用 webhook，不绑死钉钉/企微/邮件）。
 *
 * 口径与概览看板完全一致（同一套 mapper 与同一套筛选键），避免「看板说 3% 报警说 5%」这种自相矛盾。
 * 分母 0（窗口内没有联网设备）时崩溃率类指标返回 null —— 宁缺毋假，不用 0% 触发或抑制报警。
 */
@Service
public class LoganAlertServiceImpl implements LoganAlertService {

    private static final Logger LOGGER = Logger.getLogger(LoganAlertServiceImpl.class);
    private static final long MINUTE = 60_000L;
    // 冷却下限：规则被写成 0（或负数）时不能变成「每轮扫描都发一次」，
    // 否则一条规则两小时就能把 logan_alert_record 灌成几百上千行并把 webhook 打爆（实测 692 条）。
    // 取下限 5 分钟，明显大于 worker 的 60s 扫描周期，保证同一次故障只通知一次。
    private static final int MIN_COOLDOWN_MINUTES = 5;
    private static final int WEBHOOK_TIMEOUT_MS = 5000;

    @Resource
    private LoganAlertMapper alertMapper;
    @Resource
    private LoganOverviewMapper overviewMapper;
    @Resource
    private LoganErrorMapper errorMapper;

    @Override
    public Double metricValue(Map<String, Object> rule, long beginTime, long endTime) {
        String metric = String.valueOf(rule.get("metric"));
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("appId", rule.get("appId"));
        Object plat = rule.get("platform");
        if (plat != null) {
            q.put("platform", plat);
        }
        Object ver = rule.get("appVersion");
        if (ver != null && StringUtils.isNotBlank(String.valueOf(ver))) {
            q.put("appVersion", ver);
        }
        q.put("beginTime", beginTime);
        q.put("endTime", endTime);
        q.put("tzOffsetMs", 0L);
        try {
            if ("crash_rate".equals(metric) || "anr_rate".equals(metric)) {
                long online = nz(overviewMapper.countOnlineDevices(q));
                if (online <= 0) {
                    return null; // 分母 0 → 不判定
                }
                Map<String, Object> c = overviewMapper.summaryCounts(q);
                long num = "crash_rate".equals(metric) ? lget(c, "crashDevices") : lget(c, "anrDevices");
                return round2(100.0 * num / online);
            }
            if ("crash_events".equals(metric)) {
                Map<String, Object> c = overviewMapper.summaryCounts(q);
                return (double) lget(c, "crashEvents");
            }
            if ("http_failed".equals(metric)) {
                Map<String, Object> c = overviewMapper.summaryCounts(q);
                return (double) lget(c, "httpFailed");
            }
            if ("http_slow".equals(metric)) {
                Map<String, Object> c = overviewMapper.summaryCounts(q);
                return (double) lget(c, "httpSlow");
            }
            if ("new_issue".equals(metric)) {
                return (double) (alertMapper.countNewCrashIssues(q) + alertMapper.countNewHttpIssues(q));
            }
            if ("error_rate".equals(metric)) {
                // 口径与概览一致：窗口内去重「出过错的设备」/ 联网设备数；采集未接入或分母 0 都返回 null 不判定
                long online = nz(overviewMapper.countOnlineDevices(q));
                if (online <= 0) {
                    return null;
                }
                Map<String, Object> e = errorMapper.summary(String.valueOf(rule.get("appId")),
                        Long.valueOf(beginTime), Long.valueOf(endTime),
                        plat == null ? null : Integer.valueOf(String.valueOf(plat)),
                        ver == null ? null : String.valueOf(ver));
                long bad = lget(e, "devices");
                return round2(100.0 * bad / online);
            }
            if ("error_events".equals(metric)) {
                Map<String, Object> e = errorMapper.summary(String.valueOf(rule.get("appId")),
                        Long.valueOf(beginTime), Long.valueOf(endTime),
                        plat == null ? null : Integer.valueOf(String.valueOf(plat)),
                        ver == null ? null : String.valueOf(ver));
                return (double) lget(e, "events");
            }
        } catch (Exception e) {
            LOGGER.warn("alert metric failed metric=" + metric + " err=" + e.getMessage());
            return null;
        }
        return null;
    }

    @Override
    public boolean hit(Double value, String opType, double threshold) {
        if (value == null) {
            return false;
        }
        String op = StringUtils.isBlank(opType) ? "gte" : opType;
        if ("gt".equals(op)) {
            return value > threshold;
        }
        if ("lt".equals(op)) {
            return value < threshold;
        }
        if ("lte".equals(op)) {
            return value <= threshold;
        }
        return value >= threshold;
    }

    @Override
    public int scanOnce() {
        List<Map<String, Object>> rules;
        try {
            rules = alertMapper.listEnabledRules();
        } catch (Exception e) {
            LOGGER.warn("alert rules unavailable: " + e.getMessage());
            return 0;
        }
        if (rules == null || rules.isEmpty()) {
            return 0;
        }
        long now = System.currentTimeMillis();
        int fired = 0;
        for (Map<String, Object> rule : rules) {
            try {
                int win = intOf(rule.get("windowMinutes"), 60);
                long begin = now - Math.max(1, win) * MINUTE;
                Double value = metricValue(rule, begin, now);
                if (value == null) {
                    continue;
                }
                double threshold = doubleOf(rule.get("threshold"));
                if (!hit(value, str(rule.get("opType")), threshold)) {
                    continue;
                }
                long cooldown = Math.max(MIN_COOLDOWN_MINUTES, intOf(rule.get("cooldownMinutes"), 30)) * MINUTE;
                long last = longOf(rule.get("lastTriggerTime"));
                if (last > 0 && now - last < cooldown) {
                    continue; // 冷却中，不刷屏
                }
                String webhook = str(rule.get("webhook"));
                String payload = buildPayload(rule, value, threshold, begin, now);
                String result = StringUtils.isBlank(webhook) ? "未配置 webhook（只落库）" : post(webhook, payload);
                Map<String, Object> rec = new LinkedHashMap<>();
                rec.put("ruleId", rule.get("id"));
                rec.put("appId", rule.get("appId"));
                rec.put("metric", rule.get("metric"));
                rec.put("value", value);
                rec.put("threshold", threshold);
                rec.put("windowBegin", begin);
                rec.put("windowEnd", now);
                rec.put("webhookResult", cut(result));
                rec.put("payload", payload);
                rec.put("addTime", now);
                alertMapper.insertRecord(rec);
                alertMapper.markTriggered(longOf(rule.get("id")), now);
                fired++;
                LOGGER.info("alert fired rule=" + rule.get("id") + " metric=" + rule.get("metric") + " value=" + value + " webhook=" + result);
            } catch (Exception e) {
                LOGGER.warn("alert rule scan failed id=" + rule.get("id") + " err=" + e.getMessage());
            }
        }
        return fired;
    }

    private static final int PURGE_BATCH_ROWS = 500;
    private static final int PURGE_MAX_BATCHES = 40;

    @Override
    public int purgeRecords(long retentionMs, int maxRows) {
        long cutoff = System.currentTimeMillis() - Math.max(0L, retentionMs);
        int removed = 0;
        try {
            for (int i = 0; i < PURGE_MAX_BATCHES; i++) {
                int n = alertMapper.deleteRecordsOlderThan(cutoff, PURGE_BATCH_ROWS);
                removed += Math.max(0, n);
                if (n < PURGE_BATCH_ROWS) {
                    break;
                }
            }
            if (maxRows > 0) {
                removed += Math.max(0, alertMapper.deleteRecordsBeyond(maxRows));
            }
        } catch (Exception e) {
            LOGGER.warn("alert record purge failed: " + e.getMessage());
        }
        return removed;
    }

    private String buildPayload(Map<String, Object> rule, Double value, double threshold, long begin, long end) {
        JSONObject o = new JSONObject(true);
        o.put("title", "Logan 报警：" + str(rule.get("name")));
        o.put("appId", rule.get("appId"));
        o.put("ruleId", rule.get("id"));
        o.put("metric", rule.get("metric"));
        o.put("metricLabel", metricLabel(String.valueOf(rule.get("metric"))));
        o.put("opType", rule.get("opType"));
        o.put("value", value);
        o.put("threshold", threshold);
        o.put("windowMinutes", rule.get("windowMinutes"));
        o.put("platform", rule.get("platform"));
        o.put("appVersion", rule.get("appVersion"));
        o.put("windowBegin", begin);
        o.put("windowEnd", end);
        o.put("text", "应用 " + rule.get("appId") + " 的 " + metricLabel(String.valueOf(rule.get("metric")))
                + " 在 " + rule.get("windowMinutes") + " 分钟内为 " + value + "，" + opLabel(str(rule.get("opType"))) + " " + threshold);
        return o.toJSONString();
    }

    /** 通用 webhook：标准 JSON + Content-Type: application/json，任何接收端都能接。 */
    private String post(String webhook, String payload) {
        HttpURLConnection conn = null;
        try {
            URL u = new URL(webhook);
            conn = (HttpURLConnection) u.openConnection();
            conn.setConnectTimeout(WEBHOOK_TIMEOUT_MS);
            conn.setReadTimeout(WEBHOOK_TIMEOUT_MS);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            byte[] body = payload.getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(body.length);
            DataOutputStream out = new DataOutputStream(conn.getOutputStream());
            out.write(body);
            out.flush();
            out.close();
            int code = conn.getResponseCode();
            InputStream in = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
            String resp = readAll(in);
            if (code >= 200 && code < 300) {
                return "ok http=" + code;
            }
            return "fail http=" + code + " resp=" + cut(resp);
        } catch (Exception e) {
            return "fail " + e.getClass().getSimpleName() + ": " + e.getMessage();
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static String readAll(InputStream in) {
        if (in == null) {
            return "";
        }
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[2048];
            int n;
            int total = 0;
            while ((n = in.read(buf)) > 0 && total < 2000) {
                bos.write(buf, 0, n);
                total += n;
            }
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        } finally {
            try {
                in.close();
            } catch (Exception ignore) {
            }
        }
    }

    private static String metricLabel(String metric) {
        if ("crash_rate".equals(metric)) {
            return "设备崩溃率(%)";
        }
        if ("anr_rate".equals(metric)) {
            return "设备ANR率(%)";
        }
        if ("error_rate".equals(metric)) {
            return "设备错误率(%)";
        }
        if ("crash_events".equals(metric)) {
            return "崩溃次数";
        }
        if ("http_failed".equals(metric)) {
            return "失败请求数";
        }
        if ("http_slow".equals(metric)) {
            return "慢请求数";
        }
        if ("new_issue".equals(metric)) {
            return "新增问题数";
        }
        if ("error_events".equals(metric)) {
            return "错误次数";
        }
        return metric;
    }

    private static String opLabel(String op) {
        if ("gt".equals(op)) {
            return ">";
        }
        if ("lt".equals(op)) {
            return "<";
        }
        if ("lte".equals(op)) {
            return "≤";
        }
        return "≥";
    }

    private static long nz(Long v) {
        return v == null ? 0L : v;
    }

    private static long lget(Map<String, Object> m, String k) {
        if (m == null) {
            return 0L;
        }
        Object v = m.get(k);
        return v instanceof Number ? ((Number) v).longValue() : 0L;
    }

    private static long longOf(Object v) {
        return v instanceof Number ? ((Number) v).longValue() : 0L;
    }

    private static double doubleOf(Object v) {
        return v instanceof Number ? ((Number) v).doubleValue() : 0d;
    }

    private static int intOf(Object v, int def) {
        if (v instanceof Number) {
            return ((Number) v).intValue();
        }
        try {
            return v == null ? def : Integer.parseInt(String.valueOf(v));
        } catch (Exception e) {
            return def;
        }
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static String cut(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= 200 ? s : s.substring(0, 200);
    }

    private static double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
