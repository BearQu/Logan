package com.meituan.logan.web.dto;

import lombok.Data;

import java.util.Date;

/** logan_crash 行对象；字段与 SDK CrashEnvelope 对齐（SDK 是权威 schema）。 */
@Data
public class LoganCrashDTO {

    private long id;

    private String appId;

    private int platform;

    /** java / native / anr / js / freeze */
    private String kind;

    /** SDK 端确定性聚类键（hash:len），绝不进时间戳与地址 */
    private String fingerprint;

    private String deviceId;

    private String unionId;

    private String userId;

    private String appVersion;

    private String buildVersion;

    private String brand;

    private String model;

    private String manufacturer;

    private Integer sdkInt;

    private String releaseVer;

    private String abi;

    private String signalInfo;

    /** 顶3业务帧 JSON 数组串 */
    private String topFrames;

    /** 1 = 下次启动补传（通道 B） */
    private int deferred;

    private long crashTime;

    private long addTime;

    /** tombstone 原文 */
    private String content;

    /** SHA1(appId|deviceId|fingerprint|crashTime|kind)，唯一键防双通道重复入库 */
    private String contentHash;

    private Date updateTime;
}
