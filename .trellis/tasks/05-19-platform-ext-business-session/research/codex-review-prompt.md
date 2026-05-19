# Codex Design Review Prompt — platformExtParam 补充 businessSession 字段

> 把这份内容贴给 codex (gpt-5.5 + xhigh)，请它做 Critical/High/Medium 三档评审。
> 评审产物请回填到 `codex-review.md`（同目录）。

---

## 你的角色

你是 senior backend engineer 兼协议设计评审人。请对下面的设计做严格评审，**特别关注 wire 兼容性、并发/重发边界、pending 队列升级、双链路一致性**。

判定档位：
- **Critical**：会导致线上消息丢失 / 协议破坏 / 数据错绑的问题，必须修
- **High**：会导致语义不一致 / 边界 case bug / 难以回滚的问题，强烈建议修
- **Medium**：代码可维护性 / 可测性 / spec 不完整等问题，可作为后续优化

---

## 项目背景

- **仓库**：opencode-CUI（multi-module Java/Spring + TS 前端）
- **本任务范围**：skill-server（Java/Spring）
- **协议链路**：
  - 云端助手：miniapp/IM/external → skill-server → ai-gateway (GW) → 云端 agent
  - 个人助手：miniapp/IM/external → skill-server → ai-gateway → plugin → 个人 agent
- **现有 spec**：`docs/superpowers/specs/2026-04-27-extparams-passthrough-design.md`（已建立 `extParameters = { businessExtParam, platformExtParam }` 两层信封）

---

## 本次需求

在两条链路的**所有下行消息**里，让 `extParameters.platformExtParam` 携带三个字段：
- `businessSessionDomain`（`miniapp` / `im` / `external` 等）
- `businessSessionType`（`group` / `direct` / null）
- `businessSessionId`（IM 群 ID / 单聊 ID / external 业务 ID / null）

缺失时**各字段序列化为 JSON `null`**，key 始终保留。

---

## 锁定的 8 项决策

| # | 维度 | 决定 |
|---|---|---|
| 1 | platformExtParam 由谁填 | skill-server 自己填；ai-gateway / plugin / 前端零改动 |
| 2 | 哪些 action 带 | 除 `route_confirm` / `route_reject` 外**全部**（含 `chat` / `question_reply` / `permission_reply` / `create_session` / `close_session` / `abort_session` / `rebuild` / pending replay / `session_created` 后重发） |
| 3 | pending 队列存储 | 从纯文本升级为信封 `{text, businessSessionDomain, businessSessionType, businessSessionId}`，replay 用绑定值（不再查 session 表） |
| 4 | 入参取数 | 直接读现有字段：`ImMessageRequest.businessDomain/sessionType/sessionId`、`ExternalInvokeRequest.businessDomain/sessionType/sessionId`、miniapp `CreateSessionRequest.businessSessionDomain/businessSessionType`；其余从已加载的 `SkillSession` getter 取 |
| 5 | InvokeCommand 字段 | 复用现有 `domain` / `domainType`（语义就是 businessSessionDomain/Type），**仅新增 `String businessSessionId`** 一个字段 |
| 6 | personal 路径 businessExtParam 位置 | **从 `payload.businessExtParam`（顶层）搬到 `payload.extParameters.businessExtParam`**，跟 business wire 形态对齐（P2a）。已 grep 验证 ai-gateway / plugin / 个人 agent / 前端**零消费** businessExtParam |
| 7 | 缺失字段序列化 | JSON `null`，保留 key（不省略） |
| 8 | chatHistory 历史消息 | 不带 platformExtParam（与 D15 对齐：历史消息属上下文，三字段是当前请求维度） |

---

## 关键改动点

### 数据流向

```
入参 (ImMessageRequest / ExternalInvokeRequest / CreateSessionRequest) ──┐
                                                                          ├─→ InvokeCommand (含 domain/domainType/businessSessionId)
SkillSession.getter (DB) ─────────────────────────────────────────────────┘                │
                                                                                            ↓
                                          ┌─────── BusinessScopeStrategy / DefaultAssistantScopeStrategy (云端助手)
                                          │           └─→ extParameters.platformExtParam = {三字段}
                                          ├─────── GatewayRelayService.buildInvokeMessage (个人助手)
                                          │           └─→ payload.extParameters.{businessExtParam, platformExtParam}
                                          └─────── SessionRebuildService pending entry (绑定三字段)
                                                      └─→ replay 时用绑定值
```

### 涉及文件 / callsite

**PR1（云端助手 + scaffolding）**：
1. `skill-server/.../model/InvokeCommand.java` —— record 新增 `businessSessionId` 字段
2. `skill-server/.../scope/BusinessScopeStrategy.java:96` —— `platformExtParam` 从 `{}` 改为含三字段对象
3. `skill-server/.../scope/DefaultAssistantScopeStrategy.java:130` —— 同上
4. 7 个 callsite 构造 `InvokeCommand` 时传入 `businessSessionId`：
   - `SkillSessionController.java:170` / `:250` / `:291`（createSession / closeSession / abortSession）
   - `SkillMessageController.java:258` / `:470`（chat-or-questionReply / replyPermission）
   - `ImSessionManager.java:163` / `:312`
   - `InboundProcessingService.java:391` / `:456` / `:520`

