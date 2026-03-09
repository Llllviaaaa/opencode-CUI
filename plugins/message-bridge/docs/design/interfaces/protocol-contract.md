# Message-Bridge 协议契约（当前生效）

**Version:** 1.2
**Date:** 2026-03-09
**Status:** Active
**Owner:** message-bridge maintainers  
**Related:** `../../product/prd.md`, `../../architecture/overview.md`

## 1. 范围

- 仅定义 `message-bridge <-> ai-gateway` 边界协议。
- 不定义 Gateway/Skill-Server 内部路由与持久化。

## 2. 消息类型

| 方向 | 类型 |
|---|---|
| 下行 | `invoke`, `status_query` |
| 上行 | `register`, `heartbeat`, `tool_event`, `tool_error`, `session_created`, `status_response` |

## 3. 字段约定（关键）

1. **不再使用 `envelope`**；边界报文全部改为扁平字段。
2. `welinkSessionId`：技能侧会话标识（Gateway/Skill 侧语义）。
3. `toolSessionId`：OpenCode 会话标识（SDK 侧语义）。
4. `status_query`/`status_response` 不携带会话字段。
5. 当前 runtime 不主动发送 `tool_done`；完成态通过 `tool_event` 透传的 `session.idle` / `session.status` 等事件体现。

## 4. 下行协议（Gateway -> 插件）

### 4.1 `invoke`

```json
{
  "type": "invoke",
  "welinkSessionId": "wlk-001",
  "action": "chat",
  "payload": {
    "toolSessionId": "ses_001",
    "text": "继续"
  }
}
```

`action` 取值：

- `chat`
- `create_session`
- `abort_session`
- `close_session`
- `permission_reply`
- `question_reply`

### 4.2 `status_query`

```json
{
  "type": "status_query"
}
```

## 5. 上行协议（插件 -> Gateway）

### 5.1 `tool_event`

```json
{
  "type": "tool_event",
  "toolSessionId": "ses_001",
  "event": { "type": "message.part.updated" }
}
```

### 5.2 `tool_error`

```json
{
  "type": "tool_error",
  "welinkSessionId": "wlk-001",
  "toolSessionId": "ses_001",
  "error": "error message"
}
```

`welinkSessionId` 与 `toolSessionId` 允许按可用性单独出现，但至少应携带一个可路由标识。

### 5.3 `session_created`

```json
{
  "type": "session_created",
  "welinkSessionId": "wlk-001",
  "toolSessionId": "ses_new_001",
  "session": { "id": "ses_new_001" }
}
```

### 5.4 `status_response`

```json
{
  "type": "status_response",
  "opencodeOnline": true
}
```

## 6. Action 与 SDK 映射

| Action | SDK 调用 |
|---|---|
| `chat` | `session.prompt` |
| `create_session` | `session.create`（payload 透传） |
| `abort_session` | `session.abort` |
| `close_session` | `session.delete` |
| `permission_reply` | `postSessionIdPermissionsPermissionId` |
| `question_reply` | `GET /question` + `POST /question/{requestID}/reply` |

## 7. 兼容性声明

- 本文件定义的是当前唯一有效契约。
- 历史 `envelope/sessionId` 形态视为废弃，不再作为兼容输入输出。
