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

    /** 综合搜索时，命中筛选条件的崩溃次数（未筛选时等于 crashCount） */
    private Integer filteredCrashCount;

    /** 综合搜索时，命中筛选条件的设备数 */
    private Integer filteredDevices;

    /** 处理人（对标 Bugly 处理人；平台不猜，由前端显式传入） */
    private String assignee;

    /** 最近指派时间（毫秒） */
    private Long assignTime;

    /** 处置结论说明（修复/忽略的理由） */
    private String closeNote;

    /** 处置时间（毫秒） */
    private Long closeTime;


    /** 0 未处理 1 处理中 2 已修复 3 忽略 */
    private int status;

    /** 「已修复」后同指纹再次上报的次数（本地修复被推翻的证据） */
    private Integer reopenCount;

    /** 最近一次本地修复报告的摘要（修复人/时间/根因/细节） */
    private String lastFixNote;

    private long addTime;

    private Date updateTime;
    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getAppId() {
        return appId;
    }

    public void setAppId(String appId) {
        this.appId = appId;
    }

    public int getPlatform() {
        return platform;
    }

    public void setPlatform(int platform) {
        this.platform = platform;
    }

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public void setFingerprint(String fingerprint) {
        this.fingerprint = fingerprint;
    }

    public String getSampleContent() {
        return sampleContent;
    }

    public void setSampleContent(String sampleContent) {
        this.sampleContent = sampleContent;
    }

    public String getTopFrames() {
        return topFrames;
    }

    public void setTopFrames(String topFrames) {
        this.topFrames = topFrames;
    }

    public String getSignalInfo() {
        return signalInfo;
    }

    public void setSignalInfo(String signalInfo) {
        this.signalInfo = signalInfo;
    }

    public Long getFirstCrashTime() {
        return firstCrashTime;
    }

    public void setFirstCrashTime(Long firstCrashTime) {
        this.firstCrashTime = firstCrashTime;
    }

    public Long getLastCrashTime() {
        return lastCrashTime;
    }

    public void setLastCrashTime(Long lastCrashTime) {
        this.lastCrashTime = lastCrashTime;
    }

    public int getCrashCount() {
        return crashCount;
    }

    public void setCrashCount(int crashCount) {
        this.crashCount = crashCount;
    }

    public String getAffectedDevices() {
        return affectedDevices;
    }

    public void setAffectedDevices(String affectedDevices) {
        this.affectedDevices = affectedDevices;
    }

    public String getLlmSummary() {
        return llmSummary;
    }

    public void setLlmSummary(String llmSummary) {
        this.llmSummary = llmSummary;
    }

    public String getLlmSeverity() {
        return llmSeverity;
    }

    public void setLlmSeverity(String llmSeverity) {
        this.llmSeverity = llmSeverity;
    }

    public Integer getFilteredCrashCount() {
        return filteredCrashCount;
    }

    public void setFilteredCrashCount(Integer filteredCrashCount) {
        this.filteredCrashCount = filteredCrashCount;
    }

    public Integer getFilteredDevices() {
        return filteredDevices;
    }

    public void setFilteredDevices(Integer filteredDevices) {
        this.filteredDevices = filteredDevices;
    }

    public int getStatus() {
        return status;
    }

    public void setStatus(int status) {
        this.status = status;
    }

    public Integer getReopenCount() {
        return reopenCount;
    }

    public void setReopenCount(Integer reopenCount) {
        this.reopenCount = reopenCount;
    }

    public String getLastFixNote() {
        return lastFixNote;
    }

    public void setLastFixNote(String lastFixNote) {
        this.lastFixNote = lastFixNote;
    }

    public long getAddTime() {
        return addTime;
    }

    public void setAddTime(long addTime) {
        this.addTime = addTime;
    }

}
