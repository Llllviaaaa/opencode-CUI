# Research: PR2 全链路重命名命中点盘点（`QuestionInfo.requestId` → `questionId`）

- **Query**: PR2 开工前盘点所有命中点；区分该改 / 不该改；评估 JSON 兼容性
- **Scope**: internal
- **Date**: 2026-05-19

---

## 关键判定原则

**只改 question 域**：与 `StreamMessage.QuestionInfo.requestId` 同源的字段（来源：`question.asked event` 的 `properties.id`，作用：personal scope 快路径 `POST /question/{requestID}/reply` 鉴权 token）。

**绝不动**：
1. opencode 上游事件里的 `properties.requestID`（permission.replied / question.replied 等上游字段）
2. opencode SDK URL path 模板 `/question/{requestID}/reply` 及其 `path: { requestID }` 占位符
3. miniapp `useSkillStream.ts` 里的 `historyRequestRef`（局部变量名，业务语义为"历史请求 id"，与 question 域无关）
4. v1/v2 协议文档历史快照
5. 任何 trace/log 字段 `requestId` 如果不是 question 域的

---

## 1. 该改的命中点（按层分组）

### 1.1 skill-server 后端（Java）

| 文件 : 行 | 上下文 | 改名后 |
|---|---|---|
| `skill-server/src/main/java/com/opencode/cui/skill/model/StreamMessage.java:137` | 注释："…miniapp 侧字段名 `requestId`。" | `questionId` |
| `skill-server/src/main/java/com/opencode/cui/skill/model/StreamMessage.java:139` | 注释："新版 plugin 收到 question_reply payload 含 requestId 时可走快路径" | `questionId`（描述文字，URL 注释 `/question/{requestID}/reply` 保留） |
| `skill-server/src/main/java/com/opencode/cui/skill/model/StreamMessage.java:142` | `private String requestId;`（`QuestionInfo` 字段，`@JsonUnwrapped` 平铺到顶层） | `private String questionId;` + 显式 `@JsonProperty("questionId")` |
| `skill-server/src/main/java/com/opencode/cui/skill/service/OpenCodeEventTranslator.java:560` | 注释："requestId 与 partId 同源（properties.id）…" | `questionId` |
| `skill-server/src/main/java/com/opencode/cui/skill/service/OpenCodeEventTranslator.java:561` | `.requestId(partId)` 构造 QuestionInfo | `.questionId(partId)` |
| `skill-server/src/main/java/com/opencode/cui/skill/controller/SkillMessageController.java:232` | 注释："D8：仅当 requestId 非空白时塞入 payload" | `questionId` |
| `skill-server/src/main/java/com/opencode/cui/skill/controller/SkillMessageController.java:233` | `String requestId = request.getRequestId();` | `String questionId = request.getQuestionId();` |
| `skill-server/src/main/java/com/opencode/cui/skill/controller/SkillMessageController.java:234` | `if (requestId != null && !requestId.isBlank())` | `questionId` |
| `skill-server/src/main/java/com/opencode/cui/skill/controller/SkillMessageController.java:235` | `qr.put("requestId", requestId);` —— **下行 payload key** | `qr.put("questionId", questionId);` |
| `skill-server/src/main/java/com/opencode/cui/skill/controller/SkillMessageController.java:570-571` | DTO Javadoc："…opencode question request id …非空时 SS 透传给 plugin…" | 改文字注释；URL `POST /question/{requestID}/reply` **保留** |
| `skill-server/src/main/java/com/opencode/cui/skill/controller/SkillMessageController.java:574` | `private String requestId;`（SendMessageRequest 内嵌 DTO） | `private String questionId;`（Lombok `@Data` 会自动生成 `getQuestionId()`） |

**测试**：
| 文件 : 行 | 上下文 | 改名后 |
|---|---|---|
| `skill-server/src/test/java/com/opencode/cui/skill/service/OpenCodeEventTranslatorTest.java:192` | 注释 "requestId 与 properties.id == partId 同源…" | `questionId` |
| `skill-server/src/test/java/com/opencode/cui/skill/service/OpenCodeEventTranslatorTest.java:193-194` | `assertEquals(…, translated.getQuestionInfo().getRequestId())` ×2 | `getQuestionId()` |
| `skill-server/src/test/java/com/opencode/cui/skill/service/OpenCodeEventTranslatorTest.java:198` | `@DisplayName("question.asked: requestId == partId == properties.id …")` | `questionId` |
| `skill-server/src/test/java/com/opencode/cui/skill/service/OpenCodeEventTranslatorTest.java:219` | `@DisplayName("StreamMessage @JsonUnwrapped surfaces QuestionInfo.requestId at top level")` | `questionId` |
| `skill-server/src/test/java/com/opencode/cui/skill/service/OpenCodeEventTranslatorTest.java:227` | `.requestId("req-1")` builder 调用 | `.questionId("req-1")` |
| `skill-server/src/test/java/com/opencode/cui/skill/service/OpenCodeEventTranslatorTest.java:233` | `node.path("requestId").asText()` —— 顶层 JSON 断言 | `node.path("questionId").asText()` |
| `skill-server/src/test/java/com/opencode/cui/skill/controller/SkillMessageControllerTest.java:831` | `@DisplayName("…requestId 非空 → payload 含 requestId…")` | `questionId` |
| `skill-server/src/test/java/com/opencode/cui/skill/controller/SkillMessageControllerTest.java:854` | `payload.get("requestId").asText()` | `payload.get("questionId")` |
| `skill-server/src/test/java/com/opencode/cui/skill/controller/SkillMessageControllerTest.java:858` | `@DisplayName("…requestId 为 null → payload 无 requestId key（D8）")` | `questionId` |
| `skill-server/src/test/java/com/opencode/cui/skill/controller/SkillMessageControllerTest.java:875` | 注释 "// requestId 不设置（null）" | `// questionId 不设置` |
| `skill-server/src/test/java/com/opencode/cui/skill/controller/SkillMessageControllerTest.java:881` | `assertFalse(payload.has("requestId"), …)` | `"questionId"` |
| `skill-server/src/test/java/com/opencode/cui/skill/controller/SkillMessageControllerTest.java:885-908` | 另两个 @DisplayName + assert 块（blank 场景）相同形态 | 同上 |

