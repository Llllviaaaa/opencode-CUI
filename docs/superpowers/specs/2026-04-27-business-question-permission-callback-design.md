# Business 助理 q_r / p_r 远端能力 + 回调配置接口切换

> **Date**: 2026-04-27
> **Type**: Cross-layer (skill-server + ai-gateway)

## 目标

1. 业务助理新增 `question_reply` / `permission_reply` 远端能力（当前不支持）
2. ai-gateway 把"取云端路由配置"切到 api-server 新接口 `POST /gateway/callbacks/config`，按 `(ak, scope)` 双维度查询
3. 三种业务 scope：
   - `callback:weagent:chat` —— 现有 chat（流式 SSE/WebSocket）
   - `callback:weagent:question_reply` —— 新增（同步 WebHook）
   - `callback:weagent:permission_reply` —— 新增（同步 WebHook）

---

## 改动总览

| 模块 | 类 | 改动 |
|---|---|---|
| ai-gateway | `CallbackConfigService`（新） | 外观：v1 走 `LegacyRouteResolver`（旧 GET-with-body）/ v2 走 `GatewayCallbackResolver`（新 POST），按 feature flag 装配 |
| ai-gateway | `CloudAgentService` | 加 `action→scope` 硬编码映射；按 `channelType` 分叉到 `CloudProtocolClient`（sse/ws）或 `WebHookExecutor`（webhook） |
| ai-gateway | `WebHookExecutor`（新） | 同步 POST + 鉴权；2xx 不回流，失败回 `tool_error`；不重试；不进 `CloudConnectionLifecycle` |
| ai-gateway | `CloudConnectionLifecycle` | 加 `awaitingReply: AtomicBoolean` + `pauseIdleTimer/resumeIdleTimer`；`resetIdleTimeout` 在 `awaitingReply==true` 时 no-op（防 heartbeat 重启 idle timer） |
| ai-gateway | `SseProtocolStrategy` / `WebSocketProtocolStrategy` | 收到 `question` / `permission.ask` 触发 pause；其他事件触发 resume；`appId==null` 时跳过 `X-App-Id` header |
| ai-gateway | `CloudConnectionContext` | 新增 `channelType` / `scope`；`endpoint` 改名 `channelAddress`；`appId` 保留（v1 非空 / v2 为 null） |
| ai-gateway | `CloudAuthService` + `NoAuthStrategy`（新） | `authType="none"` → NoAuth（不写鉴权 header）；其他未知 authType 仍 fail-fast |
| skill-server | `BusinessScopeStrategy` | 按 action 取 invoke payload 字段写入 `CloudRequestContext`：q_r 取 `toolCallId` + `answer`（**单字符串**）→ 经 `parseAnswers` 转成 `List<List<String>>` 写入 `replyAnswers`；p_r 取 `permissionId` + `response` |
| skill-server | `CloudRequestContext` | 新增 4 个 reply 字段：`replyToolCallId` / `replyAnswers` (`List<List<String>>`) / `replyPermissionId` / `replyResponse` |
| skill-server | `DefaultCloudRequestStrategy` | q_r/p_r 时把 reply 字段组装成 `replyContext` 嵌套对象写入 cloudRequest；chat 不变 |
| skill-server | `CloudEventTranslator.handleQuestion` | 兼容 `questions: [...]` 数组与单结构两种形态；填充 `QuestionInfo.questions`；**透传云端新增的 `extParam` 字段到 `QuestionInfo.extParam`** |
| skill-server | `StreamMessage.QuestionInfo` | 加 `questions: List<QuestionItem>` 字段；**加 `extParam: JsonNode` 字段（云端透传给 miniapp / external WS）**；保留顶层 `header/question/options` 兼容现有前端 |

---

## 协议契约

### cloudRequest 给云端（gateway → cloud）

**chat**（不变，沿用现状）：
```json
{
  "topicId": "<toolSessionId>",
  "content": "<用户输入文本>",
  "contentType": "text",
  "assistantAccount": "...",
  "sendUserAccount": "...",
  "imGroupId": "...",
  "messageId": "...",
  "clientLang": "zh",
  "extParameters": { "businessExtParam": {}, "platformExtParam": {} }
}
```

