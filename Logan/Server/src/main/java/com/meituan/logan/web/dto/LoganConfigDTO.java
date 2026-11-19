package com.meituan.logan.web.dto;

/**
 * 远程配置项（对标 Bugly 收费版「远程配置」，PoC 净新增）。
 * 表 logan_config 行映射；version 为 app 维度的单调递增版本号（logan_config_seq 派生），
 * 客户端带 sinceVersion 拉增量：服务端只回 version &gt; sinceVersion 的行；删键也产生一行
 * enabled=0 的墓碑，保证端上删除语义与版本号一致。
 */
public class LoganConfigDTO {
    private Long id;
    private String appId;
    private String configKey;
    private String configValue;
    private Integer enabled;
    private Long version;
    private String remark;
    private Long addTime;
    private java.util.Date updateTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getAppId() { return appId; }
    public void setAppId(String appId) { this.appId = appId; }
    public String getConfigKey() { return configKey; }
    public void setConfigKey(String configKey) { this.configKey = configKey; }
    public String getConfigValue() { return configValue; }
    public void setConfigValue(String configValue) { this.configValue = configValue; }
    public Integer getEnabled() { return enabled; }
    public void setEnabled(Integer enabled) { this.enabled = enabled; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public Long getAddTime() { return addTime; }
    public void setAddTime(Long addTime) { this.addTime = addTime; }
    public java.util.Date getUpdateTime() { return updateTime; }
    public void setUpdateTime(java.util.Date updateTime) { this.updateTime = updateTime; }
}
