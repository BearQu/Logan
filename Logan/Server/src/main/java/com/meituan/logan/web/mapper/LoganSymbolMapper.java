package com.meituan.logan.web.mapper;

import com.meituan.logan.web.dto.LoganSymbolDTO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 符号表归档（用户需求：崩溃解析应有上传符号表的位置）。 */
public interface LoganSymbolMapper {

    int insertIgnore(LoganSymbolDTO dto);

    LoganSymbolDTO selectById(@Param(
"id") long id);

    LoganSymbolDTO selectByHash(@Param(
"appId") String appId, @Param(
"fileHash") String fileHash);

    /** 按应用+版本+类别取最新一条（反混淆用）。 */
    LoganSymbolDTO selectLatest(@Param(
"appId") String appId, @Param(
"appVersion") String appVersion,
            @Param(
"kind") String kind);

    List<LoganSymbolDTO> list(@Param(
"appId") String appId, @Param(
"appVersion") String appVersion,
            @Param(
"kind") String kind, @Param(
"limit") int limit);

    int deleteById(@Param(
"id") long id);
}
