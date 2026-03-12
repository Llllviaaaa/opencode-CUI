# Phase 6: 修复 skill server 与 gateway 多实例场景下消息被非连接实例消费，导致用户收不到 opencode 返回消息 - Context

**Gathered:** 2026-03-12
**Status:** Ready for planning

<domain>
## Phase Boundary

修复多实例 `skill-server` / `gateway` 场景下的消息消费归属错误，确保 OpenCode 返回消息不会因为 REST 落到错误实例而被错误消费。范围聚焦在消息广播 key、`userId` 路由上下文、以及 `skill-server` 侧订阅与消费生命周期，不扩展到新的用户能力或 UI 改造。

</domain>

<decisions>
## Implementation Decisions

### 广播模型
- 不再以 `welinkSessionId` 作为实时广播主 key。
- `welinkSessionId` 继续保留为业务会话标识，用于定位这条消息属于哪个业务会话。
- 实时广播改为按 `userId` 广播，Redis channel 采用 `user-stream:{userId}`。
- 某个 `skill-server` 实例收到 `gateway` 回流消息后，应发布到 `user-stream:{userId}`，而不是发布到 `session:{welinkSessionId}`。
- 现有基于 `session:{sessionId}` 的实时广播与订阅逻辑应视为待清理旧路径；本 phase 需要评估并移除、替换或至少下线其在实时推送链路中的职责。

### 消费资格
- 真正的消费筛选通过 `user-stream:{userId}` 的订阅关系完成，而不是收到消息后再二次查 owner 集合。
- 只有当前持有该 `userId` 有效流连接的 `skill-server` 实例，才应该订阅 `user-stream:{userId}`。
- 订阅到消息的实例可以直接消费并推送本机用户连接，不再额外查 `streamOwner:{userId}`。
- `streamOwner:{userId}` 这类状态索引暂时不要，避免重复维护两套 Redis 结构。

### userId 来源与校验
- `pc-agent` 不应该携带 `userId`。
- `skill-server` 在 invoke 时通过 cookie 拿到 `userId`，并把它传给 `gateway`。
- `gateway` 在 Redis 维护 `ak -> userId` 映射。
- `gateway` 收到 invoke 时，必须校验“cookie 里的 `userId`”与“`ak -> userId` 映射”是否一致。
- `gateway` 回流消息时，通过 `ak -> userId` 再把 `userId` 补回消息，再发给 `skill-server`。
- `ak -> userId` 可以作为 `gateway` 侧的可信查询来源。

### skill-server 订阅生命周期
- `user-stream:{userId}` 的订阅关系由 `skill-server` 维护。
- 某用户在本实例第一条流连接建立成功时，订阅 `user-stream:{userId}`。
- 某用户在本实例最后一条流连接关闭时，取消订阅 `user-stream:{userId}`。
- 本机需要维护 `userId -> activeConnectionCount`，避免同实例重复连接时过早取消订阅。
- 生命周期策略采用“close/remove 为主，TTL 为兜底”。
- TTL 只用于异常断线或实例异常退出后的兜底清理，不作为正常消费判断的一部分。
- 当前围绕 `ensureSessionSubscribed`、`subscribeToSessionBroadcast`、`unsubscribeFromSession` 的 session 级订阅生命周期，需要同步清理或迁移到 user 级订阅模型。

### Claude's Discretion
- `userId -> activeConnectionCount` 的具体存储结构和线程安全实现。
- Redis 订阅封装的代码组织方式。
- TTL 具体时长、刷新时机和异常清理的实现细节。
- `gateway` 对缺失或校验失败 `userId` 的错误处理形式。

</decisions>

<specifics>
## Specific Ideas

- 当前问题场景明确为：客户端连接在 A，REST 发消息落到 B，OpenCode 返回经过 C，结果 B 可能错误消费消息，导致 A 上的用户看不到回复。
- 用户强调本质不是“消息没发出来”，而是“错误实例参与了消费”。
- 用户认为问题需要从广播和订阅模型本身重构，而不是继续围绕 `welinkSessionId` 打补丁。
- 用户倾向于让 `skill-server` 先收到消息，再由 `skill-server` 自己通过订阅关系决定是否消费，而不是由 `gateway` 先按实例精准分发。

</specifics>

<code_context>
## Existing Code Insights

### Reusable Assets
- `skill-server/.../service/GatewayRelayService.java`: 当前 gateway 回流消息入口、消息解析与 Redis 广播主流程，适合作为本 phase 的主改造入口。
- `skill-server/.../service/RedisMessageBroker.java`: 当前已封装 session 级别 publish/subscribe，可扩展为 user 级别 channel。
- `skill-server/.../ws/SkillStreamHandler.java`: 当前用户流连接维护点，已持有 `userSubscribers`，适合接入“首连订阅 / 末连退订 / activeConnectionCount”逻辑。
- `skill-server/.../controller/SkillMessageController.java`: invoke 请求入口，已能通过 cookie 和 session access control 拿到可信用户上下文。
- `ai-gateway/.../service/SkillRelayService.java` 与 `RedisMessageBroker.java`: 当前 gateway 已具备多实例 relay 和 Redis 协调能力，可承接 `ak -> userId` 相关校验或运行时映射。

### Established Patterns
- 当前 `skill-server` 是“先收到 gateway 消息，再通过 Redis 广播给其他 skill-server 实例”的模型。
- 当前 Redis 广播维度是 `session:{sessionId}`，本 phase 需要把实时广播维度调整为 `user-stream:{userId}`。
- 当前前端流连接入口是用户级 `/ws/skill/stream`，不是 session 级长连接，这支持按 `userId` 建立实时订阅模型。
- 当前 `gateway` 与 `pc-agent` 间已有基于 `ak` 的路由和连接归属关系，适合在 gateway 层利用 `ak -> userId` 做校验与回填。

### Integration Points
- `SkillMessageController -> GatewayRelayService.sendInvokeToGateway(...)`: 需要把 `userId` 传给 gateway。
- `gateway` invoke 校验逻辑：需要在进入 agent 调用前完成 `ak -> userId` 一致性校验。
- `GatewayRelayService.handleGatewayMessage(...)`: 需要在回流消息侧拿到可信 `userId` 并改为发布 `user-stream:{userId}`。
- `SkillStreamHandler.afterConnectionEstablished / afterConnectionClosed / handleTransportError`: 需要驱动 user channel 的订阅和取消订阅。
- `GatewayRelayService` 与 `RedisMessageBroker` 中现有 `session:{sessionId}` publish/subscribe 相关入口，需要明确哪些保留给历史/恢复语义，哪些从实时链路中移除。

</code_context>

<deferred>
## Deferred Ideas

- 是否在后续阶段为 `gateway` 增加更细粒度的 user 级精准分发，而不是先到 skill-server 再广播。
- 是否在未来增加 user 连接状态的显式 Redis 索引或可观测性看板。
- 是否将 `ak -> userId` 从 Redis 运行时映射进一步收敛为单一可信来源与统一失效策略。

</deferred>

---

*Phase: 06-skill-server-gateway-opencode*
*Context gathered: 2026-03-12*
