# 下行消息 platformExtParam 补充 businessSession 字段

## Goal

让 skill-server 在云端助手（business / default_assistant scope）和个人助手（personal scope）
两条链路的所有下行消息里，把 `extParameters.platformExtParam` 从当前 `{}` 占位升级为携带三字段：
`businessSessionDomain` / `businessSessionType` / `businessSessionId`。缺失时各字段序列化为 `null`
（key 保留）。下游 agent 据此感知"消息属于哪个业务域 + 会话类型 + 业务侧会话 ID"。

## Requirements

- **R1**：云端助手 `BusinessScopeStrategy.buildInvoke` / `DefaultAssistantScopeStrategy.buildInvoke`
  把 `platformExtParam` 从 `{}` 改为含三字段对象，**通过新抽出的 `PlatformExtParamBuilder` 公共
  helper 构造**（参见 R8）。
- **R2**：个人助手路径 `GatewayRelayService.buildInvokeMessage` 也补
  `extParameters.platformExtParam` 信封；**同时把当前 `payload.businessExtParam`（顶层）搬到
  `payload.extParameters.businessExtParam`**，跟 business wire 形态对齐（P2a；已 grep 验证
  ai-gateway / plugin / 个人 agent / 前端零消费 businessExtParam）。`payload.imGroupId`
  字段保留不动（H3，详见 R9）。ai-gateway 零改动。
- **R3**：取数规则（全部直接读入参 / 实体字段，**不做映射推断**）：
  - **入参直拿**：`ImMessageRequest.businessDomain/sessionType/sessionId`、
    `ExternalInvokeRequest.businessDomain/sessionType/sessionId`、
    miniapp `createSession` 的 `businessSessionDomain/businessSessionType` 入参
  - **DB 反查**：已经持有 `SkillSession` 对象的位置（`SkillMessageController.routeToGateway`、
    `replyPermission` 等），直接读 `session.getBusinessSessionDomain()` / `getBusinessSessionType()`
    / `getBusinessSessionId()` getter
- **R4**：作用范围 —— 除 `route_confirm` / `route_reject` 外，**所有出向 GW 的 action 都带**：
  `chat` / `question_reply` / `permission_reply` / `create_session` / `close_session` /
  `abort_session` / `rebuild` + pending replay + `session_created` 后重发。
- **R5**：pending 队列字段扩展（**修正自初稿误判**）—— pending list 早已是 JSON 信封
  （`SessionRebuildService` PR2 升级，存 `PendingChatRequest` record JSON），不是纯文本。
  本任务**只在 `PendingChatRequest` record 新增两个字段** `businessSessionDomain` /
  `businessSessionType`（`businessSessionId` 复用现有 `imGroupId` 字段语义，见 R9）。
  `fromSessionFallback` 同步从 `session.getBusinessSession*()` 取值兜底；缺失时各字段 null。
  Jackson 反序列化对老 entry（缺新字段）自动兜底 null，record canonical constructor 不做强校验。
- **R6**：缺失字段序列化为 JSON `null`，保留 key（不省略）。统一由 R8 的 helper 处理。
- **R7**：`chatHistory` 内历史消息**不带** `platformExtParam`（与 D15 对齐；当前 chatHistory
  形态本来就没有 extParameters 信封，是冗余约束，在 spec 写明即可）。
- **R8**：**新建公共 helper** `PlatformExtParamBuilder.build(ObjectMapper, domain, type, id)`
  —— 三处构造点（BusinessScopeStrategy / DefaultAssistantScopeStrategy / GatewayRelayService
  + retryPendingMessages）共用，避免行为漂移。
- **R9**：**`imGroupId` 字段名保留不动**（review H3 选项 a）。理由：grep 显示 `imGroupId` 在
  30+ callsite 使用，重命名风险大；platformExtParam 内单独写 `businessSessionId`，接受短期
  "payload.imGroupId 与 platformExtParam.businessSessionId 同值"的命名冗余，在 spec / 注释写清。
- **R10**：**顺手修隐藏 bug**（review H1 / H2 / H4）—— 当前 6 个 callsite 用 5/6 参 InvokeCommand
  构造器，没传 `domain` / `domainType`，导致 IM/external 入站默认助手反查永远 miss。本任务一起改 8 参：
  - `InboundProcessingService.java:391` / `:456` / `:520`（3 处）
  - `ImSessionManager.java:163` / `:312`（2 处）
  - `SessionRebuildService.java:162`（rebuildToolSession sendInvoke，1 处）
  - `GatewayMessageRouter.java:927`（retryPendingMessages sendInvokeToGateway，1 处）
  共 7 个 callsite。

## Acceptance Criteria

- [ ] business + default_assistant 两个 scope 的 chat / question_reply / permission_reply /
      create_session / close_session / abort_session 下行消息，
      `extParameters.platformExtParam.{businessSessionDomain, businessSessionType, businessSessionId}`
      正确填充
