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
| 服务内注册表 | MySQL 持久化 + Redis 缓存 | 第6节 |
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

## 6. 服务内资源注册表：MySQL + Redis 缓存

每个服务内部维护"资源在哪个实例上"的映射。这是纯内部状态，不暴露给其他服务。

### 6.1 GW 侧：Agent 连接注册表

**MySQL 表 `gw_agent_route`**：

| 字段 | 类型 | 说明 |
|------|------|------|
| ak | VARCHAR(64) PK | Agent Access Key |
| instance_id | VARCHAR(128) | GW 实例 ID |
| status | VARCHAR(16) | CONNECTED / DISCONNECTED |
| connected_at | DATETIME | 连接时间 |
| updated_at | DATETIME | 最后更新时间 |

**Redis 缓存**：`gw:internal:agent:{ak} → instanceId`

- Agent 注册：写 MySQL + 写 Redis（TTL 与心跳周期关联）
- Agent 心跳：每次心跳刷新 Redis TTL；MySQL `updated_at` 每 5 分钟批量更新一次（避免 150 万 Agent 心跳产生的高频写入压力）
- Agent 断连：删 Redis + 更新 MySQL status 为 DISCONNECTED

**命名约定**：`gw:internal:` 前缀明确这是 GW 内部命名空间。

**索引**：`instance_id` 字段需要索引，用于实例宕机后按 instance_id 批量清理/接管记录。

### 6.2 SS 侧：Session 所有权注册表

**MySQL 表**：复用现有 `session_route` 表（`source_instance` 字段记录所有权）

**Redis 缓存**：`ss:internal:session:{welinkSessionId} → instanceId`

- Session 创建：写 MySQL + 写 Redis
- Session 活跃：消息处理时刷新 Redis TTL
- Session 关闭：删 Redis + 更新 MySQL status 为 CLOSED

### 6.3 SS 侧：Miniapp WebSocket 连接注册表

一个 session 可能有多个 miniapp 通过 WebSocket 连接到不同的 SS 实例。需要额外的注册表跟踪 miniapp 连接分布。

**Redis 缓存**：`ss:internal:miniapp-ws:{welinkSessionId}` → Redis Set，成员为 instanceId

- miniapp WS 连接建立：`SADD` 当前 instanceId
- miniapp WS 连接断开：`SREM` 当前 instanceId
- 查询所有 miniapp WS 实例：`SMEMBERS`

> 不需要 MySQL 持久化——miniapp WS 是瞬态连接，重连后自动重新注册。

### 6.4 缓存一致性

- 写入路径：先写 MySQL，成功后写 Redis
- 读取路径：先读 Redis → 未命中则查 MySQL 并回填 Redis
- MySQL 为 source of truth，Redis 缓存允许短暂不一致
- 实例启动时从 MySQL 预热 Redis 缓存

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
  // 单条消息转发（Agent 投递、Session 处理）
  rpc Relay(RelayRequest) returns (RelayResponse);

  // 批量转发（Streaming 场景优化）
  rpc RelayStream(stream RelayRequest) returns (stream RelayResponse);

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

### 8.2 投递流程（多 Owner：SS 推送 miniapp）

SS 处理完消息后需要推送给多个 miniapp WebSocket 连接，分布在不同 SS 实例上：

```
SS-A 处理完 tool_event，需要推送给 session-X 的所有 miniapp
  │
  ├─ 查注册表：ss:internal:miniapp-ws:{sessionId} → {SS-A, SS-C}
  │
  ├─ SS-A 本地有 miniapp WS → 直接推送 ✓
  │
  ├─ SS-C 有 miniapp WS → gRPC 调用 SS-C.Relay(sessionId, message)
  │    → 成功 → ✓
  │    → 失败（SS-C 宕机）→ 毫秒级感知
  │      → SS-C 上的 miniapp WS 也已断连
  │      → 从注册表 SREM SS-C
  │      → miniapp 重连到其他 SS 实例后自动重新注册
  │      → miniapp 重连后通过 REST 拉取最新状态补偿
  │
  └─ 推送完成
```

> **注意**：miniapp 推送失败不需要写 pending 队列。因为 miniapp WS 断连意味着 miniapp 端已经感知到断连，重连后会主动通过 HTTP REST 拉取最新状态。pending 机制只用于 Agent 侧（Agent 不会主动拉取）。

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

### 8.6 完整时序：SS-B 宕机 + miniapp 重连场景

```
T+0.0s   SS-B 宕机
T+0.0s   SS-B 上的 miniapp-X WS 断连
T+0.0s   SS-A 处理 tool_event，需推送给 miniapp-X
T+0.0s   SS-A 查 miniapp WS 注册表 → {SS-A: 无, SS-B: 有}
T+0.0s   SS-A gRPC 调用 SS-B → UNAVAILABLE（2ms）
T+0.002s SS-A 从注册表 SREM SS-B，记录推送失败
T+1.0s   miniapp-X 重连到 SS-C（通过 LB）
T+1.0s   SS-C 在 miniapp WS 注册表 SADD SS-C
T+1.0s   miniapp-X 通过 REST 拉取最新消息 → 获取到之前的 tool_event
```

用户感知：miniapp 短暂断连 ~1 秒后自动恢复，数据通过 REST 拉取补偿，无丢失。

## 9. 需要删除/重构的现有代码

### GW 侧（ai-gateway）

| 现有代码 | 处理方式 |
|---------|---------|
| `conn:ak:{ak}` Redis key | 删除。GW 内部改用 `gw:internal:agent:{ak}` |
| `GatewayInstanceRegistry` | 重构。从写共享 Redis 改为写 GW 内部 Redis（`gw:internal:instance:{id}`）+ 暴露 HTTP 接口 |
| `SkillRelayService.routeCache` | 删除。GW 不再维护 SS 实例路由缓存 |
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

## 10. 基础设施依赖

| 组件 | 规格 | 用途 |
|------|------|------|
| GW Redis Cluster | 3主3从，8GB | Agent 注册表缓存、实例注册、pending 队列 |
| SS Redis Cluster | 3主3从，8GB | Session 注册表缓存、miniapp WS 注册表、实例注册、pending 队列 |
| GW MySQL | 已有 `ai_gateway` DB | `gw_agent_route` 表 |
| SS MySQL | 已有 `skill_db` DB | 复用 `session_route` 表 |

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
  agent-route:
    mysql-batch-update-interval-ms: 300000  # MySQL updated_at 批量更新间隔（5分钟）
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

## 13. 待讨论

以下话题需要进一步细化设计：

1. **SS 消息同步到多个 miniapp 的完整流程** —— 消息广播、增量同步、断线补偿的具体策略
2. **GW 上行精准路由** —— Agent 回复的消息如何精准投递到正确的 source 服务（SS/bot-platform），而不是广播到所有 source 组
