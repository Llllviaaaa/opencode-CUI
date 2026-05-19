## Summary

PRD 已收口 `configType/configKey`、null/parse 兜底、personal scope、retry freeze 等核心决策，但仍有 PR1 blocker：first/retry 冻结点和 shared factory/callsite 范围没有写成可执行契约。

工具限制：`mcp__gitnexus` / `mcp__abcoder` 调用返回 `user cancelled`；我改用 GitNexus CLI + 源码/历史校验。ABCoder Java AST 临时生成被本地 JDTLS 安装权限阻塞。

## Issues

### [Critical] R5 的方案 B 会把 freeze 语义和 old-entry 兼容性打穿

- **Where**: PRD `R5` lines 164-170, `AC2` lines 199-200, `AC8` line 206; source `PendingChatRequest.fromSessionFallback` lines 121-144, `SessionRebuildService.rebuildToolSession(String,...)` lines 192-211, `buildPlainTextFallback` lines 559-576.
- **Why**: PRD 建议在 `SessionRebuildService` / `fromSessionFallback` 内部自动 resolve，但 `fromSessionFallback` 不只用于 miniapp first 入 pending，也用于 old/plain Redis entry 的 retry fallback 和 DB rebuild。若在这里统一 resolve，会在 retry 时读取当前 sysconfig，违反“first 入 pending 即时冻结”，也可能让老 entry 意外带上新 key，违反 AC8。
- **Fix**: 明确 freeze 点：只在“新 pending entry 创建时”resolve 并写入 `PendingChatRequest.allowedSlashCommands`。old/plain entry fallback 一律写 `null`。不要把 resolver 放进 record/factory 的通用 fallback；可新增 `fromSessionFallback(..., allowedSlashCommands)` 或在 `SessionRebuildService` legacy append 前显式传入。

### [Critical] PRD 仍未列全共享 symbol 的 d=1/callsite 更新表

- **Where**: PRD `R7` lines 184-187, `R8` lines 191-193, `AC9` line 207.
- **Why**: PRD 把关键 callsite “留给 trellis-implement 用 impact 列全”，但这正是 PRD 应先收口的内容。源码里 main 至少有 12 个 `new InvokeCommand`：`SkillSessionController` 171/252/294, `SkillMessageController` 259/472, `GatewayMessageRouter` 943, `InboundProcessingService` 397/465/533, `ImSessionManager` 163/318, `SessionRebuildService` 162。`PendingChatRequest` 构造点也不只 R5：`PendingChatRequest` 136, `InboundProcessingService` 362, `ImSessionManager` 187, `SessionRebuildService` 205/563/575。
- **Fix**: 在 PRD 加完整 callsite matrix：每个 callsite 标明 action/scope、传 resolver list 还是 `null`。GitNexus CLI 对 `PlatformExtParamBuilder.build` 报 CRITICAL，d=1 为 `GatewayRelayService.buildInvokeMessage`、`GatewayMessageRouter.retryPendingMessages`、`BusinessScopeStrategy.buildInvoke`、`DefaultAssistantScopeStrategy.buildInvoke`，也应写进 PRD。

### [Major] `routeToGateway` 同时处理 chat 和 reply，PRD 未要求 action guard

- **Where**: PRD `R4` line 152; source `SkillMessageController.routeToGateway` lines 222-242 and 258-263, `GatewayRelayService.buildInvokeMessage` lines 212-230.
- **Why**: `routeToGateway` 可能构造 `CHAT`，也可能构造 `QUESTION_REPLY`。如果按 PRD 直接在构造 `InvokeCommand` 前 resolve 并传入，reply payload 也可能带 `allowedSlashCommands`，超出“chat 场景”。
- **Fix**: PRD 明确：仅 `action == GatewayActions.CHAT` 时传 resolver 结果；`QUESTION_REPLY` / `PERMISSION_REPLY` / create/close/abort 一律 `null`，并加测试断言这些 action 不出现 key。

### [Major] IM/External “session 存在但 toolSessionId 缺失” first 路径未显式列入

- **Where**: Research `personal-assistant-chat-entries.md` line 16; source `InboundProcessingService.processChat` lines 178-239; PRD matrix lines 51-57 and R5 lines 157-170.
- **Why**: 这条路径调用 `sessionManager.requestToolSession(session, prompt)` 老 String overload，属于 personal first/rebuild pending 路径。PRD 只显式写 miniapp legacy 和 `createSessionAsync`，容易漏掉 IM/External case B。
- **Fix**: PRD 增加单独入口行：`InboundProcessingService.processChat` case B personal。定义它在 pending append 前 resolve；business self-heal fallback 保持 `null` 或明确 out of scope。

### [Major] `string[]` 校验不够严格

- **Where**: PRD decisions lines 17-19, R1 lines 91-98, Test Plan line 216.
- **Why**: `objectMapper.readValue(json, TypeReference<List<String>>)` 可能受 Jackson coercion 影响，把数字/布尔等非字符串元素转为字符串。用户契约是 `string[]`，当前 PRD 只覆盖“非数组 / parse 失败 / blank”，没有覆盖 `[1,true]` 这类数组。
- **Fix**: 改为 `readTree` 后校验 `isArray()` 且每个元素 `isTextual()`；非文本元素按 invalid config 处理并 WARN，或明确过滤策略。补 AC 和 resolver test。

### [Minor] “动态生效”对 delete/disable 缓存语义写得不完整

- **Where**: PRD Goal line 9, risk line 237; source `SysConfigService.delete` lines 120-128, cache/evict lines 48-83 and 137-145.
- **Why**: `create/update` 会 evict，但 `delete(Long id)` 不知道 type/key，只依赖 TTL。删除配置后旧缓存最多 5 分钟仍可能生效。
- **Fix**: PRD 写清楚动态生效边界：update/create 立即 evict，delete 可能 TTL 延迟；若业务要求删除立即生效，需要改 delete API 或禁用配置走 update。

## Decision

- Critical: 2
- Major: 4
- Minor: 1
- Recommendation: **BLOCK**
- Must-fix before PR1: fix R5 freeze/old-entry semantics, add complete callsite matrix, and action-gate non-chat `InvokeCommand` paths.