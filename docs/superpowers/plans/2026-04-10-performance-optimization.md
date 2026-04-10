# 性能优化实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 解决压测中发现的 SS 侧 DB CPU 高和 GW 侧 Agent 被误判离线两个问题。

**Architecture:** SS 侧通过加索引、Redis 缓存、去除冗余 DB 操作将每次终态事件的 DB 操作从 ~30 次降到 ~9 次。GW 侧通过异步发送队列替换 synchronized 同步发送，消除心跳被业务消息阻塞的问题。

**Tech Stack:** Spring Boot 3.4 / Java 21 / MyBatis / MySQL / Redis (Lettuce) / Caffeine / WebSocket

**设计文档:** `docs/superpowers/specs/2026-04-09-performance-optimization-design.md`

---

## Phase 1：SS 侧 DB 优化

### Task 1: 给 tool_session_id 加索引

**Files:**
- Create: `skill-server/src/main/resources/db/migration/V9__tool_session_id_index.sql`

- [ ] **Step 1: 创建 migration 文件**

```sql
-- V9__tool_session_id_index.sql
CREATE INDEX idx_tool_session_id ON skill_session(tool_session_id);
```

- [ ] **Step 2: 验证 Flyway migration 能正常执行**

Run: `cd skill-server && mvn flyway:migrate -Dflyway.configFiles=src/main/resources/flyway.conf`

如果项目没有独立的 flyway 配置，通过启动应用验证：
Run: `cd skill-server && mvn spring-boot:run`
Expected: 启动日志中看到 `Successfully applied 1 migration to schema ... (execution time ...)` 包含 V9。

- [ ] **Step 3: Commit**

```bash
git add skill-server/src/main/resources/db/migration/V9__tool_session_id_index.sql
git commit -m "perf(db): add index on skill_session.tool_session_id"
```

---

### Task 2: toolSessionId → sessionId 映射加 Redis 缓存

**Files:**
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/service/RedisMessageBroker.java`
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/service/GatewayMessageRouter.java`

- [ ] **Step 1: 在 RedisMessageBroker 中新增缓存方法**

在 `RedisMessageBroker.java` 末尾新增两个方法：

```java
private static final String TOOL_SESSION_PREFIX = "ss:tool-session:";
private static final long TOOL_SESSION_TTL_HOURS = 24;

/**
 * 查询 toolSessionId → sessionId 的缓存。
 * @return sessionId 字符串，缓存未命中返回 null
 */
public String getToolSessionMapping(String toolSessionId) {
    return stringRedisTemplate.opsForValue().get(TOOL_SESSION_PREFIX + toolSessionId);
}

/**
 * 写入 toolSessionId → sessionId 的缓存。
 */
public void setToolSessionMapping(String toolSessionId, String sessionId) {
    stringRedisTemplate.opsForValue().set(
            TOOL_SESSION_PREFIX + toolSessionId,
            sessionId,
            TOOL_SESSION_TTL_HOURS, TimeUnit.HOURS);
}
```

需要在文件顶部 import `java.util.concurrent.TimeUnit`（如果尚未导入）。

- [ ] **Step 2: 修改 GatewayMessageRouter.resolveSessionId 增加缓存查询**

在 `GatewayMessageRouter.java` 的 `resolveSessionId` 方法中，`toolSessionId != null` 分支内，在查 DB 之前增加 Redis 缓存查询：

将原有的：
```java
if (toolSessionId != null) {
    try {
        SkillSession session = sessionService.findByToolSessionId(toolSessionId);
```

改为：
```java
if (toolSessionId != null) {
    // 先查 Redis 缓存
    String cachedSessionId = redisMessageBroker.getToolSessionMapping(toolSessionId);
    if (cachedSessionId != null) {
        RouteResponseSender sender = routeResponseSender;
        if (sender != null) {
            sender.sendRouteConfirm(toolSessionId, cachedSessionId);
        }
        return cachedSessionId;
    }
    try {
        SkillSession session = sessionService.findByToolSessionId(toolSessionId);
```

