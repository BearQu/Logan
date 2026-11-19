package com.meituan.logan.web.service.impl;

import com.meituan.logan.web.dto.LoganConfigDTO;
import com.meituan.logan.web.mapper.LoganConfigMapper;
import com.meituan.logan.web.service.LoganConfigService;
import org.apache.log4j.Logger;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 远程配置实现：取号用 logan_config_seq 行锁（UPDATE seq=seq+1 后再 SELECT，事务内串行安全）。 */
@Service
public class LoganConfigServiceImpl implements LoganConfigService {
    private static final Logger LOGGER = Logger.getLogger(LoganConfigServiceImpl.class);
    private static final int VALUE_MAX = 2048;

    @Resource
    private LoganConfigMapper configMapper;

    @Override
    public List<LoganConfigDTO> list(String appId) {
        return configMapper.listByApp(appId);
    }

    @Override
    @Transactional
    public LoganConfigDTO save(String appId, String configKey, String configValue, String remark) {
        long version = nextVersion(appId);
        LoganConfigDTO dto = new LoganConfigDTO();
        dto.setAppId(appId);
        dto.setConfigKey(configKey);
        dto.setConfigValue(configValue == null ? "" : (configValue.length() > VALUE_MAX ? configValue.substring(0, VALUE_MAX) : configValue));
        dto.setEnabled(1);
        dto.setVersion(version);
        dto.setRemark(remark == null ? "" : remark);
        dto.setAddTime(System.currentTimeMillis());
        configMapper.upsert(dto);
        LOGGER.info("config saved app=" + appId + " key=" + configKey + " version=" + version);
        return dto;
    }

    @Override
    @Transactional
    public boolean remove(String appId, String configKey) {
        // 墓碑：不存在也写一行（幂等），删除语义靠 enabled=0 + 新 version 传达给端上
        long version = nextVersion(appId);
        LoganConfigDTO dto = new LoganConfigDTO();
        dto.setAppId(appId);
        dto.setConfigKey(configKey);
        dto.setConfigValue("");
        dto.setEnabled(0);
        dto.setVersion(version);
        dto.setRemark("removed");
        dto.setAddTime(System.currentTimeMillis());
        configMapper.upsert(dto);
        LOGGER.info("config removed app=" + appId + " key=" + configKey + " version=" + version);
        return true;
    }

    @Override
    public Map<String, Object> fetch(String appId, long sinceVersion) {
        Long seq = configMapper.selectSeq(appId);
        long current = seq == null ? 0L : seq;
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("version", current);
        Map<String, String> configs = new LinkedHashMap<String, String>();
        List<String> removed = new ArrayList<String>();
        if (current > sinceVersion) {
            List<LoganConfigDTO> rows = configMapper.listSince(appId, sinceVersion);
            for (LoganConfigDTO row : rows) {
                if (row.getEnabled() != null && row.getEnabled() == 1) {
                    configs.put(row.getConfigKey(), row.getConfigValue());
                } else {
                    removed.add(row.getConfigKey());
                }
            }
        }
        result.put("configs", configs);
        result.put("removed", removed);
        return result;
    }

    /** 取号：保证 seq 行存在后 UPDATE +1，同事务内 SELECT 回读——行锁串行化，无跳号竞态。 */
    private long nextVersion(String appId) {
        configMapper.insertSeq(appId);
        configMapper.bumpSeq(appId);
        Long v = configMapper.selectSeq(appId);
        return v == null ? 1L : v;
    }
}
