# 数据库规范

> `skill-server` 的数据面由 MySQL + MyBatis + Redis 共同组成；会话 ownership、stream seq、pub/sub 不再回落到 MySQL。

---

## 概览

- **关系库**：MySQL，业务实体主要是 `skill_session`、`skill_message`、`skill_message_part`
- **ORM**：MyBatis XML Mapper
- **缓存 / 协调**：Redis，用于 ownership、pub/sub、toolSession mapping、stream seq、buffer
- **迁移**：顺序 SQL 脚本，当前已经到 `V11`

配置证据：`skill-server/src/main/resources/application.yml:11-124`

---

## MyBatis 接口风格

Repository 接口必须是薄 Mapper，不承担业务逻辑：

```java
@Mapper
public interface SkillMessageRepository {
    SkillMessage findById(@Param("id") Long id);

    List<SkillMessage> findBySessionId(@Param("sessionId") Long sessionId,
                    @Param("offset") int offset,
                    @Param("limit") int limit);

    int findMaxSeqBySessionId(@Param("sessionId") Long sessionId);

    int insert(SkillMessage message);
}
```

来源：`skill-server/src/main/java/com/opencode/cui/skill/repository/SkillMessageRepository.java:13-45`

调用侧直接在 service 中使用 Mapper：

```java
@Transactional
SkillMessage saveMessage(SaveMessageCommand cmd) {
    int nextSeq = messageRepository.findMaxSeqBySessionId(cmd.sessionId()) + 1;
    ...
    messageRepository.insert(message);
    scheduleLatestHistoryRefreshAfterCommit(cmd.sessionId());
    return message;
}
```

来源：`skill-server/src/main/java/com/opencode/cui/skill/service/SkillMessageService.java:66-90`

规则：

- 不要再包装一层“DAO Service”。
- 多参数方法必须显式 `@Param`。
- 事务在 service，Mapper 只做 SQL 映射。

---

## XML Mapper 约定

XML Mapper 的稳定模式是：

- `<resultMap>` 显式映射枚举与字段别名
- `<sql id="selectAllColumns">` 抽公共列
- 分页统一 `LIMIT ... OFFSET`

`SkillMessageMapper.xml` 当前实现：

```xml
<resultMap id="SkillMessageResultMap" type="com.opencode.cui.skill.model.SkillMessage">
    <id     property="id"          column="id"/>
    <result property="messageId"   column="message_id"/>
    <result property="role"        column="role"
            typeHandler="org.apache.ibatis.type.EnumTypeHandler"
            javaType="com.opencode.cui.skill.model.SkillMessage$Role"/>
    <result property="contentType" column="content_type"
            typeHandler="org.apache.ibatis.type.EnumTypeHandler"
            javaType="com.opencode.cui.skill.model.SkillMessage$ContentType"/>
</resultMap>

<select id="findBySessionId" resultMap="SkillMessageResultMap">
    <include refid="selectAllColumns"/>
    WHERE session_id = #{sessionId}
    ORDER BY seq ASC
    LIMIT #{limit} OFFSET #{offset}
</select>
```

Java 侧来源：`skill-server/src/main/java/com/opencode/cui/skill/repository/SkillMessageRepository.java:23-45`  
配套 XML：`skill-server/src/main/resources/mapper/SkillMessageMapper.xml:7-110`

动态过滤示例在 `SkillSessionMapper.xml`：

```xml
<select id="findByUserIdFiltered" resultMap="SkillSessionResultMap">
    <include refid="selectAllColumns"/>
    WHERE user_id = #{userId}
    <if test="ak != null and ak != ''">
        AND ak = #{ak}
    </if>
    <if test="statuses != null and statuses.size() > 0">
        AND status IN
        <foreach item="s" collection="statuses" open="(" separator="," close=")">
            #{s}
        </foreach>
    </if>
    ORDER BY last_active_at DESC
    LIMIT #{limit} OFFSET #{offset}
</select>
```

Java 侧来源：`skill-server/src/main/java/com/opencode/cui/skill/service/SkillSessionService.java:113-143`  
配套 XML：`skill-server/src/main/resources/mapper/SkillSessionMapper.xml:106-158`

---

## 迁移脚本清单

当前 migration 目录：

```text
V1__skill.sql
V2__message_parts.sql
V3__align_session_protocol.sql
V4__align_userid_type.sql
V5__snowflake_primary_keys.sql
V6__session_chat_triple.sql
V7__skill_message_session_seq_unique.sql
V8__subagent_message_part.sql
V9__tool_session_id_index.sql
V10__create_sys_config.sql
V11__assistant_account_session_lookup.sql
```

