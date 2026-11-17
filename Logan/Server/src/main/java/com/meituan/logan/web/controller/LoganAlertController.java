package com.meituan.logan.web.controller;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.meituan.logan.web.mapper.LoganAlertMapper;
import com.meituan.logan.web.model.response.LoganResponse;
import com.meituan.logan.web.service.LoganAlertService;
import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.Logger;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.io.BufferedReader;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

// 报警规则管理（对标 Bugly 报警，但通知只走通用 webhook）。
//
// 口径说明：指标取值复用概览那套 mapper，保证看板与报警数字一致；
// 规则命中后写 logan_alert_record 并 POST 标准 JSON 到 webhook，webhook 留空则只落库（自证链路）。
@CrossOrigin(origins = "*", maxAge = 3600)
@Controller
@RequestMapping("/logan/alert")
public class LoganAlertController {

    private static final Logger LOGGER = Logger.getLogger(LoganAlertController.class);
    private static final Set<String> METRICS = new HashSet<>(Arrays.asList(
            "crash_rate", "anr_rate", "error_rate", "error_events",
            "crash_events", "http_failed", "http_slow", "new_issue"));
    private static final Set<String> OPS = new HashSet<>(Arrays.asList("gte", "gt", "lt", "lte"));
    // 冷却不允许 0、也不允许短于扫描周期太多：否则规则每轮扫描都发一次 webhook 并把记录表灌爆
    // （实测一条 cooldown=0 的规则两小时灌出 692 条记录）。
    private static final int MIN_COOLDOWN_MINUTES = 5;

    private static int clampCooldown(Integer v) {
        int minutes = v == null ? 30 : v.intValue();
        if (minutes < MIN_COOLDOWN_MINUTES) {
            return MIN_COOLDOWN_MINUTES;
        }
        return Math.min(minutes, 43200);
    }

    @Resource
    private LoganAlertMapper alertMapper;
    @Resource
    private LoganAlertService alertService;

    // 规则列表（含停用），前端报警页用。
    @RequestMapping(value = "/rules.json", method = {RequestMethod.GET, RequestMethod.POST})
    @ResponseBody
    public LoganResponse<Map<String, Object>> rules(String appId, Integer enabled, Integer limit) {
        try {
            int n = limit == null || limit <= 0 ? 100 : Math.min(300, limit);
            List<Map<String, Object>> list = alertMapper.listRules(trim(appId), enabled, n);
            Integer total = Integer.valueOf(alertMapper.countEnabledRules(trim(appId)));
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("rules", list);
            data.put("enabledTotal", total);
            data.put("metricOptions", METRIC_OPTIONS());
            return LoganResponse.success(data);
        } catch (Exception e) {
            LOGGER.warn("alert rules failed " + e.getMessage());
            return LoganResponse.exception(e.getMessage());
        }
    }

    // 新建或更新规则。body：id(可选) / appId / name / metric / opType / threshold / windowMinutes / platform / appVersion / webhook / enabled / cooldownMinutes / createdBy
    @RequestMapping(value = "/rule/save.json", method = RequestMethod.POST)
    @ResponseBody
    public LoganResponse<Map<String, Object>> saveRule(HttpServletRequest request) {
        JSONObject body = readJson(request);
        if (body == null) {
            return LoganResponse.badParam("invalid json body");
        }
        String name = trim(body.getString("name"));
        String appId = trim(body.getString("appId"));
        String metric = trim(body.getString("metric"));
        String opType = trim(body.getString("opType"));
        Double threshold = body.getDouble("threshold");
        if (StringUtils.isBlank(appId)) {
            return LoganResponse.badParam("appId required");
        }
        if (StringUtils.isBlank(name) || name.length() > 120) {
            return LoganResponse.badParam("name required, max 120 chars");
        }
        if (metric == null || !METRICS.contains(metric)) {
            return LoganResponse.badParam("unsupported metric");
        }
        if (opType == null) {
            opType = "gte";
        }
        if (!OPS.contains(opType)) {
            return LoganResponse.badParam("opType must be gte, gt, lt or lte");
        }
        if (threshold == null) {
            return LoganResponse.badParam("threshold required");
        }
        Long id = body.getLong("id");
        try {
            long now = System.currentTimeMillis();
            if (id == null || id <= 0) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("appId", appId);
                row.put("name", name);
                row.put("metric", metric);
                row.put("opType", opType);
                row.put("threshold", threshold);
                row.put("windowMinutes", intOf(body.getInteger("windowMinutes"), 60));
                row.put("platform", body.getInteger("platform"));
                row.put("appVersion", trim(body.getString("appVersion")));
                row.put("webhook", trim(body.getString("webhook")));
                Boolean en = body.getBoolean("enabled");
                row.put("enabled", en == null || en ? Integer.valueOf(1) : Integer.valueOf(0));
                row.put("cooldownMinutes", clampCooldown(body.getInteger("cooldownMinutes")));
                row.put("createdBy", trim(body.getString("createdBy")));
                row.put("addTime", now);
                alertMapper.insertRule(row);
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("id", row.get("id"));
                data.put("created", Boolean.TRUE);
                return LoganResponse.success(data);
            }
            if (alertMapper.selectRule(id.longValue()) == null) {
                return LoganResponse.badParam("rule not found");
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", id);
            row.put("name", name);
            row.put("metric", metric);
            row.put("opType", opType);
            row.put("threshold", threshold);
            if (body.get("windowMinutes") != null) {
                row.put("windowMinutes", intOf(body.getInteger("windowMinutes"), 60));
            }
            if (body.get("platform") != null) {
                row.put("platform", body.getInteger("platform"));
            }
            if (body.containsKey("appVersion")) {
                row.put("appVersion", trim(body.getString("appVersion")));
            }
            if (body.containsKey("webhook")) {
                row.put("webhook", trim(body.getString("webhook")));
            }
            if (body.get("enabled") != null) {
                Boolean en = body.getBoolean("enabled");
                row.put("enabled", en != null && en ? Integer.valueOf(1) : Integer.valueOf(0));
            }
            if (body.get("cooldownMinutes") != null) {
                row.put("cooldownMinutes", clampCooldown(body.getInteger("cooldownMinutes")));
            }
            row.put("updateTime", now);
            alertMapper.updateRule(row);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("id", id);
            data.put("updated", Boolean.TRUE);
            return LoganResponse.success(data);
        } catch (Exception e) {
            LOGGER.warn("save rule failed " + e.getMessage());
            return LoganResponse.exception(e.getMessage());
        }
    }

