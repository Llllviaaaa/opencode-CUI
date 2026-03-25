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
| 用户零感知 | 扩缩容、故障场景下消息不静默丢失、可观测、可补偿（超时通过 `invoke_timeout` 通知 source 服务决策重试或告知用户） |
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
- **上行（GW → SS）**：确定 sourceType 后，在该组内 `hash(routingKey)` 选连接。routingKey 优先用 `welinkSessionId`（active-invoke 或 routingTable 提供），没有则用 `toolSessionId`

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
- SS 实例宕机：TTL 过期自动清理；新消息到达时，通过 `SETNX` 原子抢占 ownership（先 SETNX 成功的实例获得 ownership，失败的实例 GET 后判断是否自己，避免双处理）

> 不需要 MySQL 持久化。现有 `session_route` 表可保留用于历史查询，但不再参与实时路由决策。

### 6.3 SS 侧：用户 WebSocket 连接注册表

一个用户可以在多台设备上同时在线，每台设备与 SS 建立一条 WebSocket 连接（端点 `/ws/skill/stream`），可能分布在不同的 SS 实例上。同一条 WS 连接覆盖该用户的所有 session。

**Redis Hash（引用计数）**：`ss:internal:user-ws:{userId}` → Hash\<instanceId, connectionCount\>

- WS 连接建立：`HINCRBY ss:internal:user-ws:{userId} {instanceId} 1`
- WS 连接断开：`HINCRBY ss:internal:user-ws:{userId} {instanceId} -1`，若结果 ≤ 0 则 `HDEL`
- 查询用户所有在线实例：`HKEYS`（只返回 count > 0 的 instanceId）
- Hash 为空时自动清理（`HLEN` 为 0 则 `DEL`）

> **为什么用 Hash 引用计数而不是 Set**：同一用户可能在同一 SS 实例上有多条 WS 连接（如同一设备多标签页）。Set\<instanceId\> 会将多条连接压扁为一个 instanceId，断开任一条就 SREM 整个实例，导致其他连接的推送中断。Hash 引用计数与旧版 `SkillStreamHandler` 的 `activeConnectionCounts` 本地计数器保持一致。

> 不需要 MySQL 持久化——WS 连接是瞬态的，重连后自动重新注册。

**宕机清理**：SS 实例宕机后，其在 Hash 中的 count 不会被主动清理。清理机制：
- **被动清理**：下次 gRPC PushToUser 到该实例失败时，HDEL 清除（自愈）
- **主动清理**：SS 实例启动时，扫描 Redis 中所有 `ss:internal:user-ws:*` Hash，HDEL 自己的 instanceId（清除上一次宕机的残留计数）

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
  string trace_id = 1;         // 幂等 ID（复用 GatewayMessage.traceId）
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

`traceId` 用于去重（复用 GatewayMessage 已有的 `traceId` 字段，无需新增协议字段）。接收方维护去重窗口（Caffeine LocalCache，TTL 与 `hard-timeout-seconds` 对齐，默认 600 秒），同一 `traceId` 只处理一次。覆盖 fallback、pending 补投和 Agent 重连恢复（可能在软超时到硬超时的 5-10 分钟窗口内发生）场景下的重复投递。

> **traceId 一致性保证**：Agent 只连接一个 GW 实例，tool_event/tool_done/tool_error 只到达该实例。`ensureTraceId()` 在该实例上生成唯一 traceId，后续无论精确路由还是广播 fallback，都由同一 GW 发出、携带同一 traceId，source 侧去重有效。不存在"多个 GW 同时收到同一条 Agent 回复并各自生成不同 traceId"的场景。

### 7.5 现有 pub/sub 通道的处理

当前 GW 内部 Agent 消息投递使用 Redis pub/sub（`agent:{ak}` channel）。新设计中：

- **本地 Agent 投递**（第一级）：直接通过本地内存查找 Agent 的 WebSocket Session 投递，不再经过 pub/sub
- **跨实例中转**（第二级/第三级）：新版 source 服务使用 gRPC 替代 pub/sub
- **旧版 source 兼容**（第 15 节）：`agent:{ak}` pub/sub、`publishToAgent`、`subscribeToAgent` 等 Legacy 路径保留，通过 `gateway.legacy-relay.enabled` 开关控制。所有 source 服务升级到新版后关闭开关即可删除

> **注意**：7.5 节描述的是新版路径的目标状态。过渡期内旧版路径与新版路径共存，具体保留清单见第 15.9 节。

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
  │      → 从注册表 HINCRBY ss:internal:user-ws:{userId} SS-C -1 → 0 → HDEL SS-C
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
| 注册表清理 | 无自动清理 | 推送失败时 HINCRBY -1，count ≤ 0 则 HDEL |
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

### 8.5 跨实例消息序号

旧版 `SkillStreamHandler` 的 `seqCounters` 是实例内存计数器，session owner 切换到另一个 SS 实例后序号会回退或重叠，破坏前端排序。

**方案：使用 Redis INCR 做分布式序号生成**

```
Redis Key：ss:stream-seq:{welinkSessionId}
操作：INCR ss:stream-seq:{welinkSessionId}（原子自增，返回新序号）
TTL：与 session 活跃度对齐（如 30 分钟无活动过期）
```

```java
// 替换旧版本地内存计数器
long nextTransportSeq(String sessionId) {
    return redisTemplate.opsForValue()
        .increment("ss:stream-seq:" + sessionId);  // 原子自增
}
```

**好处**：
- 所有 SS 实例共享同一计数器，session owner 切换时序号连续不中断
- Redis INCR 是原子操作，无并发冲突
- 前端 `messageSeq` 排序不会受 owner 切换影响

> 性能影响：每条 StreamMessage 多一次 Redis INCR（~0.1ms）。对于高频 streaming 场景（10-50 条/秒/session），完全可接受。

### 8.6 完整时序：GW-B 宕机场景

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

### 8.7 完整时序：SS-B 宕机 + 用户设备重连场景

