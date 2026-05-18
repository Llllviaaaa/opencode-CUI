# Codex PRD Review: personal question_reply requestId

评审对象：`.trellis/tasks/05-18-personal-question-requestid/prd.md`

结论先行：**不建议按当前 PRD 直接进入 PR1 实施**。方案方向可行，SDK 路径和 `EventQuestionAsked.properties.id` 的关键假设基本成立，但 PRD 漏掉 plugin 下行 normalizer，这会让新版链路即使三层都改了也拿不到 `requestId`，快路径不会生效。先补 Critical 后可以开工。

## Critical

1. **plugin `DownstreamMessageNormalizer` 未纳入改动，`requestId` 会在 action 前被丢弃**

   - 定位：PRD Requirements 5-8（`prd.md:100-116`）、AC B（`prd.md:141-146`）只覆盖 contract/action/port/adapter。
   - 代码证据：`plugins/agent-plugin/plugins/message-bridge/src/protocol/downstream/DownstreamMessageNormalizer.ts:284-328` 会把 `payload` 重新构造成 `{ toolSessionId, answer, toolCallId? }`。当前逻辑在 `payload.toolCallId !== undefined` 分支只返回 `toolCallId`（`DownstreamMessageNormalizer.ts:307-321`），否则只返回 `toolSessionId/answer`（`DownstreamMessageNormalizer.ts:324-327`）。
   - 影响：SS 即使把 `requestId` 放进 InvokeCommand，旧/新 plugin runtime 进入 `QuestionReplyAction.execute` 前都会丢掉它；`QuestionReplyAction.ts:39-44` 无法透传，`OpencodeSessionGatewayAdapter.ts:343-427` 的快路径永远无法命中。D6 的“新 miniapp + 新 plugin 走快路径”不成立。
   - 建议：PRD agent-plugin Requirements 增加一条：`normalizeQuestionReplyPayload` 读取并校验 `payload.requestId` 为非空 string，返回值保留 `requestId`；对应补 `downstream-message-normalizer.test.mjs:419-445` 的 requestId 断言，并加一个“旧字段未知容忍/新字段透传”的单测。

## Major

1. **miniapp 改动锚点不足，只改 `normalizeIncomingStreamMessage` 存不住 requestId**

   - 定位：PRD Requirements 10-12（`prd.md:122-131`）。
   - 代码证据：`normalizeIncomingStreamMessage` 只是 spread 原始消息（`skill-miniapp/src/hooks/useSkillStream.ts:322-329`）。真正落到 UI question state 的路径还经过：
     - `StreamMessage` / `MessagePart` 类型，目前都没有 `requestId`（`skill-miniapp/src/protocol/types.ts:58-84`, `types.ts:114-132`）。
     - `StreamAssembler.handleMessage` 的 question 分支只保留 `toolCallId/header/question/options`（`skill-miniapp/src/protocol/StreamAssembler.ts:187-203`）。
     - subagent/history 路径 `streamMessageToSubPart` 也只保留 `toolCallId`（`useSkillStream.ts:304-315`）。
     - 回调链只传 `(answer, toolCallId, subagentSessionId)`：`QuestionCard.tsx:33,42`、`SubtaskBlock.tsx:11,79`、`MessageBubble.tsx:15,66`、`ConversationView.tsx:8`、`app.tsx:104-110`。
     - HTTP 入口只接收 `toolCallId` 并只发 `{content, toolCallId}`（`skill-miniapp/src/hooks/useSkillStream.ts:1050-1070`, `skill-miniapp/src/utils/api.ts:276-284`）。
   - 影响：前端即使收到顶层 `requestId`，也会在 assembler/part/callback 任一环节丢失，最终 POST 给 SS 的 body 不含 `requestId`。
   - 建议：PRD 明确列出完整 miniapp 传播链：`StreamMessage.requestId?`、`MessagePart.requestId?`、`StreamAssembler` question 分支、`streamMessageToSubPart`、必要的 `history.ts` 回放字段、`QuestionCard/SubtaskBlock/MessageBubble/ConversationView/app/useSkillStream/api.sendMessage` 的签名和 body。AC C 增加“组件回调到 `api.sendMessage` 的 body 含 requestId”的测试点。