资源路径：`skill-server/src/main/resources/db/migration/`

规则：

- 只追加，不修改已发布脚本。
- migration 名必须反映真实 schema 变化；不要写 `misc_fix.sql` 这种空洞名字。
- schema 变更必须同步更新 `model/`、`repository/`、XML Mapper。

---

## Redis key / topic 规范

### 1. session ownership

`SessionRouteService` 已经是**纯 Redis ownership**，不再回落 MySQL：

```java
private static final String SESSION_CACHE_PREFIX = "ss:internal:session:";

public void createRoute(String ak, Long welinkSessionId, String sourceType, String userId) {
    String key = SESSION_CACHE_PREFIX + welinkSessionId;
    Boolean created = redisTemplate.opsForValue().setIfAbsent(
            key, instanceId, Duration.ofSeconds(ownershipCacheTtlSeconds));
    ...
}
```

来源：`skill-server/src/main/java/com/opencode/cui/skill/service/SessionRouteService.java:24-83`

TTL 语义也被测试固化：

```java
private static final int TTL_SECONDS = 1800;
private static final String CACHE_PREFIX = "ss:internal:session:";

when(valueOps.setIfAbsent(
        eq(CACHE_PREFIX + "12345"),
        eq(INSTANCE_ID),
        eq(Duration.ofSeconds(TTL_SECONDS)))).thenReturn(true);
```

来源：`skill-server/src/test/java/com/opencode/cui/skill/service/SessionRouteServiceCacheTest.java:34-49,103-117`

### 2. pub/sub topics

`RedisMessageBroker` 当前稳定 topic 前缀：

```java
public void publishToAgent(String agentId, String message) {
    String channel = "agent:" + agentId;
    publishMessage(channel, message);
}

public void publishToUser(String userId, String message) {
    String channel = "user-stream:" + userId;
    publishMessage(channel, message);
}

private static final String SS_RELAY_CHANNEL_PREFIX = "ss:relay:";
private static final String SS_EXTERNAL_RELAY_CHANNEL_PREFIX = "ss:external-relay:";
```

来源：`skill-server/src/main/java/com/opencode/cui/skill/service/RedisMessageBroker.java:47-71,174-221`

### 3. 其他 Redis key

| 前缀 | 含义 | 来源 |
|------|------|------|
| `ss:internal:session:{sessionId}` | session ownership -> instanceId | `SessionRouteService.java:24-83` |
| `user-stream:{userId}` | miniapp user-stream 频道 | `RedisMessageBroker.java:52-70` |
| `agent:{agentId}` | agent 频道 | `RedisMessageBroker.java:47-65` |
| `ss:relay:{instanceId}` | SS 间 relay 频道 | `RedisMessageBroker.java:176-221` |
| `ss:external-relay:{instanceId}` | SS 间 external WS relay 频道 | `RedisMessageBroker.java::publishToExternalRelay` |
| `external-ws:held-by:{instanceId}` | 本 SS 持有的 external WS domain -> connectionCount 快照 | `RedisMessageBroker.java::heldByPutAll` |
| `conn:ak:{ak}` | AK 所在 gateway instance | `RedisMessageBroker.java:223-235` |
| `ss:tool-session:{toolSessionId}` | toolSessionId -> sessionId 映射 | `RedisMessageBroker.java:239-251` |
| `ss:stream-seq:{sessionId}` | 跨实例传输序号 | `RedisMessageBroker.java:255-260` |
| `assistantAccount:status:{account}` | 助手 existence 三态缓存；value 为 JSON `{status, ak?, ownerWelinkId?, assistantAccount?, remote?, businessTag?}`；`ownerWelinkId` 为兼容字段，语义是本地助手 `createdBy` owner；远端助手允许 `ak` 为空且 owner 为空；双 TTL：EXISTS 300s / NOT_EXISTS 60s / UNKNOWN 不写 | `AssistantAccountResolverService.java` |
| ~~`assistantAccount:ak:{account}`~~ | **DEPRECATED**：已合并到 `assistantAccount:status:*`，旧 key 自然过期失效，不再读写 | — |
| ~~`assistantAccount:owner:{account}`~~ | **DEPRECATED**：已合并到 `assistantAccount:status:*`，旧 key 自然过期失效，不再读写 | — |

规则：

