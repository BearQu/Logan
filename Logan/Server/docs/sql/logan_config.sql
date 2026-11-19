-- 远程配置（对标 Bugly 收费版「远程配置」）建表 DDL
-- 服务端：logan-web 控制台「远程配置」页维护；SDK 端 POST /logan/config/get.json 增量拉取。
-- 版本语义：每次 save/delete 都在事务内从 logan_config_seq 取新全局版本号写入 version；
--          delete = 写 enabled=0 墓碑（保留行以便 removed 数组下发），SDK 端据此本地删键。
-- 历史：PoC 首次建表于 2026-11-19 在 NAS MySQL(logan 库) 直接执行，本文件为留档，与线上表一致。

CREATE TABLE IF NOT EXISTS logan_config (
  id           BIGINT       NOT NULL AUTO_INCREMENT,
  app_id       VARCHAR(128) NOT NULL COMMENT '应用包名，如 com.ccic.baodai.android',
  config_key   VARCHAR(128) NOT NULL COMMENT '键，[A-Za-z0-9_.-]{1,128}',
  config_value VARCHAR(2048) DEFAULT NULL COMMENT '值，字符串语义（SDK 端按字符串存，bool/number 由业务侧解析）',
  enabled      TINYINT      NOT NULL DEFAULT 1 COMMENT '1=生效 0=墓碑（删除保留以便下发 removed）',
  version      BIGINT       NOT NULL DEFAULT 0 COMMENT '全局版本号，由 logan_config_seq 事务内取号，SDK 增量游标',
  remark       VARCHAR(256) DEFAULT NULL COMMENT '备注（控制台展示）',
  add_time     DATETIME     DEFAULT NULL,
  update_time  DATETIME     DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_app_key (app_id, config_key),
  KEY idx_app_version (app_id, version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='远程配置键值（含墓碑行）';

CREATE TABLE IF NOT EXISTS logan_config_seq (
  app_id VARCHAR(128) NOT NULL COMMENT '应用包名',
  seq    BIGINT       NOT NULL DEFAULT 0 COMMENT '最近一次发放的全局版本号（从 1 起）',
  PRIMARY KEY (app_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='远程配置全局发号器';
