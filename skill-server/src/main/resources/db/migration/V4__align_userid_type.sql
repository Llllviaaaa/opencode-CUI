-- =============================================
-- V4: Align user_id type with protocol (String)
-- =============================================

-- Change user_id from BIGINT to VARCHAR(128) to match protocol spec
ALTER TABLE skill_session
  MODIFY COLUMN user_id VARCHAR(128) NOT NULL;