**注意**：`SkillMessageControllerTest.java:233-234` 的 `OpenCodeEventTranslator.java:451`（`props.path("requestID").asText(null)` —— `permission.replied` 上游字段）**不改**，这是上游 OpenCode 事件 schema。

---

### 1.2 skill-miniapp 前端（TypeScript）

| 文件 : 行 | 上下文 | 改名后 |
|---|---|---|
| `skill-miniapp/src/protocol/types.ts:84` | 注释 "opencode question request id（personal scope 快路径）。来源：SS QuestionInfo.requestId @JsonUnwrapped 顶层" | `questionId` |
| `skill-miniapp/src/protocol/types.ts:85` | `requestId?: string;`（应在 `MessagePart` 或 question 相关 type） | `questionId?: string;` |
| `skill-miniapp/src/protocol/types.ts:134` | 注释 "…只在实时 question event 出现，历史恢复时为 undefined（D9 fallback）" | `questionId` |
| `skill-miniapp/src/protocol/types.ts:135` | `requestId?: string;`（第二处声明，应在 SS→miniapp 顶层 message type） | `questionId?: string;` |
| `skill-miniapp/src/protocol/StreamAssembler.ts:202` | 注释 "personal scope 快路径：透传 SS 顶层 requestId 到 MessagePart" | `questionId` |
| `skill-miniapp/src/protocol/StreamAssembler.ts:203` | `if (msg.requestId) {` | `if (msg.questionId)` |
| `skill-miniapp/src/protocol/StreamAssembler.ts:204` | `part.requestId = msg.requestId;` | `part.questionId = msg.questionId;` |
| `skill-miniapp/src/hooks/useSkillStream.ts:28` | type: `sendMessage: (text: string, options?: { toolCallId?: string; requestId?: string }) => Promise<void>;` | `questionId` |
| `skill-miniapp/src/hooks/useSkillStream.ts:314` | `requestId: msg.requestId,`（StreamAssembler 输入对象组装） | `questionId: msg.questionId` |
| `skill-miniapp/src/hooks/useSkillStream.ts:1052` | `async (text: string, options?: { toolCallId?: string; requestId?: string }) => {` —— sendMessageFn 入参 | `questionId` |
| `skill-miniapp/src/hooks/useSkillStream.ts:1070` | `api.sendMessage(sessionId, text, options?.toolCallId, options?.requestId)` | `options?.questionId` |
| `skill-miniapp/src/utils/api.ts:280` | `sendMessage` 第 4 参数 `requestId?: string,` | `questionId?: string,` |
| `skill-miniapp/src/utils/api.ts:286-288` | 注释 + `if (requestId && requestId.trim()) { body.requestId = requestId; }` —— **上行 HTTP body key** | `questionId` 字段 + `body.questionId = questionId` |
| `skill-miniapp/src/app.tsx:105` | `handleQuestionAnswer = (answer, toolCallId?, _subagentSessionId?, requestId?) =>` | `questionId` |
| `skill-miniapp/src/app.tsx:110-111` | 注释 + `const options: { toolCallId?: string; requestId?: string } = {};` | `questionId` |
| `skill-miniapp/src/app.tsx:115-116` | `if (requestId) { options.requestId = requestId; }` | `questionId` |
| `skill-miniapp/src/components/MessageBubble.tsx:15` | callback type 第 4 参 `requestId?: string` | `questionId` |
| `skill-miniapp/src/components/ConversationView.tsx:8` | 同上 | `questionId` |
| `skill-miniapp/src/components/SubtaskBlock.tsx:11` | 同上 | `questionId` |
| `skill-miniapp/src/components/QuestionCard.tsx:6` | `onAnswer?: (..., requestId?: string) => void;` | `questionId` |
| `skill-miniapp/src/components/QuestionCard.tsx:33` | `onAnswer?.(label, part.toolCallId, part.subagentSessionId, part.requestId);` | `part.questionId` |
| `skill-miniapp/src/components/QuestionCard.tsx:42` | 同上 | 同上 |

