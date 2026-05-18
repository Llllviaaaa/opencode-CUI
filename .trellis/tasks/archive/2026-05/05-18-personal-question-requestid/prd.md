# personal scope question_reply 协议增 requestId 字段

> v2（吸收 codex round 1 review，2026-05-18）

## Goal

为 **personal scope**（miniapp ↔ skill-server ↔ ai-gateway ↔ agent-plugin ↔ opencode SDK，直连 opencode 的会话链路）的 `question_reply` 流程新增 `requestId` 字段。让新版 plugin 跳过一次 `GET /question` 列表反查，直接 `POST /question/{requestID}/reply`。

skill-server / agent-plugin / skill-miniapp **三层放同一个 PR**，旧版 plugin / 旧版 miniapp 完全无感（全向后/前兼容）。

## Background

### 当前 question_reply 链路（personal scope）

1. opencode 发出 `question.asked` event（来源 `@opencode-ai/sdk/v2` 的 `EventQuestionAsked`，`properties` 即 `QuestionRequest`，`properties.id` = question request id）
2. plugin → ai-gateway → skill-server 上行
3. `skill-server/src/main/java/com/opencode/cui/skill/service/OpenCodeEventTranslator.java` 的 `translateQuestionAsked` (line 521-562) 已经把 `props.path("id")` 取出来，赋值给 `StreamMessage.partId` (line 540)
4. SS push StreamMessage 给 miniapp（partId 在顶层字段，但语义跟 message.part / tool.part 的 partId 重名）
5. 用户答完，miniapp POST 给 SS 的 question_reply endpoint，body 含 `toolSessionId / answer / toolCallId`（**当前不传 requestId / partId**）
6. SS 构造 `InvokeCommand`，发给 plugin
7. plugin `DownstreamMessageNormalizer.normalizeQuestionReplyPayload` (line 284-328) 重建白名单 payload `{toolSessionId, answer, toolCallId?}`，然后丢给 `QuestionReplyAction`
8. `QuestionReplyAction → SessionScopedActionGatewayPort.replyQuestion(sessionId, toolCallId?, answer)`
9. plugin `OpencodeSessionGatewayAdapter.replyQuestion` (`plugins/agent-plugin/plugins/message-bridge/src/adapter/OpencodeSessionGatewayAdapter.ts` line 343-427)：
   - 先 `GET /question?directory=...` 列出所有 pending question requests
   - 用 `(sessionID == toolSessionId && tool.callID == toolCallId)` 过滤
   - 取 matched `request.id` 作为 requestId
   - `POST /question/{requestID}/reply`

### 关键事实（已 codex 实读源码验证）

- opencode 原生 `question.asked` event 的 `properties.id` 就是 question request id（证据：`plugins/agent-plugin/plugins/message-bridge/node_modules/@opencode-ai/sdk/dist/v2/gen/types.gen.d.ts:556-571`，`QuestionRequest.id: string`、`sessionID: string`、`EventQuestionAsked.properties` 即 `QuestionRequest`）
- SDK 接口 `Question.reply` 的 URL 是 `POST /question/{requestID}/reply`，**path 参数 key 是驼峰 `requestID`**（证据：`sdk.gen.d.ts:648-666`，`sdk.gen.js:1263-1278`）
- plugin 端发请求时实际需要的就是这个 id
- SS 端 `OpenCodeEventTranslator` 已经把它取出来，只是放在 `partId` 字段
- SS → miniapp 的 `StreamMessage.QuestionInfo` **没有 requestId** 字段
- miniapp 上行 question_reply 也**没有 requestId** 字段
- **plugin 端 `DownstreamMessageNormalizer` 重建 payload 白名单**：未知字段直接被丢弃（不 reject，但也不透传）
- 因此 plugin 每次 question_reply 都多一次 `GET /question` 反查

### 改动收益

- 新版链路上：plugin 直接拿 requestId 调 SDK，省 1 次列表查询（一次 HTTP RTT + 数十毫秒）
- 协议语义更清晰：`requestId` 在 SS / miniapp / plugin / SDK 各层字段名打通

## Decisions（已与开发方对齐）

### D1：仅 personal scope 改

