-- V10__create_sys_config.sql
CREATE TABLE IF NOT EXISTS sys_config (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    config_type VARCHAR(64) NOT NULL COMMENT '配置类型',
    config_key VARCHAR(128) NOT NULL COMMENT '配置键',
    config_value VARCHAR(512) NOT NULL COMMENT '配置值',
    description VARCHAR(256) DEFAULT '' COMMENT '描述',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '状态：1启用 0禁用',
    sort_order INT NOT NULL DEFAULT 0 COMMENT '排序',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_type_key (config_type, config_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='通用配置表';

-- 初始化默认云端请求策略
INSERT INTO sys_config (config_type, config_key, config_value, description) VALUES
('cloud_request_strategy', 'uniassistant', 'default', '统一助手，使用默认策略');