2. **快路径绕过了现有 session/toolCallId 过滤，需要显式安全/正确性取舍**

   - 定位：PRD Requirements 8（`prd.md:111-116`）。
   - 代码证据：当前 fallback 先 `GET /question`，再按 `sessionID === parameters.sessionId` 过滤（`OpencodeSessionGatewayAdapter.ts:375-379`），有 `toolCallId` 时再按 `tool.callID` 过滤（`OpencodeSessionGatewayAdapter.ts:381-387`）。PRD 的快路径直接 `POST /question/{requestID}/reply`，不再验证 requestId 与 `toolSessionId/toolCallId` 的关联。
   - 影响：正常 UI 链路没问题，但如果 miniapp 请求被篡改或传入陈旧 requestId，plugin 可能回复同 directory 下另一个 pending question。旧路径的 session/toolCallId 绑定保护会消失。
   - 建议：PRD 增加设计取舍：要么明确接受“requestId 是不可猜测 capability token，SS 已鉴权 session，快路径不二次校验”；要么在 plugin 侧维护 `question.asked` 事件观测到的 `{requestId -> sessionID, callID}` 小型 registry，快路径命中前校验 session/callID，不命中再 fallback。至少补一个负向测试或风险说明。

3. **兼容性矩阵少了 SS 版本维度，和“deploy 顺序不敏感”表述不完全匹配**

   - 定位：D5/D6（`prd.md:65-76`）。
   - 代码证据：miniapp 入站由 `SkillMessageController.SendMessageRequest` 反序列化（`SkillMessageController.java:554-564`），当前 DTO 没有 `requestId`。新 SS 会加字段；旧 SS 是否容忍新 miniapp 多发字段依赖 Spring/Jackson 配置，PRD 没验证。
   - 影响：D6 只覆盖 `(miniapp, plugin)`，但 D5 说三份代码 deploy 顺序不敏感。若 miniapp 先于 SS 发布，旧 SS 对未知字段的行为需要明确；若要求 SS 先发，D5 要改成有顺序。
   - 建议：补一个三维兼容说明或至少补 SS 行：旧 SS + 新 miniapp 的未知字段是否被忽略；新 SS + 旧 miniapp payload 无 requestId 是否不塞 null。若不想扩大矩阵，则把 rollout 顺序写成“SS 先于 miniapp，plugin 任意”。

4. **AC B 对“旧 plugin 忽略未知字段”的表述可成立，但要落成 normalizer 级测试**

   - 定位：D6 第三行（`prd.md:75`）、AC B（`prd.md:146`）。
   - 代码证据：当前 JS normalizer 不 reject 未知字段，而是重建白名单 payload（`DownstreamMessageNormalizer.ts:284-328`），所以旧 plugin 收到 `requestId` 不会报错，但也会丢弃它。
   - 影响：兼容性判断是对的，但依赖点不是 JSON parser 本身，而是 normalizer 的白名单重建行为。未来 normalizer 收紧时可能破坏。
   - 建议：把 AC 改成“old normalizer shape ignores unknown fields”，并用 `downstream-message-normalizer.test.mjs` 固化：payload 多一个 `requestId` 仍 ok，归一化输出不含 requestId（旧行为）；新版测试则验证输出包含 requestId。

## Minor

1. **协议文档会漂移，PRD 没列文档更新**

   - 定位：PRD Requirements/AC 未提文档。
   - 代码证据：`docs/superpowers/specs/2026-05-12-miniapp-skill-server-protocol.md:246-259` 和 `:263-267` 仍写 miniapp body/payload 只有 `content/toolCallId/subagentSessionId/businessExtParam`；`docs/superpowers/specs/2026-05-12-gateway-plugin-protocol.md:1005-1018` 仍写 plugin `question_reply` payload 只有 `toolSessionId/answer/toolCallId`；`documents/protocol/v3/02-skillserver-gateway.md:207-214` 同样缺 `requestId`。
   - 建议：PRD 增加 docs 同步为 Minor/AC：miniapp 入站、SS→GW/plugin payload、plugin question_reply 行为表都补 `requestId?` 和 fallback 语义。

2. **`requestId` 空白字符串处理要写清楚**

   - 定位：PRD Requirements 3、8（`prd.md:91-95`, `prd.md:111-116`）。
   - 代码证据：SS 现有 `PayloadBuilder.buildPayloadWithObjects` 会跳过 `null`/`NullNode`，但不会跳过空白字符串（`PayloadBuilder.java:53-68`）。plugin normalizer 当前用 `requireNonEmptyString` 校验 `toolCallId`（`DownstreamMessageNormalizer.ts:307-316`），requestId 也应一致。
   - 建议：SS 只在 `requestId != null && !requestId.isBlank()` 时 `qr.put`；plugin normalizer 对 `requestId` 采用同等 non-empty 校验，空白视为缺失或 invalid 需明确。

