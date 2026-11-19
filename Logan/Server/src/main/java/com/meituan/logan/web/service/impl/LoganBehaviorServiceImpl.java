package com.meituan.logan.web.service.impl;

import com.meituan.logan.web.dto.LoganBehaviorDTO;
import com.meituan.logan.web.mapper.LoganBehaviorMapper;
import com.meituan.logan.web.service.LoganBehaviorService;
import com.meituan.logan.web.util.BehaviorNorm;
import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.Logger;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// 崩溃前行为轨迹服务端实现（对标 Bugly「崩溃前行为轨迹」）。
//
// 设计要点（对齐「通用能力，不绑定单个项目」）：
// 1. 客户端只上报原始事实（类别 / 页面名 / 控件标签 / 页面 URL），类别白名单、页面名折叠、
//    URL 去敏感参数、幂等键全部在平台算 —— 任何项目或端都能直接上报。
// 2. 明细用 dedup_hash 唯一键幂等，批量重传不会重复入库。
// 3. 轨迹不做指纹聚合（它是时间流不是问题聚类），因此只有一张明细表。
// 4. 宁缺毋假：name / label / pageUrl 三者全空的条目直接丢弃，不写空轨迹。
@Service
public class LoganBehaviorServiceImpl implements LoganBehaviorService {

    private static final Logger LOGGER = Logger.getLogger(LoganBehaviorServiceImpl.class);

    private static final int MAX_EVENTS_PER_REPORT = 300;
    private static final int DEFAULT_LIMIT = 200;
    private static final int MAX_LIMIT = 1000;
    private static final long DEFAULT_WINDOW_MS = 24L * 3600L * 1000L;
    private static final long MAX_SPAN_MS = 31L * 24L * 3600L * 1000L;
    /** 设备上报时间与入库时刻的可信窗口：超出即视为设备时钟跑偏，重建为入库时刻（见 ingest 注释） */
    private static final long CLOCK_TRUST_WINDOW_MS = 5 * 60 * 1000L;
    private static final int DEFAULT_BEFORE = 180;
    private static final int DEFAULT_AFTER = 15;
    private static final int NAME_MAX = 256;
    private static final int LABEL_MAX = 1000;
    private static final int URL_MAX = 1000;

    @Resource
    private LoganBehaviorMapper behaviorMapper;

    @Override
    public Map<String, Object> report(String appId, String deviceId, Integer platform, String appVersion,
            String buildVersion, String os, String osVersion, String brand, String model,
            String networkType, List<Map<String, Object>> events) {
        Map<String, Object> result = new HashMap<String, Object>();
        int accepted = 0;
        int duplicated = 0;
        int rejected = 0;
        if (events == null || events.isEmpty()) {
            result.put("accepted", 0);
            result.put("duplicated", 0);
            result.put("rejected", 0);
            return result;
        }
        int total = Math.min(events.size(), MAX_EVENTS_PER_REPORT);
        long now = System.currentTimeMillis();
        for (int i = 0; i < total; i++) {
            LoganBehaviorDTO dto = build(appId, deviceId, platform, appVersion, buildVersion, os,
                    osVersion, brand, model, networkType, events.get(i), now);
            if (dto == null) {
                rejected++;
                continue;
            }
            int ins = 0;
            try {
                ins = behaviorMapper.insertIgnore(dto);
            } catch (Exception e) {
                LOGGER.error("insert behavior row failed appId=" + appId + " deviceId=" + deviceId
                        + " name=" + dto.getName(), e);
                continue;
            }
            if (ins <= 0) {
                duplicated++;
                continue;
            }
            accepted++;
        }
        result.put("accepted", accepted);
        result.put("duplicated", duplicated);
        result.put("rejected", rejected);
        return result;
    }

