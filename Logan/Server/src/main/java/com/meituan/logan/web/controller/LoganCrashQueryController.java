package com.meituan.logan.web.controller;

import com.meituan.logan.web.dto.LoganCrashDTO;
import com.meituan.logan.web.dto.LoganCrashIssueDTO;
import com.meituan.logan.web.mapper.LoganCrashMapper;
import com.meituan.logan.web.model.response.LoganResponse;
import com.meituan.logan.web.service.LoganCrashService;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;

/** 崩溃查询 API：LoganSite 崩溃维度页与验收脚本用。 */
@CrossOrigin(origins = "*", maxAge = 3600)
@Controller
@RequestMapping("/logan/crash")
public class LoganCrashQueryController {
    private static final int DEFAULT_LIMIT = 20;

    @Resource
    private LoganCrashService crashService;
    @Resource
    private LoganCrashMapper crashMapper;

    @GetMapping("/latest.json")
    @ResponseBody
    public LoganResponse<List<LoganCrashDTO>> latest(String appId) {
        return LoganResponse.success(crashService.latest(appId, DEFAULT_LIMIT));
    }

    @GetMapping("/device.json")
    @ResponseBody
    public LoganResponse<List<LoganCrashDTO>> byDevice(String deviceId, Integer limit) {
        if (StringUtils.isEmpty(deviceId)) {
            return LoganResponse.badParam("deviceId required");
        }
        return LoganResponse.success(crashService.latestByDevice(deviceId, limit == null ? DEFAULT_LIMIT : limit));
    }

    @GetMapping("/{crashId}/info.json")
    @ResponseBody
    public LoganResponse<LoganCrashDTO> info(@PathVariable("crashId") long crashId) {
        LoganCrashDTO dto = crashService.getById(crashId);
        return dto == null ? LoganResponse.badParam("not found") : LoganResponse.success(dto);
    }

    @GetMapping("/issues/latest.json")
    @ResponseBody
    public LoganResponse<java.util.Map<String, Object>> issues(String appId, Integer limit) {
        java.util.Map<String, Object> data = new java.util.HashMap<>();
        try {
            List<LoganCrashIssueDTO> issues = crashMapper.queryRecentIssues(appId, limit == null ? 30 : limit);
            data.put("issues", issues);
            data.put("total", crashMapper.countAll(appId));
            return LoganResponse.success(data);
        } catch (Exception e) {
            return LoganResponse.exception(e.getMessage());
        }
    }
}
