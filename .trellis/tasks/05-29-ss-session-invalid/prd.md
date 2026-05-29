# 收紧 SS session invalid 重建判定

## Goal

SS 收到 Gateway `tool_error` 时，只有明确的 session/toolSession 缺失错误才触发会话重建；`json parse error` 和 `unexpected eof` 不再被当作 session 失效，因为这类解析问题不一定需要新建会话。

## What I Already Know

- 当前判断点在 `GatewayMessageRouter.isSessionInvalidError`。
- `handleToolError` 会在 `reason=session_not_found` 或 `isSessionInvalidError(error)` 命中时调用 `SessionRebuildService.handleSessionNotFound`。
- 用户明确要求移除 `json parse error`、`unexpected eof` 两个重建触发条件。
- GitNexus impact: risk LOW; direct caller is `handleToolError`; affected upstream flow is Gateway message local routing / SS relay routing.

## Requirements

- 保留 `session not found`、`session_not_found`、`toolsession not found` 的重建触发语义。
- 移除 `json parse error`、`unexpected eof` 对 rebuild 的触发。
- 对移除项补回归测试，验证这类错误会走普通错误投递路径而不是 `SessionRebuildService.handleSessionNotFound`。

## Acceptance Criteria

- [ ] `json parse error` 不触发 rebuild。
- [ ] `unexpected eof` 不触发 rebuild。
- [ ] 既有 `session_not_found` 触发 rebuild 的行为保持不变。
- [ ] 相关 `skill-server` 单测通过。

## Out of Scope

- 不改 `SessionRebuildService` 的 create_session / pending retry 机制。
- 不改 GW 错误码或上游错误文本。
- 不调整 agent offline、context overflow 或 IM 入站缺 `toolSessionId` 的重建逻辑。

## Technical Notes

- Specs loaded: `skill-server/backend/index.md`, `directory-structure.md`, `conventions.md`, `error-handling.md`, shared guides index.
- Search before modification: `rg -n "json parse error|unexpected eof|session_not_found|toolsession not found|isSessionInvalidError" skill-server/src/main/java skill-server/src/test/java`.
