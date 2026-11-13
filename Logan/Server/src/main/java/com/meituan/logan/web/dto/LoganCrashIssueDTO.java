package com.meituan.logan.web.dto;

import lombok.Data;

import java.util.Date;

/** 聚类后的崩溃问题（借鉴 Bugly「栈顶三帧业务帧」思路，指纹由 SDK 生成，服务端只做确定性聚合与计数）。 */
@Data
public class LoganCrashIssueDTO {

    private long id;

    private String appId;

    private int platform;

    private String kind;

    private String fingerprint;

    /** 首见样本 tombstone（截断存），控制台展示与后续符号化/LLM 摘要的原料 */
    private String sampleContent;

    private String topFrames;

    private String signalInfo;

    private Long firstCrashTime;

    private Long lastCrashTime;

    private int crashCount;

    /** JSON 数组串，deviceCount 去重统计用 */
    private String affectedDevices;

    /** LLM 定性摘要（Spark qwen worker 异步回填；PoC 阶段可空） */
    private String llmSummary;

    private String llmSeverity;

    /** 0 未处理 1 处理中 2 已修复 3 忽略 */
    private int status;

    private long addTime;

    private Date updateTime;
}