- 不要在业务代码里手写散落的 key 字符串；至少要复用同一个前缀常量。
- ownership、relay、stream seq 这三类 key 不能再设计 MySQL fallback。

### 4. 分布式锁

| 前缀 | 含义 | TTL | 来源 |
|------|------|-----|------|
| `skill:im-session:create:{domain}:{type}:{sessionId}:{ak}` | `createSessionAsync` 创建 SkillSession 的互斥锁 | 15s | `ImSessionManager.java` |
| `skill:im-session:heal:{welinkSessionId}` | business 助手 toolSessionId 自愈 / rebuild 重生的互斥锁 | 10s | `InboundProcessingService.java` |

规则：

- 锁 value 用 `UUID.randomUUID()`，释放前必须 CAS 比对 value，避免释放到别人的锁。
- 获取失败必须有兜底路径：`create` 锁失败直接 `return`（另一个实例在创建，交给它）；`heal` 锁失败走"轮询 DB 看 peer 是否已完成 → 超时降级到 `requestToolSession`"。
- 禁止用 `skill:im-session:*` 前缀存业务数据；该前缀只给协作/互斥语义用。

---

## Lettuce native API 调用

Spring Data Redis 没有为所有 Redis 命令提供高层封装（如 `PUBSUB NUMSUB`）。这些命令必须走 Lettuce native API，**禁止**用 raw `connection.execute(command, args...)` —— 后者使用 `ByteArrayOutput` 解码，遇到 RESP integer / map reply 会抛 `UnsupportedOperationException` —— 该 bug 在 Lettuce 6.4.x 仍然存在。

正确写法：**通过 `connectionFactory.getConnection()` 拿 raw `LettuceConnection`**，从 `getNativeConnection()` 拿 `BaseRedisAsyncCommands`，调对应方法 + `.get(timeout)` 拿结果。**禁止**走 `redisTemplate.execute((RedisCallback) conn -> ...)`：默认 `exposeConnection=false`，conn 会被包成 `CloseSuppressingInvocationHandler` JDK 代理（实现 `RedisConnection` interface 但不是 `LettuceConnection` 类的实例），导致 `instanceof LettuceConnection` cast 永远 false → 早 return。详见 `conventions.md` 的 "RedisTemplate.execute 包 connection 成 proxy" 段。

```java
public long physicalSubscriberCount(String channel) {
    if (channel == null || channel.isBlank()) return 0L;
    RedisConnectionFactory factory = redisTemplate.getRequiredConnectionFactory();
    RedisConnection conn = RedisConnectionUtils.getConnection(factory);
    try {
        if (!(conn instanceof LettuceConnection lettuce)) return 0L;
        Object nativeConn = lettuce.getNativeConnection();
        if (!(nativeConn instanceof BaseRedisAsyncCommands<?, ?> base)) return 0L;
        @SuppressWarnings("unchecked")
        BaseRedisAsyncCommands<byte[], byte[]> async = (BaseRedisAsyncCommands<byte[], byte[]>) base;
        byte[] channelBytes = channel.getBytes(StandardCharsets.UTF_8);
        try {
            Map<byte[], Long> result = async.pubsubNumsub(channelBytes).get(2, TimeUnit.SECONDS);
            return result.values().stream().findFirst().orElse(0L);
        } catch (TimeoutException | ExecutionException e) {
            return 0L;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 0L;
        }
    } catch (Exception e) {
        return 0L;
    } finally {
        RedisConnectionUtils.releaseConnection(conn, factory);
    }
}
```

来源：`skill-server/src/main/java/com/opencode/cui/skill/service/RedisMessageBroker.java::physicalSubscriberCount`

关键修正（容易踩坑）：

- **绝不在 `RedisCallback` 内做 `instanceof LettuceConnection` cast**：默认 `exposeConnection=false`，传给 callback 的是 JDK 代理对象，cast 永远 false。
- **`LettuceConnection.getNativeConnection()` 返回 `RedisClusterAsyncCommands`，不是 `StatefulRedisConnection`**。它继承自 `BaseRedisAsyncCommands`，命令直接可调。**禁止**调 `.sync()` —— 该方法在 `*AsyncCommands` 上**不存在**。
- 默认 codec 是 `ByteArrayCodec`，泛型 `<byte[], byte[]>`。channel 名要 `.getBytes(UTF_8)`。
- 必须用 `.get(timeout)` 拿结果（默认 Lettuce 命令超时 60s 太长）；本项目约定 **2s**。
- 必须有 `instanceof LettuceConnection` 防御 cast；切到 Jedis 时 graceful 降级。
- 异常路径返回**业务安全 fallback 值**（如 0），不抛给业务层。
- `RedisConnectionUtils.releaseConnection(conn, factory)` 必须在 `finally` 内调用，正确处理 share / dedicated 连接的 close 语义；不要自己 `conn.close()`，那会误关共享连接。

