# 任务：评审"按会话分区的发送器"设计——功能正确性 + 性能影响

你是 GPT-5.5 高 reasoning。我（Claude）针对 opencode-CUI 流式事件乱序+重复问题，提出了一套**纯服务端**修复方案。
之前你已经独立验证过根因（详见 `.trellis/workspace/Llllviaaaa/codex-result-stream-ordering.md`），
我跟用户讨论修复方案时被打回两轮：
- 第一轮：提出"客户端 dedup + reorder buffer"被否（用户认为该服务端解决，因为 externalws 客户端是第三方）
- 第二轮：提出"Redis listener 砍到 1 + 每 WS 一线程"被否（性能灾难：全局串行 / 万连接 10GB 栈）

**当前方案：按会话分区（partition by sessionId-hash）的发送器**。

请你从**功能正确性**和**性能影响**两面评审。

---

## 项目上下文（关键代码）

- 仓库：`D:\02_Lab\Projects\sandbox\opencode-CUI`，已切到最新 main（commit `15ce232`，#30 修了 externalws L2）
- 双多实例：GW × N + SS × M，靠 Redis Pub/Sub + INCR + ZSET 协调
- miniapp WS 端点：`SkillStreamHandler` (`/ws/skill/stream`)；externalws WS 端点：`ExternalStreamHandler`
- Redis listener 当前配置：`RedisConfig.java:48-55`，`ThreadPoolTaskExecutor(core=5, max=50, queue=200)`
- producer 端贴 seq 已经在 externalws 跑通：`ExternalWsDeliveryStrategy.java:51`；miniapp 没贴，是在 consumer 端 `SkillStreamHandler.java:270` 才贴
- GW 那边给 PC Agent WS 用的单线程发送器范本：`ai-gateway/src/main/java/com/opencode/cui/gateway/ws/AsyncSessionSender.java`

---

## 提案：三处改动

### 改动 1 — producer 端贴 seq

- `MiniappDeliveryStrategy.deliver` (line 32-48)：在 `publishToUser` 之前调 `msg.setSeq(redisMessageBroker.nextStreamSeq(sessionId))`
- `SkillStreamHandler.pushStreamMessageToUser` (line 261-274)：删掉 `msg.setSeq(nextTransportSeq(sessionId))` 那行
- 保留 `pushStreamMessage(sessionId, msg)`（line 152）和 `sendSnapshot/sendStreamingState` 里的 nextTransportSeq —— 它们贴本地构造的快照消息，不走 Redis
- externalws 已经是这种模式，无需改

### 改动 2 — 按会话分区的发送器

新增组件 `SessionPartitionedSender`：
- 持有 K 条单线程执行器（默认 K=16，配置项 `skill.ws.session-partition-count`）
- 对外暴露 `enqueue(sessionId, wsSession, payload)`
- 路由：`int partition = Math.floorMod(sessionId.hashCode(), K)`，每个 sessionId 永远去同一条 partition
- 每条 partition 内部一个 `BlockingQueue<Task>` + 一个 worker 线程，按 enqueue 顺序处理

`SkillStreamHandler.pushStreamMessageToUser` 改写：
```java
// 旧：synchronized (ws) { ws.sendMessage(textMessage); }
// 新：partitionedSender.enqueue(sessionId, ws, textMessage);
```

externalws (`ExternalStreamHandler.pushToOne` / `pushToSource`) 同样改写。

### 改动 3 — per-session dedup（在 partition 内）

每个 partition worker 维护本地 `Map<sessionId, Long> lastSeqBySession`（Caffeine cache，maxSize=10w，TTL=1h）：
```java
Long lastSeq = lastSeqBySession.getIfPresent(sessionId);
if (lastSeq != null && msg.getSeq() != null && msg.getSeq() <= lastSeq) {
    return;   // 重复或乱序到达的旧消息，丢
}
lastSeqBySession.put(sessionId, msg.getSeq());
ws.sendMessage(serialized);
```

map 是 partition 线程内的 ThreadLocal-like 结构（同 sessionId 永远在同一个 partition 线程，**无需 ConcurrentHashMap，无需锁**）。

---

## 我自己的预估

| 维度 | 改动前 | 改动后 | 影响 |
|---|---|---|---|
| Redis listener 线程数 | 5~50 | 5~50（不变） | 无 |
| 业务线程数 | 0 | +K=16 | +16 个常驻线程，~16MB 栈 |
| 单条消息路径 | listener → 抢 ws 锁 → sendMessage | listener → 哈希 → 入队 → partition worker → sendMessage | +1 次哈希 + 1 次队列入出 |
| 同 session FIFO | ❌ 乱 | ✅ 严格 FIFO | 修好 |
| 跨 session 并发度 | 5~50（实际被锁争用拖慢） | 16 路 partition 均摊 | 高负载更稳定 |
| 万级并发用户 | WS 锁争用加剧 | 16 partition 均摊 | 改后更稳 |
| dedup map 内存 | 0 | Caffeine 10w × ~80 bytes ≈ 8MB | 极低 |

---

## 评审任务（请逐条给意见，带 file:line 证据）

### 功能正确性

