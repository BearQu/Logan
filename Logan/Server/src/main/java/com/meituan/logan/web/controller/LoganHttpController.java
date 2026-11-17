package com.meituan.logan.web.controller;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.meituan.logan.web.dto.LoganHttpDTO;
import com.meituan.logan.web.dto.LoganHttpIssueDTO;
import com.meituan.logan.web.model.response.LoganResponse;
import com.meituan.logan.web.service.LoganAppService;
import com.meituan.logan.web.service.LoganHttpService;
import com.meituan.logan.web.util.AppGuard;
import com.meituan.logan.web.util.HeaderUtil;
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
 * 网络请求观测（慢请求 / 失败请求 / H5 请求）对外接口。
 *
 * 客户端通道：POST /logan/http/report.json —— 要求 appId 已登记，与日志、崩溃通道同一套治理。
 * 控制台通道：list / summary / around / issues / issue/detail / issue/status。
 *
 * 上报 body 允许两种形态，方便不同客户端接：
 *   {appId,deviceId,events:[{url,method,status,errKind,errMsg,durationMs,...}, ...]}
 *   {appId,deviceId,url,method,...}                // 单条时 events 可省略
 * 归一化、脱敏、指纹、错误归类都由服务端 HttpNorm 完成，客户端不需要知道这些规则。
 */
@Controller
@RequestMapping("/logan/http")
public class LoganHttpController {

    private static final Logger LOGGER = Logger.getLogger(LoganHttpController.class);

