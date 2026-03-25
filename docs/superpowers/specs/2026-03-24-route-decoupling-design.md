# 服务间路由解耦设计

> 日期：2026-03-24
> 状态：Draft v2
> 分支：route-redesign-0321

## 1. 背景与问题

当前 skill-server 与 ai-gateway 之间的路由寻址存在双向耦合：

1. **SS 读取 GW 内部状态**：SS 通过 Redis `conn:ak:{ak}` key 查询 Agent 连接在哪个 GW 实例上，用于精确投递 invoke 消息。这是 GW 的内部状态泄漏到了 SS。

2. **GW 维护 SS 实例路由**：GW 的 `SkillRelayService` 维护 `routeCache`（sessionId → SS WebSocket 连接映射）和 Mesh 路由逻辑，GW 需要感知 SS 的实例拓扑。

**目标**：服务间实例寻址由服务内部自行处理，SS 不感知 GW 实例，GW 不感知 SS 实例。

## 2. 设计原则

| 原则 | 说明 |
|------|------|
| 服务间无实例级状态共享 | 发送方不知道接收方有几个实例、资源在哪个实例上 |
| 路由决策归接收方 | 发送方只管投递到某条连接，接收方自行判断是否处理或内部中转 |
| 服务内部自治 | 每个服务通过 gRPC mesh 解决实例间中转 |
| 用户零感知 | 扩缩容、故障场景下消息不丢、不报错、不乱序 |
| 可复用框架 | GW、SS 及未来其他服务复用同一套服务内路由框架 |

## 3. 整体架构

```
                    ┌───────────────────────────────────┐
                    │          Skill Server              │
                    │                                    │
                    │  SS-1 ←───gRPC mesh───→ SS-2       │
                    │   ↑                      ↑         │
                    │   │ miniapp WS            │ miniapp WS │
                    │   ↓                      ↓         │
                    │  Miniapp-A            Miniapp-B     │
                    └──┬──────────────────────────────┬──┘
                       │     WebSocket 网状长连接       │
                       │     (一致性哈希选连接)         │
                    ┌──┴──────────────────────────────┴──┐
                    │          AI Gateway                 │
                    │                                     │
                    │  GW-1 ←───gRPC mesh───→ GW-2       │
                    │                                     │
                    │  ← WS: skill-server 连接组          │
                    │  ← WS: bot-platform 连接组          │
                    └──┬──────────────────────────────┬──┘
                       │  WebSocket                    │
                    Agent-A                         Agent-B
```

**关键分层**：

| 层次 | 职责 | 章节 |
|------|------|------|
| 服务间路由 | WebSocket 网状连接 + 一致性哈希 | 第4节 |
| 服务发现 | HTTP 接口 + 配置中心降级 | 第5节 |
| 服务内注册表 | Redis 缓存（动态恢复） | 第6节 |
| 服务内中转 | gRPC 实例间 mesh + 可复用框架 | 第7节 |
| 零感知保障 | 三级 fallback + pending 队列 | 第8节 |

## 4. 服务间路由：一致性哈希

### 4.1 哈希策略

SS 与 GW 之间保持网状 WebSocket 长连接。发送方通过一致性哈希选择连接，不感知对端实例身份。

- **下行（SS → GW）**：`hash(ak)` → 选中某条 GW 连接
- **上行（GW → SS）**：`hash(welinkSessionId)` → 选中某条同 `source_type` 的 SS 连接

### 4.2 一致性哈希环

- 每条 WebSocket 连接作为哈希环上的虚拟节点（用连接 ID 做 key）
- 每个连接映射 150 个虚拟节点（可配置，`hash-ring.virtual-nodes: 150`），确保负载均匀分布
- 连接建立 → 加入环，连接断开 → 从环移除
- 扩缩容时只影响环上相邻的一小段映射，大部分路由不变
- 无需任何跨服务协调

### 4.3 GW 侧连接分组

GW 按 `source_type` 分组管理上游连接（如 `skill-server`、`bot-platform`）。上行路由时按组进行，不会错发给其他类型的服务。每组独立维护一致性哈希环。

### 4.4 与现有方案对比

| 维度 | 现有方案 | 新方案 |
|------|---------|--------|
| 下行路由 | SS 查 `conn:ak` 精确投递到特定 GW 实例 | SS 哈希选连接，GW 内部自行中转 |
| 上行路由 | GW 维护 `routeCache` + Mesh 路由 | GW 哈希选连接，SS 内部自行中转 |
| 实例感知 | 双向感知 | 双向不感知 |
| 扩缩容影响 | 需要更新服务发现 + 路由缓存 | 仅影响哈希环局部映射 |

## 5. 服务发现：HTTP 接口 + 配置中心降级

