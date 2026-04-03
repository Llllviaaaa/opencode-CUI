# 服务间路由解耦设计 v3

> 日期：2026-03-25
> 状态：Ready
> 分支：route-redesign-0321
> 前置文档：2026-03-24-route-decoupling-design.md（已废弃，由本文档替代）

## 1. 背景与问题

当前 skill-server（SS）与 ai-gateway（GW）之间的路由寻址存在双向耦合：

1. **SS 读取 GW 内部状态**：SS 通过 Redis `conn:ak:{ak}` 查询 Agent 在哪个 GW 实例上
2. **GW 维护 SS 实例路由**：GW 的 `SkillRelayService` 维护 `routeCache`（sessionId → SS WS 连接映射）

**目标**：服务间实例寻址由服务内部自行处理，SS 不感知 GW 实例，GW 不感知 SS 实例。

**关键约束**：
- welinkSessionId ↔ toolSessionId 在任意时刻一一对应
- 一个 toolSessionId 只对应一个 source 服务的一个 welinkSessionId
- **toolSessionId 可重建**：session 失效后 SS 会清空旧 toolSessionId，通过 `create_session` 获取新 toolSessionId。同一 welinkSessionId 在其生命周期内可能先后绑定不同的 toolSessionId
- GW 和 SS 各有独立的 Redis Cluster（不共享）
- GW 可连接多种 source 服务（skill-server、bot-platform 等）
- 需要兼容旧版 source 服务

## 2. 设计原则

| 原则 | 说明 |
|------|------|
| 服务间无实例级状态共享 | 发送方不知道接收方有几个实例、资源在哪个实例上 |
| 路由决策归接收方 | 发送方只管投递到某条连接，接收方自行判断是否处理或内部中转 |
| 服务内部自治 | 每个服务通过自己的 Redis pub/sub 解决实例间中转 |
| 用户零感知 | 扩缩容、故障场景下消息不静默丢失、可观测、可补偿 |

## 3. 整体架构

```
                    ┌──────────────────────────────────────┐
                    │           Skill Server                │
                    │                                       │
                    │  SS-1 ←─ Redis pub/sub ─→ SS-2        │
                    │   ↑    (ss:relay:{id})     ↑          │
                    │   │    (user-stream:{uid})  │          │
                    │  用户WS                   用户WS       │
                    └──┬──────────────────────────────────┬─┘
                       │      WebSocket 网状长连接          │
                       │      (一致性哈希选连接)            │
                    ┌──┴──────────────────────────────────┴─┐
                    │           AI Gateway                   │
                    │                                        │
                    │  GW-1 ←─ Redis pub/sub ─→ GW-2        │
                    │          (gw:relay:{id})               │
                    │                                        │
                    │  ← WS: skill-server 连接组              │
                    │  ← WS: bot-platform 连接组              │
                    └──┬──────────────────────────────────┬─┘
                       │  WebSocket                        │
                    Agent-A                             Agent-B
```

**分层**：

| 层次 | 职责 | 章节 |
|------|------|------|
| 服务间路由 | WebSocket 网状连接 + 一致性哈希 | 第4节 |
| 服务发现 | HTTP 接口 + 配置中心降级 | 第5节 |
| 服务内注册表 | GW 纯 Redis，SS MySQL + Redis | 第6节 |
| 服务内中转 | Redis pub/sub 定向 channel | 第7节 |
| GW 上行路由 | 自动学习表 | 第8节 |
| 旧版兼容 | Legacy 策略保留 | 第9节 |

## 4. 服务间路由：一致性哈希

### 4.1 哈希策略

SS 与 GW 之间保持网状 WebSocket 长连接。发送方通过一致性哈希选择连接，不感知对端实例身份。

- **下行（SS → GW）**：`hash(ak)` → 选中某条 GW 连接
- **上行（GW → SS）**：确定 sourceType 后，在该组内 `hash(routingKey)` 选连接。routingKey 优先用 `welinkSessionId`，没有则用 `toolSessionId`

### 4.2 一致性哈希环

