# 编码约定

> `skill-server` 当前实现中的通用编码规范。只记录已经在代码中稳定存在的模式。

---

## 概览

当前代码库的几个核心约定：

- 统一使用**构造函数注入**，不使用字段注入。
- 异步任务使用**命名 `Executor` + 事务提交后触发**，当前没有使用 `@Async`。
- WebSocket 生命周期集中在 `SkillStreamHandler`，连接、回放、订阅、清理都在同一个 handler 完成。
- Redis pub/sub 统一走 `RedisMessageBroker`，不要直接在业务类里操作 `RedisMessageListenerContainer`。
- miniapp / IM / external 的统一出站，已经收敛到 `StreamMessageEmitter`。

---

## 依赖注入

**必须使用构造函数注入**。`SkillMessageService` 是当前最典型的样式：依赖都声明为 `final`，构造函数中一次性注入。

```java
@Service
public class SkillMessageService {

    private final SkillMessageRepository messageRepository;
    private final SkillMessagePartRepository partRepository;
    private final SkillSessionService sessionService;
    private final SnowflakeIdGenerator snowflakeIdGenerator;
    private final ObjectMapper objectMapper;
    private final MessageHistoryCacheService messageHistoryCacheService;
    private final Executor messageHistoryRefreshExecutor;

    public SkillMessageService(SkillMessageRepository messageRepository,
            SkillMessagePartRepository partRepository,
            SkillSessionService sessionService,
            SnowflakeIdGenerator snowflakeIdGenerator,
            ObjectMapper objectMapper,
            MessageHistoryCacheService messageHistoryCacheService,
            @Qualifier("messageHistoryRefreshExecutor") Executor messageHistoryRefreshExecutor) {
        ...
    }
}
```

来源：`skill-server/src/main/java/com/opencode/cui/skill/service/SkillMessageService.java:38-60`

禁止事项：

- 不要新增 `@Autowired` 字段注入。
- 不要把“懒得传”的依赖藏进静态工具类。

---

## 异步执行模式

项目当前**没有使用 `@Async`**；异步执行的真实模式是：

1. 在 `AsyncConfig` 中暴露命名 `Executor`
2. 在事务内注册 `afterCommit`
3. 提交后再把刷新任务扔到线程池

`AsyncConfig` 只负责声明线程池：

```java
@Configuration
public class AsyncConfig {

    @Bean(name = "messageHistoryRefreshExecutor")
    public Executor messageHistoryRefreshExecutor(
            @Value("${skill.message-history.refresh.core-pool-size:2}") int corePoolSize,
            @Value("${skill.message-history.refresh.max-pool-size:4}") int maxPoolSize,
            @Value("${skill.message-history.refresh.queue-capacity:200}") int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("history-refresh-");
        executor.initialize();
        return executor;
    }
}
```

来源：`skill-server/src/main/java/com/opencode/cui/skill/config/AsyncConfig.java:10-25`

调用侧在事务提交后再触发异步刷新：

```java
public void scheduleLatestHistoryRefreshAfterCommit(Long sessionId) {
    if (sessionId == null) {
        return;
    }
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                triggerAsyncLatestHistoryRefresh(sessionId);
            }
        });
        return;
    }
    triggerAsyncLatestHistoryRefresh(sessionId);
}
```

来源：`skill-server/src/main/java/com/opencode/cui/skill/service/SkillMessageService.java:201-215`

规则：

- 新增异步逻辑时，优先复用命名 `Executor`。
- 不要在事务未提交前刷新 cache、发外部请求、或读取“刚写入但尚未提交”的状态。
- 不要为了省事引入 `@Async`，除非整个模块统一迁移。

---

## 定时任务调度

`@Scheduled` 注解默认 `initialDelay=0`，**应用启动后会立刻首次执行**。如果调度任务依赖其他 bean 的 `@PostConstruct` 副作用（Redis 订阅、外部连接、缓存加载、心跳 listener 注册等），第一次执行时这些副作用可能还没完成，触发"伪故障 → 自愈"路径，重连风暴 / 误判失联 / 全员 takeover。

