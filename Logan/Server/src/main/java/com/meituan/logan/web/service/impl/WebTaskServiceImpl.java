package com.meituan.logan.web.service.impl;

import com.meituan.logan.web.dto.WebLogTaskDTO;
import com.meituan.logan.web.enums.TaskStatusEnum;
import com.meituan.logan.web.mapper.WebLogTaskMapper;
import com.meituan.logan.web.model.WebLogTaskModel;
import com.meituan.logan.web.service.WebTaskService;
import org.apache.commons.collections.CollectionUtils;
import org.apache.log4j.Logger;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;

/**
 * 功能描述:  <p>web上报处理服务</p>
 *
 * @version 1.0 2019-10-31
 * @since logan-admin-server 1.0
 */
@Service
public class WebTaskServiceImpl implements WebTaskService {
    private static final Logger LOGGER = Logger.getLogger(WebTaskServiceImpl.class);
    @Resource
    private WebLogTaskMapper webLogTaskMapper;

    @Override
    public boolean saveTask(WebLogTaskModel taskModel) {
        try {
            WebLogTaskDTO taskDTO = taskModel.transformToDto();
            WebLogTaskDTO exist = webLogTaskMapper.exist(taskDTO.getLogDate(), taskDTO.getDeviceId(),
                    taskDTO.getPageNum());
            if (exist != null) {
                webLogTaskMapper.updateContent(exist.getTaskId(), taskDTO.getContent());
            } else {
                webLogTaskMapper.insert(taskDTO);
            }
            return true;
        } catch (Exception e) {
            LOGGER.error(e);
        }
        return false;
    }

    @Override
    public void deleteByAddTime(long addTime) {
        try {
            webLogTaskMapper.deleteByAddTime(addTime);
        } catch (Exception e) {
            LOGGER.error(e);
        }
    }

    @Override
    public List<WebLogTaskDTO> search(long beginTime, long endTime, String deviceId, String appId) {
        try {
            return webLogTaskMapper.query(beginTime, endTime, deviceId, appId);
        } catch (Exception e) {
            LOGGER.error(e);
        }
        return Collections.emptyList();
    }

    @Override
    public List<WebLogTaskDTO> queryByTaskIds(List<Long> taskIds) {
        try {
            if (CollectionUtils.isEmpty(taskIds)) {
                return Collections.emptyList();
            }
            return webLogTaskMapper.queryByIds(taskIds);
        } catch (Exception e) {
            LOGGER.error(e);
        }
        return Collections.emptyList();
    }

    @Override
    public void updateStatus(long taskId, TaskStatusEnum status) {
        try {
            webLogTaskMapper.updateStatus(taskId, status.getStatus());
        } catch (Exception e) {
            LOGGER.error(e);
        }
    }

    @Override
    public List<WebLogTaskDTO> latest(int count, String appId) {
        try {
            long maxId = webLogTaskMapper.maxId();
            long minId = Math.max(0, maxId - count * 20L);
            List<WebLogTaskDTO> all = webLogTaskMapper.queryByRange(minId, maxId);
            if (org.apache.commons.lang3.StringUtils.isNotBlank(appId)) {
                List<WebLogTaskDTO> filtered = new java.util.ArrayList<>();
                for (WebLogTaskDTO dto : all) {
                    if (appId.equals(dto.getAppId())) {
                        filtered.add(dto);
                    }
                }
                return filtered;
            }
            return all;
        } catch (Exception e) {
            LOGGER.error(e);
            return Collections.emptyList();
        }
    }
}