```
T+0.0s   SS-B 宕机
T+0.0s   用户 A 在电脑上的 WS 连接断开（该连接在 SS-B 上）
T+0.5s   SS-A（session owner）处理 tool_event，需推送给用户 A
T+0.5s   SS-A 查 ss:internal:user-ws:{userA} → {SS-A, SS-B}
T+0.5s   SS-A 本地推送给用户 A 的手机 WS → 成功 ✓
T+0.5s   SS-A gRPC PushToUser 到 SS-B → UNAVAILABLE（2ms）
T+0.502s SS-A HINCRBY ss:internal:user-ws:{userA} SS-B -1 → 0 → HDEL SS-B
T+1.5s   用户 A 的电脑 WS 重连到 SS-C（通过 LB）
T+1.5s   SS-C HINCRBY ss:internal:user-ws:{userA} SS-C 1
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
| `LegacySkillRelayStrategy` | **保留**（旧版 source 服务的上行路由仍需要）。通过 `gateway.legacy-relay.enabled` 开关控制，所有 source 升级后关闭开关并删除 |
| `RedisMessageBroker.publishToAgent` / `agent:{ak}` pub/sub | **保留**（旧版 source 的 invoke 仍走此路径）。新版 source 改为 gRPC |
| `EventRelayService.subscribeToAgent` pub/sub 订阅 | **保留**（配合上条）。新版 source 的 invoke 走 gRPC 接收 |
| `RedisMessageBroker` 中 `@Deprecated` 方法（`publishToRelay`、`refreshSourceOwner` 等） | **保留**（旧版 Legacy 路由需要）。通过 `gateway.legacy-relay.enabled` 开关控制 |
| Rendezvous Hash 实现 | **保留**（旧版 Legacy 路由的 owner 选择） |

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
| GW Redis Cluster | 3主3从，8GB | Agent 连接注册表、实例注册、pending 队列、invoke 串行化队列 |
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
    dedup-cache-ttl-seconds: 600    # 幂等去重缓存 TTL（与 hard-timeout 对齐，覆盖恢复重投窗口）
  upstream-routing:
    cache-max-size: 100000          # 路由学习表最大条目数
    cache-expire-minutes: 30        # 无活动过期时间
  invoke-queue:
    soft-timeout-seconds: 300           # 软超时（5分钟，tool_event 时刷新）
    hard-timeout-seconds: 600           # 硬超时（10分钟，不可刷新，绝对上限）
    abort-timeout-seconds: 30           # abort 后硬超时缩短为此值
    prev-invoke-grace-seconds: 60      # 硬超时后旧 invoke 信息保留时间（防旧 tool_done/tool_error 误完成新 invoke）
    recovery-scan-interval-ms: 30000    # 超时回收器扫描间隔
    max-queue-size: 100                 # 单 toolSessionId 最大排队数
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
    dedup-cache-ttl-seconds: 600    # 幂等去重缓存 TTL（与 hard-timeout 对齐，覆盖恢复重投窗口）
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
| `invoke.queue.depth` | invoke 队列深度 | 持续 > 10 时告警（Agent 处理慢） |
| `invoke.queue.wait_ms` | invoke 排队等待时间 | P99 > 30s 时告警 |
| `invoke.soft_timeout.count` | 软超时触发次数 | 持续上升时关注（Agent 处理偏慢） |
| `invoke.hard_timeout.count` | 硬超时触发次数 | > 0 时告警（Agent 可能异常） |

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
// value 为 Set<String>：一个 toolSessionId 可能同时被多个 sourceType 使用
private final Cache<String, Set<String>> routingTable = Caffeine.newBuilder()
        .expireAfterAccess(Duration.ofMinutes(30))  // 30 分钟无活动过期
        .maximumSize(100_000)                        // 最大 10 万条路由
        .build();

// 学习：从 source 连接收到 invoke 时调用
void learnRoute(GatewayMessage message, String sourceType) {
    // 从顶层字段学习 welinkSessionId（一对一，welinkSessionId 由 source 侧生成）
    String welinkSessionId = message.getWelinkSessionId();
    if (welinkSessionId != null && !welinkSessionId.isBlank()) {
        routingTable.put("w:" + welinkSessionId, Set.of(sourceType));
    }
    // 从 payload 学习 toolSessionId（一对多，同一 toolSessionId 可能被多个 sourceType 使用）
    String toolSessionId = extractToolSessionIdFromPayload(message);
    if (toolSessionId != null && !toolSessionId.isBlank()) {
        routingTable.asMap().compute(toolSessionId, (key, existing) -> {
            if (existing == null) return Set.of(sourceType);
            Set<String> updated = new HashSet<>(existing);
            updated.add(sourceType);
            return Set.copyOf(updated);
        });
    }
}

// 查询：Agent 回复消息时调用，返回所有关联的 sourceType
Set<String> resolveSourceTypes(GatewayMessage message) {
    // Agent 回复优先用 toolSessionId 查（大部分回复只有 toolSessionId）
    String toolSessionId = message.getToolSessionId();
    if (toolSessionId != null) {
        Set<String> types = routingTable.getIfPresent(toolSessionId);
        if (types != null && !types.isEmpty()) return types;
    }
    // fallback：用 welinkSessionId 查（session_created 场景）
    String welinkSessionId = message.getWelinkSessionId();
    if (welinkSessionId != null) {
        Set<String> types = routingTable.getIfPresent("w:" + welinkSessionId);
        if (types != null && !types.isEmpty()) return types;
    }
    return Set.of();
}
```

**设计要点**：
- `welinkSessionId → Set<sourceType>`：welinkSessionId 由 source 侧生成，一对一，Set 中通常只有一个元素
- `toolSessionId → Set<sourceType>`：toolSessionId 由 Agent 生成，可能被多个 source 服务共用，Set 中可能有多个元素
- 两种 key 是独立的路由条目，不是 welinkSessionId 和 toolSessionId 之间的映射关系
- 路由时向 Set 中所有 sourceType 组各 hash 选一条连接发送

### 13.4 学习时机

所有路由学习都发生在 **source 连接入站时**（invoke 消息），通过观察消息中的字段自动建立。

| invoke 场景 | 顶层 welinkSessionId | payload 中 toolSessionId | 学习结果 |
|------------|---------------------|------------------------|---------|
| create_session（新建会话） | "42" | 无 | `routingTable["w:42"] = {skill-server}` |
| chat（存量会话对话） | 无 | "ts-abc" | `routingTable["ts-abc"] += skill-server` |
| 两者都有 | "42" | "ts-abc" | 两条都学 |
| 另一个 source 也用该 toolSession | 无 | "ts-abc" | `routingTable["ts-abc"] += bot-platform`（Set 变为 {skill-server, bot-platform}） |

**Agent 回复的路由查找**：

| Agent 回复类型 | 携带字段 | 查找过程 |
|--------------|---------|---------|
| session_created | welinkSessionId + toolSessionId | 先查 toolSessionId → 未命中 → 查 "w:" + welinkSessionId → 命中 → 返回 Set ✅ |
| tool_event / tool_done / tool_error | 只有 toolSessionId | 查 toolSessionId → 命中 → 返回 Set（可能含多个 sourceType）✅ |