- 每条 WebSocket 连接作为哈希环上的虚拟节点（用连接 ID 做 key）
- 每个连接映射 150 个虚拟节点（可配置），确保负载均匀分布
- 连接建立 → 加入环，连接断开 → 从环移除
- 扩缩容时只影响环上相邻的一小段映射，大部分路由不变

### 4.3 GW 侧连接分组

GW 按 `source_type` 分组管理上游连接（如 `skill-server`、`bot-platform`）。上行路由时按组进行，每组独立维护一致性哈希环。

## 5. 服务发现：HTTP 接口 + 配置中心降级

### 5.1 主路径：HTTP 接口

GW 暴露内部 HTTP 接口，返回所有 GW 实例的 WebSocket 地址列表：

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

SS 定期调用该接口（通过 GW 负载均衡器地址），对比本地已知列表，新增实例建连、消失实例断连。

**实例列表组装**：每个 GW 实例使用 GW 自己的 Redis 维护内部实例注册（`gw:internal:instance:{id} → wsUrl`，TTL 30s，心跳刷新）。HTTP 接口查 GW 自己的 Redis 返回完整列表。

### 5.2 降级路径

- HTTP 接口不可用 → 配置中心读取静态实例列表
- 配置中心也不可用 → 维持现有连接不变

### 5.3 刷新策略

- 定期刷新间隔：10s（可配置）
- 接口调用超时：3s
- 连续 3 次失败后降级

## 6. 服务内注册表

每个服务内部维护"资源在哪个实例上"的映射。GW 侧纯 Redis，SS 侧 MySQL + Redis 双层（6.2 节说明）。

### 6.1 GW 侧：Agent 连接注册表

**Redis**：`gw:internal:agent:{ak} → instanceId`（TTL 与心跳周期关联）

- Agent 注册：写 Redis
- Agent 心跳：刷新 TTL
- Agent 断连：删 Redis
- GW 宕机：TTL 过期自动清理；Agent 重连后重新注册

### 6.2 SS 侧：Session 所有权注册表

**双层存储**：MySQL `session_route` 表（持久化，保留现有生命周期语义）+ Redis 缓存（加速查询）。

**MySQL `session_route`**（保留现有表，不删除）：
- 承担启动接管（`takeoverActiveRoutes`）、优雅关闭（`closeAllByInstance`）、按 toolSessionId 反查 welinkSessionId 等职责
- 作为 ownership 的事实来源

**Redis 缓存**：`ss:internal:session:{welinkSessionId} → instanceId`（TTL 与 session 活跃度关联）
- 读取路径：先查 Redis → 未命中则查 MySQL 并回填
- 写入路径：写 MySQL 成功后同步写 Redis
- Session 创建：写 MySQL + Redis
- Session 活跃：刷新 Redis TTL + 定期更新 MySQL `updated_at`
- Session 关闭：删 Redis + 更新 MySQL status
- SS 宕机恢复（三层机制）：
  - **Redis TTL**：自动过期清理
  - **启动接管**：按 ak 懒接管（`takeoverActiveRoutes(ak)`），在 **`agent_online` 消息处理中触发**：收到 `agent_online(ak)` → 调用 `takeoverActiveRoutes(ak)` 接管该 ak 下所有 ACTIVE 路由 → 再广播上线状态。触发点选择 `agent_online` 而非 WS 握手（握手时不知道将处理哪些 ak）或 session 创建（`createRoute` 已覆盖）。不做全量扫描，避免启动风暴
  - **消息驱动接管**：新消息到达时通过 `ensureRouteOwnership`（MySQL 查询 + 乐观锁 UPDATE）接管。见 7.2 节失联 owner 探活流程

> **为什么不纯 Redis**：`SessionRouteService` 承担的生命周期职责（启动接管、优雅关闭、僵尸清理、toolSessionId 反查）依赖 MySQL 的持久性和查询能力。纯 Redis 方案需要重新实现这些语义且容易出现孤儿 ownership。保留 MySQL 作为事实来源，Redis 作为热路径缓存，是最稳妥的过渡方案。