正确做法：用 `@EventListener(ApplicationReadyEvent.class)` 显式在所有 bean ready 后再 `TaskScheduler.scheduleWithFixedDelay`。

```java
public SkillInstanceRegistry(StringRedisTemplate redisTemplate,
        RedisMessageBroker redisMessageBroker,
        TaskScheduler taskScheduler,
        @Value("${HOSTNAME:skill-server-local}") String instanceId,
        @Value("${skill.instance-registry.refresh-interval-ms:10000}") long refreshIntervalMs) {
    // ... 构造注入
}

@PostConstruct
public void register() {
    writeHeartbeat();  // 写初始状态保护 startup grace 窗口
    log.info("[ENTRY] SkillInstanceRegistry.register: instanceId={}", instanceId);
}

@EventListener(ApplicationReadyEvent.class)
public void startScheduling(ApplicationReadyEvent event) {
    ScheduledFuture<?> future = taskScheduler.scheduleWithFixedDelay(
            this::refreshHeartbeat, Duration.ofMillis(refreshIntervalMs));
    heartbeatFutureRef.set(future);
    log.info("[ENTRY] SkillInstanceRegistry.startScheduling: instanceId={}, intervalMs={}",
            instanceId, refreshIntervalMs);
}

@PreDestroy
public void destroy() {
    ScheduledFuture<?> future = heartbeatFutureRef.get();
    if (future != null) future.cancel(false);
    redisTemplate.delete(redisKey());
}
```

来源：`skill-server/src/main/java/com/opencode/cui/skill/service/SkillInstanceRegistry.java:54-138`

规则：

- 新定时任务**优先**用 `ApplicationReadyEvent` + `TaskScheduler`；不用 `@Scheduled` 直接注解。
- 例外：任务**不依赖其他 bean 副作用**（纯无状态计算）可用 `@Scheduled`。
- `@PostConstruct` 写**初始状态**保护 startup grace 窗口，但**不**在 PostConstruct 注册周期任务。
- `@PreDestroy` 必须 `cancel(false)` 持有的 `ScheduledFuture`，避免 bean 销毁后仍触发任务。
- `@EnableScheduling` 在 `SkillConfig` 上保留（仍有其他 `@Scheduled`），`TaskScheduler` bean 由 Spring Boot autoconfigure 提供。

> **如何判断是否是 race**：不是看 `@PostConstruct` 和 `@Scheduled` 同居，而是看 schedule 任务**依不依赖** PostConstruct 的副作用（特别是异步副作用如 Redis 订阅）。
>
> 反例 — `ExternalStreamHandler.java:218-225` 表面看像同款：`subscribeRelayChannel`（订阅 Redis）+ `@Scheduled checkHeartbeatTimeouts`。但 `checkHeartbeatTimeouts` 只读 in-memory 字段（`ConnectionPool` 字段初始化、`lastActivity` `ConcurrentHashMap`），**完全不依赖** `subscribeRelayChannel` 的 Redis 副作用。最坏情况空 pool 循环，**不是 schedule-vs-postconstruct race**。
>
> 正例 — `SkillInstanceRegistry.refreshHeartbeat` 在引入 ApplicationReadyEvent 前会调用 `RedisMessageBroker.physicalSubscriberCount` 检查 Redis 端订阅数，而该订阅由**另一个 bean** `GatewayMessageRouter.initSsRelaySubscription` 通过 `RedisMessageListenerContainer.addMessageListener` 异步建立 — schedule 任务依赖跨 bean 的异步 Redis 副作用，**是 race**。

