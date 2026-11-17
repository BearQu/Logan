package com.meituan.logan.web.service.impl;

import com.meituan.logan.web.mapper.LoganOverviewMapper;
import com.meituan.logan.web.service.LoganOverviewService;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 异常概览看板实现。
 *
 * 口径（对标 Bugly，写死在此处便于统一）：
 *  - 联网设备数 = 窗口内在任一上报通道（日志任务/命令轮询/崩溃/网络）出现过的去重设备数；
 *  - 设备崩溃率 = 崩溃去重设备数 / 联网设备数；次数崩溃率 = 崩溃事件数 / 联网设备数；
 *  - ANR、错误（非致命异常）同理；分母为 0 时率返回 null（宁缺毋假，绝不显示 0.00%）；
 *  - 错误率数据源为 logan_error 表（P1 采集上线前该表不存在）→ available=false 并在响应里标注，前端显示「待接入」而不是 0。
 */
@Service
public class LoganOverviewServiceImpl implements LoganOverviewService {

    private static final long HOUR = 3600_000L;
    private static final long DAY = 24L * HOUR;
    private static final long DEFAULT_SPAN = 7L * DAY;
    private static final int TOP_LIMIT = 20;
    private static final int RANK_LIMIT = 5;
    private static final int MAX_BUCKETS = 240;
    private static final int DEFAULT_TZ_MINUTES = 480;

    @Resource
    private LoganOverviewMapper overviewMapper;

    @javax.annotation.Resource
    private javax.sql.DataSource dataSource;

    /** logan_error 表存在性缓存（P1 前不存在，探测一次即可，建表后重启或过 TTL 自动感知）。 */
    private volatile long errorTableCheckedAt;
    private volatile boolean errorTableExists;

    @Override
    public Map<String, Object> overview(String appId, Integer platform, String appVersion, String compareVersion,
                                        String granularity, Long beginTime, Long endTime, Integer tzOffsetMinutes) {
        long end = endTime == null || endTime <= 0 ? System.currentTimeMillis() : endTime;
        long begin = beginTime == null || beginTime <= 0 ? end - DEFAULT_SPAN : beginTime;
        if (begin >= end) {
            begin = Math.max(0, end - DEFAULT_SPAN);
        }
        int tzMin = tzOffsetMinutes == null ? DEFAULT_TZ_MINUTES : clamp(tzOffsetMinutes, -720, 840);
        long tzOffsetMs = tzMin * 60_000L;
        String gran = "hour".equalsIgnoreCase(granularity) ? "hour" : ("day".equalsIgnoreCase(granularity) ? "day" : null);
        if (gran == null) {
            gran = (end - begin) <= 2 * DAY ? "hour" : "day";
        }

        Map<String, Object> q = buildQuery(appId, platform, appVersion, begin, end, tzOffsetMs);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("appId", appId);
        out.put("beginTime", begin);
        out.put("endTime", end);
        out.put("granularity", gran);
        out.put("timezoneOffsetMinutes", tzMin);

        long onlineDevices = nz(overviewMapper.countOnlineDevices(q));

        Map<String, Object> counts = overviewMapper.summaryCounts(q);
        long crashEvents = lget(counts, "crashEvents");
        long crashDevices = lget(counts, "crashDevices");
        long crashIssues = lget(counts, "crashIssues");
        long anrEvents = lget(counts, "anrEvents");
        long anrDevices = lget(counts, "anrDevices");
        long anrIssues = lget(counts, "anrIssues");

        Map<String, Object> error = errorCounts(appId, platform, appVersion, begin, end);

        Map<String, Object> cards = new LinkedHashMap<>();
        cards.put("onlineDevices", onlineDevices);
        cards.put("crash", rateCard(crashEvents, crashDevices, crashIssues, onlineDevices));
        cards.put("anr", rateCard(anrEvents, anrDevices, anrIssues, onlineDevices));
        cards.put("error", error);
        cards.put("httpFailed", lget(counts, "httpFailed"));
        cards.put("httpSlow", lget(counts, "httpSlow"));
        out.put("cards", cards);
        out.put("kinds", overviewMapper.kindsBreakdown(q));

        out.put("trend", buildTrend(appId, platform, appVersion, begin, end, gran, tzOffsetMs, onlineDevices));

        if (StringUtils.isNotBlank(compareVersion)) {
            long cBegin = begin, cEnd = end;
            Map<String, Object> cq = buildQuery(appId, platform, compareVersion.trim(), cBegin, cEnd, tzOffsetMs);
            Map<String, Object> cc = overviewMapper.summaryCounts(cq);
            long cOnline = nz(overviewMapper.countOnlineDevices(cq));
            Map<String, Object> cmp = new LinkedHashMap<>();
            cmp.put("appVersion", compareVersion.trim());
            cmp.put("onlineDevices", cOnline);
            cmp.put("crash", rateCard(lget(cc, "crashEvents"), lget(cc, "crashDevices"), lget(cc, "crashIssues"), cOnline));
            cmp.put("anr", rateCard(lget(cc, "anrEvents"), lget(cc, "anrDevices"), lget(cc, "anrIssues"), cOnline));
            cmp.put("trend", buildTrend(appId, platform, compareVersion.trim(), cBegin, cEnd, gran, tzOffsetMs, cOnline));
            out.put("compare", cmp);
        }

        out.put("topIssues", buildTopIssues(q, begin, end));
        out.put("ranking", buildRanking(q, onlineDevices));
        return out;
    }

