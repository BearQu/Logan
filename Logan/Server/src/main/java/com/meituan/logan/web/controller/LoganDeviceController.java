package com.meituan.logan.web.controller;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.meituan.logan.web.dto.LoganDeviceCommandDTO;
import com.meituan.logan.web.model.response.LoganResponse;
import com.meituan.logan.web.service.LoganAppService;
import com.meituan.logan.web.service.LoganDeviceService;
import com.meituan.logan.web.util.AppGuard;
import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.Logger;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.io.BufferedReader;
import java.util.HashMap;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 主动拉日志通道（轮询式命令下发）。控制台侧走 nginx basic-auth；客户端侧 poll/ack 要求 appId 已登记。 */
@Controller
@RequestMapping("/logan/device")
public class LoganDeviceController {

    private static final Logger LOGGER = Logger.getLogger(LoganDeviceController.class);

    private static final Set<String> ACTIONS = new HashSet<String>(Arrays.asList("upload_logs", "ping"));

    @Resource
    private LoganDeviceService deviceService;

    @Resource
    private LoganAppService appService;

    @RequestMapping(value = "/command/dispatch.json", method = RequestMethod.POST)
    @ResponseBody
    public LoganResponse<Map<String, Object>> dispatch(HttpServletRequest request) {
        Map<String, Object> body = readBody(request);
        String appId = pick(body, request, "appId");
        String deviceId = pick(body, request, "deviceId");
        String action = pick(body, request, "action");
        if (StringUtils.isBlank(appId)) {
            return LoganResponse.badParam("appId required");
        }
        if (!AppGuard.registered(appService, appId)) {
            return LoganResponse.badParam(AppGuard.MSG);
        }
        if (StringUtils.isBlank(deviceId)) {
            return LoganResponse.badParam("deviceId required");
        }
        if (StringUtils.isBlank(action) || !ACTIONS.contains(action.trim())) {
            return LoganResponse.badParam("action must be upload_logs or ping");
        }
        String param = pick(body, request, "param");
        String note = pick(body, request, "note");
        String operator = StringUtils.defaultIfBlank(pick(body, request, "operator"), "console");
        Long ttl = pickLong(body, request, "ttlMs");
        try {
            Map<String, Object> r = deviceService.dispatch(appId.trim(), deviceId.trim(),
                    action.trim(), param, note, operator, ttl);
            if (r.containsKey("error")) {
                return LoganResponse.exception(String.valueOf(r.get("error")));
            }
            return LoganResponse.success(r);
        } catch (Exception e) {
            LOGGER.error("dispatch device command failed", e);
            return LoganResponse.exception("dispatch failed: " + e.getMessage());
        }
    }

    @RequestMapping(value = "/command/cancel.json", method = RequestMethod.POST)
    @ResponseBody
    public LoganResponse<Boolean> cancel(HttpServletRequest request) {
        Map<String, Object> body = readBody(request);
        Long id = pickLong(body, request, "id");
        if (id == null || id.longValue() <= 0) {
            return LoganResponse.badParam("id required");
        }
        try {
            boolean ok = deviceService.cancel(id.longValue());
            return ok ? LoganResponse.success(Boolean.TRUE)
                    : LoganResponse.badParam("command not cancellable");
        } catch (Exception e) {
            LOGGER.error("cancel device command failed", e);
            return LoganResponse.exception("cancel failed: " + e.getMessage());
        }
    }

    /** 客户端轮询领取命令。 */
    @RequestMapping(value = "/poll.json", method = RequestMethod.POST)
    @ResponseBody
    public LoganResponse<List<LoganDeviceCommandDTO>> poll(HttpServletRequest request) {
        Map<String, Object> body = readBody(request);
        String appId = headerOr(pick(body, request, "appId"), request.getHeader("appId"));
        String deviceId = headerOr(pick(body, request, "deviceId"), request.getHeader("deviceId"));
        if (StringUtils.isBlank(appId)) {
            return LoganResponse.badParam("appId required");
        }
        if (!AppGuard.registered(appService, appId)) {
            return LoganResponse.badParam(AppGuard.MSG);
        }
        if (StringUtils.isBlank(deviceId)) {
            return LoganResponse.badParam("deviceId required");
        }
        try {
            List<LoganDeviceCommandDTO> cmds = deviceService.poll(appId.trim(), deviceId.trim(),
                    pickInt(body, request, "platform"),
                    headerOr(pick(body, request, "appVersion"), request.getHeader("appVersion")),
                    headerOr(pick(body, request, "buildVersion"), request.getHeader("buildVersion")),
                    headerOr(pick(body, request, "sdkVersion"), request.getHeader("sdkVersion")),
                    pickInt(body, request, "limit"));
            return LoganResponse.success(cmds);
        } catch (Exception e) {
            LOGGER.error("device poll failed", e);
            return LoganResponse.exception("poll failed: " + e.getMessage());
        }
    }