    // 启用 / 停用。
    @RequestMapping(value = "/rule/enable.json", method = {RequestMethod.GET, RequestMethod.POST})
    @ResponseBody
    public LoganResponse<Map<String, Object>> enable(Long id, Integer enabled) {
        if (id == null || id <= 0 || enabled == null) {
            return LoganResponse.badParam("id and enabled required");
        }
        try {
            if (alertMapper.selectRule(id.longValue()) == null) {
                return LoganResponse.badParam("rule not found");
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", id);
            row.put("enabled", enabled.intValue() == 1 ? Integer.valueOf(1) : Integer.valueOf(0));
            row.put("updateTime", System.currentTimeMillis());
            alertMapper.updateRule(row);
            return LoganResponse.success(row);
        } catch (Exception e) {
            return LoganResponse.exception(e.getMessage());
        }
    }

    @RequestMapping(value = "/rule/delete.json", method = {RequestMethod.GET, RequestMethod.POST})
    @ResponseBody
    public LoganResponse<Map<String, Object>> remove(Long id) {
        if (id == null || id <= 0) {
            return LoganResponse.badParam("id required");
        }
        try {
            int n = alertMapper.deleteRule(id.longValue());
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("deleted", Integer.valueOf(n));
            return LoganResponse.success(data);
        } catch (Exception e) {
            return LoganResponse.exception(e.getMessage());
        }
    }

    // 触发记录（含 webhook 回执），用于「先能收到就行」的自证。
    @RequestMapping(value = "/records.json", method = {RequestMethod.GET, RequestMethod.POST})
    @ResponseBody
    public LoganResponse<List<Map<String, Object>>> records(String appId, Long ruleId, Integer limit) {
        try {
            int n = limit == null || limit <= 0 ? 50 : Math.min(300, limit);
            return LoganResponse.success(alertMapper.listRecords(trim(appId), ruleId, n));
        } catch (Exception e) {
            return LoganResponse.exception(e.getMessage());
        }
    }

    // 手动跑一轮扫描：不等 worker 的 60s 周期，便于验收。
    @RequestMapping(value = "/scan.json", method = {RequestMethod.GET, RequestMethod.POST})
    @ResponseBody
    public LoganResponse<Map<String, Object>> scan() {
        try {
            int fired = alertService.scanOnce();
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("fired", Integer.valueOf(fired));
            data.put("at", Long.valueOf(System.currentTimeMillis()));
            return LoganResponse.success(data);
        } catch (Exception e) {
            LOGGER.warn("manual scan failed " + e.getMessage());
            return LoganResponse.exception(e.getMessage());
        }
    }

    // 人工触发一次报警记录清理（运维/验收用）：retentionDays 默认 7，maxRows 默认 2000。
    @RequestMapping(value = "/records/purge.json", method = {RequestMethod.GET, RequestMethod.POST})
    @ResponseBody
    public LoganResponse<Map<String, Object>> purgeRecords(Integer retentionDays, Integer maxRows) {
        int days = (retentionDays == null || retentionDays <= 0) ? 7 : Math.min(retentionDays.intValue(), 3650);
        int rows = (maxRows == null) ? 2000 : Math.max(0, Math.min(maxRows.intValue(), 200000));
        try {
            int removed = alertService.purgeRecords(days * 24L * 3600 * 1000L, rows);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("removed", Integer.valueOf(removed));
            data.put("retentionDays", Integer.valueOf(days));
            data.put("maxRows", Integer.valueOf(rows));
            return LoganResponse.success(data);
        } catch (Exception e) {
            LOGGER.warn("purge alert records failed " + e.getMessage());
            return LoganResponse.exception(e.getMessage());
        }
    }

    // 试算单条规则当前值（不触发、不落库），前端「预览当前值」按钮用。
    @RequestMapping(value = "/preview.json", method = {RequestMethod.GET, RequestMethod.POST})
    @ResponseBody
    public LoganResponse<Map<String, Object>> preview(Long id, Integer windowMinutes) {
        try {
            Map<String, Object> rule = id == null ? null : alertMapper.selectRule(id.longValue());
            if (rule == null) {
                return LoganResponse.badParam("rule not found");
            }
            int wm = windowMinutes == null || windowMinutes <= 0
                    ? intOfObj(rule.get("windowMinutes"), 60) : windowMinutes.intValue();
            long end = System.currentTimeMillis();
            long begin = end - Math.max(1, wm) * 60_000L;
            Double v = alertService.metricValue(rule, begin, end);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("ruleId", id);
            data.put("metric", rule.get("metric"));
            data.put("windowMinutes", Integer.valueOf(wm));
            data.put("beginTime", Long.valueOf(begin));
            data.put("endTime", Long.valueOf(end));
            data.put("value", v);
            data.put("available", Boolean.valueOf(v != null));
            data.put("note", v == null ? "该指标当前不可用（采集未接入或分母为 0），不会触发报警" : null);
            return LoganResponse.success(data);
        } catch (Exception e) {
            return LoganResponse.exception(e.getMessage());
        }
    }

    // 指标元数据：前端下拉与口径说明的唯一来源，避免前后端各写一份。
    private static List<Map<String, Object>> METRIC_OPTIONS() {
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        out.add(option("crash_rate", "设备崩溃率(%)", "窗口内去重崩溃设备数 / 联网设备数 × 100；联网设备数取业务日志、设备心跳、崩溃、网络四通道去重", "%", Boolean.TRUE));
        out.add(option("anr_rate", "设备ANR率(%)", "窗口内去重ANR设备数 / 联网设备数 × 100", "%", Boolean.TRUE));
        out.add(option("crash_events", "崩溃次数", "窗口内崩溃上报总次数，不去重", "次", Boolean.TRUE));
        out.add(option("http_failed", "失败请求数", "窗口内 HTTP 失败（网络错误或非 2xx）条数", "次", Boolean.TRUE));
        out.add(option("http_slow", "慢请求数", "窗口内耗时超过慢阈值的请求条数", "次", Boolean.TRUE));
        out.add(option("new_issue", "新增问题数", "窗口内首次出现的崩溃问题 + 网络问题条数（按首次时间，不看最近发生）", "个", Boolean.TRUE));
        out.add(option("error_rate", "设备错误率(%)", "窗口内去重「出过错的设备」/ 联网设备数 × 100；未接入非致命采集时返回不可用，不会误报 0%", "%", Boolean.TRUE));
        out.add(option("error_events", "错误次数", "窗口内非致命错误（可捕获异常 / Promise 失败 / 资源失败）上报条数，不去重", "次", Boolean.TRUE));
        return out;
    }

    private static Map<String, Object> option(String metric, String label, String desc, String unit, Boolean available) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("metric", metric);
        m.put("label", label);
        m.put("description", desc);
        m.put("unit", unit);
        m.put("available", available);
        return m;
    }

    private static int intOfObj(Object v, int def) {
        if (v instanceof Number) {
            return ((Number) v).intValue();
        }
        try {
            return v == null ? def : Integer.parseInt(String.valueOf(v));
        } catch (Exception e) {
            return def;
        }
    }

    private static int intOf(Integer v, int def) {
        return v == null ? def : v.intValue();
    }

    private static String trim(String v) {
        if (v == null) {
            return null;
        }
        String t = v.trim();
        return t.isEmpty() ? null : t;
    }

    private static JSONObject readJson(HttpServletRequest request) {
        try {
            BufferedReader r = request.getReader();
            StringBuilder sb = new StringBuilder();
            String line;
            int total = 0;
            while ((line = r.readLine()) != null && total < 200000) {
                sb.append(line);
                total += line.length();
            }
            String txt = sb.toString().trim();
            if (txt.isEmpty()) {
                return null;
            }
            return JSON.parseObject(txt);
        } catch (Exception e) {
            LOGGER.warn("read body failed: " + e.getMessage());
            return null;
        }
    }
}
