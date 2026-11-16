SET NAMES utf8mb4;

-- 符号表归档（用户要求：崩溃解析应有上传符号表的位置）。文件落盘 -Dlogan.symbol.dir，库内只存元数据。
CREATE TABLE IF NOT EXISTS logan_symbol (
  id BIGINT NOT NULL AUTO_INCREMENT,
  app_id VARCHAR(128) NOT NULL COMMENT '应用标识（端粒度）',
  platform TINYINT NOT NULL DEFAULT 0 COMMENT '1 android 2 ios 3 harmony，0=未知',
  app_version VARCHAR(64) DEFAULT NULL COMMENT '对应版本，反混淆按版本取最新',
  build_version VARCHAR(64) DEFAULT NULL,
  kind VARCHAR(16) NOT NULL COMMENT 'mapping/dsym/so/sourcemap',
  file_name VARCHAR(255) NOT NULL COMMENT '上传时的原始文件名（展示用）',
  file_path VARCHAR(255) NOT NULL COMMENT '落盘相对文件名',
  file_hash CHAR(40) NOT NULL COMMENT 'SHA-1，去重与校验',
  file_size BIGINT NOT NULL DEFAULT 0,
  note VARCHAR(512) DEFAULT NULL,
  uploader VARCHAR(64) DEFAULT NULL COMMENT '上传人',
  source VARCHAR(32) NOT NULL DEFAULT 'console' COMMENT 'console/dsh-skill/gradle',
  add_time BIGINT NOT NULL,
  update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_app_hash (app_id, file_hash),
  KEY idx_app_ver_kind (app_id, app_version, kind),
  KEY idx_add_time (add_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='符号表归档（mapping/dSYM/so/sourcemap）';

-- 设备基本信息快照（用户要求：拉日志时顺带回传内存/磁盘/CPU/GPU 等设备状态）。
CREATE TABLE IF NOT EXISTS logan_device_info (
  id BIGINT NOT NULL AUTO_INCREMENT,
  app_id VARCHAR(128) NOT NULL,
  device_id VARCHAR(128) NOT NULL,
  platform TINYINT NOT NULL DEFAULT 0,
  info MEDIUMTEXT COMMENT '设备信息 JSON：内存/磁盘/CPU/GPU/电量/网络等，取不到的字段端侧省略，平台不伪造',
  report_time BIGINT NOT NULL,
  update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_app_device (app_id, device_id),
  KEY idx_report_time (report_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='设备基本信息最新快照（一设备一行）';
