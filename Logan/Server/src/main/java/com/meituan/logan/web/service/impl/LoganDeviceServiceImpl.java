package com.meituan.logan.web.service.impl;

import com.meituan.logan.web.dto.LoganDeviceCommandDTO;
import com.meituan.logan.web.dto.LoganDeviceViewDTO;
import com.meituan.logan.web.mapper.LoganDeviceMapper;
import com.meituan.logan.web.service.LoganDeviceService;
import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.Logger;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 主动拉日志实现。命令生命周期全部由本类与 mapper 掌控：
 * pending --领取--> delivered --回执--> acked/failed；未领取过期 -> expired；领取后不回执 -> lost。
 */
@Service
public class LoganDeviceServiceImpl implements LoganDeviceService {

    private static final Logger LOGGER = Logger.getLogger(LoganDeviceServiceImpl.class);

    private static final int MAX_COMMANDS_PER_POLL = 5;

    private static final int MAX_PARAM_LEN = 500;

    private static final int MAX_NOTE_LEN = 500;

    private static final int DEFAULT_LIMIT = 200;

    private static final int MAX_LIMIT = 500;

    private static final long MIN_TTL_MS = 30L * 1000;

    private static final long MAX_TTL_MS = 24L * 60 * 60 * 1000;

    private static final long MAX_DELAY_MS = 5L * 60 * 1000;

    @Resource
    private LoganDeviceMapper deviceMapper;

    @Override
    public Map<String, Object> dispatch(String appId, String deviceId, String action, String param,
            String note, String operator, Long ttlMs) {
        Map<String, Object> result = new HashMap<String, Object>();
        long now = System.currentTimeMillis();
        long ttl = clampTtl(ttlMs);
        // 先清理过期/失联，保证查重结果干净
        safeSweep(now);
        LoganDeviceCommandDTO dup = findActiveDuplicate(appId, deviceId, action, now);
        if (dup != null) {
            // 同设备同动作已在队列里：延长存活，不重复下发，避免客户端一次收到多条同样命令
            LoganDeviceCommandDTO patch = new LoganDeviceCommandDTO();
            patch.setId(dup.getId());
            deviceMapper.extendExpire(dup.getId(), now + ttl);
            result.put("duplicate", Boolean.TRUE);
            result.put("commandId", Long.valueOf(dup.getId()));
            result.put("status", dup.getStatus());
            result.put("expireTime", Long.valueOf(now + ttl));
            return result;
        }
        LoganDeviceCommandDTO dto = new LoganDeviceCommandDTO();
        dto.setAppId(appId);
        dto.setDeviceId(deviceId);
        dto.setAction(action);
        dto.setParam(ellipsize(param, MAX_PARAM_LEN));
        dto.setStatus("pending");
        dto.setOperator(ellipsize(operator, 60));
        dto.setNote(ellipsize(note, MAX_NOTE_LEN));
        dto.setDeliverCount(0);
        dto.setAddTime(now);
        dto.setExpireTime(now + ttl);
        int n = deviceMapper.insertCommand(dto);
        if (n <= 0 || dto.getId() <= 0) {
            result.put("error", "dispatch command failed");
            return result;
        }
        result.put("duplicate", Boolean.FALSE);
        result.put("commandId", Long.valueOf(dto.getId()));
        result.put("status", "pending");
        result.put("expireTime", Long.valueOf(dto.getExpireTime()));
        LOGGER.info("logan command dispatched id=" + dto.getId() + " app=" + appId
                + " device=" + deviceId + " action=" + action);
        return result;
    }

    private LoganDeviceCommandDTO findActiveDuplicate(String appId, String deviceId, String action, long now) {
        List<LoganDeviceCommandDTO> active = deviceMapper.listCommands(appId, deviceId, MAX_COMMANDS_PER_POLL);
        if (active == null) {
            return null;
        }
        for (LoganDeviceCommandDTO c : active) {
            if (!StringUtils.equals(c.getAction(), action)) {
                continue;
            }
            if (!"pending".equals(c.getStatus()) && !"delivered".equals(c.getStatus())) {
                continue;
            }
            if (c.getExpireTime() > now) {
                return c;
            }
        }
        return null;
    }

    @Override
    public boolean cancel(long id) {
        return deviceMapper.markCancelled(id) > 0;
    }

