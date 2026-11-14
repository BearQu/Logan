package com.meituan.logan.web.mapper;

import com.meituan.logan.web.dto.LoganAppDTO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface LoganAppMapper {

    /** INSERT IGNORE，首次上报自动登记。 */
    int insertIgnore(LoganAppDTO dto);

    LoganAppDTO selectByAppId(@Param("appId") String appId);

    /** 控制台应用清单，带崩溃/日志/设备实时统计。 */
    List<LoganAppDTO> listAll();

    /** 上报时刷新活跃信息（版本、设备、时间），并顺带更新统计列。 */
    int touch(@Param("appId") String appId, @Param("platform") Integer platform,
            @Param("appVersion") String appVersion, @Param("buildVersion") String buildVersion,
            @Param("deviceId") String deviceId, @Param("lastReportTime") long lastReportTime);

    /** 客户端上报应用名/图标后补齐信息。 */
    int updateProfile(@Param("appId") String appId, @Param("appName") String appName,
            @Param("iconPath") String iconPath, @Param("iconHash") String iconHash,
            @Param("platform") Integer platform, @Param("source") String source);
}
