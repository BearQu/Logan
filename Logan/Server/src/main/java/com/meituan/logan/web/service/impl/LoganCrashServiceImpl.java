package com.meituan.logan.web.service.impl;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.meituan.logan.web.dto.LoganCrashDTO;
import com.meituan.logan.web.dto.LoganCrashIssueDTO;
import com.meituan.logan.web.mapper.LoganCrashMapper;
import com.meituan.logan.web.service.LoganCrashService;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.Logger;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

/**
 * 崩溃入库 + 确定性聚类。LLM 定性摘要不在本类做（CrashLlmWorker 异步回填），
 * 聚类绝不用 LLM——指纹是 SDK 端确定性字符串。
 */
@Service("loganCrashService")
public class LoganCrashServiceImpl implements LoganCrashService {
    public static final int BAD = -1;
    public static final int DUPLICATE = 0;
    public static final int SAVED = 1;
    private static final Logger LOGGER = Logger.getLogger(LoganCrashServiceImpl.class);
    private static final int MAX_TRACKED_DEVICES = 500;
    private static final int MAX_SAMPLE_BYTES = 120 * 1024; // issue 样本截断，tombstone 原文在 logan_crash.content

    @Resource
    private LoganCrashMapper crashMapper;

    @Override
    public int ingest(String envelopeJson) {
        try {
            LoganCrashDTO dto = parse(envelopeJson);
            if (dto == null) {
                return BAD;
            }
            int inserted = crashMapper.insertIgnore(dto);
            if (inserted <= 0) {
                return DUPLICATE; // content_hash 撞上（通道 A 已传 / 通道 B 重扫）
            }
            aggregateToIssue(dto);
            return SAVED;
        } catch (Exception e) {
            LOGGER.error("crash ingest failed: " + e.getMessage(), e);
            return BAD;
        }
    }

    private LoganCrashDTO parse(String json) {
        if (StringUtils.isBlank(json)) {
            return null;
        }
        JSONObject o;
        try {
            o = JSONObject.parseObject(json);
        } catch (Exception e) {
            LOGGER.warn("crash envelope not json, dropped");
            return null;
        }
        if (o == null || StringUtils.isBlank(o.getString("kind")) || StringUtils.isBlank(o.getString("fingerprint"))) {
            return null;
        }
        LoganCrashDTO dto = new LoganCrashDTO();
        dto.setAppId(nvl(o.getString("appId"), "unknown"));
        dto.setPlatform(platformOf(o));
        dto.setKind(o.getString("kind"));
        dto.setFingerprint(o.getString("fingerprint"));
        dto.setDeviceId(o.getString("deviceId"));
        dto.setUnionId(o.getString("unionId"));
        dto.setUserId(o.getString("userId"));
        dto.setAppVersion(o.getString("appVersion"));
        dto.setBuildVersion(o.getString("buildVersion"));
        dto.setBrand(o.getString("brand"));
        dto.setModel(o.getString("model"));
        dto.setManufacturer(o.getString("manufacturer"));
        dto.setSdkInt(o.getInteger("sdkInt"));
        dto.setReleaseVer(o.getString("release"));
        dto.setAbi(o.getString("abi"));
        dto.setSignalInfo(o.getString("signal"));
        Object frames = o.get("topFrames");
        if (frames instanceof JSONArray) {
            dto.setTopFrames(((JSONArray) frames).toJSONString());
        } else if (frames != null) {
            dto.setTopFrames(String.valueOf(frames)); // SDK 老版本/冒烟包可能传纯文本
        }
        dto.setDeferred(Boolean.TRUE.equals(o.getBoolean("deferred")) ? 1 : 0);
        Long ct = o.getLong("crashTime");
        dto.setCrashTime(ct == null || ct <= 0 ? System.currentTimeMillis() : ct);
        dto.setAddTime(System.currentTimeMillis());
        dto.setContent(o.getString("content"));
        dto.setContentHash(DigestUtils.sha1Hex(String.join("|",
                dto.getAppId(), nvl(dto.getDeviceId(), "-"), dto.getFingerprint(),
                String.valueOf(dto.getCrashTime()), dto.getKind())));
        return dto;
    }