在 DB 查询成功后、return 之前写入缓存：
```java
if (session != null) {
    redisMessageBroker.setToolSessionMapping(toolSessionId, session.getId().toString());
    RouteResponseSender sender = routeResponseSender;
    if (sender != null) {
        sender.sendRouteConfirm(toolSessionId, session.getId().toString());
    }
    return session.getId().toString();
}
```

确认 `redisMessageBroker` 已注入到 `GatewayMessageRouter`。如果尚未注入，在类的构造函数参数中添加 `RedisMessageBroker redisMessageBroker`。

- [ ] **Step 3: 验证**

启动服务，发送一条消息触发 resolveSessionId。检查：
1. 第一次：Redis MISS → 查 DB → 写 Redis
2. 第二次（同一 toolSessionId）：Redis HIT → 不查 DB

Run: `redis-cli GET "ss:tool-session:{testToolSessionId}"`
Expected: 返回对应的 sessionId

- [ ] **Step 4: Commit**

```bash
git add skill-server/src/main/java/com/opencode/cui/skill/service/RedisMessageBroker.java
git add skill-server/src/main/java/com/opencode/cui/skill/service/GatewayMessageRouter.java
git commit -m "perf(db): cache toolSessionId->sessionId mapping in Redis"
```

---

### Task 3: 去掉流式过程中的 syncMessageContent

**Files:**
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/service/MessagePersistenceService.java`

- [ ] **Step 1: 从 persistTextPart 中移除 syncMessageContent 调用**

在 `MessagePersistenceService.java` 的 `persistTextPart` 方法（约第 134-160 行）中，找到：

```java
if ("text".equals(partType)) {
    syncMessageContent(active);
}
```

删除这个 if 块。

- [ ] **Step 2: 在 handleSessionStatus 中增加批量 syncMessageContent**

在 `MessagePersistenceService.java` 的 `handleSessionStatus` 方法（约第 363-368 行）中，在 `tracker.removeAndFinalize(sessionId)` 之前增加批量同步：

将原有的：
```java
private void handleSessionStatus(Long sessionId, StreamMessage msg) {
    if (!"idle".equals(msg.getSessionStatus()) && !"completed".equals(msg.getSessionStatus())) {
        return;
    }
    tracker.removeAndFinalize(sessionId);
}
```

改为：
```java
private void handleSessionStatus(Long sessionId, StreamMessage msg) {
    if (!"idle".equals(msg.getSessionStatus()) && !"completed".equals(msg.getSessionStatus())) {
        return;
    }
    syncAllPendingContent(sessionId);
    tracker.removeAndFinalize(sessionId);
}
```

- [ ] **Step 3: 新增 syncAllPendingContent 方法**

在 `MessagePersistenceService.java` 中新增方法：

```java
/**
 * session idle/completed 时，批量同步该 session 下所有未同步的消息 content。
 * 从所有 text part 拼接内容写回 skill_message.content（向后兼容）。
 */
private void syncAllPendingContent(Long sessionId) {
    ActiveMessageTracker.ActiveMessageRef active = tracker.getActiveMessage(sessionId);
    if (active != null) {
        syncMessageContent(active);
    }
}
```

- [ ] **Step 4: 在 ActiveMessageTracker 中新增 getActiveMessage 方法**

在 `ActiveMessageTracker.java` 中新增：

```java
/**
 * 获取当前活跃消息引用（不创建、不修改状态）。
 * @return 活跃消息引用，不存在返回 null
 */
public ActiveMessageRef getActiveMessage(Long sessionId) {
    return activeMessages.get(sessionId);
}
```

- [ ] **Step 5: 验证**

启动服务，发送一条 AI 回复（包含多个 text part）。检查：
1. 流式过程中 `skill_message.content` 保持为空或为初始值
2. session idle 后 `skill_message.content` 被正确填充为所有 text part 的拼接内容

Run: `mysql -e "SELECT id, content FROM skill_message WHERE session_id = {testSessionId} ORDER BY seq DESC LIMIT 1"`

- [ ] **Step 6: Commit**

```bash
git add skill-server/src/main/java/com/opencode/cui/skill/service/MessagePersistenceService.java
git add skill-server/src/main/java/com/opencode/cui/skill/service/ActiveMessageTracker.java
git commit -m "perf(db): defer syncMessageContent to session idle"
```

---

### Task 4: 序号分配改内存自增

**Files:**
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/service/ActiveMessageTracker.java`
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/service/MessagePersistenceService.java`
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/service/SkillMessageService.java`

