package com.meituan.logan.web.mapper;

import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/** 问题处置留痕（对标 Bugly 处理人/操作记录）。 */
public interface LoganIssueOpMapper {

    int insert(Map<String, Object> row);

    List<Map<String, Object>> listByTarget(@Param("targetType") String targetType,
                                           @Param("targetId") long targetId,
                                           @Param("limit") int limit);
}
