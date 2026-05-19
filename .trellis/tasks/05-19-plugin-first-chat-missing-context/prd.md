# plugin 首次对话 chat 事件缺少上下文字段（assistantAccount / sendUserAccount / imGroupId）

## Goal

修复 personal 助手在 IM 单聊 / 群聊 **首次对话**场景下，`GatewayMessageRouter.retryPendingMessages` 发出的 chat invoke 报文缺失 `assistantAccount` / `sendUserAccount` / `imGroupId` / `messageId` / `businessExtParam` 的问题。**目标是 wire-layer 报文协议一致性**（首对话与后续对话报文形状相同 → 日志可观测 + 未来扩展安全）。

> ⚠️ **修复目标澄清**：plugin (OpenCode message-bridge) 当前 `normalizeChatPayload` 与 `ChatUseCase` **只消费** `{toolSessionId, text, assistantId}`，其他字段会被 normalize 时丢弃 → 本任务**不**改 plugin 端 ChatPayload 契约 / normalizer。这些字段的"真消费者"是 `BusinessScopeStrategy.buildInvoke`（反向 extract 构建云端协议请求），但 business 助手不走 retry 路径。所以补齐字段的实际收益是 **(a)** 与 `dispatchChatToGateway` 形状一致便于排查；**(b)** 防止未来 plugin / 业务方升级开始消费这些字段时再回到本 bug。

## What I already know（已通过代码确认）

1. **触发路径**：IM 单聊 / 群聊 **首次**到达 `InboundProcessingService.processChat`（`skill-server/src/main/java/com/opencode/cui/skill/service/InboundProcessingService.java`）时，因 `findSession` 返回 null，进入"情况 A"分支（line 163-175），调用 `ImSessionManager.createSessionAsync` 后**直接 return**，不会走 `dispatchChatToGateway`。

2. **个人助手（personal scope）异步链路**：`ImSessionManager.createSessionAsync` 在个人助手分支（`ImSessionManager.java` line 171-174）下：
   - 调 `requestToolSession(created, pendingMessage)` → `GatewayRelayService.rebuildToolSession`
   - `SessionRebuildService.rebuildToolSession`（`SessionRebuildService.java` line 86-141）：
     - 通过 `appendPendingMessage(sessionId, pendingMessage)` **只缓存了纯文本 prompt**（Redis List `ss:pending-rebuild:{sessionId}`）
     - 向 Gateway 发 `create_session` invoke，等 Gateway 回 `session_created`

3. **真正的 bug 位置**：`GatewayMessageRouter.retryPendingMessages`（`skill-server/src/main/java/com/opencode/cui/skill/service/GatewayMessageRouter.java` line 852-897）。Gateway 回 `session_created` → 触发 `retryPendingMessages` → 从 Redis 消费 pending text → 构建 chat payload。**但这里只塞了 `text` + `toolSessionId`**：

   ```java
   ObjectNode chatPayload = objectMapper.createObjectNode();
   chatPayload.put("text", pendingText);
   chatPayload.put("toolSessionId", toolSessionId);
   ```

   完全缺：`assistantAccount`、`sendUserAccount`、`imGroupId`、`messageId`、`businessExtParam`。

4. **业务助手（business scope）首次对话不受影响**：`ImSessionManager.createSessionAsync` line 144-170 走"本地预生成 toolSessionId 直接发 chat"路径，payload 字段齐全（包含 assistantAccount/sendUserAccount/imGroupId/messageId/businessExtParam）。

5. **`SkillMessageController.sendMessage`（miniapp）也不中招**：直接走 `routeToGateway`，payload 齐全（line 244-252）。

6. **关键事实 — 群聊 sender 不可从 SkillSession 反查**：`SkillSession.userId = ownerWelinkId`（助手拥有者），不是消息真实发送者。群聊场景必须传"实际发送者 senderUserAccount"，不能用 owner 替代。可从 session 反查的只有：`assistantAccount`、`businessSessionType` (group/direct)、`businessSessionId` (== 群聊 imGroupId)、ownerWelinkId（单聊 sender = owner）。

## 调用链路总览

