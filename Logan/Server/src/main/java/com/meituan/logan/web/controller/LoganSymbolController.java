package com.meituan.logan.web.controller;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.meituan.logan.web.dto.LoganSymbolDTO;
import com.meituan.logan.web.model.response.LoganResponse;
import com.meituan.logan.web.service.LoganAppService;
import com.meituan.logan.web.service.LoganSymbolService;
import com.meituan.logan.web.util.AppGuard;
import com.meituan.logan.web.util.HeaderUtil;
import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.Logger;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 符号表（用户要求：崩溃解析应有上传符号表的位置）。
 * 上传走 JSON base64（与图标同一姿势，容器无需 multipart 配置）；控制台侧接口靠 nginx basic-auth 保护，
 * 客户端侧（source=gradle/dsh-skill）要求 appId 已登记。
 */
@Controller
@RequestMapping(
"/logan/symbol")
public class LoganSymbolController {

    private static final Logger LOGGER = Logger.getLogger(LoganSymbolController.class);

    @Resource
    private LoganSymbolService symbolService;

    @Resource
    private LoganAppService appService;

    /** 上传符号表。body: appId/appName?/platform/appVersion/buildVersion/kind/fileName/contentBase64/note/uploader/source */
    @RequestMapping(value = 
"/upload.json", method = RequestMethod.POST)
    @ResponseBody
    public LoganResponse<Map<String, Object>> upload(HttpServletRequest request) {
        Map<String, Object> body = readBody(request);
        String appId = pick(body, request, 
"appId");
        if (StringUtils.isBlank(appId)) {
            return LoganResponse.badParam(
"appId required");
        }
        if (!AppGuard.registered(appService, appId.trim())) {
            return LoganResponse.badParam(AppGuard.MSG);
        }
        String kind = pick(body, request, 
"kind");
        if (StringUtils.isBlank(kind)) {
            return LoganResponse.badParam(
"kind required (mapping/dsym/so/sourcemap)");
        }
        String contentBase64 = pick(body, request, 
"contentBase64");
        if (StringUtils.isBlank(contentBase64)) {
            return LoganResponse.badParam(
"contentBase64 required");
        }
        Integer platform = parseInt(pick(body, request, 
"platform"));
        try {
            Map<String, Object> r = symbolService.upload(appId.trim(), platform,
                    pick(body, request, 
"appVersion"),
                    pick(body, request, 
"buildVersion"),
                    kind.trim(),
                    pick(body, request, 
"fileName"),
                    contentBase64,
                    pick(body, request, 
"note"),
                    StringUtils.defaultIfBlank(pick(body, request, 
"uploader"), 
"console"),
                    StringUtils.defaultIfBlank(pick(body, request, 
"source"), 
"console"));
            if (r.containsKey(
"error")) {
                return LoganResponse.badParam(String.valueOf(r.get(
"error")));
            }
            return LoganResponse.success(r);
        } catch (Exception e) {
            LOGGER.error(
"upload symbol failed", e);
            return LoganResponse.exception(
"upload symbol failed: " + e.getMessage());
        }
    }

    /** 列出某应用已归档符号表（控制台展示）。 */
    @RequestMapping(value = 
"/list.json", method = { RequestMethod.GET, RequestMethod.POST })
    @ResponseBody
    public LoganResponse<List<LoganSymbolDTO>> list(HttpServletRequest request) {
        String appId = pick(null, request, 
"appId");
        if (StringUtils.isBlank(appId)) {
            return LoganResponse.badParam(
"appId required");
        }
        return LoganResponse.success(symbolService.list(appId.trim(), pick(null, request, 
"appVersion"),
                pick(null, request, 
"kind"), pickInt(null, request, 
"limit")));
    }