**多 sourceType 路由**：当 `resolveSourceTypes` 返回多个 sourceType 时，GW 向每个 sourceType 组各 hash 选一条连接发送。各 source 服务收到后通过自身 DB 判断是否属于自己。

**存量会话的完整流程**：
```
1. 用户在已有会话中发消息
2. SS 发 invoke(action=chat, payload={toolSessionId:"ts-abc"})
   → GW 从 payload 学习 routingTable["ts-abc"] += skill-server ✅

3. Agent 回复 tool_event(toolSessionId="ts-abc")
   → GW 查 routingTable["ts-abc"] → {skill-server} → 命中 ✅
   → 向 skill-server 组 hash 选连接发送
```

**多 source 共用 toolSession 的流程**：
```
1. SS 发 invoke(payload={toolSessionId:"ts-abc"})
   → routingTable["ts-abc"] = {skill-server}

2. bot-platform 也发 invoke(payload={toolSessionId:"ts-abc"})
   → routingTable["ts-abc"] = {skill-server, bot-platform}

3. Agent 回复 tool_event(toolSessionId="ts-abc")
   → GW 查 routingTable["ts-abc"] → {skill-server, bot-platform}
   → 向两个组各 hash 选一条连接发送
   → 各 source 服务自行判断是否处理
```

**新建会话的完整流程**：
```
1. SS 发 invoke(action=create_session, welinkSessionId="42")
   → GW 学习 routingTable["w:42"] = {skill-server} ✅

2. Agent 回复 session_created(welinkSessionId="42", toolSessionId="ts-abc")
   → GW 查 routingTable["ts-abc"] → 未命中
   → GW 查 routingTable["w:42"] → {skill-server} → 命中 ✅
   → 路由成功后，从 session_created 消息中顺带学习：
     routingTable["ts-abc"] += skill-server ✅

3. Agent 后续回复 tool_event(toolSessionId="ts-abc")
   → GW 查 routingTable["ts-abc"] → {skill-server} → 命中 ✅
```

### 13.5 路由未命中处理

如果 `routingTable` 未命中（GW 重启丢失内存、TTL 过期等）：

```
Agent 发 tool_event → routingTable 未命中
  → GW 广播到所有 source_type 组（每组 hash 选一条连接）
  → 每个 source 服务自判：这个 toolSessionId 是我的吗？
    → 是 → 处理消息，并在 WS 上回复 route_confirm 确认消息
    → 否 → 在 WS 上回复 route_reject
  → GW 收到 route_confirm → 学习路由 routingTable[toolSessionId] += sourceType
```

**确认协议**：

```json
// source 服务 → GW（确认消息）
{
  "type": "route_confirm",
  "toolSessionId": "ts-abc",
  "sourceType": "skill-server"
}

// source 服务 → GW（拒绝消息）
{
  "type": "route_reject",
  "toolSessionId": "ts-abc"
}
```

**超时与重试**：
- GW 广播后等待 `broadcast-timeout-ms`（200ms）
- 超时未收到任何 `route_confirm` → 写入 pending 队列等待重试
- 收到至少一个 `route_confirm` → 学习路由，后续消息不再广播

**并发保护**：Agent 只连接一个 GW 实例，不存在多个 GW 同时广播同一条 Agent 回复的场景。广播仅指一个 GW 向多个 source_type 组各发一份，source 服务通过消息幂等性（`traceId`）保证不重复处理。

广播是低频事件（仅在 GW 重启或长时间无活动后），正常运行时路由表命中率接近 100%。

### 13.6 gRPC Relay 路由传播

invoke 消息到达 GW-A，但 Agent 在 GW-B 上。GW-A 学到了路由，GW-B 也需要学到，否则 Agent 回复时 GW-B 无法路由。

**解法**：gRPC RelayRequest 携带 `source_type` 和路由 key，GW-B 收到 relay 时也学习路由。

```
SS invoke(toolSessionId="ts-abc") → GW-A 的 WS 连接
  → GW-A 学习：routingTable["ts-abc"] += skill-server
  → GW-A 本地无 Agent → gRPC Relay 到 GW-B
    RelayRequest { source_type="skill-server", routing_keys=["ts-abc"] }
  → GW-B 收到 Relay，也学习：routingTable["ts-abc"] += skill-server ✅
  → GW-B 投递给本地 Agent

Agent 回复 tool_event(toolSessionId="ts-abc") → GW-B
  → GW-B 查 routingTable["ts-abc"] → {skill-server} → 命中 ✅
```

gRPC RelayRequest 增加路由传播字段：
```protobuf
message RelayRequest {
  string trace_id = 1;         // 幂等 ID（复用 GatewayMessage.traceId）
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
  ├─ 查 routingTable["ts-abc"] → {skill-server, bot-platform}（之前 invoke relay 时已学到）
  │
  ├─ 向每个 sourceType 组各 hash 选一条连接发送：
  │    skill-server 组：hash("ts-abc") → SS-A 的 WS 连接 → 发送
  │    bot-platform 组：hash("ts-abc") → BP-A 的 WS 连接 → 发送
  │
  ├─ SS-A 收到后，SS 内部自行处理：
  │    → 通过 toolSessionId 反查 welinkSessionId（sessionService.findByToolSessionId）
  │    → 找到 → 查 session ownership → owner 是 SS-B
  │    → gRPC Relay 给 SS-B → SS-B 处理业务
  │    → SS-B 查用户 WS 注册表 → gRPC PushToUser 给所有在线设备
  │
  └─ BP-A 收到后，bot-platform 内部自行处理：
       → 查本地 session 表，确认 toolSessionId 是否属于自己
       → 是 → 处理
       → 否 → 丢弃
```

## 14. Invoke 串行化与群聊精准投递

### 14.1 问题

某些特殊 Agent（如知识库 bot）共用 toolSessionId：
- 所有单聊共用一个 toolSessionId
- 所有群聊共用一个 toolSessionId
- Agent 实质上只有 2 个 toolSessionId

**群聊场景的难题**：

```
群聊 A（welinkSessionId=A）→ invoke(toolSessionId="ts-group") → Agent
群聊 B（welinkSessionId=B）→ invoke(toolSessionId="ts-group") → Agent
群聊 C（welinkSessionId=C）→ invoke(toolSessionId="ts-group") → Agent

Agent 回复 tool_event(toolSessionId="ts-group")
  → 这条回复属于群聊 A、B 还是 C？
```