    /** 客户端执行完回执。 */
    @RequestMapping(value = "/command/ack.json", method = RequestMethod.POST)
    @ResponseBody
    public LoganResponse<Boolean> ack(HttpServletRequest request) {
        Map<String, Object> body = readBody(request);
        Long id = pickLong(body, request, "id");
        if (id == null || id.longValue() <= 0) {
            return LoganResponse.badParam("id required");
        }
        String okStr = pick(body, request, "ok");
        boolean ok = !"false".equalsIgnoreCase(StringUtils.defaultString(okStr)) && !"0".equals(okStr);
        String resultNote = pick(body, request, "resultNote");
        try {
            boolean done = deviceService.ack(id.longValue(), ok, resultNote);
            return done ? LoganResponse.success(Boolean.TRUE)
                    : LoganResponse.badParam("command not found or already finished");
        } catch (Exception e) {
            LOGGER.error("ack device command failed", e);
            return LoganResponse.exception("ack failed: " + e.getMessage());
        }
    }

    @RequestMapping(value = "/list.json", method = RequestMethod.GET)
    @ResponseBody
    public LoganResponse<Map<String, Object>> list(HttpServletRequest request) {
        String appId = request.getParameter("appId");
        if (StringUtils.isBlank(appId)) {
            return LoganResponse.badParam("appId required");
        }
        if (!AppGuard.registered(appService, appId)) {
            return LoganResponse.badParam(AppGuard.MSG);
        }
        try {
            return LoganResponse.success(deviceService.listDevices(appId.trim(),
                    request.getParameter("keyword"), parseInt(request.getParameter("limit")),
                    parseInt(request.getParameter("offset"))));
        } catch (Exception e) {
            LOGGER.error("list devices failed", e);
            return LoganResponse.exception("list failed: " + e.getMessage());
        }
    }

    @RequestMapping(value = "/command/list.json", method = RequestMethod.GET)
    @ResponseBody
    public LoganResponse<List<LoganDeviceCommandDTO>> commandList(HttpServletRequest request) {
        String appId = request.getParameter("appId");
        if (StringUtils.isBlank(appId)) {
            return LoganResponse.badParam("appId required");
        }
        if (!AppGuard.registered(appService, appId)) {
            return LoganResponse.badParam(AppGuard.MSG);
        }
        try {
            return LoganResponse.success(deviceService.listCommands(appId.trim(),
                    request.getParameter("deviceId"), parseInt(request.getParameter("limit"))));
        } catch (Exception e) {
            LOGGER.error("list commands failed", e);
            return LoganResponse.exception("list failed: " + e.getMessage());
        }
    }

    private static String pick(Map<String, Object> body, HttpServletRequest request, String key) {
        if (body != null && body.get(key) != null) {
            String v = String.valueOf(body.get(key));
            if (StringUtils.isNotBlank(v)) {
                return v;
            }
        }
        return request.getParameter(key);
    }

    private static String headerOr(String value, String header) {
        return StringUtils.isNotBlank(value) ? value : header;
    }

    private static Long pickLong(Map<String, Object> body, HttpServletRequest request, String key) {
        String v = pick(body, request, key);
        if (StringUtils.isBlank(v)) {
            return null;
        }
        try {
            return Long.valueOf(v.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer pickInt(Map<String, Object> body, HttpServletRequest request, String key) {
        String v = pick(body, request, key);
        if (StringUtils.isBlank(v)) {
            return null;
        }
        try {
            return Integer.valueOf(v.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer parseInt(String v) {
        if (StringUtils.isBlank(v)) {
            return null;
        }
        try {
            return Integer.valueOf(v.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 客户端上送兼容三种姿势：JSON 体、application/x-www-form-urlencoded、查询串。
     * 早期用 @RequestBody Map 时表单上送会被 Spring 判 415，故手动读取。
     */
    private static Map<String, Object> readBody(HttpServletRequest request) {
        Map<String, Object> map = new HashMap<String, Object>();
        String ctype = StringUtils.defaultString(request.getContentType()).toLowerCase();
        if (ctype.contains("json")) {
            try {
                StringBuilder sb = new StringBuilder();
                BufferedReader reader = request.getReader();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                if (sb.length() > 0) {
                    JSONObject obj = JSON.parseObject(sb.toString());
                    if (obj != null) {
                        map.putAll(obj);
                    }
                }
            } catch (Exception e) {
                LOGGER.warn("parse json body failed: " + e.getMessage());
            }
        }
        return map;
    }

    /** body 优先，其次请求参数，最后 header。 */
    private static String pick3(Map<String, Object> body, HttpServletRequest request, String key) {
        if (body != null && body.get(key) != null) {
            String v = String.valueOf(body.get(key));
            if (StringUtils.isNotBlank(v)) {
                return v;
            }
        }
        String p = request.getParameter(key);
        if (StringUtils.isNotBlank(p)) {
            return p;
        }
        return request.getHeader(key);
    }
}
