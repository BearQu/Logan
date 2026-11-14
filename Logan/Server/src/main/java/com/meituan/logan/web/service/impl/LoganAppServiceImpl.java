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
            LoganAppDTO dto = new LoganAppDTO();
            dto.setAppId(appId.trim());
            dto.setAppName(cleanName(appName));
            dto.setPlatform(platform == null || platform <= 0 ? 1 : platform);
            dto.setLatestAppVersion(trim(appVersion, 64));
            dto.setLatestBuildVersion(trim(buildVersion, 64));
            dto.setLastDeviceId(trim(deviceId, 128));
            dto.setSource(StringUtils.isBlank(source) ? "auto_client" : source);
            dto.setAddTime(now);
            dto.setLastReportTime(now);
            if (appMapper.insertIgnore(dto) <= 0) {
                appMapper.touch(dto.getAppId(), dto.getPlatform(), dto.getLatestAppVersion(),
                        dto.getLatestBuildVersion(), dto.getLastDeviceId(), now);
            }
        } catch (Exception e) {
            LOGGER.warn("app register failed for " + appId + ": " + e.getMessage());
        }
    }

    @Override
    public LoganAppDTO reportProfile(String appId, String appName, String iconBase64, String mime,
            Integer platform, String appVersion, String buildVersion, String deviceId, String source) {
        if (StringUtils.isBlank(appId)) {
            return null;
        }
        String src = StringUtils.isBlank(source) ? "auto_client" : source.trim();
        // 已存在则不覆盖 source（人工登记过的保持 manual），新出现的应用才落来源
        LoganAppDTO exist0 = appMapper.selectByAppId(appId.trim());
        register(appId, appName, platform, appVersion, buildVersion, deviceId,
                exist0 == null ? src : exist0.getSource());
        String iconPath = null;
        String iconHash = null;
        if (StringUtils.isNotBlank(iconBase64)) {
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
        if (StringUtils.isNotBlank(appName) || iconPath != null) {
            appMapper.updateProfile(appId, cleanName(appName), iconPath, iconHash, platform,
                    exist0 == null ? src : null);
        }
        return appMapper.selectByAppId(appId);
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
