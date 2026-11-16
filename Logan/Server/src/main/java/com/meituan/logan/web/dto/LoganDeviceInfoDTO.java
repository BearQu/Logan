package com.meituan.logan.web.dto;

import lombok.Data;

/** 设备基本信息快照：客户端随轮询/回执上送，或响应 collect_info 命令即时采集上报。 */
@Data
public class LoganDeviceInfoDTO {

    private long id;

    private String appId;

    private String deviceId;

    private int platform;

    /** 设备信息 JSON 文本；平台不解析语义，取不到的字段由端侧省略，不伪造 */
    private String info;

    private long reportTime;
}