### 6.3 SS 侧：用户 WebSocket 连接注册表

一个用户可多设备在线，每台设备一条 WS 连接，可能分布在不同 SS 实例上。

**Redis Hash（引用计数）**：`ss:internal:user-ws:{userId}` → Hash\<instanceId, connectionCount\>

- WS 连接建立：`HINCRBY {instanceId} 1`
- WS 连接断开：`HINCRBY {instanceId} -1`，若 ≤ 0 则 `HDEL`
- 查询在线实例：`HKEYS`

> **为什么用 Hash 引用计数而不是 Set**：同一用户可能在同一 SS 实例上有多条 WS 连接（多标签页），Set 会压扁导致断一条就删整个实例。

**宕机清理**：
- 被动：下次推送到该实例失败时 HDEL（自愈）
- 主动：SS 实例启动时扫描 `ss:internal:user-ws:*`，HDEL 自己的 instanceId（清除上次宕机残留）

### 6.4 SS 侧：实例心跳

**Redis**：`ss:internal:instance:{instanceId} → "alive"`（TTL 30s）

- SS 启动时写入，定时每 10s 刷新
- `@PreDestroy` 时删除
- 宕机则 TTL 30s 后自然过期
- 用途：7.2 节失联 owner 探活的判断依据之一

> GW 侧对应 key `gw:internal:instance:{id}`（5.1 节已定义），SS 侧此处补齐。

## 7. 服务内中转：Redis pub/sub

### 7.1 GW 侧中转

每个 GW 实例订阅自己的 channel：`gw:relay:{instanceId}`

```
下行 invoke 到达 GW-A，Agent 在 GW-B：
  → 查注册表 gw:internal:agent:{ak} → GW-B
  → publish 到 gw:relay:gw-b，消息体含 sourceType 和 routingKeys
  → GW-B 收到 → 学习路由（第 8 节）→ 本地投递给 Agent
```

**relay 消息格式**：

```json
{
  "type": "relay",
  "sourceType": "skill-server",
  "routingKeys": ["ts-abc", "w:42"],
  "originalMessage": "<GatewayMessage JSON>"
}
```

### 7.2 SS 侧中转

每个 SS 实例订阅自己的 channel：`ss:relay:{instanceId}`

```
上行消息到达 SS-A，session owner 是 SS-B：
  → 查 Redis ss:internal:session:{welinkSessionId} → SS-B
  → publish 到 ss:relay:ss-b
  → SS-B 收到 → 处理业务逻辑
```

**失联 owner 探活与接管**：

如果 SS-B 已永久宕机，Redis 中 `ss:internal:session:{welinkSessionId}` 可能已过期（TTL），但 MySQL `session_route` 仍记录 owner 为 SS-B。SS-A 查 Redis 未命中 → 查 MySQL → 得到 SS-B → publish 到 `ss:relay:ss-b` → 黑洞。

解法：三重判断后强制接管。

```
SS-A 需要路由消息给 session-X：
  1. 查 Redis → 命中 → 直接 publish ✅
  2. Redis 未命中 → 查 MySQL → owner = SS-B
  3. 检查 SS-B 是否存活（三重判断）：
     a. 检查 SS 实例心跳 key ss:internal:instance:ss-b 是否存在（见 6.4 节）
     b. 检查 MySQL session_route.updated_at 是否超过阈值（如 2 分钟）
     c. PUBLISH ss:relay:ss-b 返回订阅者数（仅作辅助参考）
  4. 综合判断 SS-B 已失联 → SS-A 强制接管（乐观锁）：
     → UPDATE session_route SET source_instance = 'SS-A', updated_at = NOW()
       WHERE welinkSessionId = X AND source_instance = 'SS-B'
     → affected_rows = 0 → 已被其他实例抢占，放弃
     → affected_rows = 1 → 接管成功 → SET ss:internal:session:X = SS-A（回填 Redis）
     → 本地处理当前消息 ✅
```