- [ ] **Step 1: 在 ActiveMessageTracker 中新增 seq 计数器**

在 `ActiveMessageTracker.java` 中新增两个 ConcurrentHashMap 和对应方法：

```java
// 在类的字段声明区新增：
private final ConcurrentHashMap<Long, AtomicInteger> sessionSeqCounters = new ConcurrentHashMap<>();
private final ConcurrentHashMap<Long, AtomicInteger> messageSeqCounters = new ConcurrentHashMap<>();

/**
 * 获取 session 级别的下一个消息序号。
 * 首次调用时从 DB 加载当前 MAX 值，后续内存递增。
 * @param sessionId session ID
 * @param currentMaxFromDb 首次加载时的 DB MAX(seq) 值
 * @return 下一个 seq
 */
public int nextMessageSeq(Long sessionId, IntSupplier currentMaxFromDb) {
    AtomicInteger counter = sessionSeqCounters.computeIfAbsent(sessionId,
            k -> new AtomicInteger(currentMaxFromDb.getAsInt()));
    return counter.incrementAndGet();
}

/**
 * 获取 message 级别的下一个片段序号。
 * 首次调用时从 DB 加载当前 MAX 值，后续内存递增。
 * @param messageDbId message DB ID
 * @param currentMaxFromDb 首次加载时的 DB MAX(seq) 值
 * @return 下一个 seq
 */
public int nextPartSeq(Long messageDbId, IntSupplier currentMaxFromDb) {
    AtomicInteger counter = messageSeqCounters.computeIfAbsent(messageDbId,
            k -> new AtomicInteger(currentMaxFromDb.getAsInt()));
    return counter.incrementAndGet();
}
```

需要在文件顶部导入 `java.util.concurrent.atomic.AtomicInteger` 和 `java.util.function.IntSupplier`。

- [ ] **Step 2: 在 clearSession 和 removeAndFinalize 中清理计数器**

在 `ActiveMessageTracker.java` 的 `clearSession` 方法中追加：

```java
public void clearSession(Long sessionId) {
    activeMessages.remove(sessionId);
    sessionSeqCounters.remove(sessionId);
    // messageSeqCounters 的 key 是 messageDbId 而非 sessionId，
    // 通过 ActiveMessageRef.dbId() 清理
}
```

在 `removeAndFinalize` 方法中，在 `activeMessages.remove(sessionId)` 之后追加：

```java
sessionSeqCounters.remove(sessionId);
if (ref != null) {
    messageSeqCounters.remove(ref.dbId());
}
```

- [ ] **Step 3: 修改 SkillMessageService.saveMessage 使用内存计数器**

在 `SkillMessageService.java` 的 `saveMessage` 方法中，将：

```java
int nextSeq = messageRepository.findMaxSeqBySessionId(cmd.sessionId()) + 1;
```

改为：

```java
int nextSeq = activeMessageTracker.nextMessageSeq(cmd.sessionId(),
        () -> messageRepository.findMaxSeqBySessionId(cmd.sessionId()));
```

确认 `activeMessageTracker` 已注入到 `SkillMessageService`。如果尚未注入，在构造函数参数中添加 `ActiveMessageTracker activeMessageTracker`。

- [ ] **Step 4: 修改 MessagePersistenceService.resolvePartSeq 使用内存计数器**

在 `MessagePersistenceService.java` 的 `resolvePartSeq` 方法中，将最后的 fallback：

```java
return partRepository.findMaxSeqByMessageId(messageDbId) + 1;
```

改为：

```java
return tracker.nextPartSeq(messageDbId,
        () -> partRepository.findMaxSeqByMessageId(messageDbId));
```

- [ ] **Step 5: 验证**

