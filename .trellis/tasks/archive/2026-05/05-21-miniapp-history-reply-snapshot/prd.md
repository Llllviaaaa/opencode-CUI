# brainstorm: update miniapp history and reply snapshot

## Goal

更新 MiniApp 的会话切换/重连恢复逻辑：历史消息统一通过新版 cursor 历史接口获取，WebSocket 恢复只消费当前正在生成的回复快照（`streaming.parts`），不再把服务端 `snapshot.messages` 当作主要历史来源，避免历史加载和流式恢复职责重叠。

## What I already know

* 用户已确认：切会话时本身会调用历史记录接口，快照应只表达当前正在流式的内容。
* Skill Server 已有新版历史接口：`GET /api/skill/sessions/{sessionId}/messages/history?size=&beforeSeq=`，返回 `MessageHistoryResult<ProtocolMessageView>`，字段为 `content`、`size`、`hasMore`、`nextBeforeSeq`。
* Skill Server 当前 WebSocket 恢复语义应使用 `StreamMessage.Types.STREAMING = "streaming"`，内容来自 Redis `StreamBufferService` 聚合的当前流式状态。
* `SnapshotService.buildStreamingState(...)` 会把 Redis 中的 in-flight part 通过 `ProtocolMessageMapper.toProtocolStreamingPart(...)` 转为协议层 part（`text` / `thinking` / `tool` / `question` / `permission` / `file`）。
* MiniApp 已有 `api.getMessageHistory(sessionId, size, beforeSeq?)`，但 `useSkillStream` 的 `streaming` 分支仍按旧注释只标记最后一条 assistant 消息为 streaming，没有调用已存在的 `restoreStreamingMessage(...)`。
* MiniApp 仍保留 `snapshot` 分支，会把 `snapshot.messages` 作为完整历史替换当前消息；这与新职责边界不一致。
* `normalizeStreamingPart(...)` 已能把协议层 `text` / `thinking` / `tool` / `question` / `permission` / `file` 转成实时 `StreamMessage`，但 `permission` 当前总是映射为 `permission.ask`，没有处理已回复状态。
* GitNexus 影响分析结果：`useSkillStream`、`normalizeStreamingPart`、`normalizeHistoryMessages`、`streamMessageToSubPart`、`StreamAssembler` 风险均为 LOW；直接影响主要是 `SkillMain` / `App` 渲染链路。

## Assumptions (temporary)

* “新版的回复快照”指 WebSocket `streaming` 消息中的当前回复快照，而不是历史 `snapshot.messages`。
* `streaming.parts` 使用与历史 `ProtocolMessagePart` 接近的协议层 part 形状，MiniApp 需要兼容 `permission.response` / `status` 来恢复已处理的权限卡片。
* 本任务只更新 MiniApp 对历史和流式恢复的消费逻辑；不继续扩大到服务端协议字段改造。

## Open Questions

* 暂无阻塞问题；按以上假设收敛 MVP。

## Requirements (evolving)

* 会话切换时继续通过 `/messages/history` 加载历史消息，而不是依赖 WebSocket `snapshot`。
* WebSocket `streaming` 消息必须恢复当前正在生成的主回复内容、工具/问题/权限卡片、文件 part，以及 subagent 虚拟消息。
* `snapshot` 仅作为旧服务端兼容分支存在，不应清空并覆盖已由新版历史接口加载的消息。
* 权限回复快照需要区分未处理 ask 与已处理 reply，避免恢复后权限卡片重新显示为未处理。
* 保持 `protocol/history.ts` 负责历史归一化，`useSkillStream.ts` 负责 socket lifecycle 与流式状态恢复，组件不直接理解后端原始协议。

## Acceptance Criteria (evolving)

