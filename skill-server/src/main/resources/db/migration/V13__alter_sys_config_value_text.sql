-- V13__alter_sys_config_value_text.sql
ALTER TABLE sys_config MODIFY COLUMN config_value TEXT NOT NULL COMMENT '配置值';