只动 `OpenCodeEventTranslator`（直连 opencode plugin 的 personal scope）。
**`CloudEventTranslator`（business / default_assistant scope，云端 webhook）不动**——云端业务系统的 question 协议是自定义的，没有 opencode requestId 概念，加该字段无意义。

### D2：plugin 自治 + SS 双带字段（新旧兼容）

SS 给 plugin 的 `QuestionReplyPayload` 同时带 `toolCallId` 和 `requestId`：

- 旧 plugin：normalizer 重建白名单时未知字段被丢弃，继续按 toolCallId 走 `GET /question` 反查 → 行为不变
- 新 plugin：normalizer 透传 requestId，adapter 看到 requestId 非空就直接 `POST /question/{requestID}/reply`；为空才回退老路径

零握手协议改动，新老两侧自治、互不感知。

### D3：QuestionInfo 加 requestId 字段（不动 partId）

`StreamMessage.QuestionInfo` 新增 `private String requestId;` 字段。`OpenCodeEventTranslator.translateQuestionAsked` 把已经取出的 `props.path("id")` 同时填到 `partId`（保留，给 `cache.rememberQuestionPartId` / part 事件配对用）和 `QuestionInfo.requestId`（新字段）。

**不重命名 partId**——partId 在其它 part 事件里有自己的含义，重命名会引发跨事件不一致。

### D4：miniapp 请求体字段名统一 requestId

miniapp 发给 SS 的 question_reply 请求体里，新字段叫 `requestId`，与 SS `QuestionInfo` / plugin `QuestionReplyPayload` / opencode SDK URL path key `requestID`（驼峰）四层贯通。
四个具体写法位置见 Technical Notes 表格。

### D5：rollout 顺序 = SS 先于 miniapp，plugin 任意（修订自 codex Major 3）

**强制部署顺序**：SS 端必须先于 miniapp 端发布。plugin 端任意。

原因：SS 入站 `SendMessageRequest` DTO（`skill-server/src/main/java/com/opencode/cui/skill/controller/SkillMessageController.java:554-564` 附近）当前不含 `requestId` 字段。旧 SS + 新 miniapp 是否容忍未知字段依赖 Jackson 配置（Spring Boot 默认 `FAIL_ON_UNKNOWN_PROPERTIES=false`，但本任务不去验证旧 SS 这点）。简单办法是约束 rollout 顺序：

- ✅ 新 SS + 老 miniapp（SS 先发，miniapp 还没更新）：兼容（miniapp 不发 requestId，SS 不塞 payload，plugin 走老路径）
- ❌ 老 SS + 新 miniapp（rollout 顺序反了）：**不允许**——本任务运维 SOP 写死

### D6：完整兼容性矩阵（修订自 codex Major 3，明确不允许 SS rollout 反向）

| miniapp | plugin | SS | 行为 |
|---|---|---|---|
| 老（不发 requestId）| 老（不识别 requestId）| 老 | 完全走老路径（GET /question 反查），零回归 |
| 老 | 老 | 新 | SS 不收 requestId（miniapp 没发）→ 不塞 payload → plugin 走老路径 |
| 老 | 新 | 新 | normalizer 透传字段缺失 → adapter 看到 requestId=undefined → 回退老路径 |
| 新 | 老 | 新 | 旧 plugin normalizer 把 requestId 丢弃（白名单重建）→ adapter 走老路径 |
| 新 | 新 | 新 | normalizer 透传 requestId → adapter 走快路径，省一次 GET /question |
| 新 | 任意 | 老 | **不允许**（D5 rollout 约束）|

### D7：requestId 视为 capability token，快路径不二次校验（修订自 codex Major 2）

opencode 生成的 `requestId` 是 UUID，**不可猜测、不可跨 session 复用**。SS 已经在 endpoint 层鉴权了 session 归属（cookie userId / ak 校验）。因此快路径设计取舍：

- plugin 收到非空 requestId 直接 `POST /question/{requestID}/reply`，**不在 plugin 侧二次校验 requestId 与 toolSessionId / toolCallId 的关联**
- 风险接受：如果 miniapp 请求被篡改/陈旧 → 答到 session 内其它 question；但 session 内是同一用户，影响面有限
- 加 1 条 plugin 负向单测固化"伪造 requestId（不存在）→ opencode SDK 层返错"（确保 plugin 不会静默吞错）
- 不引入 plugin 端 question.asked registry（避免新增状态 + 重启/漏报同步问题）

