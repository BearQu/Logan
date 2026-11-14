package com.meituan.logan.web.mapper;

import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 崩溃与业务日志互证用查询。native 日志解析后在 logan_log_detail（靠 task_id 关联
 * logan_task 拿 appId/deviceId），H5 日志在 web_detail（关联 web_task）。
 */
public interface LoganCrashTimelineMapper {

    /** 同设备崩溃前后的业务日志（native + H5 合并由服务层做）。 */
    List<Map<String, Object>> queryNativeLogsAround(@Param("appId") String appId,
            @Param("deviceId") String deviceId, @Param("beginTime") long beginTime,
            @Param("endTime") long endTime, @Param("limit") int limit);

    List<Map<String, Object>> queryWebLogsAround(@Param("appId") String appId,
            @Param("deviceId") String deviceId, @Param("beginTime") long beginTime,
            @Param("endTime") long endTime, @Param("limit") int limit);

    /** 日志列表用：某 app 某几天有崩溃的设备与日期（做徽标）。 */
    List<Map<String, Object>> queryCrashDays(@Param("appId") String appId,
            @Param("beginTime") long beginTime, @Param("endTime") long endTime);
}