> **另一种独立的 race：subscribe vs container lifecycle**：`ExternalStreamHandler.subscribeRelayChannel` 本身（独立于上方 schedule 讨论）也曾经是 race 的另一种形态 —— 不是 schedule 依赖 PostConstruct 副作用，而是 PostConstruct 内部调用 `RedisMessageBroker.subscribe*()` 时 `RedisMessageListenerContainer`（SmartLifecycle）尚未 `start()`，SUBSCRIBE 不会真实发到 Redis。详见下一节"PostConstruct 不能注册 Redis listener"。两种 race 性质不同：上方 race 是任务**之间**的依赖，本 race 是 PostConstruct 与 Spring lifecycle 自身的依赖。

---

## PostConstruct 不能注册 Redis listener

`RedisMessageListenerContainer` 是 Spring `SmartLifecycle`（默认 `autoStartup=true`），会在**所有 `@PostConstruct` 方法都执行完毕之后**才 `start()`。在 `@PostConstruct` 阶段调 `RedisMessageBroker.subscribe()` / `subscribeToChannel()` / `subscribeToSsRelay()` 时，container 还没 running，`addMessageListener` 把 listener 加进 `channelMapping` 但**不会真实把 SUBSCRIBE 命令发到 Redis**（spring-data-redis 3.4.6 + Lettuce 6.4.2 实测）。

正确做法：监听 `ApplicationReadyEvent`，此时 container 已 `start()`，`addMessageListener` 会立即同步触发 SUBSCRIBE。

```java
// ❌ PostConstruct: container 还没 start, SUBSCRIBE 不会真实发到 Redis
@PostConstruct
void initSsRelaySubscription() {
    redisMessageBroker.subscribeToSsRelay(instanceId, this::handleMessage);
}

// ✅ ApplicationReadyEvent: container 已 start, addMessageListener 立即触发 SUBSCRIBE
@EventListener(ApplicationReadyEvent.class)
void initSsRelaySubscription(ApplicationReadyEvent event) {
    redisMessageBroker.subscribeToSsRelay(instanceId, this::handleMessage);
}
```

来源：`skill-server/src/main/java/com/opencode/cui/skill/service/GatewayMessageRouter.java:282-287`、`skill-server/src/main/java/com/opencode/cui/skill/ws/ExternalStreamHandler.java:196-202`

规则：

- 任何 `RedisMessageBroker.subscribe*()` 调用必须发生在 `ApplicationReadyEvent` 之后或运行期（如 WebSocket 连接到来时）。
- 不要为了"启动一致性"把 subscribe 塞进 `@PostConstruct`。
- 隐式时序约束：`SkillInstanceRegistry.startScheduling` 也用 `ApplicationReadyEvent`，多个 listener 之间默认无序，但 `firstRunAt = now + interval`（10s）已经给 SUBSCRIBE 留出 settle 窗口，因此**不需要** `@Order`。如果未来把首次延迟改回立即触发，需要重新评估 `@Order`。

> **历史踩坑**：本约定建立前，`ss:relay:{instanceId}` / `ss:external-relay:{instanceId}` 长期 PUBSUB NUMSUB = 0，`SkillInstanceRegistry` 自检每 10s 失败一次并触发 `forceReconnectListenerContainer` 风暴。多 pod 生产场景下跨实例 relay 完全失效。被 PR #20/#21 的 Lettuce decode bug 掩盖，修了那个之后才看清根因。

---

## WebSocket 会话生命周期

miniapp stream 的生命周期全部集中在 `SkillStreamHandler`：

```java
@Override
public void afterConnectionEstablished(WebSocketSession session) throws Exception {
    String userId = extractUserIdFromCookie(session);
    if (userId == null) {
        session.close(CloseStatus.BAD_DATA.withReason("Missing userId cookie"));
        return;
    }

    registerUserSubscriber(session, userId);
    sendInitialStreamingState(session, userId);
}

@Override
public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
    unregisterSubscriber(session);
    log.info("Skill stream subscriber disconnected: wsId={}, status={}", session.getId(), status);
}

@Override
public void handleTransportError(WebSocketSession session, Throwable exception) {
    ...
    unregisterSubscriber(session);
}
```

