package com.meituan.logan.web.service;

import java.util.Map;

/** 报警：指标取值与规则判定（纯平台侧，通知走通用 webhook）。 */
public interface LoganAlertService {

    /** 按规则取窗口内实测值；返回 null 表示该指标暂不可用（如非致命错误未接入）。 */
    Double metricValue(Map<String, Object> rule, long beginTime, long endTime);

    /** 判定：值与阈值+比较符是否成立。 */
    boolean hit(Double value, String opType, double threshold);

    /** 扫描全部启用规则并触发（worker 调用）。 */
    int scanOnce();

    /**
     * 清理过期的报警记录（worker 定时调用）：早于 retentionMs 的删除，同时保留最近 maxRows 条兜底，
     * 避免测试期把冷却调成 0 之类情况把表灌爆。返回删除行数。
     */
    int purgeRecords(long retentionMs, int maxRows);
}