需要解决两个问题：
1. **跨 source 的 invoke 串行化**：同一 toolSessionId 的 invoke 可能来自不同 source 服务，必须保证一次只有一个在处理
2. **回复精准投递**：Agent 回复只有 toolSessionId，需要关联到正确的 welinkSessionId

### 14.2 方案：Redis Invoke Queue

在 GW 的 Redis Cluster 中维护 per-toolSessionId 的 invoke 队列，保证串行化，同时记录当前处理的 welinkSessionId。

**Redis 数据结构**：

```
gw:invoke-queue:{toolSessionId}   → Redis List（FIFO 队列，存储待处理的 invoke 信息）
gw:active-invoke:{toolSessionId}  → String（当前正在处理的 invoke 路由信息，无 Redis TTL，靠 softExpiresAt/hardExpiresAt 判定超时）
```

**active-invoke 存储内容**：

```json
{
  "welinkSessionId": "A",
  "sourceType": "skill-server",
  "traceId": "uuid-xxx",
  "status": "active",
  "enqueuedAt": 1711267200000,
  "activatedAt": 1711267200000,
  "softExpiresAt": 1711267500000,
  "hardExpiresAt": 1711267800000,
  "originalInvoke": "<完整的 GatewayMessage JSON>"
}
```

> **originalInvoke**：保存完整的原始 invoke GatewayMessage，用于 Agent 重连后重新投递（14.7 节）。invokeData 入队时就包含此字段，确保队列中的每条 invoke 都可被完整恢复。

**字段说明**：

| 字段 | 说明 |
|------|------|
| welinkSessionId | 当前处理的会话，用于上行精准路由 |
| sourceType | 来源服务类型，用于上行 sourceType 组选择 |
| traceId | 当前 invoke 的唯一标识，用于 CAS compare-and-delete |
| status | `active` / `soft_expired` / `aborting`（用户取消，等待 Agent 停止） |
| enqueuedAt | 入队时间 |
| activatedAt | 激活时间（Lua 脚本激活时设置，超时从此时开始计算） |
| softExpiresAt | 软超时截止（activatedAt + soft-timeout-seconds，tool_event 到达时刷新） |
| hardExpiresAt | 硬超时截止（activatedAt + hard-timeout-seconds，不刷新，绝对上限） |

> **为什么不用 Redis TTL**：key 需要在超时后仍然存在（软超时阶段 active-invoke 不删除），直到硬超时或 tool_done/tool_error 才显式删除。
>
> **为什么不需要 generation 和 tombstone**：采用两阶段超时后，active-invoke 在软超时时不被替换，旧 tool_event 继续正确路由到旧 welinkSessionId。只有硬超时（10 分钟完全无响应）才强制替换，此时旧回复到达的概率极低。详见 14.5 节。

### 14.3 核心流程

所有 Lua 脚本的 KEYS/ARGV 约定统一如下：

```
KEYS[1] = gw:invoke-queue:{toolSessionId}     -- invoke 队列
KEYS[2] = gw:active-invoke:{toolSessionId}    -- 当前活跃 invoke
KEYS[3] = gw:invoke-sessions:{ak}             -- 反向索引（ak → toolSessionId）
```

**入队 + 尝试激活**：

```lua
-- enqueue_and_activate.lua
-- ARGV[1] = invokeData (JSON，softExpiresAt/hardExpiresAt/activatedAt 占位为 0)
-- ARGV[2] = toolSessionId（用于反向索引）
-- ARGV[3] = 当前时间戳（毫秒）
-- ARGV[4] = soft-timeout 毫秒数
-- ARGV[5] = hard-timeout 毫秒数

-- 维护反向索引
redis.call('SADD', KEYS[3], ARGV[2])

-- 入队
redis.call('RPUSH', KEYS[1], ARGV[1])

-- 尝试激活：如果当前无活跃 invoke，出队并激活
if redis.call('EXISTS', KEYS[2]) == 0 then
    local next = redis.call('LPOP', KEYS[1])
    if next then
        -- 激活时重算时间戳（超时从激活时开始，不是入队时）
        local now = tonumber(ARGV[3])
        local activated = string.gsub(next, '"activatedAt":0', '"activatedAt":' .. now)
        activated = string.gsub(activated, '"softExpiresAt":0', '"softExpiresAt":' .. (now + tonumber(ARGV[4])))
        activated = string.gsub(activated, '"hardExpiresAt":0', '"hardExpiresAt":' .. (now + tonumber(ARGV[5])))
        redis.call('SET', KEYS[2], activated)
        return activated
    end
end
return nil  -- 已有活跃 invoke，排队等待
```

**tool_event 到达时**（刷新 softExpiresAt + 获取路由信息）：

```lua
-- refresh_and_resolve.lua
-- KEYS[2] = gw:active-invoke:{toolSessionId}
-- ARGV[1] = 新的 softExpiresAt 时间戳（当前时间 + 软超时秒数）

local current = redis.call('GET', KEYS[2])
if not current then return nil end

-- 刷新软超时（hardExpiresAt 不刷新）
local updated = string.gsub(current, '"softExpiresAt":%d+', '"softExpiresAt":' .. ARGV[1])
-- 如果状态是 soft_expired，恢复为 active（Agent 又活了）
updated = string.gsub(updated, '"status":"soft_expired"', '"status":"active"')
redis.call('SET', KEYS[2], updated)
return current  -- 返回路由信息（welinkSessionId, sourceType）
```

> 实际实现建议用 Redis Hash 替代 JSON String，避免 Lua 中做字符串替换。此处为简化表述使用 JSON。

**tool_done / tool_error 到达时**（统一处理，两者都是 invoke 的终态完成信号）：

```java
// GW 收到 tool_done 或 tool_error 的统一处理逻辑
void handleToolCompletion(String toolSessionId, GatewayMessage message) {
    // 第一步：检查是否有旧 invoke 待收尾（硬超时/abort 后的迟到完成信号）
    String prevJson = redis.get("gw:prev-invoke:" + toolSessionId);
    if (prevJson != null) {
        // 这是旧 invoke 的完成信号，路由到旧 welinkSessionId
        ActiveInvoke prev = parseJson(prevJson);
        routeToSource(prev.welinkSessionId, prev.sourceType, message);
        redis.del("gw:prev-invoke:" + toolSessionId);
        return;  // 不触发 complete_and_next，当前新 invoke 不受影响
    }

    // 第二步：正常路径，先路由再完成队列
    String activeJson = redis.get("gw:active-invoke:" + toolSessionId);
    if (activeJson == null) return;
    ActiveInvoke active = parseJson(activeJson);
    routeToSource(active.welinkSessionId, active.sourceType, message);

    String next = redis.eval(COMPLETE_AND_NEXT_SCRIPT, ..., active.traceId, toolSessionId);
    if (next != null) deliverToAgent(ak, next);
}
```

