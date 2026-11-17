package com.meituan.logan.web.dto;

import lombok.Data;

// logan_error_issue 聚合行：同一指纹只有一行，重复上报累加次数。
@Data
public class LoganErrorIssueDTO {

    private long id;

    private String appId;

    private String fingerprint;

    private int platform;

    private String kind;

    private String name;

    private String title;

    private String sampleMessage;

    private String sampleStack;

    private String sampleDeviceId;

    private String sampleTraceId;

    private int eventCount;

    private String affectedDevices;

    private long firstSeen;

    private long lastSeen;

    private int statusFlag;

    private int reopenCount;

    private String lastFixNote;

    private String assignee;

    private String closeNote;

    private String llmSummary;

    private String llmSeverity;

    private long addTime;
}
