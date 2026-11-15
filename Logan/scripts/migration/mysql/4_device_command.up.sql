SET NAMES utf8mb4;

-- 1) 主动拉日志命令表：控制台下发命令，客户端轮询领取（用户拍板：轮询通道，不做 WebSocket）
CREATE TABLE IF NOT EXISTS logan_device_command (
  id BIGINT NOT NULL AUTO_INCREMENT,
  app_id VARCHAR(128) NOT NULL COMMENT '应用标识（端粒度，与 logan_app 一致）',
  device_id VARCHAR(128) NOT NULL COMMENT '目标设备机器码',
  action VARCHAR(32) NOT NULL COMMENT 'upload_logs 拉日志 / ping 探活',
  param VARCHAR(512) DEFAULT NULL COMMENT '命令参数 JSON，如 {hours:24}',
  status VARCHAR(16) NOT NULL DEFAULT 'pending' COMMENT 'pending/delivered/acked/failed/cancelled/expired/lost',
  operator VARCHAR(64) DEFAULT NULL COMMENT '下发人（控制台登录名）',
  note VARCHAR(512) DEFAULT NULL COMMENT '下发备注/原因',
  result_note VARCHAR(512) DEFAULT NULL COMMENT '客户端回执说明',
  deliver_count INT NOT NULL DEFAULT 0 COMMENT '投递次数（领取即 +1）',
  add_time BIGINT NOT NULL,
  delivered_time BIGINT DEFAULT NULL,
  ack_time BIGINT DEFAULT NULL,
  expire_time BIGINT NOT NULL COMMENT '过期时间，过期未领取置 expired',
  update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_device_status (app_id, device_id, status),
  KEY idx_status_expire (status, expire_time),
  KEY idx_add_time (add_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='下行命令（主动拉日志）';

-- 2) 设备心跳表：客户端轮询命令即上报存活，用于「在线/最近活跃」判定（不另建注册表，设备清单从 logan_task/logan_crash 派生）
CREATE TABLE IF NOT EXISTS logan_device_poll (
  id BIGINT NOT NULL AUTO_INCREMENT,
  app_id VARCHAR(128) NOT NULL,
  device_id VARCHAR(128) NOT NULL,
  platform TINYINT NOT NULL DEFAULT 1 COMMENT '1 android 2 ios 3 harmony',
  app_version VARCHAR(64) DEFAULT NULL,
  build_version VARCHAR(64) DEFAULT NULL,
  sdk_version VARCHAR(32) DEFAULT NULL COMMENT 'SDK 版本，便于灰度确认',
  last_poll_time BIGINT NOT NULL,
  poll_count BIGINT NOT NULL DEFAULT 0,
  add_time BIGINT NOT NULL,
  update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_app_device (app_id, device_id),
  KEY idx_last_poll (last_poll_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='设备心跳（命令轮询顺带上报）';