**question_reply**（新；走 WebHook）：
```json
{
  "topicId": "<toolSessionId>",
  "assistantAccount": "...",
  "sendUserAccount": "...",
  "imGroupId": "...",
  "messageId": "...",
  "clientLang": "zh",
  "replyContext": {
    "type": "question_reply",
    "toolCallId": "<原 question 事件的 toolCallId>",
    "answers": [["选项A"], ["选项B","选项C"]]
  },
  "extParameters": { "businessExtParam": {}, "platformExtParam": {} }
}
```

**permission_reply**（新；走 WebHook）：
```json
{
  "topicId": "<toolSessionId>",
  "assistantAccount": "...",
  "sendUserAccount": "...",
  "imGroupId": "...",
  "messageId": "...",
  "clientLang": "zh",
  "replyContext": {
    "type": "permission_reply",
    "permissionId": "<原 permission.ask 的 permissionId>",
    "response": "once" | "always" | "reject"
  },
  "extParameters": { "businessExtParam": {}, "platformExtParam": {} }
}
```

**字段说明**：

| 字段 | 适用 | 含义 |
|---|---|---|
| `topicId` / `assistantAccount` / `sendUserAccount` / `imGroupId` / `messageId` / `clientLang` / `extParameters` | 三种都有 | 与 chat 同款，由 `BusinessScopeStrategy` 统一填 |
| `content` + `contentType` | 仅 chat | 用户输入文本；q_r/p_r 不带 |
| `replyContext.type` | 仅 q_r/p_r | `"question_reply"` 或 `"permission_reply"`（云端可据此分流） |
| `replyContext.toolCallId` | 仅 q_r | 关联回云端发起 `question` 事件的 toolCallId |
| `replyContext.answers` | 仅 q_r | `List<List<String>>`：外层=多问题维度，内层=单题多选/单选/自由文本 |
| `replyContext.permissionId` | 仅 p_r | 关联回云端发起 `permission.ask` 事件的 permissionId（沿用现有协议） |
| `replyContext.response` | 仅 p_r | `"once"` / `"always"` / `"reject"`（沿用现有协议；不引入 allow/deny 二态） |

**`answers` 形态举例**：
- 单题单选：`[["选项A"]]`
- 单题多选：`[["选项A","选项B"]]`
- 多题：`[["A"],["B","C"]]`
- 纯文本：`[["用户输入"]]`

### 入站 → skill-server

两个入口形态不同，分别说明（**全部入参字段类型不改，只在 q_r 的 `content` 字段值约定上扩展为 stringified JSON 数组**）：

**Miniapp**：
- q_r：`POST /api/skill/sessions/{sessionId}/messages` body `SendMessageRequest{content, toolCallId, subagentSessionId, businessExtParam}`，`toolCallId` 非空时路由到 q_r。content 值约定为 stringified `[[...]]`
- p_r：`POST /api/skill/sessions/{sessionId}/permissions/{permId}` body `PermissionReplyRequest{response, subagentSessionId, businessExtParam}`，`permId` 来自 PathVariable，body **不含** permissionId

**External inbound** (`POST /api/external/invoke`)：
- q_r：`payload.{content, toolCallId, subagentSessionId}`。content 值约定同上
- p_r：`payload.{permissionId, response, subagentSessionId}`，**permissionId 在 payload 里**（与 miniapp 形态不同）

### invoke payload (skill-server → gateway)

**字段不改**（避免破坏 `extparams-passthrough` 任务）：q_r 仍是 `answer: String`（=入站 content 透传）+ `toolCallId`；p_r 仍是 `permissionId + response`。

### `BusinessScopeStrategy.parseAnswers(raw) -> List<List<String>>`

| 输入 | 输出 |
|---|---|
| `"[[\"A\"],[\"B\",\"C\"]]"` (stringified 数组的数组) | 原样 |
| `"[\"A\",\"B\"]"` (stringified 一维数组) | `[["A","B"]]` |
| `"普通文本"` 或解析失败 | `[["普通文本"]]` |
| null / blank | `[[""]]` |

### `question` 事件（云端 → skill-server）

云端可推送两种形态，skill-server 兼容：

```json
// 形态 1：questions 数组（OpenCode 风格，新）
{ "type": "question", "toolCallId": "...",
  "questions": [
    { "header": "...", "question": "...", "options": [...] },
    ...
  ],
  "extParam": { /* 任意 JSON，云端定义，平台原样透传 */ } }

// 形态 2：单结构（现状）
{ "type": "question", "toolCallId": "...",
  "header": "...", "question": "...", "options": [...],
  "extParam": { /* 同上，可选 */ } }
```

