# 服务间路由解耦 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 消除 SS 与 GW 之间的路由耦合，实现服务间实例无感知的消息路由。

**Architecture:** GW 和 SS 之间通过一致性哈希选择 WebSocket 连接，各服务内部通过 Redis pub/sub 定向 channel 做实例间中转。GW 通过路由学习表自动判断上行消息的 sourceType。Legacy 兼容通过开关保留旧路由路径。

**Tech Stack:** Spring Boot 3.4.6 / Java 21, Redis 6.0 Cluster, MySQL, Caffeine Cache, WebSocket

**Design Spec:** `docs/superpowers/specs/2026-03-25-route-decoupling-v3-design.md`

---

## 阶段划分

| 阶段 | 内容 | 可独立部署 |
|------|------|-----------|
| Phase 1 | GW 侧路由重构 | 是（旧 SS 正常工作） |
| Phase 2 | SS 侧路由重构 | 是（需 Phase 1 的 HTTP 接口） |
| Phase 3 | 集成联调 + 滚动升级验证 | 需 Phase 1 + 2 |

---

## Phase 1: GW 侧路由重构

### Task 1.1: ConsistentHashRing 工具类

**Files:**
- Create: `ai-gateway/src/main/java/com/opencode/cui/gateway/service/ConsistentHashRing.java`
- Test: `ai-gateway/src/test/java/com/opencode/cui/gateway/service/ConsistentHashRingTest.java`

**说明:** 通用一致性哈希环实现，GW 和 SS 都需要。先在 GW 侧实现，SS 侧后续复制或抽取公共模块。

- [ ] **Step 1: 编写 ConsistentHashRing 测试**

```java
// ConsistentHashRingTest.java
@Test void addNode_shouldDistributeKeys()
@Test void removeNode_shouldOnlyAffectAdjacentKeys()
@Test void getNode_withEmptyRing_shouldReturnNull()
@Test void getNode_withSingleNode_shouldAlwaysReturnIt()
@Test void distribution_shouldBeReasonablyBalanced()  // 150 虚拟节点，偏差 < 30%
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd ai-gateway && ./mvnw test -pl . -Dtest=ConsistentHashRingTest -Dsurefire.failIfNoTests=false`

- [ ] **Step 3: 实现 ConsistentHashRing**

```java
public class ConsistentHashRing<T> {
    private final TreeMap<Long, T> ring = new TreeMap<>();
    private final Map<String, T> nodes = new ConcurrentHashMap<>();
    private final int virtualNodes;

    public ConsistentHashRing(int virtualNodes) // 默认 150
    public synchronized void addNode(String nodeKey, T node)
    public synchronized void removeNode(String nodeKey)
    public T getNode(String key)  // hash(key) → 顺时针找最近节点
    public int size()
    public boolean isEmpty()
}
```

- [ ] **Step 4: 运行测试确认通过**
- [ ] **Step 5: 提交**

```bash
git add ai-gateway/src/main/java/.../ConsistentHashRing.java ai-gateway/src/test/java/.../ConsistentHashRingTest.java
git commit -m "feat(gw): 实现 ConsistentHashRing 一致性哈希环"
```

---

### Task 1.2: GW 实例注册（内部 Redis + HTTP 接口）

**Files:**
- Modify: `ai-gateway/src/main/java/com/opencode/cui/gateway/service/GatewayInstanceRegistry.java`
- Create: `ai-gateway/src/main/java/com/opencode/cui/gateway/controller/InternalInstanceController.java`
- Modify: `ai-gateway/src/main/resources/application.yml`
- Test: `ai-gateway/src/test/java/com/opencode/cui/gateway/controller/InternalInstanceControllerTest.java`

**说明:** 重构 GatewayInstanceRegistry，从写共享 Redis 改为写 GW 内部 Redis（`gw:internal:instance:{id}`）。同时保留写旧 key（`gw:instance:{id}`）用于滚动升级兼容。新增 HTTP 接口 `GET /internal/instances`。

- [ ] **Step 1: 编写 HTTP 接口测试**

```java
@WebMvcTest(InternalInstanceController.class)
class InternalInstanceControllerTest {
    @Test void getInstances_shouldReturnRegisteredInstances()
    @Test void getInstances_withNoInstances_shouldReturnEmptyList()
}
```

