# Phase 6: 修复 skill server 与 gateway 多实例场景下消息被非连接实例消费，导致用户收不到 opencode 返回消息 - Research

**Researched:** 2026-03-12
**Status:** Ready for planning

## Standard Stack

### 1. 继续使用现有 Spring Boot + Redis Pub/Sub，不引入新中间件
- **Use:** 继续基于当前 `skill-server` / `gateway` 的 Spring Boot + `StringRedisTemplate` + `RedisMessageListenerContainer`
- **Why:** 当前问题是广播 key 与订阅生命周期建模错误，不是 Redis 能力不足。现有实现已经具备 publish/subscribe、实例内连接管理、多实例 relay 等基础能力
- **Confidence:** High

### 2. 在 `GatewayMessage` 顶层补充 `userId`
- **Use:** 在 `ai-gateway` 的 [GatewayMessage.java](D:/02_Lab/Projects/sandbox/opencode-CUI/ai-gateway/src/main/java/com/opencode/cui/gateway/model/GatewayMessage.java) 顶层增加 `userId`
- **Why:** `userId` 是路由上下文，不属于 `payload` 里的业务动作字段；顶层字段最便于 `gateway` 校验、日志跟踪和回流补全
- **Confidence:** High

### 3. gateway 维护 `ak -> userId` 运行时映射
- **Use:** 复用 gateway 现有 Redis 协调能力，在 gateway 层维护或刷新 `ak -> userId` 映射，用于 invoke 校验与回流补全
- **Why:** 用户已明确要求 `pc-agent` 不携带 `userId`，因此 `gateway` 必须持有可查询的用户归属上下文；`ak` 已是当前 agent 路由的稳定 key
- **Confidence:** Medium-High

### 4. skill-server 改为订阅 `user-stream:{userId}`
- **Use:** 在 `skill-server` 的 Redis broker 增加 `user-stream:{userId}` channel 的 publish/subscribe 能力
- **Why:** 当前 `session:{sessionId}` 广播模型会把“业务会话归属”和“实时推送归属”混在一起；用户级流连接决定谁该收到实时推送，所以广播维度应改为 `userId`
- **Confidence:** High

## Architecture Patterns

### Pattern A: gateway 只负责校验和补全 userId，skill-server 负责真实消费筛选

**Recommended flow**
1. `SkillMessageController` 从 cookie + access control 拿到可信 `userId`
2. `GatewayRelayService.sendInvokeToGateway(...)` 把 `userId` 一起发给 gateway
3. gateway 用 `ak -> userId` 做一致性校验，不通过就拒绝 invoke
4. gateway 发给 `pc-agent` 的消息不带 `userId`
5. `pc-agent` 回流时，gateway 再根据 `ak -> userId` 把 `userId` 补回 `GatewayMessage`
6. 某个 `skill-server` 实例收到 gateway 回流消息
7. 它发布到 `user-stream:{userId}`
8. 只有已订阅该 `user-stream:{userId}` 的实例会收到并推送本机用户流连接

**Why this pattern fits current code**
- `gateway` 已经是 agent 连接与 AK 归属的中心
- `skill-server` 已经是前端流连接与最终推送的中心
- 两边各司其职，避免把 user 路由状态扩散到 `pc-agent`

**Confidence:** High

### Pattern B: 用“第一条连接订阅 / 最后一条连接退订”替代 owner 集合二次判断

**Recommended flow**
- `SkillStreamHandler.afterConnectionEstablished(...)`
  - 解析 cookie 中的 `userId`
  - 本机 `activeConnectionCount[userId]++`
  - 当计数从 `0 -> 1` 时，订阅 `user-stream:{userId}`
- `afterConnectionClosed(...)` / `handleTransportError(...)`
  - 本机 `activeConnectionCount[userId]--`
  - 当计数降到 `0` 时，取消订阅 `user-stream:{userId}`

**Why this pattern is better than streamOwner set**
- 不需要再维护 `streamOwner:{userId}` 状态索引
- “谁能消费”由订阅关系天然表达，不需要收到消息后再次查 Redis
- 更符合你们当前“用户级流连接”模型

**Confidence:** High

### Pattern C: 保留 `welinkSessionId` 作为业务归属，不再作为实时广播 key

**Recommended use**
- `welinkSessionId` 只用于：
  - 会话历史
  - 会话上下文持久化
  - 报文归属识别
- 不再用于：
  - 实时广播 channel
  - skill-server 多实例消费资格判断

**Why**
- 你当前暴露的问题正是 `sessionId` 广播导致 REST 落点实例混入实时消费集合
- `welinkSessionId` 是业务会话 key，不等价于“当前有资格推送的实时实例集合”

**Confidence:** High

## Don't Hand-Roll

### 1. 不要自建新的消息中间件或额外 broker 层
- **Avoid:** Kafka / RabbitMQ / NATS / 自定义 MQ 抽象层
- **Why:** 这次问题是路由建模错误，不是消息系统吞吐或可靠性问题

### 2. 不要让 pc-agent 感知或透传 userId
- **Avoid:** 在 agent 协议、SDK 事件或 agent 上报里加入业务 `userId`
- **Why:** 用户已明确要求 `pc-agent` 不携带 `userId`；这会把服务端用户上下文泄漏到 agent 侧

### 3. 不要保留“session 广播 + user 广播”双活实时链路
- **Avoid:** 新增 `user-stream:{userId}` 的同时继续让 `session:{sessionId}` 承担实时推送职责
- **Why:** 双轨实时链路会继续制造竞态、重复推送和难以定位的消费路径

