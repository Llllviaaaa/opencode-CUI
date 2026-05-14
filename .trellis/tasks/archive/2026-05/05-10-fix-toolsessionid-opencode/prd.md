# fix: 白名单外助理的 toolSessionId 仍走云端逻辑导致本地 opencode 对话失败

## Goal

修复 BusinessWhitelist 不命中（业务助理被踢出白名单 / 未配置白名单）时，
`toolSessionId` 仍以 `cloud-` 前缀（云端 ID）保留在 `SkillSession.toolSessionId`
字段中，导致后续消息走 personal/opencode 路径却用 cloud-XXX ID 转发到 GW，
最终在 opencode 那侧匹配不到 session，对话无法正常进行。

## What I already know（来自代码）

### 白名单 gate 已经生效
- `AssistantScopeDispatcher.getStrategy(AssistantInfo)` (skill-server/.../scope/AssistantScopeDispatcher.java:57)
  - scope=="business" + 白名单不命中 → 返回 PersonalScopeStrategy（降级本地）
- `BusinessWhitelistService.allowsCloud()` (skill-server/.../service/BusinessWhitelistService.java:52)
  - 总开关、tag 必填、5min Redis 缓存、fail-open 容错

### toolSessionId 生成路径（全部走 dispatcher.getStrategy(info) ✅ 含白名单 gate）
- `SkillSessionController.createSession` (skill-server/.../controller/SkillSessionController.java:113-135)
- `ImSessionManager.createSessionAsync` (skill-server/.../service/ImSessionManager.java:140-174)
- `InboundProcessingService.processChat` 情况 B (.../service/InboundProcessingService.java:177-222)
- `InboundProcessingService.processRebuild` (.../service/InboundProcessingService.java:543-569)

### 关键事实
- `BusinessScopeStrategy.generateToolSessionId()` 永远返回 `"cloud-" + uuid`（.../scope/BusinessScopeStrategy.java:141）
- `PersonalScopeStrategy.generateToolSessionId()` 永远返回 null（.../scope/PersonalScopeStrategy.java:60）
  → 由 GW 回调 `session_created` 绑定真实 OpenCode session ID（无 cloud- 前缀）
- `dispatchChatToGateway`（.../InboundProcessingService.java:320）情况 C 路径直接读
  `session.getToolSessionId()` 转发给 GW，**不重新校验 toolSessionId 是否匹配当前 strategy**
- 所有 `scopeDispatcher.getStrategy()` 调用点都是 `(AssistantInfo)` 重载（含白名单 gate） ✅

### 对应执行路径
1. T0：白名单关 / fail-open / 助理在白名单 → BusinessScopeStrategy 写入 `cloud-XXX`
2. T1：白名单开 + 助理不在白名单 → dispatcher 切到 PersonalScopeStrategy
3. T2：用户在 IM/miniapp 继续聊天 → `processChat` 情况 C（session 已就绪、toolSessionId=`cloud-XXX`）
4. T3：`dispatchChatToGateway` 用旧 `cloud-XXX` 发 chat → GW personal 路径 → opencode → 找不到 session

## Confirmed by user
- **观察证据**：DB 里看到不在白名单的业务助理的 `session.tool_session_id` 是 `cloud-` 前缀，预期应为 opencode 真实 ID。
- **业务规则**：
  - 业务助理（在白名单）：toolSessionId 由 SS 本地生成 `cloud-XXX` ✅
  - 业务助理（不在白名单）：会话创建应走 personal 流程（GW create_session → opencode 真实 ID）；会话重建（无 toolSessionId / 失败）也应走 personal 重建流程

## Root Cause（基于代码审计 + 用户观察推断）

**根因**：dispatcher（含白名单 gate）只在【当下要不要生成 cloud-XXX】这个决策点起作用，**没有任何机制处理"DB 里已经存了 cloud-XXX 但当前 strategy 切到 personal"的存量错位**。

**触发时机** — DB 里 cloud-XXX 出现的来源：
1. **存量数据**：session 的 toolSessionId 是在【该助理仍在白名单 / 白名单未启用 / fail-open 命中】时创建的，后来助理被移出白名单，但 cloud-XXX 留在 DB。
2. **白名单变更**：管理员调整白名单（删 tag / 关开关）后，已存在的 session 没有联动清理。

**透传路径**（所有都直接读 `session.getToolSessionId()` 转发，不验证）：
- `InboundProcessingService.dispatchChatToGateway`（line 320-370）
- `InboundProcessingService.processQuestionReply`（line 415-433）
- `InboundProcessingService.processPermissionReply`（line 479-510）
- `SkillMessageController.routeToGateway`（line 200-242）
- `SkillMessageController.replyPermission`（line ~432）
- `SkillSessionController.closeSession / abortSession`（line 197-205, 231-238）

→ **任何一条路径**只要 session 已有 toolSessionId，就直接透传给 GW。dispatcher 当下判定 personal 也救不了——cloud-XXX 还是发出去。GW 按 personal 路径转给 opencode，opencode 找不到对应 session → 对话失败。

