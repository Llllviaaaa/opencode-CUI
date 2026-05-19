# Codex Design Review — plugin-first-chat-missing-context

## 你是谁

你是资深 Java / Spring Boot 后端架构师，正在为一份「bug fix + 小重构」PRD 做开工前评审。你的任务不是写代码，是找出 PRD 里**未被覆盖的风险、潜在并发陷阱、向后兼容隐患、协议契约偏差**，并给出 Critical / Major / Minor 分级反馈。过 Critical 才会开 PR1。

## 上下文

仓库：`opencode-CUI`（这是个 IM ⇄ AI 助手中转层；个人助理 plugin 与 ai-gateway 通过 WS 双向通信）。

**Bug 现象**：personal 助理 plugin 在 IM 单聊/群聊**首次对话**场景下，收到的 `chat` invoke 报文缺少 `assistantAccount` / `sendUserAccount` / `imGroupId` / `messageId` / `businessExtParam`，与后续对话报文字段不一致。

**根因（已确认）**：personal 助手首次对话进 `InboundProcessingService.processChat` 情况 A → `ImSessionManager.createSessionAsync` → personal 分支 `requestToolSession` → `SessionRebuildService.rebuildToolSession.appendPendingMessage(sessionId, pendingMessage)` 只把**纯文本**入 Redis list；等 Gateway 回 `session_created` → `GatewayMessageRouter.retryPendingMessages` 反向消费时构建的 chat payload **只塞了 `{text, toolSessionId}`**（line 880-882）。

业务助手（business scope）首次对话不中招（`ImSessionManager.createSessionAsync` line 144-170 走"本地预生成 toolSessionId 直接发 chat"，payload 齐全）。miniapp 走 `SkillMessageController.sendMessage` 也不中招（直接 `routeToGateway`，payload 齐全）。

## 决策（来自 PRD）

### D1：Redis pending list value 由 `String` 升级为 `PendingChatRequest` JSON 对象

```
{ text, assistantAccount, sendUserAccount, imGroupId, messageId, businessExtParam }
```

`SessionRebuildService.{appendPendingMessage, consumePendingMessages, peekPendingMessages}` 签名同步升级；老格式（5min TTL 内残留的 plain string）做 try-parse + 兜底（fallback 为 `PendingChatRequest.fromSessionFallback(session, text)`，sender 用 `session.userId` (owner)、ext = null）。

### D2：MVP 范围

3 类 caller 中 C1（personal 首次对话）+ C2（personal 助手"情况 C" appendToPending）传齐字段；C3（`rebuildFromStoredUserMessage` 从 DB 拉 + business legacy 重放）走 owner + null 降级 + WARN 日志。

## 必读文件

1. `.trellis/tasks/05-19-plugin-first-chat-missing-context/prd.md` — 完整 PRD
2. `skill-server/src/main/java/com/opencode/cui/skill/service/SessionRebuildService.java` — pending list 入口 / rebuild 主流程
3. `skill-server/src/main/java/com/opencode/cui/skill/service/GatewayMessageRouter.java`（重点 line 822-914，handleSessionCreated + retryPendingMessages + computeSuppressReplyForRetry）
4. `skill-server/src/main/java/com/opencode/cui/skill/service/ImSessionManager.java`（重点 line 86-178，createSessionAsync 两条分支）
5. `skill-server/src/main/java/com/opencode/cui/skill/service/InboundProcessingService.java`（重点 line 127-232 + line 320-370 dispatchChatToGateway）
6. `skill-server/src/main/java/com/opencode/cui/skill/model/InvokeCommand.java` — invoke 字段封装
7. `skill-server/src/main/java/com/opencode/cui/skill/model/SkillSession.java` — 可反查字段
8. `plugins/agent-plugin/plugins/message-bridge/src/contracts/downstream-messages.ts` — plugin 端期望的 ChatPayload 契约

## 评审要点

请按下列维度逐条核查，并按 **Critical / Major / Minor** 给反馈：

### 1. 协议契约一致性
- `PendingChatRequest` 6 个字段是否真的就是 plugin chat invoke payload 的**全集**？对比 `dispatchChatToGateway` (line 335-346) 与 plugin `ChatPayload`/`InvokePayloadByAction`。是否漏 / 多了字段？
- `assistantId`（由 `GatewayRelayService.buildInvokeMessage` 在 `CREATE_SESSION`/`CHAT` 时统一注入）会不会和我们 retry 路径塞的字段冲突或重复？

### 2. 向后兼容 / 升级风险
- 升级瞬间 Redis 里残留的纯字符串 entry → JSON parse fail → fallback `fromSessionFallback(session, text)` 是否能正确还原？需要 `session.businessSessionType`（group/direct）反推 `imGroupId`，逻辑是否正确？
- TTL 5 min 是否真的能在合理升级窗口内自然消化？是否需要灰度开关 / 双写？

### 3. 并发与时序
- `consumePendingMessages` 在 Redis list 上是 `range + delete`，并非原子，已有路径多实例下是否有"消费两次"的窗口？升级后影响是否放大？
- `appendPendingMessage` 升级后 JSON 序列化耗时是否会让 Redis 调用显著变慢？群聊高并发场景下值得关心吗？
- `retryPendingMessages` 重发顺序（FIFO）：原来只是 text 顺序 → 现在每条 entry 都有独立 `messageId` (= 入队时间戳)。当 Gateway 在 session_created 后单次拉一批消息时，多个 messageId 重排序顺序的语义是否一致？

### 4. 边界 / 异常路径
- `rebuildFromStoredUserMessage` 走 fallback 路径时 `PendingChatRequest.businessExtParam = null`，下游 `extParameters.businessExtParam` 是否能容忍 null？`BusinessScopeStrategy` / `DefaultCloudRequestStrategy` 是否有 fast-fail 校验？
- 群聊 retry 时如果原始 sender 已离线，plugin 行为？（PRD 已标"暂不阻塞修复"，请确认是否真的不阻塞）
- `imGroupId` 的语义：PRD 假设群聊 `imGroupId == business_session_id`，单聊为 null。检查 `SkillSession` schema / 历史迁移是否真的成立。

### 5. 测试可观测性
- PR2/PR3 的测试用例是否覆盖了：(a) 老格式 fallback、(b) 升级后单聊 retry、(c) 升级后群聊 retry sender 不被 owner 覆盖、(d) suppressReply 在 retry 路径仍然按 channel 白名单算？
- 是否需要新增 metric / log key 来回归观察"首次对话 chat 字段齐全率"？

### 6. 重构边界
- 改动是否泄漏到了不该改的层（`GatewayRelayService.buildInvokeMessage`、`AssistantScopeStrategy`、`PayloadBuilder` 等）？
- 新模型 `PendingChatRequest` 是放 `model/` 还是 `service/` 合适？（如果只有 SessionRebuildService 内部用，可能不该升为顶层 model。）

### 7. 其他「我没想到」的事

请尽情挑刺。

## 输出格式

```markdown
# Codex Design Review Result

## Critical（必须修，否则不开 PR1）
- [C1] <一句话标题>
  - Where: <文件:行 / PRD 段落>
  - Why: <为什么是 Critical>
  - Suggested fix: <怎么改>

## Major（开 PR1 前最好处理，可放 PR2 之前）
- [M1] ...

## Minor（不阻塞，记 follow-up）
- [m1] ...

## 评审结论
- [ ] 过 Critical（可以开 PR1）
- [ ] 不过 Critical（需先回炉 PRD）
```

请认真读 PRD 和必读文件后再写评审。直接给最终评审结论，不要先输出"我打算..."的预演。
