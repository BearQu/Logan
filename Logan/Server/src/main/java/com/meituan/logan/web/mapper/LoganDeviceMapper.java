package com.meituan.logan.web.mapper;

import com.meituan.logan.web.dto.LoganDeviceCommandDTO;
import com.meituan.logan.web.dto.LoganDeviceInfoDTO;
import com.meituan.logan.web.dto.LoganDeviceViewDTO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 主动拉日志命令 + 设备心跳 + 派生设备清单。 */
public interface LoganDeviceMapper {

    // ---- 命令 ----

    int insertCommand(LoganDeviceCommandDTO dto);

    LoganDeviceCommandDTO selectCommandById(@Param("id") long id);

    /** 取出该设备待领取命令。 */
    List<LoganDeviceCommandDTO> selectPendingCommands(@Param("appId") String appId,
            @Param("deviceId") String deviceId, @Param("now") long now, @Param("limit") int limit);

    int expirePending(@Param("now") long now);

    /** 领取即置 delivered 并计数。 */
    int markDelivered(@Param("id") long id, @Param("now") long now);

    int markAck(@Param("id") long id, @Param("status") String status,
            @Param("now") long now, @Param("resultNote") String resultNote);

    int markCancelled(@Param("id") long id);

    /** 重复下发时延长存活时间并退回 pending。 */
    int extendExpire(@Param("id") long id, @Param("expireTime") long expireTime);

    /** 领取后长时间未回执 -> lost。 */
    int markLost(@Param("before") long before);

    List<LoganDeviceCommandDTO> listCommands(@Param("appId") String appId,
            @Param("deviceId") String deviceId, @Param("limit") int limit);

    int countActiveCommands(@Param("appId") String appId, @Param("deviceId") String deviceId);

    // ---- 心跳 ----

    int upsertPoll(@Param("appId") String appId, @Param("deviceId") String deviceId,
            @Param("platform") int platform, @Param("appVersion") String appVersion,
            @Param("buildVersion") String buildVersion, @Param("sdkVersion") String sdkVersion,
            @Param("now") long now, @Param("addTime") long addTime);

    // ---- 派生设备清单 ----

    List<LoganDeviceViewDTO> listDevices(@Param("appId") String appId,
            @Param("keyword") String keyword, @Param("limit") int limit, @Param("offset") int offset);

    int countDevices(@Param("appId") String appId, @Param("keyword") String keyword);

    // ---- 设备基本信息（用户要求：拉日志时顺带采集设备状态） ----

    /** 一设备一行覆盖更新。 */
    int upsertDeviceInfo(@Param("appId") String appId, @Param("deviceId") String deviceId,
            @Param("platform") int platform, @Param("info") String info,
            @Param("reportTime") long reportTime);

    LoganDeviceInfoDTO selectDeviceInfo(@Param("appId") String appId,
            @Param("deviceId") String deviceId);
}
