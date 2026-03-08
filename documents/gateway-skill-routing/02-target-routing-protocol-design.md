# 目标路由协议设计

> 版本：1.0  
> 日期：2026-03-07

## 1. 目标拓扑

目标拓扑固定为：

```text
skill-server -> ALB -> gateway
```

并配合 gateway 集群内部 Redis 定向中继：

```text
agent owner gateway -> Redis Pub/Sub -> skill owner gateway -> local skill link -> skill-server
```

## 2. 角色定义

| 角色 | 定义 |
| --- | --- |
| Skill Server | 业务服务，负责会话管理、消息持久化、推流 |
| ALB | `skill-server` 接入 `gateway` 集群的单一 WebSocket 入口 |
| Agent Owner Gateway | 持有某个 PCAgent WebSocket 的 gateway |
| Skill Owner Gateway | 本地持有至少一条 skill 长连接，能把上行事件送入 skill 集群的 gateway |
| Local Skill Link | 某个 gateway 本地的一条 `skill-server -> gateway` WebSocket 长连接 |

## 3. 建链流程

### 3.1 skill-server 启动

1. `skill-server` 读取 `skill.gateway.ws-url`
2. `skill-server` 使用内部 token 向 ALB 建立 WebSocket 长连接
3. ALB 将连接分配给某个健康的 gateway target
4. 被命中的 gateway 记录一条新的 local skill link
5. 该 gateway 刷新自身 owner 心跳，并保证自己订阅了 `gw:relay:{instanceId}`

### 3.2 gateway 本地多连接

同一个 gateway 本地可以同时持有多条 skill 长连接，但不能把它们视为完全等价连接。规则如下：

1. 每条 local skill link 都有唯一 `linkId`
2. gateway 维护本地内存映射：`sessionId -> linkId`
3. 同一 `sessionId` 的上行事件必须优先命中其绑定的 `linkId`
4. 没有 `sessionId` 绑定时，gateway 才能把事件交给该 gateway 的默认 active link

## 4. 下行路由

下行指 skill 侧发出 invoke，最终到达 PCAgent。

### 4.1 标准路径

```text
Miniapp
  -> Skill Server
  -> local skill link
  -> connected gateway
  -> PUBLISH agent:{agentId}
  -> agent owner gateway
  -> PCAgent
```

### 4.2 具体规则

1. `SkillMessageController` 或 `SkillSessionController` 构建 `GatewayMessage.invoke(...)`
2. `skill-server` 通过当前长连接把 invoke 发给命中的 gateway
3. 该 gateway 在发送 invoke 之前或同时，建立本地 `sessionId -> linkId` 绑定
4. 该 gateway 将 invoke 发布到 `agent:{agentId}`
5. 持有 agent 连接的 gateway 命中并下发给 PCAgent

### 4.3 为什么下行不需要新增 Redis 契约

因为当前代码已经具备：

- `agent:{agentId}` 定向路由
- agent owner gateway 命中消费

所以目标方案沿用既有 channel，不另起新协议。

## 5. 上行路由

上行指 PCAgent 的 `tool_event`、`tool_done`、`tool_error`、`session_created`、`agent_online`、`agent_offline` 等事件，最终进入 skill 集群。

### 5.1 标准优先级

gateway 处理上行事件时必须按以下顺序选路：

1. 优先查本地 `sessionId -> linkId`
2. 本地无绑定时，查 Redis `gw:session:skill-owner:{sessionId}`
3. Redis sticky 映射无效时，从 `gw:skill:owners` 中选择活跃 owner gateway
4. 选定 owner 后，写回 `gw:session:skill-owner:{sessionId}`
5. 将 `GatewayMessage` 发布到 `gw:relay:{instanceId}`

### 5.2 无 `sessionId` 事件

对于 `agent_online`、`agent_offline` 这类不一定包含 `sessionId` 的事件，gateway 采用以下规则：

1. 如果本地有默认 active skill link，直接发送
2. 否则从活跃 owner 集合中选择一个 owner gateway
3. 发布到 `gw:relay:{instanceId}`

这类事件不写 `gw:session:skill-owner:{sessionId}`，因为它们没有 session 维度。

### 5.3 owner 选择算法

当 `gw:session:skill-owner:{sessionId}` 不存在或失效时，gateway 必须：

1. 读取 `gw:skill:owners` 中的所有候选实例
2. 用 `gw:skill:owner:{instanceId}` TTL key 过滤掉失效实例
3. 对剩余实例执行 Rendezvous Hashing（Highest Random Weight）
4. 基于 `sessionId` 选择得分最高的 owner gateway
5. 将结果写入 `gw:session:skill-owner:{sessionId}`

选择算法固定为 Rendezvous Hashing，原因是：

- 不需要额外协调器
- 集群成员变动时扰动最小
- 比随机选择更容易获得稳定分布

## 6. 本地 session 绑定

### 6.1 建立时机

gateway 在以下时机建立本地 `sessionId -> linkId` 绑定：

- 收到某条 skill 长连接发来的 `invoke`
- 该 invoke 携带明确的 `sessionId`

### 6.2 使用规则

1. agent owner gateway 处理上行事件时，如果本地存在该 `sessionId` 的绑定，直接命中该 link
2. 命中本地绑定后，不再查 Redis sticky owner
3. 本地绑定只对当前 gateway 实例生效，不写入 Redis

### 6.3 清理规则

本地绑定在以下任一条件成立时清理：

- 对应 local skill link 断开
- 收到 `session.status=idle` 或会话关闭信号
- gateway 进程重启

## 7. 进入 skill 集群后的处理

一旦 `GatewayMessage` 到达某条 local skill link，对应的 `skill-server` 实例必须继续沿用当前处理链：

1. `GatewayRelayService.handleGatewayMessage()`
2. `OpenCodeEventTranslator.translate()`
3. `MessagePersistenceService`
4. `StreamBufferService`
5. `PUBLISH session:{sessionId}`
6. 持有前端订阅的 skill 实例推送给 Miniapp

这部分不引入新的跨服务 DTO，也不改变现有 `StreamMessage` 广播模式。

## 8. 协议载体

### 8.1 继续复用 `GatewayMessage`

`gateway` 与 `skill-server` 之间继续使用现有 `GatewayMessage`：

- 上行：`tool_event`、`tool_done`、`tool_error`、`session_created`、`agent_online`、`agent_offline`
- 下行：`invoke`

### 8.2 relay payload 不引入新 DTO

`gw:relay:{instanceId}` 中发布的 payload 仍然是序列化后的 `GatewayMessage` JSON。新增的只是路由方式，不是消息模型。

## 9. 设计边界

- 本方案不把 `gateway <-> skill-server` 改为纯 Redis 通道
- 本方案不把 `skill-server` 做成无状态 worker；session 连续性仍由 sticky 路由维持
- 本方案接受 Redis Pub/Sub 的 at-most-once 语义，恢复与补偿由现有 sequence/reconnect 机制承接