**测试 fixture**：miniapp 当前没有 `*.test.*` / `*.spec.*` 源文件（已确认：`Glob skill-miniapp/**/*.test.*` 仅命中 `node_modules`）。无需改 miniapp 测试。

---

### 1.3 plugin · message-bridge（TypeScript）

| 文件 : 行 | 上下文 | 改名后 |
|---|---|---|
| `plugins/agent-plugin/plugins/message-bridge/src/contracts/downstream-messages.ts:52-56` | `QuestionReplyPayload.requestId` 字段 + 注释 | `questionId?` |
| `plugins/agent-plugin/plugins/message-bridge/src/contracts/downstream-messages.ts:99-101` | `QuestionReplyResultData.requestId: string;`（adapter 返回值给上层日志/追踪用） | **决策点**：若想彻底净化命名 → `questionId`；若想保留 result data 用 opencode 域 token 名 → 保留。**建议**：跟随重命名为 `questionId`，与入参对齐。 |
| `plugins/agent-plugin/plugins/message-bridge/src/protocol/downstream/DownstreamMessageNormalizer.ts:325-341` | 注释 "D8：requestId 缺失/空白…" + 校验 `payload.requestId` 多处 + `result.requestId = requestId.value;` | 全部 `questionId`（含错误 path `payload.questionId`） |
| `plugins/agent-plugin/plugins/message-bridge/src/port/SessionScopedActionGatewayPort.ts:35-39` | 注释 + `requestId?: string;`（reply param 接口字段） | `questionId?`（URL 注释 `POST /question/{requestID}/reply` 保留） |
| `plugins/agent-plugin/plugins/message-bridge/src/adapter/OpencodeSessionGatewayAdapter.ts:346` | `requestId?: string;` 函数参数类型 | `questionId?` |
| `plugins/agent-plugin/plugins/message-bridge/src/adapter/OpencodeSessionGatewayAdapter.ts:355-358` | 注释 + `if (parameters.requestId) {` | `questionId`（注释里 `/question/{requestID}/reply` URL 形态保留） |
| `plugins/agent-plugin/plugins/message-bridge/src/adapter/OpencodeSessionGatewayAdapter.ts:359` | `const fastRequestId = parameters.requestId;` | 建议 `const fastQuestionId = parameters.questionId;`（局部变量名同步） |
| `plugins/agent-plugin/plugins/message-bridge/src/adapter/OpencodeSessionGatewayAdapter.ts:362` | `requestId: fastRequestId,`（debug log key） | `questionId: fastQuestionId` |
| `plugins/agent-plugin/plugins/message-bridge/src/adapter/OpencodeSessionGatewayAdapter.ts:368-369` | `url: '/question/{requestID}/reply', path: { requestID: fastRequestId },` —— **opencode SDK URL 模板，不改 `requestID`**；只改变量名 `fastRequestId` → `fastQuestionId` | URL 不改；变量名跟随 |
| `plugins/agent-plugin/plugins/message-bridge/src/adapter/OpencodeSessionGatewayAdapter.ts:379` | `data: { requestId: fastRequestId, replied: true }`（result data） | 跟随 contracts 决策；建议 `questionId` |
| `plugins/agent-plugin/plugins/message-bridge/src/adapter/OpencodeSessionGatewayAdapter.ts:422` | `const requestId = parameters.toolCallId ? readString(matchedRequests[0]?.id) : …` —— **fallback 路径下 opencode 返回的 question.id**，含义还是 question 域 | 同步改 `const questionId = …` |
| `plugins/agent-plugin/plugins/message-bridge/src/adapter/OpencodeSessionGatewayAdapter.ts:428` | `if (!requestId)` | `questionId` |
| `plugins/agent-plugin/plugins/message-bridge/src/adapter/OpencodeSessionGatewayAdapter.ts:442-443` | `url: '/question/{requestID}/reply', path: { requestID: requestId },` | URL 不改；`requestID: questionId` |
| `plugins/agent-plugin/plugins/message-bridge/src/adapter/OpencodeSessionGatewayAdapter.ts:453` | `data: { requestId, replied: true, … }` | 跟随 contracts → `questionId` |
| `plugins/agent-plugin/plugins/message-bridge/src/action/QuestionReplyAction.ts:26-27` | `requestId: payload.requestId, hasRequestId: Boolean(payload.requestId),`（日志） | `questionId / hasQuestionId` |
| `plugins/agent-plugin/plugins/message-bridge/src/action/QuestionReplyAction.ts:44` | `...(payload.requestId ? { requestId: payload.requestId } : {})` —— 透传到 port | `questionId` |
| `plugins/agent-plugin/plugins/message-bridge/src/action/QuestionReplyAction.ts:56, 68` | 错误日志里 `requestId: payload.requestId` | `questionId` |
| `plugins/agent-plugin/plugins/message-bridge/README.md:126` | 文档里的 `POST /question/{requestID}/reply` URL 示例 | URL **不改** |
| `plugins/agent-plugin/plugins/message-bridge/docs/product/prd.md:22` | 同上 URL 描述 | URL **不改** |
| `plugins/agent-plugin/plugins/message-bridge/docs/design/interfaces/end-to-end-message-flow.md:115, 731, 1023` | 三处描述 "requestId" 作为 OpenCode question API 内部字段 | 这是描述 opencode 上游域的字段名，**保留**（与 opencode 上游一致）。如果想跟 SS↔plugin 协议字段名一致，可改第 115 行（"`requestId` \| `OpenCode question API` \| `B4` 内部"）—— **建议保留不改**，因为这一列原本就是描述 opencode 上游字段名。 |
| `plugins/agent-plugin/plugins/message-bridge/docs/design/interfaces/end-to-end-message-flow.md:902-904` | 表格描述 `permission.replied` 含 `properties.requestID` | **不改**，opencode 上游字段 |