> **prev-invoke 防护**：硬超时或 abort 超时替换 active-invoke 时，旧 invoke 信息保存到 `gw:prev-invoke:{toolSessionId}`（TTL 60s）。迟到的旧 tool_done **或 tool_error** 都会先命中 prev-invoke，路由到旧 welinkSessionId 但不触发 complete_and_next，保护新 invoke 不被误完成。

**complete_and_next Lua 脚本**（CAS 完成当前 + 激活下一个）：

```lua
-- complete_and_next.lua
-- ARGV[1] = 当前 active-invoke 的 traceId（CAS 条件）
-- ARGV[2] = toolSessionId（用于反向索引清理）
-- ARGV[3] = 当前时间戳（毫秒）
-- ARGV[4] = soft-timeout 毫秒数
-- ARGV[5] = hard-timeout 毫秒数

-- CAS：只有 traceId 匹配才操作（防止和回收器竞态）
local current = redis.call('GET', KEYS[2])
if not current then return nil end
if cjson.decode(current)['traceId'] ~= ARGV[1] then
    return nil  -- traceId 不匹配，不操作
end

redis.call('DEL', KEYS[2])

local next = redis.call('LPOP', KEYS[1])
if next then
    -- 激活时重算时间戳
    local now = tonumber(ARGV[3])
    local activated = string.gsub(next, '"activatedAt":0', '"activatedAt":' .. now)
    activated = string.gsub(activated, '"softExpiresAt":0', '"softExpiresAt":' .. (now + tonumber(ARGV[4])))
    activated = string.gsub(activated, '"hardExpiresAt":0', '"hardExpiresAt":' .. (now + tonumber(ARGV[5])))
    redis.call('SET', KEYS[2], activated)
    return activated
end

-- 队列为空，清理反向索引
redis.call('SREM', KEYS[3], ARGV[2])
return nil
```

**abort_session 到达时**（CAS 标记 aborting + 缩短硬超时）：

```lua
-- abort_invoke.lua
-- KEYS[2] = gw:active-invoke:{toolSessionId}
-- ARGV[1] = 期望的 traceId
-- ARGV[2] = 新的 hardExpiresAt（now + abort-timeout-seconds）

local current = redis.call('GET', KEYS[2])
if not current then return nil end
if cjson.decode(current)['traceId'] ~= ARGV[1] then return nil end

-- 标记 aborting + 缩短硬超时
local updated = string.gsub(current, '"status":"[^"]*"', '"status":"aborting"')
updated = string.gsub(updated, '"hardExpiresAt":%d+', '"hardExpiresAt":' .. ARGV[2])
redis.call('SET', KEYS[2], updated)
return 'OK'
```

> `aborting` 状态下 tool_event 仍正常路由（Agent 可能还有残余输出），由 source 服务自行决定是否展示给用户。GW 不在 aborting 状态过滤消息——这与 GW 作为纯路由层的定位一致。

### 14.4 完整时序：群聊精准投递

```
T+0.0s   群聊 A 发消息 → SS 发 invoke(toolSessionId="ts-group", welinkSessionId=A)
T+0.0s   GW-B 收到 → enqueue_and_activate：
           active-invoke 不存在 → 出队 → SET active-invoke = {welinkSessionId:A, sourceType:skill-server}
           → 投递给 Agent ✅

T+0.5s   群聊 B 发消息 → SS 发 invoke(toolSessionId="ts-group", welinkSessionId=B)
T+0.5s   GW-B 收到 → enqueue_and_activate：
           active-invoke 存在 → 入队等待 ⏳

T+1.0s   Agent 回复 tool_event(toolSessionId="ts-group")
T+1.0s   GW-B → refresh_and_resolve → active-invoke = {welinkSessionId:A, sourceType:skill-server}
           → 精准路由到 skill-server 组 → SS 投递给群聊 A ✅

T+3.0s   Agent 回复 tool_done(toolSessionId="ts-group")
T+3.0s   GW-B → complete_and_next：
           DEL active-invoke → LPOP 队列 → 取出群聊 B 的 invoke
           → SET active-invoke = {welinkSessionId:B, sourceType:skill-server}
           → 投递给 Agent ✅

T+4.0s   Agent 回复 tool_event(toolSessionId="ts-group")
T+4.0s   GW-B → refresh_and_resolve → active-invoke = {welinkSessionId:B}
           → 精准路由给群聊 B ✅
```

### 14.5 两阶段超时与自动回收

采用**两阶段超时**，在软超时阶段不替换 active-invoke，旧 tool_event 继续正确路由，从根本上避免新旧回复混淆。

**两阶段模型**：

| 阶段 | 触发条件 | 动作 | active-invoke 状态 |
|------|---------|------|-------------------|
| 正常 | tool_event 持续到达 | 刷新 softExpiresAt | `active` |
| 软超时 | softExpiresAt 过期（默认 5 分钟无 tool_event） | 发送 `invoke_timeout` 通知 source 服务 | `soft_expired`（不删除、不替换） |
| 恢复 | 软超时后 tool_event 又到达 | 刷新 softExpiresAt，状态恢复为 active | `active` |
| 用户取消 | source 发送 abort_session | 转发 abort 给 Agent，缩短 hardExpiresAt 为 now+30s | `aborting`（不删除、不替换） |
| 硬超时 | hardExpiresAt 过期（正常 10 分钟 / abort 后 30 秒） | 强制删除 + 发送 `invoke_abandoned` + 激活下一个 | 删除 |
| 正常完成 | tool_done / tool_error 到达 | CAS 删除 + 激活下一个 | 删除 |

**关键设计**：
- **软超时只通知不替换**：active-invoke 保持原值，旧 tool_event 到达时仍然路由到正确的 welinkSessionId
- **hardExpiresAt 不可刷新**：作为绝对上限，防止 Agent 无限占用队列
- **软超时后 Agent 恢复**：如果 Agent 只是暂时卡顿，tool_event 到达后自动恢复为 active 状态