来源：`skill-server/src/main/java/com/opencode/cui/skill/ws/SkillStreamHandler.java:90-146`

客户端控制消息只支持 `resume` / `ping`：

```java
JsonNode node = objectMapper.readTree(message.getPayload());
String action = node.path("action").asText(null);
if (ACTION_RESUME.equals(action)) {
    String userId = (String) session.getAttributes().get(ATTR_USER_ID);
    if (userId != null) {
        sendInitialStreamingState(session, userId);
    }
    return;
}

if (ACTION_PING.equals(action)) {
    log.debug("Received stream heartbeat: wsId={}", session.getId());
    return;
}
```

来源：`skill-server/src/main/java/com/opencode/cui/skill/ws/SkillStreamHandler.java:103-126`

规则：

- 握手失败直接拒绝连接，不做隐式降级。
- 连接关闭和 transport error 都要走同一个 `unregisterSubscriber` 清理逻辑，保证幂等。
- 不要在其他类里复制“回放 snapshot + streaming state”逻辑；统一留在 handler 内部。

---

## Redis 订阅注册

Redis 订阅注册只通过 `RedisMessageBroker` 完成：

```java
private void subscribe(String channel, Consumer<String> handler) {
    unsubscribe(channel);

    MessageListener listener = (Message message, byte[] pattern) -> {
        try {
            String json = new String(message.getBody(), StandardCharsets.UTF_8);
            handler.accept(json);
            log.info("Received from Redis channel {}", channel);
        } catch (Exception e) {
            log.error("Failed to process message from channel {}: {}",
                    channel, e.getMessage(), e);
        }
    };

    activeListeners.put(channel, listener);
    listenerContainer.addMessageListener(listener, new ChannelTopic(channel));
    log.info("Subscribed to Redis channel: {}", channel);
}
```

来源：`skill-server/src/main/java/com/opencode/cui/skill/service/RedisMessageBroker.java:140-159`

业务层只表达“订阅哪个用户流”，不直接操作 listener container：

```java
private void subscribeToUserStream(String userId) {
    if (redisMessageBroker.isUserSubscribed(userId)) {
        return;
    }
    redisMessageBroker.subscribeToUser(userId, message -> handleUserBroadcast(userId, message));
    log.info("Subscribed to user stream: userId={}", userId);
}
```

来源：`skill-server/src/main/java/com/opencode/cui/skill/ws/SkillStreamHandler.java:212-219`

规则：

- 不要绕过 `RedisMessageBroker` 直接注册 `MessageListener`。
- 订阅前先做幂等检查，避免重复监听。
- handler 必须自己兜底异常，不能让 Redis listener 线程抛出未捕获错误。

---

## MyBatis 调用风格

service 层直接调用 `Repository` 接口；参数命名与 XML 保持一致，不做“二次包装 Mapper”。

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

对应的 Repository 接口：

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
配套 XML：`skill-server/src/main/resources/mapper/SkillMessageMapper.xml:7-110`

规则：

- 新增查询优先扩展现有 Mapper 接口 + XML，不要引入第二套 ORM。
- 参数一旦超过一个，必须在 Repository 上加 `@Param`。
- 事务边界在 service，不在 Mapper。

---

## 统一出站入口

`StreamMessageEmitter` 是当前权威出站入口：负责 enrich、用户解析、buffer、Redis user-stream 发布。

```java
private void enrich(String sessionId, StreamMessage msg) {
    if (msg == null || sessionId == null) return;

    msg.setSessionId(sessionId);
    msg.setWelinkSessionId(sessionId);

    if (!EMITTED_AT_EXCLUDED_TYPES.contains(msg.getType())
            && (msg.getEmittedAt() == null || msg.getEmittedAt().isBlank())) {
        msg.setEmittedAt(Instant.now().toString());
    }

    if (!"user".equals(ProtocolUtils.normalizeRole(msg.getRole()))) {
        Long numericId = ProtocolUtils.parseSessionId(sessionId);
        if (numericId != null) {
            persistenceService.prepareMessageContext(numericId, msg);
        }
    }
}
```

