# Personal Scope 支持云端协议本地 Agent 设计

> **日期**: 2026-04-21
> **作者**: Llllviaaaa
> **状态**: Draft (v2, 收口版)
> **影响范围**: SS (`skill-server`)

---

## 摘要

个人助理 (`scope=personal`) 内部新增一种"本地部署、但走云端事件格式"的 agent 类型。该 agent 通过现有 AI-Gateway WebSocket 通道接入（和 opencode agent 共用同一通道），**出站事件采用 `CloudEventTranslator` 当前支持的事件子集**（详见 §5.1），并在事件 JSON 顶层显式携带 `protocol=cloud` 标签。SS 侧只在 `PersonalScopeStrategy.translateEvent` 一处按标签分派到对应 Translator，其他组件零改动。

**注意**：本设计**不声称**"全量兼容 `2026-04-07-cloud-agent-protocol.md`"。`CloudEventTranslator` 当前实现与协议文档存在若干兼容缺口（详见 §5.2），在这些缺口补齐前，本地 cloud agent 必须规避相关事件变体，或等兼容性补齐后再升级。

---

## 1. 背景

### 1.1 现状：两条翻译链路

| Scope | Invoke 链路 | 出站事件协议 | Translator |
|---|---|---|---|
| `business`（云端业务 agent） | SS → GW `invoke{cloudRequest}` → GW `CloudAgentService` → SSE/WS 打云端 endpoint | 云端下发 `CloudEvent{type, properties}` | `CloudEventTranslator` |
| `personal`（本地 opencode agent） | SS → GW `invoke` → GW WebSocket → 本地 opencode agent | OpenCode 原生事件 (`message.part.updated`, `session.idle`, `question.asked` 等) | `OpenCodeEventTranslator` + `TranslatorSessionCache` |

两条 Translator 的维护成本、bug 面、演进速度各自独立。个人助理近期的改进（enrich emittedAt、question 去重、user text 时序回溯等）都只落在 `OpenCodeEventTranslator` 一侧。

### 1.2 新需求

个人助理场景下，存在一类本地部署的 agent，希望以"类云端"协议与 SS 集成，而不是实现完整的 OpenCode 原生协议。该需求**不涉及**修改现有 opencode agent 或云端 agent。

---

## 2. 目标与非目标

### 2.1 目标

1. 在 `scope=personal` 下支持一类"本地 + 云端事件格式子集"的 agent，使其可通过现有 AI-Gateway WebSocket 通道发送 `CloudEvent{type, properties}` 形状的出站事件。
2. 让 SS 能按事件自带的 `protocol` 标签正确分派到 `CloudEventTranslator`。
3. 对老 opencode agent 保持零感知（不带 `protocol` 字段或显式 `protocol=opencode` 时行为完全不变）。
4. GW 保持纯通道转发，不感知协议差异。
5. 对"显式未知 protocol 值"能发现并告警（`log.warn`），避免 typo / 配置错误静默退化为 opencode 分支。

### 2.2 非目标

1. **不合并**两个 Translator。
2. **不动** opencode agent / `plugins/agent-plugin`。
3. **不动** 云端 business agent 链路。
4. **不改** invoke 链路：`GatewayRelayService.buildInvokeMessage` / `PersonalInvokeRouteStrategy` 完全不动。
5. **不引入**新 scope：`AssistantInfo.scope` 枚举仍只有 `personal` / `business`。
6. **不改** GW 代码（含 `CloudAgentService`，它只服务 business scope 出站连接）。
7. **不补齐** `CloudEventTranslator` 与 `2026-04-07-cloud-agent-protocol.md` 的兼容缺口（详见 §5.2）——作为后续独立工作。
8. 本次**不做** metrics / feature flag / 运维开关。

---

## 3. 架构与数据流

### 3.1 三条事件链路对比

