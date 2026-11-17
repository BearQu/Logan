package com.meituan.logan.web.controller;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.meituan.logan.web.dto.LoganCrashIssueDTO;
import com.meituan.logan.web.dto.LoganErrorIssueDTO;
import com.meituan.logan.web.dto.LoganHttpIssueDTO;
import com.meituan.logan.web.mapper.LoganCrashMapper;
import com.meituan.logan.web.mapper.LoganErrorMapper;
import com.meituan.logan.web.mapper.LoganHttpMapper;
import com.meituan.logan.web.mapper.LoganIssueOpMapper;
import com.meituan.logan.web.model.response.LoganResponse;
import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.Logger;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.io.BufferedReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

// 问题处置闭环（对标 Bugly 的处理人 / 处理状态 / 操作记录）。
//
// 设计原则：
//  - 操作人由调用方显式传入：平台没有账号体系，不猜是谁（宁缺毋假）；
//  - 每次状态变更、指派、备注都落 logan_issue_op 留痕，可审计；
//  - 崩溃问题与网络问题共用一个入口，targetType 区分，前端一套交互即可复用。
@CrossOrigin(origins = "*", maxAge = 3600)
@Controller
@RequestMapping("/logan/issue")
public class LoganIssueOpController {

    private static final Logger LOGGER = Logger.getLogger(LoganIssueOpController.class);
    private static final int MAX_BATCH = 200;
    private static final Set<String> TARGETS = new HashSet<>(Arrays.asList("crash_issue", "http_issue", "error_issue"));
    private static final Set<String> OPS = new HashSet<>(Arrays.asList("status", "assign", "note", "batch"));

    @Resource
    private LoganIssueOpMapper opMapper;
    @Resource
    private LoganCrashMapper crashMapper;
    @Resource
    private LoganHttpMapper httpMapper;
    @Resource
    private LoganErrorMapper errorMapper;

    // 单个问题处置。body 字段：targetType / targetId / appId / op / operator / status / assignee / note
    @RequestMapping(value = "/op.json", method = RequestMethod.POST)
    @ResponseBody
    public LoganResponse<Map<String, Object>> op(HttpServletRequest request) {
        JSONObject body = readJson(request);
        if (body == null) {
            return LoganResponse.badParam("invalid json body");
        }
        String targetType = trim(body.getString("targetType"));
        Long targetId = body.getLong("targetId");
        String op = trim(body.getString("op"));
        String operator = trim(body.getString("operator"));
        String appId = trim(body.getString("appId"));
        if (targetType == null || !TARGETS.contains(targetType)) {
            return LoganResponse.badParam("targetType must be crash_issue or http_issue");
        }
        if (targetId == null || targetId <= 0) {
            return LoganResponse.badParam("targetId required");
        }
        if (op == null || !OPS.contains(op)) {
            return LoganResponse.badParam("op must be status, assign or note");
        }
        if (StringUtils.isBlank(operator)) {
            return LoganResponse.badParam("operator required");
        }
        Integer status = body.getInteger("status");
        String assignee = trim(body.getString("assignee"));
        String note = trim(body.getString("note"));
        try {
            Integer before = currentStatus(targetType, targetId.longValue());
            if (before == null) {
                return LoganResponse.badParam("issue not found");
            }
            long now = System.currentTimeMillis();
            if ("status".equals(op)) {
                if (status == null || status < 0 || status > 3) {
                    return LoganResponse.badParam("status 0-3 required");
                }
                applyStatus(targetType, targetId.longValue(), status.intValue(), note, now);
            } else if ("assign".equals(op)) {
                if (StringUtils.isBlank(assignee)) {
                    return LoganResponse.badParam("assignee required");
                }
                applyAssign(targetType, targetId.longValue(), assignee, now);
            } else if ("note".equals(op)) {
                if (StringUtils.isBlank(note)) {
                    return LoganResponse.badParam("note required");
                }
                applyNote(targetType, targetId.longValue(), note);
            } else {
                return LoganResponse.badParam("use batch.json for batch operations");
            }
            Integer after = "status".equals(op) ? Integer.valueOf(status.intValue()) : before;
            insertOp(appId, targetType, targetId.longValue(), op, operator, before, after, opNote(op, assignee, note));
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("targetType", targetType);
            data.put("targetId", targetId);
            data.put("beforeStatus", before);
            data.put("afterStatus", after);
            return LoganResponse.success(data);
        } catch (Exception e) {
            LOGGER.warn("issue op failed " + e.getMessage());
            return LoganResponse.exception(e.getMessage());
        }
    }