`CloudEventTranslator.handleQuestion`：
- 优先取 `questions[]` 遍历；缺失则把顶层字段包成单元素 `questions=[{header, question, options}]`
- 取 `extParam` 节点（任意 JSON）原样写入 `QuestionInfo.extParam`；缺省时为 null

**extParam 透传链路**：cloud → gateway（不解析，作为 GatewayMessage.event 的一部分原样转发） → SS `CloudEventTranslator` 写入 `QuestionInfo.extParam` → SS 通过 `OutboundDeliveryDispatcher` / WebSocket emitter 将 `StreamMessage`（含 `questionInfo.extParam`）原样下发给 miniapp / external WS。整条链路不读、不写、不修改 extParam 内部结构。

---

## chat 长连接保活

云端推 `question` / `permission.ask` 后，chat SSE/WS 连接需保活直到云端推完后续事件。

**策略**：
- `CloudConnectionLifecycle.awaitingReply=true` 时，`resetIdleTimeout()` 内部直接 return（no-op）
- Strategy 层在收到 `question` / `permission.ask` 调 `pauseIdleTimer()`，收到其他事件调 `resumeIdleTimer()`
- `permission.reply` 事件**不**触发 pause（它是 assistant role 的应答事件）
- `maxDuration` 不暂停，作为兜底（默认调到 1800s = 30min）
- WebHook 失败时不主动 close 原 chat 连接，由 maxDuration 兜底回收

---

## feature flag 切换

```yaml
gateway.cloud-route.api-version: v1   # 默认；v1 仅 chat 可用，q_r/p_r 拒绝
gateway.cloud-route.api-version: v2   # 切换后 q_r/p_r 才生效
```

`LegacyRouteResolver` 仅在 `scope=callback:weagent:chat` 时调旧接口；其他 scope 在 v1 模式直接拒绝（gateway 回 `tool_error`）。

缓存 key 统一：`gw:cloud:route:{ak}:{scope}`，TTL 沿用 300s。

---

## 行为 / 错误矩阵

**错误路径**：

| 场景 | 行为 | 回流 |
|---|---|---|
| api-server 网络/超时 / data=null / 非 200 | 返 null | `tool_error` |
| chat 收到 channelType=1 (webhook) | 拒绝 | `tool_error("Invalid channel type for chat")` |
| q_r/p_r 收到 channelType=2/3 (sse/ws) | 拒绝 | `tool_error("Invalid channel type for reply")` |
| WebHook 网络/超时/非 2xx | 不重试 | `tool_error` |
| feature flag=v1 收到 q_r/p_r | 拒绝 | `tool_error("not enabled in v1")` |
| `authType` 未知（既非 0/1/2 也非 none/soa/apig） | fail-fast IllegalArgumentException | `tool_error` |

**正常路径行为差异**（v1/v2 / authType / WebHook 成功）：

| 场景 | 行为 |
|---|---|
| **WebHook 2xx** | 不回流任何 GatewayMessage（fire-and-forget） |
| `authType="none"` | NoAuth 策略，不写任何鉴权 header |
| v2 模式 `appId=null` | protocol strategy 跳过 X-App-Id header |
| v1 模式 `appId` 来自 hisAppId | 写入 X-App-Id header（等价现状） |

---

## 配置

```yaml
gateway:
  cloud-route:
    api-version: v1                           # v1 / v2
    api-url: ...                              # v1（保留）
    bearer-token: ...
    cache-ttl-seconds: 300
    v2-api-url: https://api-server/gateway/callbacks/config
    v2-bearer-token: ...
  cloud:
    timeout:
      first-event-timeout-seconds: 30
      idle-timeout-seconds: 30
      max-duration-seconds: 1800              # 改：30min
      webhook-timeout-seconds: 10             # 新
```

---

## 测试要点

**ai-gateway**：
- `CallbackConfigServiceTest`（新）：v1/v2 命中、缓存隔离、v1 仅 chat scope、v2 全 scope
- `WebHookExecutorTest`（新）：2xx 不回流；失败回 tool_error；NoAuth/SOA/APIG header 写入；appId=null 跳过 X-App-Id
- `CloudConnectionLifecycleTest`（扩展）：pause 后 onEventReceived/onHeartbeat 触发的 reset 都 no-op；resume 后正常计时；maxDuration 不受 pause 影响
- `Sse/WebSocketProtocolStrategyTest`（扩展）：`question` / `permission.ask` 触发 pause；`permission.reply` 不触发；其他事件触发 resume
- `CloudAgentServiceTest`（扩展）：action→scope 映射；channel/action 不匹配回 tool_error；v1 拒绝 q_r/p_r