> **并发安全**：UPDATE 带 `WHERE source_instance = 'SS-B'` 乐观锁条件，两个实例同时接管时只有一个 affected_rows=1，另一个自动放弃。
>
> **PUBLISH 返回值注意**：在 Redis Cluster 下 `PUBLISH` 返回值只反映当前节点的订阅者数，可能不精确，因此仅作辅助参考信号，不单独作为判断依据。
>
> **消息处理**：接管流程中 SS-A 持有的是触发探活的**真实业务消息**。接管成功后直接本地处理该消息，不需要重放。如果接管失败（affected_rows=0，被其他实例抢占），SS-A 重新查询 MySQL/Redis 获取最新 `source_instance`（即抢占成功者），再 publish 消息到该实例的 `ss:relay:{winnerId}`。

### 7.3 SS 多设备推送

**复用现有 `user-stream:{userId}` pub/sub**（不新增机制）：

```
SS-B（session owner）处理完消息后：
  → publishToUser(userId, streamMessage)
  → 所有订阅了该 channel 的 SS 实例收到
  → 各实例查本地 userSubscribers[userId] → 推送给本地 WS 连接
```

订阅管理：用户首条 WS 连接建立时 subscribe，末条断开时 unsubscribe。与现有 `SkillStreamHandler` 逻辑一致。

### 7.4 三级 Fallback（GW 侧）

```
GW-A 收到消息，需要投递给 Agent-X：
  │
  ├─ 第一级：本地检查
  │    Agent-X 在本实例？→ 直接投递 ✓
  │
  ├─ 第二级：查注册表 + 定向 pub/sub
  │    Redis 查 gw:internal:agent:{ak} → GW-B
  │    publish 到 gw:relay:gw-b
  │    → 结束 ✓
  │
  └─ 第三级：注册表未命中（Agent 可能离线）
       → 写入 pending 队列 gw:pending:{ak}
       → Agent 重连后检查 pending 并补投
```

SS 侧同理。

### 7.5 Pending 队列（仅 GW 侧）

- Redis List：`gw:pending:{ak}`
- TTL：60 秒
- Agent 重连后 drain 并补投

> **SS 侧不设 pending 队列**。SS 中转失败时由探活接管机制（7.2 节）保证消息不丢——触发方持有原始消息，接管成功后直接本地处理，无需额外缓冲。

### 7.6 宕机场景分析

| 场景 | 处理 | 用户感知 |
|------|------|---------|
| GW-B 宕机 | pub/sub 消息丢失，但 Agent 也断连 → 重连后新消息走新路径 | 消息延迟数秒 |
| SS-B 宕机 | pub/sub 消息丢失，但 WS 也断连 → 重连 + Snapshot 恢复 | 短暂断连后自动恢复 |
| GW 扩容 | 新实例注册 → 其他实例发现 → 新连接自然分配 | 无感知 |
| SS 扩容 | 同上 | 无感知 |
| 滚动升级 | @PreDestroy 清理注册表 → 资源断连重连 | 无感知 |

> **为什么 pub/sub 丢消息在这里可接受**：资源（Agent/Session/WS）和传输 channel 绑定在同一个实例。实例死了，资源和 channel 同时不可达。丢的消息本来也送不到目标。

### 7.7 跨实例消息序号

替换旧版 `SkillStreamHandler` 的本地内存 `seqCounters`：

```
Redis Key：ss:stream-seq:{welinkSessionId}
操作：INCR（原子自增）
TTL：与 session 活跃度对齐
```

所有 SS 实例共享计数器，session owner 切换时序号不中断。

### 7.8 现有 pub/sub 通道处理

| 现有通道 | 处理方式 |
|---------|---------|
| `agent:{ak}` pub/sub | 新版 source 废弃（改为 `gw:relay:{instanceId}`）；旧版 source 保留（第 9 节） |
| `user-stream:{userId}` pub/sub | 保留，继续用于多设备推送 |
| `gw:relay:{instanceId}` pub/sub | 旧版 Legacy 跨 GW 中继保留（第 9 节） |

> 过渡期新旧路径共存，通过 `gateway.legacy-relay.enabled` 开关控制。

## 8. GW 上行路由：自动学习

### 8.1 问题

