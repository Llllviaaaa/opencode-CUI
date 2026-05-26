# 数据库规范

> `ai-gateway` 同时使用 MySQL、MyBatis XML Mapper 和 Redis。不要把它当成“只有 Redis 的轻网关”。
---

## 概览

| 存储面 | 真实用途 | 证据 |
|--------|----------|------|
| MySQL | 持久化 Agent 连接档案与 AK/SK 凭证 | `application.yml:11-39`, `resources/mapper/*.xml` |
| Redis KV + TTL | `conn:ak:*`、`gw:internal:agent:*`、`gw:route:*`、认证 nonce/identity cache | `service/RedisMessageBroker.java:56-99,158-282,700-753`; `service/AkSkAuthService.java:41-42,119-145,247-267` |
| Redis List | `gw:pending:{ak}` 离线下行缓冲队列 | `service/RedisMessageBroker.java:95-156` |
| Redis Hash | `gw:source-conn:{sourceType}:{sourceInstanceId}` Source 连接表 | `service/RedisMessageBroker.java:429-608` |
| Redis pub/sub | `agent:{ak}`、`gw:relay:{instanceId}`、`gw:legacy-relay:{instanceId}` | `service/RedisMessageBroker.java:63-70,286-319` |

## MyBatis 使用法

Repository 接口使用 `@Mapper + @Param`，SQL 落在 `resources/mapper/*.xml`。`AgentConnectionRepository` 与 `AgentConnectionMapper.xml` 是标准写法。

```java
// Source: ai-gateway/src/main/java/com/opencode/cui/gateway/repository/AgentConnectionRepository.java:13-70
@Mapper
public interface AgentConnectionRepository {
    List<AgentConnection> findByStatus(@Param("status") AgentStatus status);
    List<AgentConnection> findByUserIdAndStatus(@Param("userId") String userId,
            @Param("status") AgentStatus status);
    AgentConnection findByAkIdAndToolType(@Param("akId") String akId,
            @Param("toolType") String toolType);
    int updateStatus(@Param("id") Long id, @Param("status") AgentStatus status);
}
```

```xml
<!-- Source: ai-gateway/src/main/resources/mapper/AgentConnectionMapper.xml:7-19,67-104 -->
<resultMap id="AgentConnectionResultMap" type="AgentConnection">
    <result property="status" column="status"
            typeHandler="org.apache.ibatis.type.EnumTypeHandler"/>
</resultMap>

<select id="findLatestByAkId" resultMap="AgentConnectionResultMap">
    SELECT * FROM agent_connection
    WHERE ak_id = #{akId}
    ORDER BY created_at DESC, id DESC
    LIMIT 1
</select>
```

## MySQL 表与模型对应

| 表 | 模型 | 主用途 |
|----|------|--------|
| `agent_connection` | `AgentConnection` | Agent 注册、重连复用、在线/离线状态、设备绑定字段 |
| `ak_sk_credential` | `AkSkCredential` | 本地 gateway 模式 AK/SK 验签 |

`AgentConnection` 与 `AkSkCredential` 都使用枚举字段，并由 `EnumTypeHandler` 做字符串映射。来源：`model/AgentConnection.java:46-63`, `model/AkSkCredential.java:36-53`, `mapper/*.xml`。

## SQL 约定

| 规则 | 说明 | 真实示例 |
|------|------|----------|
| 表名/列名 | `snake_case`，由 MyBatis 配置自动映射为 camelCase | `application.yml:35-39` |
| 查询最新记录 | 用 `ORDER BY created_at DESC, id DESC LIMIT 1` 明确取最近连接 | `AgentConnectionMapper.xml:67-72` |
| 在线过滤 | 直接按 `status = 'ONLINE'` 过滤，而不是在 Java 侧筛选 | `AgentConnectionMapper.xml:42-46,74-78` |
| 枚举映射 | 使用 `EnumTypeHandler`，不要用魔法数字 | `AgentConnectionMapper.xml:16-18`, `AkSkCredentialMapper.xml:13-15` |
| 插入 ID | 由 `SnowflakeIdGenerator` 生成，不依赖数据库自增 | `service/AgentRegistryService.java:72-85` |

## 事务边界

`AgentRegistryService` 的写操作统一加 `@Transactional`，以保证状态更新与连接档案写入的一致性。

```java
// Source: ai-gateway/src/main/java/com/opencode/cui/gateway/service/AgentRegistryService.java:48-49,93-101,133-148
@Transactional
public AgentConnection register(...) { ... }

@Transactional
public void heartbeat(Long agentId) { ... }

@Scheduled(fixedDelayString = "${gateway.agent.heartbeat-check-interval-seconds:30}000")
@Transactional
public void checkTimeouts() { ... }
```

约束：