**skill-server**：
- `BusinessScopeStrategyTest`（扩展）：chat 不写 reply 字段；q_r 写 `replyToolCallId+replyAnswers`；p_r 写 `replyPermissionId+replyResponse`
- `parseAnswersTest`：4 种输入兜底正确
- `DefaultCloudRequestStrategyTest`（扩展）：chat 不含 replyContext；q_r 序列化 `replyContext: {type, toolCallId, answers}`；p_r 序列化 `replyContext: {type, permissionId, response}`
- `CloudEventTranslatorTest`（扩展）：单结构 vs questions 数组形态，`QuestionInfo.questions` 正确填充；**`extParam` 字段透传**（含 / 缺省 / 嵌套对象）到 `QuestionInfo.extParam`

---

## Acceptance Criteria

- [ ] feature flag 默认 v1，行为完全等价现状
- [ ] v2 下 chat 通过 `callback:weagent:chat` 拿到 endpoint，行为与 v1 等价（X-App-Id 不发）
- [ ] v2 下 q_r 通过 `callback:weagent:question_reply` 走 WebHook，2xx 不回流任何 GatewayMessage
- [ ] q_r cloudRequest 含 `replyContext: {type:"question_reply", toolCallId, answers}`；单题单选/多选/多题/纯文本兜底全部覆盖
- [ ] v2 下 p_r 通过 `callback:weagent:permission_reply` 走 WebHook，cloudRequest 含 `replyContext: {type:"permission_reply", permissionId, response}`
- [ ] v1 模式下 q_r/p_r 直接拒绝
- [ ] chat 收 `question`/`permission.ask` 后 idle timer 暂停；后续事件 resume；maxDuration 兜底
- [ ] WebHook 失败回 `tool_error`，**不**主动 close 原 chat 连接
- [ ] business 云端 `question` 事件含 `questions[]` 时正确遍历填充；单结构兜底为单元素
- [ ] business 云端 `question` 事件的 `extParam` 字段端到端透传：cloud → gateway → SS（`QuestionInfo.extParam`）→ miniapp / external WS，原样不修改
- [ ] q_r/p_r 入口仅 external inbound + miniapp 生效；IM inbound 不动
- [ ] 全量 mvn test 通过；GitNexus impact / detect_changes 落到预期 scope

---

## Out of Scope

- v1 接口下线时间表（v2 稳定后另定）
- WebHook 重试 / 跨 invocation 协调（如 q_r 失败时主动 close chat）
- SOA / APIG 真实鉴权实现（仍占位）
- IM inbound (`/api/inbound/messages`) 入口本身不开放 q_r/p_r 能力（保持仅 chat）；IM 单聊/群聊场景的 q_r/p_r 由调用方使用 `/api/external/invoke` 入口（已有支持）
- 前端 UI 多问题渲染（QuestionCard 仅渲染 `questions[0]`）
- 入站 DTO 字段类型升级（content 仍 String，值约定为 stringified 数组，旧调用方兜底兼容）
- OpenCode personal scope 的 question 多问题改造
- rebuild 的 business scope 处理（不存在）
- per-ak 灰度

---

## Definition of Done

- 单测全绿，回归无失败
- GitNexus impact 审查通过；detect_changes 落到预期 scope
- `cloud-agent-protocol.md` 修订：
  - §5.5.1 `question` 事件加 `questions[]` 数组形态；加 `extParam` 字段（任意 JSON，平台原样透传到 SS / miniapp / external WS）
  - §10.2 invoke payload `answer` 字段值约定（stringified `[[...]]`）
  - §10.3 cloudRequest q_r 体：把现有 `answer: String`（顶层）**改为** `replyContext: {type:"question_reply", toolCallId, answers: List<List<String>>}`；p_r 体：把现有顶层 `permissionId + response` **改为** `replyContext: {type:"permission_reply", permissionId, response}`
  - 三个 scope 名称
  - chat 保活语义 + WebHook fire-and-forget
- application.yml + README 更新配置说明

---

## Open Questions

- `webhook-timeout-seconds=10` 是否够？上线后调
- `max-duration-seconds=1800` 是否需按场景区分？先一刀切
- v1 保留多久？建议 v2 稳定 1-2 个迭代后删