来源：`skill-server/src/main/java/com/opencode/cui/skill/service/delivery/StreamMessageEmitter.java:64-81`

旧入口已经在 `GatewayMessageRouter` 中退化为兼容包装：

```java
@Deprecated
public void broadcastStreamMessage(String sessionId, String userIdHint, StreamMessage msg) {
    emitter.emitToClient(sessionId, userIdHint, msg);
}

@Deprecated
public void publishProtocolMessage(String sessionId, StreamMessage msg) {
    emitter.emitToClientWithBuffer(sessionId, msg);
}
```

来源：`skill-server/src/main/java/com/opencode/cui/skill/service/GatewayMessageRouter.java:855-870`

规则：

- 新代码不要再直接往 Redis user channel 发 `StreamMessage`。
- 不要手动补 `welinkSessionId` / `emittedAt` / message context；让 emitter 做 canonical enrich。
- 如果只是给 miniapp 前端推协议消息，优先用 `emitToClient` / `emitToClientWithBuffer`。

---

## 入站恢复路径按 scope 分流

当 `processChat` / `processRebuild` 需要处理 "session 存在但 `toolSessionId` 缺失" 或主动重生 toolSessionId 时，必须按 `assistantScope` 分流，不能一条路径打到底。

```java
AssistantScopeStrategy strategy = scopeDispatcher.getStrategy(
        assistantInfoService.getCachedScope(ak));
String generated = strategy.generateToolSessionId();
if (generated != null) {
    // business：本地补齐 / 重生 cloud-* ID，不走 create_session
    ...
} else {
    // personal / scope 识别降级：走 sessionManager.requestToolSession → create_session 回调链路
    sessionManager.requestToolSession(session, prompt);
}
```

- `BusinessScopeStrategy.generateToolSessionId()` 返回非 null（`cloud-` + UUID），代表该助手不依赖 `session_created` 回调。business 路径应**本地持久化** toolSessionId + 直接走正常 chat 投递，不要转发 `create_session` 给 Gateway（云端链路没有对应 handler）。
- `PersonalScopeStrategy.generateToolSessionId()` 返回 null，代表 toolSessionId 必须由 Agent 回调绑定。personal 路径保持 `sessionManager.requestToolSession(...)` / `createSessionAsync` 的现行设计。
- `AssistantInfoService.getCachedScope(...)` 在上游故障时**降级返回 `"personal"`**，所以 scope 分流天然具备 "不确定时落到 personal 兜底" 的安全性。
- business 的本地补/重生必须加 `skill:im-session:heal:{welinkSessionId}` 分布式锁（见 `database-guidelines.md`），防止两个实例同时生成不同 `cloud-*` 覆盖 Redis mapping 导致上行路由丢失。锁 timeout 必须降级到 `requestToolSession` 保留消息，不能抛异常让消息丢失。

### pending 列表只服务 personal rebuild 链路

`SessionRebuildService` 的 `ss:pending-rebuild:{welinkSessionId}` 列表消费者只有 `GatewayMessageRouter.handleSessionCreated` → `retryPendingMessages`，**仅 personal 链路能走到**。business 助手没有 consumer，因此：

- business 路径在 `dispatchChatToGateway` 中**必须不** `appendPendingMessage`（否则积压无人消费，且会被 self-heal 分支的 `consumePendingMessages` 误当 legacy 重放，并发下产生"消息放大"）。
- self-heal 分支在补齐 toolSessionId 后需要 `consumePendingMessages` 把历史残留（可能来自 `handleSessionNotFound` / `handleContextOverflow`）取出重放，重放时对每条消息同样按 business 规则 **不** 再 append。
- personal 路径（case C）保持原有 `appendPendingMessage`，因为它的 rebuild 回调会消费。

