package com.meituan.logan.web.service;

import com.meituan.logan.web.dto.LoganErrorDTO;
import com.meituan.logan.web.dto.LoganErrorIssueDTO;

import java.util.List;
import java.util.Map;

// 非致命错误（错误分析）服务端能力：接收、归一化、聚合、查询。
// 与崩溃的区别：不杀进程的可捕获异常，上报后 App 继续运行。
public interface LoganErrorService {

    // 客户端批量上报。返回 accepted / duplicated / rejected。
    Map<String, Object> report(String appId, String deviceId, Integer platform, String appVersion,
            String buildVersion, String os, String osVersion, String brand, String model,
            String networkType, List<Map<String, Object>> events);

    Map<String, Object> list(String appId, String deviceId, String kind, String name,
            Long beginTime, Long endTime, String keyword, Integer limit);

    Map<String, Object> summary(String appId, Long beginTime, Long endTime,
            Integer platform, String appVersion);

    // 崩溃 / 某时刻前后同设备的错误，用于互证。
    List<LoganErrorDTO> around(String appId, String deviceId, Long beginTime, Long endTime, Integer limit);

    Map<String, Object> issues(String appId, Integer statusFlag, String kind, Integer platform,
            String assignee, String keyword, Integer limit);

    LoganErrorIssueDTO issueDetail(Long id);

    /** 概览页错误榜：按错误名聚成 Top N。 */
    List<Map<String, Object>> topNames(String appId, Long beginTime, Long endTime,
            Integer platform, String appVersion, Integer limit);

    // 人工标记处置状态；statusFlag=2 视为已修复，同指纹再命中会重开并保留历史结论。
    boolean updateIssueStatus(Long id, Integer statusFlag, String lastFixNote);
}