    /** 下载原始符号文件（供本地 atos / addr2line 使用）。 */
    @RequestMapping(value = 
"/download.json", method = RequestMethod.GET)
    public ResponseEntity<byte[]> download(HttpServletRequest request) {
        HttpHeaders headers = new HttpHeaders();
        try {
            String idText = request.getParameter(
"id");
            if (StringUtils.isBlank(idText)) {
                return new ResponseEntity<byte[]>(new byte[0], headers, HttpStatus.BAD_REQUEST);
            }
            LoganSymbolDTO dto = symbolService.get(Long.parseLong(idText.trim()));
            File file = symbolService.fileOf(dto);
            if (file == null) {
                return new ResponseEntity<byte[]>(new byte[0], headers, HttpStatus.NOT_FOUND);
            }
            byte[] bytes = new byte[(int) Math.min(file.length(), 4L * 1024 * 1024)];
            FileInputStream fis = new FileInputStream(file);
            int read = 0;
            while (read < bytes.length) {
                int n = fis.read(bytes, read, bytes.length - read);
                if (n <= 0) {
                    break;
                }
                read += n;
            }
            fis.close();
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
            String name = StringUtils.defaultIfBlank(dto.getFileName(), 
"symbol.bin");
            headers.setContentDispositionFormData(
"attachment", name);
            return new ResponseEntity<byte[]>(bytes, headers, HttpStatus.OK);
        } catch (Exception e) {
            LOGGER.warn(
"download symbol failed: " + e.getMessage());
            return new ResponseEntity<byte[]>(new byte[0], headers, HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @RequestMapping(value = 
"/delete.json", method = RequestMethod.POST)
    @ResponseBody
    public LoganResponse<Boolean> delete(HttpServletRequest request) {
        try {
            String idText = pick(readBody(request), request, 
"id");
            if (StringUtils.isBlank(idText)) {
                return LoganResponse.badParam(
"id required");
            }
            boolean ok = symbolService.delete(Long.parseLong(idText.trim()));
            return ok ? LoganResponse.success(Boolean.TRUE)
                    : LoganResponse.badParam(
"symbol not found");
        } catch (Exception e) {
            LOGGER.warn(
"delete symbol failed: " + e.getMessage());
            return LoganResponse.exception(
"delete failed");
        }
    }

    /** 对该崩溃套用其版本最新的 mapping，返回还原后的堆栈（平台侧真反混淆，不伪造）。 */
    @RequestMapping(value = 
"/apply.json", method = { RequestMethod.GET, RequestMethod.POST })
    @ResponseBody
    public LoganResponse<Map<String, Object>> apply(HttpServletRequest request) {
        try {
            String crashIdText = pick(readBody(request), request, 
"crashId");
            if (StringUtils.isBlank(crashIdText)) {
                return LoganResponse.badParam(
"crashId required");
            }
            Map<String, Object> r = symbolService.applyMapping(Long.parseLong(crashIdText.trim()));
            if (r.containsKey(
"error") && !r.containsKey(
"hasMapping")) {
                return LoganResponse.badParam(String.valueOf(r.get(
"error")));
            }
            return LoganResponse.success(r);
        } catch (Exception e) {
            LOGGER.warn(
"apply mapping failed: " + e.getMessage());
            return LoganResponse.exception(
"apply mapping failed");
        }
    }

    private static String pick(Map<String, Object> body, HttpServletRequest request, String key) {
        if (body != null && body.get(key) != null) {
            String v = String.valueOf(body.get(key));
            if (StringUtils.isNotBlank(v) && !
"null".equals(v)) {
                return v;
            }
        }
        String p = request.getParameter(key);
        if (StringUtils.isNotBlank(p)) {
            return p;
        }
        return HeaderUtil.utf8(request.getHeader(key));
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

    /** 兼容 JSON 体与表单/查询串（早期 @RequestBody 会让表单上送 415）。 */
    private static Map<String, Object> readBody(HttpServletRequest request) {
        Map<String, Object> map = new HashMap<String, Object>();
        String ctype = StringUtils.defaultString(request.getContentType()).toLowerCase();
        if (ctype.contains(
"json")) {
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
                LOGGER.warn(
"parse json body failed: " + e.getMessage());
            }
        }
        return map;
    }
}