来源：`InboundProcessingService.java` — `tryHealBusinessToolSessionId`、`dispatchChatToGateway(..., boolean appendToPending)`。

---

## 测试 mock 不能跨过抽象层

如果代码通过 `RedisCallback` / `Function<Connection, T>` 等回调包装一层 raw 操作，mock **必须深到 callback 内部依赖的接口**，**禁止**直接 mock `redisTemplate.execute(callback)` 让 callback 完全不运行。否则 callback 内的真实路径（Lettuce decode、cast 防御、timeout 行为）一行都没被覆盖。

错误模式 — callback 不运行，"生产爆炸 / 测试全绿"：

```java
// ❌ callback 被绕过，Lettuce decode / cast 路径完全未覆盖
when(redisTemplate.execute(any(RedisCallback.class))).thenReturn(expectedResult);
```

正确模式 — 直接拿 raw connection（推荐）或让 callback 真实运行，mock 深到 callback 内调到的接口：

```java
// 推荐：与生产代码 `RedisConnectionUtils.getConnection(factory)` 路径完全对齐
@Mock private RedisConnectionFactory connectionFactory;
@Mock private LettuceConnection lettuceConnection;
@Mock private RedisClusterAsyncCommands<byte[], byte[]> nativeAsync;
@Mock private RedisFuture<Map<byte[], Long>> pubsubFuture;

@BeforeEach
void setUp() {
    when(redisTemplate.getRequiredConnectionFactory()).thenReturn(connectionFactory);
    when(connectionFactory.getConnection()).thenReturn(lettuceConnection);
    when(lettuceConnection.getNativeConnection()).thenReturn(nativeAsync);
}

@Test
void physicalSubscriberCount_returnsValue() throws Exception {
    when(nativeAsync.pubsubNumsub(any(byte[].class))).thenReturn(pubsubFuture);
    when(pubsubFuture.get(2L, TimeUnit.SECONDS)).thenReturn(Map.of("ch".getBytes(), 3L));
    assertEquals(3L, broker.physicalSubscriberCount("ch"));
}
```

来源：`skill-server/src/test/java/com/opencode/cui/skill/service/RedisMessageBrokerTest.java`

规则：

- 任何使用 raw `connection.*` / native API cast 的代码，测试**必须** mock 到 callback 内部依赖的接口（不是 `RedisTemplate.execute` 这一层）。
- 测试质量自检：如果未来 cast 路径或 native API 签名变更，**测试是否会破？** 破了说明覆盖到位；一直绿说明 mock 跨过了抽象层。
- **方法论**：调查 Redis 客户端层 bug 时，**先用 raw 协议 / 独立 client（PowerShell + RESP、`redis-cli`）验证 server 端真实状态**，再去推测客户端实现。否则容易被 NUMSUB=0 这种"看起来 server 端有问题"的表象误导，做出方向不对的客户端修复。

> **历史踩坑**：`physicalSubscriberCount` 长期返回 0L，触发 `Self-check failed: own relay channel has 0 subscribers` + `Force reconnecting` 风暴。表象 1：单元测试三个 case 全绿（mock 直接 return `redisTemplate.execute(callback)` 的结果，callback 根本没运行）。表象 2：PR #20/#21/#22 轮番修了 Lettuce decode bug、`scheduleWithFixedDelay` 立即触发、`@PostConstruct` 阶段 listener race 三个真实存在的次要问题，但风暴依旧。**真根因**直到用 PowerShell + RESP 直连 Redis 6379 验证 `PUBSUB NUMSUB ss:relay:skill-server-local = :1` 才暴露：是下一节"RedisTemplate.execute 包 connection 成 proxy"导致 cast guard 永远 false。修复时一并把测试 mock 重做到 native API 层 + 切到 `connectionFactory.getConnection()` 路径。

---

## RedisTemplate.execute 包 connection 成 proxy