GW 连接了多种 source 服务，Agent 回复时 GW 需要判断发给哪个 source_type。Agent 不携带 source_type，GW 通过观察入站消息自动学习。

### 8.2 路由学习表

```java
// GW 本地内存，Caffeine Cache
private final Cache<String, String> routingTable = Caffeine.newBuilder()
        .expireAfterAccess(Duration.ofMinutes(30))
        .maximumSize(100_000)
        .build();
```

**学习（source 连接入站时）**：

```java
void learnRoute(GatewayMessage message, String sourceType) {
    String welinkSessionId = message.getWelinkSessionId();
    if (welinkSessionId != null && !welinkSessionId.isBlank()) {
        routingTable.put("w:" + welinkSessionId, sourceType);
    }
    String toolSessionId = extractToolSessionIdFromPayload(message);
    if (toolSessionId != null && !toolSessionId.isBlank()) {
        routingTable.put(toolSessionId, sourceType);
    }
}
```

**查询（Agent 回复时）**：

```java
String resolveSourceType(GatewayMessage message) {
    String toolSessionId = message.getToolSessionId();
    if (toolSessionId != null) {
        String st = routingTable.getIfPresent(toolSessionId);
        if (st != null) return st;
    }
    String welinkSessionId = message.getWelinkSessionId();
    if (welinkSessionId != null) {
        String st = routingTable.getIfPresent("w:" + welinkSessionId);
        if (st != null) return st;
    }
    return null;
}
```

### 8.3 学习时机

| invoke 场景 | 顶层 welinkSessionId | payload 中 toolSessionId | 学习结果 |
|------------|---------------------|------------------------|---------|
| create_session | "42" | 无 | `routingTable["w:42"] = skill-server` |
| chat（存量会话） | 无 | "ts-abc" | `routingTable["ts-abc"] = skill-server` |

### 8.4 路由传播

invoke 到达 GW-A，但 Agent 在 GW-B。GW-A 学到了路由，需要传播给 GW-B。

通过 relay 消息的 `sourceType` 和 `routingKeys` 字段传播：

```
SS invoke → GW-A（学习路由）→ publish gw:relay:gw-b（携带 sourceType + routingKeys）
  → GW-B 收到 relay → 学习 routingTable → 投递给 Agent

Agent 回复 tool_event → GW-B 查 routingTable → 命中 ✅
```

### 8.5 路由未命中处理

```
routingTable 未命中（GW 重启丢失内存、TTL 过期等）：
  → GW 广播到所有 source_type 组（每组 hash 选一条连接，组为空则跳过不参与超时等待）
  → 每个 source 服务自判是否属于自己
    → 是 → 处理 + 回复 route_confirm
    → 否 → 回复 route_reject
  → GW 从 route_confirm 学习路由
```

**确认协议（所有 source 服务必须实现的最小契约）**：

任何接入 GW 的 source 服务都必须实现以下行为：
1. 收到含 `toolSessionId` 的上行消息后，能判断该 toolSessionId 是否属于自己（实现方式由 source 自行决定：DB 查询、内存缓存、Redis 等）
2. 属于自己 → 处理消息 + 回复 `route_confirm`
3. 不属于自己 → 回复 `route_reject`

```json
// source → GW
{ "type": "route_confirm", "toolSessionId": "ts-abc", "sourceType": "skill-server" }
{ "type": "route_reject", "toolSessionId": "ts-abc" }
```

> **对 source 服务的唯一要求**：能根据 toolSessionId 判断消息归属。SS 通过 `findByToolSessionId` 实现，其他 source 可用自己的方式实现，不要求统一的内部机制。

广播超时 200ms 未收到任何 confirm → **丢弃该消息并向 Agent 回复错误**（`{ "type": "tool_error", "error": "unknown_tool_session", "toolSessionId": "..." }`），触发 Agent 端中断或重建会话。

> **坚决不写 pending 队列**：`gw:pending:{ak}` 是下行通道（消息投递给 Agent），如果把上行消息（Agent → source）写入 pending，Agent 重连时会收到自己发出的消息，导致协议死循环。上行路由失败 = 没有 source 服务认领该 toolSessionId = 会话已失效或 source 全部离线，正确做法是通知 Agent。