**测试**：
| 文件 : 行 | 上下文 | 改名后 |
|---|---|---|
| `plugins/agent-plugin/plugins/message-bridge/tests/unit/downstream-message-normalizer.test.mjs:447` | `test('question_reply: passes requestId through when non-empty …')` | `questionId` |
| `…downstream-message-normalizer.test.mjs:457, 467` | payload fixture `requestId: 'req-uuid-abc'` + 断言 | `questionId` |
| `…downstream-message-normalizer.test.mjs:471, 481, 487-488` | blank 场景 test 名 + fixture + 断言 `result.value.payload.requestId` | `questionId` |
| `…downstream-message-normalizer.test.mjs:492, 507` | "omits requestId when absent" test + `'requestId' in result.value.payload` 断言 | `questionId` |
| `plugins/agent-plugin/plugins/message-bridge/tests/unit/opencode-session-gateway-adapter.test.mjs:798, 851, 931, 958, 963, 1011-1012, 1043` | 多处 `requestId: 'req-uuid-fast'` / `requestId: 'question-request-1'` / test 名 | `questionId`（**注意** 798/963 是 result.data 字段，跟随 contracts；1043 是 reply 参数） |
| `…opencode-session-gateway-adapter.test.mjs:572-573, 807-808, 967-968, 1008` | `url: '/question/{requestID}/reply', path: { requestID: …}` —— SDK URL 模板 | **不改** |
| `plugins/agent-plugin/plugins/message-bridge/tests/unit/actions-coverage.test.mjs:479, 489, 504` | mock `replyQuestion` 返回值 `data: { requestId: 'req-1', replied: true }` + assertDeepEquals | 跟随 contracts → `questionId` |
| `plugins/agent-plugin/plugins/message-bridge/tests/unit/example.test.mjs:16` | mock 返回 `data: { requestId: 'ignored', replied: true }` | 同上 |
| `plugins/agent-plugin/plugins/message-bridge/tests/unit/runtime-protocol.test.mjs:799-800` | `url: '/question/{requestID}/reply', path: { requestID: 'question-request-42' }` | **不改**（SDK URL） |
| `plugins/agent-plugin/plugins/message-bridge/tests/integration/protocol-question.test.mjs:124-125, 220-221` | 同 SDK URL 模板 | **不改** |
| `plugins/agent-plugin/plugins/message-bridge/tests/integration/protocol-directory.test.mjs:394-395, 592-593, 1061-1062` | 同 SDK URL 模板 | **不改** |
| `plugins/agent-plugin/plugins/message-bridge/tests/fixtures/opencode-events/permission.replied.json:5` | `"requestID": "perm_fixture_1"` —— **permission 域上游 fixture** | **不改** |

---

### 1.4 plugin · message-bridge-openclaw（mock/test bridge）

**整体说明**：openclaw 是模拟 opencode 上游的测试 bridge，它内部维护 `QuestionRecord` 注册表，字段名 `requestId` 表达的就是"opencode question request id"，与 question 域同源。建议跟随重命名以保持全栈一致；但因这是 openclaw 内部实现细节，**也可保留**（决策权交给实现者；建议跟随）。

| 文件 : 行 | 上下文 | 改名后（如跟随） |
|---|---|---|
| `plugins/agent-plugin/plugins/message-bridge-openclaw/src/runtime/QuestionRegistry.ts:15` | `QuestionRecord.requestId: string;` | `questionId` |
| `…QuestionRegistry.ts:27` | `private readonly byRequestId = new Map<string, QuestionRecord>();` | `byQuestionId`（私有，影响小） |
| `…QuestionRegistry.ts:31, 36` | `this.byRequestId.get(record.requestId)` / `set(next.requestId, next)` | 同步 |
| `…QuestionRegistry.ts:58-59, 67-68` | `markResolved(requestId: string, …)` 入参 + 内部使用 | `questionId` |
| `…QuestionRegistry.ts:72-73, 80-81` | `markExpired(requestId: string, …)` 入参 + 内部使用 | `questionId` |
| `…QuestionRegistry.ts:86, 88` | `for (const [requestId, record] of this.byRequestId.entries()) { … this.byRequestId.delete(requestId); }` | `questionId` |
| `plugins/agent-plugin/plugins/message-bridge-openclaw/src/runtime/InteractionPorts.ts:17` | `QuestionReplyParams.requestId: string;` | `questionId` |
| `plugins/agent-plugin/plugins/message-bridge-openclaw/src/session/upstreamEvents.ts:66` | `QuestionAskedEventOptions.requestId: string;` | `questionId` |
| `…upstreamEvents.ts:281` | `id: options.requestId,`（构造 opencode `question.asked` event properties.id） | `id: options.questionId`（**只改变量来源**，opencode event properties.id 字段名 **不改**） |
| `plugins/agent-plugin/plugins/message-bridge-openclaw/src/OpenClawGatewayBridge.ts:1503` | `await this.questionReplyPort.reply({ requestId: record.requestId, … })` | `questionId: record.questionId` |
| `…OpenClawGatewayBridge.ts:1506` | `this.questionRegistry.markResolved(record.requestId);` | `record.questionId` |
| `…OpenClawGatewayBridge.ts:1997` | `const requestId = asString(payload.id);`（从 opencode `question.asked` 事件抽取 properties.id；含义是 question 域 token） | `const questionId = asString(payload.id);` |
| `…OpenClawGatewayBridge.ts:1999` | `if (!requestId || !toolSessionId)` | `questionId` |
| `…OpenClawGatewayBridge.ts:2025, 2035` | `upsertPending({ requestId, … })` / `buildQuestionAskedEvent(…, { requestId, … })` | `questionId` |

