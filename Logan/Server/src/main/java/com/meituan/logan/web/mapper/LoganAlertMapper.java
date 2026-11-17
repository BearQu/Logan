package com.meituan.logan.web.mapper;

import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/** 报警规则与触发记录（纯平台侧检测 + 通用 webhook 通知）。 */
public interface LoganAlertMapper {

    List<Map<String, Object>> listEnabledRules();

    /** 规则管理页用：含停用规则。 */
    List<Map<String, Object>> listRules(@Param("appId") String appId, @Param("enabled") Integer enabled, @Param("limit") int limit);

    /** 概览页报警角标用。 */
    int countEnabledRules(@Param("appId") String appId);

    Map<String, Object> selectRule(@Param("id") long id);

    int insertRule(Map<String, Object> row);

    int updateRule(Map<String, Object> row);

    int deleteRule(@Param("id") long id);

    /** 触发后写冷却时间与累计次数。 */
    int markTriggered(@Param("id") long id, @Param("triggerTime") long triggerTime);

    int insertRecord(Map<String, Object> row);

    List<Map<String, Object>> listRecords(@Param("appId") String appId,
                                          @Param("ruleId") Long ruleId,
                                          @Param("limit") int limit);

    int countRecordsToday(@Param("appId") String appId, @Param("beginTime") long beginTime);

    /** 删除早于 cutoff 的报警记录（单轮最多 limit 行，避免长事务）。 */
    int deleteRecordsOlderThan(@Param("cutoff") long cutoff, @Param("limit") int limit);

    /** 只保留最近 keep 条，其余删除（按自增 id 倒序兜底上限）。 */
    int deleteRecordsBeyond(@Param("keep") int keep);

    /** 窗口内首次出现的崩溃问题数（new_issue 指标用）。 */
    int countNewCrashIssues(@Param("q") Map<String, Object> q);

    /** 窗口内首次出现的网络问题数。 */
    int countNewHttpIssues(@Param("q") Map<String, Object> q);
}