**反向 bug 也存在**（待用户确认要不要一起修）：
- personal 助理升级 → business + 进入白名单：旧的非 cloud- toolSessionId 仍在 DB
- 后续转发会带着这个旧 ID 给 GW，但 GW 的 business 路径期望 cloud- 前缀

## Requirements（evolving）

- 不在白名单的业务助理，下一次发消息时，必须走 personal/opencode 路径，且 GW/opencode 收到的 toolSessionId 必须是 OpenCode 真实 session ID（不能是 `cloud-` 前缀的 ID）。

## Acceptance Criteria（evolving）

- [ ] 业务助理在白名单内时，DB 里 toolSessionId = `cloud-XXX`
- [ ] 把同一助理移出白名单后，下一次发消息：
  - DB 中的 `cloud-XXX` 被清掉/替换
  - GW 收到 personal 路径的 invoke，toolSessionId 是 OpenCode 真实 ID
  - 用户在 miniapp / IM 能正常收到回复
- [ ] 反向场景：personal 助理加进白名单后，行为符合预期（待 Open Question 确认）
- [ ] 单元/集成测试覆盖：whitelist 切换 → toolSessionId 重建路径
- [ ] 不影响白名单稳定生效场景（business 一直在 / 一直不在白名单）

## Definition of Done

- 单元 + 集成测试覆盖切换路径
- `cd skill-server && mvn -DskipITs=false test` 通过
- 通过 spec 检查（`.trellis/spec/skill-server/backend/`）
- 日志清晰：能从日志看出"检测到 toolSessionId 与当前 strategy 不匹配 → 重建"

## Out of Scope（预设，可调整）

- ai-gateway / skill-miniapp 侧改动（默认只改 skill-server，除非 Q&A 表明需要）
- 白名单管理页 UI（属于配置侧，不影响本次修复）

## Technical Approach（候选）

### 候选 A：被动检测 + 自动重建（推荐主线）
- 在 `AssistantScopeStrategy` 接口加 `boolean ownsToolSessionId(String id)`：
  - `BusinessScopeStrategy`: `id != null && id.startsWith("cloud-")`
  - `PersonalScopeStrategy`: `id != null && !id.startsWith("cloud-")`
- 在所有"读 session.toolSessionId 转发"的入口前加一个统一 gate（封装为
  `SkillSessionService.ensureStrategyOwnedToolSessionId(session, strategy)` 或工具方法）：
  - strategy.ownsToolSessionId(session.toolSessionId) == true → 通过
  - false → `clearToolSessionId(session.id)` + 走 strategy 对应的重建路径
    - personal: `gatewayRelayService.rebuildToolSession(...)`（GW create_session）
    - business: `tryHealBusinessToolSessionId(...)`（本地生成新 cloud-XXX）
- 入口列表（最少 6 个）：
  - `InboundProcessingService.dispatchChatToGateway`
  - `InboundProcessingService.processQuestionReply`
  - `InboundProcessingService.processPermissionReply`
  - `SkillMessageController.routeToGateway`
  - `SkillMessageController.replyPermission`
  - 可选：`SkillSessionController.closeSession / abortSession`（关会话场景影响小）
- 优点：纯 SS 内修复，逻辑集中；存量 cloud-XXX 自动被检测
- 缺点：用户首条消息会多一跳 rebuild 延迟（可接受）

### 候选 B：白名单变更时主动 reset DB
- 白名单 / 总开关变更时，发 Redis pub/sub 事件，触发 SS 扫所有受影响的 session 清空 toolSessionId
- 优点：用户首条消息无额外延迟
- 缺点：侵入式 pub/sub、扫表开销、漏推/丢消息风险；并且**只覆盖切换时刻**，不能解决"创建瞬间 fail-open + 之后白名单生效"

### 候选 C：候选 A 主线 + 候选 B 优化（兜底分层）
- 候选 A 作为 unconditional safety net（任何错位都自动恢复）
- 候选 B 作为可选优化（提升首条消息体验），MVP 先不做

### 候选 D：在 dispatcher 内做副作用（不推荐）
- `getStrategy(AssistantInfo, SkillSession)` 重载，判定 strategy 时同时清空错位的 toolSessionId
- 缺点：违反单一职责，dispatcher 是热路径只读查询，加副作用风险大

## Decision (ADR-lite)

待与用户对齐后填。

## Technical Notes

- 关键文件：
  - `skill-server/.../service/scope/AssistantScopeDispatcher.java`
  - `skill-server/.../service/scope/{Personal,Business}ScopeStrategy.java`
  - `skill-server/.../service/InboundProcessingService.java`（情况 C：320-370 行）
  - `skill-server/.../service/ImSessionManager.java`
  - `skill-server/.../controller/SkillSessionController.java`
- 已读 spec：
  - `.trellis/spec/skill-server/backend/index.md`
  - 待读：`conventions.md`、`type-safety.md`、`database-guidelines.md`
- 相关历史 PR / 文档：
  - `docs/superpowers/specs/2026-04-27-business-tag-whitelist-design.md`
  - `docs/superpowers/plans/2026-04-27-business-tag-whitelist.md`
