# Design Review — platformExtParam 补充 businessSession 字段

**评审日期**：2026-05-19
**评审人**：Claude Opus 4.7（主会话内自评，非真正 codex；建议事后再过一次真 codex (gpt-5.5 + xhigh)）
**总体判断**：**NEEDS REVISION** —— 有 2 个 Critical 必修，4 个 High 强烈建议修，3 个 Medium 可后续。

---

## 重大发现（影响 PRD 多处描述）

在评审过程中读了 `SessionRebuildService.java` / `PendingChatRequest.java` / `GatewayMessageRouter.retryPendingMessages`，发现 **PRD R5（"pending 队列从纯文本升级为信封"）的前提是错的**：

> **pending 队列已经是 JSON 信封了**，不是纯文本。

具体证据：
- `SessionRebuildService` line 33–46 注释明确说明 "PR2 升级：pending list 的 entry value 升级为 JSON 序列化的 `PendingChatRequest` 结构体"
- `PendingChatRequest` 是 record，6 字段 JSON：`text` / `assistantAccount` / `sendUserAccount` / **`imGroupId`** / `messageId` / `businessExtParam`
- `imGroupId` 字段注释（line 43）："**群聊业务会话 ID（== `SkillSession.businessSessionId`）**" —— 也就是 pending 里**已经存了一份 businessSessionId**，只是字段名叫 `imGroupId` 且仅在 IM 群聊场景填值

→ **本任务 pending 升级**变成"`PendingChatRequest` record 加 2 个字段"（`businessSessionDomain` / `businessSessionType`）而非"信封升级"。`imGroupId` 是否改名是设计选择（见 H3）。

---

## Critical 问题（必须修）

### C1. PRD pending 升级方案与现状脱节，需要重写

- **位置**：`PRD.md` Requirements R5 / Decision (ADR-lite) / Implementation Plan PR2
- **风险**：PRD 说"pending 队列从纯文本升级为信封"，会让实现者写出多余 / 错误的代码（如把已有结构当作不存在去重做）；如果未来真的有人 rollback PR3 之前的版本逻辑，会再陷入混乱
- **建议**：把 R5 / PR2 改写为：
  > R5：`PendingChatRequest` record 新增 `businessSessionDomain` / `businessSessionType` 两个字段（`businessSessionId` 已存在，复用现有 `imGroupId` 但需在 fromSessionFallback 扩展为非 IM 场景也填值，或将 `imGroupId` 改名 —— 见 H3）。`fromSessionFallback` 同步从 `session.getBusinessSessionDomain()` / `getBusinessSessionType()` / `getBusinessSessionId()` 取值兜底；缺失时各字段 null。Jackson 反序列化对老 entry（缺新字段）自动兜底为 null —— record canonical constructor 不做强校验即可

### C2. retry 路径不构造 extParameters，PR2 必须显式注入

- **位置**：`GatewayMessageRouter.java:906–928`（`retryPendingMessages` 重建 chatPayload）
- **风险**：当前 retry 把 pending entry 重组成 chatPayload 时**只写 text / toolSessionId / assistantAccount / sendUserAccount / imGroupId / messageId / businessExtParam**（line 906–919），完全不构造 `extParameters` 信封。如果只在 business strategy 里改，retry 走 personal 路径时三字段**仍然丢失**
- **建议**：
  1. 在 `retryPendingMessages` 构造 chatPayload 时，按 personal 路径的 wire 形态注入 `extParameters.platformExtParam`（含三字段）+ `extParameters.businessExtParam`（替代当前顶层 businessExtParam）—— 与决策 6 (P2a) 对齐
  2. 同时让 `new InvokeCommand(...)` 用**8 参构造器**传入 `pendingRequest.businessSessionDomain()` / `businessSessionType()`，让 dispatcher 能反查 default-assistant 规则（当前 line 927 用的是 6 参构造器，domain/domainType 默认 null，会跳过默认助手路由 —— 这是另一个隐藏 bug）

---

## High 问题（强烈建议修）

### H1. InboundProcessingService 三个 callsite 未传 domain/domainType

- **位置**：`InboundProcessingService.java:391` / `:456` / `:520`（dispatchChatToGateway / processQuestionReply / processPermissionReply）
- **风险**：这三处构造 `InvokeCommand` 用的是 5/6 参构造器，**没有传 domain/domainType**。但 IM/external 入站的入参里 `businessDomain` / `sessionType` 都是有的，仅仅没塞到 InvokeCommand。
  - 后果：今天通过 IM/external 入站的请求，dispatcher 在 `getStrategy(null, null, info)` 时 lookup 返 empty → 退化到老 API `getStrategy(info)` → **默认助手规则反查永远 miss**
  - 这跟本任务 platformExtParam 是同一份数据，今天没传 = 默认助手在 IM/external 入站场景下根本走不到