**openclaw 测试**：
| 文件 : 行 | 上下文 | 改名后 |
|---|---|---|
| `plugins/agent-plugin/plugins/message-bridge-openclaw/tests/unit/session-event-builders.test.mjs:147` | `buildQuestionAskedEvent("ses_tool_q", { requestId: "question_1", … })` | `questionId` |
| `plugins/agent-plugin/plugins/message-bridge-openclaw/tests/unit/openclaw-gateway-bridge.test.mjs:446` | `{ requestId: "q_req_1", answer: "Vite" }` —— port reply 参数 fixture | `questionId` |

---

### 1.5 v3 协议文档

| 文件 : 行 | 上下文 | 改名后 |
|---|---|---|
| `documents/protocol/v3/02-skillserver-gateway.md:214` | `\| question_reply \| 问题回复 \| {answer, toolCallId?, toolSessionId, requestId?} \|` | `questionId?` |
| `documents/protocol/v3/02-skillserver-gateway.md:216` | 整段说明 "`requestId`（personal scope 快路径）：opencode question request id…" 多处 | `questionId`（描述里 `POST /question/{requestID}/reply` URL 保留） |
| `documents/protocol/v3/05-opencode-to-custom-protocol-mapping.md:103` | `requestID: "req-001"` —— 这是 opencode 上游 `question.asked` event 示例 | **不改**（上游 schema） |
| `documents/protocol/v3/05-opencode-to-custom-protocol-mapping.md:360` | `\| 问题请求 ID \| requestID \| requestId \| — \| — \| — \|` 映射表：opencode 列保留 `requestID`，SS→miniapp 列从 `requestId` 改 `questionId` | 第 3 列 `requestId` → `questionId`；第 2 列保留 |
| `documents/protocol/v3/06-end-to-end-flows.md:548-549` | 描述 `GET /question → 查找匹配的 requestID` `POST /question/{requestID}/reply` —— opencode 上游 URL/字段 | **不改** |
| `documents/protocol/v3/04-plugin-opencode.md:296, 480, 509, 514, 738, 740` | 全部是 opencode 上游 SDK 示例（`/question/{requestID}/reply` URL + `properties.requestID` 字段） | **不改**（line 740 `ActionSuccess<{ requestId, replied: true }>` —— 这个是 plugin result data 形状，跟随 contracts 决策，**建议改为 `questionId`**） |

**v1 / v2 协议文档**：`documents/protocol/v1/**`、`documents/protocol/v2/**` 全部命中点 **不改**（历史快照锁定，PRD Decisions 已明确）。

---

## 2. 不该改的命中点（红线清单）

### 2.1 v1/v2 协议文档（历史快照锁定）
- `documents/protocol/v1/02-skillserver-gateway.md`、`04-plugin-opencode.md`、`05-opencode-to-custom-protocol-mapping.md`、`06-end-to-end-flows.md`、`07-message-type-lifecycle.md`
- `documents/protocol/v2/` 同上 5 个文件
- `documents/im-websocket-link/api_link_websocket.md`（外部 IM 协议文档）
- `docs/superpowers/specs/2026-05-12-miniapp-skill-server-protocol.md`、`2026-05-12-gateway-plugin-protocol.md`、`2026-04-02-plugin-reply-event-forwarding-design.md`
- `docs/superpowers/plans/2026-04-02-plugin-reply-event-forwarding.md`
- `.trellis/spec/guides/cross-layer-thinking-guide.md`、`.trellis/spec/skill-miniapp/frontend/quality-guidelines.md`（spec 文档历史描述）

### 2.2 OpenCode 上游事件字段 `properties.requestID`（permission / question domain 之外的复用）
- `skill-server/src/main/java/com/opencode/cui/skill/service/OpenCodeEventTranslator.java:451` —— `props.path("requestID").asText(null)`（permission.replied 上游字段抽取）
- `skill-server/src/test/java/com/opencode/cui/skill/service/OpenCodeEventTranslatorTest.java:595, 618, 640, 657` —— 上游事件 fixture：`permission.replied` / `question.replied` / `question.rejected` 的 `properties.requestID`
- `plugins/agent-plugin/plugins/message-bridge/tests/fixtures/opencode-events/permission.replied.json:5` —— `"requestID": "perm_fixture_1"` 是 permission 域，非 question 域

