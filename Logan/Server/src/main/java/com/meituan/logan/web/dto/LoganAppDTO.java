package com.meituan.logan.web.dto;

import lombok.Data;

import java.util.Date;

/** logan_app 行对象：客户端上报包名/应用名/图标自动登记。 */
@Data
public class LoganAppDTO {

    private long id;

    private String appId;

    private String appName;

    /** 图标落盘相对路径（相对 catalina.base/logan-app-icons） */
    private String iconPath;

    private String iconHash;

    private int platform;

    private String latestAppVersion;

    private String latestBuildVersion;

    private String lastDeviceId;

    /** auto_client / manual */
    private String source;

    private Long firstSeen;

    private Long lastReportTime;

    private long addTime;

    private Date updateTime;

    // ---- 控制台列表实时统计（list.json 填充） ----

    private Integer crashCount;

    private Integer issueCount;

    private Integer deviceCount;

    private Integer logCount;

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

    public String getAppName() {
        return appName;
    }

    public void setAppName(String appName) {
        this.appName = appName;
    }

    public String getIconPath() {
        return iconPath;
    }

    public void setIconPath(String iconPath) {
        this.iconPath = iconPath;
    }

    public String getIconHash() {
        return iconHash;
    }

    public void setIconHash(String iconHash) {
        this.iconHash = iconHash;
    }

    public int getPlatform() {
        return platform;
    }

    public void setPlatform(int platform) {
        this.platform = platform;
    }

    public String getLatestAppVersion() {
        return latestAppVersion;
    }

    public void setLatestAppVersion(String latestAppVersion) {
        this.latestAppVersion = latestAppVersion;
    }

    public String getLatestBuildVersion() {
        return latestBuildVersion;
    }

    public void setLatestBuildVersion(String latestBuildVersion) {
        this.latestBuildVersion = latestBuildVersion;
    }

    public String getLastDeviceId() {
        return lastDeviceId;
    }

    public void setLastDeviceId(String lastDeviceId) {
        this.lastDeviceId = lastDeviceId;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public Long getFirstSeen() {
        return firstSeen;
    }

    public void setFirstSeen(Long firstSeen) {
        this.firstSeen = firstSeen;
    }

    public Long getLastReportTime() {
        return lastReportTime;
    }

    public void setLastReportTime(Long lastReportTime) {
        this.lastReportTime = lastReportTime;
    }

    public long getAddTime() {
        return addTime;
    }

    public void setAddTime(long addTime) {
        this.addTime = addTime;
    }

    public Integer getCrashCount() {
        return crashCount;
    }

    public void setCrashCount(Integer crashCount) {
        this.crashCount = crashCount;
    }

    public Integer getIssueCount() {
        return issueCount;
    }

    public void setIssueCount(Integer issueCount) {
        this.issueCount = issueCount;
    }

    public Integer getDeviceCount() {
        return deviceCount;
    }

    public void setDeviceCount(Integer deviceCount) {
        this.deviceCount = deviceCount;
    }

    public Integer getLogCount() {
        return logCount;
    }

    public void setLogCount(Integer logCount) {
        this.logCount = logCount;
    }

}