`RedisTemplate.execute(RedisCallback)` 默认 `exposeConnection=false`，传给 callback 的 **不是** raw `LettuceConnection`，而是 `CloseSuppressingInvocationHandler` 把 `RedisConnection` 包成的 JDK 动态代理（线上 stack 实测看到的 class 是 `jdk.proxy2.$Proxy134`）。代理的目的是防止 callback 内部误调 `connection.close()` 关闭共享连接（`LettuceConnectionFactory.shareNativeConnection=true` 时），但副作用是：**代理实现 `RedisConnection` interface，但不是 `LettuceConnection` 类的实例**。

后果：

```java
// ❌ 永远走早 return，因为 conn 是 jdk.proxy2.$ProxyN，不是 LettuceConnection 类
redisTemplate.execute((RedisCallback<Long>) conn -> {
    if (!(conn instanceof LettuceConnection lettuce)) return 0L;  // 永远 true → 0L
    ...
});
```

正确做法 — 直接拿 raw connection，绕开 RedisTemplate.execute 的 proxy 包装：

```java
RedisConnectionFactory factory = redisTemplate.getRequiredConnectionFactory();
RedisConnection conn = RedisConnectionUtils.getConnection(factory);
try {
    if (!(conn instanceof LettuceConnection lettuce)) return 0L;  // 这次能真实通过
    Object nativeConn = lettuce.getNativeConnection();
    if (!(nativeConn instanceof BaseRedisAsyncCommands<?, ?> base)) return 0L;
    @SuppressWarnings("unchecked")
    BaseRedisAsyncCommands<byte[], byte[]> async =
            (BaseRedisAsyncCommands<byte[], byte[]>) base;
    Map<byte[], Long> result = async.pubsubNumsub(channel.getBytes(UTF_8))
            .get(2, TimeUnit.SECONDS);
    return result.values().stream().findFirst().orElse(0L);
} catch (Exception e) {
    return 0L;
} finally {
    RedisConnectionUtils.releaseConnection(conn, factory);
}
```

参考实现：`skill-server/src/main/java/com/opencode/cui/skill/service/RedisMessageBroker.java::physicalSubscriberCount`

规则：

- 需要把 raw connection cast 成具体子类（`LettuceConnection`、`JedisConnection` 等）调 native API 时，**禁止**使用 `redisTemplate.execute(callback)`，必须走 `getRequiredConnectionFactory().getConnection()` + `RedisConnectionUtils.releaseConnection`。
- `RedisConnectionUtils` 是 Spring 推荐的 lifecycle 工具，正确处理 share / dedicated 连接的 close 语义；不要自己 `try-with-resources`，那会误关共享连接。
- 备选方案 `redisTemplate.execute(callback, true /* exposeConnection */)` 不被采用：它依赖 RedisTemplate 内部约定（"true 时不包 proxy"），未来 Spring 升级时风险更大；项目 spring-data-redis 固定 3.4.6，但仍优先选不依赖 internal 行为的 Approach B。
- 测试 mock 路径必须与生产代码对齐：mock `redisTemplate.getRequiredConnectionFactory()` → mock `factory.getConnection()` 返回 mock `LettuceConnection`，**不要** mock `redisTemplate.execute(callback)`（见上一节）。

> **历史踩坑**：本约定建立前，`RedisMessageBroker.physicalSubscriberCount` 用 `redisTemplate.execute((RedisCallback<Long>) conn -> ...)`，cast guard `if (!(conn instanceof LettuceConnection))` **永远 false** → 永远早 return 0L → `SkillInstanceRegistry` self-check 永远失败 → 每 10s 触发 `forceReconnectListenerContainer` 风暴。配套 unit test mock `redisTemplate.execute(callback)` 让 callback 不运行，三个 case 全绿掩盖了真根因。修复用 PowerShell + RESP 直连 Redis 验证 `PUBSUB NUMSUB = :1`（server 端订阅健康）后，才把焦点定位到客户端 cast guard。

---