### 5.1 主路径：HTTP 接口

GW 暴露内部 HTTP 接口，返回当前所有 GW 实例的 WebSocket 地址列表：

```
GET /internal/instances

Response:
{
  "instances": [
    { "instanceId": "gw-pod-1", "wsUrl": "ws://10.0.1.1:8081/ws/skill" },
    { "instanceId": "gw-pod-2", "wsUrl": "ws://10.0.1.2:8081/ws/skill" }
  ]
}
```

- SS 定期调用该接口（通过 GW 的负载均衡器地址访问任一实例）
- 对比本地已知列表，新增实例建连、消失实例断连

**GW 实例列表的组装方式**：每个 GW 实例使用 GW 自己的 Redis Cluster 维护内部实例注册（`gw:internal:instance:{id} → wsUrl`，TTL 30s，心跳刷新）。HTTP 接口查询 GW 自己的 Redis 返回完整列表。这不违反"不跨服务共享 Redis"的原则——这是 GW 服务内部的实例互发现。

### 5.2 降级路径：配置中心

HTTP 接口不可用时，从配置中心读取静态 GW 实例列表作为兜底。

### 5.3 刷新策略

- 定期刷新间隔：10 秒（可配置）
- 接口调用超时：3 秒
- 连续 3 次失败后降级到配置中心
- 配置中心也不可用时，维持现有连接不变

### 5.4 需要删除的现有机制

- `GatewayInstanceRegistry`：GW 向共享 Redis 注册 `gw:instance:{id}` → 改为写 GW 内部 Redis + HTTP 接口
- `GatewayDiscoveryService`：SS 扫描共享 Redis `gw:instance:*` → 改为调用 HTTP 接口

## 6. 服务内资源注册表：纯 Redis

每个服务内部维护"资源在哪个实例上"的映射。纯 Redis 缓存，无需 MySQL 持久化——资源（Agent 连接、Session）是动态的，断连/重连时自动重新注册。

### 6.1 GW 侧：Agent 连接注册表

**Redis**：`gw:internal:agent:{ak} → instanceId`（TTL 与心跳周期关联）

- Agent 注册：写 Redis
- Agent 心跳：刷新 Redis TTL
- Agent 断连：删 Redis
- GW 实例宕机：TTL 过期自动清理；Agent 重连到其他实例后重新注册

> 不需要 MySQL 持久化。Agent 断连后重连会重新注册，GW 重启后 Agent 也会重连，Redis 自动恢复。

### 6.2 SS 侧：Session 所有权注册表

**Redis**：`ss:internal:session:{welinkSessionId} → instanceId`（TTL 与 session 活跃度关联）

- Session 创建：写 Redis
- Session 活跃：消息处理时刷新 Redis TTL
- Session 关闭：删 Redis
- SS 实例宕机：TTL 过期自动清理；新消息到达时，第一个处理的实例 auto-claim ownership

> 不需要 MySQL 持久化。现有 `session_route` 表可保留用于历史查询，但不再参与实时路由决策。

### 6.3 SS 侧：用户 WebSocket 连接注册表

一个用户可以在多台设备上同时在线，每台设备与 SS 建立一条 WebSocket 连接（端点 `/ws/skill/stream`），可能分布在不同的 SS 实例上。同一条 WS 连接覆盖该用户的所有 session。

**Redis Set**：`ss:internal:user-ws:{userId}` → {instanceId1, instanceId2, ...}

- WS 连接建立（`afterConnectionEstablished`）：`SADD` 当前 instanceId
- WS 连接断开（`afterConnectionClosed`）：`SREM` 当前 instanceId
- 查询用户所有在线实例：`SMEMBERS`
- Set 为空时自动清理（`SCARD` 为 0 则 `DEL`）

> 不需要 MySQL 持久化——WS 连接是瞬态的，重连后自动重新注册。

**与现有 `userSubscribers` 的关系**：
- `userSubscribers`（`ConcurrentHashMap<String, Set<WebSocketSession>>`）是当前 SS 实例的**本地内存**，维护本实例上该用户的所有 WS 连接
- `ss:internal:user-ws:{userId}` 是 **Redis 跨实例注册表**，维护该用户在哪些 SS 实例上有连接
- 两者配合：Redis 注册表定位实例 → gRPC 调用目标实例 → 目标实例查本地 `userSubscribers` 推送

### 6.4 一致性保障

- Redis 为唯一数据源，无需跨存储一致性
- TTL 过期 = 自动清理，无需主动运维
- 资源重新注册 = 自动恢复，无需预热
- 宕机场景：TTL 过期 + auto-claim 机制保证最终一致

## 7. 服务内中转：gRPC Mesh

