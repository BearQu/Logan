package com.meituan.logan.web.service;

import com.meituan.logan.web.dto.LoganConfigDTO;

import java.util.List;
import java.util.Map;

/**
 * 远程配置（对标 Bugly 收费版，PoC 净新增）。
 * 版本号语义：app 维度单调递增（logan_config_seq 取号，UPSERT 与墓碑共用一个号段），
 * 客户端持本地 version 拉增量，服务端只回更大的行；删除=写 enabled=0 墓碑，端上据此移除本地键。
 */
public interface LoganConfigService {

    /** 控制台列表（含墓碑）。 */
    List<LoganConfigDTO> list(String appId);

    /** 保存键值（新建或覆盖）；返回落库后的行。value 上限 2048，key 校验 [A-Za-z0-9_.\\-]{1,128}。 */
    LoganConfigDTO save(String appId, String configKey, String configValue, String remark);

    /** 删除键（墓碑 enabled=0，携带新 version，保证增量语义）。 */
    boolean remove(String appId, String configKey);

    /**
     * 客户端增量拉取。返回 {version, configs:{k:v…（仅 enabled=1）}, removed:[k…]}；
     * sinceVersion 缺省 0 = 全量。configs 已剔除墓碑。
     */
    Map<String, Object> fetch(String appId, long sinceVersion);
}
