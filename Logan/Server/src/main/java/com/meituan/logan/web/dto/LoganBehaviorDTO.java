package com.meituan.logan.web.dto;

import lombok.Data;

// logan_behavior 明细行：一条用户行为轨迹（页面进入、点击、自定义埋点、前后台切换等）。
// 上报方只填原始事实，归一化与去重哈希一律服务端算，保证任何项目/端都能直接上报。
@Data
public class LoganBehaviorDTO {

    private long id;

    private String appId;

    private String deviceId;

    /** 1 Android 2 iOS 3 HarmonyOS，0=未知（H5 由宿主代发时为 0） */
    private int platform;

    /** page / click / custom / foreground / background / launch / exit / input / scroll / other */
    private String kind;

    /** 页面名或控件名，如 LoginActivity / btn_submit / 首页-登录按钮 */
    private String name;

    /** 人类可读标签：按钮文案、页面标题、H5 URL 等 */
    private String label;

    private String pageUrl;

    private String traceId;

    private String appVersion;

    private String buildVersion;

    private String os;

    private String osVersion;

    private String brand;

    private String model;

    private String networkType;

    /** 相对上一条轨迹的间隔毫秒（客户端可空，服务端不造假） */
    private Long intervalMs;

    private String extra;

    private long occurredAt;

    private String dedupHash;

    private long addTime;
}