### 7.1 可复用框架设计

所有需要"资源绑定实例"的服务复用同一套框架，GW、SS 及未来其他服务通过实现接口接入。

```java
/**
 * 资源注册表 —— 资源在哪个实例上
 * GW: K=ak (Agent), SS: K=welinkSessionId (Session/miniapp WS)
 */
interface ResourceRegistry<K> {
    void register(K resourceKey, String instanceId);
    void unregister(K resourceKey, String instanceId);
    String lookup(K resourceKey);            // 单一 owner
    Set<String> lookupAll(K resourceKey);    // 多 owner（如 miniapp WS 分布）
}

/**
 * 实例 Mesh —— gRPC 连接管理
 */
interface InstanceMesh {
    void onInstanceJoined(String instanceId, String grpcAddress);
    void onInstanceLeft(String instanceId);
    ManagedChannel getChannel(String instanceId);
    Set<String> allInstances();
}

/**
 * 本地投递器 —— 各服务实现自己的投递逻辑
 */
interface LocalDeliverer<K> {
    boolean deliverLocally(K resourceKey, Message message);
    boolean hasLocalResource(K resourceKey);
}

/**
 * 服务内路由器 —— 本地检查 → 定向 gRPC → 广播 gRPC → pending
 */
interface IntraServiceRouter<K> {
    DeliveryResult route(K resourceKey, Message message);
    DeliveryResult routeToAll(K resourceKey, Message message);  // 一对多
}

/**
 * Pending 队列 —— 资源暂时不可达时缓冲
 */
interface PendingQueue<K> {
    void enqueue(K resourceKey, Message message, Duration ttl);
    List<Message> drain(K resourceKey);
}
```

### 7.2 gRPC Mesh 连接管理

**实例发现**：复用第 6 节的内部 Redis 注册表。每个实例注册时同时写入 gRPC 地址：

- GW：`gw:internal:instance:{id} → { wsUrl, grpcAddress }`
- SS：`ss:internal:instance:{id} → { grpcAddress }`

**连接建立**：实例启动时扫描注册表，与所有同服务实例建立 gRPC 连接。

**连接拓扑**：N 个实例 = N×(N-1) 条 gRPC 连接。3 实例 = 6 条，5 实例 = 20 条。

**gRPC 配置**：
- 基于 HTTP/2 多路复用，一条 TCP 连接承载所有 stream
- 内置 keepalive（间隔 30s，超时 5s）
- 自动重连（exponential backoff）
- 健康检查（gRPC Health Checking Protocol）

**gRPC 服务定义**：

```protobuf
syntax = "proto3";

service IntraServiceRelay {
  // 单条消息转发（Agent 投递、Session 业务处理）
  rpc Relay(RelayRequest) returns (RelayResponse);

  // 批量转发（Streaming 场景优化，复用一条 gRPC stream）
  rpc RelayStream(stream RelayRequest) returns (stream RelayResponse);

  // 推送给本地用户的 WS 连接（多设备同步）
  rpc PushToUser(PushRequest) returns (PushResponse);

  // 广播查询（资源在不在你这）
  rpc Probe(ProbeRequest) returns (ProbeResponse);
}

message RelayRequest {
  string message_id = 1;       // 幂等 ID
  string resource_key = 2;     // ak 或 welinkSessionId
  string message_type = 3;     // tool_event, invoke 等
  bytes  payload = 4;          // 原始 GatewayMessage
}

message RelayResponse {
  bool   delivered = 1;        // 是否成功投递
  string handler_instance = 2; // 哪个实例处理的（用于更新注册表）
}

message PushRequest {
  string user_id = 1;          // 目标用户
  string session_id = 2;       // 会话 ID（前端按此过滤显示）
  bytes  payload = 3;          // StreamMessage
}

message PushResponse {
  int32  delivered_count = 1;  // 本地推送了几条 WS 连接
}

message ProbeRequest {
  string resource_key = 1;
}

message ProbeResponse {
  bool   found = 1;
  string instance_id = 2;
}
```

### 7.3 性能开销

| 维度 | 开销 |
|------|------|
| 连接数 | 2-4 条/实例（HTTP/2 多路复用） |
| 内存 | ~几 MB（连接缓冲区 + protobuf） |
| CPU | 极低（protobuf 二进制序列化比 JSON 快 5-10 倍） |
| 网络延迟 | <1ms（同机房直连，无 broker 中间跳） |
| 对比 | 相比每个实例承载的数千~数万条 WebSocket 连接，gRPC 开销可忽略 |

### 7.4 消息幂等性

`message_id` 用于去重。接收方维护短期去重窗口（Caffeine LocalCache，TTL 60 秒），同一 `message_id` 只处理一次。避免 fallback 和 pending 补投场景下的重复投递。