### 2.3 opencode SDK URL 路径 / path 占位符
**整条线绝不改 `requestID`**：
- `OpencodeSessionGatewayAdapter.ts:368-369, 442-443`：`url: '/question/{requestID}/reply', path: { requestID: … }`
- `runtime-protocol.test.mjs:799-800`、`protocol-question.test.mjs:124-125, 220-221`、`protocol-directory.test.mjs:394-395, 592-593, 1061-1062`、`opencode-session-gateway-adapter.test.mjs:572-573, 807-808, 967-968, 1008`
- `message-bridge/README.md:126`、`docs/product/prd.md:22`、`docs/design/interfaces/end-to-end-message-flow.md:578-579, 617, 1023-1024`

### 2.4 miniapp `historyRequestRef`（局部变量，非 question 域）
- `skill-miniapp/src/hooks/useSkillStream.ts:957-958`：`const requestId = historyRequestRef.current + 1; historyRequestRef.current = requestId;`
- `skill-miniapp/src/hooks/useSkillStream.ts:963, 970`：`if (!cancelled && historyRequestRef.current === requestId)` —— 这是"历史消息请求竞态去重 token"，与 question 域无关。**保留不动**。

### 2.5 StreamMessage 顶层 / 其他 InfoDTO 里的 requestId
**已确认**：`StreamMessage.java` 顶层（line 33-89）字段无 `requestId`；`ToolInfo` / `PermissionInfo` / `UsageInfo` / `FileInfo` / `QuestionItem` 嵌套类都无 `requestId` 字段。**唯一一处** `requestId` 字段就是 `QuestionInfo.requestId`（line 142）。

### 2.6 其他
- `.trellis/tasks/05-19-gw-cleanup-legacy-route-rename-questionid/task.json` 和 `prd.md`：本任务自身的 PRD，**不改**（PRD 描述的是改名前的状态）。
- `AGENTS.md`（项目工作流文档，未涉及 question 域）—— grep 未命中实际 `requestId`，无需动。

---

## 3. JSON 兼容性边界

### 3.1 线上字段名现状
- **SS → miniapp** WebSocket 消息：`QuestionInfo` 通过 `@JsonUnwrapped` 平铺，顶层 JSON key 为 `"requestId"`（见 `OpenCodeEventTranslatorTest.java:233` 断言 `node.path("requestId").asText()`）。
- **miniapp → SS** HTTP body：`api.ts:288` `body.requestId = requestId`，SS Controller DTO 字段名 `requestId`（`SkillMessageController.java:574`），Jackson 默认 camelCase 命名直接匹配。
- **SS → plugin (gateway)** invoke payload：`SkillMessageController.java:235` `qr.put("requestId", requestId)` 写到 payload JSON，plugin 端 `DownstreamMessageNormalizer.ts:328` 读 `payload.requestId`。

### 3.2 改名后兼容场景
**只有 personal scope 快路径才使用此字段**：
- 缺失/空白 → SS 不塞 payload，plugin 自动走 fallback（`GET /question` + `toolCallId` 反查）。这是 D8 设计：**字段缺失是合法兼容路径**。
- 这意味着：**老 miniapp / 老 plugin 在新协议下"丢失字段"等价于"未提供"，自动降级到 fallback**，不会报错，只是失去快路径优化。

### 3.3 三方向部署兼容性评估

| 场景 | 行为 | 影响 |
|---|---|---|
| 新 SS + 老 miniapp（miniapp 还发 `requestId`） | SS DTO 字段叫 `questionId`，Jackson 找不到 → null → SS 不塞 payload → 走 fallback | 失去快路径，无功能损坏 |
| 老 SS + 新 miniapp（miniapp 发 `questionId`） | 老 SS DTO 字段 `requestId` 找不到 `questionId` → null → 不塞 payload → fallback | 失去快路径，无功能损坏 |
| 新 SS + 老 plugin（plugin 读 `payload.requestId`） | SS 写 `payload.questionId`，老 plugin 读 `requestId` → undefined → fallback | 失去快路径，无功能损坏 |
| 老 SS + 新 plugin | SS 写 `requestId`，新 plugin 读 `questionId` → undefined → fallback | 失去快路径 |
| 新 SS → 新 miniapp（顶层 JSON key `questionId`） | 老 miniapp 读 `msg.requestId` → undefined → MessagePart 无 `requestId` → 用户点回答时 fallback | 失去快路径 |

**结论**：改名**全链路 100% 软兼容**，最坏情况是 personal scope 快路径降级到 fallback（`GET /question` 反查）。不会导致 question 流程功能崩溃。

### 3.4 是否需要 `@JsonAlias("requestId")` 兼容期？