规则：

- 新增 raw Redis 调用前，先在 `StringRedisTemplate` / `RedisOperations` 上找接口，确认没有高层封装才走 native API。
- 不要把 `BaseRedisAsyncCommands` 引用 cache 到字段 — 跨 callback 复用涉及 `shareNativeConnection` 语义。
- 测试必须 mock 到 `connectionFactory.getConnection()` 这一层（不是 `redisTemplate.execute`），见 `conventions.md` 的"测试 mock 不能跨过抽象层"段。

---

## Redis 对象缓存序列化：用 JSON，不要裸字符串拼接

当 Redis value 需要缓存复合对象（多个字段，且字段值包含自由文本），**禁止用管道分隔符（`|`）或任意分隔符拼接**。自由文本字段可能包含分隔符本身（如 markdown 表格中的 `|`），导致反序列化时字段错位。

错误模式：
```java
// ❌ 管道分隔：message 含 markdown 表格的 | 会破坏字段解析
String serialized = r.source().name() + "|" + r.message() + "|" + r.toolType();
redisTemplate.opsForValue().set(key, serialized, ttl);
```

正确模式 — Jackson `ObjectMapper` 读写 JSON：
```java
// ✅ JSON 序列化：类型安全，不受字段内容影响
private final ObjectMapper objectMapper;

private String serialize(AvailabilityResult r) {
    try {
        return objectMapper.writeValueAsString(r);
    } catch (JsonProcessingException e) {
        log.warn("Failed to serialize {}: {}", r.source(), e.getMessage());
        return null;
    }
}

private AvailabilityResult deserialize(String cached) {
    try {
        return objectMapper.readValue(cached, AvailabilityResult.class);
    } catch (Exception e) {
        log.warn("Failed to deserialize availability cache: {}", e.getMessage());
        return null;
    }
}
```

来源：`skill-server/src/main/java/com/opencode/cui/skill/service/AssistantAvailabilityService.java`

规则：

- 对象缓存值统一用 Jackson JSON 序列化，不用手工拼接字符串
- 对应的 record 加 `@JsonInclude(NON_NULL)` + `@JsonIgnoreProperties(ignoreUnknown = true)`（向后兼容字段变更）
- 序列化失败 → 返回 null 并跳过 Redis 写入（不抛异常）
- 反序列化失败 → 返回 null，调用方视同 cache miss
- 简单位元数据（单字段、无特殊字符）可继续用 `String.format()` 拼接

---

## 事务边界

### 会话创建

`SkillSessionService.createSession` 在一个事务里写 session，再同步写 Redis ownership：

```java
SkillSession session = SkillSession.builder()
        .id(snowflakeIdGenerator.nextId())
        .userId(userId)
        .ak(ak)
        ...
        .build();

sessionRepository.insert(session);

if (ak != null) {
    sessionRouteService.createRoute(ak, session.getId(), "skill-server", userId);
}
```

来源：`skill-server/src/main/java/com/opencode/cui/skill/service/SkillSessionService.java:53-77`

### 消息写入

`SkillMessageService.saveMessage` 只负责 DB 写入和 after-commit 刷新触发：

```java
@Transactional
SkillMessage saveMessage(SaveMessageCommand cmd) {
    ...
    messageRepository.insert(message);
    scheduleLatestHistoryRefreshAfterCommit(cmd.sessionId());
    ...
}
```

来源：`skill-server/src/main/java/com/opencode/cui/skill/service/SkillMessageService.java:66-90`

### 流式 part 落盘

`MessagePersistenceService.persistIfFinal` 先缓冲 / 统计，等 `session.status=idle` 再 flush：

```java
boolean refreshed = switch (msg.getType()) {
    case StreamMessage.Types.TEXT_DONE -> persistTextPart(sessionId, msg, "text", active);
    case StreamMessage.Types.TOOL_UPDATE -> persistToolPartIfFinal(sessionId, msg, active);
    case StreamMessage.Types.STEP_DONE -> persistStepDone(sessionId, msg, active);
    case StreamMessage.Types.SESSION_STATUS -> {
        handleSessionStatus(sessionId, msg);
        yield false;
    }
    default -> false;
};
if (refreshed) {
    messageService.scheduleLatestHistoryRefreshAfterCommit(sessionId);
}
```

