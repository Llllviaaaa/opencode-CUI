# 性能优化设计文档

## 背景

压测发现两个问题：
1. **SS 侧数据库 CPU 高**：流式消息持久化过程中 DB 操作过多、过重
2. **Agent 被误判离线**：大量 Agent 同时在线时，GW 到 SS 的消息转发阻塞了心跳处理，导致心跳超时被 checkTimeouts 标记 OFFLINE

## 问题一：SS 侧数据库 CPU 高

### 根因分析

一次 AI 回复的流式过程中，只有终态事件（text.done、tool_result completed、step.done 等）触发 DB 操作，delta 事件不碰 DB。但每个终态事件到达时做了过多的 DB 操作：

| 操作 | 每个终态事件 | 是否必要 |
|------|-------------|----------|
| 按 toolSessionId 查会话 | 1 次 SELECT（无索引，全表扫描） | 要查，但应该缓存 |
| 查/创建消息记录 | 1-4 次 | 首次必要，后续已有内存缓存 |
| 查 MAX(seq) 分配序号 | 1-2 次 SELECT | 不必要，内存自增即可 |
| upsert 片段 | 1 次 INSERT ON DUPLICATE KEY UPDATE | 必要 |
| syncMessageContent（GROUP_CONCAT + MEDIUMTEXT UPDATE） | 2 次（仅 text part） | 不必要，content 是冗余字段 |
| updateLastActiveAt | 1 次 UPDATE | 不需要每条事件都更新 |

一次 AI 回复（1 段文本 + 3 次工具调用）约 28-32 次 DB 操作，其中真正必要的只有 upsert 本身。

### 解决方案

#### 1.1 给 tool_session_id 加索引

新增 migration：

```sql
CREATE INDEX idx_tool_session_id ON skill_session(tool_session_id);
```

全表扫描 → 索引查找。

#### 1.2 toolSessionId → sessionId 映射加 Redis 缓存

在 GatewayMessageRouter 中增加 Redis 缓存：

```
Key:    ss:tool-session:{toolSessionId}
Value:  sessionId
TTL:    24 小时
```

首次查 DB，写入 Redis 缓存；后续直接从 Redis 读取。同一个 session 的 toolSessionId 映射不会变。

使用 Redis 而非本地内存缓存的原因：多实例部署时，session ownership 可能发生转移，Redis 缓存可跨实例共享，避免每个实例都要回源查 DB。

#### 1.3 去掉流式过程中的 syncMessageContent

**现状**：每个 text part 持久化后执行 `GROUP_CONCAT` + `UPDATE message.content`。

**改为**：`persistTextPart` 中移除 `syncMessageContent()` 调用。在 `handleSessionStatus`（session idle/completed）时，对该 session 所有未同步的消息做一次 syncMessageContent。

**原因**：`skill_message.content` 是冗余字段。历史记录 API 已经同时返回了 parts 列表，前端可以从 parts 中获取完整文本。content 字段仅为向后兼容保留，不需要实时维护。

#### 1.4 序号分配改内存自增

**现状**：
- `findMaxSeqBySessionId`：每次创建新消息时查 DB
- `findMaxSeqByMessageId`：每次创建新片段时查 DB

**改为**：在 ActiveMessageTracker 中维护两级内存计数器：

- `sessionSeqCounters: ConcurrentHashMap<Long, AtomicInteger>`（session → 消息序号）
- `messageSeqCounters: ConcurrentHashMap<Long, AtomicInteger>`（message → 片段序号）

首次操作时从 DB 加载当前 MAX 值，后续内存递增。随 ActiveMessageTracker 的生命周期清理（session idle/completed 时移除）。

多实例安全性：当前架构中同一个 session 由 Redis session ownership 保证只在一个实例上处理，内存计数器不会跨实例冲突。

#### 1.5 updateLastActiveAt 延迟到 session idle

**现状**：每条终态事件到达后都执行 `UPDATE skill_session SET last_active_at = ? WHERE id = ?`。

**改为**：只在 session idle/completed 事件处理时更新一次。

**影响**：`cleanupIdleSessions` 定时任务依赖 `last_active_at` 判断空闲会话。延迟更新不影响其判断准确性——如果 session 还在活跃对话中，它不会被清理；对话结束时 session idle 事件会触发更新。

### 优化效果

以一次 AI 回复（1 段文本 + 3 次工具调用 + 1 个 step.done）为例，共 6 个终态事件：