```
IM 首次消息
  → ImInboundController / ExternalInboundController
  → InboundProcessingService.processChat
    └─ session==null → ImSessionManager.createSessionAsync(..., pendingMessage=prompt, businessExtParam)
       ├─ business scope: 本地预生成 toolSessionId → 立即 chat invoke（payload 齐全）✅
       └─ personal scope: requestToolSession
          └─ SessionRebuildService.rebuildToolSession
             ├─ appendPendingMessage(sessionId, prompt)   // ⚠️ 只存 text，丢失 sender/ext/imGroupId
             └─ 发 create_session invoke → Gateway

Gateway 回 session_created
  → GatewayMessageRouter.handleSessionCreated
    └─ retryPendingMessages
       └─ 构建 chat payload                              // ⚠️ 只有 text + toolSessionId
          → sender.sendInvokeToGateway(InvokeCommand(... CHAT, payload))
             → plugin 收到的 chat 缺字段
```

## Assumptions（已确认）

* 业务期望：首次对话 chat 报文字段需与后续对话**完全一致**（含 messageId / businessExtParam）。
* 缓存 TTL 5 分钟可以接受（与现有 `PENDING_MSG_TTL` 一致）。
* `messageId` 重发时用"消费时间戳"是 OK 的（首次对话本来 messageId 在 ImSessionManager 也是 `System.currentTimeMillis()`，无业务幂等语义）。

## Decision (ADR-lite)

### D1 — 缓存结构：方案 A 完整上下文对象

**Context**：`SessionRebuildService.appendPendingMessage` 当前只缓存纯文本，导致 `retryPendingMessages` 在 personal 助手首次对话 retry 时构建的 chat invoke 缺关键上下文字段。

**Decision**：升级 Redis pending list 的 value 为 JSON 序列化的 `PendingChatRequest` 结构：

```
{ text, assistantAccount, sendUserAccount, imGroupId, messageId, businessExtParam }
```

`retryPendingMessages` 反序列化对象 + 注入新 toolSessionId 即可重建完整 payload。新增模型 `PendingChatRequest` 放在 `skill-server/.../model/`。

**Consequences**：
- ✅ 首次对话 plugin chat 报文字段与后续对话 100% 对齐，含 businessExtParam。
- ✅ 群聊真实 sender 保留，不退化为 owner。
- ⚠️ TTL（5 min）内可能残留老格式 plain-string entry → `consumePendingMessages` 反序列化时 try/catch fallback：JSON 解析失败 → 当作 text，其它字段用 SkillSession 反查 + owner/null 兜底。
- ⚠️ 所有 `appendPendingMessage` caller 需要同步改签名（3 处，编译器会强制提示）。

### D2 — MVP 范围：首次对话 + 情况 C 全量结构化；其他降级路径走 owner+null fallback

**Context**：`appendPendingMessage` 当前共 3 类 caller：
- C1：`ImSessionManager.createSessionAsync` 间接通过 `requestToolSession` → `SessionRebuildService.rebuildToolSession` 缓存 personal 首次对话 prompt — **sender/ext 已知**
- C2：`InboundProcessingService.processChat` 情况 C personal 助手 `appendToPending`（line 332，session 就绪但 toolSession 缺失） — **sender/ext 已知**
- C3：`SessionRebuildService.rebuildFromStoredUserMessage` 从 DB 拉 lastUserMessage 触发的 rebuild + `InboundProcessingService.processChat` business self-heal legacy 重放（line 197-211） — **sender/ext 不可知**

**Decision**：C1 + C2 必须传完整 `PendingChatRequest`（含 sender/ext）。C3 降级：sender 用 `session.userId`（owner），ext = null，retry 时记 WARN 日志，保持老行为不报错。

**Consequences**：
- ✅ 用户报告的首次对话场景彻底修复。
- ✅ 情况 C（toolSessionId 缺失自愈）同一类 bug 顺手覆盖，避免幽灵复现。
- ⚠️ DB rebuild / business legacy 重放仍然丢 sender/ext，但这是历史降级路径（重发的是已经发过一遍的旧消息，业务对 ext 精确性容忍度更高），用 WARN 日志暴露 + 留 follow-up 任务。

## Requirements (locked)

