# fix snapshot resume and session status drift

## Goal

修复 skill-server miniapp stream 快照恢复时的 live-buffer 生命周期漂移：进行中的回复要在 snapshot 中显示 `sessionStatus=busy`，终态回复、abort 后错误、云端 SSE/WS 超时错误等场景要清掉 Redis streaming snapshot，让刷新后的内容以历史会话接口为准，避免 resume 重放已经结束或已经报错的流内消息。

## What I Already Know

- 用户反馈的现象有三类：
  - 发送对话后 agent 已回 `sessionStatus=busy`，但获取 snapshot 的状态还是 `idle`。
  - agent 回复结束后，或者 abort 触发 error / 云端 SSE、WS 超时 error 后，snapshot 状态仍是 `busy`。
  - 终态内容已经进入数据库，查询历史会话接口可以拿到时，resume 不应该再返回 stream 里的快照消息。
- `SnapshotService.buildStreamingState(...)` 只看 `StreamBufferService.isSessionStreaming(sessionId)` 和 `getStreamingParts(sessionId)`，不会读取最新 live event 本身。
- `StreamBufferService.handleSessionStatus(...)` 目前只在 `idle` / `completed` 时清 buffer，没有在 `busy` 时设置 streaming，因此早期 resume 可能显示 `idle`。
- `StreamBufferService.completeAccumulatedPart(...)` 会在 `text.done` / `thinking.done` 后继续 `setSessionStreaming(true)`，依赖后续 `session.status=idle` 清理；如果后续变成 error/timeout 而不是 idle，buffer 会残留。
- `GatewayMessageRouter.handleToolError(...)` 当前会持久化系统错误、投递 `StreamMessage.Types.ERROR` 并 finalize active message，但没有清 miniapp live-buffer。
- GitNexus impact：
  - `StreamBufferService.accumulate` 为 CRITICAL，直接影响 `routeAssistantMessage`、`handleToolDone`、`emitToClientWithBuffer` 等 live replay 入口。
  - `StreamBufferService.handleSessionStatus` 为 HIGH，直接由 `accumulate` 调用。
  - `GatewayMessageRouter.handleToolError` 为 HIGH，直接由 `dispatchLocally` 调用。
  - `GatewayMessageRouter.activateIdleSession` 为 LOW，直接由 `handleToolEvent` 调用。

## Assumptions

- 这次只修 `skill-server` 的 live-buffer / snapshot 状态生命周期，不改 miniapp 协议字段、不改历史会话接口、不改 gateway cloud SSE/WS 超时策略。
- `busy`、`retry` 属于 live 状态，应该让 snapshot 呈现为 `busy`；`idle`、`completed`、`error`、`session.error`、`StreamMessage.Types.ERROR` 属于终态，应该清 live-buffer。
- abort 后如果 GW 返回 `no_active_connection` / timeout 这类 error，前端已收到即时错误即可；后续 resume 不应继续拿到旧的 streaming parts。

## Requirements

- `session.status=busy` 进入 miniapp live path 后，Redis live-buffer 必须标记 session streaming，`SnapshotService.buildStreamingState(...)` 返回 `sessionStatus=busy`。
- `session.status=idle` / `completed` 继续清理 live-buffer，并保持已有 `text.done` 直到 idle 的行为。
- `StreamMessage.Types.ERROR` 和 `StreamMessage.Types.SESSION_ERROR` 进入 miniapp live-buffer path 时，必须清理 session live-buffer。
- `tool_error` 路径即使不经过 `routeAssistantMessage(...)` 的 buffer accumulate，也必须清理对应 session live-buffer。
- 已清理 live-buffer 后，snapshot 的 `parts` 为空且 `sessionStatus=idle`，历史内容由消息历史接口负责。

## Acceptance Criteria

- [ ] `StreamBufferServiceTest` 覆盖 `busy` 设置 streaming、`error/session.error` 清理 streaming、`text.done` 后 error 清理残留 parts。
- [ ] `GatewayRelayServiceTest` 或 `GatewayMessageRouterTest` 覆盖 `tool_error` 会清理 miniapp buffer。
- [ ] `SnapshotServiceTest` 覆盖 buffer streaming 标记为 busy 且无 parts 时 snapshot 仍返回 `sessionStatus=busy`。
- [ ] 相关 Maven 测试切片通过。
- [ ] `gitnexus_detect_changes` 确认影响范围符合预期。

## Out Of Scope

- 不改前端 `useSkillStream` / `StreamAssembler`。
- 不改 ai-gateway cloud SSE/WS timeout 生成逻辑。
- 不改变错误事件是否实时投递给当前 WebSocket 客户端。
- 不改变数据库历史消息 schema 或历史查询接口。

## Technical Notes

- 主要代码候选：
  - `skill-server/src/main/java/com/opencode/cui/skill/service/StreamBufferService.java`
  - `skill-server/src/main/java/com/opencode/cui/skill/service/GatewayMessageRouter.java`
  - `skill-server/src/main/java/com/opencode/cui/skill/service/SnapshotService.java`（可能只补测试）
- 已读规范：
  - `.trellis/spec/skill-server/backend/index.md`
  - `.trellis/spec/skill-server/backend/conventions.md`
  - `.trellis/spec/skill-server/backend/database-guidelines.md`
  - `.trellis/spec/skill-server/backend/type-safety.md`
  - `.trellis/spec/skill-server/backend/error-handling.md`
  - `.trellis/spec/skill-server/backend/logging-guidelines.md`
  - `.trellis/spec/guides/index.md`
  - `.trellis/spec/guides/cross-layer-thinking-guide.md`
- 设计约束：snapshot / streaming state 统一留在 `SkillStreamHandler` + `SnapshotService`，live replay 数据面留在 Redis buffer；MySQL 历史接口负责已完成内容。
