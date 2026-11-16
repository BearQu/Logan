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
            if (dto == null) {
                return LoganResponse.badParam(com.meituan.logan.web.util.AppGuard.MSG);
            }
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

    /**
     * 平台侧创建应用（治理入口）：一个端一个 appId，创建后各端 SDK 持各的 appId 上报。
     * 与 save.json 的区别：本接口只做「新建」，appId 已存在会明确拒绝，避免误覆盖人工登记的资料。
     * body: {"appId","appName","platform"}，platform 1=Android 2=iOS 3=HarmonyOS。
     */
    @PostMapping("/create.json")
    @ResponseBody
    public LoganResponse<LoganAppDTO> create(@RequestBody(required = false) java.util.Map<String, Object> body,
            HttpServletRequest request) {
        String appId = pick(body, request, "appId");
        if (StringUtils.isBlank(appId)) {
            return LoganResponse.badParam("appId required");
        }
        String appName = pick(body, request, "appName");
        if (StringUtils.isBlank(appName)) {
            return LoganResponse.badParam("appName required");
        }
        Integer platform = parseInt(pick(body, request, "platform"));
        if (platform == null || platform < 1 || platform > 3) {
            return LoganResponse.badParam("platform must be 1(Android)/2(iOS)/3(HarmonyOS)");
        }
        String id = appId.trim();
        if (!id.matches("[A-Za-z0-9_.\\-]{3,128}")) {
            return LoganResponse.badParam("appId only allows letters, digits, dot, dash, underscore (3-128)");
        }
        try {
            if (appService.get(id) != null) {
                return LoganResponse.badParam("appId already exists: " + id);
            }
            LoganAppDTO dto = appService.createApp(id, appName, platform);
            if (dto == null) {
                return LoganResponse.exception("create app failed");
            }
            return LoganResponse.success(dto);
        } catch (Exception e) {
            LOGGER.error("create app failed: " + e.getMessage());
            return LoganResponse.exception("create app failed");
        }
    }

    /**
     * appId 预生成（用户要求：登记之后自动生成，不让人手填）。
     * 传包名 + 所属端，平台按「包名 + 端后缀」派生 appId 并查重；冲突时追加端标记后缀，
     * 前端只读展示这个值，用户确认即可创建。
     * body/参数: {packageName, platform} 或 {appId}（纯查重）。
     */
    @PostMapping("/prepare.json")
    @ResponseBody
    public LoganResponse<java.util.Map<String, Object>> prepare(@RequestBody(required = false) java.util.Map<String, Object> body,
            HttpServletRequest request) {
        String explicit = StringUtils.trimToEmpty(pick(body, request, "appId"));
        Integer platform = parseInt(pick(body, request, "platform"));
        String packageName = StringUtils.trimToEmpty(pick(body, request, "packageName"));
        java.util.Map<String, Object> result = new java.util.HashMap<String, Object>();
        String candidate;
        if (StringUtils.isNotBlank(explicit)) {
            candidate = explicit;
        } else {
            if (StringUtils.isBlank(packageName)) {
                return LoganResponse.badParam("packageName or appId required");
            }
            if (platform == null || platform < 1 || platform > 3) {
                return LoganResponse.badParam("platform must be 1(Android)/2(iOS)/3(HarmonyOS)");
            }
            String pkg = packageName.replaceAll("[^A-Za-z0-9_.\\-]", "");
            if (pkg.length() < 3) {
                return LoganResponse.badParam("packageName looks invalid: " + packageName);
            }
            String suffix = platform.intValue() == 1 ? ".android" : (platform.intValue() == 2 ? ".ios" : ".harmony");
            // 已带正确后缀就不重复追加
            candidate = pkg.toLowerCase().endsWith(suffix) ? pkg : pkg + suffix;
        }
        if (!candidate.matches("[A-Za-z0-9_.\\\\-]{3,128}")) {
            return LoganResponse.badParam("appId only allows letters, digits, dot, dash, underscore (3-128)");
        }
        String finalId = candidate;
        int round = 0;
        try {
            // 撞名则加端标记，最多让 20 次，全部由平台决定，用户不需要手填
            while (appService.get(finalId) != null) {
                round++;
                if (round > 20) {
                    return LoganResponse.exception("too many apps on this package name, register manually");
                }
                finalId = candidate + "-" + round;
            }
        } catch (Exception e) {
            return LoganResponse.exception("prepare appId failed: " + e.getMessage());
        }
        result.put("appId", finalId);
        result.put("suggested", candidate);
        result.put("existed", Boolean.valueOf(round > 0 || !finalId.equals(candidate)));
        result.put("renamed", Boolean.valueOf(!finalId.equals(candidate)));
        result.put("platform", platform);
        result.put("packageName", StringUtils.trimToNull(packageName));
        return LoganResponse.success(result);
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