```
[business]
    云端 agent ──SSE/WS──▶ GW CloudAgentService ──▶ GW Relay ──▶ SS BusinessScopeStrategy ──▶ CloudEventTranslator
                                                                         │
                                                         event.protocol 不读，scope 决定用 Cloud 翻译器

[personal / opencode 老]
    opencode agent ──WS──▶ GW(纯透传) ──▶ SS PersonalScopeStrategy ──event.protocol 缺失/opencode──▶ OpenCodeEventTranslator

[personal / 新本地-云协议]
    新本地 agent  ──WS──▶ GW(纯透传) ──▶ SS PersonalScopeStrategy ──event.protocol=cloud──▶ CloudEventTranslator
```

### 3.2 关键不变量

1. **GW 对 personal scope 保持零协议感知**：`GatewayMessage.event` 作为 `JsonNode` 原样透传，不读、不注入、不改写。
2. **invoke 方向单一格式**：personal scope 所有子类型（opencode / cloud）接收相同的 invoke 消息结构（`GatewayRelayService.buildInvokeMessage` 输出），由 agent 端各自消费。
3. **分派点唯一**：只在 `PersonalScopeStrategy.translateEvent` 一处读 `event.protocol`；不要在 GW、`AssistantScopeStrategy` 接口、`AssistantInfo` 等其他地方散布协议判断。
4. **session 生命周期不变**：`requiresSessionCreatedCallback=true`、`requiresOnlineCheck=true`、`toolSessionId` 由 agent 回传 `session_created` 绑定——云端协议的本地 agent 同样遵守。
5. **`sessionId` 参数语义统一**：`translateEvent(event, sessionId)` 的 `sessionId` 在所有 scope 下都是 SS 内部 `SkillSession` ID（数字型，`ProtocolUtils.parseSessionId` 转 `Long`），不是 `welinkSessionId`。`CloudEventTranslator` 在 personal scope 下复用时无需 sessionId 的适配。

---

## 4. 分派规则（`protocol` 字段）

### 4.1 字段定义

| 字段 | 位置 | 类型 | 必填 | 语义 |
|---|---|---|---|---|
| `protocol` | event JSON 顶层（与 `type` 同级） | string | 可选 | 事件协议标签。建议值：`cloud`、`opencode`。大小写不敏感。 |

### 4.2 分派矩阵

| `protocol` 字段状态 | 分派目标 | 日志 |
|---|---|---|
| 缺失（无此字段） | `OpenCodeEventTranslator` | 无（不 warn，兼容老 agent） |
| `opencode`（不区分大小写） | `OpenCodeEventTranslator` | 无 |
| `cloud`（不区分大小写） | `CloudEventTranslator` | `DEBUG` 分派路径日志 |
| 空串 `""` | `OpenCodeEventTranslator`（fallback） | `WARN`：`unknown protocol value` |
| 其它非空未知值（如 `mcp`） | `OpenCodeEventTranslator`（fallback） | `WARN`：`unknown protocol value` |

**设计取舍**：缺失和未知非空值差异化处理——前者是老 agent 零感知路径，后者是 typo/配置错误/未来协议，必须可观测。

---

## 5. 协议规范（本地 cloud agent 发送约束）

本节是**规范源头**。本地 cloud agent 的行为遵循本节约束，而不是"去读 `CloudEventTranslator` 源码猜"或"套用 `2026-04-07-cloud-agent-protocol.md` 全量"。

### 5.1 当前支持子集（允许发送的事件）

本地 cloud agent 选择云端协议时，**只允许发送以下 event type**，并遵循列出的字段形状：

| event.type | 级别 | 必填字段（properties 内） | 允许字段 | 备注 |
|---|---|---|---|---|
| `text.delta` | Part | `messageId`, `partId`, `content` | `role` | |
| `text.done` | Part | `messageId`, `partId`, `content` | `role` | |
| `thinking.delta` | Part | `messageId`, `partId`, `content` | `role` | |
| `thinking.done` | Part | `messageId`, `partId`, `content` | `role` | |
| `tool.update` | Part | `messageId`, `partId`, `toolName`, `toolCallId`, `status` | `input`（String；建议 JSON 字符串）, `output`（String）, `title`, `error` | `input` **暂不支持嵌套对象**，详见 §5.2 |
| `step.start` | Message | `messageId` | `role` | |
| `step.done` | Message | `messageId` | `tokens`（Object，键值对，数值），`cost`（Number）,`reason`（String） | 字段名是 `tokens` **不是** `usage`，详见 §5.2 |
| `session.status` | Session | `sessionStatus`（String，值 `busy`/`idle`/`retry`） | — | 字段名是 `sessionStatus` **不是** `status`，详见 §5.2 |
| `session.title` | Session | `title`（String） | — | |
| `session.error` | Session | `error`（String） | — | |
| `file` | Part | `messageId`, `partId` | `fileName`, `fileUrl`, `fileMime` | |

