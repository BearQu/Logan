package com.meituan.logan.web.dto;

import lombok.Data;

/** 崩溃修复报告（本地 DSH 分析并修改后由 skill 回传；平台不接触源码）。 */
@Data
public class LoganCrashFixDTO {

    private long id;

    private String appId;

    private Long issueId;

    private String fingerprint;

    private String kind;

    /** fix / note / reopen */
    private String action;

    /** fixed / verified / partial / wontfix / reopen */
    private String status;

    private String fixer;

    private String rootCause;

    private String detail;

    /** JSON 数组串，改动的文件列表 */
    private String files;

    private String commitUrl;

    private String prUrl;

    private String source;

    private String client;

    private String evidenceHash;

    private String reportHash;

    private Long fixedAt;

    private long addTime;

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

    public Long getIssueId() {
        return issueId;
    }

    public void setIssueId(Long issueId) {
        this.issueId = issueId;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public void setFingerprint(String fingerprint) {
        this.fingerprint = fingerprint;
    }

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getFixer() {
        return fixer;
    }

    public void setFixer(String fixer) {
        this.fixer = fixer;
    }

    public String getRootCause() {
        return rootCause;
    }

    public void setRootCause(String rootCause) {
        this.rootCause = rootCause;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }

    public String getFiles() {
        return files;
    }

    public void setFiles(String files) {
        this.files = files;
    }

    public String getCommitUrl() {
        return commitUrl;
    }

    public void setCommitUrl(String commitUrl) {
        this.commitUrl = commitUrl;
    }

    public String getPrUrl() {
        return prUrl;
    }

    public void setPrUrl(String prUrl) {
        this.prUrl = prUrl;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getClient() {
        return client;
    }

    public void setClient(String client) {
        this.client = client;
    }

    public String getEvidenceHash() {
        return evidenceHash;
    }

    public void setEvidenceHash(String evidenceHash) {
        this.evidenceHash = evidenceHash;
    }

    public String getReportHash() {
        return reportHash;
    }

    public void setReportHash(String reportHash) {
        this.reportHash = reportHash;
    }

    public Long getFixedAt() {
        return fixedAt;
    }

    public void setFixedAt(Long fixedAt) {
        this.fixedAt = fixedAt;
    }

    public long getAddTime() {
        return addTime;
    }

    public void setAddTime(long addTime) {
        this.addTime = addTime;
    }

}