启动服务，发送多轮 AI 回复。检查：
1. 消息 seq 和 part seq 单调递增，无重复
2. DB 日志中 `findMaxSeqBySessionId` 和 `findMaxSeqByMessageId` 仅在首次出现

- [ ] **Step 6: Commit**

```bash
git add skill-server/src/main/java/com/opencode/cui/skill/service/ActiveMessageTracker.java
git add skill-server/src/main/java/com/opencode/cui/skill/service/SkillMessageService.java
git add skill-server/src/main/java/com/opencode/cui/skill/service/MessagePersistenceService.java
git commit -m "perf(db): use in-memory seq counters instead of MAX(seq) queries"
```

---

### Task 5: updateLastActiveAt 延迟到 session idle

**Files:**
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/service/SkillMessageService.java`
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/service/MessagePersistenceService.java`

- [ ] **Step 1: 从 saveMessage 中移除 touchSession 调用**

在 `SkillMessageService.java` 的 `saveMessage` 方法（约第 67-92 行）中，找到并删除这一行：

```java
sessionService.touchSession(cmd.sessionId());
```

注意：`saveMessage` 在保存用户消息和 AI 消息创建时都会被调用。用户消息的 `touchSession` 由调用方（SkillMessageController/ImInboundController）负责，不受此变更影响。AI 消息侧，session 活跃时间改由 session idle 时更新。

- [ ] **Step 2: 在 handleSessionStatus 中增加 touchSession**

在 `MessagePersistenceService.java` 的 `handleSessionStatus` 方法中，在 `syncAllPendingContent` 之后增加：

```java
private void handleSessionStatus(Long sessionId, StreamMessage msg) {
    if (!"idle".equals(msg.getSessionStatus()) && !"completed".equals(msg.getSessionStatus())) {
        return;
    }
    syncAllPendingContent(sessionId);
    sessionService.touchSession(sessionId);
    tracker.removeAndFinalize(sessionId);
}
```

确认 `sessionService` 已注入到 `MessagePersistenceService`。如果尚未注入，在构造函数参数中添加 `SkillSessionService sessionService`。

- [ ] **Step 3: 验证**

启动服务，发送 AI 回复。检查：
1. 流式过程中 `skill_session.last_active_at` 不变
2. session idle 后 `last_active_at` 被更新

- [ ] **Step 4: Commit**

```bash
git add skill-server/src/main/java/com/opencode/cui/skill/service/SkillMessageService.java
git add skill-server/src/main/java/com/opencode/cui/skill/service/MessagePersistenceService.java
git commit -m "perf(db): defer updateLastActiveAt to session idle"
```

---

## Phase 2：GW 侧断联修复

### Task 6: 新增 AsyncSessionSender

**Files:**
- Create: `ai-gateway/src/main/java/com/opencode/cui/gateway/ws/AsyncSessionSender.java`

- [ ] **Step 1: 创建 AsyncSessionSender 类**

```java
package com.opencode.cui.gateway.ws;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 异步 WebSocket 消息发送器。
 * 每个 WebSocket session 对应一个实例，通过内部单线程消费队列串行发送，
 * 替代 synchronized(session) 的同步发送模式。
 */
public class AsyncSessionSender {

    private static final Logger log = LoggerFactory.getLogger(AsyncSessionSender.class);
    private static final int DEFAULT_QUEUE_CAPACITY = 10000;

    private final WebSocketSession session;
    private final BlockingQueue<TextMessage> queue;
    private final Thread senderThread;
    private volatile boolean running = true;

    public AsyncSessionSender(WebSocketSession session) {
        this(session, DEFAULT_QUEUE_CAPACITY);
    }

    public AsyncSessionSender(WebSocketSession session, int queueCapacity) {
        this.session = session;
        this.queue = new LinkedBlockingQueue<>(queueCapacity);
        this.senderThread = new Thread(this::sendLoop,
                "ws-sender-" + session.getId());
        this.senderThread.setDaemon(true);
        this.senderThread.start();
    }

    /**
     * 非阻塞地将消息放入发送队列。
     * @return true 如果成功入队，false 如果队列满
     */
    public boolean enqueue(TextMessage message) {
        boolean offered = queue.offer(message);
        if (!offered) {
            log.warn("[AsyncSender] Queue full, dropping message: linkId={}, queueSize={}",
                    session.getId(), queue.size());
        }
        return offered;
    }

    /**
     * 停止发送线程，等待最多 5 秒排空剩余消息。
     */
    public void shutdown() {
        running = false;
        senderThread.interrupt();
        try {
            senderThread.join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 获取当前队列中待发送的消息数。
     */
    public int pendingCount() {
        return queue.size();
    }

    private void sendLoop() {
        while (running) {
            try {
                TextMessage msg = queue.poll(1, TimeUnit.SECONDS);
                if (msg == null) {
                    continue;
                }
                session.sendMessage(msg);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (IOException e) {
                log.error("[AsyncSender] Send failed: linkId={}, remaining={}",
                        session.getId(), queue.size(), e);
                // 发送失败说明连接有问题，停止发送，由上层处理重连
                break;
            }
        }
        log.info("[AsyncSender] Sender thread stopped: linkId={}, droppedMessages={}",
                session.getId(), queue.size());
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add ai-gateway/src/main/java/com/opencode/cui/gateway/ws/AsyncSessionSender.java
git commit -m "feat(gw): add AsyncSessionSender for non-blocking WebSocket send"
```