**所有 Part/Message 级事件**的 `messageId`/`partId` 语义见 §5.3。

**未列入此表的 event type**（例如 `permission.ask`、`permission.reply`、`question`、`planning.*`、`searching`、`search_result`、`reference`、`ask_more`）在 `CloudEventTranslator` 中虽有 handler，但**本次不纳入支持子集**——这些事件在 personal 场景下尚未验证，等补齐兼容性后再显式加入本表。

### 5.2 已知兼容缺口（本次不修，本地 cloud agent 必须规避）

`CloudEventTranslator` 当前实现与 `2026-04-07-cloud-agent-protocol.md` 存在以下不对齐点。本次 spec **不修复**这些缺口，本地 cloud agent 发送事件时必须规避或按"当前实现方言"发送。

| # | 缺口 | 协议文档定义 | 当前实现 | 对本地 cloud agent 的约束 |
|---|---|---|---|---|
| G1 | `session.status` 字段名 | `properties.status`（见 2026-04-07 文档 §5） | 主 handler `CloudEventTranslator.handleSessionStatus` 只读 `properties.sessionStatus`；`translate(...)` 末尾的 idle 清理逻辑额外兼容 `status -> sessionStatus` | 发送时用 `sessionStatus`，**不要用** `status` |
| G2 | `tool.update.input` 类型 | `Object`（2026-04-07 文档 L530） | `CloudEventTranslator.handleToolUpdate` 用 `asText()` 转字符串；`StreamMessage.ToolInfo.input` 虽声明为 `Object` 但输入已丢结构 | 本地 agent 的 `input` **暂传 JSON 字符串**，不要传嵌套对象；或等 G2 修复后再升级 |
| G3 | `step.done` usage 字段名 | `usage`（2026-04-07 文档 L555 样例） | `CloudEventTranslator.handleStepDone` 读 `tokens` | 发送时用 `tokens`，**不要用** `usage` |
| G4 | `partSeq` 语义 | "Part 在消息中的排序序号"（`stream-protocol.md:33`）；OpenCode 路径按 messageId 内 part 顺序分配（`TranslatorSessionCache.rememberPartSeq`） | Cloud 路径在 `CloudEventTranslator.translate(...)` 中按 `(sessionId, partId)` 维度对**同一 partId 的事件次数**自增 | 这是**语义差异**，不是 agent 行为约束——本地 cloud agent 无法规避，只能接受 partSeq 在 personal+cloud 路径下语义和协议文档不一致。与 OpenCode 路径、业务 scope 混用时 partSeq 不可比。后续 G4 修复时需同时调整 business scope 行为，有 regression 风险。 |

**后续工作**：G1/G2/G3 在 `CloudEventTranslator` 侧修复（独立 spec），同时需兼容现有 business scope 输入；G4 需评估影响面后选择"改翻译器"或"改协议文档"。

### 5.3 字段责任矩阵

