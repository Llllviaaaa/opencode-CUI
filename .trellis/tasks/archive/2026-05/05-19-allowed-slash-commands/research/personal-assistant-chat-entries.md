# Research: 个人助手 chat 入口清单（first / retry / normal）

- **Query**: 个人助手 chat 完整入口（first / retry / normal）+ 是否已组装 platformExtParam + payload 关键字段
- **Scope**: internal
- **Date**: 2026-05-19
- **参考 commit**: `2b2075f fix(skill-server): personal 助手首次对话 retry chat payload 补全上下文字段`

## 三种入口的判定（基于代码分支）

| 类型 | 判定条件 | 触发点 |
|---|---|---|
| **first** | `SkillSession` 不存在 → 异步建会话；首条用户消息在 GW `session_created` 回调到来前被缓存到 Redis pending list | `InboundProcessingService.processChat` 情况 A（`session == null`） + `ImSessionManager.createSessionAsync` |
| **retry** | GW 回 `session_created` 之后，从 Redis pending list FIFO 重放未投递的消息 | `GatewayMessageRouter.handleSessionCreated` → `retryPendingMessages` |
| **normal** | `SkillSession` 已存在 + `toolSessionId` 已就绪 → 直接转发当前用户消息到 GW | `InboundProcessingService.processChat` 情况 C / `SkillMessageController.routeToGateway` chat 分支 |

> 注：`InboundProcessingService.processChat` 情况 B（session 在但 `toolSessionId` 缺失）personal 分支也走 rebuild，本质是 "首次 / retry" 的退化形态；为简化把它归并到 first（行为一致：消息入 pending，等回调）。

## Personal 助手 chat 入口清单

### A. miniapp 通道（HTTP REST，用户主动发消息）

#### A.1 normal chat — `SkillMessageController.sendMessage` → `routeToGateway`

| 项 | 值 |
|---|---|
| Controller | `com.opencode.cui.skill.controller.SkillMessageController` |
| 方法 | `sendMessage(...)` → 内部委托 `routeToGateway(...)` |
| HTTP 路径 | `POST /api/skill/sessions/{sessionId}/messages` |
| 文件:行 | `SkillMessageController.java:115` (sendMessage)，`:180-264` (routeToGateway) |
| 触发条件 | session 已存在 + `toolSessionId` 就绪 (`SkillMessageController.java:210`) |
| 是否已组装 platformExtParam | **是**，通过 `gatewayRelayService.sendInvokeToGateway(new InvokeCommand(..., session.getBusinessSessionDomain(), session.getBusinessSessionType(), session.getBusinessSessionId()))` (`SkillMessageController.java:258-263`)，由 `GatewayRelayService.buildInvokeMessage` 完成（personal scope） |
| payload 关键字段 | `text` / `toolSessionId` / `sendUserAccount` / `assistantAccount` / `messageId` / `businessExtParam` (`SkillMessageController.java:244-252`) |
| 备注 | `toolSessionId` 缺失时 `routeToGateway` 提前调 `rebuildToolSession`（走 A.2 first 路径） (`SkillMessageController.java:210-216`) |

#### A.2 first chat — `SkillMessageController.routeToGateway` → `rebuildToolSession`

| 项 | 值 |
|---|---|
| Controller / Service | `SkillMessageController.routeToGateway` → `GatewayRelayService.rebuildToolSession(sessionId, session, request.getContent())` 老 String 重载 |
| HTTP 路径 | 同 A.1 `POST /api/skill/sessions/{sessionId}/messages`，但走 `toolSessionId == null` 分支 |
| 文件:行 | `SkillMessageController.java:210-215` |
| 触发条件 | session 存在但 `toolSessionId` 缺失 |
| 是否已组装 platformExtParam | **暂未**，因为它走的是 `GatewayRelayService.rebuildToolSession(String, SkillSession, String)` 老 String 重载，内部把消息以"半填 PendingChatRequest"(`fields_degraded=businessExtParam`) 入 pending list，等待 retry —— 见 `SessionRebuildService.java:198`。`PendingChatRequest.fromSessionFallback` 会从 `session.getBusinessSessionDomain() / Type()` 填充 `businessSessionDomain` / `businessSessionType` (`PendingChatRequest` 注释 `:108-109`)，**所以 retry 时仍然能拿到 domain/type** |
| payload 关键字段（pending list 落地） | `text=request.getContent()`；其余字段从 session 反查（fallback） |

> **重要**：A.2 走 `rebuildToolSession` 老 String 重载会打 `WARN [rebuild_legacy_string_overload]` (`SessionRebuildService.java:198`)；上线后看到这个日志说明 miniapp 通道 first-chat 还在走老路径，PR3 已留 TODO 但未迁移（见 `ImSessionManager.java:231-233` 的 @Deprecated 注释）。

