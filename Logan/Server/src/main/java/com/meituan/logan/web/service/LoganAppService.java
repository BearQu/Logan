package com.meituan.logan.web.service;

import com.meituan.logan.web.dto.LoganAppDTO;

import java.util.List;

/** 应用自动登记：客户端只管带上包名/应用名/图标，平台不需要手工录入。 */
public interface LoganAppService {

    /** 任何上报入口（native 日志 / H5 日志 / 崩溃）调用，异常绝不影响上报。 */
    void register(String appId, String appName, Integer platform, String appVersion,
            String buildVersion, String deviceId, String source);

    /** 显式上报应用资料（名称/图标 base64），可带 iconBase64 与 mime。 */
    LoganAppDTO reportProfile(String appId, String appName, String iconBase64, String mime,
            Integer platform, String appVersion, String buildVersion, String deviceId, String source);

    /** 平台侧创建应用（治理入口）：已存在返回 null，source 固定 manual。 */
    LoganAppDTO createApp(String appId, String appName, Integer platform);

    List<LoganAppDTO> list();

    LoganAppDTO get(String appId);
}