### D8：requestId 空白字符串视为缺失（修订自 codex Minor 2）

- SS 端 `SkillMessageController.routeToGateway` 只在 `requestId != null && !requestId.isBlank()` 时 `payload.put("requestId", ...)`
- plugin 端 normalizer 对 `requestId` 也采用 `requireNonEmptyString` 风格校验（对齐现有 toolCallId 处理）
- 空白等同未提供 → 走 fallback

### D9：历史恢复走 fallback（修订自 codex Minor 3，MVP 接受）

- 当前 SS 持久化 `SkillMessagePart`（`SkillMessagePart.java:34-69`）和 miniapp 端 `BackendMessagePart`（`skill-miniapp/src/protocol/history.ts:3-35`）都没有 requestId 字段
- MVP 接受：用户刷新页面后再回答旧 pending question 时，从历史 state 拿不到 requestId，**自动走老路径（toolCallId 反查）**，功能完整、只是不享受快路径优化
- 后续可选项：把 requestId 持久化到 SkillMessagePart + history。**本任务不做**

## Requirements

### skill-server

1. **`StreamMessage.QuestionInfo`** (`skill-server/src/main/java/com/opencode/cui/skill/model/StreamMessage.java` line 124-133)：
   - 新增 `private String requestId;`
   - 注意：QuestionInfo 通过 `@JsonUnwrapped` 平铺到 StreamMessage JSON 顶层，所以 wire 上 miniapp 看到的就是 `requestId` 字段
   - 已确认：StreamMessage 顶层无其它名为 `requestId` 的字段（codex 已验证）

2. **`OpenCodeEventTranslator.translateQuestionAsked`** (`skill-server/src/main/java/com/opencode/cui/skill/service/OpenCodeEventTranslator.java` line 521-562)：
   - 已有 `String partId = props.path("id").asText(null);` （line 540）
   - 在 `.questionInfo(QuestionInfo.builder()...build())` 时同时 `.requestId(partId)` 填进去
   - partId 字段保留不动

3. **`SkillMessageController` 入站 DTO + `routeToGateway` question_reply 分支**：
   - `SendMessageRequest` DTO（line 554-564 附近）加 `requestId?: String`
   - 从 miniapp 请求 body 读 requestId
   - `routeToGateway` question_reply 分支：**只在 `requestId != null && !requestId.isBlank()` 时**，把 requestId 塞进发给 plugin 的 InvokeCommand payload（`qr.put("requestId", requestId);`）—— D8
   - 缺失或空白时不塞（避免下游 JSON 含 null / 空字符串污染）→ plugin 走 fallback

4. **单测**：
   - `OpenCodeEventTranslatorTest.translatesQuestionAsked` 用例补"requestId 等于 properties.id"断言
   - `StreamMessageTest`：补 `@JsonUnwrapped` 顶层 JSON 序列化断言（含 requestId 字段）
   - `SkillMessageControllerTest` question_reply 用例：补"payload contains requestId when provided / payload no requestId key when blank or absent"

### agent-plugin (message-bridge)

5. **`contracts/downstream-messages.ts`** `QuestionReplyPayload` (line 47-51)：
   - 加 `requestId?: string;`

6. **`protocol/downstream/DownstreamMessageNormalizer.ts.normalizeQuestionReplyPayload`** (line 284-328) — **codex Critical 1 修订**：
   - 当前实现：白名单重建 `{toolSessionId, answer, toolCallId?}`，未知字段被丢弃
   - 改动：在白名单里**加入 requestId**，按 `requireNonEmptyString`（或等价空校验）校验
     - 命中 → 输出含 `requestId` 的 payload
     - 缺失 / 空白 → 输出不含 requestId 的 payload（兼容 D8 缺失语义）
   - 这是本任务的 **Critical 必修点**，没有这步快路径永远不会生效

7. **`port/SessionScopedActionGatewayPort.ts`** `replyQuestion` 签名 (line 32-37)：
   - 加 `requestId?: string;` 参数

8. **`action/QuestionReplyAction.ts`**：
   - 把 `payload.requestId` 透传到 `port.replyQuestion({..., requestId})`
   - log 也带 requestId 字段方便排障