* [x] `useSkillStream` 使用新版 `streaming` 快照恢复当前 in-flight parts，而不是只标记最后一条 assistant 消息。
* [x] 历史加载路径使用 `api.getMessageHistory(...)`，并且与 WebSocket `streaming` 恢复不会互相覆盖。
* [x] `permission` 快照中存在 `response` 或完成态 `status` 时，MiniApp 恢复为已处理权限状态。
* [x] 旧 `snapshot.messages` 到达时不再作为新流程的主数据源覆盖当前消息列表。
* [x] `cd skill-miniapp && npm run typecheck` 通过。
* [x] `cd skill-miniapp && npm run build` 通过。
* [x] `gitnexus_detect_changes(scope="all")` 显示影响范围与预期一致。

## Implementation Notes

* `useSkillStream` 的 `streaming` 分支已改为调用 `restoreStreamingMessage(...)`，由 `streaming.parts` 恢复当前主回复和 subagent 虚拟消息。
* `snapshot.messages` 仍作为旧服务端兼容分支保留，但只 merge 到现有消息列表，不再 `resetStreamingState()` 或覆盖历史接口加载结果。
* `normalizeStreamingPart(...)` 支持协议层 part 的 `input`、`error`、`answered`、`response`、完成态 `status` 等字段，并将已处理 permission 还原为 `permission.reply`。
* `restoreStreamingMessage(...)` 与 subagent 恢复路径使用 part merge，避免新版回复快照覆盖已由历史接口加载的完整消息。
* `StreamAssembler` 的 `permission.reply` 分支保留权限类型、内容和 subagent 信息，避免已处理权限卡片恢复时丢上下文。

## Validation Results

* `npm --prefix "skill-miniapp" run typecheck`：通过。
* `npm --prefix "skill-miniapp" run build`：通过。
* `ReadLints`（`useSkillStream.ts`、`StreamAssembler.ts`）：无诊断。
* `gitnexus_detect_changes(scope="all")`：medium，影响范围主要为 `SkillMain` 的 MiniApp 流式 UI 流程，以及此前 SS WebSocket 初始流式状态改动，符合预期。

## Definition of Done (team quality bar)

* Tests added/updated where existing coverage patterns make them valuable.
* Lint / typecheck / build green for affected MiniApp package.
* Docs/notes updated if behavior changes.
* Rollout/rollback considered if risky.

## Out of Scope (explicit)

* 不改 Skill Server 历史接口契约。
* 不新增 MiniApp 历史分页 UI。
* 不重构 `StreamAssembler` 整体架构。
* 不改 agent-plugin / gateway 协议链路。

## Technical Notes

* 相关 MiniApp 文件：
  * `skill-miniapp/src/hooks/useSkillStream.ts`
  * `skill-miniapp/src/protocol/StreamAssembler.ts`
  * `skill-miniapp/src/protocol/history.ts`
  * `skill-miniapp/src/utils/api.ts`
  * `skill-miniapp/src/protocol/types.ts`
* 相关 Skill Server 文件：
  * `skill-server/src/main/java/com/opencode/cui/skill/controller/SkillMessageController.java`
  * `skill-server/src/main/java/com/opencode/cui/skill/service/SkillMessageService.java`
  * `skill-server/src/main/java/com/opencode/cui/skill/service/SnapshotService.java`
  * `skill-server/src/main/java/com/opencode/cui/skill/service/ProtocolMessageMapper.java`
  * `skill-server/src/main/java/com/opencode/cui/skill/model/MessageHistoryResult.java`
* 新历史接口按 `beforeSeq` 做 cursor 查询，返回窗口按 seq 升序排列；首次加载 `beforeSeq=null` 可走 latest history cache。
* `ProtocolMessageMapper.toProtocolStreamingPart(...)` 会把 `permission.ask` / `permission.reply` 都归一为 `type: "permission"`，通过 `response` / `status` 表达是否已处理。
* 前端质量门禁来自 `.trellis/spec/skill-miniapp/frontend/quality-guidelines.md`：`npm run typecheck`、`npm run build`。
