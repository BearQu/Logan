package com.meituan.logan.web.controller;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.meituan.logan.web.model.response.LoganResponse;
import com.meituan.logan.web.service.LoganAppService;
import com.meituan.logan.web.service.LoganBehaviorService;
import com.meituan.logan.web.util.AppGuard;
import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.Logger;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.io.BufferedReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 崩溃前行为轨迹对外接口 —— 对标 Bugly「崩溃前行为轨迹」。
 *
 * 客户端通道：POST /logan/behavior/report.json —— appId 必须已登记（与日志/崩溃/网络/错误同一套治理）。
 * 上报 body 两种形态：
 *   {appId,deviceId,events:[{kind,name,label,pageUrl,traceId,intervalMs,extra,occurredAt}, ...]}
 *   {appId,deviceId,kind,name,label,...}   // 单条
 * 归一化、脱敏、幂等都由服务端 BehaviorNorm 完成，任何项目直接上报原始事实即可。
 * 控制台通道：list / summary / around / top.json。
 */
@Controller
@RequestMapping("/logan/behavior")
public class LoganBehaviorController {

    private static final Logger LOGGER = Logger.getLogger(LoganBehaviorController.class);

    @Resource
    private LoganBehaviorService behaviorService;

    @Resource
    private LoganAppService appService;

    @RequestMapping(value = "/report.json", method = RequestMethod.POST)
    @ResponseBody
    public LoganResponse<Map<String, Object>> report(HttpServletRequest request) {
        Map<String, Object> body = readBody(request);
        String appId = pick3(body, request, "appId");
        String deviceId = pick3(body, request, "deviceId");
        if (StringUtils.isBlank(appId)) {
            return LoganResponse.badParam("appId required");
        }
        if (!AppGuard.registered(appService, appId)) {
            return LoganResponse.badParam(AppGuard.MSG);
        }
        if (StringUtils.isBlank(deviceId)) {
            return LoganResponse.badParam("deviceId required");
        }
        List<Map<String, Object>> events = events(body);
        if (events.isEmpty()) {
            return LoganResponse.badParam("events required");
        }
        Integer platform = pickInt(body, request, "platform");
        if (platform == null) {
            platform = parseInt(request.getHeader("platform"));
        }
        try {
            Map<String, Object> r = behaviorService.report(appId.trim(), deviceId.trim(), platform,
                    headerOr(pick(body, request, "appVersion"), request.getHeader("appVersion")),
                    headerOr(pick(body, request, "buildVersion"), request.getHeader("buildVersion")),
                    headerOr(pick(body, request, "os"), request.getHeader("os")),
                    headerOr(pick(body, request, "osVersion"), request.getHeader("osVersion")),
                    headerOr(pick(body, request, "brand"), request.getHeader("brand")),
                    headerOr(pick(body, request, "model"), request.getHeader("model")),
                    pick3(body, request, "networkType"), events);
            return LoganResponse.success(r);
        } catch (Exception e) {
            LOGGER.error("report behavior events failed appId=" + appId + " deviceId=" + deviceId, e);
            return LoganResponse.exception("report failed: " + e.getMessage());
        }
    }

    @RequestMapping(value = "/list.json", method = { RequestMethod.GET, RequestMethod.POST })
    @ResponseBody
    public LoganResponse<Map<String, Object>> list(HttpServletRequest request) {
        Map<String, Object> body = readBody(request);
        String appId = pick(body, request, "appId");
        if (StringUtils.isBlank(appId)) {
            return LoganResponse.badParam("appId required");
        }
        try {
            return LoganResponse.success(behaviorService.list(appId.trim(), pick(body, request, "deviceId"),
                    pick(body, request, "kind"), pick(body, request, "name"),
                    pickLong(body, request, "beginTime"), pickLong(body, request, "endTime"),
                    pick(body, request, "keyword"), pickInt(body, request, "limit")));
        } catch (Exception e) {
            LOGGER.error("list behavior failed appId=" + appId, e);
            return LoganResponse.exception("list failed: " + e.getMessage());
        }
    }

    @RequestMapping(value = "/summary.json", method = { RequestMethod.GET, RequestMethod.POST })
    @ResponseBody
    public LoganResponse<Map<String, Object>> summary(HttpServletRequest request) {
        Map<String, Object> body = readBody(request);
        String appId = pick(body, request, "appId");
        if (StringUtils.isBlank(appId)) {
            return LoganResponse.badParam("appId required");
        }
        try {
            return LoganResponse.success(behaviorService.summary(appId.trim(),
                    pickLong(body, request, "beginTime"), pickLong(body, request, "endTime"),
                    pickInt(body, request, "platform"), pick(body, request, "appVersion")));
        } catch (Exception e) {
            LOGGER.error("behavior summary failed appId=" + appId, e);
            return LoganResponse.exception("summary failed: " + e.getMessage());
        }
    }

    @RequestMapping(value = "/top.json", method = { RequestMethod.GET, RequestMethod.POST })
    @ResponseBody
    public LoganResponse<Map<String, Object>> top(HttpServletRequest request) {
        Map<String, Object> body = readBody(request);
        String appId = pick(body, request, "appId");
        if (StringUtils.isBlank(appId)) {
            return LoganResponse.badParam("appId required");
        }
        try {
            return LoganResponse.success(behaviorService.topNames(appId.trim(),
                    pickLong(body, request, "beginTime"), pickLong(body, request, "endTime"),
                    pick(body, request, "kind"), pickInt(body, request, "platform"),
                    pickInt(body, request, "limit")));
        } catch (Exception e) {
            LOGGER.error("behavior top failed appId=" + appId, e);
            return LoganResponse.exception("top failed: " + e.getMessage());
        }
    }

