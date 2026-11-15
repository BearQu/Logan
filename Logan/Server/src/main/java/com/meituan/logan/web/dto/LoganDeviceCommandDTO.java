package com.meituan.logan.web.dto;

import lombok.Data;

import java.util.Date;

/**
 * 下行命令行对象：控制台下发「主动拉日志」等命令，客户端轮询领取。
 * 状态机 pending -> delivered -> acked / failed；pending/delivered 可 cancelled；
 * pending 过期转 expired；delivered 超时未回执转 lost。
 */
@Data
public class LoganDeviceCommandDTO {

    private long id;

    private String appId;

    private String deviceId;

    /** upload_logs / ping */
    private String action;

    /** 命令参数 JSON 文本，如 {hours:24} */
    private String param;

    /** pending / delivered / acked / failed / cancelled / expired / lost */
    private String status;

    private String operator;

    private String note;

    private String resultNote;

    private int deliverCount;

    private long addTime;

    private Long deliveredTime;

    private Long ackTime;

    private long expireTime;

    private Date updateTime;
}
