CREATE INDEX idx_biz_session_assistant_account
  ON skill_session (business_session_domain, business_session_type, business_session_id, assistant_account);
