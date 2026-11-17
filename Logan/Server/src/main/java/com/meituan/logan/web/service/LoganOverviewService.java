package com.meituan.logan.web.service;

import java.util.Map;

/** 异常概览看板（对标 Bugly 异常概览页）聚合查询服务。 */
public interface LoganOverviewService {

    /**
     * 概览一屏数据：三率卡片 + 趋势 + Top 问题 + 三张排行榜。
     *
     * @param appId         必填，多应用严格隔离
     * @param platform      可选，1 安卓 2 iOS 3 鸿蒙
     * @param appVersion    可选，单版本视角
     * @param compareVersion 可选，对照版本（返回 compareTrend 与对照三率）
     * @param granularity   可选 hour|day，缺省按跨度自动（<=2 天按小时，否则按天）
     * @param beginTime     毫秒；缺省=近 7 天
     * @param endTime       毫秒；缺省=现在
     * @param tzOffsetMinutes 趋势分桶时区偏移分钟，缺省 480（东八区）
     */
    Map<String, Object> overview(String appId, Integer platform, String appVersion, String compareVersion,
                                 String granularity, Long beginTime, Long endTime, Integer tzOffsetMinutes);

    /** 筛选项：版本候选等（下拉用）。 */
    Map<String, Object> filters(String appId, Integer platform);
}
