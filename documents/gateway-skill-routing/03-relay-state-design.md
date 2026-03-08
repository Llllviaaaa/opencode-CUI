# Relay 状态与 Redis 设计

> 版本：1.0  
> 日期：2026-03-07

## 1. 设计目标

本设计用于支撑以下事实：

- `skill-server` 通过 ALB 连接到的是“某个 gateway”，而不是整个 gateway 集群
- 某个 agent 上行事件可能进入一个本地没有 skill 长连接的 gateway
- 同一 `sessionId` 的上行事件最好回到同一 skill 实例

因此需要一组 Redis 状态来支撑：

- owner gateway 活跃性判断
- session 级 sticky owner 路由
- gateway 间定向 relay

## 2. Redis 契约总表

| 名称 | 类型 | 示例 | 写入方 | 读取方 | 说明 |
| --- | --- | --- | --- | --- | --- |
| `agent:{agentId}` | Pub/Sub Channel | `agent:42` | 任意 connected gateway | agent owner gateway | 现有下行 invoke 路由 |
| `session:{sessionId}` | Pub/Sub Channel | `session:1001` | skill-server | skill-server | 现有前端推流广播 |
| `gw:relay:{instanceId}` | Pub/Sub Channel | `gw:relay:gw-a` | non-owner gateway | 指定 owner gateway | 新增上行定向中继 |
| `gw:skill:owner:{instanceId}` | TTL Key | `gw:skill:owner:gw-a=alive` | skill owner gateway | 所有 gateway | 标记 owner 活跃 |
| `gw:session:skill-owner:{sessionId}` | TTL Key | `gw:session:skill-owner:1001=gw-a` | gateway | gateway | session 粘性 owner |
| `gw:skill:owners` | Set | `{gw-a,gw-b}` | skill owner gateway | gateway | 活跃 owner 候选集合 |

## 3. Gateway 本地状态

Redis 之外，gateway 本地还需要维护两组内存态：

| 名称 | 结构 | 用途 |
| --- | --- | --- |
| Local Skill Links | `linkId -> WebSocketSession` | 保存本地 skill 长连接 |
| Local Session Bindings | `sessionId -> linkId` | 确保同一 session 回到同一条本地 skill link |

这两组状态只存在于 gateway 本地，不写入 Redis。

## 4. Owner 注册与心跳

### 4.1 注册条件

某个 gateway 只要本地存在至少一条可用的 skill 长连接，就被视为 skill owner gateway。

### 4.2 注册动作

当 gateway 成为 skill owner 时，必须：

1. `SETEX gw:skill:owner:{instanceId} alive {ownerTtl}`
2. `SADD gw:skill:owners {instanceId}`
3. `SUBSCRIBE gw:relay:{instanceId}`

### 4.3 心跳刷新

gateway 按固定周期刷新 owner TTL：

- 刷新周期：`gateway.skill-relay.owner-heartbeat-interval-seconds`
- TTL：`gateway.skill-relay.owner-ttl-seconds`

要求：

- `owner-heartbeat-interval-seconds < owner-ttl-seconds`
- 推荐比值为 `1 : 3`

例如：

- 心跳间隔 10 秒
- TTL 30 秒

### 4.4 退订条件

当 gateway 本地最后一条 skill 长连接断开时，必须：

1. `DEL gw:skill:owner:{instanceId}`
2. `SREM gw:skill:owners {instanceId}`
3. 保留或延迟取消 `SUBSCRIBE gw:relay:{instanceId}` 均可，但推荐取消订阅

## 5. Session Sticky Owner 映射

### 5.1 写入规则

gateway 在以下场景写入：

- 收到某个 session 的上行事件，且需要选择 owner gateway

写入 key：

```text
gw:session:skill-owner:{sessionId}
```

value 为：

```text
{instanceId}
```

TTL 为：

```text
gateway.skill-relay.session-route-ttl-seconds
```

### 5.2 读取规则

agent owner gateway 处理上行事件时：

1. 本地无 `sessionId -> linkId` 绑定
2. 读取 `gw:session:skill-owner:{sessionId}`
3. 再校验 `gw:skill:owner:{instanceId}` 是否仍存在
4. 存在则直接 relay
5. 不存在则重新选主并覆盖 sticky 映射

### 5.3 TTL 选择

`session-route-ttl-seconds` 应覆盖一次正常 AI 会话的活跃时间，但不能长到让陈旧 owner 长期残留。文档基线建议：

- 默认 1800 秒（30 分钟）

如果会话在 gateway 或 skill 侧被显式关闭，可以主动删除该映射。

## 6. Owner 选择算法

### 6.1 候选集获取

读取 `gw:skill:owners` 后，必须过滤掉没有活跃 TTL key 的实例：

```text
gw:skill:owner:{instanceId}
```

### 6.2 算法固定

owner 选择算法固定为：

- Rendezvous Hashing

输入：

- `sessionId`
- 活跃 owner 实例列表

输出：

- 一个目标 `instanceId`

### 6.3 为什么不是广播

不采用“所有 gateway 都订阅同一个 relay channel，命中者消费，其余丢弃”的原因：

- 广播流量浪费
- 难以稳定回到相同 owner
- 更难判断到底有没有 gateway 真正接收

因此 `gw:relay:{instanceId}` 必须是定向 channel。

## 7. 本地多 skill link 规则

同一个 gateway 本地可能因为 ALB 接入而持有多条 skill 长连接。此时：

1. 每条长连接有唯一 `linkId`
2. gateway 为某个 `sessionId` 建立本地 `sessionId -> linkId` 绑定
3. 本地命中时，直接把 `GatewayMessage` 发给对应 link
4. 不命中时，只能使用一个默认 active link 做无 session 事件的兜底

默认 active link 仅用于：

- `agent_online`
- `agent_offline`
- 其他无 `sessionId` 的内部事件

## 8. Relay Payload

### 8.1 格式

`gw:relay:{instanceId}` 中的 payload 为完整 `GatewayMessage` JSON。

不新增：

- 新的 relay DTO
- 新的 envelope 层

### 8.2 必填字段

对于 session 级 relay，payload 至少需要包含：

- `type`
- `agentId`
- `sessionId`
- `event` 或 `error` 或 `usage` 或 `toolSessionId`

## 9. Pub/Sub 语义边界

### 9.1 语义

本方案明确接受 Redis Pub/Sub 的语义：

- at-most-once

这意味着：

- relay 期间如果目标 owner gateway 掉线，消息可能直接丢失
- Redis 不会帮我们排队、重试、确认消费

### 9.2 为什么仍然接受

因为本方案中：

- WebSocket 长连接仍是主通道
- Redis relay 只是补跳，不是主消息总线
- 现有 skill 侧已有 sequence gap 检测、buffer 和 reconnect 设计

### 9.3 边界说明

Redis relay 层不负责：

- 消息确认
- 重试队列
- 顺序回放
- exactly-once

这些能力如果未来需要，应作为单独 phase 设计。
