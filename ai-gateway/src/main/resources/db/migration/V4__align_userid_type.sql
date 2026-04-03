-- =============================================
-- V4: Align gateway user_id type with protocol (String)
-- =============================================

ALTER TABLE agent_connection
  MODIFY COLUMN user_id VARCHAR(128) NOT NULL;

ALTER TABLE ak_sk_credential
  MODIFY COLUMN user_id VARCHAR(128) NOT NULL COMMENT 'Associated user ID';
