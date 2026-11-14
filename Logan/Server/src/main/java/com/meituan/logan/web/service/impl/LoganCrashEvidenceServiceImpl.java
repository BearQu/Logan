package com.meituan.logan.web.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.meituan.logan.web.dto.LoganCrashDTO;
import com.meituan.logan.web.dto.LoganCrashFixDTO;
import com.meituan.logan.web.dto.LoganCrashIssueDTO;
import com.meituan.logan.web.mapper.LoganCrashFixMapper;
import com.meituan.logan.web.mapper.LoganCrashMapper;
import com.meituan.logan.web.mapper.LoganCrashTimelineMapper;
import com.meituan.logan.web.service.LoganCrashEvidenceService;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.Logger;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 证据包组装 + 修复报告回传 + 同指纹自动重开。 */
@Service("loganCrashEvidenceService")
public class LoganCrashEvidenceServiceImpl implements LoganCrashEvidenceService {
    private static final Logger LOGGER = Logger.getLogger(LoganCrashEvidenceServiceImpl.class);
    private static final int DEFAULT_BEFORE = 180;   // 崩溃前 3 分钟
    private static final int DEFAULT_AFTER = 15;     // 崩溃后 15 秒（多为补传，窗口很小）
    private static final int MAX_LOG_ROWS = 400;
    private static final int MAX_LOG_CHARS = 1600;
    private static final int STATUS_FIXED = 2;

    @Resource
    private LoganCrashMapper crashMapper;
    @Resource
    private LoganCrashFixMapper fixMapper;
    @Resource
    private LoganCrashTimelineMapper timelineMapper;

    @Override
    public JSONObject evidenceForCrash(long crashId, int beforeSeconds, int afterSeconds) {
        LoganCrashDTO crash = crashMapper.selectById(crashId);
        if (crash == null) {
            return null;
        }
        JSONObject bundle = new JSONObject(true);
        bundle.put("crash", crashJson(crash, true));
        LoganCrashIssueDTO issue = crashMapper.selectIssueByFp(crash.getAppId(), crash.getFingerprint());
        if (issue != null) {
            bundle.put("issue", issueJson(issue));
            bundle.put("instances", instanceArray(crash.getAppId(), crash.getFingerprint(), 20));
        }
        bundle.put("businessLogs", timeline(crash.getAppId(), crash.getDeviceId(), crash.getCrashTime(),
                win(beforeSeconds, DEFAULT_BEFORE), win(afterSeconds, DEFAULT_AFTER), MAX_LOG_ROWS));
        bundle.put("fixReports", fixArray(crash.getAppId(), crash.getFingerprint(), 20));
        bundle.put("meta", metaNode(beforeSeconds, afterSeconds));
        bundle.put("evidenceHash", sha1(bundle.toJSONString()));
        return bundle;
    }

    @Override
    public JSONObject evidenceForIssue(long issueId, int beforeSeconds, int afterSeconds, int instanceLimit) {
        LoganCrashIssueDTO issue = crashMapper.selectIssueById(issueId);
        if (issue == null) {
            return null;
        }
        List<LoganCrashDTO> instances = crashMapper.queryInstancesByFp(issue.getAppId(),
                issue.getFingerprint(), Math.max(1, Math.min(50, instanceLimit)));
        JSONObject bundle = new JSONObject(true);
        bundle.put("issue", issueJson(issue));
        JSONArray arr = new JSONArray();
        LoganCrashDTO latest = null;
        if (instances != null) {
            for (LoganCrashDTO d : instances) {
                arr.add(crashJson(d, false));
                if (latest == null || d.getCrashTime() > latest.getCrashTime()) {
                    latest = d;
                }
            }
        }
        bundle.put("instances", arr);
        if (latest != null) {
            bundle.put("sampleCrash", crashJson(latest, true));
            bundle.put("businessLogs", timeline(latest.getAppId(), latest.getDeviceId(), latest.getCrashTime(),
                    win(beforeSeconds, DEFAULT_BEFORE), win(afterSeconds, DEFAULT_AFTER), MAX_LOG_ROWS));
        } else {
            bundle.put("businessLogs", new JSONArray());
        }
        bundle.put("fixReports", fixArray(issue.getAppId(), issue.getFingerprint(), 20));
        bundle.put("meta", metaNode(beforeSeconds, afterSeconds));
        bundle.put("evidenceHash", sha1(bundle.toJSONString()));
        return bundle;
    }