1. **新增模型** `skill-server/src/main/java/com/opencode/cui/skill/model/PendingChatRequest.java`：
   - Record 形式，字段 **6 个**：`{text, assistantAccount, sendUserAccount, imGroupId, messageId, businessExtParam}`。
   - **不含 `assistantId`** — 由 `GatewayRelayService.buildInvokeMessage` 在 CHAT/CREATE_SESSION 时自动注入（采纳 codex Minor m1）。
   - 静态方法 `fromSessionFallback(SkillSession session, String text)` 用于"老格式 plain-string 兼容 + DB rebuild 降级"：
     - `assistantAccount = session.getAssistantAccount()`
     - `sendUserAccount = session.getUserId()`（owner，群聊语义降级）
     - `imGroupId = session.isImGroupSession() ? session.getBusinessSessionId() : null`（**严格用 businessSessionId，不能误用 SkillSession.id**，采纳 codex Major M3）
     - `messageId = String.valueOf(System.currentTimeMillis())`
     - `businessExtParam = null`
   - 类注释明确这是 **Redis pending list 内部传输 DTO**，不是外部 API 契约（采纳 codex Minor m5）。
2. **`SessionRebuildService` 改造**：
   - `appendPendingMessage(sessionId, PendingChatRequest)`：JSON 序列化入 Redis list（保留现有 TTL / key 前缀）。
   - `consumePendingMessages(sessionId) -> List<PendingChatRequest>`：**先 `readTree(JsonNode)`，仅当是 object 且 `text` 是非空 textual 字段时按新格式 deserialize；否则把整个 raw value 当 plain text 走 `fromSessionFallback`**（采纳 codex Major M1 — 防止"用户发了 `{"foo":"bar"}` 文本"被误判为新格式 + text=null）。
   - `peekPendingMessages` 同步升级，老格式 / 新格式两路 fallback。
   - **既有风险显式记录**：`consumePendingMessages = range + delete` 非原子，多实例 / 重复 session_created 回调下可能双发（codex Major M2）。本任务**不**修复，仅在 PRD risk 段标注并补 INFO 级日志，留作 follow-up。
3. **`GatewayMessageRouter.retryPendingMessages` 重写 payload 构建**：
   - 从 `PendingChatRequest` 重建完整 payload `{text, toolSessionId, assistantAccount, sendUserAccount, imGroupId, messageId, businessExtParam}`。
   - **按 list 顺序发送，不按 messageId 排序**（采纳 codex Minor m2 — FIFO 由 list 保证，messageId 仅业务字段）。
   - 保留 suppressReply 逻辑。
4. **`ImSessionManager.createSessionAsync`（personal 分支）+ `InboundProcessingService.processChat` 情况 C**：构造完整 `PendingChatRequest` 调用新 API。
5. **`SessionRebuildService.rebuildFromStoredUserMessage`**：构造 `fromSessionFallback(session, text)` 时记 WARN：`reason=rebuild_from_db, fields_degraded=sender+ext`。**fallback 之前必须校验 `session.getAssistantAccount()` / `session.getUserId()` 非空，缺一即不入队 + 记 ERROR 日志保留 pending 供人工排查**（采纳 codex Major M5 — 防止后续云端 fast-fail 抛 IllegalArgumentException）。
6. **可观测性增强**（采纳 codex Minor m4）：在 `retryPendingMessages` 入口 + `consumePendingMessages` 反序列化处加结构化日志字段：
   - `pending_format=json/plain/fallback_invalid_json`
   - `fields_degraded`（bool）
   - `retry_count`
7. **不影响**：业务助手首次对话分支、miniapp `SkillMessageController.sendMessage`、permission_reply / question_reply、plugin 端 `ChatPayload` 契约 / normalizer。

## Acceptance Criteria