9. **`adapter/OpencodeSessionGatewayAdapter.ts.replyQuestion`** (line 343-427)：
   - 保留 `withResolvedDirectory` 外层包装不动（依然需要 directory 解析）
   - 在 handler 内部前置 `if (parameters.requestId)` 判断：
     - 命中：**快路径**——直接 `executeSdkCall({sourceOperation: 'question.reply', promiseFactory: () => client._client.post({ url: '/question/{requestID}/reply', path: { requestID: parameters.requestId }, body: { answers: [[parameters.answer]] }, headers: {'Content-Type':'application/json'}, ...(directory ? { query: { directory } } : {}) })})`，onSuccess 返回 `{ requestId: parameters.requestId, replied: true }`
     - 未命中：**回退路径**——保留现有 `GET /question` 反查 + toolCallId 过滤 + `POST /question/{found-id}/reply` 三步流程
   - **不**在 plugin 侧二次校验 requestId 与 session/toolCallId 的关联（D7）

10. **单测**（codex Major 4 修订：测试落到 normalizer 级 + capability token 负向）：
    - `downstream-message-normalizer.test.mjs` 加：
      - "old normalizer shape ignores unknown fields"：payload 含 requestId、normalizer 输出不含 requestId（旧行为契约）
      - 新增 normalizer 用例：payload 含 requestId → 输出含 requestId
      - 新增 normalizer 用例：payload 含 blank requestId → 输出不含 requestId（D8）
    - `opencode-session-gateway-adapter.test.mjs` 加：
      - "replyQuestion with requestId skips GET /question and posts directly"：assert getCalls 为空、postCalls 1 次、path 含 requestId
      - 保留 "replyQuestion without requestId falls back to /question lookup"
      - 负向用例（D7 capability token 兜底）："replyQuestion with invalid requestId surfaces SDK error"

### skill-miniapp（codex Major 1 修订：完整传播链）

任何一环漏了 requestId 都会丢字段。完整改动锚点：

11. **类型定义**：
    - `skill-miniapp/src/protocol/types.ts:58-84` `StreamMessage` 加 `requestId?: string`
    - `skill-miniapp/src/protocol/types.ts:114-132` `MessagePart` 加 `requestId?: string`

12. **流式接收路径**：
    - `useSkillStream.normalizeIncomingStreamMessage` (line 322-329)：spread 时确保 requestId 透传（理论上 spread 已 cover，但要测）
    - `StreamAssembler.handleMessage` question 分支 (`StreamAssembler.ts:187-203`)：增加 `requestId` 进 part 字段
    - `streamMessageToSubPart` (`useSkillStream.ts:304-315`)：question 类型 part 复制 requestId

13. **历史恢复路径**（D9 接受 MVP fallback）：
    - `history.ts:3-35` `BackendMessagePart` 不加 requestId 字段
    - `history.ts:185-204` question 分支不输出 requestId
    - 行为：刷新后 UI question state 里 requestId 为空 → 用户回答时 body 不带 requestId → SS 不塞 payload → plugin 走老路径

14. **组件回调链**：以下文件的 onReplyQuestion / onAnswer 等回调签名加 `requestId?: string` 参数：
    - `QuestionCard.tsx:33,42`
    - `SubtaskBlock.tsx:11,79`
    - `MessageBubble.tsx:15,66`
    - `ConversationView.tsx:8`
    - `app.tsx:104-110`

15. **HTTP 入口 + API client**：
    - `useSkillStream.ts:1050-1070` 处理 question reply 的 hook：把 requestId 透传给 api
    - `utils/api.ts:276-284` `sendMessage`（或对应的 question reply 函数）的 request body 加 `requestId?` 字段

16. **单测**（codex Major 4）：
    - 类型 & assembler 单测：question event 带 requestId → MessagePart 含 requestId
    - 组件回调单测：QuestionCard onAnswer → ConversationView → app → api.sendMessage 调用时 body 含 requestId
    - 历史恢复单测：BackendMessagePart 无 requestId → state 无 requestId → API 调用 body 不含 requestId（D9）

### 文档同步（codex Minor 1）