正常运行时路由表命中率接近 100%，广播 + 丢弃是极低频的异常路径。

### 8.6 toolSessionId 换代（重建场景）

同一 welinkSessionId 可能先后绑定不同的 toolSessionId（session 失效 → 清空旧 toolSessionId → create_session 获取新 toolSessionId）。GW 的 routingTable 需要处理旧条目失效。

**自然失效**：旧 toolSessionId 的路由条目在 Caffeine 的 `expireAfterAccess` 30 分钟后自动淘汰。session 重建后不会再有消息携带旧 toolSessionId，条目自然无人访问直至过期。

**主动失效**：当 GW 收到同一 welinkSessionId 的新 `create_session` invoke 时，如果 routingTable 中存在该 welinkSessionId 之前关联的旧 toolSessionId 条目，可主动清理。实现方式：

```java
void learnRoute(GatewayMessage message, String sourceType) {
    String welinkSessionId = message.getWelinkSessionId();
    String newToolSessionId = extractToolSessionIdFromPayload(message);

    if (welinkSessionId != null && !welinkSessionId.isBlank()) {
        routingTable.put("w:" + welinkSessionId, sourceType);
    }
    if (newToolSessionId != null && !newToolSessionId.isBlank()) {
        routingTable.put(newToolSessionId, sourceType);
    }
}
```

> **为什么旧条目不需要显式删除**：旧 toolSessionId 的路由指向的 sourceType 和新 toolSessionId 一样（同一 welinkSessionId、同一 source 服务），即使旧条目暂存也不会导致错误路由。唯一的副作用是缓存空间浪费（30 分钟后自动回收）。

**Agent 回复使用旧 toolSessionId 的风险**：session 重建时 SS 会先清空旧 toolSessionId，Agent 收到新 create_session 后使用新 toolSessionId。在清空到重建之间的窗口（通常秒级），如果 Agent 还在用旧 toolSessionId 发 tool_event，GW 的 routingTable 仍持有旧条目 → 路由到正确的 sourceType → source 服务通过 `findByToolSessionId` 反查失败（SS 已清空）。

**此时 SS 不应静默丢弃，应通过 GW 向 Agent 回复明确错误**：

```json
{ "type": "tool_error", "toolSessionId": "旧-ts-id", "error": "invalid_session" }
```

Agent 收到 `invalid_session` 后应中断当前操作、清空本地执行状态，等待新 `create_session` 的 toolSessionId 完成重建。这避免了 Agent 陷入"发了消息但永远收不到回复"的卡死状态。

### 8.7 完整上行路由流程

```
Agent 发 tool_event(toolSessionId="ts-abc") → GW-B 收到
  │
  ├─ 查 routingTable["ts-abc"] → "skill-server" → 命中
  │
  ├─ 在 skill-server 组内 hash("ts-abc") 选连接 → SS-A
  │    → 发送给 SS-A
  │
  └─ SS-A 收到后，SS 内部自行处理：
       → 通过 toolSessionId 反查 welinkSessionId
       → 查 session ownership → owner 是 SS-B
       → publish 到 ss:relay:ss-b → SS-B 处理业务
       → SS-B publishToUser → 推送给所有在线设备
```

### 8.8 新协议消息类型

| 消息类型 | 方向 | 用途 |
|---------|------|------|
| `route_confirm` | source → GW | 上行路由广播后确认 |
| `route_reject` | source → GW | 上行路由广播后拒绝 |

## 9. 旧版 Source 服务兼容

### 9.1 版本检测

通过 WS 握手字段判断：

- `instanceId` 存在 → 新版，Mesh 策略
- `instanceId` 不存在 → 旧版，Legacy 策略

### 9.2 上行路由策略选择

```
确定 sourceType 后，查该组下连接类型：
  ├─ 有 mesh 连接 → 一致性哈希选连接
  └─ 只有 legacy 连接 → Rendezvous Hash + Redis relay
```