    // 由客户端原始事件构造明细：归一化与幂等键都在服务端算，客户端零逻辑。
    private LoganBehaviorDTO build(String appId, String deviceId, Integer platform, String appVersion,
            String buildVersion, String os, String osVersion, String brand, String model,
            String networkType, Map<String, Object> ev, long now) {
        if (ev == null) {
            return null;
        }
        String kind = BehaviorNorm.kindOf(str(ev.get("kind")));
        String name = cut(BehaviorNorm.normName(str(ev.get("name"))), NAME_MAX);
        String label = cut(BehaviorNorm.normLabel(str(ev.get("label"))), LABEL_MAX);
        String pageUrl = cut(BehaviorNorm.normPageUrl(str(ev.get("pageUrl"))), URL_MAX);
        // 三要素全空 = 没有任何指示意义的轨迹，丢弃而不是写一行空白（宁缺毋假）
        if (name == null && label == null && pageUrl == null) {
            return null;
        }
        long occurredAt = longOf(ev.get("occurredAt"), 0L);
        // 时钟可信性（PoC 实测教训：NAS MySQL 容器时钟偏 +1 年）：设备时间与入库时刻差超过
        // 5 分钟一律判为时钟跑偏，以入库时刻重建——否则崩溃（服务端时间）与行为（设备时间）
        // 在崩溃前后时间轴里永远对不齐。旧口径用 31 天宽容窗口，把「偏一年」的设备放进了库，
        // 导致 around.json 拿 DB 原生值查才出得来、按真实崩溃时刻查是空的。
        if (occurredAt <= 0 || Math.abs(occurredAt - now) > CLOCK_TRUST_WINDOW_MS) {
            occurredAt = now;
        }
        LoganBehaviorDTO dto = new LoganBehaviorDTO();
        dto.setAppId(appId);
        dto.setDeviceId(deviceId);
        dto.setPlatform(platform == null ? 0 : platform.intValue());
        dto.setKind(kind);
        dto.setName(name);
        dto.setLabel(label);
        dto.setPageUrl(pageUrl);
        dto.setTraceId(cut(str(ev.get("traceId")), 128));
        dto.setAppVersion(cut(str(appVersion), 64));
        dto.setBuildVersion(cut(str(buildVersion), 64));
        dto.setOs(cut(str(os), 64));
        dto.setOsVersion(cut(str(osVersion), 64));
        dto.setBrand(cut(str(brand), 128));
        dto.setModel(cut(str(model), 128));
        dto.setNetworkType(cut(str(networkType), 32));
        Long interval = longOrNull(ev.get("intervalMs"));
        if (interval != null && interval.longValue() >= 0 && interval.longValue() <= 86400000L) {
            dto.setIntervalMs(interval);
        }
        dto.setExtra(cut(str(ev.get("extra")), 4000));
        dto.setOccurredAt(occurredAt);
        dto.setDedupHash(BehaviorNorm.dedupHash(appId, deviceId, kind, nvl(name), nvl(label), occurredAt,
                nvl(str(ev.get("traceId")))));
        dto.setAddTime(now);
        return dto;
    }

    @Override
    public Map<String, Object> list(String appId, String deviceId, String kind, String name,
            Long beginTime, Long endTime, String keyword, Integer limit) {
        Map<String, Object> q = new HashMap<String, Object>();
        q.put("appId", appId);
        putIf(q, "deviceId", deviceId);
        if (StringUtils.isNotBlank(kind)) {
            q.put("kind", BehaviorNorm.kindOf(kind));
        }
        putIf(q, "name", name);
        putIf(q, "keyword", keyword);
        long[] span = span(beginTime, endTime);
        q.put("beginTime", Long.valueOf(span[0]));
        q.put("endTime", Long.valueOf(span[1]));
        q.put("limit", Integer.valueOf(clampLimit(limit)));
        Map<String, Object> r = new HashMap<String, Object>();
        List<LoganBehaviorDTO> rows = null;
        try {
            rows = behaviorMapper.list(q);
        } catch (Exception e) {
            LOGGER.warn("behavior list skipped: " + e.getMessage());
        }
        r.put("rows", rows == null ? new ArrayList<LoganBehaviorDTO>() : rows);
        long total = 0L;
        try {
            total = behaviorMapper.countList(q);
        } catch (Exception e) {
            LOGGER.warn("behavior count skipped: " + e.getMessage());
        }
        r.put("total", Long.valueOf(total));
        return r;
    }

    @Override
    public Map<String, Object> summary(String appId, Long beginTime, Long endTime, Integer platform,
            String appVersion) {
        long[] span = span(beginTime, endTime);
        Map<String, Object> raw = null;
        try {
            raw = behaviorMapper.summary(appId, Long.valueOf(span[0]), Long.valueOf(span[1]),
                    platform, appVersion);
        } catch (Exception e) {
            // 表还没建（未接入行为采集）属于正常状态，降级为不可用而不是 500
            LOGGER.warn("behavior summary skipped: " + e.getMessage());
        }
        Map<String, Object> r = new HashMap<String, Object>();
        r.put("events", raw == null ? Long.valueOf(0L) : Long.valueOf(longOf(raw.get("events"), 0L)));
        r.put("devices", raw == null ? Long.valueOf(0L) : Long.valueOf(longOf(raw.get("devices"), 0L)));
        r.put("available", Boolean.valueOf(raw != null));
        r.put("beginTime", Long.valueOf(span[0]));
        r.put("endTime", Long.valueOf(span[1]));
        return r;
    }