## 运维可配置的"套餐" = SysConfig 数据 + Registry 运行时拼装

新增"多策略可自由组合"维度（例：业务 vendor × 出/入参协议）时，**不要**为每个组合写一个 `@Component`。让策略本身保持 `@Component`，再用 record / POJO 表达"套餐"，由 `@Service` Registry 运行时按两段 SysConfig 查找拼装：①`<feature>_profile:<dimensionKey>` → profile name；②`<feature>_profile_def:<profileName>` → JSON 列出选哪些策略 bean。`profile_def` 缺失时约定 fallback 为 "profile name == strategy bean name"。

Registry 必须 in-memory TTL cache（5min 量级），SysConfig 不要进热路径。跨服务（SS↔GW）只共享 profile name 字符串。参考：`CloudRequestProfileRegistry`、`BusinessScopeStrategy.buildInvoke` 调用点。

## 跨 vendor 的 toolSessionId 必须 Long-parseable

`toolSessionId` 是 SS 内部生成、可能被任意云端 vendor 当作业务 ID（如助手广场 `topicId: long`）使用。生成端（`*ScopeStrategy.generateToolSessionId`）**必须用 Snowflake**（`SnowflakeIdGenerator.nextId().toString()`），不要用 `"prefix-" + UUID`。否则下游 `Long.parseLong(toolSessionId)` 在新 vendor 接入时炸裂，且改不动（已下发的 ID 收不回）。

## 禁止事项

| 禁止 | 原因 | 正确做法 |
|------|------|---------|
| `@Autowired` 字段注入 | 隐藏依赖、测试成本高 | 构造函数注入 |
| 在 `@PostConstruct` 里 `taskScheduler.schedule*` 注册周期任务 | 启动顺序不确定，可能在依赖 bean 的 PostConstruct 之前跑 | 用 `@EventListener(ApplicationReadyEvent.class)` |
| 在 `@PostConstruct` 里调 `RedisMessageBroker.subscribe*()` | `RedisMessageListenerContainer` 是 SmartLifecycle，PostConstruct 阶段尚未 `start()`，SUBSCRIBE 不真实发到 Redis（NUMSUB=0） | 用 `@EventListener(ApplicationReadyEvent.class)` |
| `mock(redisTemplate.execute(any())).thenReturn(...)` 让 callback 不运行 | Lettuce decode / cast / timeout 路径全部没覆盖；生产爆炸测试全绿 | mock 深到 callback 内部依赖（`connectionFactory.getConnection()` → `LettuceConnection.getNativeConnection()` 等） |
| 在 `redisTemplate.execute((RedisCallback) conn -> ...)` 内 `instanceof LettuceConnection` cast | 默认 `exposeConnection=false`，conn 是 `CloseSuppressingInvocationHandler` JDK 代理，cast 永远 false → 早 return | 走 `redisTemplate.getRequiredConnectionFactory().getConnection()` + `RedisConnectionUtils.releaseConnection` |
| 直接在业务类里注册 Redis listener | 难以清理、难以幂等 | 统一走 `RedisMessageBroker` |
| 在事务内立即刷新历史 cache | 可能读到未提交状态 | `afterCommit` + 命名 `Executor` |
| 绕过 `StreamMessageEmitter` 手写前端推送 | 容易漏掉 enrich / buffer / context | 调用 emitter |
| 把 personal / business 分支散落到 Controller | scope 规则难以维护 | 使用 `AssistantScopeDispatcher` |
| business 路径调 `appendPendingMessage` / `requestToolSession` | 云端链路无 `session_created` 回调，消息积压或静默丢失 | 本地 `generateToolSessionId` + `updateToolSessionId`，加 `skill:im-session:heal:*` 锁 |
| 跨 scope 共用的 helper 不区分 append-pending | business 和 personal 对 pending 列表的消费语义不同，并发下造成消息放大或丢消息 | helper 必须显式接收 `appendToPending` 参数，由调用方按 scope 决策 |
