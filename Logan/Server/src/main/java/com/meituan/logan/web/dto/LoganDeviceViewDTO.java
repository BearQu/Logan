package com.meituan.logan.web.dto;

import lombok.Data;

/**
 * 设备清单视图：设备不单独注册，从 logan_task（日志上报）与 logan_crash（崩溃上报）派生，
 * 再左连 logan_device_poll（心跳）与待执行命令数。
 */
@Data
public class LoganDeviceViewDTO {

    private String appId;

    private String deviceId;

    private int platform;

    private String appVersion;

    private String buildVersion;

    private String brand;

    private String model;

    private Integer sdkInt;

    private String releaseVer;

    /** 日志包数（logan_task） */
    private int logCount;

    private Long lastLogTime;

    /** 崩溃数（logan_crash） */
    private int crashCount;

    private Long lastCrashTime;

    private Long lastPollTime;

    private long pollCount;

    /** 待领取/已领取未回执的命令数 */
    private int pendingCommands;

    /** 心跳在阈值内视为在线（服务端算，前端直接用） */
    private boolean online;
}