来源：`skill-server/src/main/java/com/opencode/cui/skill/service/MessagePersistenceService.java:63-91`

测试证据：`skill-server/src/test/java/com/opencode/cui/skill/service/MessagePersistenceServiceTest.java:58-130`

规则：

- 外部 HTTP / WebSocket 下发不要混进 DB 事务里。
- cache 刷新统一放 `afterCommit`。
- session idle / flush 相关逻辑先走 buffer，再批量 upsert。

---

## sys_config 默认助手规则（新 type，任务 05-15-noauth-conversation-permission）

| 字段 | 值 |
|---|---|
| `config_type` | `"default_assistant_rule"` |
| `config_key` | `"{businessSessionDomain}:{businessSessionType}"` 字面比对 |
| `config_value` | JSON `{"ak":"...","assistantAccount":"...","businessTag":"..."}` 3 字段非 blank |
| `status` | 1=启用，0=禁用 |

读取入口：`DefaultAssistantRuleService.lookup(domain, domainType)` 复用 `SysConfigService.getValue` Redis 5min 缓存。运维改规则用现有 `/api/admin/configs/**`；`SysConfigService.update` 自动 evict 缓存，下次 lookup 拿新值。

精确匹配语义：null/blank 入参或 sys_config 无对应 key 一律视为未命中。**不**做通配 / 大小写归一 / 模糊匹配。

详见运维 SOP `docs/superpowers/specs/2026-05-15-default-assistant-rule-ops.md`。

---

## sys_config personal scope 允许 slash 命令清单（新 type，任务 05-19-allowed-slash-commands）

| 字段 | 值 |
|---|---|
| `config_type` | `"allowed_slash_commands"` |
| `config_key` | `"{businessSessionDomain}_{businessSessionType}"`（**下划线**拼接，无冒号；与 default_assistant_rule 不同） |
| `config_value` | JSON 数组字符串，如 `["plan","ask","run"]`（严格 `string[]`，含非文本元素整数组拒绝） |
| `status` | 1=启用，0=禁用 |

读取入口：`AllowedSlashCommandsResolver.resolve(domain, type)` 复用 `SysConfigService.getValue` Redis 5min 缓存（与 `DefaultAssistantRuleService` 薄壳约定一致，**不再加外层缓存**避免 evict 传播变慢）。运维改规则同样走 `/api/admin/configs/**`；`SysConfigService.update/create` 自动 evict 缓存。**注意 `SysConfigService.delete` 不知 type/key**，删除依赖 5min TTL 自然过期；运维要立即生效请走 `update(status=0)` 路径。

兜底语义：sysconfig null / blank / parse 失败 / 非数组 / **含任一非 textual 元素**（数字 / 布尔 / 对象 / null）/ 空数组 / 全 blank 元素 / domain 或 type 缺失 → 一律返 null（caller 不下发 `platformExtParam.allowedSlashCommands` key）。

调用门控（双重）：
1. **action guard**：仅 `action == CHAT` 走 resolver；reply / create / close / abort 一律传 null
2. **personal scope gating**：strategy.generateToolSessionId() == null 判定 personal；business / default_assistant 即使 CHAT 也不调 resolver

调用点 3 处：`SkillMessageController.routeToGateway`（A4 CHAT 分支）/ `InboundProcessingService.dispatchChatToGateway`（A7 + B2，appendToPending 间接判定 personal）/ `SessionRebuildService.rebuildToolSession` legacy String overload（含 IM/External case B + business self-heal fallback）。

frozen 语义：personal first chat 入 pending 时 resolve 一次写入 `PendingChatRequest.allowedSlashCommands`，retry 路径 `GatewayMessageRouter.retryPendingMessages` 复用 frozen list，sysconfig 期间被更新不影响 retry 下发的值。

详见 PRD `.trellis/tasks/05-19-allowed-slash-commands/prd.md`。

---

## 常见错误

1. 不要在 XML 里用 `${}` 拼接用户输入；统一 `#{}` 绑定。
2. 不要把 Redis ownership 设计回 MySQL 双写；当前 contract 已经是纯 Redis。
3. 不要在事务里直接做耗时外部调用；先持久化，再下发。
4. 不要漏掉 migration / model / mapper 的三件套同步更新。