- MySQL 写入完成后再做外部副作用（如中继通知、WS 发送）。
- `checkTimeouts()` 只负责状态收敛与本地会话清理，不直接访问外部 HTTP 服务。

## Redis Key / Channel 规范

| 前缀 | 类型 | 用途 |
|------|------|------|
| `conn:ak:{ak}` | KV + TTL | 记录某个 Agent 当前落在哪个 Gateway 实例 |
| `gw:internal:agent:{ak}` | KV + TTL | Gateway 内部路由表；与 `conn:ak` 双写 |
| `gw:pending:{ak}` | List + TTL | Agent 离线时缓存下行消息 |
| `gw:source-conn:{sourceType}:{sourceInstanceId}` | Hash + TTL | Source 连接注册表 |
| `gw:route:{toolSessionId}` | KV + TTL | `toolSessionId -> sourceType:sourceInstanceId` |
| `gw:route:w:{welinkSessionId}` | KV + TTL | `welinkSessionId -> sourceType:sourceInstanceId` |
| `gw:cloud-stream:{toolSessionId}` | KV + TTL | 云端 SSE/WebSocket 流 owner：`toolSessionId -> gatewayInstanceId` |
| `gw:agent:user:{ak}` | KV | Agent AK → userId 绑定 |
| `agent:{ak}` | pub/sub channel | Agent 本地投递通道 |
| `gw:relay:{instanceId}` | pub/sub channel | GW→GW 新版中继 |
| `gw:legacy-relay:{instanceId}` | pub/sub channel | 旧版 GW→GW 兼容中继 |

来源：`ai-gateway/src/main/java/com/opencode/cui/gateway/service/RedisMessageBroker.java:56-99,158-282,700-850`。

`gw:cloud-stream:{toolSessionId}` 只用于云端流取消的 GW owner 查找；它不能替代 `gw:route:{toolSessionId}`，后者记录的是 source service 路由。删除云端流 owner 必须走条件删除，避免非 owner GW 清掉仍在使用的流 owner。

## Pending Queue 模式

离线下行缓冲使用 Redis List，并通过 Lua 脚本原子 drain，避免 `LRANGE + DEL` 之间丢消息。

```java
// Source: ai-gateway/src/main/java/com/opencode/cui/gateway/service/RedisMessageBroker.java:95-156
public void enqueuePending(String ak, String message, Duration ttl) {
    redisTemplate.opsForList().rightPush(pendingKey(ak), message);
    redisTemplate.expire(pendingKey(ak), ttl);
}

public List<String> drainPending(String ak) {
    return redisTemplate.execute(DRAIN_PENDING_SCRIPT, java.util.List.of(pendingKey(ak)));
}
```

## Source 连接表模式

`gw:source-conn:*` 已支持**连接级别**注册：字段既写 `gwInstanceId#sessionId`，也双写兼容字段 `gwInstanceId`，以兼顾旧读者。

```java
// Source: ai-gateway/src/main/java/com/opencode/cui/gateway/service/RedisMessageBroker.java:490-549
String compoundField = gwInstanceId + "#" + sessionId;
redisTemplate.opsForHash().put(key, compoundField, timestamp);
redisTemplate.opsForHash().put(key, gwInstanceId, timestamp); // compat dual-write
redisTemplate.expire(key, SOURCE_CONN_TTL);
```

这个行为被 `RedisMessageBrokerSourceConnTest` 明确覆盖，包括 dual-write、TTL、清理 stale entry 与 compound field 解析。来源：`src/test/java/com/opencode/cui/gateway/service/RedisMessageBrokerSourceConnTest.java:62-377`。

## 认证缓存与防重放

`AkSkAuthService` 自己管理认证相关 Redis 数据，不走 `RedisMessageBroker`：

| Key | 用途 | 实现 |
|-----|------|------|
| `gw:auth:nonce:{nonce}` | 反重放，`SET NX` 成功才允许通过 | `service/AkSkAuthService.java:41,119-125` |
| `auth:identity:{ak}` | remote 模式 L2 身份缓存 | `service/AkSkAuthService.java:42,247-267` |

## 迁移脚本

- 迁移文件放在 `ai-gateway/src/main/resources/db/migration/`。
- 命名格式为 `V{n}__description.sql`。
- 已有迁移不要改历史内容，只追加新版本；当前存在 `V1` 到 `V5`。来源：`resources/db/migration/`。

## 禁止事项

1. 不要在业务代码里手写 `conn:ak:`、`gw:route:` 等字符串前缀，统一走 `RedisMessageBroker` 常量/辅助方法。
2. 不要把 Redis 用成“共享临时变量堆”；每个 key 都要有明确 TTL 与所有者。
3. 不要绕过 Mapper XML 直接在 Service 拼 SQL。
4. 不要删除 `gw:source-conn` 的兼容字段 `gwInstanceId`，除非全链路读者都已经升级。
