# Phase 7: 多服务来源识别与回流隔离 - Context

**Gathered:** 2026-03-12
**Status:** Ready for planning

<domain>
## Phase Boundary

本 phase 只解决 `gateway` 对多上游服务的来源识别、请求归属隔离、回流路由隔离和可观测性建设。重点是确保来自 `skill-server` 与新增服务的消息在 `gateway` 内部始终按来源服务隔离处理，且 OpenCode 返回不会串到错误服务。全局唯一 session id 算法属于 Phase 8，不在本 phase 内展开。

</domain>

<decisions>
## Implementation Decisions

### 来源服务表达
- `GatewayMessage` 顶层新增 `source`
- `source` 使用稳定服务标识字符串，而不是模糊枚举值
- `source` 的语义定义为“这条消息所属的上游服务”
- 回流消息必须继承原始 `source`，不能在中途按投递目标改写语义
- `source` 只属于上游服务与 `gateway` 之间的标准接入协议，以及 `gateway` 内部路由上下文
- `pc-agent` 不感知 `source`，也不需要在 `gateway <-> pc-agent` 协议中携带该字段
- 消息体显式携带 `source`，但 `gateway` 不能只信消息体
- 连接建立时先绑定可信 `source`，消息处理时再校验“消息体 `source`”与“连接绑定 `source`”一致
- `source` 不只是白名单字符串，还必须与接入凭证或连接身份绑定
- `gateway` 对非法 `source``、`source_not_allowed` 和 `source_mismatch` 做协议级错误返回

### 多服务路由模型
- `new-service` 与 `skill-server` 复用同一套多实例路由思想，但作为两个平级、独立的上游服务存在
- `gateway` 内部路由维度采用 `source + owner`
- 路由先按 `source` 分服务域，再在该 `source` 域内按 owner 选择回流目标
- owner 注册表采用统一模型，但 key 天然带 `source`
- owner key 采用 `source:instanceId`
- 原 owner 不可用时，允许在同一 `source` 域内重选可用 owner
- 严禁跨 `source` 降级或重选
- 同域重选属于允许行为，但必须保留明确日志

### 标准接入协议
- `skill-server <-> gateway` 现有协议提升为标准上游接入协议
- 新增服务必须遵守该标准协议接入 `gateway`
- `gateway` 不负责为不同上游服务做业务协议适配
- `gateway` 只负责标准协议解析、`source` 校验、owner 路由、标准错误返回和回流分发
- 各上游服务自行适配到标准协议，而不是让 `gateway` 适配各服务的私有协议

### 错投保护与观测
- 默认策略为“宁可失败，不可错投”
- 发现归属异常时，`gateway` 必须拒绝投递、返回明确错误并记录日志
- 关键路由日志采用结构化字段，并引入统一 `traceId`
- 关键日志至少记录：`traceId`、`source`、`instanceId`、`ownerKey`、`ak`、`messageType`、`sessionId`、`routeDecision`、`fallbackUsed`、`errorCode`
- 同域重选成功时记录路由事件日志
- 同域重选失败时记录 warning / error，并明确指出原 owner 不可用

### Claude's Discretion
- `source` 白名单与凭证绑定的具体配置组织方式
- `traceId` 的生成、透传和日志封装细节
- 同域重选成功时使用 `info` 还是专用 event 级别记录
- 标准错误响应的具体字段命名与错误码承载形式

</decisions>

<specifics>
## Specific Ideas

- 用户明确要求 `skill-server` 和新增服务是两个独立的上游服务，二者不应耦合
- 用户明确要求 `gateway` 提供的是标准接入协议，而不是为每个新服务做协议适配
- 用户明确要求多服务隔离优先于可用性兜底，尤其不能出现“新服务下发消息，结果返回给了 `skill-server`”
- 用户明确要求 `new-service` 与 `gateway` 的多实例路由思路应与 `skill-server` 和 `gateway` 保持一致，但必须先完成 `source` 维度隔离

</specifics>

<code_context>
## Existing Code Insights

### Reusable Assets
- [`ai-gateway/src/main/java/com/opencode/cui/gateway/model/GatewayMessage.java`](D:/02_Lab/Projects/sandbox/opencode-CUI/ai-gateway/src/main/java/com/opencode/cui/gateway/model/GatewayMessage.java): 当前 gateway 协议模型，适合扩展 `source` 等标准路由字段
- [`ai-gateway/src/main/java/com/opencode/cui/gateway/service/SkillRelayService.java`](D:/02_Lab/Projects/sandbox/opencode-CUI/ai-gateway/src/main/java/com/opencode/cui/gateway/service/SkillRelayService.java): 当前 `gateway -> skill-server` 回流主入口，现状仍偏“任意可用 skill link”语义，是本 phase 的重点改造点
- [`ai-gateway/src/main/java/com/opencode/cui/gateway/service/RedisMessageBroker.java`](D:/02_Lab/Projects/sandbox/opencode-CUI/ai-gateway/src/main/java/com/opencode/cui/gateway/service/RedisMessageBroker.java): 已有 owner 心跳、Redis relay 和 `ak -> userId` 等基础设施，适合扩展为 `source` 维度 owner 注册
- [`skill-server/src/main/java/com/opencode/cui/skill/service/GatewayRelayService.java`](D:/02_Lab/Projects/sandbox/opencode-CUI/skill-server/src/main/java/com/opencode/cui/skill/service/GatewayRelayService.java): 现有标准接入协议的一号实现方，可作为标准协议字段演进的参考

### Established Patterns
- 当前 `gateway` 已具备基于 Redis 的多实例 relay 能力，适合延伸到多 `source` 域 owner 路由
- 当前 `skill-server` 已经在下游使用 `user-stream:{userId}` 做用户流广播，因此本 phase 可把重点集中在 `gateway` 服务域隔离，而不是重做下游广播模型
- 当前 `GatewayMessage` 已承载 `ak`、`welinkSessionId`、`toolSessionId`、`userId` 等路由字段，说明在顶层增加标准字段符合既有协议风格
- 当前 `gateway -> pc-agent` 链路主要关注 agent 执行所需上下文，`source` 不应扩散为 agent 侧协议负担

### Integration Points
- `gateway` 内部服务连接注册与 owner 维护逻辑，需要从单一 `skill-server` 语义提升到 `source` 域语义
- invoke 入站校验链路需要把 `source` 一致性校验纳入标准流程
- OpenCode 回流进入 `gateway` 后，需要根据原始 `source` 和 owner 进行服务域隔离路由
- 协议错误返回链路需要增加 `source_not_allowed`、`source_mismatch` 等标准错误

</code_context>

<deferred>
## Deferred Ideas

- 全局唯一 session id 的编码方式、生成算法和跨服务不冲突策略 - Phase 8
- 是否引入更细粒度的 request-level ownership 记录，而不仅是 `source:instanceId` owner - 后续可在 Phase 8 或再后续 phase 评估
- 是否进一步把多上游服务的注册和治理抽象成通用平台能力 - 当前不在本 phase 范围

</deferred>

---

*Phase: 07-gateway-multi-service-source-isolation*
*Context gathered: 2026-03-12*
