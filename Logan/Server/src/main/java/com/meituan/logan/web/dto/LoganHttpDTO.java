package com.meituan.logan.web.dto;

import lombok.Data;

import java.util.Date;

/**
 * logan_http 明细行：一条慢请求或失败请求。
 * 上报方（三端 SDK / H5）只填原始事实，归一化 path、指纹、去重哈希一律服务端算，
 * 这样任何项目、任何语言的上报方都不需要理解平台的聚类规则。
 */
@Data
public class LoganHttpDTO {

    private long id;

    private String appId;

    private String deviceId;

    /** 1 Android 2 iOS 3 HarmonyOS，0=未知（例如纯 H5 由宿主代发） */
    private int platform;

    /** native / h5 */
    private String kind;

    private String method;

    /** 完整 URL，query 已脱敏 */
    private String url;

    private String host;

    /** 服务端归一化：数字段->:n，UUID->:uuid，长 hex->:hex，日期/时间戳->:t */
    private String path;

    private int status;

    /** timeout/connect/ssl/parse/cancel/other，成功为 null */
    private String errKind;

    private String errMsg;

    private long durationMs;

    private Long connectMs;

    private Long dnsMs;

    private Long ttfbMs;

    private Long reqBytes;

    private Long respBytes;

    private boolean slow;

    private String pageUrl;

    private String traceId;

    private String appVersion;

    private String buildVersion;

    private String os;

    private String osVersion;

    private String brand;

    private String model;

    private String networkType;

    private String extra;

    private long occurredAt;

    private String fingerprint;

    private String dedupHash;

    private long addTime;

    private Date updateTime;
}