### 7.5 现有 pub/sub 通道的处理

当前 GW 内部 Agent 消息投递使用 Redis pub/sub（`agent:{ak}` channel）。新设计中：

- **本地 Agent 投递**（第一级）：直接通过本地内存查找 Agent 的 WebSocket Session 投递，不再经过 pub/sub
- **跨实例中转**（第二级/第三级）：使用 gRPC 替代 pub/sub
- `EventRelayService.subscribeToAgent` 的 pub/sub 订阅模式废弃
- `RedisMessageBroker.publishToAgent` 废弃

## 8. 零感知保障：三级 Fallback

### 8.1 投递流程（单一 Owner）

以 GW 收到下行消息、需要投递给 Agent-X 为例：

```
GW-A 收到消息（SS 通过哈希选中 GW-A）
  │
  ├─ 第一级：本地检查
  │    Agent-X 在本实例？→ 直接投递，结束 ✓
  │
  ├─ 第二级：查注册表 + gRPC 定向转发
  │    Redis 查 gw:internal:agent:{ak} → GW-B
  │    gRPC 调用 GW-B.Relay(ak, message)
  │      → 成功（delivered=true）→ 结束 ✓
  │      → 失败（UNAVAILABLE，GW-B 挂了）→ 毫秒级感知
  │        → 从 mesh 移除 GW-B → 清理注册表 → 进入第三级
  │
  └─ 第三级：gRPC 广播自判
       逐个调用其他存活实例 gRPC Probe(ak)
         → 某实例返回 found=true → gRPC Relay 到该实例 → 更新注册表 → 结束 ✓
         → 全部 found=false → Agent 确实不在线 → 写入 pending 队列
```

**与之前 Redis Streams 方案的关键差异**：
- gRPC 是同步的，失败**毫秒级感知**（不需要等心跳 TTL 过期或 XCLAIM 超时）
- 发送方始终持有消息，只有确认投递成功才释放
- 不存在"消息卡在死实例队列里"的问题

### 8.2 投递流程（多设备推送：SS 推送给用户所有设备）

SS 处理完消息后需要推送给该用户所有在线设备的 WebSocket 连接，分布在不同 SS 实例上。

**连接模型**：
- 一个用户一台设备一条 WS 连接（端点 `/ws/skill/stream`）
- 同一条 WS 连接覆盖该用户的所有 session（通过 `welinkSessionId` 字段区分）
- 多设备同步：所有设备都收到所有 session 的消息，前端按当前选中的 session 过滤显示

**推送流程**：

```
SS-B（session owner）处理完 tool_event
  │
  ├─ 查 session 的 userId（sessionOwners 缓存或 DB）
  │
  ├─ 查用户 WS 注册表：ss:internal:user-ws:{userId} → {SS-A, SS-B, SS-C}
  │
  ├─ SS-B 本地有该用户的 WS 连接 → 查 userSubscribers[userId] → 直接推送 ✓
  │
  ├─ SS-A 有该用户的 WS 连接 → gRPC 调用 SS-A.PushToUser(userId, message)
  │    → SS-A 查本地 userSubscribers[userId] → 推送给本地 WS 连接 ✓
  │
  ├─ SS-C 有该用户的 WS 连接 → gRPC 调用 SS-C.PushToUser(userId, message)
  │    → 成功 → ✓
  │    → 失败（SS-C 宕机）→ 毫秒级感知
  │      → SS-C 上的 WS 连接也已断连 → 用户设备已感知断连
  │      → 从注册表 SREM SS-C
  │      → 用户设备重连到其他 SS 实例后自动重新注册
  │      → 重连后通过 REST 拉取最新消息补偿（Snapshot 机制）
  │
  └─ 推送完成
```

**与现有 Redis pub/sub `user-stream:{userId}` 的对比**：

| 维度 | 现有 Redis pub/sub | 新 gRPC 推送 |
|------|-------------------|-------------|
| 机制 | publishToUser → 所有订阅实例收到 | 查注册表 → gRPC 逐个调用 |
| 故障感知 | 无（fire-and-forget） | 毫秒级（gRPC 返回状态） |
| 注册表清理 | 无自动清理 | 推送失败时自动 SREM |
| 额外依赖 | Redis pub/sub channel | gRPC mesh（已有） |

> **注意**：用户设备推送失败不需要写 pending 队列。WS 断连意味着设备已感知断连，重连后会通过 Snapshot 机制（`sendInitialStreamingState`）自动恢复完整状态。pending 机制只用于 Agent 侧。

### 8.3 Pending 队列

