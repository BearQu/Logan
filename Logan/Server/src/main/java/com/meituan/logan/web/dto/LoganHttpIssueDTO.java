package com.meituan.logan.web.dto;

import lombok.Data;

import java.util.Date;

/** logan_http_issue 聚合行：同一归一化接口 + 同一失败类别只有一行。 */
@Data
public class LoganHttpIssueDTO {

    private long id;

    private String appId;

    private String fingerprint;

    private String kind;

    private String method;

    private String host;

    private String path;

    private int status;

    private String errKind;

    private String title;

    private String sampleUrl;

    private String sampleDeviceId;

    private String sampleTraceId;

    private int errorCount;

    private int slowCount;

    private int totalCount;

    private long avgMs;

    private long maxMs;

    private String lastErrMsg;

    private String affectedDevices;

    /** 0 未处理 1 处理中 2 已修复 */
    private int statusFlag;

    private String lastFixNote;

    private int reopenCount;

    private String llmSummary;

    private String llmSeverity;

    private long firstSeen;

    private long lastSeen;

    /** 处理人（处置闭环） */
    private String assignee;

    /** 处置结论说明 */
    private String closeNote;


    private long addTime;

    private Date updateTime;
}