- **建议**：PR1 顺手修。把这三处改成 8 参构造器，传入参里现有的 `businessDomain` / `sessionType` + 新增 `businessSessionId`（也是入参的 `sessionId`）。这条修复**与本任务高度耦合，应该一起做**

### H2. ImSessionManager 两个 callsite 同上

- **位置**：`ImSessionManager.java:163` / `:312`
- **风险**：同 H1，IM 创建会话 / 后续发送时 InvokeCommand 也没传 domain/domainType
- **建议**：同样改为 8 参构造器，从 `SkillSession` getter 取（这里已经有 session 对象）

### H3. `imGroupId` 命名 vs 语义不一致，扩展时要做选择

- **位置**：`PendingChatRequest.imGroupId` / `dispatchChatToGateway:373` / `processQuestionReply:453` / `processPermissionReply:517`
- **风险**：现有代码到处用 `imGroupId`，但其语义就是 `SkillSession.businessSessionId`（PendingChatRequest.java line 43 注释明确）。本次扩展 personal 路径 wire 后，要在 platformExtParam 写"businessSessionId"键，**与旧 payload.imGroupId 字段并存**会出现：
  - `payload.imGroupId = "xxx"`（顶层，老形态）
  - `payload.extParameters.platformExtParam.businessSessionId = "xxx"`（新加）
  - 两个值写同一份数据，下游或后续维护者会迷惑
- **建议**：三选一（P 我推荐 **a**）：
  - **(a) 保持 `imGroupId` 字段名不变**（兼容性最佳，零下游协调），platformExtParam 内单独写 `businessSessionId`；接受短期"双写同一数据"的命名冗余，在 spec / 注释里把这事写清楚
  - (b) 一次性把 `imGroupId` 重命名为 `businessSessionId`：影响面大（grep 一下大约 30+ 处 callsite），需要单独一个 PR 做 refactor，本任务不建议合并做
  - (c) 仅在 personal/business wire 时把 imGroupId 字段从 payload 顶层移除，只保留 extParameters.platformExtParam.businessSessionId —— 风险：业务方 grep `imGroupId` 期望它在顶层会失望，相当于多一次破坏行为；不推荐

### H4. SessionRebuildService.rebuildToolSession 调 sendInvoke 用 5 参构造器

- **位置**：`SessionRebuildService.java:162–167`
- **风险**：rebuild 发 `create_session` 时也走 5 参构造器，domain/domainType 丢失。如果是默认助手在 IM 入站后触发 rebuild，rebuild create_session 不会走默认助手路径
- **建议**：PR2 顺手改 8 参，传 `session.getBusinessSessionDomain()` / `getBusinessSessionType()`（rebuild 时 session 对象已经在手）

---

## Medium 问题（后续优化）

### M1. business / default_assistant / personal 三处构造 platformExtParam 重复

- **位置**：`BusinessScopeStrategy.buildInvoke:96` / `DefaultAssistantScopeStrategy.buildInvoke:130` / `GatewayRelayService.buildInvokeMessage`（PR2 新增）
- **建议**：抽一个 `PlatformExtParamBuilder` 工具类（或 `ObjectMapper` 扩展方法）：
  ```java
  public static ObjectNode buildPlatformExtParam(ObjectMapper m, String domain, String type, String id) {
      ObjectNode n = m.createObjectNode();
      n.set("businessSessionDomain", domain != null ? new TextNode(domain) : NullNode.instance);
      n.set("businessSessionType", type != null ? new TextNode(type) : NullNode.instance);
      n.set("businessSessionId", id != null ? new TextNode(id) : NullNode.instance);
      return n;
  }
  ```
  避免三处独立写 + 各自处理 null 兜底导致行为漂移
- **优先级**：PR1 时直接抽出来更省事，不是事后

### M2. 缺失字段 → JSON `null` 的下游 Jackson 兼容性

- **位置**：决策 7
- **风险**：JSON `null` 字段下游用 Jackson 反序列化到 `String` POJO 时一般 OK（null），但如果下游用 Lombok `@NonNull` / `@NotNull` / Bean Validation，会爆。grep 已确认 ai-gateway / plugin / 前端零消费 businessExtParam，**但 platformExtParam 是新字段，下游可能会"准备消费"**
- **建议**：跟下游 agent 团队同步一次"我们会发 null 字段"的约束；在 spec `2026-04-07-cloud-agent-protocol.md` 更新里把这个明确写出来