    @Resource
    private LoganHttpService httpService;

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
            Map<String, Object> r = httpService.report(appId.trim(), deviceId.trim(), platform,
                    headerOr(pick(body, request, "appVersion"), request.getHeader("appVersion")),
                    headerOr(pick(body, request, "buildVersion"), request.getHeader("buildVersion")),
                    headerOr(pick(body, request, "os"), request.getHeader("os")),
                    headerOr(pick(body, request, "osVersion"), request.getHeader("osVersion")),
                    headerOr(pick(body, request, "brand"), request.getHeader("brand")),
                    headerOr(pick(body, request, "model"), request.getHeader("model")),
                    pick3(body, request, "networkType"), events);
            return LoganResponse.success(r);
        } catch (Exception e) {
            LOGGER.error("report http events failed appId=" + appId + " deviceId=" + deviceId, e);
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
        if (!AppGuard.registered(appService, appId)) {
            return LoganResponse.badParam(AppGuard.MSG);
        }
        try {
            Map<String, Object> r = httpService.list(appId.trim(), pick(body, request, "deviceId"),
                    pick(body, request, "kind"), pick(body, request, "host"), pick(body, request, "path"),
                    pick(body, request, "errKind"), pickInt(body, request, "minStatus"),
                    pickInt(body, request, "maxStatus"), boolParam(body, request, "slowOnly"),
                    pickLong(body, request, "beginTime"), pickLong(body, request, "endTime"),
                    pick(body, request, "keyword"), pickInt(body, request, "limit"));
            return LoganResponse.success(r);
        } catch (Exception e) {
            LOGGER.error("list http events failed appId=" + appId, e);
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
        if (!AppGuard.registered(appService, appId)) {
            return LoganResponse.badParam(AppGuard.MSG);
        }
        try {
            return LoganResponse.success(httpService.summary(appId.trim(),
                    pickLong(body, request, "beginTime"), pickLong(body, request, "endTime")));
        } catch (Exception e) {
            LOGGER.error("summary http failed appId=" + appId, e);
            return LoganResponse.exception("summary failed: " + e.getMessage());
        }
    }

    /** 崩溃前后互证用：某设备某时间窗内的网络事件。 */
    @RequestMapping(value = "/around.json", method = { RequestMethod.GET, RequestMethod.POST })
    @ResponseBody
    public LoganResponse<List<LoganHttpDTO>> around(HttpServletRequest request) {
        Map<String, Object> body = readBody(request);
        String appId = pick(body, request, "appId");
        String deviceId = pick(body, request, "deviceId");
        if (StringUtils.isBlank(appId) || StringUtils.isBlank(deviceId)) {
            return LoganResponse.badParam("appId and deviceId required");
        }
        if (!AppGuard.registered(appService, appId)) {
            return LoganResponse.badParam(AppGuard.MSG);
        }
        try {
            return LoganResponse.success(httpService.around(appId.trim(), deviceId.trim(),
                    pickLong(body, request, "beginTime"), pickLong(body, request, "endTime"),
                    pickInt(body, request, "limit")));
        } catch (Exception e) {
            LOGGER.error("around http events failed appId=" + appId + " deviceId=" + deviceId, e);
            return LoganResponse.exception("around failed: " + e.getMessage());
        }
    }


    @RequestMapping(value = "/issues.json", method = { RequestMethod.GET, RequestMethod.POST })
    @ResponseBody
    public LoganResponse<Map<String, Object>> issues(HttpServletRequest request) {
        Map<String, Object> body = readBody(request);
        String appId = pick(body, request, "appId");
        if (StringUtils.isBlank(appId)) {
            return LoganResponse.badParam("appId required");
        }
        if (!AppGuard.registered(appService, appId)) {
            return LoganResponse.badParam(AppGuard.MSG);
        }
        try {
            return LoganResponse.success(httpService.issues(appId.trim(), pickInt(body, request, "statusFlag"),
                    pick(body, request, "kind"), pick(body, request, "errKind"), pickInt(body, request, "limit")));
        } catch (Exception e) {
            LOGGER.error("list http issues failed appId=" + appId, e);
            return LoganResponse.exception("issues failed: " + e.getMessage());
        }
    }

    @RequestMapping(value = "/issue/detail.json", method = { RequestMethod.GET, RequestMethod.POST })
    @ResponseBody
    public LoganResponse<LoganHttpIssueDTO> issueDetail(HttpServletRequest request) {
        Map<String, Object> body = readBody(request);
        Long id = pickLong(body, request, "id");
        if (id == null || id.longValue() <= 0) {
            return LoganResponse.badParam("id required");
        }
        try {
            LoganHttpIssueDTO dto = httpService.issueDetail(id);
            return dto == null ? LoganResponse.badParam("issue not found") : LoganResponse.success(dto);
        } catch (Exception e) {
            LOGGER.error("http issue detail failed id=" + id, e);
            return LoganResponse.exception("detail failed: " + e.getMessage());
        }
    }

    /** 处置网络问题：statusFlag 0=未处理 1=处理中 2=已修复；标 2 后同指纹再上报会自动重开并保留历史结论。 */
    @RequestMapping(value = "/issue/status.json", method = RequestMethod.POST)
    @ResponseBody
    public LoganResponse<Boolean> issueStatus(HttpServletRequest request) {
        Map<String, Object> body = readBody(request);
        Long id = pickLong(body, request, "id");
        Integer statusFlag = pickInt(body, request, "statusFlag");
        if (id == null || id.longValue() <= 0) {
            return LoganResponse.badParam("id required");
        }
        if (statusFlag == null) {
            return LoganResponse.badParam("statusFlag required");
        }
        try {
            boolean ok = httpService.updateIssueStatus(id, statusFlag, pick(body, request, "lastFixNote"));
            return ok ? LoganResponse.success(Boolean.TRUE) : LoganResponse.badParam("update failed");
        } catch (Exception e) {
            LOGGER.error("update http issue status failed id=" + id, e);
            return LoganResponse.exception("update failed: " + e.getMessage());
        }
    }

    // ---------- 私有工具（与其他控制器同一套取参姿势） ----------

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> events(Map<String, Object> body) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        if (body == null) {
            return out;
        }
        Object raw = body.get("events");
        if (raw == null) {
            raw = body.get("logs");
        }
        if (raw instanceof String && StringUtils.isNotBlank((String) raw)) {
            try {
                JSONArray arr = JSON.parseArray((String) raw);
                if (arr != null) {
                    for (int i = 0; i < arr.size(); i++) {
                        JSONObject o = arr.getJSONObject(i);
                        if (o != null) {
                            out.add(o);
                        }
                    }
                }
                return out;
            } catch (Exception ignore) {
                return out;
            }
        }
        if (raw instanceof JSONArray) {
            JSONArray arr = (JSONArray) raw;
            for (int i = 0; i < arr.size(); i++) {
                JSONObject o = arr.getJSONObject(i);
                if (o != null) {
                    out.add(o);
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
        // 单条上报形态：body 本身就是事件（去掉控制字段后剩下的就是事实字段）
        if (body.get("url") != null) {
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
            single.remove("logs");
            out.add(single);
        }
        return out;
    }

    private static String pick(Map<String, Object> body, HttpServletRequest request, String key) {
        if (body != null && body.get(key) != null && StringUtils.isNotBlank(String.valueOf(body.get(key)))) {
            return String.valueOf(body.get(key));
        }
        String v = request.getParameter(key);
        if (StringUtils.isNotBlank(v)) {
            return v;
        }
        return null;
    }

    private static String pick3(Map<String, Object> body, HttpServletRequest request, String key) {
        String v = pick(body, request, key);
        if (StringUtils.isNotBlank(v)) {
            return v;
        }
        String h = request.getHeader(key);
        if ("appName".equals(key)) {
            return HeaderUtil.utf8(h);
        }
        return h;
    }

    private static String headerOr(String value, String header) {
        return StringUtils.isNotBlank(value) ? value : header;
    }

    private static Integer pickInt(Map<String, Object> body, HttpServletRequest request, String key) {
        String v = pick(body, request, key);
        return parseInt(v);
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

    private static boolean boolParam(Map<String, Object> body, HttpServletRequest request, String key) {
        String v = pick(body, request, key);
        if (StringUtils.isBlank(v)) {
            return false;
        }
        String s = v.trim();
        return "true".equalsIgnoreCase(s) || "1".equals(s);
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

    private static Map<String, Object> readBody(HttpServletRequest request) {
        String ctype = StringUtils.defaultString(request.getContentType()).toLowerCase();
        if (!ctype.contains("json")) {
            return null;
        }
        String encoding = StringUtils.defaultString(request.getHeader("Content-Encoding")).toLowerCase();
        try {
            StringBuilder sb = new StringBuilder();
            // 客户端为省流量按 gzip 压缩上报（同崩溃直传姿势）；不解压直接 getReader() 会读到二进制，JSON 解析必失败
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
            LOGGER.warn("read http report body failed: " + e.getMessage());
            return null;
        }
    }
}

