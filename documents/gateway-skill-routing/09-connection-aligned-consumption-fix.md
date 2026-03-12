# 09 Connection-Aligned Consumption Fix

> 版本：1.1
> 日期：2026-03-12

## 背景

当 `skill-server` 和 `gateway` 都以多实例运行时，前端长连接所在实例、REST 发消息命中的实例、以及 gateway 回流命中的实例可能不是同一台机器。

旧模型把实时广播建立在 `session:{sessionId}` 上，导致“发消息经过的实例”也可能参与订阅和消费，从而出现：

- 客户端连接在 A
- REST 请求命中 B
- gateway 回流先到 C
- B 因为旧的 session 订阅关系参与消费
- A 没有拿到这条返回，用户看不到消息

## 目标

让实时消费资格与“当前是否持有该用户的流连接”对齐，而不是与 `welinkSessionId` 订阅关系对齐。

## 新模型

### 1. `userId` 成为实时广播维度

- `welinkSessionId` 继续表示业务会话
- `userId` 表示实时下行消息应该广播到哪个用户的在线流连接
- `skill-server` 收到 gateway 回流后，发布到 `user-stream:{userId}`

### 2. `pc-agent` 不携带 `userId`

- `userId` 只存在于 `skill-server <-> gateway` 服务端链路
- `gateway -> pc-agent` 发出的消息会移除 `userId`
- agent 回流时也不要求带回 `userId`

### 3. `gateway` 负责 `ak -> userId` 校验与补全

- invoke 时，`skill-server` 从 cookie 解析出 `userId`
- `gateway` 根据 Redis 中的 `ak -> userId` 做一致性校验
- 回流时，`gateway` 再根据 `ak -> userId` 把 `userId` 补回给 `skill-server`

### 4. `skill-server` 通过订阅关系决定消费资格

- 某用户在本实例建立第一条流连接时，订阅 `user-stream:{userId}`
- 该用户在本实例最后一条流连接关闭时，取消订阅 `user-stream:{userId}`
- 只有真实持有该用户流连接的实例才会收到并消费该用户的实时消息

## 关键约束

- 旧的 `session:{sessionId}` 不再承担实时广播与消费职责
- REST 请求命中的实例不会因为“发过消息”自动获得消费资格
- 同一个用户如果在多个终端上分别连到不同实例，这些实例都可以同时订阅并消费同一条 `user-stream:{userId}`

## 代码落点

- `ai-gateway`
  - `GatewayMessage` 顶层增加 `userId`
  - `EventRelayService` 维护 `ak -> userId`
  - `SkillRelayService` 校验 invoke 请求中的 `userId`
- `skill-server`
  - `GatewayRelayService` 将实时广播改为 `user-stream:{userId}`
  - `SkillStreamHandler` 维护用户级订阅生命周期
  - `RedisMessageBroker` 删除旧的 session 级实时 pub/sub API

## 验证重点

- A/B/C 错投链路下，只有真实持有用户流连接的实例会收到实时广播
- 同一用户在两个实例上都有有效流连接时，两个实例都能收到同一条 `user-stream` 消息
- `pc-agent` 出入站报文中不暴露 `userId`
