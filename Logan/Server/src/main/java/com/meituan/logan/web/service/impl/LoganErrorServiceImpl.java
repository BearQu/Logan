package com.meituan.logan.web.service.impl;

import com.meituan.logan.web.dto.LoganErrorDTO;
import com.meituan.logan.web.dto.LoganErrorIssueDTO;
import com.meituan.logan.web.mapper.LoganErrorMapper;
import com.meituan.logan.web.service.LoganErrorService;
import com.meituan.logan.web.util.ErrorNorm;
import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.Logger;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 非致命错误（错误分析）服务端实现。
 *
 * 设计要点（对齐「通用能力，不绑定单个项目」）：
 * 1. 客户端只上报原始事实（异常名 / 消息 / 堆栈 / 页面），错误名归一化、消息去噪、
 *    堆栈骨架、指纹、去重键全部在平台算 —— 换任何崩溃框架或纯 try/catch 都能直接上报。
 * 2. 明细用 dedup_hash 唯一键幂等，批量重传不会重复入库。
 * 3. 聚合按指纹累加，已标记修复的指纹再次命中自动重开并保留历史结论（与崩溃、网络同一套语义）。
 * 4. 统计口径与概览 LoganOverviewServiceImpl 的 logan_error 计数完全一致（同表同列）。
 */
@Service
public class LoganErrorServiceImpl implements LoganErrorService {

    private static final Logger LOGGER = Logger.getLogger(LoganErrorServiceImpl.class);

    private static final int MAX_EVENTS_PER_REPORT = 200;
    private static final int DEFAULT_LIMIT = 200;
    private static final int MAX_LIMIT = 1000;
    private static final int MAX_AFFECTED_DEVICES = 50;
    private static final long DEFAULT_WINDOW_MS = 24L * 3600L * 1000L;
    private static final long MAX_SPAN_MS = 31L * 24L * 3600L * 1000L;
    private static final int MSG_MAX = 4000;
    private static final int STACK_MAX = 32000;
    private static final int NAME_MAX = 256;
    private static final int FRAME_MAX = 6;