当三级 fallback 都未能投递时（Agent 确实暂时不可达）：

- 消息写入 Redis List：`gw:pending:{ak}` 或 `ss:pending:{welinkSessionId}`
- TTL：60 秒（超过此时间认为消息已过期）
- Agent 重连 / Session 被接管后，新 owner 实例检查 pending 队列并补投

```java
// 资源重新注册时自动触发补投
void onResourceRegistered(K resourceKey) {
    List<Message> pending = pendingQueue.drain(resourceKey);
    for (Message msg : pending) {
        localDeliverer.deliverLocally(resourceKey, msg);
    }
}
```

### 8.4 场景覆盖

| 场景 | 处理方式 | 用户感知 |
|------|---------|---------|
| 正常运行 | 第一级本地命中或第二级 gRPC 定向 | 无延迟 |
| GW 实例宕机 | gRPC 毫秒级感知 → 广播 → pending → Agent 重连补投 | 消息延迟数秒（Agent 重连时间） |
| SS 实例宕机 | gRPC 毫秒级感知 → miniapp 重连 + REST 拉取补偿 | 用户短暂断连后自动恢复 |
| GW 扩容 | 新实例注册 → 其他实例发现 → 建立 gRPC 连接 → 加入 mesh | 无感知 |
| SS 扩容 | 同上 | 无感知 |
| GW 滚动升级 | gRPC drain → 完成进行中 RPC → Agent 断连重连 → 新实例加入 | 无感知 |
| SS 滚动升级 | gRPC drain → miniapp 断连重连 → 新实例加入 | 无感知 |
| 消息乱序 | gRPC 同步顺序处理，失败毫秒级感知后重路由，不存在多路径竞争 | 不乱序 |

### 8.5 完整时序：GW-B 宕机场景

```
T+0.0s   GW-B 宕机，Agent-X 断连
T+0.5s   SS 发 invoke 给 Agent-X，hash 选中 GW-A
T+0.5s   GW-A 本地无 Agent-X
T+0.5s   GW-A 查注册表 → 指向 GW-B
T+0.5s   GW-A gRPC 调用 GW-B → UNAVAILABLE（2ms 内返回）
T+0.502s GW-A 从 mesh 移除 GW-B，清理 GW-B 的注册表记录
T+0.502s GW-A 逐个 gRPC Probe 其他实例 → 都没有 Agent-X
T+0.503s GW-A 写入 pending 队列 gw:pending:{ak}
T+3.0s   Agent-X 重连到 GW-C
T+3.0s   GW-C 注册 Agent-X → 触发 onResourceRegistered
T+3.0s   GW-C 检查 pending 队列 → 发现积压消息 → 立即投递
```

用户感知：消息延迟约 2.5 秒（Agent 重连时间），无丢失、无乱序。
对比之前 Redis Streams 方案的最差 10 秒延迟，**故障感知从秒级提升到毫秒级**。

### 8.6 完整时序：SS-B 宕机 + 用户设备重连场景

```
T+0.0s   SS-B 宕机
T+0.0s   用户 A 在电脑上的 WS 连接断开（该连接在 SS-B 上）
T+0.5s   SS-A（session owner）处理 tool_event，需推送给用户 A
T+0.5s   SS-A 查 ss:internal:user-ws:{userA} → {SS-A, SS-B}
T+0.5s   SS-A 本地推送给用户 A 的手机 WS → 成功 ✓
T+0.5s   SS-A gRPC PushToUser 到 SS-B → UNAVAILABLE（2ms）
T+0.502s SS-A 从注册表 SREM SS-B
T+1.5s   用户 A 的电脑 WS 重连到 SS-C（通过 LB）
T+1.5s   SS-C SADD 到 ss:internal:user-ws:{userA}
T+1.5s   SS-C 发送 Snapshot（历史消息 + 实时流式状态）→ 用户 A 电脑恢复完整状态
```

用户感知：手机端无任何中断；电脑端短暂断连 ~1 秒后自动恢复，Snapshot 补偿完整状态。

## 9. 需要删除/重构的现有代码

### GW 侧（ai-gateway）

| 现有代码 | 处理方式 |
|---------|---------|
| `conn:ak:{ak}` Redis key | 删除。GW 内部改用 `gw:internal:agent:{ak}` |
| `GatewayInstanceRegistry` | 重构。从写共享 Redis 改为写 GW 内部 Redis（`gw:internal:instance:{id}`）+ 暴露 HTTP 接口 |
| `SkillRelayService.routeCache` | 重构。从 sessionId→WS连接 映射改为 routingKey→sourceType 学习表（第13节） |
| `SkillRelayService` Mesh 路由逻辑 | 重构为一致性哈希选连接 + gRPC 服务内中转 |
| `LegacySkillRelayStrategy` | 删除。新设计统一为一致性哈希，不再保留 Legacy 兼容 |
| `RedisMessageBroker.publishToAgent` / `agent:{ak}` pub/sub | 废弃。改为本地直投 + gRPC 跨实例中转 |
| `EventRelayService.subscribeToAgent` pub/sub 订阅 | 废弃。改为 gRPC 接收中转消息 |
| `RedisMessageBroker` 中 `@Deprecated` 方法 | 删除 |