* [ ] IM 单聊首次消息：retry 路径发出的 chat invoke 报文包含完整 6 字段（imGroupId 为 null 合法）。
* [ ] IM 群聊首次消息：retry 路径发出的 chat 报文 `sendUserAccount` 是**真实发送者**（不是 owner），`imGroupId == 业务群 sessionId`（来自 `session.businessSessionId`，**不能用 `session.id`**）。
* [ ] **老格式兼容**：Redis 里如残留 plain-string entry（含"内容是合法 JSON 但不是新格式"的对抗输入，如 `{"foo":"bar"}`），`consumePendingMessages` 反序列化时识别为 plain text 并走 `fromSessionFallback`，不会把 `text` 设为 null。
* [ ] **fallback 字段非空校验**：`fromSessionFallback` 在 `session.assistantAccount` 或 `session.userId` 为空时不入队，记 ERROR + 保留 pending 供排查（不静默 fast-fail）。
* [ ] 后续对话报文字段保持不变（回归无影响）。
* [ ] 业务助手首次对话字段保持不变（已字段齐全）。
* [ ] suppressReply 逻辑在 retry 路径行为不变（群聊 + channel 白名单命中）。
* [ ] 新增 / 更新单元测试覆盖 `retryPendingMessages` 的字段填充。
* [ ] 现有 `GatewayMessageRouterTest`、`InboundProcessingServiceTest`、`SessionRebuildServiceTest` 全绿。
* [ ] 新增可观测日志字段（`pending_format` / `retry_count` / `fields_degraded`）在测试中可见。

## Definition of Done

* 单元 / 集成测试覆盖首次对话单聊 + 群聊两路径。
* mvn `test` + lint 通过。
* `gitnexus_impact` 对修改的符号跑过，无 HIGH / CRITICAL 未处理。
* `gitnexus_detect_changes` 确认改动 scope 与预期一致。

## Out of Scope

* 业务助手（business scope）路径的字段补齐（已经齐全）。
* miniapp 走 `SkillMessageController.sendMessage` 的路径（不存在该问题）。
* `permission_reply` / `question_reply` 走 retry 路径（目前并未走 retry，permission/question 不进 pending 队列）。
* 重新设计 `rebuild` 状态机或 pending TTL 策略。
* `rebuildFromStoredUserMessage` / business self-heal legacy 重放路径**字段恢复到完整**（本任务采用 owner + null 降级 + WARN，完整恢复留作 follow-up：需在 DB schema 持久化 sender/ext，工作量超出本 bug fix 范围）。
* **plugin (message-bridge) `ChatPayload` / normalizer 扩展**（评审 codex C1 误判 — plugin 当前不消费这些字段，没必要扩契约表面积）。
* **`consumePendingMessages` 原子化**（codex Major M2 — 是既有 bug，本任务仅 PRD risk 段标注 + 加日志可见性；原子化改造（Lua / RENAMENX）独立 follow-up）。
* **首次对话 createLock 并发丢消息**（codex Critical C2 — 是独立根因 bug，**单独开 follow-up 任务跟踪**）：
  - 触发条件：多实例 / 群聊高并发 → 第二个并发请求进 `ImSessionManager.createSessionAsync` 拿不到 `setIfAbsent` 锁 → line 111-114 直接 return，没 append pending、没返回 sessionId → 首轮消息丢失。
  - 修法方向（follow-up）：lock loser 短轮询 `findSession` 拿到 sessionId 后 `appendPendingMessage`；或新增按 `(domain, sessionType, sessionId, ak)` 业务 key 的 **pre-session pending list**，session 创建后迁移到 `skillSessionId` 队列。
  - 涉及文件 `ImSessionManager.java:107-114`、`InboundProcessingService.java:167-175`。

## Implementation Plan（小步推进）

* **PR1**：新增 `PendingChatRequest` 模型 + 单元测试（含 JSON 序列化 / `fromSessionFallback`）。
* **PR2**：`SessionRebuildService` 三个方法签名升级 + 老格式 fallback；同步改 `GatewayMessageRouter.retryPendingMessages` 重建完整 payload。补 `SessionRebuildServiceTest` / `GatewayMessageRouterTest` 用例覆盖：
  - JSON 反序列化成功路径
  - 老格式 plain-string fallback 路径
  - retry payload 字段齐全断言（单聊 + 群聊各一）
* **PR3**：`ImSessionManager.createSessionAsync` + `InboundProcessingService.processChat` 情况 C / business self-heal legacy 重放 + `rebuildFromStoredUserMessage` 改造为构造 `PendingChatRequest` 调用新 API；补 `InboundProcessingServiceTest` / 集成测试覆盖首次对话单聊 + 群聊端到端 invoke 报文字段。

## Technical Notes

### 修复方案对比

