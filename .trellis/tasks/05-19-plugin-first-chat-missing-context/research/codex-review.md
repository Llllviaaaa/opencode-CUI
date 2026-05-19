# Codex Design Review Result

## Critical（必须修，否则不开 PR1）
- [C1] PRD 只改 skill-server 不足以让 plugin 端“收到”新增字段
  - Where: `plugins/agent-plugin/plugins/message-bridge/src/contracts/downstream-messages.ts:17-21`, `DownstreamMessageNormalizer.ts:169-190`, `ChatUseCase.ts:15-18`
  - Why: 当前 `ChatPayload` 只定义 `toolSessionId/text/assistantId`，`normalizeChatPayload` 会重建对象并丢弃 `assistantAccount/sendUserAccount/imGroupId/messageId/businessExtParam`。也就是说，即使 retry payload 补齐，message-bridge 规范化后仍看不到这些字段，PRD 的核心验收无法闭环。
  - Suggested fix: PRD 明确同步更新 plugin 端契约、normalizer 和相关测试；或明确这些字段只给 Gateway/云端策略消费，不要求 message-bridge action 层可见。若目标是 plugin 可见，`ChatPayload` 必须扩展并保留这些字段。

- [C2] 首次会话创建锁竞争会丢消息，结构化 pending 设计没有覆盖 lock loser
  - Where: `ImSessionManager.java:107-114`, `InboundProcessingService.java:167-175`
  - Why: 多实例/群聊并发下，第二条首次消息在 `findSession == null` 后进入 `createSessionAsync`，拿不到 create lock 就直接 return，没有 session id，也没有 append pending。PRD 只修“已入 pending 的字段缺失”，但这个窗口会直接丢首轮消息，是比字段缺失更严重的首次对话风险。
  - Suggested fix: PRD 增加 lock loser 策略：短轮询已创建 session 后 append 完整 `PendingChatRequest`，或新增按业务会话 key 的 pre-session pending list，session 创建后迁移到 skillSessionId 队列再发 `create_session`。

## Major（开 PR1 前最好处理，可放 PR2 之前）
- [M1] 老格式 fallback 不能只靠 JSON parse exception
  - Where: PRD D1；`SessionRebuildService.java:165-170`
  - Why: 用户纯文本可能本身是合法 JSON，例如 `{"foo":"bar"}`。如果直接 `readValue(PendingChatRequest.class)`，可能得到 `text=null` 或非预期对象，而不是把原文作为 plain string fallback，导致消息内容丢失。
  - Suggested fix: 反序列化先读 `JsonNode`，必须是 object 且 `text` 为非空 textual 字段才按新格式处理；否则 whole raw value 作为旧 plain text fallback。

- [M2] `consumePendingMessages = range + delete` 非原子，重复 `session_created` 可双发
  - Where: `SessionRebuildService.java:165-170`, `GatewayMessageRouter.java:822-848`
  - Why: 两个实例或重复回调同时消费同一 Redis list 时，都可能先 `range` 到同一批消息，再分别 `delete`，导致首轮 chat 重放两次。结构化后有 `messageId`，但没有幂等消费或去重。
  - Suggested fix: 用 Lua 原子 `LRANGE + DEL`，或 `RENAMENX` 到 processing key 后消费；至少在 PRD 明确这是既有风险并补回归用例。

- [M3] fallback 的 `imGroupId` 推导需要写死为 `businessSessionId`，不能误用 skill session id
  - Where: `SkillSession.java:67-73`, `SkillSessionService.java:50-67`
  - Why: `SkillSession.id` 是 skill 侧主键，`businessSessionId` 才是 IM 群/单聊业务会话 id。fallback 若用 `sessionId` 参数会把 skill PK 发成 `imGroupId`。
  - Suggested fix: `fromSessionFallback` 明确：`assistantAccount=session.assistantAccount`；`sendUserAccount=session.userId`；`imGroupId=session.isImGroupSession()?session.businessSessionId:null`。

- [M4] 测试计划漏掉 plugin normalizer 层契约测试
  - Where: PRD PR2/PR3 测试计划；`DownstreamMessageNormalizer.ts:169-190`
  - Why: Java 侧 payload 字段齐全不代表 plugin action 层保留字段。当前 TS normalizer 正是会丢字段的地方。
  - Suggested fix: 增加 message-bridge 单测：chat invoke 带 6 个业务字段时，normalized payload 仍保留；ChatPayload 类型也要同步断言。

- [M5] C3 `businessExtParam=null` 本身可容忍，但 account fallback 仍可能 fast-fail
  - Where: `BusinessScopeStrategy.java:93-107`, `DefaultCloudRequestStrategy.java:90-100`
  - Why: `businessExtParam` 缺失会被兜底为 `{}`，不是问题；真正会 fast-fail 的是 `assistantAccount/sendUserAccount`。因此 C3 fallback 必须保证这两个字段非空，否则仍会抛 `IllegalArgumentException`。
  - Suggested fix: fallback 构造时校验并 WARN；缺 `assistantAccount` 或 `session.userId` 时不要静默发 retry，返回错误或保留 pending 供人工排查。

## Minor（不阻塞，记 follow-up）
- [m1] `assistantId` 不应放进 `PendingChatRequest`
  - Where: `GatewayRelayService.java:189-203`
  - Why: `assistantId` 由 `buildInvokeMessage` 对 `create_session/chat` 统一注入。pending payload 再塞会造成覆盖语义不清。
  - Suggested fix: 保持 PRD 当前 6 字段，不新增 `assistantId`；测试断言最终 invoke 仍有自动注入的 `assistantId`。

- [m2] `messageId` 不要承担排序语义
  - Where: `GatewayMessageRouter.java:875-892`
  - Why: FIFO 由 Redis list 顺序保证；毫秒时间戳可能相同。按 messageId 排序会引入重排风险。
  - Suggested fix: retry 按 list 顺序发送；`messageId` 仅作为业务字段和日志关联字段。

- [m3] JSON 序列化开销不是主要风险
  - Where: `SessionRebuildService.java:148-152`
  - Why: 单条 pending 对象很小，相比 Redis/network/WS 开销可忽略。高并发风险主要是锁竞争、非原子消费和重复回放。
  - Suggested fix: 不需要为性能加复杂优化，重点补原子消费和并发测试。

- [m4] 建议加可观测字段
  - Where: PRD 测试可观测性
  - Why: 这类问题很适合上线后看“retry chat 字段齐全率”和 fallback 使用率。
  - Suggested fix: 增加结构化日志或 metric：`pending_format=json/plain/fallback_invalid_json`、`fields_degraded`、`retry_payload_missing_required=false/true`、`retry_count`。

- [m5] `PendingChatRequest` 放 `model/` 可接受，但最好标注为内部传输 DTO
  - Where: PRD Requirements 1
  - Why: 它会被 `SessionRebuildService`、`GatewayMessageRouter`、`ImSessionManager`/`InboundProcessingService` 共享，顶层 model 不算越界；但不是外部 API 契约。
  - Suggested fix: 包名可用 `model`，类注释写明 Redis pending 内部格式和兼容策略。

## 评审结论
- [ ] 过 Critical（可以开 PR1）
- [x] 不过 Critical（需先回炉 PRD）