### 9.3 Legacy 路径保留

| 保留代码 | 原因 |
|---------|------|
| `agent:{ak}` pub/sub | 旧版 source 的 invoke 路径 |
| `gw:relay:{instanceId}` pub/sub | 旧版跨 GW 中继 |
| `gw:source:owner` + 心跳 | 旧版 owner 管理 |
| `gw:agent:source:{ak}` | 旧版 ak → source 绑定 |
| Rendezvous Hash | 旧版 owner 选择 |
| `LegacySkillRelayStrategy` | 旧版上行路由 |

通过 `gateway.legacy-relay.enabled: true` 开关控制，所有 source 升级后关闭并删除。

### 9.4 兼容矩阵

| 场景 | 下行（source → agent） | 上行（agent → source） | SS 服务发现 |
|------|----------------------|----------------------|------------|
| 新版 SS + 新版 GW | 一致性哈希 → `gw:relay:{instanceId}` | routingTable → 一致性哈希 | HTTP 接口 + 配置中心降级 |
| 旧版 source + 新版 GW | `agent:{ak}` pub/sub | Rendezvous Hash + Redis relay | — |
| **新版 SS + 旧版 GW** | 种子 WS URL 直连 + `agent:{ak}` pub/sub | 旧版 routeCache 路由 | **保留 `scanGatewayInstances` + 种子 URL 降级** |
| **新版 GW + 旧版 SS** | 旧版 SS 用 `conn:ak` → GW 同时支持新旧 key | Rendezvous Hash + Redis relay | 旧版 SS 扫描 `gw:instance:*` → GW 同时写新旧 key |

### 9.5 滚动升级路径

**推荐升级顺序**：先升级 GW，再升级 SS。

**阶段 1：升级 GW（SS 仍为旧版）**
- 新版 GW 同时写 `gw:internal:instance:{id}`（新）和 `gw:instance:{id}`（旧，兼容旧 SS 的 scanGatewayInstances）
- 新版 GW 同时写 `gw:internal:agent:{ak}`（新）和 `conn:ak:{ak}`（旧，兼容旧 SS 的 getConnAk）
- 旧版 SS 正常工作，无感知

**阶段 2：升级 SS（GW 已为新版）**
- 新版 SS 优先用 HTTP 接口发现 GW 实例
- HTTP 接口失败 → 降级到 `scanGatewayInstances`（扫描 `gw:instance:*`，兼容旧 GW）
- 再失败 → 配置中心
- 再失败 → 种子 WS URL 直连

**阶段 3：全部升级完成**
- 关闭 `gateway.legacy-relay.enabled` 开关
- 停止写旧 Redis key（`gw:instance:*`、`conn:ak:*`）
- 删除旧版代码

### 9.6 SS 侧保留的旧版兼容代码

| 代码 | 保留原因 | 清理时机 |
|------|---------|---------|
| `scanGatewayInstances()`（扫描 `gw:instance:*`） | 新版 SS 连旧版 GW 时降级路径 | 所有 GW 升级后 |
| `getConnAk()`（读 `conn:ak:{ak}`） | 新版 SS 连旧版 GW 时降级路径 | 所有 GW 升级后 |
| 种子 WS URL 直连 | 最终兜底 | 可长期保留 |

## 10. 需要删除/重构的现有代码

### GW 侧

| 现有代码 | 处理方式 |
|---------|---------|
| `conn:ak:{ak}` Redis key | 删除。改用 `gw:internal:agent:{ak}` |
| `GatewayInstanceRegistry` | 重构。写 GW 内部 Redis + HTTP 接口 |
| `SkillRelayService.routeCache` | 重构。改为 routingKey → sourceType 学习表 |
| `SkillRelayService` Mesh 路由逻辑 | 重构。一致性哈希 + Redis pub/sub 中转 |
| `LegacySkillRelayStrategy` | **保留**（旧版兼容，开关控制） |
| `agent:{ak}` pub/sub | **保留**（旧版兼容） |
| Legacy Redis keys + Rendezvous Hash | **保留**（旧版兼容） |

