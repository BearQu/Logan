package com.meituan.logan.web.mapper;

import com.meituan.logan.web.dto.LoganConfigDTO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 远程配置（对标 Bugly 收费版，PoC 净新增）：控制台 CRUD + 客户端增量拉取。 */
public interface LoganConfigMapper {

    /** 键存在则整行覆盖（含墓碑复活），不存在则插入；version 由 service 层从 seq 取号。 */
    int upsert(LoganConfigDTO dto);

    /** 全量列表（控制台管理页，含墓碑 enabled=0）。 */
    List<LoganConfigDTO> listByApp(@Param("appId") String appId);

    /** 客户端增量：只回 version &gt; sinceVersion 的行（含墓碑）。 */
    List<LoganConfigDTO> listSince(@Param("appId") String appId, @Param("sinceVersion") long sinceVersion);

    /** 当前 app 的 seq（无行返回 null，视为 0）。 */
    Long selectSeq(@Param("appId") String appId);

    /** 取号：seq+1 并返回新值（service 事务内先 UPDATE 再 SELECT，见 LoganConfigServiceImpl）。 */
    int bumpSeq(@Param("appId") String appId);

    int insertSeq(@Param("appId") String appId);
}
