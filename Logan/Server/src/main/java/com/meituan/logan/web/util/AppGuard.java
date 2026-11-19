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
        String id = appId.trim();
        try {
            if (appService.get(id) != null) {
                return true;
            }
        } catch (Exception e) {
            LOGGER.warn("app guard query failed for " + id + ": " + e.getMessage());
        }
        // get 把「连接坏了」和「应用真没登记」压成同一个 null，且坏连接只进日志不进响应，
        // 客户端只能看到一条误导性的「未登记」。查不到就用独立查询复查一次：
        // list() 从池里另拿连接——查得到说明刚才是连接问题，放行并留痕；真查不到才拒。
        try {
            java.util.List<com.meituan.logan.web.dto.LoganAppDTO> all = appService.list();
            if (all != null) {
                for (com.meituan.logan.web.dto.LoganAppDTO dto : all) {
                    if (dto != null && id.equals(dto.getAppId())) {
                        LOGGER.warn("app guard: get() missed registered app " + id
                                + " (stale pool connection?) — allowing via list recheck");
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.warn("app guard recheck failed for " + id + ": " + e.getMessage());
        }
        return false;
    }
}