- [ ] **Step 2: 运行测试确认失败**
- [ ] **Step 3: 实现 InternalInstanceController**

```java
@RestController
@RequestMapping("/internal")
public class InternalInstanceController {
    @GetMapping("/instances")
    public ResponseEntity<Map<String, Object>> getInstances()
    // 查 Redis gw:internal:instance:* 返回 instanceId + wsUrl 列表
}
```

- [ ] **Step 4: 修改 GatewayInstanceRegistry 双写逻辑**

```java
// register() 中同时写新旧 key：
// gw:internal:instance:{id} → wsUrl (新)
// gw:instance:{id} → wsUrl (旧，滚动升级兼容)
```

- [ ] **Step 5: 补充配置项**

```yaml
# application.yml 新增
gateway:
  internal-api:
    enabled: true
```

- [ ] **Step 6: 运行测试确认通过**
- [ ] **Step 7: 提交**

```bash
git commit -m "feat(gw): 实例注册双写 + HTTP 接口 /internal/instances"
```

---

### Task 1.3: Agent 连接注册表（新 Redis Key）

**Files:**
- Modify: `ai-gateway/src/main/java/com/opencode/cui/gateway/service/RedisMessageBroker.java`
- Modify: `ai-gateway/src/main/java/com/opencode/cui/gateway/ws/AgentWebSocketHandler.java`
- Test: `ai-gateway/src/test/java/com/opencode/cui/gateway/service/RedisMessageBrokerTest.java`

**说明:** Agent 注册时同时写 `gw:internal:agent:{ak}` （新）和 `conn:ak:{ak}`（旧）。新增方法 `getInternalAgentInstance(ak)` 查新 key。

- [ ] **Step 1: 在 RedisMessageBroker 新增方法并测试**

```java
// 新增方法
void bindInternalAgent(String ak, String instanceId, Duration ttl)
String getInternalAgentInstance(String ak)
void removeInternalAgent(String ak)
```

- [ ] **Step 2: 修改 AgentWebSocketHandler 注册/断连/心跳流程，双写新旧 key**
- [ ] **Step 3: 运行测试确认通过**
- [ ] **Step 4: 提交**

```bash
git commit -m "feat(gw): Agent 注册表双写 gw:internal:agent + conn:ak"
```

---

### Task 1.4: GW Relay pub/sub Channel

**Files:**
- Modify: `ai-gateway/src/main/java/com/opencode/cui/gateway/service/RedisMessageBroker.java`
- Modify: `ai-gateway/src/main/java/com/opencode/cui/gateway/service/EventRelayService.java`
- Create: `ai-gateway/src/main/java/com/opencode/cui/gateway/model/RelayMessage.java`
- Test: `ai-gateway/src/test/java/com/opencode/cui/gateway/service/GwRelayPubSubTest.java`

**说明:** 每个 GW 实例订阅 `gw:relay:{instanceId}` channel。收到 relay 消息后查本地 Agent 连接投递。

- [ ] **Step 1: 创建 RelayMessage 模型**

```java
public record RelayMessage(
    String type,           // "relay"
    String sourceType,     // "skill-server"
    List<String> routingKeys,  // ["ts-abc", "w:42"]
    String originalMessage // GatewayMessage JSON
) {}
```

- [ ] **Step 2: RedisMessageBroker 新增 relay 订阅/发布方法**

```java
void subscribeToRelay(String instanceId, Consumer<String> handler)
void publishToRelayChannel(String targetInstanceId, String message)
```

- [ ] **Step 3: EventRelayService 启动时订阅 gw:relay:{self}，收到消息后本地投递**
- [ ] **Step 4: 编写测试**
- [ ] **Step 5: 运行测试确认通过**
- [ ] **Step 6: 提交**

```bash
git commit -m "feat(gw): 实现 gw:relay:{instanceId} pub/sub 中转通道"
```

---

### Task 1.5: 上行路由学习表（RoutingTable）

**Files:**
- Create: `ai-gateway/src/main/java/com/opencode/cui/gateway/service/UpstreamRoutingTable.java`
- Test: `ai-gateway/src/test/java/com/opencode/cui/gateway/service/UpstreamRoutingTableTest.java`

**说明:** Caffeine 本地缓存，key 为 toolSessionId 或 `"w:" + welinkSessionId`，value 为 sourceType 字符串。

