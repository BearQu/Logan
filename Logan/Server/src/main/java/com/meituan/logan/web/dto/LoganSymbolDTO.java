package com.meituan.logan.web.dto;

import lombok.Data;

import java.util.Date;

/** 符号表归档对象：Android mapping、iOS dSYM 压缩包、native .so 符号、H5 sourcemap。 */
@Data
public class LoganSymbolDTO {

    private long id;

    private String appId;

    /** 1 Android 2 iOS 3 HarmonyOS */
    private int platform;

    private String appVersion;

    private String buildVersion;

    /** mapping / dsym / so / sourcemap */
    private String kind;

    private String fileName;

    /** 落盘相对文件名（相对 logan.symbol.dir） */
    private String filePath;

    private String fileHash;

    private long fileSize;

    private String note;

    private String uploader;

    /** console / dsh-skill / gradle */
    private String source;

    private long addTime;

    private Date updateTime;
}
