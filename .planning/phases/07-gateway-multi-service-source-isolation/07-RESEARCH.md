# Phase 7: 多服务来源识别与回流隔离 - Research

**Researched:** 2026-03-12
**Status:** Ready for planning

## Standard Stack

### 1. 继续使用现有 Spring Boot WebSocket + Redis Pub/Sub，不引入新中间件
- **Use:** 继续基于当前 `ai-gateway` / `skill-server` 的 Spring Boot 3.4.6、`spring-boot-starter-websocket`、`spring-boot-starter-data-redis`
- **Why:** 本 phase 的问题是服务域隔离与回流归属，而不是消息中间件能力不足。现有代码已经具备 WebSocket 握手、Redis relay、owner heartbeat 和多实例基础设施
- **Verified with:** Spring WebSocket 官方文档说明可以通过 `HandshakeInterceptor` 在握手阶段拒绝连接并把属性放入 `WebSocketSession`；Spring Data Redis 官方文档说明 `RedisMessageListenerContainer` 支持按 channel 注册监听器并集中处理异常
- **Confidence:** High

### 2. 将 `source` 作为标准上游协议字段加入 `GatewayMessage`，但不扩散到 `pc-agent`
- **Use:** 在 [`GatewayMessage.java`](D:/02_Lab/Projects/sandbox/opencode-CUI/ai-gateway/src/main/java/com/opencode/cui/gateway/model/GatewayMessage.java) 顶层新增 `source`
- **Why:** `source` 是上游服务归属上下文，和 `ak`、`welinkSessionId`、`toolSessionId` 同级；它属于上游服务与 `gateway` 的标准接入协议，以及 `gateway` 内部路由上下文，不应变成 `pc-agent` 侧协议负担
- **Verified with:** 当前 [`EventRelayService.java`](D:/02_Lab/Projects/sandbox/opencode-CUI/ai-gateway/src/main/java/com/opencode/cui/gateway/service/EventRelayService.java) 已经在发往 agent 前去掉 `userId`，说明 gateway 已有“上游路由字段不下沉到 agent”的边界
- **Confidence:** High

### 3. 用握手阶段绑定可信 `source`，消息阶段只做一致性校验
- **Use:** 复用 `HandshakeInterceptor` 模式，在上游服务 WebSocket 握手时绑定 `source` 到 `WebSocketSession.attributes`
- **Why:** 官方推荐的握手扩展点就是 `HandshakeInterceptor`；当前 [`SkillWebSocketHandler.java`](D:/02_Lab/Projects/sandbox/opencode-CUI/ai-gateway/src/main/java/com/opencode/cui/gateway/ws/SkillWebSocketHandler.java) 和 [`AgentWebSocketHandler.java`](D:/02_Lab/Projects/sandbox/opencode-CUI/ai-gateway/src/main/java/com/opencode/cui/gateway/ws/AgentWebSocketHandler.java) 已经采用该模式处理内部 token / AK-SK 握手，因此新增 `source` 绑定与校验应延续此模式
- **Verified with:** Spring WebSocket 官方文档指出 `HandshakeInterceptor` 可在握手前后工作，并把属性暴露给 `WebSocketSession`
- **Confidence:** High

### 4. 统一 owner 注册表，但 key 必须带 `source`
- **Use:** 延续当前 Redis owner heartbeat 模型，把 owner key 从单一 `instanceId` 提升为 `source:instanceId`
- **Why:** 这样既能复用当前多实例路由思想，又能把 `skill-server` 与 `new-service` 的 owner 空间彻底隔离；实现上仍然是一套统一表结构，语义上则按 `source` 分域
- **Verified with:** 当前 [`RedisMessageBroker.java`](D:/02_Lab/Projects/sandbox/opencode-CUI/ai-gateway/src/main/java/com/opencode/cui/gateway/service/RedisMessageBroker.java) 已有 `gw:skill:owner:{instanceId}` 和 `gw:skill:owners` 模式，扩展命名空间比重做注册机制更合适
- **Confidence:** High

## Architecture Patterns