- [ ] **Step 1: 编写测试**

```java
@Test void learnRoute_fromCreateSession_shouldStoreWelinkSessionId()
@Test void learnRoute_fromChat_shouldStoreToolSessionId()
@Test void resolveSourceType_withToolSessionId_shouldReturnLearned()
@Test void resolveSourceType_withWelinkSessionId_shouldReturnLearned()
@Test void resolveSourceType_withUnknownId_shouldReturnNull()
@Test void learnFromRelay_shouldPropagateRouting()
@Test void entries_shouldExpireAfterAccess()
```

- [ ] **Step 2: 运行测试确认失败**
- [ ] **Step 3: 实现 UpstreamRoutingTable**

```java
@Component
public class UpstreamRoutingTable {
    private final Cache<String, String> routingTable;

    public void learnRoute(GatewayMessage message, String sourceType)
    public void learnFromRelay(List<String> routingKeys, String sourceType)
    public String resolveSourceType(GatewayMessage message)
    private String extractToolSessionIdFromPayload(GatewayMessage message)
}
```

- [ ] **Step 4: 运行测试确认通过**
- [ ] **Step 5: 添加配置**

```yaml
gateway:
  upstream-routing:
    cache-max-size: 100000
    cache-expire-minutes: 30
    broadcast-timeout-ms: 200
```

- [ ] **Step 6: 提交**

```bash
git commit -m "feat(gw): 实现上行路由学习表 UpstreamRoutingTable"
```

---

### Task 1.6: Pending 队列

**Files:**
- Modify: `ai-gateway/src/main/java/com/opencode/cui/gateway/service/RedisMessageBroker.java`
- Modify: `ai-gateway/src/main/java/com/opencode/cui/gateway/ws/AgentWebSocketHandler.java`
- Test: `ai-gateway/src/test/java/com/opencode/cui/gateway/service/PendingQueueTest.java`

**说明:** `gw:pending:{ak}` Redis List，TTL 60s。Agent 重连时 drain 并投递。

- [ ] **Step 1: RedisMessageBroker 新增 pending 队列方法**

```java
void enqueuePending(String ak, String message, Duration ttl)
List<String> drainPending(String ak)
```

- [ ] **Step 2: AgentWebSocketHandler 注册成功后调用 drainPending**
- [ ] **Step 3: 编写测试**
- [ ] **Step 4: 运行测试确认通过**
- [ ] **Step 5: 提交**

```bash
git commit -m "feat(gw): 实现 gw:pending:{ak} 下行消息缓冲队列"
```

---

### Task 1.7: 重构 SkillRelayService（核心）

**Files:**
- Modify: `ai-gateway/src/main/java/com/opencode/cui/gateway/service/SkillRelayService.java`
- Modify: `ai-gateway/src/main/java/com/opencode/cui/gateway/service/EventRelayService.java`
- Test: `ai-gateway/src/test/java/com/opencode/cui/gateway/service/SkillRelayServiceV2Test.java`

**说明:** 这是 GW 侧最核心的改动。将 `routeCache` 替换为 `UpstreamRoutingTable`，上行路由改为一致性哈希 + 广播 fallback，下行路由改为查 `gw:internal:agent:{ak}` + relay pub/sub。

- [ ] **Step 1: 编写新版上行路由测试**

```java
@Test void relayToSkill_withKnownRoute_shouldHashToCorrectConnection()
@Test void relayToSkill_withUnknownRoute_shouldBroadcastToAllGroups()
@Test void relayToSkill_broadcastTimeout_shouldReplyErrorToAgent()
@Test void handleInvokeFromSkill_shouldLearnRoute()
@Test void handleInvokeFromSkill_agentOnDifferentInstance_shouldRelay()
@Test void handleRouteConfirm_shouldLearnRoute()
@Test void handleRouteReject_shouldBeIgnored()
```

- [ ] **Step 2: 运行测试确认失败**

- [ ] **Step 3: 重构 SkillRelayService**

核心改动点：
1. 注入 `ConsistentHashRing`，source 连接注册/移除时更新环
2. 注入 `UpstreamRoutingTable`，invoke 入站时学习路由
3. `relayToSkill()` 方法重写：
   - `resolveSourceType()` → 查 UpstreamRoutingTable
   - 命中 → 在该 sourceType 组内 `hashRing.getNode(routingKey)` 选连接
   - 未命中 → 广播到所有组 → 等待 route_confirm（200ms 超时）
   - 全部超时 → 回复 Agent `tool_error(unknown_tool_session)`
