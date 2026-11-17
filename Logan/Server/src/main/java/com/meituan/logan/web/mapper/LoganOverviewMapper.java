package com.meituan.logan.web.mapper;

import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 异常概览看板（对标 Bugly 异常概览页）专用只读聚合查询。
 * 全部按时间窗聚合，分母「联网设备数」= 窗口内在任一上报通道出现过的去重设备数。
 */
public interface LoganOverviewMapper {

    /** 联网设备数（分母）：logan_task / logan_device_poll / logan_crash / logan_http 四个通道去重设备数。 */
    Long countOnlineDevices(@Param("q") Map<String, Object> q);

    /** 崩溃与 ANR 的分子：事件数与去重设备数（kind='anr' 归 ANR，其余归崩溃）。 */
    Map<String, Object> summaryCounts(@Param("q") Map<String, Object> q);

    /** 分类型明细：[{kind, events, devices, issues}]。 */
    List<Map<String, Object>> kindsBreakdown(@Param("q") Map<String, Object> q);

    /** 趋势分桶：[{bucket, kind, events, devices}]，granularity=hour|day。 */
    List<Map<String, Object>> trendBuckets(@Param("q") Map<String, Object> q,
                                           @Param("granularity") String granularity);

    /** Top 问题：命中窗口内崩溃的聚合问题，带窗口内次数与设备数。 */
    List<Map<String, Object>> topIssues(@Param("q") Map<String, Object> q, @Param("limit") int limit);

    /** 同一批指纹在对照窗口内的次数（算波动用）。 */
    List<Map<String, Object>> countsByFingerprints(@Param("q") Map<String, Object> q,
                                                   @Param("beginTime") long beginTime,
                                                   @Param("endTime") long endTime,
                                                   @Param("fingerprints") List<String> fingerprints);

    /** 排行榜：dim = os | device | version。 */
    List<Map<String, Object>> ranking(@Param("q") Map<String, Object> q,
                                      @Param("dim") String dim,
                                      @Param("limit") int limit);

    /** 窗口内出现的版本候选（筛选下拉，含崩溃与上报两个来源）。 */
    List<String> listVersions(@Param("q") Map<String, Object> q);
}