    private int platformOf(JSONObject o) {
        // SDK 未带 platform 字段时用 kind 线索兜底：freeze/js 多为鸿蒙，其余按 android 记
        String platform = o.getString("platform");
        if (StringUtils.isNotBlank(platform)) {
            try {
                return Integer.parseInt(platform.trim());
            } catch (NumberFormatException ignore) {
            }
        }
        String os = nvl(o.getString("os"), "");
        if (os.toLowerCase().contains("harmony")) {
            return 3;
        }
        if (os.toLowerCase().contains("ios")) {
            return 2;
        }
        return 1;
    }

    private synchronized void aggregateToIssue(LoganCrashDTO dto) {
        try {
            LoganCrashIssueDTO exist = crashMapper.selectIssueByFp(dto.getAppId(), dto.getFingerprint());
            if (exist == null) {
                LoganCrashIssueDTO issue = new LoganCrashIssueDTO();
                issue.setAppId(dto.getAppId());
                issue.setPlatform(dto.getPlatform());
                issue.setKind(dto.getKind());
                issue.setFingerprint(dto.getFingerprint());
                issue.setSampleContent(truncate(dto.getContent(), MAX_SAMPLE_BYTES));
                issue.setTopFrames(dto.getTopFrames());
                issue.setSignalInfo(dto.getSignalInfo());
                issue.setFirstCrashTime(dto.getCrashTime());
                issue.setLastCrashTime(dto.getCrashTime());
                issue.setCrashCount(1);
                issue.setAffectedDevices(deviceArray(dto.getDeviceId()));
                issue.setAddTime(System.currentTimeMillis());
                crashMapper.insertIssue(issue);
            } else {
                String devices = mergeDevice(exist.getAffectedDevices(), dto.getDeviceId());
                crashMapper.updateIssueAgg(exist.getId(), exist.getCrashCount() + 1,
                        Math.max(nvlL(exist.getLastCrashTime(), 0), dto.getCrashTime()), devices);
            }
        } catch (Exception e) {
            LOGGER.error("crash issue aggregate failed (crash row saved): " + e.getMessage());
        }
    }

    private String deviceArray(String deviceId) {
        if (StringUtils.isBlank(deviceId)) {
            return "[]";
        }
        JSONArray a = new JSONArray();
        a.add(deviceId);
        return a.toJSONString();
    }

    private String mergeDevice(String oldJson, String deviceId) {
        JSONArray a;
        try {
            a = StringUtils.isBlank(oldJson) ? new JSONArray() : JSONArray.parseArray(oldJson);
        } catch (Exception e) {
            a = new JSONArray();
        }
        if (StringUtils.isNotBlank(deviceId) && !a.contains(deviceId) && a.size() < MAX_TRACKED_DEVICES) {
            a.add(deviceId);
        }
        return a.toJSONString();
    }

    @Override
    public LoganCrashDTO getById(long id) {
        try {
            return crashMapper.selectById(id);
        } catch (Exception e) {
            LOGGER.error(e);
            return null;
        }
    }

    @Override
    public List<LoganCrashDTO> latestByDevice(String deviceId, int limit) {
        try {
            List<LoganCrashDTO> list = crashMapper.queryByDevice(deviceId, limit <= 0 ? 20 : limit);
            return list == null ? Collections.<LoganCrashDTO>emptyList() : list;
        } catch (Exception e) {
            LOGGER.error(e);
            return Collections.emptyList();
        }
    }

    @Override
    public List<LoganCrashDTO> latest(String appId, int limit) {
        try {
            List<LoganCrashDTO> list = crashMapper.queryLatest(appId, limit <= 0 ? 20 : limit);
            return list == null ? Collections.<LoganCrashDTO>emptyList() : list;
        } catch (Exception e) {
            LOGGER.error(e);
            return Collections.emptyList();
        }
    }

    private static String nvl(String s, String def) {
        return StringUtils.isBlank(s) ? def : s;
    }

    private static long nvlL(Long l, long def) {
        return l == null ? def : l;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        if (b.length <= max) {
            return s;
        }
        return s.substring(0, Math.max(0, Math.min(s.length(), max))) + "\n...(truncated)";
    }
}
