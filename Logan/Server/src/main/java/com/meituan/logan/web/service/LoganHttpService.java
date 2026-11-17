package com.meituan.logan.web.service;

import com.meituan.logan.web.dto.LoganHttpDTO;
import com.meituan.logan.web.dto.LoganHttpIssueDTO;

import java.util.List;
import java.util.Map;

// 网络请求观测（慢请求 / 失败请求）服务端能力：接收、归一化、聚合、查询。
public interface LoganHttpService {

    // 客户端批量上报。返回 accepted / duplicated / aggregated。
    Map<String, Object> report(String appId, String deviceId, Integer platform, String appVersion,
            String buildVersion, String os, String osVersion, String brand, String model,
            String networkType, List<Map<String, Object>> events);

    Map<String, Object> list(String appId, String deviceId, String kind, String host, String path,
            String errKind, Integer minStatus, Integer maxStatus, Boolean slowOnly,
            Long beginTime, Long endTime, String keyword, Integer limit);

    Map<String, Object> summary(String appId, Long beginTime, Long endTime);

    List<LoganHttpDTO> around(String appId, String deviceId, Long beginTime, Long endTime, Integer limit);

    Map<String, Object> issues(String appId, Integer statusFlag, String kind, String errKind, Integer limit);

    LoganHttpIssueDTO issueDetail(Long id);

    // 人工标记处置状态；statusFlag=2 视为已修复，同指纹再命中会累加 reopen。
    boolean updateIssueStatus(Long id, Integer statusFlag, String lastFixNote);
}