| 选项 | 优点 | 缺点 |
|---|---|---|
| **一刀切（推荐）** | 代码干净，无技术债 | 灰度期间老客户端失去快路径（功能 OK，只是慢/多一次 GET 调用） |
| **加 `@JsonAlias("requestId")` 兼容期 + 同时写两个 JSON key** | 灰度无快路径退化 | 双字段维护、代码混乱、后续清理债务 |

**建议**：**一刀切**。理由：
1. 快路径退化是性能优化层面，不是正确性层面；
2. miniapp 是 Taro app，前端版本灰度通常 1-2 天即覆盖；
3. plugin 升级与 SS 通常一起发；
4. 兼容期代码会成为后续 PR 的清理债务。

如果你团队不能接受 1-2 天 personal scope 快路径退化，**最小兼容方案**只需：在 SS Controller `SendMessageRequest` DTO 里加 `@JsonAlias({"requestId"})`，**单向兼容老 miniapp 上行**即可；下行方向（SS→miniapp 顶层 JSON key）改成 `questionId` 后，老 miniapp 读不到 → fallback，安全。

---

## 4. URL 路径里的 requestId 提及（**全部保留**）

opencode 上游 SDK 接口 URL 模板 `POST /question/{requestID}/reply`，其 path 占位符名 `requestID` 是 opencode SDK 对外契约，**本仓库一律不动**。

完整命中列表（仅核对，**不在 PR2 改动范围**）：
- `skill-server/.../StreamMessage.java:140`（注释里提到 URL）
- `skill-server/.../SkillMessageController.java:571`（注释）
- `plugins/.../OpencodeSessionGatewayAdapter.ts:368, 442`（SDK 调用）
- `plugins/.../README.md:126`、`docs/product/prd.md:22`、`docs/design/interfaces/end-to-end-message-flow.md:578-579, 617, 1024`
- `plugins/.../tests/unit/runtime-protocol.test.mjs:799`、`opencode-session-gateway-adapter.test.mjs:572, 807, 967`
- `plugins/.../tests/integration/protocol-question.test.mjs:124, 220`、`protocol-directory.test.mjs:394, 592, 1061`
- `documents/protocol/v3/06-end-to-end-flows.md:548-549`
- `documents/protocol/v3/04-plugin-opencode.md:296, 480, 509, 514, 738`

**强约束**：PR2 改完后 `git grep '/question/{requestID}/reply'` 命中数量应**保持不变**。

---

## 5. 数量统计

| 文件 | question 域命中数 | 非 question 域命中数（保留） |
|---|---:|---:|
| `skill-server/src/main/java/.../model/StreamMessage.java` | 3（line 137, 139, 142） | 0 |
| `skill-server/src/main/java/.../service/OpenCodeEventTranslator.java` | 2（line 560, 561） | 1（line 451，permission.replied） |
| `skill-server/src/main/java/.../controller/SkillMessageController.java` | 6（line 232, 233, 234, 235, 570-571, 574） | 0（URL 注释保留） |
| `skill-server/src/test/.../OpenCodeEventTranslatorTest.java` | 6 个断言/builder/@DisplayName | 4（line 595, 618, 640, 657：上游 fixture） |
| `skill-server/src/test/.../SkillMessageControllerTest.java` | 9（3 个 test 块的 @DisplayName + 注释 + assert） | 0 |
| `skill-miniapp/src/protocol/types.ts` | 4（line 84, 85, 134, 135） | 0 |
| `skill-miniapp/src/protocol/StreamAssembler.ts` | 4（line 202, 203, 204；msg.requestId 引用） | 0 |
| `skill-miniapp/src/hooks/useSkillStream.ts` | 4（line 28, 314, 1052, 1070） | 5（line 957-970，`historyRequestRef` 局部变量） |
| `skill-miniapp/src/utils/api.ts` | 4（line 280, 286, 287, 288） | 0 |
| `skill-miniapp/src/app.tsx` | 6（line 105, 110, 111, 115, 116） | 0 |
| `skill-miniapp/src/components/MessageBubble.tsx` | 1（line 15） | 0 |
| `skill-miniapp/src/components/ConversationView.tsx` | 1（line 8） | 0 |
| `skill-miniapp/src/components/SubtaskBlock.tsx` | 1（line 11） | 0 |
| `skill-miniapp/src/components/QuestionCard.tsx` | 3（line 6, 33, 42） | 0 |
| `plugins/.../message-bridge/src/contracts/downstream-messages.ts` | 2（line 56, 100） | 0 |
| `plugins/.../message-bridge/src/protocol/downstream/DownstreamMessageNormalizer.ts` | 7（line 325, 328, 329, 332, 333, 335, 340, 341） | 0 |
| `plugins/.../message-bridge/src/port/SessionScopedActionGatewayPort.ts` | 2（line 36, 39） | 0（URL 注释保留） |
| `plugins/.../message-bridge/src/adapter/OpencodeSessionGatewayAdapter.ts` | 11（line 346, 355-358, 359, 362, 379, 422, 428, 453） | 4（line 368, 369, 442, 443：SDK URL `requestID`） |
| `plugins/.../message-bridge/src/action/QuestionReplyAction.ts` | 5（line 26, 27, 44, 56, 68） | 0 |
| `plugins/.../message-bridge/tests/unit/downstream-message-normalizer.test.mjs` | 9 | 0 |
| `plugins/.../message-bridge/tests/unit/opencode-session-gateway-adapter.test.mjs` | ~9（result data + payload 入参） | 7（SDK URL 模板） |
| `plugins/.../message-bridge/tests/unit/actions-coverage.test.mjs` | 3（line 479, 489, 504：mock result data） | 0 |
| `plugins/.../message-bridge/tests/unit/example.test.mjs` | 1（line 16） | 0 |
| `plugins/.../message-bridge/tests/unit/runtime-protocol.test.mjs` | 0 | 2（line 799-800：SDK URL） |
| `plugins/.../message-bridge/tests/integration/protocol-question.test.mjs` | 0 | 4（SDK URL） |
| `plugins/.../message-bridge/tests/integration/protocol-directory.test.mjs` | 0 | 6（SDK URL） |
| `plugins/.../message-bridge/tests/fixtures/opencode-events/permission.replied.json` | 0 | 1（line 5：上游 fixture） |
| `plugins/.../message-bridge/docs/**` | 0 | 5（URL / 描述列） |
| `plugins/.../message-bridge-openclaw/src/runtime/QuestionRegistry.ts` | 10+（建议跟随重命名） | 0 |
| `plugins/.../message-bridge-openclaw/src/runtime/InteractionPorts.ts` | 1（line 17） | 0 |
| `plugins/.../message-bridge-openclaw/src/session/upstreamEvents.ts` | 3（line 66, 281） | 0（`id:` JSON key 是 opencode event schema，保留） |
| `plugins/.../message-bridge-openclaw/src/OpenClawGatewayBridge.ts` | 7（line 1503, 1506, 1997, 1999, 2025, 2035） | 0 |
| `plugins/.../message-bridge-openclaw/tests/unit/session-event-builders.test.mjs` | 1（line 147） | 0 |
| `plugins/.../message-bridge-openclaw/tests/unit/openclaw-gateway-bridge.test.mjs` | 1（line 446） | 0 |
| `documents/protocol/v3/02-skillserver-gateway.md` | ~5 行说明 | 1（URL 注释） |
| `documents/protocol/v3/05-opencode-to-custom-protocol-mapping.md` | 1（line 360 第 3 列） | 2（line 103 + line 360 第 2 列：opencode 列） |
| `documents/protocol/v3/04-plugin-opencode.md` | 1（line 740 result data） | 5（line 296, 480, 509, 514, 738：opencode SDK 示例） |
| `documents/protocol/v3/06-end-to-end-flows.md` | 0 | 2（line 548-549：opencode URL/字段） |
| `documents/protocol/v3/07-message-type-lifecycle.md` | 0（grep 未命中） | 0 |
| `documents/protocol/v1/**`、`v2/**` | 0（历史快照锁定） | 多处保留 |