    // 批量处置。body 字段：targetType / ids / status / operator / appId
    @RequestMapping(value = "/batch.json", method = RequestMethod.POST)
    @ResponseBody
    public LoganResponse<Map<String, Object>> batch(HttpServletRequest request) {
        JSONObject body = readJson(request);
        if (body == null) {
            return LoganResponse.badParam("invalid json body");
        }
        String targetType = trim(body.getString("targetType"));
        String operator = trim(body.getString("operator"));
        String appId = trim(body.getString("appId"));
        Integer status = body.getInteger("status");
        JSONArray ids = body.getJSONArray("ids");
        if (targetType == null || !TARGETS.contains(targetType)) {
            return LoganResponse.badParam("targetType must be crash_issue or http_issue");
        }
        if (status == null || status < 0 || status > 3) {
            return LoganResponse.badParam("status 0-3 required");
        }
        if (StringUtils.isBlank(operator)) {
            return LoganResponse.badParam("operator required");
        }
        if (ids == null || ids.isEmpty()) {
            return LoganResponse.badParam("ids required");
        }
        long now = System.currentTimeMillis();
        int done = 0;
        List<Long> skipped = new ArrayList<>();
        for (int i = 0; i < ids.size() && done < MAX_BATCH; i++) {
            Long id = ids.getLong(i);
            if (id == null || id <= 0) {
                continue;
            }
            try {
                Integer before = currentStatus(targetType, id.longValue());
                if (before == null) {
                    skipped.add(id);
                    continue;
                }
                applyStatus(targetType, id.longValue(), status.intValue(), null, now);
                insertOp(appId, targetType, id.longValue(), "batch", operator, before, status, "batch status change");
                done++;
            } catch (Exception e) {
                skipped.add(id);
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("done", done);
        data.put("skipped", skipped);
        data.put("operator", operator);
        data.put("at", now);
        return LoganResponse.success(data);
    }

    // 处置历史（留痕），供前端操作记录时间线使用。
    @RequestMapping(value = "/ops.json", method = {RequestMethod.GET, RequestMethod.POST})
    @ResponseBody
    public LoganResponse<List<Map<String, Object>>> ops(String targetType, Long targetId, Integer limit) {
        if (targetType == null || !TARGETS.contains(targetType) || targetId == null || targetId <= 0) {
            return LoganResponse.badParam("targetType and targetId required");
        }
        int n = limit == null || limit <= 0 ? 50 : Math.min(200, limit);
        try {
            return LoganResponse.success(opMapper.listByTarget(targetType, targetId.longValue(), n));
        } catch (Exception e) {
            return LoganResponse.exception(e.getMessage());
        }
    }

    private Integer currentStatus(String targetType, long targetId) {
        if ("crash_issue".equals(targetType)) {
            LoganCrashIssueDTO d = crashMapper.selectIssueById(targetId);
            return d == null ? null : Integer.valueOf(d.getStatus());
        }
        if ("http_issue".equals(targetType)) {
            LoganHttpIssueDTO d = httpMapper.selectIssueById(targetId);
            return d == null ? null : Integer.valueOf(d.getStatusFlag());
        }
        LoganErrorIssueDTO e = errorMapper.selectIssueById(targetId);
        return e == null ? null : Integer.valueOf(e.getStatusFlag());
    }

    private void applyStatus(String targetType, long targetId, int status, String note, long now) {
        if ("crash_issue".equals(targetType)) {
            crashMapper.updateIssueStatus(targetId, status);
            // 已修复 / 已忽略记为处置完结（写结论与完结时间）；退回未处理 / 处理中则清掉完结时间
            Long closeTime = (status == 2 || status == 3) ? Long.valueOf(now) : null;
            crashMapper.updateIssueClose(targetId, note, closeTime);
        } else if ("http_issue".equals(targetType)) {
            httpMapper.updateIssueStatus(targetId, status, note, null);
        } else {
            errorMapper.updateIssueStatus(targetId, status, note, null);
        }
    }

    private void applyAssign(String targetType, long targetId, String assignee, long now) {
        if ("crash_issue".equals(targetType)) {
            crashMapper.updateIssueAssign(targetId, assignee, now);
        } else if ("http_issue".equals(targetType)) {
            httpMapper.updateIssueAssign(targetId, assignee);
        } else {
            errorMapper.updateIssueAssign(targetId, assignee);
        }
    }

    private void applyNote(String targetType, long targetId, String note) {
        if ("crash_issue".equals(targetType)) {
            crashMapper.updateIssueClose(targetId, note, null);
        } else if ("http_issue".equals(targetType)) {
            httpMapper.updateIssueCloseNote(targetId, note);
        } else {
            errorMapper.updateIssueCloseNote(targetId, note);
        }
    }

    private static String opNote(String op, String assignee, String note) {
        if ("assign".equals(op)) {
            return "assigned to " + assignee;
        }
        return note;
    }

    private void insertOp(String appId, String targetType, long targetId, String op, String operator,
                          Integer before, Integer after, String note) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("appId", appId == null ? "" : appId);
        row.put("targetType", targetType);
        row.put("targetId", targetId);
        row.put("op", op);
        row.put("operator", operator);
        row.put("beforeStatus", before);
        row.put("afterStatus", after);
        row.put("note", note);
        row.put("addTime", System.currentTimeMillis());
        opMapper.insert(row);
    }

    private static String trim(String v) {
        if (v == null) {
            return null;
        }
        String t = v.trim();
        return t.isEmpty() ? null : t;
    }

    private static JSONObject readJson(HttpServletRequest request) {
        try {
            BufferedReader r = request.getReader();
            StringBuilder sb = new StringBuilder();
            String line;
            int total = 0;
            while ((line = r.readLine()) != null && total < 200000) {
                sb.append(line);
                total += line.length();
            }
            String txt = sb.toString().trim();
            if (txt.isEmpty()) {
                return null;
            }
            return JSON.parseObject(txt);
        } catch (Exception e) {
            LOGGER.warn("read body failed: " + e.getMessage());
            return null;
        }
    }
}
