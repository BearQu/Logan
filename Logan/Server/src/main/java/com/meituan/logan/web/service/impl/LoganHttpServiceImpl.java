package com.meituan.logan.web.service.impl;

import com.alibaba.fastjson.JSON;
import com.meituan.logan.web.dto.LoganHttpDTO;
import com.meituan.logan.web.dto.LoganHttpIssueDTO;
import com.meituan.logan.web.mapper.LoganHttpMapper;
import com.meituan.logan.web.service.LoganHttpService;
import com.meituan.logan.web.util.HttpNorm;
import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.Logger;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 网络请求观测服务端实现。
 *
 * 设计要点（对应用户要求的「通用能力，不绑定单个项目」）：
 * 1. 客户端只上报原始事实（url / method / status / 原始异常名 / 耗时），
 *    归一化、脱敏、指纹、错误归类全部在平台做 —— 换任何 HTTP 库、任何业务都不用改客户端。
 * 2. 明细用 dedup_hash 唯一键幂等，客户端批量重传不会重复入库。
 * 3. 聚合按指纹累加（同接口同类问题一行），慢与失败分别计数；已修复指纹再命中自动重开并保留历史结论。
 */
@Service
public class LoganHttpServiceImpl implements LoganHttpService {

    private static final Logger LOGGER = Logger.getLogger(LoganHttpServiceImpl.class);

    private static final int MAX_EVENTS_PER_REPORT = 200;
    private static final int DEFAULT_LIMIT = 200;
    private static final int MAX_LIMIT = 1000;
    private static final int MAX_AFFECTED_DEVICES = 50;
    private static final long DEFAULT_WINDOW_MS = 24L * 3600L * 1000L;
    private static final long MAX_SPAN_MS = 31L * 24L * 3600L * 1000L;
    private static final int MSG_MAX = 1000;