### Pattern A: `source` 只在上游服务 <-> gateway 与 gateway 内部存在，agent 侧完全无感知
**Recommended flow**
1. 上游服务按标准协议连接 `gateway`，握手时通过凭证绑定合法 `source`
2. `gateway` 把绑定后的 `source` 写入 `WebSocketSession.attributes`
3. 上游服务发来的业务消息继续显式携带 `source`
4. `gateway` 在消息处理阶段校验“消息体 `source` == 连接绑定 `source`”
5. `gateway` 内部保留 `source` 作为回流路由上下文
6. `gateway` 发往 `pc-agent` 的消息不带 `source`
7. OpenCode 回流进入 `gateway` 后继续沿用原始 `source`
8. `gateway` 按 `source + owner` 决定回流目标

**Why this pattern fits current code**
- 当前 `gateway` 已经明确区分上游 `skill-server` 内部链路和下游 `pc-agent` 链路
- [`AgentWebSocketHandler.java`](D:/02_Lab/Projects/sandbox/opencode-CUI/ai-gateway/src/main/java/com/opencode/cui/gateway/ws/AgentWebSocketHandler.java) 与 [`EventRelayService.java`](D:/02_Lab/Projects/sandbox/opencode-CUI/ai-gateway/src/main/java/com/opencode/cui/gateway/service/EventRelayService.java) 已经体现出 agent 侧只需要执行上下文，不需要上游用户/服务归属字段
- 这样能把多服务路由语义限制在真正需要它的边界内

**Confidence:** High

### Pattern B: 路由先按 `source` 分服务域，再在域内按 `source:instanceId` 选择 owner

**Recommended flow**
- owner 注册:
  - `skill-server` owner 记录在 `skill-server:{instanceId}`
  - `new-service` owner 记录在 `new-service:{instanceId}`
- 回流选择:
  - 先根据原始 `source` 过滤到对应服务域
  - 再在该域内选择原 owner 或可用 fallback owner
- fallback:
  - 仅允许在同一 `source` 域内重选
  - 严禁跨域重选

**Why this pattern is better than source-only routing**
- 仅按 `source` 只能防止“回给错误服务”
- `source + owner` 还可以防止“回给同服务的错误实例”
- 这与当前 `skill-server <-> gateway` 多实例协同思路一致，只是把命名空间从单服务扩展到多服务

**Confidence:** High

### Pattern C: 把 `skill-server <-> gateway` 现有协议提升为标准上游接入协议

**Recommended use**
- `gateway` 提供一套标准上游协议
- 新服务必须适配到这套协议
- `gateway` 只做标准协议解析、校验、路由和回流
- 不在 `gateway` 内引入服务专属协议翻译层

**Why**
- 用户明确要求 `skill-server` 与 `new-service` 是两个平级、独立的服务，不能让其中一个挂靠在另一个服务语义上
- 标准协议模型能避免 `gateway` 越接越多时演变成“多服务协议适配器集合”
- 当前 [`GatewayWSClient.java`](D:/02_Lab/Projects/sandbox/opencode-CUI/skill-server/src/main/java/com/opencode/cui/skill/ws/GatewayWSClient.java) 已经是现有标准协议的一号实现方

**Confidence:** High

### Pattern D: 严格保护优先于可用性兜底，日志用结构化字段 + `traceId`

**Recommended flow**
- 校验失败:
  - 直接拒绝处理
  - 返回协议级错误，如 `source_not_allowed`、`source_mismatch`
  - 打结构化错误日志
- 正常路由:
  - 打结构化路由日志
  - 透传 `traceId`
- fallback:
  - 同域重选成功打路由事件日志
  - 同域重选失败打 warning / error

**Why this pattern fits current stack**
- Redis Pub/Sub 官方文档明确指出其投递语义是 at-most-once，一旦订阅方断开或处理失败，消息不会自动重发
- 这意味着不能依赖“错投之后再补救”的思路，必须在 gateway 路由决策点前置阻断
- 当前项目已有较高粒度日志风格，扩展结构化字段成本低于引入新 tracing 基础设施

**Confidence:** High

## Don't Hand-Roll