**PR2（个人助手 + pending 升级）**：
5. `skill-server/.../service/GatewayRelayService.java:154`（`buildInvokeMessage`）—— 构造 `extParameters` 信封，把 payload.businessExtParam 搬入，再加 platformExtParam
6. `skill-server/.../service/SessionRebuildService.java:162`（`requestToolSession`）—— pending entry 加三字段
7. `skill-server/.../service/GatewayMessageRouter.java:927` / `:1337`（session_created 后重发 / 内部 sender 入口）—— 用 pending 绑定值
8. `skill-server/.../service/GatewayRelayService.java:367` / `:398`（`retryPendingMessages`）—— 用绑定值

**PR3（集成测 + spec）**：
9. `ExtParametersIntegrationTest` 端到端用例
10. spec `2026-04-27-extparams-passthrough-design.md` 更新 D2 / D5 / D16 / D17 / D19

---

## 请重点评审以下风险点

请按 Critical / High / Medium 分档给出问题清单。每个问题指明：
1. 哪个文件/方法/路径
2. 具体什么风险
3. 修复建议

### 1. wire 兼容性 / 协议破坏（Critical 候选）
- **R1**：决策 6（businessExtParam 从 payload 顶层搬到 extParameters 内）我们用 grep 验证了下游零消费，但有没有可能：
  - 测试代码 / mock 之外存在 reflection / 配置驱动的消费？
  - 灰度期间老版本下游还在路上没更新？
  - 历史日志 / 监控 / 审计系统读这个字段？
- **R2**：决策 7（缺失字段写 null 而非省略 key）下游 Jackson / Gson 反序列化是否能接受 `null` 字段？特别是 strongly-typed POJO 用 Lombok @NonNull 的话会爆。

### 2. pending 队列升级的兼容性（Critical 候选）
- **R3**：当前 pending entry 是纯文本，升级到 `{text + 三字段}` 信封。如果 pending 队列底层是 Redis（持久化），灰度期间存在**两种 schema 共存**：
  - 旧 entry（纯文本）replay 时读不到三字段 → 兜底 null 还是失败？
  - 新代码写新 schema，老代码（rollback 后）读不懂？
- **R4**：pending 是内存还是 Redis？需要 confirm — 如果是 Redis，schema 升级方案要给清楚（兼容期 / 迁移）

### 3. 并发与重发边界（High 候选）
- **R5**：session_created 后重发场景，pending entry 的三字段值是**发送时**的快照还是**重发时**查 DB？决策 3 说"用绑定值"，但如果发送时 session 还没建好（businessSessionId 是 null），重发时 session 已 ready，是否应该用最新值？
- **R6**：miniapp rebuild 路径（toolSessionId 未就绪→入 pending→retry），rebuild 触发时 caller 是不是一定能拿到三字段？

### 4. 双链路一致性（High 候选）
- **R7**：business / default_assistant / personal 三处构造 platformExtParam 是重复代码 —— 是否抽公共 helper？参考 `code-reuse-thinking-guide.md`。
- **R8**：personal 路径 `buildInvokeMessage` 当前是用 ObjectMapper 直接拼，business 路径走 strategy + CloudRequestContext。两套构造方式如何保证序列化顺序、null 行为完全一致？

### 5. 字段命名混乱（Medium 候选）
- **R9**：`InvokeCommand` 复用 `domain` / `domainType` 表示 businessSessionDomain / businessSessionType，但代码里 `domain` 一词被 dispatcher 反查规则用过（场景不同含义同）。新增的 `businessSessionId` 字段命名跟 `domain` / `domainType` 不对齐 —— 是否应该统一改名 `businessSessionDomain` / `businessSessionType` / `businessSessionId`（X→Y 的争论）？后续维护成本如何权衡？
- **R10**：spec D2/D5/D16/D17/D19 升级是否会跟其它正在进行的任务（如 `differentiated-error-hints`、`plugin-first-chat-missing-context`）冲突？

### 6. 测试覆盖（Medium 候选）
- **R11**：列出最必要的测试场景，特别是：
  - business / default_assistant / personal 三 scope 各自的填充测
  - 缺失字段 → null 序列化的形态测
  - pending replay 用绑定值的测
  - chatHistory 不带 platformExtParam 的测

### 7. 回滚方案（Medium 候选）
- **R12**：如果 PR2（pending 升级）合并后发现下游不兼容（虽然 grep 显示零消费但万一），如何回滚？pending 队列里已存的新 schema entry 会不会卡死？

---

## 输出格式

请按下面格式输出到 `codex-review.md`：

```markdown
# Codex Review — platformExtParam 补充 businessSession 字段

**评审日期**：2026-05-19
**评审模型**：codex (gpt-5.5 + xhigh)
**总体判断**：[PASS / NEEDS REVISION / BLOCK]

## Critical 问题（必须修，否则不放 PR1）

### C1. <标题>
- **位置**：<文件/方法>
- **风险**：<具体描述>
- **建议**：<修复建议>

### C2. <...>

## High 问题（强烈建议修）

### H1. <...>

## Medium 问题（后续优化）

### M1. <...>

## 总体建议

<段落>
```
