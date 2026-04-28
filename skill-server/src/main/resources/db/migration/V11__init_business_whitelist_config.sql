-- V11__init_business_whitelist_config.sql
-- 总开关：默认 '0' = 关闭白名单 gate（全 business 走云端，等同当前线上行为）
-- 幂等：利用 sys_config 已有的 UNIQUE KEY uk_type_key (config_type, config_key)（V10 创建）
INSERT INTO sys_config (config_type, config_key, config_value, description, status) VALUES
  ('cloud_route', 'business_whitelist_enabled', '0',
   '业务助手云端白名单开关：1=启用白名单 gate；0=关闭 gate（全 business 放行，老行为）', 1)
ON DUPLICATE KEY UPDATE id = id;
