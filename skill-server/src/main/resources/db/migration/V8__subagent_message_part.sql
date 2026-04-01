-- Add subagent tracking columns to skill_message_part

ALTER TABLE skill_message_part
  ADD COLUMN subagent_session_id VARCHAR(64) NULL COMMENT 'OpenCode child session ID for subagent parts' AFTER finish_reason,
  ADD COLUMN subagent_name VARCHAR(128) NULL COMMENT 'Subagent name (e.g. code-reviewer)' AFTER subagent_session_id;

ALTER TABLE skill_message_part
  ADD INDEX idx_subagent_session (session_id, subagent_session_id);
