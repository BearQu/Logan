package com.meituan.logan.web.controller;

import com.meituan.logan.web.dto.LoganAppDTO;
import com.meituan.logan.web.model.response.LoganResponse;
import com.meituan.logan.web.service.LoganAppService;
import com.meituan.logan.web.util.AppFileUtil;
import com.meituan.logan.web.util.HeaderUtil;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.Logger;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.io.File;
import java.util.List;

/**
 * 应用维度：自动登记后的应用清单 + 客户端主动上报应用资料（包名/应用名/图标）。
 * 平台侧无需任何手工录入。
 */
@CrossOrigin(origins = "*", maxAge = 3600)
@Controller
@RequestMapping("/logan/app")
public class LoganAppController {
    private static final Logger LOGGER = Logger.getLogger(LoganAppController.class);

    @Resource
    private LoganAppService appService;

    /** 控制台应用清单（含崩溃数/问题数/设备数/日志数）。 */
    @GetMapping("/list.json")
    @ResponseBody
    public LoganResponse<List<LoganAppDTO>> list() {
        return LoganResponse.success(appService.list());
    }

    @GetMapping("/info.json")
    @ResponseBody
    public LoganResponse<LoganAppDTO> info(String appId) {
        if (StringUtils.isBlank(appId)) {
            return LoganResponse.badParam("appId required");
        }
        return LoganResponse.success(appService.get(appId));
    }

    /**
     * 客户端上报应用资料（也可在日志/崩溃上报的 header 里被动登记）。
     * body 支持两种：JSON {"appId","appName","iconBase64","mime","platform",...}
     * 或表单参数（同名字段）。
     */
    @PostMapping("/save.json")
    @ResponseBody
    public LoganResponse<LoganAppDTO> save(@RequestBody(required = false) java.util.Map<String, Object> body,
            HttpServletRequest request) {
        String appId = pick(body, request, "appId");
        if (StringUtils.isBlank(appId)) {
            appId = request.getHeader("appId");
        }
        if (StringUtils.isBlank(appId)) {
            return LoganResponse.badParam("appId required");
        }
        try {
            LoganAppDTO dto = appService.reportProfile(appId,
                    pick(body, request, "appName"),
                    pick(body, request, "iconBase64"),
                    pick(body, request, "mime"),
                    parseInt(pick(body, request, "platform")),
                    pick(body, request, "appVersion"),
                    pick(body, request, "buildVersion"),
                    pick(body, request, "deviceId"),
                    pick(body, request, "source"));
            return LoganResponse.success(dto);
        } catch (Exception e) {
            LOGGER.error("save app profile failed: " + e.getMessage());
            return LoganResponse.exception("save app profile failed");
        }
    }

    /** 图标读回（控制台展示用）。 */
    @GetMapping("/icon.json")
    public ResponseEntity<byte[]> icon(String appId) {
        LoganAppDTO dto = StringUtils.isBlank(appId) ? null : appService.get(appId);
        File file = dto == null ? null : AppFileUtil.resolve(dto.getIconPath());
        HttpHeaders headers = new HttpHeaders();
        if (file == null) {
            headers.setContentType(MediaType.IMAGE_PNG);
            return new ResponseEntity<>(new byte[0], headers, HttpStatus.NOT_FOUND);
        }
        try {
            String name = file.getName().toLowerCase();
            headers.setContentType(name.endsWith(".jpg") || name.endsWith(".jpeg") ? MediaType.IMAGE_JPEG
                    : name.endsWith(".gif") ? MediaType.IMAGE_GIF : MediaType.IMAGE_PNG);
            headers.setCacheControl("max-age=86400");
            return new ResponseEntity<>(FileUtils.readFileToByteArray(file), headers, HttpStatus.OK);
        } catch (Exception e) {
            return new ResponseEntity<>(new byte[0], headers, HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private static String pick(java.util.Map<String, Object> body, HttpServletRequest request, String key) {
        if (body != null && body.get(key) != null) {
            return String.valueOf(body.get(key));
        }
        String v = request.getParameter(key);
        if (StringUtils.isNotBlank(v)) {
            return v;
        }
        // 表单没有再看 header（客户端常用 header 带元信息），中文按 UTF-8 还原
        return HeaderUtil.utf8(request.getHeader(key));
    }

    private static Integer parseInt(String s) {
        try {
            return StringUtils.isBlank(s) ? null : Integer.valueOf(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