---

### Task 7: 重构 SkillRelayService 使用 AsyncSessionSender

**Files:**
- Modify: `ai-gateway/src/main/java/com/opencode/cui/gateway/service/SkillRelayService.java`

- [ ] **Step 1: 新增 sender 管理 Map 和辅助方法**

在 `SkillRelayService.java` 的类字段声明区新增：

```java
import com.opencode.cui.gateway.ws.AsyncSessionSender;
import java.util.concurrent.ConcurrentHashMap;

// 类字段声明区：
private final ConcurrentHashMap<String, AsyncSessionSender> sessionSenders = new ConcurrentHashMap<>();
```

新增辅助方法：

```java
/**
 * 获取或创建 session 对应的异步发送器。
 */
private AsyncSessionSender getOrCreateSender(WebSocketSession session) {
    return sessionSenders.computeIfAbsent(session.getId(),
            k -> new AsyncSessionSender(session));
}

/**
 * 移除并关闭 session 对应的异步发送器。在连接关闭时调用。
 */
public void removeSessionSender(String sessionId) {
    AsyncSessionSender sender = sessionSenders.remove(sessionId);
    if (sender != null) {
        sender.shutdown();
    }
}
```

- [ ] **Step 2: 重构 sendToSession 方法**

将原有的 `sendToSession`（第 967-980 行）：

```java
private boolean sendToSession(WebSocketSession session, GatewayMessage message) {
    try {
        String json = objectMapper.writeValueAsString(message);
        synchronized (session) {
            session.sendMessage(new TextMessage(json));
        }
        log.info("[EXIT->SS] Sent to skill session: linkId={}, type={}", session.getId(), message.getType());
        return true;
    } catch (IOException e) {
        log.error("[EXIT->SS] Failed to send to skill session: linkId={}, type={}",
                session.getId(), message.getType(), e);
        return false;
    }
}
```

改为：

```java
private boolean sendToSession(WebSocketSession session, GatewayMessage message) {
    try {
        String json = objectMapper.writeValueAsString(message);
        AsyncSessionSender sender = getOrCreateSender(session);
        boolean enqueued = sender.enqueue(new TextMessage(json));
        if (enqueued) {
            log.info("[EXIT->SS] Enqueued to skill session: linkId={}, type={}, pending={}",
                    session.getId(), message.getType(), sender.pendingCount());
        }
        return enqueued;
    } catch (IOException e) {
        log.error("[EXIT->SS] Failed to serialize message: linkId={}, type={}",
                session.getId(), message.getType(), e);
        return false;
    }
}
```

- [ ] **Step 3: 重构 sendProtocolError 方法**

将原有的 `sendProtocolError`（第 992-1001 行）中的 `synchronized(session)` 替换：

