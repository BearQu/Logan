package com.meituan.logan.web.mapper;

import com.meituan.logan.web.dto.LoganCrashFixDTO;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

public interface LoganCrashFixMapper {

    /** INSERT IGNORE，report_hash 唯一键幂等。返回 0 表示重复回传。 */
    int insertIgnore(LoganCrashFixDTO dto);

    /** 同指纹的历史修复报告（含跨应用兜底用 appId 过滤），按时间倒序。 */
    List<LoganCrashFixDTO> queryByFingerprint(@Param("appId") String appId,
            @Param("fingerprint") String fingerprint, @Param("limit") int limit);

    List<LoganCrashFixDTO> queryByIssue(@Param("issueId") long issueId, @Param("limit") int limit);

    LoganCrashFixDTO selectById(@Param("id") long id);

    /** 导出证据包时按筛选条件捞报告（多维筛选）。 */
    List<LoganCrashFixDTO> queryByFilter(@Param("q") Map<String, Object> q);
}
