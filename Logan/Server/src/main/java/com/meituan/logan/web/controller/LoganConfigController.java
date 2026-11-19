package com.meituan.logan.web.controller;

import com.meituan.logan.web.dto.LoganConfigDTO;
import com.meituan.logan.web.model.response.LoganResponse;
import com.meituan.logan.web.service.LoganAppService;
import com.meituan.logan.web.service.LoganConfigService;
import com.meituan.logan.web.util.AppGuard;
import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.Logger;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * 远程配置下发（对标 Bugly 收费版，PoC 净新增）。
 * 客户端通道 get.json：AppGuard 登记校验，带本地 version 拉增量（{version,configs,removed}）。
 * 控制台通道 list/save/delete：登录网关后（nginx basic），供平台配置管理页 CRUD。
 */
@CrossOrigin(origins = "*", maxAge = 3600)
@Controller
@RequestMapping("/logan/config")
public class LoganConfigController {
    private static final Logger LOGGER = Logger.getLogger(LoganConfigController.class);
    private static final String KEY_PATTERN = "[A-Za-z0-9_.\\-]{1,128}";

    @Resource
    private LoganConfigService configService;
    @Resource
    private LoganAppService appService;

    /** SDK 拉取（增量）：body（gzip 或明文 JSON）或 query 带 appId + sinceVersion(缺省0)。 */
    @RequestMapping(value = "/get.json", method = {RequestMethod.GET, RequestMethod.POST})
    @ResponseBody
    public LoganResponse<Map<String, Object>> get(HttpServletRequest request) {
        Map<String, Object> body = readBody(request);
        String appId = pick(body, request, "appId");
        if (StringUtils.isBlank(appId)) {
            return LoganResponse.badParam("appId required");
        }
        if (!AppGuard.registered(appService, appId)) {
            return LoganResponse.badParam(AppGuard.MSG);
        }
        long sinceVersion = parseLong(pick(body, request, "sinceVersion"), 0L);
        try {
            return LoganResponse.success(configService.fetch(appId.trim(), sinceVersion));
        } catch (Exception e) {
            LOGGER.error("config get failed: " + e.getMessage());
            return LoganResponse.exception("config get failed");
        }
    }

    /** 控制台列表（含墓碑，带 enabled/remark/version 供页面展示）。 */
    @GetMapping("/list.json")
    @ResponseBody
    public LoganResponse<List<LoganConfigDTO>> list(String appId) {
        if (StringUtils.isBlank(appId)) {
            return LoganResponse.badParam("appId required");
        }
        try {
            return LoganResponse.success(configService.list(appId.trim()));
        } catch (Exception e) {
            LOGGER.error("config list failed: " + e.getMessage());
            return LoganResponse.exception("config list failed");
        }
    }

    /** 控制台保存：body {appId,configKey,configValue,remark}，存在即覆盖并递增版本。 */
    @PostMapping("/save.json")
    @ResponseBody
    public LoganResponse<LoganConfigDTO> save(@RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest request) {
        String appId = pick(body, request, "appId");
        if (StringUtils.isBlank(appId)) {
            return LoganResponse.badParam("appId required");
        }
        if (appService.get(appId.trim()) == null) {
            return LoganResponse.badParam("app not found: " + appId);
        }
        String configKey = pick(body, request, "configKey");
        if (StringUtils.isBlank(configKey) || !configKey.trim().matches(KEY_PATTERN)) {
            return LoganResponse.badParam("configKey only allows letters, digits, dot, dash, underscore (1-128)");
        }
        String value = pick(body, request, "configValue");
        if (value != null && value.length() > 2048) {
            return LoganResponse.badParam("configValue too long (max 2048)");
        }
        try {
            return LoganResponse.success(configService.save(appId.trim(), configKey.trim(), value,
                    pick(body, request, "remark")));
        } catch (Exception e) {
            LOGGER.error("config save failed: " + e.getMessage());
            return LoganResponse.exception("config save failed");
        }
    }

    /** 控制台删除（墓碑，端上下一轮增量拉取后移除本地键）。 */
    @PostMapping("/delete.json")
    @ResponseBody
    public LoganResponse<Boolean> delete(@RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest request) {
        String appId = pick(body, request, "appId");
        String configKey = pick(body, request, "configKey");
        if (StringUtils.isBlank(appId) || StringUtils.isBlank(configKey)) {
            return LoganResponse.badParam("appId and configKey required");
        }
        try {
            configService.remove(appId.trim(), configKey.trim());
            return LoganResponse.success(Boolean.TRUE);
        } catch (Exception e) {
            LOGGER.error("config delete failed: " + e.getMessage());
            return LoganResponse.exception("config delete failed");
        }
    }

    private static String pick(Map<String, Object> body, HttpServletRequest request, String key) {
        if (body != null && body.get(key) != null) {
            return String.valueOf(body.get(key));
        }
        String v = request.getParameter(key);
        if (StringUtils.isNotBlank(v)) {
            return v;
        }
        return request.getHeader(key);
    }

    private static long parseLong(String s, long def) {
        if (StringUtils.isBlank(s)) {
            return def;
        }
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }


    /** gzip 感知读 body（与行为/崩溃通道同一套 SDK 传输约定），非 JSON 返回 null 走 query/header 回落。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> readBody(HttpServletRequest request) {
        try {
            String encoding = request.getHeader("Content-Encoding");
            InputStream in = encoding != null && encoding.contains("gzip")
                    ? new GZIPInputStream(request.getInputStream())
                    : request.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            int total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > 4 * 1024 * 1024) {
                    return null;
                }
                bos.write(buf, 0, n);
            }
            String raw = bos.toString("UTF-8").trim();
            if (raw.length() == 0 || raw.charAt(0) != '{') {
                return null;
            }
            return com.alibaba.fastjson.JSON.parseObject(raw, Map.class);
        } catch (Exception e) {
            return null;
        }
    }
}
