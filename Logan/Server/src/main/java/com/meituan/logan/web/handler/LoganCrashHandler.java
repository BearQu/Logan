package com.meituan.logan.web.handler;

import com.alibaba.fastjson.JSONObject;
import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.Logger;
import org.springframework.stereotype.Component;

/**
 * 崩溃日志（type=99）展示格式化。content 是 SDK CrashEnvelope 封的 JSON 行。
 */
@Component
public class LoganCrashHandler implements ContentHandler {
    private static final Logger LOGGER = Logger.getLogger(LoganCrashHandler.class);

    @Override
    public String getSimpleContent(String content) {
        try {
            JSONObject o = JSONObject.parseObject(content);
            if (o == null) {
                return content;
            }
            StringBuilder sb = new StringBuilder();
            sb.append("[").append(o.getString("kind")).append("]");
            if (StringUtils.isNotBlank(o.getString("signal"))) {
                sb.append(" ").append(o.getString("signal"));
            }
            if (StringUtils.isNotBlank(o.getString("deviceId"))) {
                sb.append(" device=").append(o.getString("deviceId"));
            }
            if (StringUtils.isNotBlank(o.getString("model"))) {
                sb.append(" ").append(o.getString("brand")).append("/").append(o.getString("model"));
            }
            if (StringUtils.isNotBlank(o.getString("appVersion"))) {
                sb.append(" v").append(o.getString("appVersion")).append("(").append(o.getString("buildVersion")).append(")");
            }
            if (Boolean.TRUE.equals(o.getBoolean("deferred"))) {
                sb.append(" [补传]");
            }
            return sb.toString();
        } catch (Exception ex) {
            LOGGER.warn("crash envelope parse fail: " + ex.getMessage());
            return content;
        }
    }

    @Override
    public String getFormatContent(String content) {
        try {
            JSONObject o = JSONObject.parseObject(content);
            if (o == null || StringUtils.isBlank(o.getString("content"))) {
                return content;
            }
            String head = "kind=" + o.getString("kind")
                    + "  fingerprint=" + o.getString("fingerprint")
                    + "  crashTime=" + o.getLong("crashTime") + "\n\n";
            return head + o.getString("content");
        } catch (Exception ex) {
            return content;
        }
    }
}