### 4. 不要把消费资格判断同时放在 gateway 和 skill-server 两边
- **Avoid:** gateway 先做一轮实例过滤，skill-server 再做一轮不一致过滤
- **Why:** 这会让消息路由语义更复杂，调试链路更长；当前更适合由 skill-server 负责最终消费判定

## Common Pitfalls

### 1. 只新增 `userId` 字段，但不清理旧的 `session:{sessionId}` 订阅链路
- **Risk:** 旧链路仍然会让非连接实例收到并消费消息
- **Prevention:** planning 时必须包含“session 级广播/订阅链路清理”任务，而不是只加新逻辑
- **Confidence:** High

### 2. 本机连接计数不准确，导致过早退订或永不退订
- **Risk:** 同一实例多条连接并存时，关闭一条连接就退订会导致丢消息；异常断开未清理则会留脏订阅
- **Prevention:** 本机维护 `userId -> activeConnectionCount`，并配合 close/remove 主流程与 TTL 兜底
- **Confidence:** High

### 3. `ak -> userId` 只在一个入口刷新，导致映射陈旧
- **Risk:** invoke 校验或回流补全使用过期 userId
- **Prevention:** 规划时需要明确 gateway 侧映射来源、刷新时机和失效策略，并给校验失败场景明确错误语义
- **Confidence:** Medium

### 4. 把 user 广播误当作“一个 user 只能有一个实例消费”
- **Risk:** 会误伤“同一用户多端同时在线”的业务场景
- **Prevention:** `user-stream:{userId}` 必须允许多个实例同时订阅，只要这些实例当前都持有该用户的有效流连接
- **Confidence:** High

### 5. 清理 session 广播链路时误删历史/恢复语义
- **Risk:** 断线恢复、snapshot、streaming state 可能仍依赖 session 级语义
- **Prevention:** 研究和规划时需先区分“实时推送链路”与“历史恢复链路”，不要一刀切删除所有 session 相关逻辑
- **Confidence:** Medium-High

## Code Examples

### Example 1: GatewayMessage 顶层新增 userId

**Current anchor**
- [GatewayMessage.java](D:/02_Lab/Projects/sandbox/opencode-CUI/ai-gateway/src/main/java/com/opencode/cui/gateway/model/GatewayMessage.java)

**Recommended change**
- 在顶层增加 `private String userId;`
- `invoke(...)`、`toolEvent(...)`、`toolDone(...)`、`toolError(...)` 等 builder / copy helper 要能携带或保留 `userId`

**Why**
- 现有 `GatewayMessage` 已承载 `ak`、`welinkSessionId`、`toolSessionId`
- `userId` 与这些一样，属于跨服务的路由上下文字段

### Example 2: skill-server Redis broker 从 session channel 扩展到 user channel

**Current anchor**
- [RedisMessageBroker.java](D:/02_Lab/Projects/sandbox/opencode-CUI/skill-server/src/main/java/com/opencode/cui/skill/service/RedisMessageBroker.java)

**Recommended change**
- 参考现有 `publishToSession / subscribeToSession / unsubscribeFromSession`
- 增加：
  - `publishToUser(String userId, String message)`
  - `subscribeToUser(String userId, Consumer<String> handler)`
  - `unsubscribeFromUser(String userId)`

**Why**
- 现有封装已经包含 listener 管理、日志、unsubscribe 前清理等模式，可直接复用

### Example 3: SkillStreamHandler 作为 user 订阅生命周期入口

**Current anchor**
- [SkillStreamHandler.java](D:/02_Lab/Projects/sandbox/opencode-CUI/skill-server/src/main/java/com/opencode/cui/skill/ws/SkillStreamHandler.java)

**Recommended change**
- 在 `registerUserSubscriber(...)` 中维护本机 `activeConnectionCount`
- 第一条连接时触发 `subscribeToUser(userId)`
- 在 `unregisterSubscriber(...)` 和 transport error 清理里，最后一条连接关闭时触发 `unsubscribeFromUser(userId)`

**Why**
- 这里已经持有 `userSubscribers`
- `afterConnectionEstablished` 已通过 cookie 识别 `userId`
- 是最贴近真实流连接状态的入口

### Example 4: Gateway 回流入口改为按 userId 广播

**Current anchor**
- [GatewayRelayService.java](D:/02_Lab/Projects/sandbox/opencode-CUI/skill-server/src/main/java/com/opencode/cui/skill/service/GatewayRelayService.java)

**Current problem**
- 当前流程会把消息发布到 `session:{sessionId}`
- 并通过 `subscribeToSessionBroadcast / ensureSessionSubscribed` 扩散到 skill-server 多实例

**Recommended change**
- 解析出可信 `userId` 后，发布到 `user-stream:{userId}`
- 把原本的 session 级实时广播改造成 user 级实时广播
- `session:{sessionId}` 只保留给确有需要的历史恢复语义，或完全退役出实时链路

## Suggested Planning Focus

在后续 `$gsd-plan-phase 6` 中，建议至少覆盖 3 个 plan concerns：

1. **gateway userId context**
- 增加 `GatewayMessage.userId`
- invoke 校验 `ak -> userId`
- 回流补 userId

2. **skill-server user broadcast**
- user-stream Redis publish/subscribe
- `SkillStreamHandler` 的首订阅 / 末退订
- 本机 `activeConnectionCount`

3. **legacy session path cleanup + regression**
- 清理 `session:{sessionId}` 在实时链路中的职责
- 识别历史/恢复链路仍需保留的部分
- 增加 A/B/C 多实例回归验证

---

*Phase: 06-skill-server-gateway-opencode*
*Research completed: 2026-03-12*