| 操作 | 优化前 | 优化后 |
|------|--------|--------|
| 查会话 | 6 次全表扫描 | 1 次索引查找 + 5 次内存缓存命中 |
| MAX(seq) 查询 | ~10 次 SELECT | 0 次（内存自增） |
| syncMessageContent | 2 次 GROUP_CONCAT + MEDIUMTEXT UPDATE | 0 次（延迟到 session idle） |
| updateLastActiveAt | 6 次 UPDATE | 0 次（延迟到 session idle） |
| upsert part | 6 次 | 6 次（不变） |
| **合计 DB 操作** | **~30 次** | **~7 次** |

session idle 时追加：1 次 syncMessageContent + 1 次 updateLastActiveAt = 2 次。

**总计从 ~30 次降到 ~9 次，减少约 70%。其中消除了全部全表扫描和全部 MEDIUMTEXT 重操作。**

---

## 问题二：Agent 被误判离线

### 根因分析

Agent 每 30 秒发一次心跳，GW 收到后更新 `agent_connection.last_seen_at`。GW 每 30 秒扫描一次超时 Agent（last_seen_at 超过 90 秒），标记 OFFLINE。

大量 Agent 同时在线时，心跳处理被阻塞的链路：

1. Agent 发的 tool_event 等业务消息需要通过 GW 转发给 SS
2. 转发时调用 `SkillRelayService.sendToSession()`，使用 `synchronized(skillSession)` 加锁写入
3. GW 到 SS 只有 3 条 WebSocket 连接，所有 Agent 的消息共用这 3 条连接
4. 大量 Agent 并发时，线程排队等锁，占住 Tomcat 处理线程
5. 同一个 Agent 的 WebSocket 连接上，心跳和业务消息串行处理
6. 前一条业务消息的处理线程被锁阻塞 → 后续心跳消息无法被处理
7. 连续 90 秒心跳未更新 → checkTimeouts 标记 OFFLINE

### 解决方案

#### 2.1 消息转发改异步发送

**现状**：`handleTextMessage` 线程同步调用 `synchronized(skillSession) { session.sendMessage() }`，阻塞直到发送完成。

**改为**：为每个 Skill WebSocket session 创建一个异步发送队列：

```java
class AsyncSessionSender {
    private final BlockingQueue<TextMessage> queue;
    private final Thread senderThread;

    void enqueue(TextMessage message) {
        queue.offer(message);  // 非阻塞，立刻返回
    }

    // senderThread 消费循环：
    // while (running) {
    //     TextMessage msg = queue.take();
    //     session.sendMessage(msg);  // 串行发送，天然有序，不需要 synchronized
    // }
}
```

**效果**：`handleTextMessage` 线程投入队列后立刻返回，不阻塞。心跳消息可以正常被处理。

**背压策略**：队列设置上限（如 10000），超出时记录告警并丢弃最旧消息。

**错误处理**：发送失败时由 senderThread 记录日志，触发连接重建。不影响 handleTextMessage 线程。

#### 2.2 增加 GW ↔ SS 连接数

**现状**：`GatewayWSClient` 的 `connection-count` 默认 3。

**改为**：调整为 8-10（通过配置 `gateway.skill.connection-count`）。

**效果**：降低单连接消息密度，减少队列积压。

### 优化效果

| 指标 | 优化前 | 优化后 |
|------|--------|--------|
| 消息转发对心跳的阻塞 | tool_event 等锁期间心跳无法处理 | 转发投入队列立刻返回，心跳正常处理 |
| Tomcat 线程占用 | 大量线程阻塞在 synchronized 锁上 | 线程立刻释放 |
| 单连接消息密度 | 所有 Agent 共享 3 条连接 | 分散到 8-10 条连接 |

---

## 实施计划

### Phase 1：DB 优化（SS 侧）

| 步骤 | 改动 | 风险 |
|------|------|------|
| 1 | 新增 migration：`tool_session_id` 索引 | 低 |
| 2 | GatewayMessageRouter 增加 toolSessionId 缓存 | 低 |
| 3 | MessagePersistenceService 移除流式过程中的 syncMessageContent，改到 session idle 触发 | 低 |
| 4 | ActiveMessageTracker 增加内存 seq 计数器 | 低 |
| 5 | 移除流式过程中的 updateLastActiveAt，改到 session idle 触发 | 低 |

### Phase 2：断联修复（GW 侧）

| 步骤 | 改动 | 风险 |
|------|------|------|
| 1 | 新增 AsyncSessionSender，重构 SkillRelayService.sendToSession | 中（并发模型变更） |
| 2 | 调大 connection-count 配置 | 低 |

### 验证方式

- 压测对比：相同并发下 DB CPU 使用率
- 压测对比：Agent 离线率
- 功能验证：历史记录完整性（content 字段是否在 session idle 后正确填充）