```java
private void sendProtocolError(WebSocketSession session, String reason) {
    try {
        String json = objectMapper.writeValueAsString(GatewayMessage.registerRejected(reason));
        AsyncSessionSender sender = getOrCreateSender(session);
        sender.enqueue(new TextMessage(json));
    } catch (IOException e) {
        log.error("Failed to serialize protocol error: linkId={}, reason={}", session.getId(), reason, e);
    }
}
```

- [ ] **Step 4: 在连接关闭时清理 sender**

找到 `SkillWebSocketHandler.java` 中的 `afterConnectionClosed` 方法，在其中增加调用：

```java
skillRelayService.removeSessionSender(session.getId());
```

- [ ] **Step 5: Commit**

```bash
git add ai-gateway/src/main/java/com/opencode/cui/gateway/service/SkillRelayService.java
git add ai-gateway/src/main/java/com/opencode/cui/gateway/ws/SkillWebSocketHandler.java
git commit -m "perf(gw): replace synchronized send with async queue in SkillRelayService"
```

---

### Task 8: 重构 LegacySkillRelayStrategy 使用 AsyncSessionSender

**Files:**
- Modify: `ai-gateway/src/main/java/com/opencode/cui/gateway/service/LegacySkillRelayStrategy.java`

- [ ] **Step 1: 新增 sender 管理和辅助方法**

与 Task 7 相同模式，在 `LegacySkillRelayStrategy.java` 中新增：

```java
import com.opencode.cui.gateway.ws.AsyncSessionSender;
import java.util.concurrent.ConcurrentHashMap;

// 类字段声明区：
private final ConcurrentHashMap<String, AsyncSessionSender> sessionSenders = new ConcurrentHashMap<>();

private AsyncSessionSender getOrCreateSender(WebSocketSession session) {
    return sessionSenders.computeIfAbsent(session.getId(),
            k -> new AsyncSessionSender(session));
}

public void removeSessionSender(String sessionId) {
    AsyncSessionSender sender = sessionSenders.remove(sessionId);
    if (sender != null) {
        sender.shutdown();
    }
}
```

- [ ] **Step 2: 重构 sendToSession 方法**

将原有的 `sendToSession`（第 264-276 行）改为：

```java
private boolean sendToSession(WebSocketSession session, GatewayMessage message) {
    try {
        String json = objectMapper.writeValueAsString(message);
        AsyncSessionSender sender = getOrCreateSender(session);
        return sender.enqueue(new TextMessage(json));
    } catch (IOException e) {
        log.error("[Legacy] Failed to serialize message: linkId={}, type={}",
                session.getId(), message.getType(), e);
        return false;
    }
}
```

- [ ] **Step 3: 重构 sendProtocolError 方法**

将原有的 `sendProtocolError`（第 409-418 行）改为：

```java
private void sendProtocolError(WebSocketSession session, String reason) {
    try {
        String json = objectMapper.writeValueAsString(GatewayMessage.registerRejected(reason));
        AsyncSessionSender sender = getOrCreateSender(session);
        sender.enqueue(new TextMessage(json));
    } catch (IOException e) {
        log.error("[Legacy] Failed to serialize protocol error: linkId={}, reason={}", session.getId(), reason, e);
    }
}
```

- [ ] **Step 4: 在连接关闭时清理 sender**

在 `SkillWebSocketHandler.afterConnectionClosed` 中追加：

```java
legacySkillRelayStrategy.removeSessionSender(session.getId());
```

（`legacySkillRelayStrategy` 需确认已注入到 `SkillWebSocketHandler`。）

- [ ] **Step 5: Commit**

```bash
git add ai-gateway/src/main/java/com/opencode/cui/gateway/service/LegacySkillRelayStrategy.java
git add ai-gateway/src/main/java/com/opencode/cui/gateway/ws/SkillWebSocketHandler.java
git commit -m "perf(gw): replace synchronized send with async queue in LegacySkillRelayStrategy"
```

---

### Task 9: 重构 EventRelayService 中的 synchronized 发送

**Files:**
- Modify: `ai-gateway/src/main/java/com/opencode/cui/gateway/service/EventRelayService.java`