### SS 侧（skill-server）

| 现有代码 | 处理方式 |
|---------|---------|
| `RedisMessageBroker.getConnAk()` | 删除。SS 不再读取 GW 的 Agent 位置信息 |
| `RedisMessageBroker.scanGatewayInstances()` | 删除。改为 HTTP 接口调用 |
| `GatewayRelayService.sendInvokeToGateway` 中查 `conn:ak` 的逻辑 | 删除。改为一致性哈希选连接 |
| `GatewayDiscoveryService` | 重构。从扫描共享 Redis 改为调用 HTTP 接口 + 配置中心降级 |
| `RedisMessageBroker.publishToUser` / `user-stream:{userId}` pub/sub | 废弃。改为 gRPC PushToUser 多设备推送 |
| `SkillStreamHandler.subscribeToUserStream` / `unsubscribeFromUserStream` | 废弃。改为 gRPC PushToUser 接收端 |

## 10. 基础设施依赖

| 组件 | 规格 | 用途 |
|------|------|------|
| GW Redis Cluster | 3主3从，8GB | Agent 连接注册表、实例注册、pending 队列 |
| SS Redis Cluster | 3主3从，8GB | Session 所有权注册表、用户 WS 注册表、实例注册、pending 队列 |

> MySQL 不再参与实时路由决策。现有 `session_route` 表可保留用于历史查询和运维审计，但路由层面纯靠 Redis + 动态恢复。

**注意**：
- GW 和 SS 的 Redis Cluster 是独立的，不共享
- 服务间唯一的通信通道是 WebSocket 长连接
- 服务内实例间通过 gRPC 直连通信
- 无需 Kafka 或其他额外消息中间件

## 11. 配置参数

### GW 侧

```yaml
gateway:
  instance-id: ${GATEWAY_INSTANCE_ID:${HOSTNAME:gateway-local}}
  internal-api:
    enabled: true                    # 暴露 /internal/instances 接口
  hash-ring:
    virtual-nodes: 150              # 每条连接的虚拟节点数
  grpc:
    server-port: 9091               # gRPC 服务端口
    keepalive-interval-s: 30        # keepalive 间隔
    keepalive-timeout-s: 5          # keepalive 超时
    max-inbound-message-size: 4MB   # 最大消息体
    drain-timeout-s: 30             # 优雅关闭 drain 超时
  relay:
    pending-ttl-seconds: 60         # Pending 队列 TTL
    probe-timeout-ms: 100           # Probe 调用超时
    relay-timeout-ms: 500           # Relay 调用超时
    dedup-cache-ttl-seconds: 60     # 幂等去重缓存 TTL
  upstream-routing:
    cache-max-size: 100000          # 路由学习表最大条目数
    cache-expire-minutes: 30        # 无活动过期时间
```

### SS 侧

```yaml
skill:
  instance-id: ${HOSTNAME:skill-server-local}
  gateway:
    discovery-url: ${GATEWAY_LB_URL:http://localhost:8081}/internal/instances
    discovery-interval-ms: 10000    # 服务发现刷新间隔
    discovery-timeout-ms: 3000      # HTTP 调用超时
    discovery-fail-threshold: 3     # 连续失败次数后降级
    fallback-ws-urls:               # 配置中心降级地址
      - ws://gateway-1:8081/ws/skill
      - ws://gateway-2:8081/ws/skill
  hash-ring:
    virtual-nodes: 150
  grpc:
    server-port: 9092
    keepalive-interval-s: 30
    keepalive-timeout-s: 5
    max-inbound-message-size: 4MB
    drain-timeout-s: 30
  relay:
    pending-ttl-seconds: 60
    probe-timeout-ms: 100
    relay-timeout-ms: 500
    dedup-cache-ttl-seconds: 60
```

## 12. 监控指标