**汇总**：
- 该改的文件数：**~25 个**（skill-server 主代码 3、skill-server 测试 2、miniapp 9、plugin message-bridge 主代码+测试 9、openclaw 5、v3 协议文档 3）
- 该改的命中数：**~130 处**
- 保留不动的命中数：**~40 处**（绝大部分是 SDK URL 模板 + opencode 上游事件 fixture + v1/v2 历史文档）

---

## 改名安全总结

**结论：全链路改名可以一刀切。**

依据：
1. **唯一的 SS DTO 字段**就是 `QuestionInfo.requestId`（line 142），StreamMessage 顶层 + 其他 InfoDTO 都没有同名字段；`@JsonUnwrapped` 改名带 `@JsonProperty("questionId")` 后顶层 JSON key 同步切换，影响面清晰。
2. **该字段语义为"快路径优化 token"**，缺失/空白自动走 fallback（GET /question 反查）—— 这是 D8 已有降级路径，改名后无论新老客户端组合都不会破坏 question 流程的正确性。
3. **opencode 上游 SDK URL 模板 `/question/{requestID}/reply` 与 path 占位符 `requestID` 一概不动**，所有 SDK 调用层（adapter + 测试）只动局部变量名、不动 URL 模板字符串。
4. **`historyRequestRef` 是 miniapp 局部命名巧合**，与 question 域无任何关联，命中"红线清单 §2.4"。
5. **可选兼容措施**（如果团队对 1-2 天 personal scope 快路径退化敏感）：仅在 `SkillMessageController.SendMessageRequest` DTO 字段 `questionId` 上加 `@JsonAlias({"requestId"})`，单向兼容老 miniapp 上行；下行 SS→miniapp 顶层 JSON key 直接切换为 `questionId`，老 miniapp 自然走 fallback。**这步可选，不加也安全。**

**强约束（开 PR 后 Acceptance 用）**：
- `git grep '"requestId"' skill-server skill-miniapp plugins documents/protocol/v3` 应零结果（除 `useSkillStream.ts` 的 `historyRequestRef` 局部变量上下文）
- `git grep '/question/{requestID}/reply'` 命中数量改名前后**保持不变**
- `git grep 'properties.requestID' skill-server plugins` 命中数量改名前后**保持不变**（opencode 上游事件 fixture）
- 测试全绿 + miniapp typecheck 全绿（plugin 已有 D8 测试断言会一起改）
