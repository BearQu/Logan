package com.meituan.logan.web.controller;

import com.meituan.logan.web.enums.ResultEnum;
import com.meituan.logan.web.model.LoganTaskModel;
import com.meituan.logan.web.model.response.LoganResponse;
import com.meituan.logan.web.parser.RequestContextParser;
import com.meituan.logan.web.util.HeaderUtil;
import com.meituan.logan.web.service.LoganLogFileService;
import com.meituan.logan.web.service.LoganTaskService;
import com.meituan.logan.web.util.FileUtil;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.io.IOException;

/**
 * 客户端日志上报接口
 *
 * @since 2019-10-09 17:56
 * @since logan-web 1.0
 */
@CrossOrigin(origins = "*", maxAge = 3600)
@Controller
@RequestMapping("/logan")
public class LoganUploadController {

    @Resource
    private LoganTaskService taskService;
    @Resource
    private LoganLogFileService fileService;
    @Resource
    private com.meituan.logan.web.service.LoganAppService appService;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.meituan.logan.web.service.LoganLogDetailService detailService;

    /**
     * 一次上报一个任务一天日志
     */
    @PostMapping("/upload.json")
    @ResponseBody
    public LoganResponse<String> upload(HttpServletRequest request) throws IOException {
        LoganTaskModel model = RequestContextParser.parse(request);
        // 治理规则（用户拍板）：应用必须先在平台「应用清单」创建，未登记的 appId 上报直接拒收，
        // 平台不再自动登记，避免脏应用混进统计。
        if (!com.meituan.logan.web.util.AppGuard.registered(appService, model.getAppId())) {
            return LoganResponse.badParam(com.meituan.logan.web.util.AppGuard.MSG);
        }
                // 已登记应用：照常刷新最新版本/构建号/设备/上报时间（register 已退化为仅 touch）
        appService.register(model.getAppId(), HeaderUtil.utf8(request.getHeader("appName")), model.getPlatform(),
                model.getAppVersion(), model.getBuildVersion(), model.getDeviceId(), "auto_client");
        ResultEnum result = fileService.write(request.getInputStream(), model.getLogFileName());
        if (ResultEnum.SUCCESS != result) {
            return LoganResponse.exception(result.name());
        }
        long taskId = taskService.insertTask(model);
        if (taskId <= 0) {
            return LoganResponse.exception(ResultEnum.ERROR_DATABASE.name());
        }
        // 入库即异步解析：崩溃↔日志互证与 AI 摘要依赖明细已入库，不等人工打开任务页
        if (detailService != null) {
            detailService.analyzeTaskAsync(taskId);
        }
        return LoganResponse.success(FileUtil.getDownloadUrl(request, model.getLogFileName()));

    }
}