4. `handleInvokeFromSkill()` 方法重写：
   - `learnRoute()` → 调用 UpstreamRoutingTable
   - 查 `gw:internal:agent:{ak}` → 本地有则直投，不在本地则 publish `gw:relay:{targetId}`
5. 新增 `handleRouteConfirm()` 和 `handleRouteReject()` 处理
6. 保留 `LegacySkillRelayStrategy` 作为 fallback（`gateway.legacy-relay.enabled` 开关）

- [ ] **Step 4: 修改 EventRelayService**

- `relayToSkillServer()` 中收到 relay 消息时调用 `learnFromRelay()` 传播路由知识
- 下行到 Agent 的路径改为：本地查 → `gw:internal:agent:{ak}` → relay pub/sub → pending 队列

- [ ] **Step 5: 运行测试确认通过**
- [ ] **Step 6: 提交**

```bash
git commit -m "feat(gw): 重构 SkillRelayService 为一致性哈希 + 路由学习表"
```

---

### Task 1.8: GatewayMessage 新增协议类型

**Files:**
- Modify: `ai-gateway/src/main/java/com/opencode/cui/gateway/model/GatewayMessage.java`

**说明:** 新增 `route_confirm`、`route_reject` 消息类型常量。

- [ ] **Step 1: 在 GatewayMessage.Types 中新增常量**

```java
public static final String ROUTE_CONFIRM = "route_confirm";
public static final String ROUTE_REJECT = "route_reject";
```

- [ ] **Step 2: 提交**

```bash
git commit -m "feat(gw): GatewayMessage 新增 route_confirm/route_reject 类型"
```

---

### Task 1.9: Legacy 兼容开关

**Files:**
- Modify: `ai-gateway/src/main/resources/application.yml`
- Modify: `ai-gateway/src/main/java/com/opencode/cui/gateway/service/SkillRelayService.java`

**说明:** 新增 `gateway.legacy-relay.enabled` 配置，控制是否保留 Legacy 路由路径。

- [ ] **Step 1: 添加配置**

```yaml
gateway:
  legacy-relay:
    enabled: true  # 默认开启，全部升级后关闭
```

- [ ] **Step 2: SkillRelayService 中根据开关决定是否启用 LegacySkillRelayStrategy**
- [ ] **Step 3: 提交**

```bash
git commit -m "feat(gw): 添加 legacy-relay.enabled 开关控制旧版兼容"
```

---

## Phase 2: SS 侧路由重构

### Task 2.1: ConsistentHashRing（SS 侧）

**Files:**
- Create: `skill-server/src/main/java/com/opencode/cui/skill/service/ConsistentHashRing.java`
- Test: `skill-server/src/test/java/com/opencode/cui/skill/service/ConsistentHashRingTest.java`

**说明:** 复制 GW 侧的 ConsistentHashRing 到 SS。后续可抽取公共模块。

- [ ] **Step 1: 复制 + 测试**
- [ ] **Step 2: 提交**

```bash
git commit -m "feat(ss): 复制 ConsistentHashRing 到 skill-server"
```

---

### Task 2.2: HTTP 服务发现 + 多级降级

