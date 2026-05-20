-- V14__seed_assistant_offline_defaults.sql
-- 依赖 V10__create_sys_config.sql 的 UNIQUE KEY uk_type_key (config_type, config_key)，
-- ON DUPLICATE KEY UPDATE 据此保证幂等。切勿删除该唯一索引。
INSERT INTO sys_config (config_type, config_key, config_value, description, status) VALUES
  ('assistant_offline', 'not_configured',
   '该助理尚未完成初始化配置，请前往 **[OpenCode开放平台](https://opencode.woa.com)** 完成绑定后重试。',
   '未配置文案（agent_connection 表无此 AK 记录）', 1),
  ('assistant_offline', 'tool_type:opencode',
   '任务下发失败，请检查助理是否离线，确保 **OpenCode 客户端在线**后重试。',
   'OpenCode 类型离线文案', 1),
  ('assistant_offline', 'message',
   '任务下发失败，请检查助理是否离线，确保助理在线后重试。',
   '类型未知兜底离线文案', 1)
ON DUPLICATE KEY UPDATE id = id;