### 1. 不要为每个新服务在 `gateway` 内做私有协议适配层
- **Avoid:** `gateway` 内部出现 `SkillServerProtocolAdapter` / `NewServiceProtocolTranslator` 这类按服务翻译协议的业务层
- **Why:** 用户明确要求“后续服务都按标准协议接入 gateway”，不是让 gateway 适配每个服务

### 2. 不要让 `pc-agent` 感知 `source`
- **Avoid:** 在 `gateway -> pc-agent` 或 `pc-agent -> gateway` 协议中新增 `source`
- **Why:** 这会把上游服务域语义扩散到 agent 执行层，破坏边界，也与当前 `userId` 不下沉到 agent 的做法冲突

### 3. 不要只做 `source` 字段，不做连接级可信绑定
- **Avoid:** 单纯依赖消息体里的 `source` 决定路由
- **Why:** 这样任何接入方只要能发消息就能伪造来源，无法满足“`gateway` 不能只信消息体”的前提

### 4. 不要把 owner 注册表继续做成单一 `skill-server` 语义
- **Avoid:** 继续沿用只针对 `skill-server` 的 owner key / owners set 命名，再给新服务打补丁
- **Why:** 这样会把单服务假设永久固化在基础设施中，后续每接一个服务都会继续污染语义

### 5. 不要把 Redis Pub/Sub 当成可靠投递队列
- **Avoid:** 指望 Redis Pub/Sub 在连接抖动、监听器异常、订阅中断后自动补发
- **Why:** Redis 官方文档明确说明 Pub/Sub 是 at-most-once 语义；需要 durable/replay 的场景应该依赖已有持久化/恢复链路，而不是指望 Pub/Sub 自愈

## Common Pitfalls

### 1. 只新增 `source` 字段，但回流代码仍然“发给任意可用 skill link”
- **Risk:** 表面上协议里已经区分服务，实际路由仍按旧的单服务模型运行，照样会串流
- **Prevention:** planning 时必须把 [`SkillRelayService.java`](D:/02_Lab/Projects/sandbox/opencode-CUI/ai-gateway/src/main/java/com/opencode/cui/gateway/service/SkillRelayService.java) 从单一 skill 语义提升到多 `source` 语义
- **Confidence:** High

### 2. `source` 白名单存在，但凭证与 `source` 没绑定
- **Risk:** 接入方可以合法连接后伪造另一个合法 `source`
- **Prevention:** 研究与规划时要明确“白名单 + 凭证/连接身份绑定”是一个组合约束，不是两个可选方案
- **Confidence:** High

### 3. fallback 没限制在同域重选
- **Risk:** 原 owner 不可用时，为了提高可用性错误地把消息发给另一个 `source`
- **Prevention:** 路由器 API 和日志字段都要显式带 `source`，避免出现“只凭 instanceId 重选”的实现
- **Confidence:** High

### 4. 结构化日志只记 `source`，不记 owner / route decision
- **Risk:** 出问题时只能知道“串服务了”，但不知道是 owner 注册错、fallback 错，还是连接绑定错
- **Prevention:** 路由日志必须最少覆盖 `traceId`、`source`、`instanceId`、`ownerKey`、`messageType`、`routeDecision`、`fallbackUsed`、`errorCode`
- **Confidence:** High

### 5. 把标准协议升级和 session id 全局唯一方案混在一个 phase 里
- **Risk:** planning 时 scope 失焦，Phase 7 会一边做服务域隔离，一边做跨服务 id 算法，复杂度明显上升
- **Prevention:** 本 phase 只研究 `source`/owner/回流隔离；session id 算法继续留在 Phase 8
- **Confidence:** High

## Code Examples

### Example 1: 在握手阶段绑定可信 `source`

**Current anchors**
- [`GatewayConfig.java`](D:/02_Lab/Projects/sandbox/opencode-CUI/ai-gateway/src/main/java/com/opencode/cui/gateway/config/GatewayConfig.java)
- [`SkillWebSocketHandler.java`](D:/02_Lab/Projects/sandbox/opencode-CUI/ai-gateway/src/main/java/com/opencode/cui/gateway/ws/SkillWebSocketHandler.java)