### SS 侧

| 现有代码 | 处理方式 |
|---------|---------|
| `RedisMessageBroker.getConnAk()` | **保留**（新版 SS 连旧版 GW 的降级路径）。全部 GW 升级后删除 |
| `RedisMessageBroker.scanGatewayInstances()` | **保留**（同上）。全部 GW 升级后删除 |
| `GatewayRelayService` 中查 `conn:ak` | **保留作为降级**。优先用一致性哈希，降级到 `conn:ak`（连旧版 GW 时） |
| `GatewayDiscoveryService` | 重构。优先 HTTP 接口 → 降级到 `scanGatewayInstances` → 配置中心 → 种子 URL |
| `user-stream:{userId}` pub/sub | **保留**（多设备推送） |
| `SkillStreamHandler.seqCounters` | 重构。改为 Redis INCR |

## 11. 配置参数

### GW 侧

```yaml
gateway:
  instance-id: ${GATEWAY_INSTANCE_ID:${HOSTNAME:gateway-local}}
  internal-api:
    enabled: true
  hash-ring:
    virtual-nodes: 150
  relay:
    pending-ttl-seconds: 60
  upstream-routing:
    cache-max-size: 100000
    cache-expire-minutes: 30
    broadcast-timeout-ms: 200
  legacy-relay:
    enabled: true                    # 旧版兼容开关
```

### SS 侧

```yaml
skill:
  instance-id: ${HOSTNAME:skill-server-local}
  gateway:
    discovery-url: ${GATEWAY_LB_URL:http://localhost:8081}/internal/instances
    discovery-interval-ms: 10000
    discovery-timeout-ms: 3000
    discovery-fail-threshold: 3
    ws-url: ${SKILL_GATEWAY_WS_URL:ws://localhost:8081/ws/skill}  # 种子 URL（复用现有配置键，HTTP 接口不可用时降级使用）
  hash-ring:
    virtual-nodes: 150
  relay:
    owner-dead-threshold-seconds: 120  # 接管探活的 updated_at 超时阈值
```

## 12. 监控指标

| 指标 | 说明 | 报警阈值 |
|------|------|---------|
| `relay.local.rate` | 本地命中比例 | < 10% 告警 |
| `relay.pubsub.rate` | pub/sub 中转比例 | 基线监控 |
| `relay.pending.count` | Pending 队列消息数 | 持续 > 0 关注 |
| `relay.pending.expired` | Pending 过期数 | > 0 告警 |
| `hashring.node.count` | 哈希环节点数 | 偏离预期告警 |
| `upstream.routing.hit.rate` | 上行路由命中率 | < 90% 告警 |
| `upstream.routing.broadcast.rate` | 广播 fallback 比例 | > 10% 告警 |
| `discovery.fallback.active` | 是否降级到配置中心 | true 告警 |
| `push.user.count` | 多设备推送频率 | 基线监控 |
| `session.takeover.count` | Session ownership 接管次数 | > 0 说明有实例故障 |
| `session.takeover.conflict.count` | 接管乐观锁冲突次数 | 持续 > 0 关注 |
| `owner.probe.dead.count` | 探活发现失联 owner 次数 | 基线监控 |

## 13. 基础设施依赖

| 组件 | 规格 | 用途 |
|------|------|------|
| GW Redis Cluster | 3主3从，8GB | Agent 注册表、实例注册、pending 队列、relay pub/sub |
| SS Redis Cluster | 3主3从，8GB | Session 注册表缓存、用户 WS 注册表、实例注册、relay pub/sub、序号生成、user-stream pub/sub |
| SS MySQL | 已有 `skill_db` DB | `session_route` 表（ownership 事实来源，保留现有生命周期语义） |

**注意**：
- GW 和 SS 的 Redis Cluster 独立，不共享
- 服务间唯一通信通道是 WebSocket 长连接
- 服务内中转通过各自的 Redis pub/sub
- SS 的 session ownership 保留 MySQL 持久化（Redis 作为缓存层）
- 无需 gRPC、Kafka 或其他额外中间件