EventRelayService 有 3 处 `synchronized(session)` 调用，其中 2 处是发送到 Agent session（不影响 Skill 连接断联问题，但为统一模式一并改造），1 处是发送到 Source session。

- [ ] **Step 1: 新增 sender 管理**

在 `EventRelayService.java` 的类字段声明区新增：

```java
import com.opencode.cui.gateway.ws.AsyncSessionSender;

// 类字段声明区：
private final ConcurrentHashMap<String, AsyncSessionSender> sessionSenders = new ConcurrentHashMap<>();

private AsyncSessionSender getOrCreateSender(WebSocketSession session) {
    return sessionSenders.computeIfAbsent(session.getId(),
            k -> new AsyncSessionSender(session));
}

public void removeSessionSender(String sessionId) {
    AsyncSessionSender sender = sessionSenders.remove(sessionId);
    if (sender != null) {
        sender.shutdown();
    }
}
```

- [ ] **Step 2: 替换第 155-157 行（handleToSourceRelay）**

将：
```java
synchronized (session) {
    session.sendMessage(new TextMessage(payload));
}
```

改为：
```java
getOrCreateSender(session).enqueue(new TextMessage(payload));
```

- [ ] **Step 3: 替换第 272-274 行（sendToLocalAgentIfPresent）**

将：
```java
synchronized (session) {
    session.sendMessage(new TextMessage(json));
}
```

改为：
```java
getOrCreateSender(session).enqueue(new TextMessage(json));
```

- [ ] **Step 4: 替换第 295-297 行（sendToLocalAgent）**

将：
```java
synchronized (session) {
    session.sendMessage(new TextMessage(json));
}
```

改为：
```java
getOrCreateSender(session).enqueue(new TextMessage(json));
```

- [ ] **Step 5: 在连接关闭时清理 sender**

在 `removeAgentSession` 方法中追加 sender 清理。找到 `removeAgentSession` 方法，在 `agentSessions.remove(ak)` 之后增加：

```java
if (session != null) {
    removeSessionSender(session.getId());
}
```

- [ ] **Step 6: Commit**

```bash
git add ai-gateway/src/main/java/com/opencode/cui/gateway/service/EventRelayService.java
git commit -m "perf(gw): replace synchronized send with async queue in EventRelayService"
```

---

### Task 10: 增加 GW ↔ SS 连接数配置

**Files:**
- Modify: `ai-gateway/src/main/resources/application.yml`（如果 connection-count 在此配置）
- 或 Modify: `skill-server/src/main/resources/application.yml`（GatewayWSClient 在 SS 侧）

注意：`GatewayWSClient` 是 skill-server 侧的组件（SS 主动连 GW），连接数配置在 SS 的 application.yml 中。

- [ ] **Step 1: 修改 connection-count 配置**

在 `skill-server/src/main/resources/application.yml` 中找到 `gateway.websocket.connection-count`（或类似配置项），将值从 `3` 改为 `8`：

```yaml
gateway:
  websocket:
    connection-count: 8
```

如果配置项名称不同，搜索 `connection-count` 或 `pool-size` 确认准确的 key。

- [ ] **Step 2: 验证**

启动 SS，检查日志确认建立了 8 条到 GW 的 WebSocket 连接。

- [ ] **Step 3: Commit**

```bash
git add skill-server/src/main/resources/application.yml
git commit -m "perf(gw): increase GW-SS WebSocket connection count from 3 to 8"
```

---

## 验证清单

- [ ] **压测验证：DB CPU**
  - 相同并发条件下对比优化前后的 DB CPU 使用率
  - 预期：降低 70% 以上

- [ ] **压测验证：Agent 离线率**
  - 大量 Agent 同时在线场景下对比优化前后的 Agent 被判离线次数
  - 预期：降为 0

- [ ] **功能验证：历史记录完整性**
  - 发送多轮 AI 回复后，检查 `skill_message.content` 字段在 session idle 后是否正确填充
  - 检查前端历史记录页面显示是否正常

- [ ] **功能验证：消息有序性**
  - 异步发送队列是 per-session 单线程消费，消息有序性应该保持
  - 验证 Agent 收到的消息顺序与发送顺序一致