#### A.3 retry chat — `GatewayMessageRouter.retryPendingMessages`

| 项 | 值 |
|---|---|
| Service | `com.opencode.cui.skill.service.GatewayMessageRouter` |
| 方法 | `retryPendingMessages(String sessionId, String ak, String userId, String toolSessionId)` |
| HTTP 路径 | （非 HTTP）由 GW WS 上行 `session_created` 触发：`handleSessionCreated` (`:822`) → `retryPendingMessages` (`:849`) |
| 文件:行 | `GatewayMessageRouter.java:869` (retryPendingMessages 实现)；`:822-849` (调用链) |
| 触发条件 | GW 上行 `type=session_created` + 对应 sessionId 的 pending list 非空 |
| 是否已组装 platformExtParam | **是**，在 `retryPendingMessages` 内显式构造 `extParameters.platformExtParam`（`GatewayMessageRouter.java:920-933`），从 `PendingChatRequest.businessSessionDomain/Type` + `req.imGroupId()` 取值；然后由 `GatewayRelayService.buildInvokeMessage` 的幂等保护跳过二次注入 |
| payload 关键字段 | `text` / `toolSessionId`（方法入参） / `assistantAccount` / `sendUserAccount` / `imGroupId` / `messageId` / `extParameters.{businessExtParam,platformExtParam}` (`:906-933`) |

### B. IM 入站通道（WeLink → POST `/api/inbound/messages`）

#### B.1 normal chat — `InboundProcessingService.processChat` 情况 C

| 项 | 值 |
|---|---|
| Controller | `com.opencode.cui.skill.controller.ImInboundController.receiveMessage` |
| 委托方法 | `InboundProcessingService.processChat(...)` → `dispatchChatToGateway(...)` |
| HTTP 路径 | `POST /api/inbound/messages` |
| 文件:行 | `ImInboundController.java:41` → `InboundProcessingService.java:128` (processChat)；`:243-251` (情况 C 入口)；`:339-408` (dispatchChatToGateway) |
| 触发条件 | session 已存在 + `toolSessionId` 就绪 + personal scope (`InboundProcessingService.java:244-246`，`appendToPending = caseCStrategy.generateToolSessionId() == null`) |
| 是否已组装 platformExtParam | **是**，`dispatchChatToGateway` 末尾 `sendInvokeToGateway(new InvokeCommand(..., businessDomain, sessionType, sessionId))` (`InboundProcessingService.java:397-404`) → personal scope → `GatewayRelayService.buildInvokeMessage` 组装 |
| payload 关键字段 | `text` / `toolSessionId` / `assistantAccount` / `sendUserAccount` (effectiveSender) / `imGroupId` / `messageId` / `businessExtParam` (`:374-381`) |
| 备注 | 同步会写一份完整 `PendingChatRequest` 入 pending list（`appendToPending = true`，`:358-372`），但这里入队**只对 retry 路径有意义**——pending list 是 personal scope 的 retry 消费者 |

#### B.2 first chat — `InboundProcessingService.processChat` 情况 A → `ImSessionManager.createSessionAsync`

| 项 | 值 |
|---|---|
| Controller / Service | `ImInboundController.receiveMessage` → `InboundProcessingService.processChat` 情况 A → `ImSessionManager.createSessionAsync(...)` |
| 方法 | `ImSessionManager.createSessionAsync(businessDomain, sessionType, sessionId, ak, ownerWelinkId, assistantAccount, senderUserAccount, prompt, businessExtParam)` |
| HTTP 路径 | `POST /api/inbound/messages`，走 session 不存在分支 |
| 文件:行 | `InboundProcessingService.java:165-176` (情况 A 入口)；`ImSessionManager.java:98-206` (createSessionAsync 实现) |
| 触发条件 | session 不存在 (`session == null`) |
| 是否已组装 platformExtParam | **未在 first 这一步组装**。personal 分支（`ImSessionManager.java:176-201`）只把消息构造成完整 `PendingChatRequest`（含 `businessSessionDomain` / `businessSessionType` 两字段，`:194-195`）→ `requestToolSession(created, pendingRequest)`（`:201`）→ `GatewayRelayService.rebuildToolSession(PendingChatRequest)` 新签名，消息入 Redis pending list。**platformExtParam 在 retry 阶段才组装**（见 A.3 / B.3） |
| pending entry 字段 | `text=pendingMessage` / `assistantAccount` / `effectiveSender` / `imGroupId=(group ? sessionId : null)` / `messageId=System.currentTimeMillis()` / `businessExtParam` / `businessSessionDomain=businessDomain` / `businessSessionType=sessionType` (`ImSessionManager.java:187-195`) |
| 备注 | business scope（同方法的 `if (generatedToolSessionId != null)` 分支，`:145-175`）会**立即发** chat invoke（不入 pending list），所以本研究只关注 personal 分支 |

