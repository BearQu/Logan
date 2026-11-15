package com.meituan.logan.web.util;

import com.meituan.logan.web.service.LoganAppService;
import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.Logger;

/**
 * 治理规则（用户拍板）：应用必须先在平台「应用清单」创建，客户端上报一律携带已登记的 appId；
 * 未登记的 appId 直接拒收（返回明确提示），平台不再自动登记，避免脏应用混进统计。
 */
public final class AppGuard {

    private static final Logger LOGGER = Logger.getLogger(AppGuard.class);

    public static final String MSG =
            "appId not registered: create the app in the platform app list first (\u5e94\u7528\u6e05\u5355 \u2192 \u521b\u5efa\u5e94\u7528)";

    private AppGuard() {
    }

    public static boolean registered(LoganAppService appService, String appId) {
        if (appService == null || StringUtils.isBlank(appId) || "unknown".equals(appId)) {
            return false;
        }
        try {
            return appService.get(appId.trim()) != null;
        } catch (Exception e) {
            LOGGER.warn("app guard query failed for " + appId + ": " + e.getMessage());
            return false;
        }
    }
}
