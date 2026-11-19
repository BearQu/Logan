package com.meituan.logan.web.service;

import java.util.List;
import java.util.Map;

// 崩溃前行为轨迹：上报入库 + 时间窗取数 + 多维筛选。
public interface LoganBehaviorService {

    Map<String, Object> report(String appId, String deviceId, Integer platform, String appVersion,
            String buildVersion, String os, String osVersion, String brand, String model,
            String networkType, List<Map<String, Object>> events);

    Map<String, Object> list(String appId, String deviceId, String kind, String name,
            Long beginTime, Long endTime, String keyword, Integer limit);

    Map<String, Object> summary(String appId, Long beginTime, Long endTime, Integer platform,
            String appVersion);

    Map<String, Object> topNames(String appId, Long beginTime, Long endTime, String kind,
            Integer platform, Integer limit);

    // 崩溃前后同设备的轨迹（互证用，控制器也可查）。
    Map<String, Object> around(String appId, String deviceId, long crashTime, Integer beforeSeconds,
            Integer afterSeconds, Integer limit);
}