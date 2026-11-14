package com.meituan.logan.web.controller;

import com.alibaba.fastjson.JSONObject;
import com.meituan.logan.web.dto.LoganCrashFixDTO;
import com.meituan.logan.web.model.response.LoganResponse;
import com.meituan.logan.web.service.LoganCrashEvidenceService;
import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.Logger;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 证据包导出与修复报告回传。
 *
 * <p>设计边界（用户定案）：平台不接触任何源码。平台只做两件事——把崩溃+日志+AI 初判+历史修复
 * 打包导出（多维筛选），以及接收本地（DSH）分析完成后回传的修复报告。同指纹再次上报会自动重开
 * 并携带历史修复内容与证据。
 */
@CrossOrigin(origins = "*", maxAge = 3600)
@Controller
@RequestMapping("/logan/crash")
public class LoganCrashEvidenceController {
    private static final Logger LOGGER = Logger.getLogger(LoganCrashEvidenceController.class);

    @Resource
    private LoganCrashEvidenceService evidenceService;

    // ---------------- 证据包 ----------------

    /** 单条崩溃证据包：崩溃原文 + 设备信息 + 崩溃前后业务日志 + AI 初判 + 历史修复。 */
    @GetMapping("/evidence.json")
    @ResponseBody
    public LoganResponse<JSONObject> evidence(Long crashId, Long issueId, Integer before, Integer after, Integer instanceLimit) {
        try {
            JSONObject bundle = null;
            if (crashId != null && crashId > 0) {
                bundle = evidenceService.evidenceForCrash(crashId, nz(before), nz(after));
            } else if (issueId != null && issueId > 0) {
                bundle = evidenceService.evidenceForIssue(issueId, nz(before), nz(after),
                        instanceLimit == null ? 10 : instanceLimit);
            }
            return bundle == null ? LoganResponse.badParam("crashId or issueId required") : LoganResponse.success(bundle);
        } catch (Exception e) {
            LOGGER.warn("evidence build failed: " + e.getMessage());
            return LoganResponse.exception(e.getMessage());
        }
    }

    /** 证据包下载（附件，文件名带应用与时间，可直接喂给本地 Agent）。 */
    @GetMapping("/evidence.download")
    public ResponseEntity<byte[]> evidenceDownload(Long crashId, Long issueId, Integer before, Integer after, Integer instanceLimit) {
        JSONObject bundle = null;
        try {
            if (crashId != null && crashId > 0) {
                bundle = evidenceService.evidenceForCrash(crashId, nz(before), nz(after));
            } else if (issueId != null && issueId > 0) {
                bundle = evidenceService.evidenceForIssue(issueId, nz(before), nz(after),
                        instanceLimit == null ? 10 : instanceLimit);
            }
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(("error: " + e.getMessage()).getBytes(StandardCharsets.UTF_8));
        }
        if (bundle == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("crashId or issueId required".getBytes(StandardCharsets.UTF_8));
        }
        return download(bundle.toJSONString(), "logan-evidence-" + stamp() + ".json");
    }

    /**
     * 多维筛选批量导出证据包（本地 Agent 一次吃一批）。
     * 参数与综合搜索一致：appId/kind/brand/model/deviceId/unionId/appVersion/buildVersion/beginTime/endTime/keyword/status/issueKeyword + maxIssues。
     */
    @GetMapping("/export.json")
    @ResponseBody
    public LoganResponse<JSONObject> exportBundle(
            @RequestParam(required = false) String appId,
            @RequestParam(required = false) String kind,
            @RequestParam(required = false) String brand,
            @RequestParam(required = false) String model,
            @RequestParam(required = false) String deviceId,
            @RequestParam(required = false) String unionId,
            @RequestParam(required = false) String appVersion,
            @RequestParam(required = false) String buildVersion,
            @RequestParam(required = false) String beginTime,
            @RequestParam(required = false) String endTime,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String issueKeyword,
            @RequestParam(required = false) Integer maxIssues,
            @RequestParam(required = false) Integer before,
            @RequestParam(required = false) Integer after) {
        try {
            Map<String, Object> q = buildFilter(appId, kind, brand, model, deviceId, unionId, appVersion,
                    buildVersion, beginTime, endTime, keyword, status, issueKeyword);
            if (!q.containsKey("appId")) {
                return LoganResponse.badParam("appId required for export");
            }
            return LoganResponse.success(evidenceService.exportBundle(q,
                    maxIssues == null ? 10 : maxIssues, nz(before), nz(after)));
        } catch (Exception e) {
            LOGGER.warn("export failed: " + e.getMessage());
            return LoganResponse.exception(e.getMessage());
        }
    }