    @Resource
    private LoganHttpMapper httpMapper;

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
            LoganHttpDTO dto = build(appId, deviceId, platform, appVersion, buildVersion, os,
                    osVersion, brand, model, networkType, ev, now);
            if (dto == null) {
                rejected++;
                continue;
            }
            int ins = 0;
            try {
                ins = httpMapper.insertIgnore(dto);
            } catch (Exception e) {
                LOGGER.error("insert http detail failed appId=" + appId + " deviceId=" + deviceId
                        + " url=" + dto.getUrl(), e);
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
                LOGGER.error("aggregate http issue failed appId=" + appId
                        + " fingerprint=" + dto.getFingerprint(), e);
            }
        }
        result.put("accepted", accepted);
        result.put("duplicated", duplicated);
        result.put("rejected", rejected);
        return result;
    }


    /** 由客户端原始事件构造明细：归一化 + 脱敏 + 指纹 + 幂等键都在服务端算。 */
    private LoganHttpDTO build(String appId, String deviceId, Integer platform, String appVersion,
            String buildVersion, String os, String osVersion, String brand, String model,
            String networkType, Map<String, Object> ev, long now) {
        String url = str(ev.get("url"));
        if (StringUtils.isBlank(url)) {
            return null;
        }
        String kind = StringUtils.defaultIfBlank(str(ev.get("kind")), "native").trim().toLowerCase(Locale.ROOT);
        if (!"native".equals(kind) && !"h5".equals(kind)) {
            kind = "native";
        }
        String method = StringUtils.defaultIfBlank(str(ev.get("method")), "GET").trim().toUpperCase(Locale.ROOT);
        if (method.length() > 16) {
            method = method.substring(0, 16);
        }
        Integer status = num(ev.get("status")) == null ? null : num(ev.get("status")).intValue();
        int st = status == null ? 0 : status.intValue();
        String rawErrKind = str(ev.get("errKind"));
        String errMsg = cut(str(ev.get("errMsg")), MSG_MAX);
        String errKind = HttpNorm.errKindOf(rawErrKind, errMsg, status);
        String scrubbed = HttpNorm.scrubUrl(url);
        String host = HttpNorm.host(url);
        String normPath = HttpNorm.path(url);
        long occurredAt = nzl(num(ev.get("occurredAt")), now);
        // 客户端时钟明显跑偏（未来 / 远古）时以服务端时间兜住，避免时间轴查询落空
        if (occurredAt > now + 3600000L || occurredAt < now - MAX_SPAN_MS * 12) {
            occurredAt = now;
        }
        long durationMs = Math.max(0L, nzl(num(ev.get("durationMs")), 0L));
        LoganHttpDTO dto = new LoganHttpDTO();
        dto.setAppId(appId);
        dto.setDeviceId(deviceId);
        dto.setPlatform(platform == null ? 0 : platform.intValue());
        dto.setKind(kind);
        dto.setMethod(method);
        dto.setUrl(scrubbed);
        dto.setHost(host);
        dto.setPath(normPath);
        dto.setStatus(st);
        dto.setErrKind(errKind);
        dto.setErrMsg(errMsg);
        dto.setDurationMs(durationMs);
        dto.setConnectMs(num(ev.get("connectMs")));
        dto.setDnsMs(num(ev.get("dnsMs")));
        dto.setTtfbMs(num(ev.get("ttfbMs")));
        dto.setReqBytes(num(ev.get("reqBytes")));
        dto.setRespBytes(num(ev.get("respBytes")));
        boolean slow = bool(ev.get("slow"));
        dto.setSlow(slow);
        dto.setPageUrl(cut(str(ev.get("pageUrl")), 1000));
        dto.setTraceId(cut(str(ev.get("traceId")), 64));
        dto.setAppVersion(cut(StringUtils.defaultIfBlank(str(ev.get("appVersion")), appVersion), 64));
        dto.setBuildVersion(cut(StringUtils.defaultIfBlank(str(ev.get("buildVersion")), buildVersion), 64));
        dto.setOs(cut(StringUtils.defaultIfBlank(str(ev.get("os")), os), 32));
        dto.setOsVersion(cut(StringUtils.defaultIfBlank(str(ev.get("osVersion")), osVersion), 64));
        dto.setBrand(cut(StringUtils.defaultIfBlank(str(ev.get("brand")), brand), 64));
        dto.setModel(cut(StringUtils.defaultIfBlank(str(ev.get("model")), model), 128));
        dto.setNetworkType(cut(StringUtils.defaultIfBlank(str(ev.get("networkType")), networkType), 32));
        dto.setExtra(cut(str(ev.get("extra")), 4000));
        dto.setOccurredAt(occurredAt);
        dto.setFingerprint(HttpNorm.fingerprint(kind, method, host, normPath, st, errKind));
        dto.setDedupHash(HttpNorm.dedupHash(appId, deviceId, dto.getTraceId(), occurredAt, scrubbed, method, st));
        dto.setAddTime(now);
        return dto;
    }

    /**
     * 只有「值得看」的事件才进聚合表：失败（4xx/5xx 或传输错误）或慢请求。
     * 成功且不慢的请求只落明细，供统计与时间轴互证使用。
     */
    private void aggregate(LoganHttpDTO dto) {
        boolean failed = dto.getStatus() >= 400 || StringUtils.isNotBlank(dto.getErrKind());
        if (!failed && !dto.isSlow()) {
            return;
        }
        LoganHttpIssueDTO issue = new LoganHttpIssueDTO();
        issue.setAppId(dto.getAppId());
        issue.setFingerprint(dto.getFingerprint());
        issue.setKind(dto.getKind());
        issue.setMethod(dto.getMethod());
        issue.setHost(dto.getHost());
        issue.setPath(dto.getPath());
        issue.setStatus(dto.getStatus());
        issue.setErrKind(dto.getErrKind());
        issue.setTitle(HttpNorm.title(dto.getMethod(), dto.getHost(), dto.getPath(),
                dto.getStatus(), dto.getErrKind()));
        issue.setSampleUrl(dto.getUrl());
        issue.setSampleDeviceId(dto.getDeviceId());
        issue.setSampleTraceId(dto.getTraceId());
        issue.setErrorCount(failed ? 1 : 0);
        issue.setSlowCount(dto.isSlow() ? 1 : 0);
        issue.setTotalCount(1);
        issue.setAvgMs(dto.getDurationMs());
        issue.setMaxMs(dto.getDurationMs());
        issue.setLastErrMsg(dto.getErrMsg());
        issue.setAffectedDevices(dto.getDeviceId());
        issue.setStatusFlag(0);
        issue.setFirstSeen(dto.getOccurredAt());
        issue.setLastSeen(dto.getOccurredAt());
        issue.setAddTime(System.currentTimeMillis());
        LoganHttpIssueDTO exist = httpMapper.selectIssueByFingerprint(dto.getAppId(), dto.getFingerprint());
        if (exist != null && isFixedLike(exist.getStatusFlag())) {
            // 已标记修复又出现同指纹 → 重开并保留历史修复全文，和崩溃侧同一套语义
            httpMapper.updateIssueStatus(exist.getId(), 0, rebuildNote(exist, dto),
                    exist.getReopenCount() + 1);
        }
        httpMapper.upsertIssue(issue);
    }


    @Override
    public Map<String, Object> list(String appId, String deviceId, String kind, String host, String path,
            String errKind, Integer minStatus, Integer maxStatus, Boolean slowOnly,
            Long beginTime, Long endTime, String keyword, Integer limit) {
        long[] win = window(beginTime, endTime);
        int lim = clampLimit(limit);
        List<LoganHttpDTO> items = httpMapper.list(appId, deviceId, StringUtils.trimToNull(kind), StringUtils.trimToNull(host),
                StringUtils.trimToNull(path), StringUtils.trimToNull(errKind), minStatus, maxStatus, slowOnly,
                win[0], win[1], StringUtils.trimToNull(keyword), lim);
        Map<String, Object> result = new HashMap<String, Object>();
        result.put("appId", appId);
        result.put("beginTime", win[0]);
        result.put("endTime", win[1]);
        result.put("limit", lim);
        result.put("items", items == null ? new ArrayList<LoganHttpDTO>() : items);
        result.put("count", items == null ? 0 : items.size());
        return result;
    }

    @Override
    public Map<String, Object> summary(String appId, Long beginTime, Long endTime) {
        long[] win = window(beginTime, endTime);
        Map<String, Object> row = null;
        try {
            row = httpMapper.summary(appId, win[0], win[1]);
        } catch (Exception e) {
            LOGGER.error("summary http failed appId=" + appId, e);
        }
        Map<String, Object> result = new HashMap<String, Object>();
        result.put("appId", appId);
        result.put("beginTime", win[0]);
        result.put("endTime", win[1]);
        if (row != null) {
            result.putAll(row);
        }
        result.put("total", nzl(num(row == null ? null : row.get("total")), 0L));
        result.put("slowCount", nzl(num(row == null ? null : row.get("slowCount")), 0L));
        result.put("httpErrorCount", nzl(num(row == null ? null : row.get("httpErrorCount")), 0L));
        result.put("transportErrorCount", nzl(num(row == null ? null : row.get("transportErrorCount")), 0L));
        result.put("avgMs", nzl(num(row == null ? null : row.get("avgMs")), 0L));
        result.put("maxMs", nzl(num(row == null ? null : row.get("maxMs")), 0L));
        result.put("deviceCount", nzl(num(row == null ? null : row.get("deviceCount")), 0L));
        return result;
    }

    @Override
    public List<LoganHttpDTO> around(String appId, String deviceId, Long beginTime, Long endTime, Integer limit) {
        if (StringUtils.isBlank(appId) || StringUtils.isBlank(deviceId)) {
            return new ArrayList<LoganHttpDTO>();
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
        List<LoganHttpDTO> rows = httpMapper.listAround(appId, deviceId, b, e, clampLimit(limit));
        return rows == null ? new ArrayList<LoganHttpDTO>() : rows;
    }

    @Override
    public Map<String, Object> issues(String appId, Integer statusFlag, String kind, String errKind, Integer limit) {
        int lim = clampLimit(limit);
        List<LoganHttpIssueDTO> items = httpMapper.listIssues(appId, statusFlag, StringUtils.trimToNull(kind),
                StringUtils.trimToNull(errKind), lim);
        Map<String, Object> result = new HashMap<String, Object>();
        result.put("appId", appId);
        result.put("items", items == null ? new ArrayList<LoganHttpIssueDTO>() : items);
        result.put("count", items == null ? 0 : items.size());
        return result;
    }

    @Override
    public LoganHttpIssueDTO issueDetail(Long id) {
        if (id == null || id.longValue() <= 0) {
            return null;
        }
        return httpMapper.selectIssueById(id.longValue());
    }

    @Override
    public boolean updateIssueStatus(Long id, Integer statusFlag, String lastFixNote) {
        if (id == null || id.longValue() <= 0 || statusFlag == null) {
            return false;
        }
        int flag = statusFlag.intValue();
        if (flag < 0 || flag > 2) {
            return false;
        }
        LoganHttpIssueDTO exist = httpMapper.selectIssueById(id.longValue());
        if (exist == null) {
            return false;
        }
        int reopen = exist.getReopenCount();
        return httpMapper.updateIssueStatus(id.longValue(), flag, cut(lastFixNote, 4000), reopen) > 0;
    }

    // ---------- 私有工具 ----------

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

    private static boolean isFixedLike(Integer statusFlag) {
        return statusFlag != null && statusFlag.intValue() == 2;
    }

    private static String rebuildNote(LoganHttpIssueDTO exist, LoganHttpDTO dto) {
        StringBuilder sb = new StringBuilder();
        sb.append("已修复结论被推翻：同指纹网络请求再次出现（样本 ").append(dto.getUrl());
        sb.append(", 设备 ").append(StringUtils.defaultString(dto.getDeviceId())).append("）。");
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

