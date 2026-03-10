# Agent 身份持久化设计

> 版本：1.0  
> 日期：2026-03-09

## 1. 问题描述

当前 `AgentRegistryService.register()` 每次调用都执行 `INSERT`，导致：

- 同一设备重启后获得不同 `agentId`
- 历史会话关联断裂
- `agent_connection` 表无限膨胀

## 2. 目标设计

### 2.1 核心原则

**同一 AK + 同一 toolType = 同一条 agent_connection 记录**

### 2.2 注册逻辑

```text
register(userId, akId, macAddress, deviceName, os, toolType, toolVersion):
  │
  ├── findByAkIdAndToolType(akId, toolType)
  │     │
  │     ├── 找到已有记录 ──→ UPDATE:
  │     │     status = ONLINE
  │     │     deviceName = 新值
  │     │     macAddress = 新值
  │     │     os = 新值
  │     │     toolVersion = 新值
  │     │     lastSeenAt = now()
  │     │     return existing (同一个 agentId)
  │     │
  │     └── 未找到 ──→ INSERT:
  │           新建 AgentConnection 记录
  │           return agent (新 agentId)
```

### 2.3 效果

| 场景                  | 旧行为               | 新行为                     |
| --------------------- | -------------------- | -------------------------- |
| 同一设备首次连接      | INSERT               | INSERT（相同）             |
| 同一设备重启后连接    | INSERT（新 agentId） | **UPDATE（同一 agentId）** |
| 同一设备切换 toolType | INSERT               | INSERT（不同 toolType）    |
| 不同 AK 连接          | INSERT               | INSERT（不同 AK）          |

## 3. 数据库迁移

### 3.1 迁移脚本：V3__agent_identity_persistence.sql

```sql
-- 1. 新增 mac_address 列
ALTER TABLE agent_connection
ADD COLUMN mac_address VARCHAR(64) AFTER device_name;

-- 2. 清理历史重复记录：每个 ak_id+tool_type 只保留最新一条
DELETE a FROM agent_connection a
INNER JOIN (
    SELECT ak_id, tool_type, MAX(id) AS keep_id
    FROM agent_connection
    GROUP BY ak_id, tool_type
) b ON a.ak_id = b.ak_id
  AND a.tool_type = b.tool_type
  AND a.id != b.keep_id;

-- 3. 重置所有状态为 OFFLINE（避免重启后残留 ONLINE）
UPDATE agent_connection SET status = 'OFFLINE';

-- 4. 添加唯一约束：保证一个 AK+toolType 只有一条记录
ALTER TABLE agent_connection
ADD UNIQUE INDEX uk_ak_tooltype (ak_id, tool_type);
```

### 3.2 迁移影响

| 操作         | 影响                                                    |
| ------------ | ------------------------------------------------------- |
| 新增列       | 低风险，VARCHAR(64) 允许 NULL                           |
| 删除重复记录 | **有数据删除**，保留每组最新的一条                      |
| 重置状态     | 所有 Agent 需重新连接                                   |
| 唯一约束     | 后续同 AK+toolType 的 INSERT 会报错（需走 UPDATE 路径） |

### 3.3 表结构变更

```text
agent_connection
├── id               BIGINT AUTO_INCREMENT (PK)
├── user_id          BIGINT
├── ak_id            VARCHAR(64)
├── device_name      VARCHAR(128)
├── mac_address      VARCHAR(64)     ← 新增
├── os               VARCHAR(32)
├── tool_type        VARCHAR(32)
├── tool_version     VARCHAR(32)
├── status           ENUM('ONLINE','OFFLINE')
├── last_seen_at     DATETIME
├── created_at       DATETIME
└── UNIQUE INDEX uk_ak_tooltype (ak_id, tool_type) ← 新增
```

## 4. Repository 变更

### 4.1 新增方法

```java
// 不限 status 查找：用于身份复用
AgentConnection findByAkIdAndToolType(String akId, String toolType);

// 更新设备信息和状态：用于复用记录时的 UPDATE
int updateAgentInfo(AgentConnection agent);
```

### 4.2 对应 SQL

```xml
<select id="findByAkIdAndToolType" resultMap="AgentConnectionResultMap">
    SELECT * FROM agent_connection
    WHERE ak_id = #{akId} AND tool_type = #{toolType}
    LIMIT 1
</select>

<update id="updateAgentInfo">
    UPDATE agent_connection
    SET status = #{status},
        device_name = #{deviceName},
        mac_address = #{macAddress},
        os = #{os},
        tool_version = #{toolVersion},
        last_seen_at = #{lastSeenAt}
    WHERE id = #{id}
</update>
```

## 5. 会话复用效果

由于 `agentId` 保持稳定，只要上层按 `agentId` 或 `akId` 关联会话，重启后即可恢复历史会话上下文：

```text
第一次启动: AK=ak_001, toolType=OPENCODE → agentId=42 (INSERT)
→ 创建若干 skill_session 关联 agentId=42

第二次启动: AK=ak_001, toolType=OPENCODE → agentId=42 (UPDATE)
→ 查询到历史 skill_session，可继续使用
```
