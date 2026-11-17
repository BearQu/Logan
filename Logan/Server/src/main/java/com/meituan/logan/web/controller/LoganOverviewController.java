package com.meituan.logan.web.controller;

import com.meituan.logan.web.model.response.LoganResponse;
import com.meituan.logan.web.service.LoganOverviewService;
import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.Logger;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import javax.annotation.Resource;
import java.util.Map;

/**
 * 异常概览看板 API（对标 Bugly「异常概览」页）。只读，不落库。
 * 参数一律 query；时间为毫秒时间戳。
 */
@CrossOrigin(origins = "*", maxAge = 3600)
@Controller
@RequestMapping("/logan/overview")
public class LoganOverviewController {

    private static final Logger LOGGER = Logger.getLogger(LoganOverviewController.class);

    @Resource
    private LoganOverviewService overviewService;

    /** 概览一屏：三率卡片 + 趋势（按小时/累计）+ Top 问题（含波动）+ 系统/设备/版本 Top5 榜。 */
    @GetMapping("/summary.json")
    @ResponseBody
    public LoganResponse<Map<String, Object>> summary(String appId,
                                                      Integer platform,
                                                      String appVersion,
                                                      String compareVersion,
                                                      String granularity,
                                                      Long beginTime,
                                                      Long endTime,
                                                      Integer tzOffsetMinutes) {
        if (StringUtils.isBlank(appId)) {
            return LoganResponse.badParam("appId required");
        }
        try {
            return LoganResponse.success(overviewService.overview(appId, platform, appVersion, compareVersion,
                    granularity, beginTime, endTime, tzOffsetMinutes));
        } catch (Exception e) {
            LOGGER.warn("overview failed appId=" + appId + " " + e.getMessage());
            return LoganResponse.exception(e.getMessage());
        }
    }

    /** 看板筛选项：版本候选（对比版本下拉用）。 */
    @GetMapping("/filters.json")
    @ResponseBody
    public LoganResponse<Map<String, Object>> filters(String appId, Integer platform) {
        if (StringUtils.isBlank(appId)) {
            return LoganResponse.badParam("appId required");
        }
        try {
            return LoganResponse.success(overviewService.filters(appId, platform));
        } catch (Exception e) {
            return LoganResponse.exception(e.getMessage());
        }
    }
}