> **为什么不需要 generation 和 tombstone**：active-invoke 在软超时时不被替换，旧 tool_event 路由目标没有变化，不存在新旧混淆问题。只有硬超时（10 分钟完全无响应）才强制替换，此时 Agent 确实已死，旧回复到达的概率极低。

**invoke_timeout 通知**：

```json
{
  "type": "invoke_timeout",
  "toolSessionId": "ts-group",
  "welinkSessionId": "A",
  "sourceType": "skill-server",
  "traceId": "uuid-xxx"
}
```

source 服务收到后可以：
- 通知用户"AI 处理较慢，请稍候"
- 如果用户选择取消，发送 `abort_session` invoke（GW 转发给 Agent 并将 active-invoke status 标记为 `aborting`，同时将 hardExpiresAt 缩短为 `now + abort-timeout-seconds`（默认 30 秒）。Agent 停止处理后发送 tool_done/tool_error 触发正常 complete_and_next。如果 Agent 在 30 秒内未响应，硬超时自动兜底清除队列）
- 如果选择等待，不操作（Agent 恢复后自动继续）
- 记录日志用于运维排查

**硬超时终态通知**：硬超时（10 分钟）触发时，GW 向 source 服务发送 `invoke_abandoned` 通知，表示该 invoke 已被强制放弃，区别于软超时的 `invoke_timeout`（仅警告）。source 服务收到后应通知用户"请求已超时，请重试"。

**定时回收器**（每个 GW 实例运行，间隔 30 秒）：

```java
@Scheduled(fixedDelay = 30_000)
void recoverExpiredInvokeQueues() {
    long now = System.currentTimeMillis();
    for (String ak : localAgentRegistry.allAks()) {
        Set<String> toolSessionIds = redis.smembers("gw:invoke-sessions:" + ak);
        for (String tsId : toolSessionIds) {
            String activeJson = redis.get("gw:active-invoke:" + tsId);

            if (activeJson == null) {
                // active-invoke 不存在 → 尝试激活队列中的下一个
                String next = atomicDequeueAndActivate(tsId, ak);
                if (next != null) {
                    deliverToAgent(ak, next);
                } else {
                    redis.srem("gw:invoke-sessions:" + ak, tsId);
                }
                continue;
            }

            ActiveInvoke active = parseJson(activeJson);

            // 硬超时：强制删除 + 激活下一个 + 终态通知
            if (now > active.hardExpiresAt) {
                String result = redis.eval(HARD_TIMEOUT_SCRIPT,
                    List.of("gw:invoke-queue:" + tsId,
                            "gw:active-invoke:" + tsId,
                            "gw:invoke-sessions:" + ak),
                    active.traceId, tsId);

                if (result == null) continue;  // CAS 失败，已被 tool_done/tool_error 处理

                // 发送终态通知（区别于软超时的 invoke_timeout 警告）
                notifyInvokeAbandoned(active.welinkSessionId, active.sourceType,
                    active.traceId, tsId);

                if (!"EMPTY".equals(result)) {
                    deliverToAgent(ak, result);
                }
                continue;
            }

            // 软超时：通知 source 服务，但不替换 active-invoke
            if (now > active.softExpiresAt && "active".equals(active.status)) {
                String softResult = redis.eval(SOFT_TIMEOUT_SCRIPT,
                    List.of("gw:active-invoke:" + tsId),
                    active.traceId, String.valueOf(now));

                // 只有 Lua 确认超时成功才发通知（防止和 tool_event 刷新竞态）
                if ("OK".equals(softResult)) {
                    notifyInvokeTimeout(active.welinkSessionId, active.sourceType,
                        active.traceId, tsId);
                }
            }
        }
    }
}
```

**软超时 Lua 脚本**（CAS 修改状态，不删除）：

```lua
-- soft_timeout.lua
-- KEYS[1] = gw:active-invoke:{toolSessionId}
-- ARGV[1] = 期望的 traceId
-- ARGV[2] = 当前时间戳（毫秒）

local current = redis.call('GET', KEYS[1])
if not current then return nil end

local decoded = cjson.decode(current)

-- 三重检查：traceId 匹配 + 状态仍为 active + softExpiresAt 确实已过期
if decoded['traceId'] ~= ARGV[1] then return nil end
if decoded['status'] ~= 'active' then return nil end
if tonumber(decoded['softExpiresAt']) > tonumber(ARGV[2]) then return nil end

-- 全部通过，改状态
local updated = string.gsub(current, '"status":"active"', '"status":"soft_expired"')
redis.call('SET', KEYS[1], updated)
return 'OK'
```

**硬超时 Lua 脚本**（CAS 删除 + 激活下一个）：

```lua
-- hard_timeout.lua
-- KEYS[1] = gw:invoke-queue:{toolSessionId}
-- KEYS[2] = gw:active-invoke:{toolSessionId}
-- KEYS[3] = gw:invoke-sessions:{ak}
-- ARGV[1] = 期望的 traceId
-- ARGV[2] = toolSessionId
-- ARGV[3] = prev-invoke grace TTL seconds（如 60）
-- ARGV[4] = 当前时间戳（毫秒）
-- ARGV[5] = soft-timeout 毫秒数
-- ARGV[6] = hard-timeout 毫秒数

local current = redis.call('GET', KEYS[2])
if not current then return nil end
if cjson.decode(current)['traceId'] ~= ARGV[1] then return nil end

-- 保存旧 invoke 信息到 prev-invoke（供迟到 tool_done/tool_error 识别）
redis.call('SET', 'gw:prev-invoke:' .. ARGV[2], current, 'EX', tonumber(ARGV[3]))

redis.call('DEL', KEYS[2])

local next = redis.call('LPOP', KEYS[1])
if next then
    -- 激活时重算时间戳
    local now = tonumber(ARGV[4])
    local activated = string.gsub(next, '"activatedAt":0', '"activatedAt":' .. now)
    activated = string.gsub(activated, '"softExpiresAt":0', '"softExpiresAt":' .. (now + tonumber(ARGV[5])))
    activated = string.gsub(activated, '"hardExpiresAt":0', '"hardExpiresAt":' .. (now + tonumber(ARGV[6])))
    redis.call('SET', KEYS[2], activated)
    return activated
end

redis.call('SREM', KEYS[3], ARGV[2])
return 'EMPTY'
```

> 回收器只在 Agent 所在的 GW 实例运行。正常处理时 tool_done/tool_error 驱动出队，回收器只处理超时的异常情况。

### 14.6 反向索引：ak → toolSessionId

Agent 重连到新的 GW 实例后，该实例只知道 `ak`，需要找到所有有 active-invoke 或排队 invoke 的 `toolSessionId`。

