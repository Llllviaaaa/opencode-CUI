---
phase: 1
plan: 2
wave: 1
---

# Plan 1.2: 消息格式对齐 + 下行操作实现

## Objective
对齐上行消息格式（确保 envelope/event 分离符合协议规范），实现下行 invoke 命令的完整处理（包括 permission_reply 和 session abort），确保协议层 0 和层 1 的消息格式精确匹配 `full_stack_protocol.md`。

## Context
- .gsd/SPEC.md
- .gsd/phases/1/RESEARCH.md (特别是 §2 Permission API, §3 Session.abort)
- D:\00_Inbox\full_stack_protocol.md (协议规范)
- src/main/pc-agent/ProtocolAdapter.ts
- src/main/pc-agent/EventRelay.ts (Plan 1.1 重构后的版本)
- src/main/pc-agent/types/MessageEnvelope.ts

## Tasks

<task type="auto">
  <name>对齐 ProtocolAdapter 消息格式</name>
  <files>
    src/main/pc-agent/ProtocolAdapter.ts (修改)
    src/main/pc-agent/types/MessageEnvelope.ts (修改)
  </files>
  <action>
    1. 对照 `full_stack_protocol.md` Layer 1 upstream 消息格式，确认 ProtocolAdapter 输出：
       ```json
       {
         "type": "tool_event",
         "sessionId": "42",
         "event": { /* 原始 OpenCode Event, 完全透传 */ },
         "envelope": {
           "version": "0.1",
           "messageId": "uuid",
           "timestamp": "ISO-8601",
           "agentId": "agent-xxx",
           "sequenceNumber": 42
         }
       }
       ```
    2. 确保 `envelope` 是平台协议元数据字段（ADR-005），与 `event` 明确分离
    3. 验证所有上行消息类型的包装：
       - `tool_event` — OpenCode 事件流
       - `tool_done` — 会话空闲时发送
       - `tool_error` — 错误事件
       - `session_created` — 新会话创建
       - `agent_online` / `agent_offline` — HealthChecker 使用
       - `status_response` — 状态查询回复
    4. 更新类型定义 `MessageEnvelope.ts` 中的 TypeScript 类型以精确匹配

    **避免：**
    - 不要把 OpenCode event 解析或修改 — 必须是完全原始透传
    - envelope 字段不在 event 内部，而是和 event 同级
  </action>
  <verify>
    npx tsc --noEmit
    # 手动验证: ProtocolAdapter.wrapEvent() 输出包含正确的 type + sessionId + event + envelope 结构
  </verify>
  <done>
    - ProtocolAdapter 输出格式精确匹配协议规范
    - envelope 和 event 是同级字段
    - 所有 6 种上行消息类型均有对应的 wrap 方法
    - MessageEnvelope.ts 类型定义准确
  </done>
</task>

<task type="auto">
  <name>实现 permission_reply + session.abort 下行操作</name>
  <files>
    src/main/pc-agent/EventRelay.ts (修改 — 下行处理部分)
  </files>
  <action>
    1. 在 EventRelay 的下行消息处理中，添加对以下 invoke 命令的处理：

    **permission_reply:**
    ```ts
    case "permission_reply":
      // 从 invoke 消息提取: sessionId, permissionId, response
      // 映射 response: "allow" → "once", "always" → "always", "deny" → "reject"
      await client.postSessionIdPermissionsPermissionId({
        body: { response: mappedResponse },
        path: { id: sessionId, permissionID: permissionId }
      })
      break
    ```

    2. **session abort (close_session):**
    ```ts
    case "close_session":
      await client.session.abort({
        path: { id: sessionId }
      })
      break
    ```

    3. 确保现有的 invoke 命令处理完整：
       - `chat` → `client.session.prompt()` 或 `client.session.promptAsync()`
       - `create_session` → `client.session.create()`
       - `close_session` → `client.session.abort()` (新增)
       - `permission_reply` → 权限回复 (新增)
       - `status_query` → 回复状态信息

    4. 为每个 invoke 处理添加 try/catch，失败时通过 GatewayConnection 发送 `tool_error`

    **避免：**
    - 不要使用 `"allow"` 直接作为 SDK response — 必须映射为 `"once"`
    - 不要使用 OpenCodeBridge — 使用传入的 `OpencodeClient`（来自 ctx.client）
  </action>
  <verify>
    npx tsc --noEmit
    # 确认 permission_reply 和 close_session 处理逻辑存在
  </verify>
  <done>
    - permission_reply invoke 命令被处理，response 映射正确 (allow→once, deny→reject)
    - close_session invoke 命令调用 session.abort()
    - 所有 5 种 invoke 命令均有处理逻辑
    - 每种操作有 try/catch 错误处理
    - TypeScript 编译通过
  </done>
</task>

## Success Criteria
- [ ] 上行消息格式精确匹配协议 Layer 1 规范 (type + sessionId + event + envelope)
- [ ] 下行 invoke 命令全覆盖: chat, create_session, close_session, permission_reply, status_query
- [ ] Permission response 映射: allow→once, always→always, deny→reject
- [ ] 所有下行操作使用 `OpencodeClient` (ctx.client)
- [ ] `npx tsc --noEmit` 编译通过