1. **partition 路由的稳定性**：`Math.floorMod(sessionId.hashCode(), K)` 在 sessionId 是字符串时 hashCode 分布是否均匀？有没有数据倾斜风险（比如某些热门 sessionId 集中到同一 partition）？
2. **跨 SS 实例的同 session 路由**：用户在 SS-A 和 SS-B 都有 WS（多端登录），同一 sessionId 在 A 走 partition X，在 B 也是 partition X 吗？这不重要——但要确认两个 partition 之间的 seq 一致性（producer 端贴号已经保证）。
3. **没有 sessionId 的消息怎么办**：agent_online、agent_offline、status_query/response 等没有 sessionId。改动 2 的 `enqueue(sessionId, ws, payload)` 接口怎么处理 sessionId=null 的情况？
4. **dedup 与 session rebuild 的兼容**：`SessionRebuildService` / `updateToolSessionId` 会把同一逻辑会话重映射到新 sessionId，dedup map 是否需要主动清理旧 sessionId？会不会造成"旧 sessionId 残留 lastSeq → 新消息被错误丢弃"？
5. **dedup 与 snapshot/streaming-state 的兼容**：`SkillStreamHandler.sendSnapshot` 和 `sendStreamingState` 也会推消息，它们走 nextTransportSeq 本地贴号（保留），seq 跟 Redis INCR 是不同空间。如果客户端混看 snapshot 的 seq 和 live delta 的 seq，dedup 会不会把 snapshot 误丢？
6. **partition 线程内 Caffeine cache 的并发安全**：Caffeine 默认线程安全，但我说"map 是 partition 线程内的 ThreadLocal-like 结构"——实际是被 partition worker 单线程访问的共享对象，是不是该用普通 HashMap 反而更便宜？
7. **WS 关闭时的清理**：`SkillStreamHandler.unregisterSubscriber` (line 184-210) 和 `ExternalStreamHandler.afterConnectionClosed` 在关 WS 时是否需要通知 SessionPartitionedSender 清理对应队列里的任务？否则一条已死 WS 的写任务还在 partition 队列里被取出尝试 send，会抛 IOException。
8. **背压**：某个 partition 队列堆积时怎么办？默认 BlockingQueue 容量多少合适？满了 enqueue 应该 block / drop / unbounded？
9. **listener pool（5~50）→ partition pool（K=16）的 fan-out 关系**：如果 listener pool 同时把同 sessionId 的两条消息派给两个不同 worker，两个 worker enqueue 到同一 partition 时，谁先 enqueue 谁后 enqueue **本身**还是 race。要不要在 listener handler 入口先按 sessionId 做局部 sequencing？还是说"reorder buffer 应该在 partition 内"？

### 性能影响

10. **K=16 的合理性**：典型 SS 实例的 CPU 核数？在 100 / 1000 / 10000 并发用户场景下，16 partition 是否成瓶颈？K 应不应该跟 CPU 核数相关（比如 K=Runtime.availableProcessors() × 2）？
11. **SingleThreadExecutor × 16 的内存开销**：每条线程栈默认 1MB，16 线程 ≈ 16MB。能否调小（`-Xss256k`？）？
12. **BlockingQueue 选型**：LinkedBlockingQueue vs ArrayBlockingQueue 在高吞吐下的 GC 压力差异？
13. **入队 + 出队的额外延迟**：相比直接 `synchronized(ws).sendMessage()`，多一次队列操作的延迟在 P99 时是多少？（典型估算）
14. **跨 partition 的"长尾 session"**：某个 session 的 handler 慢了，本 partition 的其他 session 全跟着慢。K=16 时一条 partition 平摊 1/16 的 session，最坏情况一个慢 session 拖累 N/16 个其他 session。能否引入"超时迁移"或"per-session quota"？
15. **比较替代方案**：
    - 替代 A：改 `synchronized(ws)` 为 `ReentrantLock`，listener pool 内每个 worker 在写之前 `lock.tryLock()` + 循环等待——能否避免抢锁的 race？
    - 替代 B：用 Netty event loop（每个 ws 绑定到固定 event loop 线程）——性能上限更高但改造成本大
    - 替代 C：Java 21 virtual threads——每 ws 一个 virtual thread 几乎零开销，但需要项目 JDK 升级
    - 这三个替代和我提的 partition 方案各自的 trade-off？

### 我可能忽略的盲区

16. **GW 侧也有同样问题吗**：`AsyncSessionSender` 已经是单线程 per-ws，但 GW 侧的 `EventRelayService.handleGwRelayMessage` 也是 listener pool 多线程派发的，会不会出现"两个 worker 并发处理同一 ak 的消息然后并发 enqueue 到 ws sender"导致 enqueue 顺序错乱？
17. **producer 端 INCR + publish 的非原子性**：`MiniappDeliveryStrategy.deliver` 里 `nextStreamSeq` 和 `publishToUser` 不在同一锁内，并发 emit 同一 sessionId 时 INCR 顺序和 publish 顺序可能错位（T1 INCR=5、T2 INCR=6，T2 先 publish）。这种情况下 partition 拿到 seq=6 后又来 seq=5，dedup 会把 seq=5 直接丢。**这是不是数据丢失？**
18. **externalws 客户端的兼容**：第三方客户端如果完全不看 seq 字段，我们改的这些他们感知到啥？wire 上的内容是不是 100% 兼容？

---

## 输出格式要求

1. 简短结论：是否同意这套方案 / 有 fatal bug / 需要哪些修订
2. 按上面 18 个评审点逐条回应（每条 2-5 行；附 file:line 证据；不能空话）
3. 最关心的 3 个性能数字应该跑哪些压测来验证
4. 总字数控制在 2500 字以内

工作目录 `D:\02_Lab\Projects\sandbox\opencode-CUI`，只读，可以用 Read/Grep/Glob 验证代码细节。