    @Override
    public JSONObject exportBundle(Map<String, Object> filter, int maxIssues, int beforeSeconds, int afterSeconds) {
        Map<String, Object> q = new HashMap<String, Object>(filter);
        int cap = Math.max(1, Math.min(50, maxIssues));
        q.put("offset", 0);
        q.put("limit", cap);
        List<LoganCrashIssueDTO> issues = crashMapper.searchIssues(q);
        JSONArray bundles = new JSONArray();
        if (issues != null) {
            for (LoganCrashIssueDTO issue : issues) {
                JSONObject one = evidenceForIssue(issue.getId(), beforeSeconds, afterSeconds, 10);
                if (one != null) {
                    bundles.add(one);
                }
            }
        }
        JSONObject root = new JSONObject(true);
        root.put("appId", filter.get("appId"));
        root.put("filter", new JSONObject(new HashMap<String, Object>(filter)));
        root.put("issueCount", bundles.size());
        root.put("exportedAt", System.currentTimeMillis());
        root.put("evidences", bundles);
        return root;
    }

    @Override
    public List<Map<String, Object>> timeline(String appId, String deviceId, long crashTime,
            int beforeSeconds, int afterSeconds, int limit) {
        if (StringUtils.isBlank(deviceId) || crashTime <= 0) {
            return Collections.emptyList();
        }
        // 控制器可能透传 0（参数缺省），统一回落默认窗口，否则窗口塌成 0 秒查不到日志
        int beforeSec = win(beforeSeconds, DEFAULT_BEFORE);
        int afterSec = win(afterSeconds, DEFAULT_AFTER);
        long begin = crashTime - beforeSec * 1000L;
        long end = crashTime + afterSec * 1000L;
        int cap = Math.max(1, Math.min(1000, limit));
        List<Map<String, Object>> merged = new ArrayList<Map<String, Object>>();
        merged.addAll(safeList(timelineMapper.queryNativeLogsAround(appId, deviceId, begin, end, cap)));
        merged.addAll(safeList(timelineMapper.queryWebLogsAround(appId, deviceId, begin, end, cap)));
        for (Map<String, Object> row : merged) {
            Object c = row.get("content");
            if (c != null && String.valueOf(c).length() > MAX_LOG_CHARS) {
                row.put("content", String.valueOf(c).substring(0, MAX_LOG_CHARS) + "...(truncated)");
                row.put("truncated", true);
            }
            long delta = longOf(row.get("logTime")) - crashTime;
            // 「紧贴崩溃」只标最后 10 秒（崩溃前的触发路径），整窗全标会失去指示意义
            row.put("nearCrash", delta <= 0 && delta >= -10_000L);
        }
        Collections.sort(merged, (a, b) -> Long.compare(longOf(a.get("logTime")), longOf(b.get("logTime"))));
        if (merged.size() > cap) {
            merged = new ArrayList<Map<String, Object>>(merged.subList(0, cap));
        }
        for (Map<String, Object> row : merged) {
            row.put("deltaMs", longOf(row.get("logTime")) - crashTime);
        }
        return merged;
    }

