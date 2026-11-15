package com.meituan.logan.web.service.impl;

import com.meituan.logan.web.dto.LoganAppDTO;
import com.meituan.logan.web.mapper.LoganAppMapper;
import com.meituan.logan.web.service.LoganAppService;
import com.meituan.logan.web.util.AppFileUtil;
import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.Logger;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;

/**
 * 应用自动登记实现。register 走 INSERT IGNORE + touch，任何异常只记日志——
 * 上报主链路绝不能因为登记表挂掉。
 */
@Service("loganAppService")
public class LoganAppServiceImpl implements LoganAppService {
    private static final Logger LOGGER = Logger.getLogger(LoganAppServiceImpl.class);
    private static final int MAX_NAME = 120;

    @Resource
    private LoganAppMapper appMapper;

    @Override
    public void register(String appId, String appName, Integer platform, String appVersion,
            String buildVersion, String deviceId, String source) {
        if (StringUtils.isBlank(appId) || "unknown".equals(appId)) {
            return;
        }
        try {
            long now = System.currentTimeMillis();
            // 治理规则（用户拍板）：应用必须先在平台创建，这里只刷新已登记应用的事实字段，
            // 不再 INSERT 自动登记；manual 应用的所属端只认控制台写入。
            LoganAppDTO exist = appMapper.selectByAppId(appId.trim());
            if (exist == null) {
                return;
            }
            Integer effPlatform = platform == null || platform < 1 || platform > 3 ? null : platform;
            if ("manual".equals(exist.getSource())) {
                effPlatform = null;
            }
            appMapper.touch(appId.trim(), effPlatform, trim(appVersion, 64), trim(buildVersion, 64),
                    trim(deviceId, 128), now);
        } catch (Exception e) {
            LOGGER.warn("app touch failed for " + appId + ": " + e.getMessage());
        }
    }
    @Override
    public LoganAppDTO reportProfile(String appId, String appName, String iconBase64, String mime,
            Integer platform, String appVersion, String buildVersion, String deviceId, String source) {
        if (StringUtils.isBlank(appId)) {
            return null;
        }
        String src = StringUtils.isBlank(source) ? "auto_client" : source.trim();
        // 治理规则（用户拍板）：save.json 只更新已登记应用，不再替客户端建号；
        // 建号唯一入口是控制台 create.json（或清单页「创建应用」）。
        LoganAppDTO exist0 = appMapper.selectByAppId(appId.trim());
        if (exist0 == null) {
            return null;
        }
        // 事实字段（版本/设备/时间）照常刷新；source 保持登记时的来源
        register(appId, appName, platform, appVersion, buildVersion, deviceId, exist0.getSource());
        // 人工登记优先：已 manual 登记的应用，其显示名/所属端/图标只认控制台（source=manual）的写入，
        // 客户端上报（auto_client/auto_crash）不再覆盖；版本号、设备、上报时间等事实字段照常刷新。
        boolean locked = exist0 != null && "manual".equals(exist0.getSource()) && !"manual".equals(src);
        String effAppName = locked ? null : appName;
        Integer effPlatform = locked ? null : platform;
        String iconPath = null;
        String iconHash = null;
        // 人工登记的应用若已有图标，客户端不再替换；若控制台建号时没挂图标，允许客户端补一次
        boolean keepIcon = locked && StringUtils.isNotBlank(exist0.getIconHash());
        if (StringUtils.isNotBlank(iconBase64) && !keepIcon) {
            LoganAppDTO exist = appMapper.selectByAppId(appId);
            String currentHash = exist == null ? null : exist.getIconHash();
            AppFileUtil.IconWrite write = AppFileUtil.writeIcon(iconBase64, appId, mime);
            if (write != null) {
                if (write.sha1.equals(currentHash)) {
                    // 图标没变，省一次 UPDATE
                } else {
                    iconPath = write.fileName;
                    iconHash = write.sha1;
                }
            }
        }
        // 控制台（source=manual）的编辑要把应用升级为人工登记，之后客户端上报就不再覆盖显示名；
        // 客户端自身的上报永远不动 source。
        String newSource = (exist0 == null || "manual".equals(src)) ? src : null;
        if (StringUtils.isNotBlank(effAppName) || iconPath != null || newSource != null) {
            appMapper.updateProfile(appId, cleanName(effAppName), iconPath, iconHash, effPlatform, newSource);
        }
        return appMapper.selectByAppId(appId);
    }

    @Override
    public LoganAppDTO createApp(String appId, String appName, Integer platform) {
        if (StringUtils.isBlank(appId) || StringUtils.isBlank(appName)) {
            return null;
        }
        String id = appId.trim();
        long now = System.currentTimeMillis();
        LoganAppDTO dto = new LoganAppDTO();
        dto.setAppId(id);
        dto.setAppName(cleanName(appName));
        dto.setPlatform(platform == null || platform < 1 || platform > 3 ? 1 : platform);
        dto.setSource("manual");
        dto.setAddTime(now);
        dto.setLastReportTime(now);
        if (appMapper.insertIgnore(dto) <= 0) {
            return null;
        }
        appMapper.updateProfile(id, cleanName(appName), null, null, dto.getPlatform(), "manual");
        return appMapper.selectByAppId(id);
    }

    @Override
    public List<LoganAppDTO> list() {
        try {
            List<LoganAppDTO> list = appMapper.listAll();
            return list == null ? Collections.<LoganAppDTO>emptyList() : list;
        } catch (Exception e) {
            LOGGER.error("list apps failed: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public LoganAppDTO get(String appId) {
        try {
            return appMapper.selectByAppId(appId);
        } catch (Exception e) {
            LOGGER.error("get app failed: " + e.getMessage());
            return null;
        }
    }

    private static String cleanName(String name) {
        String n = trim(name, MAX_NAME);
        return n == null ? null : n.replaceAll("[\\/:*?\"<>|]", "_");
    }

    private static String trim(String s, int max) {
        if (StringUtils.isBlank(s)) {
            return null;
        }
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }
}
