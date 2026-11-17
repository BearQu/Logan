package com.meituan.logan.web.mapper;

import com.meituan.logan.web.dto.LoganErrorDTO;
import com.meituan.logan.web.dto.LoganErrorIssueDTO;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

// 非致命错误（错误分析）：明细幂等入库 + 同指纹聚合累加 + 多维筛选。
public interface LoganErrorMapper {

    // dedup_hash 唯一键冲突即忽略，客户端重试/断网重传天然幂等。
    int insertIgnore(LoganErrorDTO dto);

    LoganErrorDTO selectById(@Param("id") long id);

    // 明细列表（多维筛选）。
    List<LoganErrorDTO> list(@Param("q") Map<String, Object> q);

    long countList(@Param("q") Map<String, Object> q);

    // 崩溃/错误前后时间窗内同设备的错误（互证用）。
    List<LoganErrorDTO> listAround(@Param("appId") String appId, @Param("deviceId") String deviceId,
            @Param("beginTime") long beginTime, @Param("endTime") long endTime, @Param("limit") int limit);

    // 概览卡片：次数 / 影响设备 / 问题数。
    Map<String, Object> summary(@Param("appId") String appId, @Param("beginTime") Long beginTime,
            @Param("endTime") Long endTime, @Param("platform") Integer platform,
            @Param("appVersion") String appVersion);

    // 聚合问题累加（同指纹只有一行）。
    int upsertIssue(LoganErrorIssueDTO dto);

    List<LoganErrorIssueDTO> listIssues(@Param("q") Map<String, Object> q);

    LoganErrorIssueDTO selectIssueByFingerprint(@Param("appId") String appId,
            @Param("fingerprint") String fingerprint);

    LoganErrorIssueDTO selectIssueById(@Param("id") long id);

    int updateIssueStatus(@Param("id") long id, @Param("statusFlag") int statusFlag,
            @Param("lastFixNote") String lastFixNote, @Param("reopenCount") Integer reopenCount);

    int updateIssueLlm(@Param("id") long id, @Param("llmSummary") String llmSummary,
            @Param("llmSeverity") String llmSeverity);

    // AI 摘要 worker：捞还没有定性摘要的聚合问题（口径同崩溃侧 queryIssuesWithoutSummary）。
    List<LoganErrorIssueDTO> queryIssuesWithoutSummary(@Param("limit") int limit);

    int updateIssueAssign(@Param("id") long id, @Param("assignee") String assignee);

    int updateIssueCloseNote(@Param("id") long id, @Param("closeNote") String closeNote);

    /** 错误榜（概览页用）：按 name 聚成 Top N。 */
    List<Map<String, Object>> topNames(@Param("appId") String appId, @Param("beginTime") Long beginTime,
            @Param("endTime") Long endTime, @Param("platform") Integer platform,
            @Param("appVersion") String appVersion, @Param("limit") int limit);
}