**Redis Set**：`gw:invoke-sessions:{ak}` → Set\<toolSessionId\>

- invoke 入队时：`SADD gw:invoke-sessions:{ak} {toolSessionId}`
- 队列和 active-invoke 都为空时：`SREM gw:invoke-sessions:{ak} {toolSessionId}`
- Agent 重连时：`SMEMBERS gw:invoke-sessions:{ak}` → 获取所有需要恢复的 toolSessionId

> 反向索引的 SADD/SREM 逻辑已统一在 14.3 节的 Lua 脚本中（KEYS[3] = `gw:invoke-sessions:{ak}`）：enqueue_and_activate 入队时 SADD，complete_and_next 和 hard_timeout 队列清空时 SREM。

### 14.7 Agent 宕机/重连场景（完整）

```
T+0.0s   Agent（ak=A1）在 GW-B 上处理群聊 A 的 invoke 时宕机
           Redis 状态：
             gw:active-invoke:ts-group = {welinkSessionId:A, sourceType:skill-server}
             gw:invoke-queue:ts-group = [群聊 B 的 invoke]
             gw:invoke-sessions:A1 = {"ts-group"}

T+3.0s   Agent 重连到 GW-C
T+3.0s   GW-C 的 Agent 注册回调触发恢复流程：
           1. SMEMBERS gw:invoke-sessions:A1 → {"ts-group"}
           2. 检查 gw:active-invoke:ts-group → 存在
              → 重新投递给 Agent（续处理群聊 A 的请求）
           3. Agent 完成 → tool_done → complete_and_next
              → 出队群聊 B 的 invoke → 投递给 Agent

T+5m     Agent 5 分钟无 tool_event（软超时）：
           回收器检测到 softExpiresAt 过期
           → status 改为 soft_expired（active-invoke 不删除、不替换）
           → 向群聊 A 的 source 服务发送 invoke_timeout 通知
           → source 服务通知用户"AI 处理较慢，请稍候"
           → 如果 Agent 随后恢复发送 tool_event → status 恢复为 active，继续正常处理

T+10m    Agent 10 分钟完全无响应（硬超时）：
           回收器检测到 hardExpiresAt 过期
           → CAS 删除 active-invoke
           → 向 source 服务发送 invoke_abandoned 终态通知
           → 出队群聊 B 的 invoke → 投递给 Agent
           → source 服务通知用户"请求已超时，请重试"
```

### 14.8 与上行路由的配合

invoke 串行化解决了 `toolSessionId → welinkSessionId` 的精准关联，上行路由流程变为：

```
Agent 回复 tool_event(toolSessionId="ts-group") → GW-B 收到
  │
  ├─ 查 gw:active-invoke:ts-group → {welinkSessionId:A, sourceType:skill-server}
  │    → 精确知道 sourceType 和 welinkSessionId ✅
  │
  ├─ 在 skill-server 组内 hash("A") 选连接 → SS-A
  │    → 消息中注入 welinkSessionId=A
  │    → SS-A 精准投递给群聊 A ✅
  │
  └─ routingTable 学习表作为 fallback
       → 仅在 active-invoke 未命中时使用（如非共用 toolSessionId 的普通 Agent）
```

**路由优先级**：
1. `gw:active-invoke:{toolSessionId}` → 精确路由（共用 toolSessionId 场景）
2. `routingTable[toolSessionId]` → 学习表路由（普通 Agent 场景）
3. 广播到所有 sourceType 组 → fallback

### 14.9 两阶段超时如何避免代际混淆

**问题回顾**：超时后如果立即替换 active-invoke，旧 invoke 的迟到 tool_event 会被错误关联到新 invoke。Agent 上行消息不携带 traceId 或 generation，无法在协议层区分新旧回复。

**两阶段超时的解法**：不立即替换，从根本上避免混淆。

```
时间线（Agent 处理慢、不是死）：

T+0      invoke A 激活，active-invoke = {welinkSessionId:A, status:active}
T+5m     软超时 → status = soft_expired → 通知 source → active-invoke 不变
T+5m+30s Agent 终于回复 tool_event → active-invoke 还是 A → 正确路由 ✅
         → softExpiresAt 刷新 → status 恢复为 active
T+6m     Agent 回复 tool_done → complete_and_next → 激活 invoke B
T+6m+1s  invoke B 的 tool_event → active-invoke 是 B → 正确路由 ✅
```

**对比旧方案（立即替换）的问题**：

```
T+5m     超时 → 立即删除 A → 激活 B → active-invoke = {welinkSessionId:B}
T+5m+30s Agent 的旧 tool_event 到达 → active-invoke 是 B → 错误路由到 B ❌
```

**硬超时后的已知限制**：

硬超时（10 分钟）后强制替换 active-invoke，理论上存在旧回复被错误路由的可能。但：
- 10 分钟完全无 tool_event，Agent 极大概率已死
- 死亡的 Agent 不会再发送 tool_event
- 作为已知的极端边界 trade-off，影响可忽略

> **不需要 generation、tombstone、或 Agent 协议变更。** 两阶段超时通过"不替换"而非"识别旧消息"来解决问题，是最简洁的方案。

### 14.10 新协议消息类型

本设计引入了以下新的 GatewayMessage 类型，需要同步更新协议模型：

| 消息类型 | 方向 | 用途 | 关键字段 |
|---------|------|------|---------|
| `invoke_timeout` | GW → source 服务 | 软超时警告（5 分钟无响应，invoke 仍在处理中） | toolSessionId, welinkSessionId, sourceType, traceId |
| `invoke_abandoned` | GW → source 服务 | 硬超时终态（10 分钟无响应，invoke 已被强制放弃） | toolSessionId, welinkSessionId, sourceType, traceId |
| `route_confirm` | source 服务 → GW | 上行路由广播后确认 | toolSessionId, sourceType |
| `route_reject` | source 服务 → GW | 上行路由广播后拒绝 | toolSessionId |

**取消 invoke**：source 服务可在软超时后发送 `invoke`（action=`abort_session`），复用现有 `GatewayActions.ABORT_SESSION`。GW 处理流程：
1. 转发 abort 给 Agent
2. 将 active-invoke status 改为 `aborting`，hardExpiresAt 缩短为 `now + abort-timeout-seconds`（默认 30s）
3. Agent 收到 abort 后停止处理，发送 tool_done/tool_error → 正常 complete_and_next
4. 如果 Agent 在 30 秒内未响应 → 硬超时自动兜底（回收器正常流程）