17. 同 PR 内更新 3 份协议文档：
    - `docs/superpowers/specs/2026-05-12-miniapp-skill-server-protocol.md:246-259` 和 `:263-267`（miniapp body + SS→GW payload 加 requestId 说明）
    - `docs/superpowers/specs/2026-05-12-gateway-plugin-protocol.md:1005-1018`（plugin question_reply payload + fallback 语义）
    - `documents/protocol/v3/02-skillserver-gateway.md:207-214`（SS↔GW question_reply payload）

## Acceptance Criteria

### A. SS 侧

- [ ] `OpenCodeEventTranslator.translateQuestionAsked` emit 的 StreamMessage：`questionInfo.requestId == properties.id == partId`（三者同源）
- [ ] StreamMessage JSON 序列化：`requestId` 字段平铺在顶层（@JsonUnwrapped）
- [ ] `SkillMessageController.routeToGateway` question_reply 分支：
  - miniapp body 带非空 requestId → InvokeCommand payload 含 `requestId`
  - body 不带 / 带空白 requestId → InvokeCommand payload 无 `requestId` key

### B. plugin 侧

- [ ] `DownstreamMessageNormalizer.normalizeQuestionReplyPayload`：
  - 输入含非空 requestId → 输出含 requestId（新行为）
  - 输入含空白 / 缺失 requestId → 输出不含 requestId（D8）
  - 输入含 requestId 但 normalizer 仍是旧版（仅作为兼容性测试参考点）：输出不含 requestId（旧契约固化）
- [ ] `QuestionReplyAction` 收到 payload.requestId → 透传给 port.replyQuestion
- [ ] `OpencodeSessionGatewayAdapter.replyQuestion` with requestId → **不调** `GET /question`，直接 `POST /question/{requestID}/reply`
- [ ] `OpencodeSessionGatewayAdapter.replyQuestion` without requestId → 保留现有 `GET /question` + toolCallId 过滤 + `POST /question/{found-id}/reply` 三步流程
- [ ] 伪造 / 不存在的 requestId 走快路径 → opencode SDK 返错 → adapter 透传错误（D7 capability token 兜底）

### C. miniapp 侧（codex Major 1 修订）

- [ ] question event 收到后：StreamMessage 顶层 requestId 经 `StreamAssembler` 落入 MessagePart.requestId、再落入 UI question state
- [ ] QuestionCard.onAnswer → SubtaskBlock → MessageBubble → ConversationView → app → useSkillStream → api.sendMessage 调用时 body 含 `requestId`（当 state 有 requestId 时）
- [ ] 历史恢复（D9）：从 history 重建的 question state 没有 requestId → api.sendMessage body 不含 requestId

### D. 端到端

- [ ] **新 miniapp + 新 plugin + 新 SS** + 实时 question 流：plugin 端**只有 1 次** `POST /question/{id}/reply` 调用（无 `GET /question` 调用）
- [ ] 新 miniapp + 新 plugin + 新 SS + 历史回答（D9）：plugin 端走老路径（GET + filter + POST）
- [ ] 新 miniapp + 旧 plugin + 新 SS：plugin 走老路径（normalizer 丢弃 requestId），功能正确
- [ ] 旧 miniapp + 新 plugin + 新 SS：plugin 走老路径（payload 无 requestId），功能正确

## Out of Scope (MVP)

- 不动 business / default_assistant scope（CloudEventTranslator）
- 不删除 toolCallId 字段（保留向后兼容）
- 不重命名 `StreamMessage.partId`（不动 message.part / tool.part 等其它事件的 partId 语义）
- 不引入 protocol version 握手协议
- 不为 permission_reply 做类似改动（permission 走的是另一套 permissionId，本任务范围不涉及）
- **不引入 plugin 端 question.asked registry**（D7 capability token 设计，不二次校验）
- **不持久化 requestId 到 history**（D9：刷新后回答走 fallback）
- 不验证旧 SS 对 miniapp 未知字段的容忍（D5 rollout 顺序约束代替）

## Known Issues / Future Evolution