    @RequestMapping(value = "/around.json", method = { RequestMethod.GET, RequestMethod.POST })
    @ResponseBody
    public LoganResponse<Map<String, Object>> around(HttpServletRequest request) {
        Map<String, Object> body = readBody(request);
        String appId = pick(body, request, "appId");
        String deviceId = pick3(body, request, "deviceId");
        if (StringUtils.isBlank(appId) || StringUtils.isBlank(deviceId)) {
            return LoganResponse.badParam("appId and deviceId required");
        }
        Long crashTime = pickLong(body, request, "crashTime");
        if (crashTime == null || crashTime.longValue() <= 0) {
            return LoganResponse.badParam("crashTime required");
        }
        try {
            return LoganResponse.success(behaviorService.around(appId.trim(), deviceId.trim(),
                    crashTime.longValue(), pickInt(body, request, "beforeSeconds"),
                    pickInt(body, request, "afterSeconds"), pickInt(body, request, "limit")));
        } catch (Exception e) {
            LOGGER.error("behavior around failed appId=" + appId, e);
            return LoganResponse.exception("around failed: " + e.getMessage());
        }
    }

    // ---------- 私有工具（与错误/网络侧同一套，保持客户端上报姿势一致） ----------

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> events(Map<String, Object> body) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        if (body == null) {
            return out;
        }
        Object raw = body.get("events");
        if (raw == null) {
            raw = body.get("behaviors");
        }
        if (raw instanceof JSONArray) {
            for (Object o : (JSONArray) raw) {
                if (o instanceof Map) {
                    out.add((Map<String, Object>) o);
                }
            }
            return out;
        }
        if (raw instanceof List) {
            for (Object o : (List<Object>) raw) {
                if (o instanceof Map) {
                    out.add((Map<String, Object>) o);
                }
            }
            return out;
        }
        // 单条形态：body 本身就是一条行为
        if (body.get("name") != null || body.get("label") != null || body.get("pageUrl") != null) {
            Map<String, Object> single = new HashMap<String, Object>(body);
            single.remove("appId");
            single.remove("deviceId");
            single.remove("platform");
            single.remove("appVersion");
            single.remove("buildVersion");
            single.remove("os");
            single.remove("osVersion");
            single.remove("brand");
            single.remove("model");
            single.remove("networkType");
            single.remove("events");
            single.remove("behaviors");
            out.add(single);
        }
        return out;
    }

    private static Map<String, Object> readBody(HttpServletRequest request) {
        String ctype = StringUtils.defaultString(request.getContentType()).toLowerCase();
        if (!ctype.contains("json")) {
            return null;
        }
        String encoding = StringUtils.defaultString(request.getHeader("Content-Encoding")).toLowerCase();
        try {
            StringBuilder sb = new StringBuilder();
            java.io.InputStream in = encoding.contains("gzip")
                    ? new java.util.zip.GZIPInputStream(request.getInputStream())
                    : request.getInputStream();
            BufferedReader reader = new BufferedReader(new java.io.InputStreamReader(in, "UTF-8"));
            char[] buf = new char[4096];
            int n;
            int total = 0;
            while ((n = reader.read(buf)) > 0) {
                sb.append(buf, 0, n);
                total += n;
                if (total > 4 * 1024 * 1024) {
                    break;
                }
            }
            if (sb.length() == 0) {
                return null;
            }
            JSONObject obj = JSON.parseObject(sb.toString());
            return obj == null ? null : obj;
        } catch (Exception e) {
            LOGGER.warn("read behavior report body failed: " + e.getMessage());
            return null;
        }
    }

    private static String pick(Map<String, Object> body, HttpServletRequest request, String key) {
        if (body != null && body.get(key) != null && StringUtils.isNotBlank(String.valueOf(body.get(key)))) {
            return String.valueOf(body.get(key));
        }
        String v = request.getParameter(key);
        return StringUtils.isNotBlank(v) ? v : null;
    }

    private static String pick3(Map<String, Object> body, HttpServletRequest request, String key) {
        String v = pick(body, request, key);
        if (StringUtils.isNotBlank(v)) {
            return v;
        }
        return request.getHeader(key);
    }

    private static String headerOr(String value, String header) {
        return StringUtils.isNotBlank(value) ? value : header;
    }

    private static Integer pickInt(Map<String, Object> body, HttpServletRequest request, String key) {
        return parseInt(pick(body, request, key));
    }

    private static Long pickLong(Map<String, Object> body, HttpServletRequest request, String key) {
        String v = pick(body, request, key);
        if (StringUtils.isBlank(v)) {
            return null;
        }
        try {
            return Long.valueOf((long) Double.parseDouble(v.trim()));
        } catch (Exception e) {
            return null;
        }
    }

    private static Integer parseInt(String v) {
        if (StringUtils.isBlank(v)) {
            return null;
        }
        try {
            return Integer.valueOf((int) Double.parseDouble(v.trim()));
        } catch (Exception e) {
            return null;
        }
    }
}