3. **历史恢复是否携带 requestId 需要显式接受或覆盖**

   - 定位：PRD Requirements 10-12（`prd.md:122-131`）。
   - 代码证据：历史 normalize 的 `BackendMessagePart` 没有 `requestId`（`skill-miniapp/src/protocol/history.ts:3-35`），question 分支也不输出（`history.ts:185-204`）。SS 持久化 part 模型当前也没有 requestId 字段（`SkillMessagePart.java:34-69`）。
   - 影响：用户刷新页面后再回答旧 pending question 时，新版 miniapp 无法从历史 state 发 requestId，只能靠 toolCallId fallback。功能仍可 work，但不会走快路径。
   - 建议：PRD 明确 MVP 接受“实时 stream 有 requestId，历史恢复走 fallback”，或者把 requestId 放入可持久化字段/`toolInput` 并覆盖历史恢复测试。

## Nit

1. **SDK 关键假设已验证，PRD 可把证据写入 Technical Notes**

   - 证据：`@opencode-ai/sdk@1.2.15` 的 `QuestionRequest` 类型包含 `id: string` 和 `sessionID: string`（`plugins/agent-plugin/plugins/message-bridge/node_modules/@opencode-ai/sdk/dist/v2/gen/types.gen.d.ts:556-563`），`EventQuestionAsked.properties` 就是 `QuestionRequest`（`types.gen.d.ts:568-571`）。`Question.reply` 确认使用 `requestID` path 参数（`sdk.gen.d.ts:648-666`），底层 URL 是 `/question/{requestID}/reply`（`sdk.gen.js:1263-1278`）。
   - 建议：保留 PRD 当前 `requestID` 大写写法；实现时也可考虑调用生成的 `client.question.reply({ requestID, directory, answers })`，但沿用现有 `_client.post` 风格也一致。

2. **代码锚点总体准确，但需要补漏而不是改现有锚点**

   - 已核准：`StreamMessage.QuestionInfo`（`StreamMessage.java:124-133`）、`OpenCodeEventTranslator.translateQuestionAsked`（`OpenCodeEventTranslator.java:521-562`，`partId` 在 `:540`）、`QuestionReplyPayload`（`downstream-messages.ts:47-51`）、port（`SessionScopedActionGatewayPort.ts:32-37`）、adapter（`OpencodeSessionGatewayAdapter.ts:343-427`）都与 PRD 锚点对得上。
   - 需要补充：`DownstreamMessageNormalizer.ts:284-328`、miniapp 的 `StreamAssembler.ts:187-203`、`QuestionCard.tsx:33,42`、`api.ts:276-284`。

## 兼容性矩阵复核

| miniapp | plugin | 复核结论 |
|---|---|---|
| 旧 | 旧 | 可 work：仍按 `toolCallId` 走 `GET /question` + filter + POST。 |
| 旧 | 新 | 可 work：前提是新版 plugin 把 `requestId` 设计为 optional；无 requestId 时走现有 fallback。 |
| 新 | 旧 | 可 work：当前旧 plugin normalizer 会忽略未知字段，不会 reject，但这是 normalizer 行为而非 TS 类型本身。 |
| 新 | 新 | 当前 PRD **不可保证**：若不改 `DownstreamMessageNormalizer` 和 miniapp 传播链，requestId 到不了 adapter；补齐后可 work。 |

## 验收标准可落地性

- SS A 组可落地：`OpenCodeEventTranslatorTest.translatesQuestionAsked` 可补 requestId 断言；`StreamMessageTest` 可补 `@JsonUnwrapped` 顶层序列化；`SkillMessageControllerTest` 可补有/无 requestId 的 Invoke payload 断言。
- plugin B 组可落地，但需要增加 normalizer 单测；adapter 单测可在 `opencode-session-gateway-adapter.test.mjs` 验证 with requestId 时 `getCalls` 为空、`postCalls` 为 1 次。
- miniapp C 组目前 PRD 不够具体；需要覆盖 `StreamAssembler`/component callback/`api.sendMessage`，否则只测 hook normalize 会漏掉真实丢字段。
- D 组可做集测模拟，不需要真旧 plugin：旧 plugin 行为可用“payload 多 requestId 但 normalizer 丢弃未知字段”的测试固化；新 plugin 快路径用 runtime protocol test 验证无 `/question` GET。

## PR 切分

单 PR 三层一起改是合理的：字段是 additive/optional，且目标收益只有三层贯通后才出现。现有仓库也有跨层设计文档把 bridge / SS / miniapp 联动作为同一任务处理。前提是 PRD 先补齐 normalizer、miniapp 传播链和兼容矩阵的 SS 维度；否则“单 PR 小改动”会低估实际改动面。

## 总体结论

**当前 PRD 不应直接进 PR1。** 先修 Critical（plugin normalizer 丢字段）并把 Major 1 的 miniapp 传播链写成明确任务；Major 2/3 至少要有显式设计取舍。完成这些修订后，方案 A 可以进入单 PR 实施。
