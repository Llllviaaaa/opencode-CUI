**总判断**

`sessionId-hash partition` 可以作为 WS 发送串行器，但原方案不是完整正确解法。致命点是“dedup-only”：如果 `seq=6` 先进入 worker，`seq=5` 后到会被 `<= lastSeq` 直接丢弃；必须加 reorder buffer，或把 `INCR+PUBLISH` 做成原子有序生产。

当前源码证据：miniapp 仍是 publish 前不赋 `seq`，见 `MiniappDeliveryStrategy.java:38-42`、`StreamMessageEmitter.java:117-123`；consumer 才赋 `seq`，见 `SkillStreamHandler.java:268-273`。Redis listener 是 `core=5/max=50/queue=200`，见 `RedisConfig.java:48-55`，`synchronized(ws)` 只保证互斥发送，不保证进入锁顺序，见 `SkillStreamHandler.java:375-381`。

**18 点结论**

1. `Math.floorMod(sessionId.hashCode(), K)` 本身没 fatal bug；Java `String.hashCode()` 跨 JVM 稳定。风险是热点 session/低熵 sessionId 倾斜，建议用 Murmur3/xxHash 并保留 K 可配置。

2. SS-A/SS-B 场景下，miniapp 同一用户 WS 在本机订阅 `user-stream:{userId}`，见 `SkillStreamHandler.java:213-218`。partition 只能保证本实例本 worker 内顺序；跨实例 producer 乱序仍要靠 `seq` reorder。

3. `agent_online/offline` 在 router 里是 `sessionId=null` 的非 session-affinity 消息，见 `GatewayMessageRouter.java:371-378`，但随后按活跃 session 逐个 `emitToClient`，见 `GatewayMessageRouter.java:797-803,814-816`。GW 的 `status_query` 是按 `ak/ws` 发，不适合用 `sessionId` 分区，见 `EventRelayService.java:319-321`。

4. rebuild/updateToolSessionId 不改变 welink `sessionId`，只改 toolSession 映射，见 `SkillSessionService.java:228-240`。所以 dedup key 用 welink sessionId 是对的，但 session close/新建必须自然换 key。

5. snapshot/streaming-state 是新连接后直接 `sendToSession`，且会取新的 Redis seq，见 `SkillStreamHandler.java:277-298`。如果把 snapshot 也纳入 dedup，不能让高 seq snapshot 直接吞掉低 seq live delta；需要 baseline 语义或 barrier。

6. 每 partition 一个 `HashMap` 可以，无需 `ConcurrentHashMap`。如果要 TTL/maxSize，用每 partition 一个 Caffeine cache 也行，但它不是 ThreadLocal，只是线程安全 cache。

7. 现有关闭逻辑会移除订阅者/连接池，见 `SkillStreamHandler.java:184-207`、`ExternalStreamHandler.java:115-124`。partition queue 仍可能残留已关闭 WS 任务，worker 必须二次 `isOpen`，并支持按 wsId tombstone/清队列降噪。

8. 队列必须 bounded。用 `offer`，满了不要无界堆积；miniapp 可关闭 WS 让前端重连 snapshot，externalws 应返回失败/重试/清 registry，并打 metrics。

9. listener pool 50 + partition 16 仍有 race：多个 listener 线程对同 session 的 `offer` 先后不等于 Redis 接收顺序。partition 只能保 enqueue FIFO，不能替代 reorder buffer。

10. K=16 可作为起点，但不是容量公式。IO 阻塞 send 场景建议 `min(max(16, cpu*2), 64)` 可配，并观察每 partition queue depth/lag。

11. 16 个平台线程的栈保留大约十几 MB 级，不会导致 10GB。真正的 10GB 风险来自 unbounded queue 或每条 payload 被长期引用。

12. 建议 `ArrayBlockingQueue` 或有容量的 `LinkedBlockingQueue`。前者内存稳定、GC 小；后者节点分配更多，但容量明确也可接受。

13. 当前同步 `sendMessage` 会把 listener 线程卡住，见 `SkillStreamHandler.java:375-381`、`ExternalStreamHandler.java:166-174`。partition worker 会降低 listener P99，但慢 WS 会拖慢同 partition，需要超时/关闭策略。

14. “一个慢 session 拖 1/16 session”是真的。若慢客户端很多，改成 per-WS sender 更隔离；GW 已有类似实现，见 `AsyncSessionSender.java:16-30,41-49,72-80`。

15. `ReentrantLock` 只替代 `synchronized`，不解决入锁顺序；Netty event loop 是更正统的按 channel 串行，但迁移成本大；virtual threads 降低阻塞成本，但不提供 FIFO/backpressure。partition sender 是中等成本折中，但必须配 reorder/限流。

16. GW 侧已经是 per WebSocket 单线程 sender，见 `EventRelayService.java:379-384` 和 `AsyncSessionSender.java:72-80`。如果要统一 worker，key 应是 `ak` 或 `wsId`，不是 `sessionId`。

17. `INCR` 和 `PUBLISH` 分离会产生 seq/publish 顺序反转，见 `RedisMessageBroker.java:413-416` 与 `65-67/144-147`。所以 producer-side seq 还不够，最好 Lua `INCR+PUBLISH` 原子化，或 worker/client reorder。

18. externalws 当前主路径会在序列化前赋 `seq`，见 `ExternalWsDeliveryStrategy.java:50-56`，L2 relay 也转发这个 payload，见 `ExternalStreamHandler.java:206-213`。但遗留 `stream:{source}` 订阅没有找到 publisher，见 `ExternalStreamHandler.java:92-95,265`；不要按它设计主链路。

**推荐方案 Top 3**

1. 最小可上线：miniapp/external producer 统一赋 `seq`，miniapp 前端加 per-session reorder+dedup buffer；服务端 partition sender 只负责避免并发 `sendMessage`。这是性价比最高的修复。

2. 中等增强：在 partition worker 内做 per-session reorder buffer，bounded queue 满则关闭 WS 触发 snapshot/resume。注意 snapshot 必须定义 baseline 规则，避免吞 live delta。

3. 根治方案：用 Redis Streams/XADD 或 Lua 原子 `INCR+PUBLISH` 建立生产顺序，再消费端按 seq 重排。不要把 Redis listener pool 改成 1 当长期方案，它会造成全 channel head-of-line blocking，且仍不修复分离 `INCR/PUBLISH` 的反转窗口。