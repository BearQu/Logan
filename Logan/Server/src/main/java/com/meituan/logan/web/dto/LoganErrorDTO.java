package com.meituan.logan.web.dto;

import lombok.Data;

// logan_error 明细行：一条非致命错误（业务异常、被 catch 的异常、前端 error 等）。
// 上报方只填原始事实，指纹与去重哈希一律服务端算，保证任何项目/语言都能直接上报。
@Data
public class LoganErrorDTO {

    private long id;

    private String appId;

    private String deviceId;

    /** 1 Android 2 iOS 3 HarmonyOS，0=未知（H5 由宿主代发时为 0） */
    private int platform;

    /** business / caught / ui / promise / resource / other，缺省 business */
    private String kind;

    /** 异常类名或错误名，如 NullPointerException / TypeError */
    private String name;

    private String message;

    private String stack;

    private String pageUrl;

    private String traceId;

    private String appVersion;

    private String buildVersion;

    private String os;

    private String osVersion;

    private String brand;

    private String model;

    private String networkType;

    /** 是否致命（默认 0：非致命才进这张表，致命走崩溃通道） */
    private int fatal;

    private String extra;

    private long occurredAt;

    private String fingerprint;

    private String dedupHash;

    private long addTime;
}
