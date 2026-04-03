-- Agent identity persistence: mac_address, data cleanup, unique constraint
-- v3: 2026-03-09

-- 1. Add mac_address column
ALTER TABLE agent_connection ADD COLUMN mac_address VARCHAR(64) AFTER device_name;

-- 2. Deduplicate: keep only the latest record per (ak_id, tool_type)
DELETE a FROM agent_connection a
INNER JOIN (
    SELECT ak_id, tool_type, MAX(id) AS keep_id
    FROM agent_connection
    GROUP BY ak_id, tool_type
) b ON a.ak_id = b.ak_id AND a.tool_type = b.tool_type AND a.id != b.keep_id;

-- 3. Reset all to OFFLINE (avoid stale ONLINE after restart)
UPDATE agent_connection SET status = 'OFFLINE';

-- 4. Unique constraint to enforce one record per AK + toolType
ALTER TABLE agent_connection ADD UNIQUE INDEX uk_ak_tooltype (ak_id, tool_type);