- [ ] personal scope 同上 + rebuild / pending replay / session_created 重发也带
- [ ] personal 路径 `businessExtParam` 从 `payload.businessExtParam` 搬到 `payload.extParameters.businessExtParam`
- [ ] 三字段值：能从入参拿就从入参（IM/external/miniapp 入参字段），其余从 `SkillSession` getter
- [ ] 缺失字段序列化为 `null`，JSON 保留 key 不省略
- [ ] `PendingChatRequest` record 加 2 字段，`fromSessionFallback` 扩展取值
- [ ] `retryPendingMessages` 重建 chatPayload 时注入 `extParameters` 信封
- [ ] **6 个 InvokeCommand callsite 升 8 参**（H1/H2/H4 隐藏 bug 修复），传 domain/domainType + businessSessionId
- [ ] `route_confirm` / `route_reject` 不带
- [ ] 既有单测 / 集成测全绿；新增三 scope × 三字段填充 × 缺失兜底测；
      `ExtParametersIntegrationTest` 端到端覆盖 platformExtParam

## Definition of Done

- 单测覆盖 PlatformExtParamBuilder helper + 三 scope 路径 + retry 路径
- 集成测端到端 JSON 形态验证（含 missing → null）
- mvn 编译 + 全套测试绿
- spec `2026-04-27-extparams-passthrough-design.md` 增补：D2 升级为"`platformExtParam` 含三字段"，
  D5/D16 升级为"personal 路径在 skill-server 构造 extParameters 信封"，
  D17/D19 升级为"pending entry record 含 businessSession 三字段"
- spec `2026-04-07-cloud-agent-protocol.md` 更新 extParameters 字段说明
- 备忘录强制要求的 codex (gpt-5.5 + xhigh) 评审过 Critical（本轮主会话自评的 codex-review.md
  已落盘，建议事后再过一次真 codex）

## Out of Scope

- ai-gateway 侧改动（零改动）
- skill-miniapp / plugin 端 UI（透明）
- 三字段值的服务端校验 / 大小限制
- 上游 DTO 加新字段（现有入参字段已足够）
- `imGroupId` 字段重命名（review H3 (b) 选项，独立 refactor PR）

## Technical Approach

### 数据流向（高层）

```
入参 (ImMessageRequest / ExternalInvokeRequest / CreateSessionRequest) ──┐
                                                                          ├─→ InvokeCommand 8 参（含 domain/domainType/businessSessionId）
SkillSession.getter (DB) ─────────────────────────────────────────────────┘                                  │
                                                                                                              ↓
                                ┌─── PlatformExtParamBuilder.build(domain, type, id) ───┐
                                │                                                         │
   BusinessScopeStrategy ───────┤                                                         │
   DefaultAssistantScopeStrategy ┤  → extParameters.platformExtParam = {三字段}            │
   GatewayRelayService.buildInvokeMessage ┤  → payload.extParameters.{businessExtParam, platformExtParam}
   GatewayMessageRouter.retryPendingMessages ─┘
                                                                                          │
                                          SessionRebuildService pending entry ────────────┘
                                            (PendingChatRequest record 5 字段 + 2 新增)
```

### Decision (ADR-lite)

- **Context**：
  - 协议形态 `extParameters = { businessExtParam, platformExtParam }` 已定型（spec 2026-04-27），
    platformExtParam 占位 `{}` 多月未填
  - personal 路径今天没构造 extParameters 信封（D5/D16 设计），businessExtParam 裸在 payload 顶层
  - pending 队列已经是 JSON 信封（D17/D19 PR2 升级），不是纯文本（初稿误判）
  - 现有 6 个 InvokeCommand callsite 在 IM/external/rebuild/retry 路径用老构造器，domain/domainType
    没传 —— 默认助手反查在这些路径永远 miss（隐藏 bug，review H1/H2/H4）
- **Decision**：
  - 三 scope 统一在 skill-server 构造 platformExtParam 三字段，ai-gateway 零改动
  - personal 路径 wire 形态升级与 business 对齐（P2a），businessExtParam 搬入 extParameters 信封
  - pending entry record 加 2 字段，复用 imGroupId 作为 businessSessionId 来源
  - 抽 `PlatformExtParamBuilder` 公共 helper，三处共用
  - 顺手修 6 callsite 的 InvokeCommand 8 参隐藏 bug
- **Consequences**：
  - skill-server 成为 platformExtParam 的唯一权威源
  - personal 路径 wire 一次性变化（businessExtParam 移位 + 新增 extParameters 信封）
  - imGroupId 字段名与 businessSessionId 短期并存（命名冗余），后续可独立 refactor PR 改名
  - 默认助手路由在所有入口都能命中（顺手修 H1/H2/H4 副产品）

### Implementation Plan (small PRs)

