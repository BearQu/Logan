package com.meituan.logan.web.service;

import com.alibaba.fastjson.JSONObject;

import java.util.List;
import java.util.Map;

/**
 * 崩溃证据包与修复报告。设计原则：平台不接触任何源码，只负责把证据攒齐并导出去，
 * 分析修改在本地（DSH）完成，改完之后把报告回传回来。
 */
public interface LoganCrashEvidenceService {

    /** 单条崩溃的完整证据包：崩溃原文 + 设备信息 + 崩溃前后业务日志 + AI 初判 + 历史修复报告。 */
    JSONObject evidenceForCrash(long crashId, int beforeSeconds, int afterSeconds);

    /** 聚合问题的证据包（同指纹取样本 + 实例列表 + 历史修复）。 */
    JSONObject evidenceForIssue(long issueId, int beforeSeconds, int afterSeconds, int instanceLimit);

    /** 多维筛选批量导出（给本地 Agent 一次性喂一批）。 */
    JSONObject exportBundle(Map<String, Object> filter, int maxIssues, int beforeSeconds, int afterSeconds);

    /** 崩溃前后业务日志时间线（详情页展示，与 AI 摘要共用）。 */
    List<Map<String, Object>> timeline(String appId, String deviceId, long crashTime, int beforeSeconds, int afterSeconds, int limit);

    /** 某 app 一段时间内有崩溃的设备与日期（日志列表徽标）。 */
    List<Map<String, Object>> crashDays(String appId, long beginTime, long endTime);

    /** 回传修复报告；同指纹若处于「已修复」会自动重开并累计 reopen_count。 */
    JSONObject reportFix(JSONObject payload);

    List<com.meituan.logan.web.dto.LoganCrashFixDTO> fixesOfIssue(long issueId, int limit);

    List<com.meituan.logan.web.dto.LoganCrashFixDTO> fixesOfFingerprint(String appId, String fingerprint, int limit);
}
