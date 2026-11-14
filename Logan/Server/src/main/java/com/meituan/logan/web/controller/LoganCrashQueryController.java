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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    /** 崩溃实例列表（不含堆栈正文）：点进问题后的「活跃设备」列表。 */
    @GetMapping("/instances.json")
    @ResponseBody
    public LoganResponse<List<LoganCrashDTO>> instances(String appId, String fingerprint, Integer limit) {
        if (StringUtils.isEmpty(appId) || StringUtils.isEmpty(fingerprint)) {
            return LoganResponse.badParam("appId and fingerprint required");
        }
        int n = limit == null || limit <= 0 ? 30 : Math.min(limit, 200);
        try {
            return LoganResponse.success(crashMapper.queryInstancesByFp(appId, fingerprint, n));
        } catch (Exception e) {
            return LoganResponse.exception(e.getMessage());
        }
    }

    /** KPI 概览：总数/影响设备/未处理数，按 kind 细分。 */
    @GetMapping("/stats.json")
    @ResponseBody
    public LoganResponse<Map<String, Object>> stats(String appId) {
        try {
            Map<String, Object> data = new HashMap<>();
            List<Map<String, Object>> kinds = crashMapper.queryKindSummary(appId);
            long total = 0, devices = 0;
            List<Map<String, Object>> kindList = new ArrayList<>();
            for (Map<String, Object> row : kinds) {
                Map<String, Object> item = new LinkedHashMap<>();
                long cnt = row.get("total") == null ? 0 : ((Number) row.get("total")).longValue();
                item.put("kind", row.get("kind"));
                item.put("total", cnt);
                item.put("issues", row.get("issues"));
                item.put("devices", row.get("devices"));
                item.put("lastCrashTime", row.get("lastCrashTime"));
                total += cnt;
                kindList.add(item);
            }
            data.put("total", total);
            data.put("kinds", kindList);
            try {
                List<LoganCrashIssueDTO> unresolved = crashMapper.queryRecentIssues(appId, 500);
                int pending = 0;
                for (LoganCrashIssueDTO i : unresolved) { if (i.getStatus() == 0) pending++; }
                data.put("unresolvedIssues", pending);
                data.put("issueTotal", unresolved.size());
            } catch (Exception ignore) { }
            return LoganResponse.success(data);
        } catch (Exception e) {
            return LoganResponse.exception(e.getMessage());
        }
    }

    /** 按天趋势：beginTime/endTime 毫秒，缺省近 7 天。 */
    @GetMapping("/trend.json")
    @ResponseBody
    public LoganResponse<List<Map<String, Object>>> trend(String appId, Long beginTime, Long endTime) {
        long end = endTime == null ? System.currentTimeMillis() : endTime;
        long begin = beginTime == null ? end - 7L * 24 * 3600 * 1000 : beginTime;
        try {
            return LoganResponse.success(crashMapper.queryDailyTrend(appId, begin, end));
        } catch (Exception e) {
            return LoganResponse.exception(e.getMessage());
        }
    }

    /** 更新处理状态：0 未处理 1 处理中 2 已修复 3 忽略。 */
    @GetMapping("/issues/status.json")
    @ResponseBody
    public LoganResponse<String> updateStatus(Long id, Integer status) {
        if (id == null || status == null || status < 0 || status > 3) {
            return LoganResponse.badParam("id and status(0-3) required");
        }
        try {
            crashMapper.updateIssueStatus(id, status);
            return LoganResponse.success("ok");
        } catch (Exception e) {
            return LoganResponse.exception(e.getMessage());
        }
    }

    /** 单条问题详情：按 issue id。 */
    @GetMapping("/issues/detail.json")
    @ResponseBody
    public LoganResponse<LoganCrashIssueDTO> issueDetail(Long id) {
        if (id == null) {
            return LoganResponse.badParam("id required");
        }
        try {
            LoganCrashIssueDTO issue = crashMapper.selectIssueById(id);
            return issue == null ? LoganResponse.badParam("not found") : LoganResponse.success(issue);
        } catch (Exception e) {
            return LoganResponse.exception(e.getMessage());
        }
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