    @Resource
    private LoganErrorMapper errorMapper;

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
            Map<String, Object> ev = events.get(i);
            if (ev == null) {
                rejected++;
                continue;
            }
            LoganErrorDTO dto = build(appId, deviceId, platform, appVersion, buildVersion, os,
                    osVersion, brand, model, networkType, ev, now);
            if (dto == null) {
                rejected++;
                continue;
            }
            int ins = 0;
            try {
                ins = errorMapper.insertIgnore(dto);
            } catch (Exception e) {
                LOGGER.error("insert error detail failed appId=" + appId + " deviceId=" + deviceId
                        + " name=" + dto.getName(), e);
                continue;
            }
            if (ins <= 0) {
                duplicated++;
                continue;
            }
            accepted++;
            try {
                aggregate(dto);
            } catch (Exception e) {
                LOGGER.error("aggregate error issue failed appId=" + appId
                        + " fingerprint=" + dto.getFingerprint(), e);
            }
        }
        result.put("accepted", accepted);
        result.put("duplicated", duplicated);
        result.put("rejected", rejected);
        return result;
    }

    /** 由客户端原始事件构造明细：归一化 + 指纹 + 幂等键都在服务端算，客户端零逻辑。 */
    private LoganErrorDTO build(String appId, String deviceId, Integer platform, String appVersion,
            String buildVersion, String os, String osVersion, String brand, String model,
            String networkType, Map<String, Object> ev, long now) {
        String rawName = str(ev.get("name"));
        String rawMessage = cut(str(ev.get("message")), MSG_MAX);
        String rawStack = cut(str(ev.get("stack")), STACK_MAX);
        if (StringUtils.isBlank(rawName) && StringUtils.isBlank(rawMessage) && StringUtils.isBlank(rawStack)) {
            // 三样都没有的错误没有任何分析价值，直接丢弃而不是造一个空的
            return null;
        }
        String pageUrl = cut(str(ev.get("pageUrl")), 1000);
        String rawKind = str(ev.get("kind"));
        String kind = ErrorNorm.kindOf(rawKind, rawName, pageUrl);
        String name = cut(ErrorNorm.normName(rawName), NAME_MAX);
        if (StringUtils.isBlank(name)) {
            name = cut(StringUtils.abbreviateMiddle(StringUtils.defaultString(rawMessage),
                    "...", 120), NAME_MAX);
        }
        if (StringUtils.isBlank(name)) {
            name = StringUtils.isBlank(rawStack) ? null : cut(ErrorNorm.topFrames(rawStack, 1), NAME_MAX);
        }
        if (StringUtils.isBlank(name)) {
            return null;
        }
        long occurredAt = nzl(num(ev.get("occurredAt")), now);
        if (occurredAt > now + 3600000L || occurredAt < now - MAX_SPAN_MS * 12) {
            occurredAt = now;
        }
        LoganErrorDTO dto = new LoganErrorDTO();
        dto.setAppId(appId);
        dto.setDeviceId(deviceId);
        dto.setPlatform(platform == null ? 0 : platform.intValue());
        dto.setKind(kind);
        dto.setName(name);
        dto.setMessage(rawMessage);
        dto.setStack(rawStack);
        dto.setPageUrl(pageUrl);
        dto.setTraceId(cut(str(ev.get("traceId")), 64));
        dto.setAppVersion(cut(StringUtils.defaultIfBlank(str(ev.get("appVersion")), appVersion), 64));
        dto.setBuildVersion(cut(StringUtils.defaultIfBlank(str(ev.get("buildVersion")), buildVersion), 64));
        dto.setOs(cut(StringUtils.defaultIfBlank(str(ev.get("os")), os), 32));
        dto.setOsVersion(cut(StringUtils.defaultIfBlank(str(ev.get("osVersion")), osVersion), 64));
        dto.setBrand(cut(StringUtils.defaultIfBlank(str(ev.get("brand")), brand), 64));
        dto.setModel(cut(StringUtils.defaultIfBlank(str(ev.get("model")), model), 128));
        dto.setNetworkType(cut(StringUtils.defaultIfBlank(str(ev.get("networkType")), networkType), 32));
        dto.setFatal(bool(ev.get("fatal")) ? 1 : 0);
        dto.setExtra(cut(str(ev.get("extra")), 4000));
        dto.setOccurredAt(occurredAt);
        dto.setFingerprint(ErrorNorm.fingerprint(dto.getPlatform(), kind, name, rawMessage, rawStack));
        dto.setDedupHash(ErrorNorm.dedupHash(appId, deviceId, dto.getFingerprint(), occurredAt, dto.getTraceId()));
        dto.setAddTime(now);
        return dto;
    }

    /** 同指纹聚成一行；已修复的再次命中自动重开并保留历史结论。 */
    private void aggregate(LoganErrorDTO dto) {
        LoganErrorIssueDTO exist = errorMapper.selectIssueByFingerprint(dto.getAppId(), dto.getFingerprint());
        String affected = mergeDevices(exist == null ? null : exist.getAffectedDevices(), dto.getDeviceId());
        if (exist != null && isFixedLike(exist.getStatusFlag())) {
            errorMapper.updateIssueStatus(exist.getId(), 0, rebuildNote(exist, dto),
                    exist.getReopenCount() + 1);
        }
        LoganErrorIssueDTO issue = new LoganErrorIssueDTO();
        issue.setAppId(dto.getAppId());
        issue.setFingerprint(dto.getFingerprint());
        issue.setPlatform(dto.getPlatform());
        issue.setKind(dto.getKind());
        issue.setName(dto.getName());
        issue.setTitle(ErrorNorm.title(dto.getName(), dto.getMessage(), dto.getStack()));
        issue.setSampleMessage(dto.getMessage());
        issue.setSampleStack(cut(dto.getStack(), 8000));
        issue.setSampleDeviceId(dto.getDeviceId());
        issue.setSampleTraceId(dto.getTraceId());
        issue.setEventCount(1);
        issue.setAffectedDevices(affected);
        issue.setStatusFlag(0);
        issue.setFirstSeen(dto.getOccurredAt());
        issue.setLastSeen(dto.getOccurredAt());
        issue.setAddTime(System.currentTimeMillis());
        errorMapper.upsertIssue(issue);
    }

    @Override
    public Map<String, Object> list(String appId, String deviceId, String kind, String name,
            Long beginTime, Long endTime, String keyword, Integer limit) {
        long[] win = window(beginTime, endTime);
        Map<String, Object> q = baseQuery(appId, win);
        q.put("deviceId", StringUtils.trimToNull(deviceId));
        q.put("kind", StringUtils.trimToNull(kind));
        q.put("name", StringUtils.trimToNull(name));
        q.put("keyword", StringUtils.trimToNull(keyword));
        q.put("limit", Integer.valueOf(clampLimit(limit)));
        List<LoganErrorDTO> items = errorMapper.list(q);
        long total = 0L;
        try {
            Long c = errorMapper.countList(q);
            total = c == null ? 0L : c.longValue();
        } catch (Exception e) {
            LOGGER.warn("count error list failed appId=" + appId, e);
        }
        Map<String, Object> result = new HashMap<String, Object>();
        result.put("appId", appId);
        result.put("beginTime", Long.valueOf(win[0]));
        result.put("endTime", Long.valueOf(win[1]));
        result.put("items", items == null ? new ArrayList<LoganErrorDTO>() : items);
        result.put("count", items == null ? 0 : items.size());
        result.put("total", Long.valueOf(total));
        return result;
    }

    @Override
    public Map<String, Object> summary(String appId, Long beginTime, Long endTime,
            Integer platform, String appVersion) {
        long[] win = window(beginTime, endTime);
        Map<String, Object> row = null;
        boolean available = true;
        try {
            row = errorMapper.summary(appId, Long.valueOf(win[0]), Long.valueOf(win[1]), platform,
                    StringUtils.trimToNull(appVersion));
        } catch (Exception e) {
            // 表还没建（客户端未接入非致命采集）→ 明确告知不可用，不用 0 冒充「零错误」
            available = false;
            LOGGER.warn("summary error events unavailable appId=" + appId + " err=" + e.getMessage());
        }
        Map<String, Object> result = new HashMap<String, Object>();
        result.put("appId", appId);
        result.put("beginTime", Long.valueOf(win[0]));
        result.put("endTime", Long.valueOf(win[1]));
        result.put("available", Boolean.valueOf(available));
        if (!available) {
            result.put("note", "非致命错误采集不可用（logan_error 表缺失或查询失败）");
        }
        result.put("events", nzl(num(row == null ? null : row.get("events")), 0L));
        result.put("devices", nzl(num(row == null ? null : row.get("devices")), 0L));
        result.put("issues", nzl(num(row == null ? null : row.get("issues")), 0L));
        return result;
    }

    @Override
    public List<LoganErrorDTO> around(String appId, String deviceId, Long beginTime, Long endTime, Integer limit) {
        if (StringUtils.isBlank(appId) || StringUtils.isBlank(deviceId)) {
            return new ArrayList<LoganErrorDTO>();
        }
        long b = nzl(beginTime, 0L);
        long e = nzl(endTime, System.currentTimeMillis());
        if (e < b) {
            long t = b;
            b = e;
            e = t;
        }
        if (e - b > MAX_SPAN_MS) {
            b = e - MAX_SPAN_MS;
        }
        List<LoganErrorDTO> rows = errorMapper.listAround(appId.trim(), deviceId.trim(),
                Long.valueOf(b), Long.valueOf(e), Integer.valueOf(clampLimit(limit)));
        return rows == null ? new ArrayList<LoganErrorDTO>() : rows;
    }

    @Override
    public Map<String, Object> issues(String appId, Integer statusFlag, String kind, Integer platform,
            String assignee, String keyword, Integer limit) {
        Map<String, Object> q = new HashMap<String, Object>();
        q.put("appId", appId);
        q.put("statusFlag", statusFlag);
        q.put("kind", StringUtils.trimToNull(kind));
        q.put("platform", platform);
        q.put("assignee", StringUtils.trimToNull(assignee));
        q.put("keyword", StringUtils.trimToNull(keyword));
        q.put("limit", Integer.valueOf(clampLimit(limit)));
        List<LoganErrorIssueDTO> items = errorMapper.listIssues(q);
        Map<String, Object> result = new HashMap<String, Object>();
        result.put("appId", appId);
        result.put("items", items == null ? new ArrayList<LoganErrorIssueDTO>() : items);
        result.put("count", items == null ? 0 : items.size());
        return result;
    }

    @Override
    public LoganErrorIssueDTO issueDetail(Long id) {
        if (id == null || id.longValue() <= 0) {
            return null;
        }
        return errorMapper.selectIssueById(id.longValue());
    }

    @Override
    public List<Map<String, Object>> topNames(String appId, Long beginTime, Long endTime,
            Integer platform, String appVersion, Integer limit) {
        long[] win = window(beginTime, endTime);
        int lim = limit == null || limit.intValue() <= 0 ? 10 : Math.min(limit.intValue(), 50);
        List<Map<String, Object>> rows = null;
        try {
            rows = errorMapper.topNames(appId, Long.valueOf(win[0]), Long.valueOf(win[1]), platform,
                    StringUtils.trimToNull(appVersion), Integer.valueOf(lim));
        } catch (Exception e) {
            LOGGER.warn("topNames failed appId=" + appId, e);
        }
        return rows == null ? new ArrayList<Map<String, Object>>() : rows;
    }

    @Override
    public boolean updateIssueStatus(Long id, Integer statusFlag, String lastFixNote) {
        if (id == null || id.longValue() <= 0 || statusFlag == null) {
            return false;
        }
        int flag = statusFlag.intValue();
        if (flag < 0 || flag > 3) {
            return false;
        }
        LoganErrorIssueDTO exist = errorMapper.selectIssueById(id.longValue());
        if (exist == null) {
            return false;
        }
        return errorMapper.updateIssueStatus(id.longValue(), flag, cut(lastFixNote, 4000),
                Integer.valueOf(exist.getReopenCount())) > 0;
    }

    // ---------- 私有工具 ----------

    private Map<String, Object> baseQuery(String appId, long[] win) {
        Map<String, Object> q = new HashMap<String, Object>();
        q.put("appId", appId);
        q.put("beginTime", Long.valueOf(win[0]));
        q.put("endTime", Long.valueOf(win[1]));
        return q;
    }

    /** 影响设备去重累加（逗号分隔），只保留最近 MAX_AFFECTED_DEVICES 台。 */
    private static String mergeDevices(String existing, String deviceId) {
        Set<String> set = new LinkedHashSet<String>();
        if (StringUtils.isNotBlank(existing)) {
            for (String s : existing.split(",")) {
                String t = s.trim();
                if (t.length() > 0) {
                    set.add(t);
                }
            }
        }
        if (StringUtils.isNotBlank(deviceId)) {
            set.add(deviceId.trim());
        }
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (String s : set) {
            if (n >= MAX_AFFECTED_DEVICES) {
                break;
            }
            if (sb.length() > 0) {
                sb.append(",");
            }
            sb.append(s);
            n++;
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private long[] window(Long beginTime, Long endTime) {
        long now = System.currentTimeMillis();
        long e = nzl(endTime, now);
        long b = nzl(beginTime, e - DEFAULT_WINDOW_MS);
        if (b > e) {
            long t = b;
            b = e;
            e = t;
        }
        if (e - b > MAX_SPAN_MS) {
            b = e - MAX_SPAN_MS;
        }
        return new long[] { b, e };
    }

    private int clampLimit(Integer limit) {
        if (limit == null || limit.intValue() <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit.intValue(), MAX_LIMIT);
    }

    private static boolean isFixedLike(int statusFlag) {
        return statusFlag == 2 || statusFlag == 3;
    }

    private static String rebuildNote(LoganErrorIssueDTO exist, LoganErrorDTO dto) {
        StringBuilder sb = new StringBuilder();
        sb.append("已修复结论被推翻：同指纹错误再次发生（设备 ")
                .append(StringUtils.defaultString(dto.getDeviceId())).append("，时间 ")
                .append(dto.getOccurredAt()).append("）。");
        if (StringUtils.isNotBlank(exist.getLastFixNote())) {
            sb.append(" 历史处置：").append(cut(exist.getLastFixNote(), 2000));
        }
        return cut(sb.toString(), 4000);
    }

    private static String str(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof String) {
            return (String) v;
        }
        return String.valueOf(v);
    }

    private static Long num(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number) {
            return Long.valueOf(((Number) v).longValue());
        }
        String s = String.valueOf(v).trim();
        if (s.length() == 0) {
            return null;
        }
        try {
            return Long.valueOf((long) Double.parseDouble(s));
        } catch (Exception e) {
            return null;
        }
    }

    private static long nzl(Long v, long def) {
        return v == null ? def : v.longValue();
    }

    private static boolean bool(Object v) {
        if (v == null) {
            return false;
        }
        if (v instanceof Boolean) {
            return ((Boolean) v).booleanValue();
        }
        String s = String.valueOf(v).trim();
        return "true".equalsIgnoreCase(s) || "1".equals(s);
    }

    private static String cut(String v, int max) {
        if (v == null) {
            return null;
        }
        String s = v.trim();
        if (s.length() == 0) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