| 维度 | 方案 A：完整结构化缓存 | 方案 B：核心三字段缓存 | 方案 C：纯反查 |
|------|---------------------|---------------------|--------------|
| Redis value | JSON：{text, sender, ext, msgId} | JSON：{text, sender} | 仅 text（不变） |
| 群聊真实 sender | ✅ 保留 | ✅ 保留 | ❌ 退化为 owner |
| businessExtParam | ✅ 保留 | ❌ 丢失 | ❌ 丢失 |
| 向后兼容（TTL 内老格式 entry） | 需兼容代码（plain string fallback） | 需兼容 | 0 改造 |
| 改造面 | 中（新增 PendingChatRequest 模型） | 中（同 A 但缺 ext） | 小（retry 内反查） |
| 推荐度 | ⭐⭐⭐（最完整） | ⭐⭐ | ⭐（不完整） |

### 涉及文件（预估）

* `skill-server/src/main/java/com/opencode/cui/skill/service/SessionRebuildService.java`
  * `appendPendingMessage(sessionId, pendingMessage)`：签名变为接收一个上下文对象（含 sender/groupId/ext），序列化后入队。
  * `consumePendingMessages` / `peekPendingMessages`：反序列化 + 老格式兼容（plain string → 兜底 text，其余字段为 null）。
* `skill-server/src/main/java/com/opencode/cui/skill/service/GatewayMessageRouter.java`
  * `retryPendingMessages`：消费结构化对象，构建完整 payload；suppressReply 逻辑保留。
* `skill-server/src/main/java/com/opencode/cui/skill/service/InboundProcessingService.java`
  * 情况 C `appendToPending`、business self-heal legacy 重放路径：同步用新签名调 `appendPendingMessage`。
* `skill-server/src/main/java/com/opencode/cui/skill/service/SessionRebuildService.java`
  * `rebuildFromStoredUserMessage`：从 DB 拉 lastUserMessage 时只有 text，**无 sender/ext**——保留老格式或用 session 反查（owner + business_session_id）。
* 新模型（如选 A/B）：`skill-server/src/main/java/com/opencode/cui/skill/model/PendingChatRequest.java`

### 风险点

1. **TTL 内既有缓存格式不兼容**：升级瞬间 Redis 里残留的纯字符串 entry 会反序列化失败 → `consumePendingMessages` 用 `readTree(JsonNode)` 先判断是否 object + `text` textual 字段，否则把整个 raw 当 plain text 走 `fromSessionFallback`。**陷阱**：用户消息内容本身是合法 JSON（如 `{"foo":"bar"}`）→ 必须严格按 schema 判断不能只靠 `try { mapper.readValue(PendingChatRequest.class) }`。
2. **`rebuildFromStoredUserMessage`**：触发 rebuild 时从 DB 拉历史 user message，**只有 text**，无 sender/ext。本任务走 owner + null 降级 + WARN + 字段非空校验（缺关键 account 不入队，避免后续云端 fast-fail）。
3. **`consumePendingMessages` 非原子（既有 bug，本任务标注不修）**：`range + delete` 多实例 / 重复回调下可能双发。本任务仅加结构化日志 `pending_format` / `retry_count` 暴露问题，**follow-up 任务**用 Lua atomic LRANGE+DEL 或 RENAMENX 修复。
4. **群聊重复发送者识别**：retry 时是异步的，原 sender 可能已离线，下游 plugin 是否仍按 sender 路由？需要查 plugin 行为（暂不阻塞修复，已澄清 plugin normalizer 不消费 sender 字段，所以无影响）。
5. **首次对话 createLock 并发丢消息**（C2 follow-up）：详见 Out of Scope。本任务不修，但要在 follow-up 跟踪。

### 关键代码定位

* 字段被丢失的现场：`skill-server/src/main/java/com/opencode/cui/skill/service/GatewayMessageRouter.java:880-882`
* 正确字段集参考：`skill-server/src/main/java/com/opencode/cui/skill/service/InboundProcessingService.java:335-346`（`dispatchChatToGateway`）
* 业务助手首次对话参考：`skill-server/src/main/java/com/opencode/cui/skill/service/ImSessionManager.java:144-170`
* 缓存入口：`skill-server/src/main/java/com/opencode/cui/skill/service/SessionRebuildService.java:148-157`（`appendPendingMessage`）
