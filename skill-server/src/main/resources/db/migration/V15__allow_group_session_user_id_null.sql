-- Allow IM group sessions to avoid storing an arbitrary sender as session owner.
ALTER TABLE skill_session
  MODIFY COLUMN user_id VARCHAR(128) NULL;
