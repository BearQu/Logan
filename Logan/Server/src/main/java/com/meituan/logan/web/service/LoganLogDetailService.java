package com.meituan.logan.web.service;

import com.meituan.logan.web.model.LoganLogDetailModel;
import com.meituan.logan.web.model.LoganLogSimpleModel;

import java.util.List;

public interface LoganLogDetailService {

    List<List<LoganLogSimpleModel>> listByTaskIdTypeKeyword(long taskId, List<Integer> type, String keyword);

    /**
     * 上传入库后异步解析日志包（官方为懒解析：等有人打开任务页才解密入库）。
     * 崩溃↔日志互证、AI 摘要注入业务日志、证据包导出都依赖 logan_log_detail 已入库，
     * 因此上传成功即调度解析；解析幂等（按任务状态 + 文件名锁去重）。
     */
    void analyzeTaskAsync(long taskId);

    List<LoganLogDetailModel> listByDetailIds(List<Long> detailIds);

    LoganLogDetailModel getByDetailId(long detailId);
}
