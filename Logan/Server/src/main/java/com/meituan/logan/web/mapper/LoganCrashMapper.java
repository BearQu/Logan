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

    int insertIssue(@Param("dto") LoganCrashIssueDTO dto);

    /** 计数+1 与 last_crash_time；affectedDevices 由服务层读出后合并 JSON 再写回。 */
    int updateIssueAgg(@Param("id") long id, @Param("crashCount") int crashCount, @Param("lastCrashTime") long lastCrashTime, @Param("affectedDevices") String affectedDevices);

    int updateLlmResult(@Param("id") long id, @Param("llmSummary") String llmSummary, @Param("llmSeverity") String llmSeverity);

    /** LLM worker 扫描：还没有摘要的最近问题（限量） */
    List<LoganCrashIssueDTO> queryIssuesWithoutSummary(@Param("limit") int limit);
}