| 指标 | 说明 | 报警阈值建议 |
|------|------|-------------|
| `relay.local.rate` | 第一级（本地命中）比例 | 低于 10% 时告警（哈希分布异常） |
| `relay.grpc.direct.rate` | 第二级（gRPC 定向转发）比例 | 正常应为主要路径 |
| `relay.grpc.broadcast.rate` | 第三级（gRPC 广播）比例 | 高于 20% 时告警 |
| `relay.grpc.latency_ms` | gRPC 转发延迟 | P99 > 10ms 时告警 |
| `relay.grpc.error.rate` | gRPC 调用失败率 | > 1% 时告警 |
| `relay.pending.count` | Pending 队列消息数 | 持续 > 0 时关注 |
| `relay.pending.expired` | Pending 过期（丢失）消息数 | > 0 时告警 |
| `mesh.instance.count` | gRPC mesh 活跃实例数 | 偏离预期实例数时告警 |
| `hashring.node.count` | 哈希环活跃节点数 | 偏离预期连接数时告警 |
| `discovery.fallback.active` | 是否降级到配置中心 | true 时告警 |
| `upstream.routing.hit.rate` | 上行路由学习表命中率 | 低于 90% 时告警 |
| `upstream.routing.broadcast.rate` | 上行路由未命中广播比例 | 高于 10% 时告警 |
| `upstream.routing.size` | 路由学习表当前条目数 | 接近 max-size 时告警 |
| `push.user.grpc.rate` | gRPC PushToUser 调用频率 | 基线监控 |
| `push.user.grpc.error.rate` | PushToUser 失败率 | > 5% 时告警（可能有 SS 实例异常） |

## 13. GW 上行路由：自动学习

### 13.1 问题

GW 连接了多种 source 服务（skill-server、bot-platform 等），Agent 回复消息时 GW 需要判断发给哪个 source_type。

**约束**：
- Agent 和 Plugin 不应承担路由职责，不能要求它们回传 source_type
- GW 不应维护 per-session 的外部映射（如 Redis/MySQL）
- 方案应通用，适用于任何 source 服务

### 13.2 方案：路由学习表（Learning Route Table）

类似网络交换机的 MAC 地址学习机制：GW 通过观察入站消息自动学习路由，不依赖任何外部系统。

**学习过程**：
```
SS 发 invoke（携带 welinkSessionId=S1）→ 经过 source_type=skill-server 的 WS 连接进入 GW
  → GW 自动记录：routingTable[S1] = skill-server

Agent 回复 tool_event（携带 welinkSessionId=S1）
  → GW 查 routingTable[S1] → skill-server
  → 在 skill-server 连接组内 hash(S1) 选连接 → 投递
```

### 13.3 路由表设计

```java
// GW 本地内存，Caffeine Cache，无需 Redis
private final Cache<String, String> routingTable = Caffeine.newBuilder()
        .expireAfterAccess(Duration.ofMinutes(30))  // 30 分钟无活动过期
        .maximumSize(100_000)                        // 最大 10 万条路由
        .build();

// 学习：从 source 连接收到 invoke 时调用
void learnRoute(GatewayMessage message, String sourceType) {
    // 从顶层字段学习 welinkSessionId
    String welinkSessionId = message.getWelinkSessionId();
    if (welinkSessionId != null && !welinkSessionId.isBlank()) {
        routingTable.put("w:" + welinkSessionId, sourceType);
    }
    // 从 payload 学习 toolSessionId
    String toolSessionId = extractToolSessionIdFromPayload(message);
    if (toolSessionId != null && !toolSessionId.isBlank()) {
        routingTable.put(toolSessionId, sourceType);
    }
}

// 查询：Agent 回复消息时调用
String resolveSourceType(GatewayMessage message) {
    // Agent 回复优先用 toolSessionId 查（大部分回复只有 toolSessionId）
    String toolSessionId = message.getToolSessionId();
    if (toolSessionId != null) {
        String st = routingTable.getIfPresent(toolSessionId);
        if (st != null) return st;
    }
    // fallback：用 welinkSessionId 查（session_created 场景）
    String welinkSessionId = message.getWelinkSessionId();
    if (welinkSessionId != null) {
        String st = routingTable.getIfPresent("w:" + welinkSessionId);
        if (st != null) return st;
    }
    return null;
}
```

**两种 key 是独立的路由条目，不是 welinkSessionId 和 toolSessionId 之间的映射关系。**

### 13.4 学习时机

所有路由学习都发生在 **source 连接入站时**（invoke 消息），通过观察消息中的字段自动建立。

| invoke 场景 | 顶层 welinkSessionId | payload 中 toolSessionId | 学习结果 |
|------------|---------------------|------------------------|---------|
| create_session（新建会话） | "42" | 无 | `routingTable["w:42"] = skill-server` |
| chat（存量会话对话） | 无 | "ts-abc" | `routingTable["ts-abc"] = skill-server` |
| 两者都有 | "42" | "ts-abc" | 两条都学 |

**Agent 回复的路由查找**：