**GW 不在收到 abort_session 时主动替换 active-invoke**——与两阶段超时设计原则一致。abort 只是缩短了硬超时窗口，最终仍由 tool_done/tool_error 或硬超时触发队列推进。

> 不需要新增 GatewayMessage 字段。代际隔离通过两阶段超时解决（14.9 节），不依赖 Agent 行为。
>
> 以上新消息类型需要在 `GatewayMessage.java` 的 `Types` 常量类中注册，并更新协议文档。

## 15. 旧版 Source 服务兼容

### 15.1 问题

新版 GW 改用一致性哈希 + gRPC mesh 路由，但其他接入 GW 的 source 服务可能仍使用旧版路由协议。新版 GW 需要同时支持两种路由策略。

### 15.2 版本检测

通过 WS 握手时的字段判断 source 版本：

| 握手字段 | 旧版 source | 新版 source |
|---------|-----------|-----------|
| `token` | ✅ | ✅ |
| `source` | ✅（如 "skill-server"） | ✅ |
| `instanceId` | ❌ 无此字段 | ✅（如 "ss-pod-1"） |

**判断规则**：
- `instanceId` 存在且非空 → 新版，使用 Mesh 策略
- `instanceId` 不存在 → 旧版，使用 Legacy 策略

```java
// SkillWebSocketHandler.beforeHandshake
String instanceId = authNode.path("instanceId").asText(null);
if (instanceId != null && !instanceId.isBlank()) {
    attributes.put(INSTANCE_ID_ATTR, instanceId);
    attributes.put(STRATEGY_ATTR, "mesh");
} else {
    attributes.put(STRATEGY_ATTR, "legacy");
}
```

### 15.3 连接管理：新旧并存

同一个 `source_type` 下可以同时有新旧版本的连接：

```
sourceConnections["skill-server"]:
  ├─ SS-pod-1（新版，strategy=mesh，有 instanceId）
  ├─ SS-pod-2（新版，strategy=mesh，有 instanceId）
  └─ SS-legacy-1（旧版，strategy=legacy，无 instanceId）
```

### 15.4 上行路由策略选择

Agent 回复消息需要路由到 source 服务时，GW 按以下逻辑选择策略：

```
resolveSourceTypes(message) → {skill-server}
  ↓
查看 skill-server 组下有哪些连接类型：
  ├─ 全是 mesh 连接 → 一致性哈希选连接，直接发送
  ├─ 全是 legacy 连接 → Legacy 策略（Rendezvous Hash + Redis relay）
  └─ 混合（新旧并存）→ 优先 mesh 连接 + 一致性哈希
```

> **混合场景说明**：新旧版本 source 服务混合部署是过渡期暂态。建议同一 source_type 下的所有实例统一升级，避免长期混合。

### 15.5 Legacy 策略保留

为旧版 source 服务保留完整的 Legacy 路由路径：

**Redis Key（保留）**：
```
gw:source:owner:{source}:{instanceId}  → "alive"，TTL=30s
gw:source:owners:{source}              → Set，活跃 owner
gw:relay:{instanceId}                  → Redis pub/sub，跨 GW 中继
gw:agent:source:{ak}                   → ak → source 绑定
```

**Legacy 路由流程（保留）**：
```
1. 本地有旧版 source 连接？→ sendViaDefaultLink → 成功则结束
2. Rendezvous Hash 选择 owner GW 实例
3. 本实例是 owner → 本地重试
4. 远程 owner → Redis pub/sub gw:relay:{ownerId} → 远程 GW 发送
```

**Owner 心跳（保留）**：
```
每 10s 刷新 gw:source:owner:{source}:{instanceId}，TTL=30s
连接断开时清理 owner 状态
```

### 15.6 下行路由（source → agent）兼容

旧版 source 发 invoke 时的处理与现有逻辑一致：

```
旧版 source 发 invoke(source="skill-server", ak="xxx", ...)
  → GW 验证 source 与 bound source 一致
  → 绑定 gw:agent:source:{ak} = "skill-server"（Legacy 路由需要）
  → 同时学习 routingTable（新版路由需要）
  → 发布到 agent:{ak} Redis pub/sub → Agent 所在的 GW 实例投递
```

**注意**：旧版 source 的 invoke 仍然走 Redis pub/sub `agent:{ak}` 路径到达 Agent，不走 gRPC mesh。但 GW 同时学习 routingTable，以便 Agent 回复时能用新版路由查找 sourceType。

### 15.7 Agent 回复路由的兼容处理

Agent 回复时，GW 通过 routingTable 或 active-invoke 确定 sourceType 后，还需判断该 sourceType 下的连接策略：

```
Agent 回复 tool_event(toolSessionId="ts-abc")
  → 查 active-invoke 或 routingTable → sourceType = "skill-server"
  → 查 skill-server 组下连接的 strategy：
    ├─ mesh 连接存在 → 一致性哈希选连接 → 直接发送（新版路径）
    └─ 只有 legacy 连接 → Legacy 路由（Rendezvous Hash + Redis relay）
```

### 15.8 兼容矩阵

| 场景 | 下行（source → agent） | 上行（agent → source） |
|------|----------------------|----------------------|
| 新版 SS + 新版 GW | 一致性哈希 → gRPC mesh | routingTable → 一致性哈希 |
| 旧版 source + 新版 GW | Redis pub/sub `agent:{ak}` | Legacy Rendezvous Hash + Redis relay |
| 新旧混合（同 sourceType） | 按连接类型分别处理 | 优先 mesh，fallback legacy |

### 15.9 需要保留的旧版代码

| 代码 | 保留原因 |
|------|---------|
| `RedisMessageBroker.publishToAgent` / `agent:{ak}` pub/sub | 旧版 source 的 invoke 仍走此路径 |
| `RedisMessageBroker.refreshSourceOwner` / Owner 心跳 | 旧版 source 的 Legacy 路由需要 |
| `RedisMessageBroker.publishToRelay` / `gw:relay:{instanceId}` | 旧版 source 的跨 GW 中继 |
| `RedisMessageBroker.bindAgentSource` / `gw:agent:source:{ak}` | 旧版 source 的 ak → source 解析 |
| Rendezvous Hash 实现 | Legacy 路由的 owner 选择 |

> **清理时机**：当所有 source 服务升级到新版后，上述 Legacy 代码和 Redis Key 可以移除。建议通过配置开关控制：`gateway.legacy-relay.enabled: true`（默认开启，全部升级后关闭）。