### M3. ChatHistory 不带 platformExtParam 但 IM 历史消息透传到 GW 后形态

- **位置**：`ImMessageRequest.chatHistory` 透传链路（dispatchChatToGateway 等）
- **风险**：决策 8 说历史消息不带 platformExtParam。但需要确认 chatHistory 本身在 wire 上**根本就不嵌套 extParameters 信封** —— 它只是 `{senderAccount, senderName, content, timestamp}` 4 字段，跟 platformExtParam 没冲突。这一条本来就是冗余约束
- **建议**：在 spec 里写明 chatHistory 形态不变，避免误解

---

## 总体建议

### 必要修订（落到 PRD 后再开 PR1）

1. ✅ **C1**：重写 PRD R5 / PR2 描述，澄清"pending 已经是 JSON 信封，本次只是 record 加字段"
2. ✅ **C2**：PR2 实现里明确 `retryPendingMessages` 要注入 extParameters；同时把 `new InvokeCommand(...)` 改 8 参传 domain/domainType
3. ✅ **H1 + H2 + H4**：PR1 范围扩展 —— 把 InboundProcessingService 三处 + ImSessionManager 两处 + SessionRebuildService 一处的 InvokeCommand 全部改 8 参构造器，把现有"未传 domain/domainType"的隐藏 bug 一起修
4. ✅ **H3**：在 PRD 写明保留 `imGroupId` 字段名不动（选项 a），platformExtParam 内单独写 `businessSessionId`；接受短期命名冗余
5. ✅ **M1**：PR1 把 `PlatformExtParamBuilder` helper 抽出来，三处共用

### PR 拆分建议（修订后）

- **PR1**：
  - `InvokeCommand` 加 `businessSessionId`（复用 `domain` / `domainType`）
  - `PlatformExtParamBuilder` 公共 helper
  - `BusinessScopeStrategy` / `DefaultAssistantScopeStrategy` 改 `platformExtParam` 构造
  - **6 个 InvokeCommand callsite 升 8 参**：InboundProcessingService×3、ImSessionManager×2、SessionRebuildService.rebuildToolSession×1（修 H1/H2/H4 隐藏 bug）
  - 单测：strategy 层 + helper
- **PR2**：
  - `PendingChatRequest` record 加 `businessSessionDomain` / `businessSessionType`（复用 `imGroupId` 作为 businessSessionId 来源），`fromSessionFallback` 扩展取值
  - `GatewayRelayService.buildInvokeMessage` 注入 `extParameters` + 搬 businessExtParam (P2a)
  - `GatewayMessageRouter.retryPendingMessages` 注入 extParameters + 8 参 InvokeCommand
  - 单测：personal + retry
- **PR3**：
  - 集成测 + spec 更新（D2/D5/D16/D17/D19）
  - 顺手在 spec 里 deprecate `payload.imGroupId` 字段名（推荐改用 `businessSessionId`，但代码不动）

### 风险评估

| 维度 | 评级 |
|---|---|
| wire 兼容性 | 🟡 Medium —— P2a 已 grep 验证下游零消费 businessExtParam，但 platformExtParam 是全新字段，下游 strongly-typed POJO 反序列化要确认 |
| 灰度回滚 | 🟢 Low —— pending entry 加新字段 Jackson 自动兜底；businessExtParam 搬位置如发现问题可临时双写恢复 |
| 隐藏 bug 顺手修 | 🔴 High —— H1/H2/H4 揭示了 domain/domainType 没传的隐藏 bug，本任务应该一起修 |
| 命名一致性 | 🟡 Medium —— imGroupId vs businessSessionId 命名冗余可接受，但要在 spec 写清楚 |

---

## 给真 codex 评审的补充问题

如果之后过真 codex (gpt-5.5 + xhigh) 评审，建议额外问：

1. 决策 6 (P2a：businessExtParam 搬位置) 在云端 agent 的 strongly-typed protobuf / IDL 反序列化下，是否会因"未知字段"被严格模式拒绝？（grep 仅验证了 Java/TS 代码消费，没验证云端 IDL）
2. `PlatformExtParamBuilder` 抽公共 helper 时，是否应该用 Jackson 的 `JsonInclude.Include.ALWAYS` 全局配置而不是手动塞 `NullNode`？哪种更稳？
3. spec D19 (统一 pending/replay/重发契约) 升级后，是否需要在 PRD 里写**灰度切流策略**？比如开关控制新老 wire 形态？
