package com.meituan.logan.web.service;

import com.meituan.logan.web.dto.LoganDeviceCommandDTO;
import com.meituan.logan.web.dto.LoganDeviceViewDTO;

import java.util.List;
import java.util.Map;

/**
 * 主动拉日志（轮询命令通道，用户拍板）：控制台下发命令 -> 客户端定时带 deviceId 领取 -> 执行后回执。
 * 设备清单不另建注册表，从 logan_task / logan_crash 派生，心跳来自命令轮询。
 */
public interface LoganDeviceService {

    /** 在线判定阈值：最近一次心跳在 now-ONLINE_WINDOW_MS 内。 */
    long ONLINE_WINDOW_MS = 2L * 60 * 1000;

    /** 命令默认存活时间。 */
    long DEFAULT_TTL_MS = 30L * 60 * 1000;

    /** 领取后多久没回执判 lost。 */
    long DELIVERED_TIMEOUT_MS = 3L * 60 * 1000;

    /**
     * 下发命令。同设备同 action 若已有未过期的 pending/delivered 命令，则复用并延长存活，
     * 返回的 map 里 duplicate=true 且带原命令 id。
     */
    Map<String, Object> dispatch(String appId, String deviceId, String action, String param,
            String note, String operator, Long ttlMs);

    boolean cancel(long id);

    /** 客户端轮询：顺带写心跳、清理过期与失联命令，返回该设备待执行命令（已置 delivered）。 */
    List<LoganDeviceCommandDTO> poll(String appId, String deviceId, Integer platform,
            String appVersion, String buildVersion, String sdkVersion, Integer limit);

    /** 客户端执行完回执。ok=false 转 failed。 */
    boolean ack(long id, boolean ok, String resultNote);

    Map<String, Object> listDevices(String appId, String keyword, Integer limit, Integer offset);

    List<LoganDeviceCommandDTO> listCommands(String appId, String deviceId, Integer limit);

    /** 客户端上送设备基本信息快照（内存/磁盘/CPU/GPU/电量/网络…），一设备一行覆盖。 */
    boolean reportDeviceInfo(String appId, String deviceId, Integer platform, String info);

    /** 读取某设备最新设备信息快照，无则 null。 */
    com.meituan.logan.web.dto.LoganDeviceInfoDTO deviceInfo(String appId, String deviceId);
}
