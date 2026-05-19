CREATE INDEX idx_ak_last_seen ON agent_connection (ak_id, last_seen_at DESC, id DESC);