    @Override
    public List<LoganDeviceCommandDTO> poll(String appId, String deviceId, Integer platform,
            String appVersion, String buildVersion, String sdkVersion, Integer limit) {
        long now = System.currentTimeMillis();
        safeSweep(now);
        try {
            deviceMapper.upsertPoll(appId, deviceId, nzPlatform(platform), ellipsize(appVersion, 60),
                    ellipsize(buildVersion, 60), ellipsize(sdkVersion, 30), now, now);
        } catch (Exception e) {
            // 心跳失败绝不能影响命令下发
            LOGGER.warn("logan device poll heartbeat failed: " + e.getMessage());
        }
        int max = limit == null || limit <= 0 ? MAX_COMMANDS_PER_POLL : Math.min(limit.intValue(), MAX_COMMANDS_PER_POLL);
        List<LoganDeviceCommandDTO> pending = deviceMapper.selectPendingCommands(appId, deviceId, now, max);
        if (pending == null || pending.isEmpty()) {
            return new ArrayList<LoganDeviceCommandDTO>();
        }
        List<LoganDeviceCommandDTO> handed = new ArrayList<LoganDeviceCommandDTO>();
        for (LoganDeviceCommandDTO c : pending) {
            if (deviceMapper.markDelivered(c.getId(), now) > 0) {
                // 只有真正抢到状态跃变的才交给客户端，避免并发轮询重复执行
                c.setStatus("delivered");
                c.setDeliveredTime(Long.valueOf(now));
                c.setDeliverCount(c.getDeliverCount() + 1);
                handed.add(c);
            }
        }
        return handed;
    }

    @Override
    public boolean ack(long id, boolean ok, String resultNote) {
        String status = ok ? "acked" : "failed";
        return deviceMapper.markAck(id, status, System.currentTimeMillis(), ellipsize(resultNote, MAX_NOTE_LEN)) > 0;
    }

    @Override
    public Map<String, Object> listDevices(String appId, String keyword, Integer limit, Integer offset) {
        int lim = limit == null || limit <= 0 ? DEFAULT_LIMIT : Math.min(limit.intValue(), MAX_LIMIT);
        int off = offset == null || offset < 0 ? 0 : offset.intValue();
        String kw = StringUtils.isBlank(keyword) ? null : keyword.trim();
        long onlineAfter = System.currentTimeMillis() - ONLINE_WINDOW_MS;
        List<LoganDeviceViewDTO> list = deviceMapper.listDevices(appId, kw, lim, off);
        if (list == null) {
            list = new ArrayList<LoganDeviceViewDTO>();
        }
        for (LoganDeviceViewDTO d : list) {
            Long p = d.getLastPollTime();
            d.setOnline(p != null && p.longValue() >= onlineAfter);
        }
        int total = deviceMapper.countDevices(appId, kw);
        Map<String, Object> result = new HashMap<String, Object>();
        result.put("devices", list);
        result.put("total", Integer.valueOf(total));
        result.put("onlineWindowMs", Long.valueOf(ONLINE_WINDOW_MS));
        return result;
    }

    @Override
    public List<LoganDeviceCommandDTO> listCommands(String appId, String deviceId, Integer limit) {
        int lim = limit == null || limit <= 0 ? 50 : Math.min(limit.intValue(), MAX_LIMIT);
        List<LoganDeviceCommandDTO> list = deviceMapper.listCommands(appId, deviceId, lim);
        return list == null ? new ArrayList<LoganDeviceCommandDTO>() : list;
    }

    /** 过期与失联清理，失败只记日志。 */
    private void safeSweep(long now) {
        try {
            deviceMapper.expirePending(now);
            deviceMapper.markLost(now - DELIVERED_TIMEOUT_MS);
        } catch (Exception e) {
            LOGGER.warn("logan command sweep failed: " + e.getMessage());
        }
    }

    private static long clampTtl(Long ttlMs) {
        if (ttlMs == null || ttlMs.longValue() <= 0) {
            return DEFAULT_TTL_MS;
        }
        long v = ttlMs.longValue();
        if (v < MIN_TTL_MS) {
            return MIN_TTL_MS;
        }
        return v > MAX_TTL_MS ? MAX_TTL_MS : v;
    }

    private static int nzPlatform(Integer platform) {
        if (platform == null) {
            return 1;
        }
        int v = platform.intValue();
        if (v < 1) {
            return 1;
        }
        return v > 3 ? 3 : v;
    }

    private static String ellipsize(String v, int max) {
        if (v == null) {
            return null;
        }
        String s = v.trim();
        if (s.isEmpty()) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