| 字段 | agent 侧责任 | SS 侧位置 |
|---|---|---|
| `messageId` | **MUST 自己生成并回传**。缺失时 `CloudEventTranslator` 会 `log.warn`；随后 `StreamMessageEmitter.enrich -> ActiveMessageTracker -> SkillMessageService` 会在发送前补一个本地生成的 `messageId`。这是兜底，不是协议契约，agent 不能依赖它 | `CloudEventTranslator.translate(...)`（告警） + `StreamMessageEmitter.enrich` / `ActiveMessageTracker` / `SkillMessageService`（兜底生成） |
| `partId` | **MUST 自己生成并回传**。缺失 → `log.warn`；若继续流转到持久化阶段，`MessagePersistenceService` 可能再生成 fallback `partId` | `CloudEventTranslator.translate(...)`（告警） + `MessagePersistenceService`（持久化 fallback） |
| `messageSeq` | **SHOULD NOT 传**。SS `ActiveMessageTracker` 在发送前按会话内消息序号注入 | `ActiveMessageTracker.applyMessageContext(...)` |
| `partSeq` | **SHOULD NOT 传**。SS `CloudEventTranslator` 按当前实现语义生成（见 G4） | `CloudEventTranslator.translate(...)` partSeq 生成分支 |

**agent 侧最小契约**：本地 cloud agent 只需为每条 Part 级事件保证同一消息内 `messageId` 稳定、同一分片内 `partId` 稳定即可；所有 `*Seq` 字段由 SS 自管。

### 5.4 事件示例

**老 opencode agent 事件（不带 `protocol`，不变）**：

```json
{
  "type": "message.part.updated",
  "properties": {
    "part": { "id": "p1", "type": "text" },
    "sessionID": "s1"
  }
}
```

**新本地 cloud agent 事件**：

```json
{
  "protocol": "cloud",
  "type": "text.delta",
  "properties": {
    "content": "hello",
    "messageId": "m1",
    "partId": "p1"
  }
}
```

---

## 6. SS 侧改动

### 6.1 主要修改文件

`skill-server/src/main/java/com/opencode/cui/skill/service/scope/PersonalScopeStrategy.java`

```java
@Slf4j
@Component
public class PersonalScopeStrategy implements AssistantScopeStrategy {

    private final OpenCodeEventTranslator openCodeEventTranslator;
    private final CloudEventTranslator cloudEventTranslator;   // 新增

    public PersonalScopeStrategy(OpenCodeEventTranslator openCodeEventTranslator,
                                 CloudEventTranslator cloudEventTranslator) {   // 构造器新增参数
        this.openCodeEventTranslator = openCodeEventTranslator;
        this.cloudEventTranslator = cloudEventTranslator;
    }

    @Override
    public StreamMessage translateEvent(JsonNode event, String sessionId) {
        if (event == null) {
            return null;
        }
        JsonNode protocolNode = event.path("protocol");
        // 缺失字段（含 missing/null）→ opencode，不 warn
        if (protocolNode.isMissingNode() || protocolNode.isNull()) {
            return openCodeEventTranslator.translate(event);
        }
        String protocol = protocolNode.asText("");
        if ("opencode".equalsIgnoreCase(protocol)) {
            return openCodeEventTranslator.translate(event);
        }
        if ("cloud".equalsIgnoreCase(protocol)) {
            log.debug("[PersonalScope] dispatch: protocol=cloud, type={}, sessionId={}",
                    event.path("type").asText(""), sessionId);
            return cloudEventTranslator.translate(event, sessionId);
        }
        // 空串或其它非空未知值 → warn + fallback opencode
        log.warn("[PersonalScope] unknown protocol value=\"{}\", fallback to OpenCodeEventTranslator, type={}, sessionId={}",
                protocol, event.path("type").asText(""), sessionId);
        return openCodeEventTranslator.translate(event);
    }

    // 其他方法（getScope/buildInvoke/generateToolSessionId/
    // requiresSessionCreatedCallback/requiresOnlineCheck）完全不动
}
```

### 6.2 必需的测试基线对齐

`skill-server/src/test/java/com/opencode/cui/skill/service/EventTranslationScopeTest.java`（`setUp()` 中的构造器调用）

当前构造器调用：

```java
personalStrategy = new PersonalScopeStrategy(openCodeEventTranslator);
```

随着 `PersonalScopeStrategy` 构造器签名变化，**此行必需同步修改为**：

```java
personalStrategy = new PersonalScopeStrategy(openCodeEventTranslator, cloudEventTranslator);
```

