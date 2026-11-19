package com.meituan.logan.web.mapper;

import com.meituan.logan.web.dto.LoganBehaviorDTO;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

// 崩溃前行为轨迹：明细幂等入库 + 时间窗取数（互证用）+ 多维筛选。
// 轨迹是时间流不是问题聚类，因此只有明细表，不做指纹聚合。
public interface LoganBehaviorMapper {

    // dedup_hash 唯一键冲突即忽略，客户端重试/断网重传天然幂等。
    int insertIgnore(LoganBehaviorDTO dto);

    LoganBehaviorDTO selectById(@Param("id") long id);

    List<LoganBehaviorDTO> list(@Param("q") Map<String, Object> q);

    long countList(@Param("q") Map<String, Object> q);

    // 崩溃前后时间窗内同设备的行为轨迹（证据包与时间轴互证用）。
    List<LoganBehaviorDTO> listAround(@Param("appId") String appId, @Param("deviceId") String deviceId,
            @Param("beginTime") long beginTime, @Param("endTime") long endTime, @Param("limit") int limit);

    // 概览/看板：轨迹条数与涉及设备数。
    Map<String, Object> summary(@Param("appId") String appId, @Param("beginTime") Long beginTime,
            @Param("endTime") Long endTime, @Param("platform") Integer platform,
            @Param("appVersion") String appVersion);

    // 轨迹里的高频页面/控件榜。
    List<Map<String, Object>> topNames(@Param("appId") String appId, @Param("beginTime") Long beginTime,
            @Param("endTime") Long endTime, @Param("kind") String kind,
            @Param("platform") Integer platform, @Param("limit") int limit);
}
