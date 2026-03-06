---
phase: 2
verified_at: 2026-03-06T08:43:00+08:00
verdict: PASS
---

# Phase 2 验证报告

## 概要
7/7 Must-Haves 验证通过

## 基线验证

### ✅ 编译通过
**证据:** `mvn compile` → BUILD SUCCESS
```
[INFO] Building ai-gateway 1.0.0-SNAPSHOT
[INFO] BUILD SUCCESS
```

### ✅ 单元测试通过
**证据:** `mvn test` → 24/24 pass
```
Tests run: 15, Failures: 0, Errors: 0 -- GatewayMessageTest
Tests run: 9, Failures: 0, Errors: 0  -- AkSkAuthServiceTest
Tests run: 24, Failures: 0, Errors: 0 -- Total
BUILD SUCCESS
```

## Must-Haves

### ✅ REQ-03: AK/SK HMAC-SHA256 认证
**验证方法:** 代码搜索
**证据:**
- `AkSkAuthService.java:30` — `private static final String HMAC_SHA256 = "HmacSHA256";`
- `AkSkAuthService.java:104` — `expectedSignature = computeHmacSha256(record.sk, message);`
- `AkSkAuthService.java:125` — `private String computeHmacSha256(String key, String message)`
- 9 个单元测试覆盖正常/异常路径

### ✅ REQ-08: Gateway↔Skill Server WebSocket (内部 Token 认证)
**验证方法:** 代码搜索
**证据:**
- `SkillServerWSClient.java:39` — `ws-url:ws://localhost:8082/ws/internal/gateway`
- `SkillServerWSClient.java:42` — `internal-token:changeme`
- `SkillServerWSClient.java:118` — `String url = skillServerWsUrl + "?token=" + internalToken;`
- 支持指数退避重连 (1s→30s cap)
- 认证失败时停止重连 (`isInvalidInternalTokenReason`)

### ✅ REQ-09: Gateway 向转发消息注入 agentId
**验证方法:** 代码搜索
**证据:**
- `EventRelayService.java:128` — `GatewayMessage forwarded = message.withAgentId(agentId);`
- `GatewayMessage.java:178` — `withAgentId()` 方法完整拷贝所有字段（含 session, opencodeOnline）

### ✅ REQ-10: agent_online/agent_offline 通知
**验证方法:** 代码审查
**证据:**
- `AgentWebSocketHandler.handleRegister (L191-L193)`:
  ```java
  GatewayMessage onlineMsg = GatewayMessage.agentOnline(agentIdStr, toolType, toolVersion);
  eventRelayService.relayToSkillServer(agentIdStr, onlineMsg);
  ```
- `AgentWebSocketHandler.afterConnectionClosed (L152-L154)`:
  ```java
  GatewayMessage offlineMsg = GatewayMessage.agentOffline(agentId);
  eventRelayService.relayToSkillServer(agentId, offlineMsg);
  ```

### ✅ REQ-11: Redis Pub/Sub (`agent:{agentId}` 下行 + `session:{sessionId}` 上行)
**验证方法:** 代码搜索
**证据:**
- `RedisMessageBroker.java:59` — `String channel = "agent:" + agentId;`
- `RedisMessageBroker.java:70` — `String channel = "session:" + sessionId;`
- `EventRelayService.java:76` — `redisMessageBroker.subscribeToAgent(agentId, ...)`
- `EventRelayService.java:156` — `redisMessageBroker.publishToAgent(agentId, message)`
- `RedisConfig.java` — 线程池配置 (max-active=50, core=5, queue=100) + 错误恢复

### ✅ REQ-12: 序列号机制 (AtomicLong per session)
**验证方法:** 代码搜索
**证据:**
- `RedisMessageBroker.java:39` — `Map<String, AtomicLong> sessionSequences = new ConcurrentHashMap<>();`
- `RedisMessageBroker.java:73-75`:
  ```java
  AtomicLong sequence = sessionSequences.computeIfAbsent(sessionId, k -> new AtomicLong(0));
  long seqNum = sequence.incrementAndGet();
  ```
- `GatewayMessage.java:204` — `withSequenceNumber()` 方法完整拷贝所有字段

### ✅ REQ-26: 数据库 AK/SK 凭证存储
**验证方法:** 代码搜索 + 文件检查
**证据:**
- `AkSkCredential.java` — 实体模型 (id, ak, sk, userId, status, description, createdAt, updatedAt)
- `AkSkCredentialRepository.java` — MyBatis 接口 (findActiveByAk, findById, insert, updateStatus)
- `AkSkCredentialMapper.xml` — SQL 映射文件
- `V2__ak_sk_credential.sql` — Flyway 迁移 (建表 + 测试数据)
- `AkSkAuthService.java:159` — `credentialRepository.findActiveByAk(ak)` 替代了硬编码 TODO
- 9 个单元测试验证凭证查询逻辑

## 文件清单
| 文件                            | 作用                                     | 行数     |
| ------------------------------- | ---------------------------------------- | -------- |
| `AgentWebSocketHandler.java`    | PCAgent WS 端点                          | ~200     |
| `SkillServerWSClient.java`      | Skill Server WS 客户端                   | 228      |
| `EventRelayService.java`        | 双向事件中继                             | 196      |
| `RedisMessageBroker.java`       | Redis pub/sub 操作                       | 184      |
| `RedisConfig.java`              | Redis 线程池配置                         | 70       |
| `AkSkAuthService.java`          | AK/SK HMAC 验证                          | ~170     |
| `AkSkCredential.java`           | 凭证实体                                 | ~60      |
| `AkSkCredentialRepository.java` | MyBatis mapper 接口                      | ~30      |
| `AkSkCredentialMapper.xml`      | SQL 映射                                 | ~55      |
| `V2__ak_sk_credential.sql`      | 数据库迁移                               | ~40      |
| `GatewayMessage.java`           | 消息 DTO（含协议适配修复）               | 257      |
| 测试文件 (2)                    | GatewayMessageTest + AkSkAuthServiceTest | ~24 测试 |

共 16 个 Java 源文件，24 个单元测试

## 判定
**PASS** — 所有 7 个 Must-Have 均通过代码级验证

## 集成测试说明
> [!NOTE]
> 以下功能需要在连接真实 Redis + MariaDB 环境时做集成测试验证：
> - Redis pub/sub 消息实际投递
> - Flyway 数据库迁移执行
> - AK/SK 凭证数据库查询
> - Gateway↔Skill Server WebSocket 连接