| Agent 回复类型 | 携带字段 | 查找过程 |
|--------------|---------|---------|
| session_created | welinkSessionId + toolSessionId | 先查 toolSessionId → 未命中 → 查 "w:" + welinkSessionId → 命中 ✅ |
| tool_event / tool_done / tool_error | 只有 toolSessionId | 查 toolSessionId → 命中（之前 chat invoke 已学过）✅ |

**存量会话的完整流程**：
```
1. 用户在已有会话中发消息
2. SS 发 invoke(action=chat, payload={toolSessionId:"ts-abc"})
   → GW 从 payload 学习 routingTable["ts-abc"] = skill-server ✅

3. Agent 回复 tool_event(toolSessionId="ts-abc")
   → GW 查 routingTable["ts-abc"] → skill-server → 命中 ✅
```

**新建会话的完整流程**：
```
1. SS 发 invoke(action=create_session, welinkSessionId="42")
   → GW 学习 routingTable["w:42"] = skill-server ✅

2. Agent 回复 session_created(welinkSessionId="42", toolSessionId="ts-abc")
   → GW 查 routingTable["ts-abc"] → 未命中
   → GW 查 routingTable["w:42"] → skill-server → 命中 ✅
   → 路由成功后，从 session_created 消息中顺带学习：
     routingTable["ts-abc"] = skill-server ✅

3. Agent 后续回复 tool_event(toolSessionId="ts-abc")
   → GW 查 routingTable["ts-abc"] → 命中 ✅
```

### 13.5 路由未命中处理

如果 `routingTable` 未命中（GW 重启丢失内存、TTL 过期等）：

```
Agent 发 tool_event → routingTable 未命中
  → GW 广播到所有 source_type 组（每组 hash 选一条连接）
  → 每个 source 服务自判：这个 session 是我的吗？
    → 是 → 处理 + 回复确认
    → 否 → 丢弃
  → GW 从回复中重新学习路由
```

广播是低频事件（仅在 GW 重启或长时间无活动后），正常运行时路由表命中率接近 100%。

### 13.6 gRPC Relay 路由传播

invoke 消息到达 GW-A，但 Agent 在 GW-B 上。GW-A 学到了路由，GW-B 也需要学到，否则 Agent 回复时 GW-B 无法路由。

**解法**：gRPC RelayRequest 携带 `source_type` 和路由 key，GW-B 收到 relay 时也学习路由。

```
SS invoke(toolSessionId="ts-abc") → GW-A 的 WS 连接
  → GW-A 学习：routingTable["ts-abc"] = skill-server
  → GW-A 本地无 Agent → gRPC Relay 到 GW-B
    RelayRequest { source_type="skill-server", routing_keys=["ts-abc"] }
  → GW-B 收到 Relay，也学习：routingTable["ts-abc"] = skill-server ✅
  → GW-B 投递给本地 Agent

Agent 回复 tool_event(toolSessionId="ts-abc") → GW-B
  → GW-B 查 routingTable["ts-abc"] → skill-server → 命中 ✅
```

gRPC RelayRequest 增加路由传播字段：
```protobuf
message RelayRequest {
  string message_id = 1;
  string resource_key = 2;
  string message_type = 3;
  bytes  payload = 4;
  string source_type = 5;              // 路由学习传播
  repeated string routing_keys = 6;    // 需要学习的路由 key（如 "ts-abc", "w:42"）
}
```

### 13.7 与现有 `routeCache` 的区别

| 维度 | 现有 routeCache | 新 routingTable |
|------|----------------|----------------|
| 映射目标 | 具体 WS 连接（SS 实例级） | source_type（服务类型级） |
| 耦合性 | GW 感知 SS 实例 | GW 只感知服务类型 |
| 粒度 | sessionId → WS Session | routingKey → sourceType |
| 学习方式 | 被动缓存 | 主动学习 + 广播 fallback |
| 实例间共享 | 否 | 否（各实例独立） |

### 13.8 完整上行路由流程

```
Agent 发 tool_event（toolSessionId="ts-abc"）→ GW-B 收到
  │
  ├─ 查 routingTable["ts-abc"] → skill-server（之前 invoke relay 时已学到）
  │
  ├─ 在 skill-server 连接组内 hash("ts-abc") 选连接 → SS-A 的 WS 连接
  │    → 发送给 SS-A
  │
  └─ SS-A 收到后，SS 内部自行处理：
       → 通过 toolSessionId 反查 welinkSessionId（sessionService.findByToolSessionId）
       → 查 session ownership → owner 是 SS-B
       → gRPC Relay 给 SS-B
       → SS-B 处理业务
       → SS-B 查用户 WS 注册表 → gRPC PushToUser 给所有在线设备
```