- **PR1（云端助手 + scaffolding + 隐藏 bug 修复）**：
  - 新增 `PlatformExtParamBuilder` helper（R8）
  - `InvokeCommand` record 新增 `businessSessionId` 字段（复用 `domain` / `domainType`）
  - `BusinessScopeStrategy.buildInvoke` / `DefaultAssistantScopeStrategy.buildInvoke`：
    `platformExtParam` 从 `{}` 改为 `PlatformExtParamBuilder.build(...)`
  - 7 个 InvokeCommand callsite 全部升 8 参，传 domain/domainType + businessSessionId：
    - miniapp 三处已是 8 参（SkillSessionController:170/250/291、SkillMessageController:258/470），
      只需新增 businessSessionId 参数
    - **新升级的 6 处**：InboundProcessingService×3 / ImSessionManager×2 / SessionRebuildService×1
      （前 5 处入参里已有 businessDomain/sessionType/sessionId，最后 1 处用 session getter）
    - **第 7 处** GatewayMessageRouter:927（retryPendingMessages）在 PR2 改（需要先有 PendingChatRequest
      新字段）
  - 单测：PlatformExtParamBuilder + 两 strategy 填充 / 缺失兜底
- **PR2（个人助手 + pending 字段扩展）**：
  - `PendingChatRequest` record 新增 `businessSessionDomain` / `businessSessionType` 字段（R5）
  - `PendingChatRequest.fromSessionFallback` 扩展取 `session.getBusinessSession*()` 三字段
  - `GatewayRelayService.buildInvokeMessage`：
    构造 `payload.extParameters` 信封 + 移入 businessExtParam + 注入 platformExtParam（R2）
  - `GatewayMessageRouter.retryPendingMessages` (line 906–928)：
    构造 chatPayload 时注入 `extParameters` 信封；`new InvokeCommand(...)` 升 8 参（R10）
  - 单测：personal 路径 wire 形态 / retry 路径绑定值
- **PR3（集成测 + spec）**：
  - `ExtParametersIntegrationTest` 风格端到端用例覆盖 platformExtParam
  - spec `2026-04-27-extparams-passthrough-design.md`：升级 D2 / D5 / D16 / D17 / D19
  - spec `2026-04-07-cloud-agent-protocol.md`：增补 platformExtParam 字段说明
  - 在 spec 写明：`payload.imGroupId` 与 `payload.extParameters.platformExtParam.businessSessionId`
    短期并存的命名冗余约定

### Codex 评审节点

按 `feedback_codex_design_review` 备忘录约束：**本轮主会话已自评，产物在
`research/codex-review.md`**（Critical 项已吸收进本 PRD）。建议 PR1 开工前再过一次真
codex (gpt-5.5 + xhigh) 评审做补强。

## Technical Notes

- 关键文件清单：
  - `skill-server/.../scope/BusinessScopeStrategy.java`
  - `skill-server/.../scope/DefaultAssistantScopeStrategy.java`
  - `skill-server/.../service/GatewayRelayService.java`（buildInvokeMessage + retryPendingMessages）
  - `skill-server/.../service/GatewayMessageRouter.java`（retryPendingMessages line 906–928 + line 927 callsite）
  - `skill-server/.../service/SessionRebuildService.java`（rebuildToolSession line 162 callsite）
  - `skill-server/.../service/InboundProcessingService.java`（391/456/520 三个 callsite）
  - `skill-server/.../service/ImSessionManager.java`（163/312 两个 callsite）
  - `skill-server/.../service/PayloadBuilder.java`（嵌套对象支持已有）
  - `skill-server/.../controller/SkillMessageController.java`（已 8 参，加 businessSessionId）
  - `skill-server/.../controller/SkillSessionController.java`（已 8 参，加 businessSessionId）
  - `skill-server/.../controller/ImInboundController.java`
  - `skill-server/.../controller/ExternalInboundController.java`
  - `skill-server/.../model/InvokeCommand.java`（+businessSessionId）
  - `skill-server/.../model/PendingChatRequest.java`（+2 字段 + fromSessionFallback 扩展）
  - `skill-server/.../model/SkillSession.java`（getter 已存在）
  - `skill-server/.../model/ImMessageRequest.java` / `ExternalInvokeRequest.java`（入参字段已存在）
  - **新建** `skill-server/.../service/PlatformExtParamBuilder.java`
- 参考 spec：
  - `docs/superpowers/specs/2026-04-27-extparams-passthrough-design.md`（要更新 D2/D5/D16/D17/D19）
  - `docs/superpowers/specs/2026-04-07-cloud-agent-protocol.md`（要更新）
- 参考测试：`ExtParametersIntegrationTest` / `BusinessScopeStrategyTest` /
  `AssistantSquareCloudRequestStrategyTest`
- Codex review 报告：`research/codex-review.md`