**Files:**
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/service/GatewayDiscoveryService.java`
- Modify: `skill-server/src/main/resources/application.yml`
- Test: `skill-server/src/test/java/com/opencode/cui/skill/service/GatewayDiscoveryServiceTest.java`

**说明:** 重构 GatewayDiscoveryService：优先 HTTP 接口 → 降级 scanGatewayInstances → 配置中心 → 种子 URL。

- [ ] **Step 1: 编写测试**

```java
@Test void discover_httpSuccess_shouldReturnInstances()
@Test void discover_httpFail_shouldFallbackToRedis()
@Test void discover_httpFail3Times_shouldDegradePermanently()
@Test void discover_allFail_shouldKeepExistingConnections()
```

- [ ] **Step 2: 运行测试确认失败**
- [ ] **Step 3: 实现多级降级逻辑**

```java
// 降级链：HTTP → scanGatewayInstances → 配置中心 → 种子 URL → 维持现有
private Set<InstanceInfo> discoverInstances() {
    Set<InstanceInfo> result = tryHttpDiscovery();
    if (result != null) return result;
    result = tryScanRedis();  // 保留旧方法，兼容旧 GW
    if (result != null) return result;
    result = tryConfigCenter();
    if (result != null) return result;
    return Collections.emptySet();  // 维持现有连接
}
```

- [ ] **Step 4: 添加配置**

```yaml
skill:
  gateway:
    discovery-url: ${GATEWAY_LB_URL:http://localhost:8081}/internal/instances
    discovery-interval-ms: 10000
    discovery-timeout-ms: 3000
    discovery-fail-threshold: 3
```

- [ ] **Step 5: 运行测试确认通过**
- [ ] **Step 6: 提交**

```bash
git commit -m "feat(ss): 重构 GatewayDiscoveryService 多级降级发现"
```

---

### Task 2.3: GatewayWSClient 一致性哈希重构

**Files:**
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/ws/GatewayWSClient.java`
- Test: `skill-server/src/test/java/com/opencode/cui/skill/ws/GatewayWSClientTest.java`

**说明:** 下行路由（SS → GW）从精确投递（`conn:ak` 查 GW 实例 → `sendToGateway`）改为一致性哈希选连接。保留 `getConnAk` 作为降级路径。

- [ ] **Step 1: 编写测试**

```java
@Test void sendViaHash_shouldSelectConsistentConnection()
@Test void sendViaHash_connectionDown_shouldFallbackToConnAk()
@Test void onGatewayAdded_shouldUpdateHashRing()
@Test void onGatewayRemoved_shouldUpdateHashRing()
```

- [ ] **Step 2: 运行测试确认失败**
- [ ] **Step 3: 重构 GatewayWSClient**

核心改动点：
1. 新增 `ConsistentHashRing<GwConnection>` 成员
2. `onGatewayAdded` / `onGatewayRemoved` 时同步更新哈希环
3. 新增 `sendViaHash(String hashKey, String message)` 方法
4. `GatewayRelayService.sendInvokeToGateway()` 调用 `sendViaHash(ak, message)` 替代原有的 `getConnAk + sendToGateway`
5. hash 失败时降级到 `getConnAk`（兼容旧 GW）

- [ ] **Step 4: 运行测试确认通过**
- [ ] **Step 5: 提交**

```bash
git commit -m "feat(ss): GatewayWSClient 下行路由改为一致性哈希"
```

---

### Task 2.4: SS 实例心跳

**Files:**
- Create: `skill-server/src/main/java/com/opencode/cui/skill/service/SkillInstanceRegistry.java`
- Test: `skill-server/src/test/java/com/opencode/cui/skill/service/SkillInstanceRegistryTest.java`

**说明:** `ss:internal:instance:{instanceId} → "alive"`，TTL 30s，10s 刷新。

- [ ] **Step 1: 实现 + 测试**

```java
@Component
public class SkillInstanceRegistry {
    @PostConstruct void register()
    @Scheduled(fixedDelay = 10_000) void refreshHeartbeat()
    @PreDestroy void destroy()
    boolean isInstanceAlive(String instanceId)
}
```

- [ ] **Step 2: 运行测试确认通过**
- [ ] **Step 3: 提交**

```bash
git commit -m "feat(ss): 实现 SS 实例心跳 ss:internal:instance:{id}"
```

---

### Task 2.5: Session Ownership Redis 缓存层

**Files:**
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/service/SessionRouteService.java`
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/service/RedisMessageBroker.java`
- Test: `skill-server/src/test/java/com/opencode/cui/skill/service/SessionRouteServiceCacheTest.java`

**说明:** 在 SessionRouteService 的 MySQL 操作基础上加 Redis 缓存层（`ss:internal:session:{welinkSessionId} → instanceId`）。读先查 Redis，未命中查 MySQL 回填。写先 MySQL 再 Redis。

- [ ] **Step 1: 编写测试**

```java
@Test void getOwner_redisCacheHit_shouldNotQueryMySQL()
@Test void getOwner_redisMiss_shouldQueryMySQLAndBackfill()
@Test void createRoute_shouldWriteBothMySQLAndRedis()
@Test void closeRoute_shouldDeleteRedisAndUpdateMySQL()
```

- [ ] **Step 2: 运行测试确认失败**
- [ ] **Step 3: 实现 Redis 缓存层**

```java
// 新增方法
public String getOwnerInstance(String welinkSessionId) {
    // 1. 查 Redis ss:internal:session:{welinkSessionId}
    // 2. 未命中 → 查 MySQL → 回填 Redis
    // 3. 返回 instanceId
}
```

- [ ] **Step 4: 运行测试确认通过**
- [ ] **Step 5: 提交**

```bash
git commit -m "feat(ss): SessionRouteService 增加 Redis 缓存层"
```

---

### Task 2.6: SS Relay pub/sub + 失联 Owner 探活

**Files:**
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/service/RedisMessageBroker.java`
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/service/GatewayMessageRouter.java`
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/service/SessionRouteService.java`
- Test: `skill-server/src/test/java/com/opencode/cui/skill/service/SsRelayAndTakeoverTest.java`

**说明:** SS 实例订阅 `ss:relay:{instanceId}`。消息到达后查 session ownership，不是本地则 relay。Owner 失联时三重判断后乐观锁接管。

- [ ] **Step 1: RedisMessageBroker 新增 SS relay 方法**

```java
void subscribeToSsRelay(String instanceId, Consumer<String> handler)
long publishToSsRelay(String targetInstanceId, String message)  // 返回订阅者数
```

- [ ] **Step 2: GatewayMessageRouter 中集成 relay 逻辑**

```java
// route() 方法中：
// 1. 查 sessionRouteService.getOwnerInstance(welinkSessionId)
// 2. 本实例 → 本地处理
// 3. 其他实例 → publishToSsRelay(ownerInstanceId, message)
// 4. owner 失联 → 三重判断 → 乐观锁接管 → 本地处理 or 转发给 winner
```

- [ ] **Step 3: SessionRouteService 新增接管方法**

```java
public boolean tryTakeover(String welinkSessionId, String deadInstanceId, String newInstanceId) {
    // UPDATE session_route SET source_instance = ? WHERE welinkSessionId = ? AND source_instance = ?
    // affected_rows = 1 → 成功，回填 Redis
    // affected_rows = 0 → 失败，重查 winner
}
```

- [ ] **Step 4: 编写测试**

```java
@Test void route_ownerIsLocal_shouldProcessLocally()
@Test void route_ownerIsRemote_shouldRelay()
@Test void route_ownerDead_shouldTakeoverAndProcess()
@Test void route_takeoverConflict_shouldForwardToWinner()
```

- [ ] **Step 5: 运行测试确认通过**
- [ ] **Step 6: 添加配置**

```yaml
skill:
  relay:
    owner-dead-threshold-seconds: 120
```

- [ ] **Step 7: 提交**

```bash
git commit -m "feat(ss): 实现 SS relay pub/sub + 失联 owner 探活接管"
```

---

### Task 2.7: 用户 WS 连接注册表（Hash 引用计数）

**Files:**
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/ws/SkillStreamHandler.java`
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/service/RedisMessageBroker.java`
- Test: `skill-server/src/test/java/com/opencode/cui/skill/ws/UserWsRegistryTest.java`

**说明:** `ss:internal:user-ws:{userId}` Hash\<instanceId, count\>。连接建立时 HINCRBY +1，断开时 -1。SS 启动时清理自己的残留。

- [ ] **Step 1: RedisMessageBroker 新增方法**

```java
void registerUserWs(String userId, String instanceId)     // HINCRBY +1
void unregisterUserWs(String userId, String instanceId)   // HINCRBY -1, ≤0 则 HDEL
Set<String> getUserWsInstances(String userId)              // HKEYS
void cleanupUserWsForInstance(String instanceId)           // 启动时扫描清理
```

- [ ] **Step 2: SkillStreamHandler 注册/断连时调用**
- [ ] **Step 3: 编写测试**

```java
@Test void registerUserWs_shouldIncrementCount()
@Test void unregisterUserWs_shouldDecrementAndCleanup()
@Test void multipleConnections_sameInstance_shouldTrackCount()
@Test void cleanupOnStartup_shouldRemoveStaleEntries()
```

- [ ] **Step 4: 运行测试确认通过**
- [ ] **Step 5: 提交**

```bash
git commit -m "feat(ss): 用户 WS 注册表 Hash 引用计数"
```

---

### Task 2.8: 跨实例消息序号（Redis INCR）

**Files:**
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/ws/SkillStreamHandler.java`
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/service/RedisMessageBroker.java`
- Test: `skill-server/src/test/java/com/opencode/cui/skill/ws/DistributedSeqTest.java`

**说明:** `ss:stream-seq:{welinkSessionId}` Redis INCR 替换本地 `seqCounters`。

- [ ] **Step 1: RedisMessageBroker 新增方法**

```java
long nextStreamSeq(String welinkSessionId)  // INCR ss:stream-seq:{welinkSessionId}
```

- [ ] **Step 2: SkillStreamHandler.nextTransportSeq() 改为调用 Redis**
- [ ] **Step 3: 编写测试**
- [ ] **Step 4: 运行测试确认通过**
- [ ] **Step 5: 提交**

```bash
git commit -m "feat(ss): 跨实例消息序号改用 Redis INCR"
```

---

### Task 2.9: agent_online 触发 takeoverActiveRoutes

**Files:**
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/service/GatewayMessageRouter.java`

**说明:** `handleAgentOnline()` 中在广播上线状态前，调用 `sessionRouteService.takeoverActiveRoutes(ak)` 接管该 ak 下的所有 ACTIVE 路由。

- [ ] **Step 1: 修改 handleAgentOnline**

```java
private void handleAgentOnline(String ak, String userId, JsonNode node) {
    // 新增：按 ak 接管路由
    sessionRouteService.takeoverActiveRoutes(ak);
    // 原有：广播上线状态
    broadcastAgentStatus(ak, node, "agent.online");
}
```

- [ ] **Step 2: 编写测试**
- [ ] **Step 3: 运行测试确认通过**
- [ ] **Step 4: 提交**

```bash
git commit -m "feat(ss): agent_online 触发 takeoverActiveRoutes"
```

---

### Task 2.10: SS 侧 route_confirm/route_reject 响应

**Files:**
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/service/GatewayMessageRouter.java`
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/service/GatewayRelayService.java`

**说明:** SS 收到含 toolSessionId 的上行消息时，判断是否属于自己。属于 → 处理 + 回复 `route_confirm`。不属于 → 回复 `route_reject`。

- [ ] **Step 1: GatewayMessageRouter 新增路由确认逻辑**

```java
// 收到上行消息（tool_event 等）后：
// 1. findByToolSessionId(toolSessionId)
// 2. 找到 → 正常处理 + 发送 route_confirm
// 3. 找不到 → 发送 route_reject
// 4. 找到但 toolSessionId 已失效 → 发送 tool_error(invalid_session)
```

- [ ] **Step 2: GatewayRelayService 新增发送 confirm/reject 的方法**
- [ ] **Step 3: 编写测试**
- [ ] **Step 4: 运行测试确认通过**
- [ ] **Step 5: 提交**

```bash
git commit -m "feat(ss): 实现 route_confirm/route_reject 协议响应"
```

---

## Phase 3: 集成联调 + 滚动升级验证

### Task 3.1: 端到端下行路由测试（SS → GW → Agent）

**Files:**
- Test: `tests/test_downlink_routing.py`

**说明:** Python 集成测试。验证 SS 发 invoke 通过一致性哈希到达 GW，GW 通过 relay pub/sub 投递给 Agent。

- [ ] **Step 1: 编写测试用例**

```python
def test_invoke_reaches_agent_via_hash_ring():
    """SS 发 invoke → hash 选 GW → relay → Agent 收到"""

def test_invoke_with_agent_offline_goes_to_pending():
    """Agent 离线 → invoke 进 pending → Agent 重连后收到"""
```

- [ ] **Step 2: 运行测试**

Run: `cd tests && pytest test_downlink_routing.py -v`

- [ ] **Step 3: 提交**

```bash
git commit -m "test: 端到端下行路由集成测试"
```

---

### Task 3.2: 端到端上行路由测试（Agent → GW → SS）

**Files:**
- Test: `tests/test_uplink_routing.py`

**说明:** Agent 发 tool_event → GW 路由学习表查 sourceType → hash 选 SS → SS 处理。

- [ ] **Step 1: 编写测试用例**

```python
def test_tool_event_routes_to_correct_source():
    """Agent 回复 tool_event → GW 路由到正确的 SS"""

def test_route_fallback_broadcast_and_confirm():
    """GW 重启后路由表为空 → 广播 → SS 回复 confirm → GW 学习路由"""

def test_invalid_session_returns_error():
    """旧 toolSessionId → SS 回复 invalid_session → Agent 收到错误"""
```

- [ ] **Step 2: 运行测试**
- [ ] **Step 3: 提交**

```bash
git commit -m "test: 端到端上行路由集成测试"
```

---

### Task 3.3: 滚动升级兼容性测试

**Files:**
- Test: `tests/test_rolling_upgrade.py`

**说明:** 验证新旧版本混部场景的兼容性。

- [ ] **Step 1: 编写测试用例**

```python
def test_new_gw_with_old_ss():
    """新版 GW 双写 Redis key → 旧版 SS 正常发现和路由"""

def test_new_ss_with_old_gw():
    """新版 SS 降级到 scanGatewayInstances → 旧版 GW 正常工作"""

def test_legacy_relay_enabled():
    """legacy-relay.enabled=true → Legacy 路由路径正常"""
```

- [ ] **Step 2: 运行测试**
- [ ] **Step 3: 提交**

```bash
git commit -m "test: 滚动升级兼容性集成测试"
```

---

### Task 3.4: SS 失联 Owner 探活接管测试

**Files:**
- Test: `tests/test_owner_takeover.py`

**说明:** 验证 SS 实例宕机后的 ownership 接管。

- [ ] **Step 1: 编写测试用例**

```python
def test_dead_owner_takeover():
    """SS-B 宕机 → SS-A 探活 → 乐观锁接管 → 消息正常处理"""

def test_concurrent_takeover_only_one_wins():
    """SS-A 和 SS-C 同时接管 → 只有一个 affected_rows=1"""

def test_takeover_failure_forwards_to_winner():
    """接管失败 → 重查 winner → 转发消息"""
```

- [ ] **Step 2: 运行测试**
- [ ] **Step 3: 提交**

```bash
git commit -m "test: SS 失联 owner 探活接管集成测试"
```

---

### Task 3.5: 监控指标接入

**Files:**
- Modify: `ai-gateway/src/main/java/com/opencode/cui/gateway/service/SkillRelayService.java`
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/service/GatewayMessageRouter.java`
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/service/SessionRouteService.java`

**说明:** 在关键路径埋入指标计数（使用 Micrometer 或自定义 Counter）。

- [ ] **Step 1: GW 侧指标**

```java
// SkillRelayService
counter("relay.local.count")        // 本地命中
counter("relay.pubsub.count")       // pub/sub 中转
counter("relay.pending.count")      // 写入 pending
counter("upstream.routing.hit")     // 路由学习表命中
counter("upstream.routing.broadcast") // 广播 fallback
```

- [ ] **Step 2: SS 侧指标**

```java
// GatewayMessageRouter + SessionRouteService
counter("session.takeover.count")
counter("session.takeover.conflict.count")
counter("owner.probe.dead.count")
counter("relay.pubsub.count")
```

- [ ] **Step 3: 提交**

```bash
git commit -m "feat: 接入路由解耦相关监控指标"
```

---

### Task 3.6: 文档更新

**Files:**
- Modify: `CLAUDE.md`

**说明:** 更新项目文档中的架构描述，反映新的路由机制。

- [ ] **Step 1: 更新 Architecture 和 Message Flow 部分**
- [ ] **Step 2: 提交**

```bash
git commit -m "docs: 更新 CLAUDE.md 反映路由解耦架构"
```

---

## 实施顺序与里程碑

```
Week 1: Phase 1 (Task 1.1 ~ 1.9) → GW 独立部署验证
Week 2: Phase 2 (Task 2.1 ~ 2.10) → SS 独立部署验证
Week 3: Phase 3 (Task 3.1 ~ 3.6) → 联调 + 灰度上线
```

**关键里程碑**：
1. Phase 1 完成后：新版 GW 部署，旧版 SS 正常工作（双写 key 兼容）
2. Phase 2 完成后：新版 SS 部署，通过 HTTP 接口发现新版 GW
3. Phase 3 完成后：端到端验证通过，`legacy-relay.enabled` 可安全关闭

**回滚策略**：每个 Phase 可独立回滚。Phase 1 回滚不影响 SS，Phase 2 回滚不影响 GW（SS 降级到旧路径）。
