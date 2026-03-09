-- =============================================
-- V3: Align skill_session columns with protocol
-- =============================================

-- 1. Rename agent_id → ak + fix type (was BIGINT, should be VARCHAR)
ALTER TABLE skill_session
  ADD COLUMN ak VARCHAR(64) NULL AFTER user_id;

UPDATE skill_session SET ak = CAST(agent_id AS CHAR) WHERE agent_id IS NOT NULL;

ALTER TABLE skill_session
  DROP INDEX idx_agent,
  DROP COLUMN agent_id;

ALTER TABLE skill_session
  ADD INDEX idx_ak (ak);

-- 2. Rename im_chat_id → im_group_id
ALTER TABLE skill_session
  CHANGE COLUMN im_chat_id im_group_id VARCHAR(128);

-- 3. Drop skill_definition_id (protocol doesn't use it, only one skill in MVP)
ALTER TABLE skill_session
  DROP COLUMN skill_definition_id;
