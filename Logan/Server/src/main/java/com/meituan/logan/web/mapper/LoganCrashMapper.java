package com.meituan.logan.web.mapper;

import com.meituan.logan.web.dto.LoganCrashDTO;
import com.meituan.logan.web.dto.LoganCrashIssueDTO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface LoganCrashMapper {

    /** INSERT IGNORE：content_hash 唯一键去重，双通道重复上报只入库一次。返回影响行数（0=重复）。 */
    int insertIgnore(@Param("dto") LoganCrashDTO dto);

    LoganCrashDTO selectById(@Param("id") long id);

    List<LoganCrashDTO> queryByFingerprint(@Param("appId") String appId, @Param("fingerprint") String fingerprint, @Param("limit") int limit);

    List<LoganCrashDTO> queryByDevice(@Param("deviceId") String deviceId, @Param("limit") int limit);

    List<LoganCrashDTO> queryLatest(@Param("appId") String appId, @Param("limit") int limit);

    int countByFingerprint(@Param("appId") String appId, @Param("fingerprint") String fingerprint);

    int countAll(@Param("appId") String appId);

    // ---- issue 聚类 ----

    List<LoganCrashIssueDTO> queryRecentIssues(@Param("appId") String appId, @Param("limit") int limit);


    LoganCrashIssueDTO selectIssueByFp(@Param("appId") String appId, @Param("fingerprint") String fingerprint);

    LoganCrashIssueDTO selectIssueById(@Param("id") long id);

    int insertIssue(@Param("dto") LoganCrashIssueDTO dto);

    /** 计数+1 与 last_crash_time；affectedDevices 由服务层读出后合并 JSON 再写回。 */
    int updateIssueAgg(@Param("id") long id, @Param("crashCount") int crashCount, @Param("lastCrashTime") long lastCrashTime, @Param("affectedDevices") String affectedDevices);

    int updateLlmResult(@Param("id") long id, @Param("llmSummary") String llmSummary, @Param("llmSeverity") String llmSeverity);

    /** LLM worker 扫描：还没有摘要的最近问题（限量） */
    List<LoganCrashIssueDTO> queryIssuesWithoutSummary(@Param("limit") int limit);

    /** 更新问题处理状态（Bugly 式「已修复/忽略」闭环，0 未处理 1 处理中 2 已修复 3 忽略）。 */
    int updateIssueStatus(@Param("id") long id, @Param("status") int status);

    // ---- 控制台统计（LoganSite 崩溃维度页） ----

    /** 按天趋势：[{d:"2026-11-14", kind, cnt}]，beginTime 起。 */
    List<java.util.Map<String, Object>> queryDailyTrend(@Param("appId") String appId, @Param("beginTime") long beginTime, @Param("endTime") long endTime);

    /** KPI 汇总：[{kind, total, issues, devices, lastCrashTime}]。 */
    List<java.util.Map<String, Object>> queryKindSummary(@Param("appId") String appId);

    /** 实例列表（不含 content 大字段，列表页用）。 */
    List<com.meituan.logan.web.dto.LoganCrashDTO> queryInstancesByFp(@Param("appId") String appId, @Param("fingerprint") String fingerprint, @Param("limit") int limit);
}