1. **未来全网新版 plugin 稳定后**，可考虑在 SS 端废弃 toolCallId fallback（即 SS 只发 requestId），plugin 端简化代码、删除 `GET /question` 反查分支。本任务暂不做。
2. **未来若加 protocol version 握手**，可以让 SS 在 plugin 不支持新字段时跳过传 requestId，节省一点 wire 字节——YAGNI。
3. **partId 与 requestId 同源但不同名**：本任务后 OpenCodeEventTranslator 同时填 partId 和 requestId，两者值相同。任何一边重命名都需谨慎——partId 还有 message.part / tool.part 等其它使用方。
4. **requestId 持久化到 history**：D9 接受 MVP fallback。若运营反馈"刷新后回答慢一拍"明显，再考虑把 requestId 加进 SkillMessagePart + miniapp BackendMessagePart 的 schema。
5. **plugin 端 capability token 风险**：D7 接受 session 内可能误答到其它 question 的风险。若未来安全敏感性提升，可重启时拉 plugin 端 question.asked registry 做二次校验。
6. **rollout 顺序硬约束**：D5 要求 SS 先于 miniapp 上线。运维 SOP 必须强调；若工程组未来有反向 rollback 需求（旧 SS + 新 miniapp），需要先验证旧 SS Jackson 默认 `FAIL_ON_UNKNOWN_PROPERTIES=false` 行为，本任务不做。

## Technical Notes

### 跨层字段命名收敛

| 层 | 字段名 | 类型 | 出现位置 |
|---|---|---|---|
| SS internal model | `QuestionInfo.requestId` | `String` | StreamMessage |
| SS → miniapp wire (JSON) | `requestId`（@JsonUnwrapped 顶层）| string | WebSocket push payload |
| miniapp UI state | `MessagePart.requestId` | `string?` | UI 内部 question state |
| miniapp → SS wire (JSON) | `requestId` | string | question_reply HTTP body |
| SS internal | `SendMessageRequest.requestId` | `String?` | controller DTO |
| SS → plugin (InvokeCommand payload) | `requestId` | string | JSON Map key |
| plugin contract | `QuestionReplyPayload.requestId` | `string?` | downstream-messages.ts |
| plugin normalizer | white-listed field `requestId` | `string?` | DownstreamMessageNormalizer |
| plugin port | `replyQuestion({requestId})` | `string?` | SessionScopedActionGatewayPort |
| opencode SDK URL | `{requestID}` path 变量 | string | `POST /question/{requestID}/reply` |

注意最后一行：opencode SDK 的 URL 模板用驼峰 `requestID`（与其它 SDK URL 模板一致），其它八处统一用驼峰 `requestId`（与项目其它字段命名风格一致）。

### @JsonUnwrapped 影响

QuestionInfo 加 requestId 后，StreamMessage 顶层 JSON 会增加 `requestId` 字段。已确认 StreamMessage 顶层无其它名为 `requestId` 的字段，且仅 question 事件会带 requestId（其它事件 questionInfo 为 null → 整组字段被 `@JsonInclude(Include.NON_NULL)` 跳过）。

### opencode SDK 关键假设（codex round 1 已实读源码验证）

- `@opencode-ai/sdk@1.2.15` 的 `QuestionRequest` 类型：`id: string` + `sessionID: string` + `tool: { callID, ... }`（`plugins/agent-plugin/plugins/message-bridge/node_modules/@opencode-ai/sdk/dist/v2/gen/types.gen.d.ts:556-563`）
- `EventQuestionAsked.properties` 类型 = `QuestionRequest`（`types.gen.d.ts:568-571`）
- `Question.reply` SDK 方法签名用 `requestID` 大写驼峰 path 参数（`sdk.gen.d.ts:648-666`）
- 底层 URL：`/question/{requestID}/reply`（`sdk.gen.js:1263-1278`）
- adapter 可选项：直接调用生成的 `client.question.reply({ requestID, directory, answers })` 或沿用现有 `_client.post`。建议沿用现有 `_client.post` 风格（与 fallback 路径保持一致）

### 关键代码锚点（基于本次评审 + codex 实读核准）

**已核准 + 本任务改动 (SS)**：
- `skill-server/src/main/java/com/opencode/cui/skill/model/StreamMessage.java` (QuestionInfo line 124-133)
- `skill-server/src/main/java/com/opencode/cui/skill/service/OpenCodeEventTranslator.java` (line 521-562, 540)
- `skill-server/src/main/java/com/opencode/cui/skill/controller/SkillMessageController.java` (SendMessageRequest line 554-564 附近 + routeToGateway question_reply 分支)
- `skill-server/src/main/java/com/opencode/cui/skill/payload/PayloadBuilder.java:53-68`（不需要改，但要注意 D8 空白处理 SS 侧靠 caller 控制）