    @Override
    public List<Map<String, Object>> crashDays(String appId, long beginTime, long endTime) {
        try {
            return safeList(timelineMapper.queryCrashDays(appId, beginTime, endTime));
        } catch (Exception e) {
            LOGGER.warn("crashDays failed: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public JSONObject reportFix(JSONObject payload) {
        JSONObject result = new JSONObject(true);
        String appId = payload.getString("appId");
        String fingerprint = payload.getString("fingerprint");
        String fixer = payload.getString("fixer");
        if (StringUtils.isBlank(appId) || StringUtils.isBlank(fingerprint) || StringUtils.isBlank(fixer)) {
            result.put("ok", false);
            result.put("msg", "appId / fingerprint / fixer required");
            return result;
        }
        LoganCrashFixDTO dto = new LoganCrashFixDTO();
        dto.setAppId(appId.trim());
        dto.setFingerprint(fingerprint.trim());
        dto.setKind(payload.getString("kind"));
        dto.setAction(nvl(payload.getString("action"), "fix"));
        dto.setStatus(nvl(payload.getString("status"), "fixed"));
        dto.setFixer(fixer.trim());
        dto.setRootCause(cut(payload.getString("rootCause"), 500));
        dto.setDetail(payload.getString("detail"));
        Object files = payload.get("files");
        dto.setFiles(files == null ? null : (files instanceof String ? (String) files : JSON.toJSONString(files)));
        dto.setCommitUrl(cut(payload.getString("commitUrl"), 500));
        dto.setPrUrl(cut(payload.getString("prUrl"), 500));
        dto.setSource(nvl(payload.getString("source"), "dsh"));
        dto.setClient(cut(payload.getString("client"), 64));
        dto.setEvidenceHash(payload.getString("evidenceHash"));
        Long issueId = payload.getLong("issueId");
        dto.setFixer(fixer.trim());
        dto.setFixedAt(payload.getLong("fixedAt") == null ? System.currentTimeMillis() : payload.getLong("fixedAt"));
        dto.setAddTime(System.currentTimeMillis());

        LoganCrashIssueDTO issue = crashMapper.selectIssueByFp(dto.getAppId(), dto.getFingerprint());
        if (issue != null && issueId == null) {
            issueId = issue.getId();
        }
        dto.setIssueId(issueId);
        // 幂等键：同一次修改重复回传只入一条
        dto.setReportHash(sha1(String.join("|", dto.getAppId(), dto.getFingerprint(), dto.getFixer(),
                String.valueOf(dto.getFixedAt()), nvl(dto.getAction(), "-"), nvl(dto.getStatus(), "-"),
                nvl(dto.getDetail(), "-"), nvl(dto.getCommitUrl(), "-"))));

        boolean reopened = false;
        int inserted = fixMapper.insertIgnore(dto);
        if (inserted <= 0) {
            result.put("ok", true);
            result.put("duplicate", true);
            result.put("msg", "duplicate report ignored");
            return result;
        }
        if (issue != null) {
            String note = fixNote(dto);
            if ("reopen".equalsIgnoreCase(dto.getAction()) || isFixedLike(dto.getStatus())
                    || issue.getStatus() != 0) {
                // 本地说「没修好/只是备注/推翻了已修复结论」→ 问题回到未处理并累计复发次数
                if (issue.getStatus() == STATUS_FIXED || "reopen".equalsIgnoreCase(dto.getAction())) {
                    reopened = true;
                    crashMapper.reopenIssue(issue.getId(), Math.max(0, issue.getReopenCount()) + 1, note);
                } else {
                    crashMapper.updateIssueStatus(issue.getId(), 1);
                    crashMapper.updateIssueFixNote(issue.getId(), note);
                }
            } else {
                crashMapper.updateIssueFixNote(issue.getId(), note);
            }
            LoganCrashIssueDTO after = crashMapper.selectIssueById(issue.getId());
            result.put("issueStatus", after == null ? null : after.getStatus());
            result.put("reopenCount", after == null ? 0 : after.getReopenCount());
        }
        result.put("ok", true);
        result.put("fixId", dto.getId());
        result.put("reopened", reopened);
        result.put("issueId", issueId);
        result.put("msg", "fix report saved");
        LOGGER.info("crash fix reported app=" + dto.getAppId() + " fp=" + dto.getFingerprint()
                + " fixer=" + dto.getFixer() + " status=" + dto.getStatus());
        return result;
    }

    @Override
    public List<LoganCrashFixDTO> fixesOfIssue(long issueId, int limit) {
        try {
            return safeList(fixMapper.queryByIssue(issueId, Math.max(1, Math.min(100, limit))));
        } catch (Exception e) {
            LOGGER.warn("fixesOfIssue failed: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public List<LoganCrashFixDTO> fixesOfFingerprint(String appId, String fingerprint, int limit) {
        try {
            return safeList(fixMapper.queryByFingerprint(appId, fingerprint, Math.max(1, Math.min(100, limit))));
        } catch (Exception e) {
            LOGGER.warn("fixesOfFingerprint failed: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    // ---------------- helpers ----------------

    private JSONObject crashJson(LoganCrashDTO d, boolean withContent) {
        JSONObject o = new JSONObject(true);
        o.put("id", d.getId());
        o.put("appId", d.getAppId());
        o.put("kind", d.getKind());
        o.put("fingerprint", d.getFingerprint());
        o.put("deviceId", d.getDeviceId());
        o.put("unionId", d.getUnionId());
        o.put("userId", d.getUserId());
        o.put("appVersion", d.getAppVersion());
        o.put("buildVersion", d.getBuildVersion());
        o.put("brand", d.getBrand());
        o.put("model", d.getModel());
        o.put("manufacturer", d.getManufacturer());
        o.put("sdkInt", d.getSdkInt());
        o.put("releaseVer", d.getReleaseVer());
        o.put("abi", d.getAbi());
        o.put("signalInfo", d.getSignalInfo());
        o.put("topFrames", d.getTopFrames());
        o.put("deferred", d.getDeferred());
        o.put("crashTime", d.getCrashTime());
        if (withContent) {
            o.put("content", d.getContent());
        }
        return o;
    }

    private JSONObject issueJson(LoganCrashIssueDTO i) {
        JSONObject o = new JSONObject(true);
        o.put("id", i.getId());
        o.put("appId", i.getAppId());
        o.put("kind", i.getKind());
        o.put("fingerprint", i.getFingerprint());
        o.put("signalInfo", i.getSignalInfo());
        o.put("topFrames", i.getTopFrames());
        o.put("sampleContent", i.getSampleContent());
        o.put("crashCount", i.getCrashCount());
        o.put("affectedDevices", i.getAffectedDevices());
        o.put("firstCrashTime", i.getFirstCrashTime());
        o.put("lastCrashTime", i.getLastCrashTime());
        o.put("status", i.getStatus());
        o.put("reopenCount", i.getReopenCount());
        o.put("lastFixNote", i.getLastFixNote());
        o.put("llmSeverity", i.getLlmSeverity());
        o.put("llmSummary", i.getLlmSummary());
        return o;
    }

    private JSONArray instanceArray(String appId, String fingerprint, int limit) {
        JSONArray arr = new JSONArray();
        List<LoganCrashDTO> list = crashMapper.queryInstancesByFp(appId, fingerprint, limit);
        if (list != null) {
            for (LoganCrashDTO d : list) {
                arr.add(crashJson(d, false));
            }
        }
        return arr;
    }

    private JSONArray fixArray(String appId, String fingerprint, int limit) {
        JSONArray arr = new JSONArray();
        for (LoganCrashFixDTO f : fixesOfFingerprint(appId, fingerprint, limit)) {
            JSONObject o = new JSONObject(true);
            o.put("id", f.getId());
            o.put("action", f.getAction());
            o.put("status", f.getStatus());
            o.put("fixer", f.getFixer());
            o.put("rootCause", f.getRootCause());
            o.put("detail", f.getDetail());
            o.put("files", f.getFiles());
            o.put("commitUrl", f.getCommitUrl());
            o.put("prUrl", f.getPrUrl());
            o.put("source", f.getSource());
            o.put("client", f.getClient());
            o.put("evidenceHash", f.getEvidenceHash());
            o.put("fixedAt", f.getFixedAt());
            o.put("addTime", f.getAddTime());
            arr.add(o);
        }
        return arr;
    }

    private JSONObject metaNode(int beforeSeconds, int afterSeconds) {
        JSONObject meta = new JSONObject(true);
        meta.put("server", "logan-poc");
        meta.put("logWindowSeconds", new int[]{win(beforeSeconds, DEFAULT_BEFORE), win(afterSeconds, DEFAULT_AFTER)});
        meta.put("hint", "平台不含源码。在本地仓库按 topFrames/content 里的 文件名:行号 定位代码，"
                + "结合 businessLogs 时间线判断根因，修改后 POST /logan/crash/fix/report.json 回传报告"
                + "（fixer/detail/status/commitUrl，带 fingerprint 与 evidenceHash）。");
        return meta;
    }

    private String fixNote(LoganCrashFixDTO f) {
        StringBuilder sb = new StringBuilder();
        sb.append(nvl(f.getStatus(), "fixed")).append(" @").append(f.getFixedAt())
                .append(" by ").append(nvl(f.getFixer(), "-"));
        if (StringUtils.isNotBlank(f.getRootCause())) {
            sb.append(" | 根因: ").append(f.getRootCause());
        }
        if (StringUtils.isNotBlank(f.getDetail())) {
            sb.append(" | ").append(cut(f.getDetail(), 300));
        }
        return cut(sb.toString(), 1800);
    }

    private static boolean isFixedLike(String status) {
        if (StringUtils.isBlank(status)) {
            return false;
        }
        String s = status.toLowerCase();
        // fixed 也算「修复方宣称已修」：报告回传后平台置为「处理中(待验证)」，人工确认后才到已修复
        return s.equals("fixed") || s.equals("partial") || s.equals("wontfix") || s.equals("verifying") || s.equals("verified");
    }

    private static int win(int v, int def) {
        return v <= 0 ? def : Math.min(v, 6 * 3600);
    }

    private static <T> List<T> safeList(List<T> in) {
        return in == null ? new ArrayList<T>() : in;
    }

    private static long longOf(Object o) {
        if (o instanceof Number) {
            return ((Number) o).longValue();
        }
        try {
            return o == null ? 0 : Long.parseLong(String.valueOf(o));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String nvl(String s, String def) {
        return StringUtils.isBlank(s) ? def : s;
    }

    private static String cut(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static String sha1(String s) {
        return DigestUtils.sha1Hex(s.getBytes(StandardCharsets.UTF_8));
    }
}
