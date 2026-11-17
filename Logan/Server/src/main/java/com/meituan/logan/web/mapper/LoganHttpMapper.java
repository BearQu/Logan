package com.meituan.logan.web.mapper;

import com.meituan.logan.web.dto.LoganHttpDTO;
import com.meituan.logan.web.dto.LoganHttpIssueDTO;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

// 网络请求观测：明细幂等入库 + 聚合问题累加 + 多维筛选。
public interface LoganHttpMapper {

    // dedup_hash 冲突即忽略（客户端重试/断网重传幂等）。
    int insertIgnore(LoganHttpDTO dto);

    LoganHttpDTO selectById(@Param("id") long id);

    // 列表页多维筛选。
    List<LoganHttpDTO> list(@Param("appId") String appId, @Param("deviceId") String deviceId,
            @Param("kind") String kind, @Param("host") String host, @Param("path") String path,
            @Param("errKind") String errKind, @Param("minStatus") Integer minStatus,
            @Param("maxStatus") Integer maxStatus, @Param("slowOnly") Boolean slowOnly,
            @Param("beginTime") Long beginTime, @Param("endTime") Long endTime,
            @Param("keyword") String keyword, @Param("limit") int limit);

    // 崩溃前后时间窗内的网络事件（崩溃与网络互证用），同设备。
    List<LoganHttpDTO> listAround(@Param("appId") String appId, @Param("deviceId") String deviceId,
            @Param("beginTime") long beginTime, @Param("endTime") long endTime, @Param("limit") int limit);

    // 顶部统计卡。
    Map<String, Object> summary(@Param("appId") String appId, @Param("beginTime") Long beginTime,
            @Param("endTime") Long endTime);

    // 聚合问题累加（同指纹只有一行）。
    int upsertIssue(LoganHttpIssueDTO dto);

    List<LoganHttpIssueDTO> listIssues(@Param("appId") String appId, @Param("statusFlag") Integer statusFlag,
            @Param("kind") String kind, @Param("errKind") String errKind, @Param("limit") int limit);

    LoganHttpIssueDTO selectIssueByFingerprint(@Param("appId") String appId,
            @Param("fingerprint") String fingerprint);

    LoganHttpIssueDTO selectIssueById(@Param("id") long id);

    int updateIssueStatus(@Param("id") long id, @Param("statusFlag") int statusFlag,
            @Param("lastFixNote") String lastFixNote, @Param("reopenCount") Integer reopenCount);

    // AI 定性摘要回填。
    int updateIssueLlm(@Param("id") long id, @Param("llmSummary") String llmSummary,
            @Param("llmSeverity") String llmSeverity);

    // 处置闭环：指派处理人 / 处置结论。
    int updateIssueAssign(@Param("id") long id, @Param("assignee") String assignee);

    int updateIssueCloseNote(@Param("id") long id, @Param("closeNote") String closeNote);
}