否则本改动会导致 `EventTranslationScopeTest` 编译失败。同文件中若还有其他 new 调用也需同步。

### 6.3 不变的组件

| 组件 | 说明 |
|---|---|
| `CloudEventTranslator` | 零改动。sessionId 作 partSeqCounters key 的语义在 personal 下自然成立（虽然和协议文档 partSeq 定义不一致，见 G4） |
| `GatewayMessageRouter.handleToolEvent` | 零改动。sessionId 的解析与传递不变 |
| `AssistantScopeStrategy` 接口签名 `translateEvent(event, sessionId)` | 零改动 |
| `BusinessScopeStrategy` | 零改动 |
| `OpenCodeEventTranslator` + `TranslatorSessionCache` | 零改动 |

---

## 7. 错误处理

| 异常场景 | 行为 | 位置 |
|---|---|---|
| `protocol=cloud` 但 event 结构不符合 CloudEvent（无 `type`） | `CloudEventTranslator.translate` 返回 `null` | `CloudEventTranslator.translate(...)` 前置检查 |
| 未知 cloud event type（如 `type=foo.bar`） | `log.debug("Unknown cloud event type")`，返回 null，丢弃 | `CloudEventTranslator.translate(...)` handler 查找分支 |
| `protocol` 字段空串或未知非空值 | `log.warn` + fallback 到 `OpenCodeEventTranslator`；OpenCode 若认不出继续 `log.debug` 忽略 | `PersonalScopeStrategy.translateEvent` 末尾分支 |
| cloud event 缺 `messageId` | `CloudEventTranslator` 先 `log.warn`；随后 `StreamMessageEmitter.enrich -> ActiveMessageTracker -> SkillMessageService` 会在发送前生成本地 `messageId`，但该 ID 不再等同于上游协议 ID | `CloudEventTranslator.translate(...)` + `StreamMessageEmitter.enrich` / `ActiveMessageTracker` / `SkillMessageService` |
| cloud event 缺 `partId` | `CloudEventTranslator` 先 `log.warn`；若继续走到持久化阶段，`MessagePersistenceService` 可能生成 fallback `partId`，但前端在发送时仍可能看到空 `partId` | `CloudEventTranslator.translate(...)` + `MessagePersistenceService` |
| agent 同 session 中混发 opencode / cloud 两套事件 | 按每条独立分派；`partSeqCounters` 与 `TranslatorSessionCache` 两套缓存**可能交叉污染**（`partSeq` 不清、question/part 状态错位）——**规范禁止**，代码不主动防御 | 规范层约束 |
| Spring 启动期 `CloudEventTranslator` Bean 注入失败 | 启动期快速失败，不进运行期 | 启动期 |

---

## 8. 可观测性

- **DEBUG 日志**：分派到 Cloud 路径时打印 `protocol / type / sessionId`。
- **WARN 日志**：`protocol` 空串或未知非空值时打印原值、type、sessionId，便于定位 agent 配置错误。
- **MDC 不动**：`MdcHelper.ensureTraceId()` 在上游已注入。
- **Metrics**：本次不做。

---

## 9. 灰度与回滚

### 9.1 灰度

**天然灰度**：

- 老 opencode agent 不带 `protocol` 字段 → 走原有路径，零感知
- 新本地 cloud agent 自己决定何时开始发 `protocol=cloud`
- 无需配置开关或 feature flag

### 9.2 回滚

- **代码回滚**：`PersonalScopeStrategy.translateEvent` 和构造器回到单 translator 版本，同步回滚 `EventTranslationScopeTest` 构造器调用。1 次 git revert 搞定。
- **数据兼容**：`StreamMessage` / DB schema 不变，无迁移。
- **Agent 端回滚**：agent 去掉 `protocol` 字段即回退到 opencode 路径（前提 agent 自身能发 opencode 格式）。

---

## 10. 测试策略

### 10.1 单元测试（新增或扩展 `PersonalScopeStrategyTest`）

