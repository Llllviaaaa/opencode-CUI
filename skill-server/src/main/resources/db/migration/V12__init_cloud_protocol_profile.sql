-- V12__init_cloud_protocol_profile.sql
-- 预置 cloud_protocol_profile_def 套餐定义。
-- - default          : 入参走 default strategy；出参走 default decoder（OpenCode 标准协议）
-- - assistant_square : 入参走 assistant_square strategy；出参走 assistant_square decoder
-- 业务映射（cloud_protocol_profile:<businessTag> → profile name）由运维按需新增。
INSERT INTO sys_config (config_type, config_key, config_value, description, status) VALUES
  ('cloud_protocol_profile_def', 'default',
   '{"request_strategy":"default","response_decoder":"default"}',
   '默认套餐：入参/出参均走 OpenCode 标准协议', 1),
  ('cloud_protocol_profile_def', 'assistant_square',
   '{"request_strategy":"assistant_square","response_decoder":"assistant_square"}',
   '助手广场套餐：入参/出参均走助手广场非标协议', 1)
ON DUPLICATE KEY UPDATE id = id;