**Recommended change**
- 延续 `HandshakeInterceptor` 模式
- 在上游服务握手通过后，把可信 `source` 放入 `WebSocketSession.attributes`
- 业务消息进入 handler 时读取该属性，校验与消息体 `source` 一致

**Why**
- Spring 官方明确支持在握手阶段向 `WebSocketSession` 暴露属性
- 当前 gateway 已在握手阶段处理内部 token 与 AK/SK 身份，扩展 `source` 绑定是自然演进

### Example 2: `GatewayMessage` 顶层新增 `source`，但发往 agent 前去掉服务域语义

**Current anchors**
- [`GatewayMessage.java`](D:/02_Lab/Projects/sandbox/opencode-CUI/ai-gateway/src/main/java/com/opencode/cui/gateway/model/GatewayMessage.java)
- [`EventRelayService.java`](D:/02_Lab/Projects/sandbox/opencode-CUI/ai-gateway/src/main/java/com/opencode/cui/gateway/service/EventRelayService.java)

**Recommended change**
- 顶层新增 `private String source;`
- 增加 `withSource(...)` / `withoutSource()` 或等价 helper
- `gateway` 内部保留 `source`
- 在发布到 agent channel 前统一剥离 `source`

**Why**
- 保持“上游服务归属上下文只存在于上游协议与 gateway 内部”的边界

### Example 3: owner key 从单一 skill owner 扩展为 `source:instanceId`

**Current anchor**
- [`RedisMessageBroker.java`](D:/02_Lab/Projects/sandbox/opencode-CUI/ai-gateway/src/main/java/com/opencode/cui/gateway/service/RedisMessageBroker.java)

**Recommended change**
- 把当前单一 `gw:skill:*` owner key 模式抽象成支持 `source`
- 例如保留统一表结构，但 channel / key / set member 带 `source:instanceId`
- owner 查询 API 显式接收 `source`

**Why**
- 保持数据结构统一
- 同时避免 `skill-server` 和 `new-service` 共享 owner 命名空间

### Example 4: 路由器返回显式 route decision，而不是只返回 session send 成功/失败

**Current anchor**
- [`SkillRelayService.java`](D:/02_Lab/Projects/sandbox/opencode-CUI/ai-gateway/src/main/java/com/opencode/cui/gateway/service/SkillRelayService.java)

**Recommended change**
- 从“是否发出去”升级为“路由到了谁、是否 fallback、失败码是什么”
- 例如返回结构化路由结果，供日志和错误响应复用

**Why**
- 这能直接支撑 SAFE-01 的可观测性要求
- 同时让“同域重选成功”和“重选失败”成为一等路由事件，而不是散落在 warn/debug 文本里

## Official Sources

- Spring WebSocket 握手与 `HandshakeInterceptor`: [Spring Framework WebSocket API](https://docs.spring.io/spring-framework/reference/web/websocket/server.html)
- Spring Data Redis Pub/Sub 与 `RedisMessageListenerContainer`: [Spring Data Redis Pub/Sub Messaging](https://docs.spring.io/spring-data/redis/reference/redis/pubsub.html)
- Redis Pub/Sub at-most-once 语义: [Redis Pub/Sub Docs](https://redis.io/docs/latest/develop/pubsub/)

## Suggested Planning Focus

在后续 `$gsd-plan-phase 7` 中，建议至少拆成 3 个 plan concerns：

1. **标准协议扩展与可信 source**
- `GatewayMessage.source`
- 上游握手绑定 `source`
- 消息体与连接身份一致性校验
- 标准错误码：`source_not_allowed`、`source_mismatch`

2. **多 source owner 路由**
- owner 注册表提升到 `source:instanceId`
- 回流按 `source + owner` 路由
- 原 owner 不可用时仅同域 fallback
- 禁止跨 source 重选

3. **结构化路由观测与回归验证**
- 引入 `traceId`
- 统一路由日志字段
- 增加“新服务消息不会回给 skill-server”的回归验证
- 覆盖 owner 丢失、同域重选、source 校验失败场景

---

*Phase: 07-gateway-multi-service-source-isolation*
*Research completed: 2026-03-12*