**已核准 + 本任务改动 (plugin)**：
- `plugins/agent-plugin/plugins/message-bridge/src/contracts/downstream-messages.ts` (line 47-51)
- `plugins/agent-plugin/plugins/message-bridge/src/protocol/downstream/DownstreamMessageNormalizer.ts` (line 284-328) ← Critical
- `plugins/agent-plugin/plugins/message-bridge/src/port/SessionScopedActionGatewayPort.ts` (line 32-37)
- `plugins/agent-plugin/plugins/message-bridge/src/action/QuestionReplyAction.ts`
- `plugins/agent-plugin/plugins/message-bridge/src/adapter/OpencodeSessionGatewayAdapter.ts` (line 343-427)

**已核准 + 本任务改动 (miniapp，完整传播链)**：
- `skill-miniapp/src/protocol/types.ts:58-84` (StreamMessage)
- `skill-miniapp/src/protocol/types.ts:114-132` (MessagePart)
- `skill-miniapp/src/protocol/StreamAssembler.ts:187-203` (question 分支)
- `skill-miniapp/src/hooks/useSkillStream.ts:304-315` (streamMessageToSubPart)
- `skill-miniapp/src/hooks/useSkillStream.ts:322-329` (normalizeIncomingStreamMessage)
- `skill-miniapp/src/hooks/useSkillStream.ts:1050-1070` (question reply 入口)
- `skill-miniapp/src/utils/api.ts:276-284` (sendMessage body)
- `skill-miniapp/src/components/QuestionCard.tsx:33,42`
- `skill-miniapp/src/components/SubtaskBlock.tsx:11,79`
- `skill-miniapp/src/components/MessageBubble.tsx:15,66`
- `skill-miniapp/src/components/ConversationView.tsx:8`
- `skill-miniapp/src/app.tsx:104-110`

**已核准 + 本任务改动 (docs)**：
- `docs/superpowers/specs/2026-05-12-miniapp-skill-server-protocol.md:246-259, 263-267`
- `docs/superpowers/specs/2026-05-12-gateway-plugin-protocol.md:1005-1018`
- `documents/protocol/v3/02-skillserver-gateway.md:207-214`

**不动（参考用）**：
- `skill-miniapp/src/protocol/history.ts:3-35, 185-204`（D9 接受 MVP fallback，不持久化 requestId）
- `skill-server/src/main/java/com/opencode/cui/skill/model/SkillMessagePart.java:34-69`（同上）

### Brainstorming + Review 收口

- 方案选择由用户在 brainstorming 阶段对齐：
  - Plugin 自治 + SS 双带字段（区分新旧 plugin）
  - QuestionInfo 新增独立 requestId 字段（不复用 partId 语义）
  - 字段名统一 `requestId`
  - 单 PR 三层一起改
- Codex round 1 评审（gpt-5.5 + xhigh effort，read-only 实读源码）发现 1 Critical / 4 Major / 3 Minor / 2 Nit；本 v2 PRD 已全部吸收：
  - Critical 1（normalizer 丢字段）→ Requirements 6
  - Major 1（miniapp 传播链不足）→ Requirements 11-15 完整列出 13 处锚点
  - Major 2（快路径绕过过滤）→ D7 接受 capability token 模式
  - Major 3（兼容矩阵缺 SS 维度）→ D5 强制 SS 先于 miniapp + D6 矩阵补全
  - Major 4（AC 落到 normalizer 级）→ AC B + Requirements 10 修订
  - Minor 1（文档漂移）→ Requirements 17
  - Minor 2（空白字符串）→ D8
  - Minor 3（历史恢复）→ D9
  - Nit 1（SDK 证据）→ Technical Notes "opencode SDK 关键假设"
  - Nit 2（锚点补充）→ Technical Notes "关键代码锚点" 全量补完
- PR base = `main`（PR #35 已合，main 含 PR3+PR4 的 default_assistant scope 接入代码作为参考——但本任务**不依赖** default_assistant 改动，纯 personal scope）
- Codex round 2 评审（开工前再过一轮）：开发方决定是否要跑。Critical 已闭合后可以进 PR1。