    @Override
    public Map<String, Object> filters(String appId, Integer platform) {
        Map<String, Object> q = new LinkedHashMap<>();
        if (StringUtils.isNotBlank(appId)) {
            q.put("appId", appId.trim());
        }
        if (platform != null) {
            q.put("platform", platform);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            out.put("appVersions", overviewMapper.listVersions(q));
        } catch (Exception e) {
            out.put("appVersions", Collections.emptyList());
        }
        return out;
    }

    // ---------------------------------------------------------------- 内部

    private Map<String, Object> buildQuery(String appId, Integer platform, String appVersion,
                                           long begin, long end, long tzOffsetMs) {
        Map<String, Object> q = new LinkedHashMap<>();
        if (StringUtils.isNotBlank(appId)) {
            q.put("appId", appId.trim());
        }
        if (platform != null) {
            q.put("platform", platform);
        }
        if (StringUtils.isNotBlank(appVersion)) {
            q.put("appVersion", appVersion.trim());
        }
        q.put("beginTime", begin);
        q.put("endTime", end);
        q.put("tzOffsetMs", tzOffsetMs);
        return q;
    }

    /** 率卡片：分母为 0 → 率为 null（前端显示「-」），绝不假造成 0。 */
    private static Map<String, Object> rateCard(long events, long devices, long issues, long onlineDevices) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("events", events);
        m.put("affectedDevices", devices);
        m.put("issues", issues);
        m.put("onlineDevices", onlineDevices);
        m.put("deviceRate", percent(devices, onlineDevices));
        m.put("eventRate", percent(events, onlineDevices));
        return m;
    }

    private static Double percent(long numerator, long denominator) {
        if (denominator <= 0 || numerator < 0) {
            return null;
        }
        double v = numerator * 100.0d / denominator;
        if (v > 100d) {
            v = 100d;
        }
        return Math.round(v * 100d) / 100d;
    }

    /** 趋势：补零成连续桶，保证折线图时间轴诚实（没发生就是 0，而不是断点）。 */
    private Map<String, Object> buildTrend(String appId, Integer platform, String appVersion,
                                           long begin, long end, String gran, long tzOffsetMs, long onlineDevices) {
        Map<String, Object> q = buildQuery(appId, platform, appVersion, begin, end, tzOffsetMs);
        List<Map<String, Object>> rows;
        try {
            rows = overviewMapper.trendBuckets(q, gran);
        } catch (Exception e) {
            rows = Collections.emptyList();
        }
        long step = "hour".equals(gran) ? HOUR : DAY;
        long first = alignDown(begin + tzOffsetMs, step) - tzOffsetMs;
        Map<Long, Map<String, Object>> byBucket = new TreeMap<>();
        long maxEvents = 0;
        for (Map<String, Object> row : rows) {
            long bt = lget(row, "bucketTime");
            if (bt < first) {
                continue;
            }
            Map<String, Object> bucket = byBucket.get(bt);
            if (bucket == null) {
                bucket = new LinkedHashMap<>();
                bucket.put("bucketTime", bt);
                bucket.put("events", 0L);
                bucket.put("devices", 0L);
                bucket.put("kinds", new LinkedHashMap<String, Object>());
                byBucket.put(bt, bucket);
            }
            long ev = lget(row, "events");
            long dv = lget(row, "devices");
            bucket.put("events", ((Number) bucket.get("events")).longValue() + ev);
            bucket.put("devices", ((Number) bucket.get("devices")).longValue() + dv);
            if (ev > maxEvents) {
                maxEvents = ev;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> kinds = (Map<String, Object>) bucket.get("kinds");
            String kind = str(row.get("kind"));
            kinds.put(kind, ev);
        }
        // 连续补零（上限保护，跨度太大时丢弃最早的桶）
        long last = alignDown(end + tzOffsetMs, step) - tzOffsetMs;
        List<Map<String, Object>> buckets = new ArrayList<>();
        for (long bt = first; bt <= last; bt += step) {
            Map<String, Object> b = byBucket.get(bt);
            if (b == null) {
                b = new LinkedHashMap<>();
                b.put("bucketTime", bt);
                b.put("events", 0L);
                b.put("devices", 0L);
                b.put("kinds", new LinkedHashMap<String, Object>());
            }
            buckets.add(b);
        }
        if (buckets.size() > MAX_BUCKETS) {
            buckets = new ArrayList<>(buckets.subList(buckets.size() - MAX_BUCKETS, buckets.size()));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("granularity", gran);
        out.put("stepMs", step);
        out.put("buckets", buckets);
        out.put("maxEvents", maxEvents);
        out.put("totalEvents", sum(buckets, "events"));
        out.put("onlineDevices", onlineDevices);
        return out;
    }

    /** Top 问题：窗口内次数排序 + 与前一等长窗口对比算波动（Bugly 的「波动」列）。 */
    private List<Map<String, Object>> buildTopIssues(Map<String, Object> q, long begin, long end) {
        List<Map<String, Object>> top;
        try {
            top = overviewMapper.topIssues(q, TOP_LIMIT);
        } catch (Exception e) {
            return Collections.emptyList();
        }
        if (top.isEmpty()) {
            return top;
        }
        List<String> fps = new ArrayList<>();
        for (Map<String, Object> row : top) {
            String fp = str(row.get("fingerprint"));
            if (StringUtils.isNotBlank(fp)) {
                fps.add(fp);
            }
        }
        Map<String, long[]> prev = new LinkedHashMap<>();
        if (!fps.isEmpty()) {
            long span = Math.max(1, end - begin);
            try {
                for (Map<String, Object> r : overviewMapper.countsByFingerprints(q, begin - span, begin - 1, fps)) {
                    prev.put(str(r.get("fingerprint")), new long[]{lget(r, "events"), lget(r, "devices")});
                }
            } catch (Exception ignore) {
                // 对照窗口取不到就不给波动值，不编造
            }
        }
        for (Map<String, Object> row : top) {
            String fp = str(row.get("fingerprint"));
            long cur = lget(row, "windowEvents");
            long[] p = prev.get(fp);
            if (p == null) {
                row.put("prevEvents", 0L);
                row.put("isNew", lget(row, "firstCrashTime") >= begin);
                row.put("delta", null);
            } else {
                row.put("prevEvents", p[0]);
                row.put("isNew", false);
                row.put("delta", p[0] <= 0 ? null : percent(cur - p[0], p[0]));
            }
        }
        return top;
    }

    /** 三张 Top5 榜：系统版本 / 设备 / 应用版本，带影响设备数与占联网设备数比例。 */
    private Map<String, Object> buildRanking(Map<String, Object> q, long onlineDevices) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("os", safeRanking(q, "os", onlineDevices));
        out.put("device", safeRanking(q, "device", onlineDevices));
        out.put("version", safeRanking(q, "version", onlineDevices));
        return out;
    }

    private List<Map<String, Object>> safeRanking(Map<String, Object> q, String dim, long onlineDevices) {
        List<Map<String, Object>> rows;
        try {
            rows = overviewMapper.ranking(q, dim, RANK_LIMIT);
        } catch (Exception e) {
            return Collections.emptyList();
        }
        for (Map<String, Object> row : rows) {
            long devices = lget(row, "devices");
            row.put("devicesRatio", percent(devices, onlineDevices));
            row.put("onlineDevices", onlineDevices);
        }
        return rows;
    }

    /** 非致命错误（logan_error 表）；表不存在时 available=false，前端显示「待接入」而不是 0.00%。 */
    private Map<String, Object> errorCounts(String appId, Integer platform, String appVersion, long begin, long end) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("available", false);
        m.put("events", 0L);
        m.put("affectedDevices", 0L);
        m.put("issues", 0L);
        if (!errorTableAvailable()) {
            m.put("note", "非致命异常采集未接入（logan_error 表不存在）");
            return m;
        }
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) AS events, COUNT(DISTINCT device_id) AS devices, "
                + "COUNT(DISTINCT fingerprint) AS issues FROM logan_error WHERE occurred_at BETWEEN ? AND ?");
        List<Object> args = new ArrayList<>();
        args.add(begin);
        args.add(end);
        if (StringUtils.isNotBlank(appId)) {
            sql.append(" AND app_id=?");
            args.add(appId.trim());
        }
        if (platform != null) {
            sql.append(" AND platform=?");
            args.add(platform);
        }
        if (StringUtils.isNotBlank(appVersion)) {
            sql.append(" AND app_version=?");
            args.add(appVersion.trim());
        }
        try (java.sql.Connection conn = dataSource.getConnection();
             java.sql.PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < args.size(); i++) {
                ps.setObject(i + 1, args.get(i));
            }
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    m.put("available", true);
                    m.put("events", rs.getLong("events"));
                    m.put("affectedDevices", rs.getLong("devices"));
                    m.put("issues", rs.getLong("issues"));
                }
            }
        } catch (Exception e) {
            m.put("available", false);
            m.put("note", "错误统计暂不可用");
        }
        return m;
    }

    private boolean errorTableAvailable() {
        long now = System.currentTimeMillis();
        if (now - errorTableCheckedAt < 10 * 60_000L) {
            return errorTableExists;
        }
        errorTableCheckedAt = now;
        try (java.sql.Connection conn = dataSource.getConnection();
             java.sql.PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='logan_error'")) {
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                errorTableExists = rs.next() && rs.getLong(1) > 0;
            }
        } catch (Exception e) {
            errorTableExists = false;
        }
        return errorTableExists;
    }

    private static long alignDown(long v, long step) {
        long m = v % step;
        return m < 0 ? v + m + step : v - m;
    }

    private static long sum(List<Map<String, Object>> rows, String key) {
        long s = 0;
        for (Map<String, Object> r : rows) {
            s += lget(r, key);
        }
        return s;
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static long nz(Long v) {
        return v == null ? 0L : v;
    }

    private static long lget(Map<String, Object> m, String k) {
        if (m == null) {
            return 0L;
        }
        Object v = m.get(k);
        return v instanceof Number ? ((Number) v).longValue() : 0L;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
