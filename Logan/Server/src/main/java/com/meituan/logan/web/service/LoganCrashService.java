package com.meituan.logan.web.service;

import com.meituan.logan.web.dto.LoganCrashDTO;

import java.util.List;

public interface LoganCrashService {

    /**
     * 入库一条崩溃封包（SDK CrashEnvelope JSON）。content_hash 唯一键去重，
     * 新行同步做确定性聚类聚合（Bugly 同款「栈顶业务帧指纹」思路，指纹由 SDK 端生成）。
     *
     * @return true=新入库，false=重复（双通道 A/B 撞上）
     */
    boolean ingest(String envelopeJson);

    LoganCrashDTO getById(long id);

    List<LoganCrashDTO> latestByDevice(String deviceId, int limit);

    List<LoganCrashDTO> latest(String appId, int limit);
}