| 用例 | 输入 | 期望 | 必需 |
|---|---|---|---|
| `translateEvent_nullEvent_returnsNull` | `null` | 返回 `null`，两个 translator 都不调用 | 是 |
| `translateEvent_noProtocolField_delegatesToOpenCode_noWarn` | `{type:"message.part.updated", properties:{...}}` | 调用 `openCodeEventTranslator.translate(event)`，不调用 cloud，**不产生 warn** | 是 |
| `translateEvent_protocolOpencode_delegatesToOpenCode_noWarn` | `{protocol:"opencode", ...}` | 走 opencode，**不产生 warn** | 是 |
| `translateEvent_protocolCloud_delegatesToCloud` | `{protocol:"cloud", type:"text.delta", properties:{...}}` | 调用 `cloudEventTranslator.translate(event, sessionId)`，sessionId 透传 | 是 |
| `translateEvent_protocolCloudUpperCase_caseInsensitive` | `{protocol:"CLOUD", ...}` | 走 cloud 分支 | 是 |
| `translateEvent_protocolEmptyString_warnsAndFallsBackToOpenCode` | `{protocol:"", ...}` | 走 opencode，**产生一条 warn 日志** | 是 |
| `translateEvent_protocolUnknownValue_warnsAndFallsBackToOpenCode` | `{protocol:"mcp", ...}` | 走 opencode，**产生一条 warn 日志** | 是 |

Mockito mock 两个 translator，验证调用次数 + 参数；log 断言用 LogCaptor 或等价机制。

### 10.2 集成测试（新增 `PersonalScopeCloudProtocolTest`）

- 构造个人助理 `tool_event` JSON（`event.protocol=cloud`, 从 §5.1 子集里取 type = `text.delta` / `tool.update` / `session.status`，其中 `session.status` 用 `properties.sessionStatus="idle"`）
- 喂给 `GatewayMessageRouter.handleToolEvent`
- 断言：
  - StreamMessage 正确生成（messageId/partId/role/content）
  - `partSeq` 按 `CloudEventTranslator` **当前实现**语义生成（非 §5.2 G4 描述的"目标语义"——测试锁定的是当前行为）
  - `session.status=idle` 后 `partSeqCounters` 被清理

### 10.3 回归测试

`mvn test -pl skill-server`，关注：

- `OpenCodeEventTranslatorTest`（opencode 路径不受影响）
- `CloudEventTranslatorTest` / `BusinessScopeStrategyTest`（business 路径不受影响）
- `EventTranslationScopeTest`（**必需更新构造器调用**，见 §6.2）
- `SsRelayAndTakeoverTest` / `ImOutboundFilterTest` / `GatewayMessageRouterImPushTest`

### 10.4 人工联调（可选）

mock 一个本地 cloud agent（Python 脚本连 AI-Gateway WS 发 §5.4 示例），端到端验证前端收到 StreamMessage。作为联调 checklist，不纳入必须项。

---

## 11. 后续演进（独立 spec）

| 演进项 | 内容 | 触发时机 |
|---|---|---|
| 补齐 §5.2 G1/G2/G3 | `CloudEventTranslator` 对齐 `2026-04-07-cloud-agent-protocol.md` 字段定义，双向兼容新旧字段 | 协议兼容性补齐阶段 |
| 修复 §5.2 G4 | `CloudEventTranslator.partSeq` 语义改为"message 内 part 顺序"，同步评估 business scope regression | 同上，建议和 G1-G3 一批 |
| 扩展 §5.1 支持子集 | 本次未列入的 event type（permission.ask/reply、question、planning.*、searching、search_result、reference、ask_more）在 personal+cloud 下逐一验证并显式加入 §5.1 | 有具体 agent 需求时 |
| 引入 `mcp` / `a2a` 等新协议 | `PersonalScopeStrategy.translateEvent` 分派逻辑加新分支 | 有明确需求时 |
| 运维开关 | 加 `skill.personal.protocol-dispatch.enabled` 运行时开关 | 出现需要临时禁用的运维场景时 |
| Metrics | `translator.dispatch{protocol=cloud|opencode}` micrometer counter | 需要观测协议占比时 |