#### B.3 retry chat — `GatewayMessageRouter.retryPendingMessages`

同 A.3（路径共享）。无论 first 触发自 miniapp 通道还是 IM 通道，都汇聚到 GW `session_created` 回调 → `retryPendingMessages`，从 `PendingChatRequest` 重建完整 chat invoke payload。

### C. External 通道（开放给外部业务方）

#### C.1 normal / first chat — `ExternalInboundController.invoke` action=`chat`

| 项 | 值 |
|---|---|
| Controller | `com.opencode.cui.skill.controller.ExternalInboundController` |
| 委托方法 | `InboundProcessingService.processChat(...)` 同 B |
| HTTP 路径 | `POST /api/external/invoke` (action=`chat`) |
| 文件:行 | `ExternalInboundController.java:39` (`@PostMapping("/invoke")`)；`:61-68` (chat action 分支) |
| 是否已组装 platformExtParam | 与 B.1 / B.2 一致（共用 `InboundProcessingService.processChat`） |
| 备注 | `inboundSource = "EXTERNAL"`，与 IM 唯一区别是日志标签和 `writeInvokeSource` 的值 |

## 总览矩阵

| 入口标签 | 文件:行 | 入口类型 | platformExtParam 已落地？ | platformExtParam 来源 |
|---|---|---|---|---|
| miniapp 通道 normal | `SkillMessageController.java:258-263` | normal | 是 | `session.getBusinessSessionDomain/Type/Id()` 直接灌入 `InvokeCommand` |
| miniapp 通道 first (toolSession 缺) | `SkillMessageController.java:210-215` | first（pending 入队） | 否（仅入 pending）；retry 时落 | 通过 `rebuildToolSession` 老 String 重载入 pending；`PendingChatRequest.fromSessionFallback` 从 `session.getBusinessSessionDomain/Type()` 补 (`PendingChatRequest.java:108-109`) |
| IM 通道 normal (情况 C) | `InboundProcessingService.java:397-404` | normal | 是 | request 入参 `businessDomain` / `sessionType` / `sessionId` 灌入 `InvokeCommand` |
| IM 通道 first (情况 A) | `ImSessionManager.java:163-205` | first（personal 入 pending；business 即时发） | personal 否、business 是 | personal：入 pending 待 retry；business：`businessDomain` / `sessionType` / `sessionId` 灌入 InvokeCommand |
| External 通道 chat | `ExternalInboundController.java:61-68` | normal/first（按 session 存在与否走 B.1/B.2） | 同 B | 同 B |
| **retry（所有通道汇聚）** | `GatewayMessageRouter.java:869-953` | retry | 是 | `PendingChatRequest.businessSessionDomain/Type` + `req.imGroupId()` 显式组装 extParameters |

## 关键观察（对 allowed-slash-commands 任务的影响）

1. **三种入口的 "数据源" 不一样**：
   - **normal**: 直接从 `SkillSession` 或 request 入参拿 domain/type
   - **first (personal)**: 从 request 入参或 session 拿，写入 `PendingChatRequest`
   - **retry**: 从 `PendingChatRequest` 反序列化拿
2. **PRD 决策"三个入口都要下发 `allowedSlashCommands`"** 意味着实现至少要覆盖：
   - normal 路径：3 个 strategy builder（business/default_assistant/personal）+ `GatewayRelayService.buildInvokeMessage`
   - first 路径：先把 list 写进 `PendingChatRequest`（或入队时不写、retry 时再现取）
   - retry 路径：`GatewayMessageRouter.retryPendingMessages` 在组装 extParameters 时一并 set
3. **first 写入 pending 时即时取 sysconfig vs retry 时再取**：
   - 即时取：避免 retry 时 sysconfig 已被更新导致 first / retry 看到不同 list（一致性优先）
   - retry 时取：最新值，但 sysconfig 在 TTL 内被改时 retry 仍可能拿旧 cache（一致性差），且需要 retry 路径有 domain/type（已有 `req.businessSessionDomain/Type`）
   - PRD 未明确，需要 prd.md 收口
4. **business / default_assistant scope 是否也需要 `allowedSlashCommands`？** PRD 标题写"个人助手 chat 场景"，但实际 `platformExtParam` 在三种 scope 共用，业务侧很可能后续也要给 business / default_assistant 配。当前任务范围以 PRD 为准（仅 personal），但 builder 改造时建议保持三处对称，避免一处下发一处不下发的不一致。

## Caveats

- 没有发现独立"plugin 端入口"（plugin 不直接发 chat 给 SS，它只通过 GW 回调收 invoke），所以 plugin 这一侧不需要单独的入口改造。
- WebSocket 主动消息（`ws/` 包下的 `ExternalStreamHandler`、`UserStreamHandler` 等）只承担推送 SS→client 的下行 SSE 流，**不发起 chat invoke**，不在本研究的入口集合内。