    @Override
    public Map<String, Object> topNames(String appId, Long beginTime, Long endTime, String kind,
            Integer platform, Integer limit) {
        long[] span = span(beginTime, endTime);
        int lim = limit == null || limit.intValue() <= 0 ? 20 : Math.min(100, limit.intValue());
        Map<String, Object> r = new HashMap<String, Object>();
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        try {
            List<Map<String, Object>> got = behaviorMapper.topNames(appId, Long.valueOf(span[0]),
                    Long.valueOf(span[1]), StringUtils.isBlank(kind) ? null : BehaviorNorm.kindOf(kind),
                    platform, Integer.valueOf(lim));
            if (got != null) {
                rows = got;
            }
        } catch (Exception e) {
            LOGGER.warn("behavior top names skipped: " + e.getMessage());
        }
        r.put("rows", rows);
        return r;
    }

    @Override
    public Map<String, Object> around(String appId, String deviceId, long crashTime, Integer beforeSeconds,
            Integer afterSeconds, Integer limit) {
        Map<String, Object> r = new HashMap<String, Object>();
        List<LoganBehaviorDTO> rows = new ArrayList<LoganBehaviorDTO>();
        if (StringUtils.isNotBlank(deviceId) && crashTime > 0) {
            int beforeSec = beforeSeconds == null || beforeSeconds.intValue() <= 0 ? DEFAULT_BEFORE
                    : Math.min(86400, beforeSeconds.intValue());
            int afterSec = afterSeconds == null || afterSeconds.intValue() < 0 ? DEFAULT_AFTER
                    : Math.min(3600, afterSeconds.intValue());
            int cap = limit == null || limit.intValue() <= 0 ? 200 : Math.min(1000, limit.intValue());
            try {
                List<LoganBehaviorDTO> got = behaviorMapper.listAround(appId, deviceId,
                        crashTime - beforeSec * 1000L, crashTime + afterSec * 1000L, Integer.valueOf(cap));
                if (got != null) {
                    rows = got;
                }
            } catch (Exception e) {
                LOGGER.warn("behavior around skipped: " + e.getMessage());
            }
        }
        r.put("rows", rows);
        r.put("total", Integer.valueOf(rows.size()));
        return r;
    }

    private static long[] span(Long beginTime, Long endTime) {
        long end = endTime == null || endTime.longValue() <= 0 ? System.currentTimeMillis() : endTime.longValue();
        long begin = beginTime == null || beginTime.longValue() <= 0 ? end - DEFAULT_WINDOW_MS : beginTime.longValue();
        if (begin > end) {
            long t = begin;
            begin = end;
            end = t;
        }
        if (end - begin > MAX_SPAN_MS) {
            begin = end - MAX_SPAN_MS;
        }
        return new long[] { begin, end };
    }

    private static int clampLimit(Integer limit) {
        if (limit == null || limit.intValue() <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(MAX_LIMIT, limit.intValue());
    }

    private static void putIf(Map<String, Object> q, String key, String value) {
        if (StringUtils.isNotBlank(value)) {
            q.put(key, value.trim());
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }

    private static String cut(String s, int max) {
        if (s == null) {
            return null;
        }
        String v = s.trim();
        if (v.isEmpty()) {
            return null;
        }
        return v.length() > max ? v.substring(0, max) : v;
    }

    private static long longOf(Object o, long def) {
        if (o == null) {
            return def;
        }
        if (o instanceof Number) {
            return ((Number) o).longValue();
        }
        try {
            return (long) Double.parseDouble(String.valueOf(o).trim());
        } catch (Exception e) {
            return def;
        }
    }

    private static Long longOrNull(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Number) {
            return Long.valueOf(((Number) o).longValue());
        }
        try {
            return Long.valueOf((long) Double.parseDouble(String.valueOf(o).trim()));
        } catch (Exception e) {
            return null;
        }
    }
}