    /** 多维筛选批量导出为附件下载。 */
    @GetMapping("/export.download")
    public ResponseEntity<byte[]> exportDownload(
            @RequestParam(required = false) String appId,
            @RequestParam(required = false) String kind,
            @RequestParam(required = false) String brand,
            @RequestParam(required = false) String model,
            @RequestParam(required = false) String deviceId,
            @RequestParam(required = false) String unionId,
            @RequestParam(required = false) String appVersion,
            @RequestParam(required = false) String buildVersion,
            @RequestParam(required = false) String beginTime,
            @RequestParam(required = false) String endTime,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String issueKeyword,
            @RequestParam(required = false) Integer maxIssues,
            @RequestParam(required = false) Integer before,
            @RequestParam(required = false) Integer after) {
        try {
            Map<String, Object> q = buildFilter(appId, kind, brand, model, deviceId, unionId, appVersion,
                    buildVersion, beginTime, endTime, keyword, status, issueKeyword);
            if (!q.containsKey("appId")) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("appId required".getBytes(StandardCharsets.UTF_8));
            }
            JSONObject bundle = evidenceService.exportBundle(q,
                    maxIssues == null ? 10 : maxIssues, nz(before), nz(after));
            String name = "logan-evidence-" + safe(appId) + "-" + stamp() + ".json";
            return download(bundle.toJSONString(), name);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(("error: " + e.getMessage()).getBytes(StandardCharsets.UTF_8));
        }
    }

    // ---------------- 崩溃 ↔ 日志互证 ----------------

    /** 崩溃前后业务日志时间线（native + H5 合并），详情页「关联日志」区用。 */
    @GetMapping("/timeline.json")
    @ResponseBody
    public LoganResponse<Object> timeline(String appId, String deviceId, Long crashTime, Long crashId,
                                          Integer before, Integer after, Integer limit) {
        try {
            long ts = crashTime == null ? 0 : crashTime;
            String dev = deviceId;
            if ((StringUtils.isBlank(dev) || ts <= 0) && crashId != null && crashId > 0) {
                com.meituan.logan.web.dto.LoganCrashDTO c = null;
                try {
                    c = crashService.getById(crashId);
                } catch (Exception ignore) {
                }
                if (c != null) {
                    dev = StringUtils.isBlank(dev) ? c.getDeviceId() : dev;
                    if (ts <= 0) {
                        ts = c.getCrashTime();
                    }
                    if (StringUtils.isBlank(appId)) {
                        appId = c.getAppId();
                    }
                }
            }
            if (StringUtils.isBlank(dev) || ts <= 0) {
                return LoganResponse.badParam("deviceId+crashTime (or crashId) required");
            }
            Map<String, Object> data = new HashMap<>();
            data.put("deviceId", dev);
            data.put("crashTime", ts);
            data.put("logs", evidenceService.timeline(appId, dev, ts, nz(before), nz(after),
                    limit == null ? 200 : limit));
            return LoganResponse.success(data);
        } catch (Exception e) {
            LOGGER.warn("timeline failed: " + e.getMessage());
            return LoganResponse.exception(e.getMessage());
        }
    }

    /** 日志列表徽标用：某应用一段时间内有崩溃的设备+日期。 */
    @GetMapping("/days.json")
    @ResponseBody
    public LoganResponse<Object> days(String appId, String beginTime, String endTime) {
        try {
            Long bt = parseTime(beginTime);
            Long et = parseTime(endTime);
            long end = et == null ? System.currentTimeMillis() : et;
            long begin = bt == null ? end - 30L * 24 * 3600 * 1000 : bt;
            return LoganResponse.success(evidenceService.crashDays(appId, begin, end));
        } catch (Exception e) {
            return LoganResponse.exception(e.getMessage());
        }
    }

    // ---------------- 修复报告回传（本地 DSH 分析完成后调用）----------------

    /**
     * 回传修复报告。必填 appId / fingerprint / fixer；建议带 detail（改了什么）、status、
     * commitUrl、files、evidenceHash（导出时的哈希，串起证据链）。
     * action=reopen 或 status=fixed 会把「已修复」的问题重新打开（若之前标了已修复）或转处理中，
     * 并把报告摘要写入 last_fix_note，同指纹后续上报会带上这段历史。
     */
    @PostMapping("/fix/report.json")
    @ResponseBody
    public LoganResponse<JSONObject> reportFix(@RequestBody(required = false) String body,
                                               @RequestParam(required = false) String payload) {
        String json = StringUtils.isNotBlank(body) ? body : payload;
        if (StringUtils.isBlank(json)) {
            return LoganResponse.badParam("json body required");
        }
        try {
            JSONObject o = JSONObject.parseObject(json);
            if (o == null) {
                return LoganResponse.badParam("body not json");
            }
            JSONObject r = evidenceService.reportFix(o);
            return Boolean.TRUE.equals(r.get("ok")) ? LoganResponse.success(r) : LoganResponse.badParam(r.getString("msg"));
        } catch (Exception e) {
            LOGGER.warn("fix report failed: " + e.getMessage());
            return LoganResponse.exception(e.getMessage());
        }
    }

    /** 某问题/某指纹的历史修复报告（详情页「历史修复」卡用）。 */
    @GetMapping("/fixes.json")
    @ResponseBody
    public LoganResponse<List<LoganCrashFixDTO>> fixes(Long issueId, String appId, String fingerprint, Integer limit) {
        try {
            int n = limit == null ? 20 : limit;
            if (issueId != null && issueId > 0) {
                return LoganResponse.success(evidenceService.fixesOfIssue(issueId, n));
            }
            if (StringUtils.isNotBlank(appId) && StringUtils.isNotBlank(fingerprint)) {
                return LoganResponse.success(evidenceService.fixesOfFingerprint(appId, fingerprint, n));
            }
            return LoganResponse.badParam("issueId or (appId+fingerprint) required");
        } catch (Exception e) {
            return LoganResponse.exception(e.getMessage());
        }
    }

    // ---------------- helpers ----------------

    @Resource
    private com.meituan.logan.web.service.LoganCrashService crashService;

    private static ResponseEntity<byte[]> download(String json, String fileName) {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.parseMediaType("application/json;charset=UTF-8"));
        h.setContentLength(bytes.length);
        h.set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"");
        return new ResponseEntity<byte[]>(bytes, h, HttpStatus.OK);
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }

    private static String stamp() {
        return new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
    }

    private static String safe(String s) {
        return s == null ? "app" : s.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static Map<String, Object> buildFilter(String appId, String kind, String brand, String model,
                                                   String deviceId, String unionId, String appVersion,
                                                   String buildVersion, String beginTime, String endTime,
                                                   String keyword, Integer status, String issueKeyword) {
        Map<String, Object> q = new HashMap<String, Object>();
        if (StringUtils.isNotBlank(appId)) q.put("appId", appId.trim());
        if (StringUtils.isNotBlank(kind)) q.put("kind", kind.trim());
        if (StringUtils.isNotBlank(brand)) q.put("brand", brand.trim());
        if (StringUtils.isNotBlank(model)) q.put("model", model.trim());
        if (StringUtils.isNotBlank(deviceId)) q.put("deviceId", deviceId.trim());
        if (StringUtils.isNotBlank(unionId)) q.put("unionId", unionId.trim());
        if (StringUtils.isNotBlank(appVersion)) q.put("appVersion", appVersion.trim());
        if (StringUtils.isNotBlank(buildVersion)) q.put("buildVersion", buildVersion.trim());
        if (StringUtils.isNotBlank(keyword)) q.put("keyword", keyword.trim());
        Long bt = parseTime(beginTime);
        if (bt != null) q.put("beginTime", bt);
        Long et = parseTime(endTime);
        if (et != null) q.put("endTime", et);
        if (status != null) q.put("status", status);
        if (StringUtils.isNotBlank(issueKeyword)) q.put("issueKeyword", issueKeyword.trim());
        return q;
    }

    private static Long parseTime(String v) {
        if (StringUtils.isBlank(v)) return null;
        String t = v.trim();
        try {
            if (t.matches("\\d{12,14}")) return Long.parseLong(t);
            String norm = t.length() == 10 ? t + " 00:00:00" : t;
            return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse(norm).getTime();
        } catch (Exception e) {
            return null;
        }
    }
}
