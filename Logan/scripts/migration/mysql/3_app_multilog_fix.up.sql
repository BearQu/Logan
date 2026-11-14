SET NAMES utf8mb4;

-- 1) 应用自动登记（客户端上报包名/应用名/图标，平台零手工录入）
CREATE TABLE IF NOT EXISTS logan_app (
  id BIGINT NOT NULL AUTO_INCREMENT,
  app_id VARCHAR(128) NOT NULL COMMENT '包名/应用标识，客户端上报',
  app_name VARCHAR(128) DEFAULT NULL COMMENT '应用名称，客户端上报',
  icon_path VARCHAR(256) DEFAULT NULL COMMENT '图标落盘相对路径',
  icon_hash CHAR(40) DEFAULT NULL COMMENT '图标内容 sha1，变化才重写',
  platform TINYINT NOT NULL DEFAULT 1 COMMENT '1 android 2 ios 3 harmony',
  latest_app_version VARCHAR(64) DEFAULT NULL,
  latest_build_version VARCHAR(64) DEFAULT NULL,
  last_device_id VARCHAR(128) DEFAULT NULL,
  source VARCHAR(32) DEFAULT NULL COMMENT 'auto_client / manual',
  first_seen BIGINT DEFAULT NULL,
  last_report_time BIGINT DEFAULT NULL,
  add_time BIGINT NOT NULL,
  update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_app_id (app_id),
  KEY idx_last_report (last_report_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='应用登记表（自动）';

-- 2) H5 日志按应用隔离
ALTER TABLE web_task ADD COLUMN app_id VARCHAR(64) NOT NULL DEFAULT '' COMMENT '应用标识';
ALTER TABLE web_task ADD INDEX idx_app_device (app_id, device_id);

-- 3) native 日志按应用+设备查询加速
ALTER TABLE logan_task ADD INDEX idx_app_device (app_id, device_id);

-- 4) 崩溃修复报告（本地 DSH 分析修改后回传，平台不接触源码）
CREATE TABLE IF NOT EXISTS logan_crash_fix (
  id BIGINT NOT NULL AUTO_INCREMENT,
  app_id VARCHAR(64) NOT NULL,
  issue_id BIGINT DEFAULT NULL,
  fingerprint VARCHAR(64) NOT NULL,
  kind VARCHAR(16) DEFAULT NULL,
  action VARCHAR(16) NOT NULL DEFAULT 'fix' COMMENT 'fix / note / reopen',
  status VARCHAR(24) NOT NULL DEFAULT 'fixed' COMMENT 'fixed / verified / partial / wontfix / reopen',
  fixer VARCHAR(64) NOT NULL COMMENT '修改人',
  root_cause VARCHAR(512) DEFAULT NULL COMMENT '根因一句话',
  detail MEDIUMTEXT COMMENT '修改细节（改了哪些文件/为什么/怎么验证）',
  files TEXT COMMENT '受影响文件 JSON 数组',
  commit_url VARCHAR(512) DEFAULT NULL,
  pr_url VARCHAR(512) DEFAULT NULL,
  source VARCHAR(32) DEFAULT NULL COMMENT 'dsh / web / agent',
  client VARCHAR(64) DEFAULT NULL COMMENT '上报客户端标识与版本',
  evidence_hash CHAR(40) DEFAULT NULL COMMENT '对应证据包 hash，证明分析基于哪份证据',
  report_hash CHAR(40) DEFAULT NULL COMMENT '幂等键，重复回传只入一次',
  fixed_at BIGINT DEFAULT NULL COMMENT '本地修改完成时间，缺省取服务端接收时间',
  add_time BIGINT NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_report_hash (report_hash),
  KEY idx_fp (app_id, fingerprint),
  KEY idx_issue (issue_id),
  KEY idx_fixer (fixer)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='崩溃修复报告';

-- 5) 同指纹复发需要「带历史」，聚合问题上加复发计数与最近一次修复摘要
ALTER TABLE logan_crash_issue ADD COLUMN reopen_count INT NOT NULL DEFAULT 0 COMMENT '修复后被同类崩溃重新打开的次数';
ALTER TABLE logan_crash_issue ADD COLUMN last_fix_note TEXT COMMENT '最近一次本地修复的摘要，复发时随证据一起展示';

-- 6) 排序规则统一：官方老表 utf8mb4_general_ci/utf8mb3，MySQL8 新表默认 utf8mb4_0900_ai_ci，
--    跨表比较(logan_log_detail JOIN logan_task, EXISTS(logan_crash) 等)会报
--    "Illegal mix of collations"。全部 CONVERT 到 utf8mb4_general_ci。
ALTER TABLE logan_log_detail CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
ALTER TABLE logan_task CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
ALTER TABLE web_detail CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
ALTER TABLE web_task CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
ALTER TABLE logan_app CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
ALTER TABLE logan_crash CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
ALTER TABLE logan_crash_fix CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
ALTER TABLE logan_crash_issue CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
ALTER DATABASE logan CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
