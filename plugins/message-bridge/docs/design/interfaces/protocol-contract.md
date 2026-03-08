# Message-Bridge 协议契约

**Version:** 1.1  
**Date:** 2026-03-07  
**Status:** Draft  
**Owner:** message-bridge maintainers  
**Related:** `../../product/prd.md`, `../../architecture/overview.md`, `../solution-design.md`

## In Scope

- 插件边界的上下行消息类型契约
- Envelope 字段及语义约束
- `permission_reply` 的标准字段定义

## Out of Scope

- Gateway 内部处理逻辑
- Skill-server 的持久化逻辑

## External Dependencies

- `ai-gateway` WebSocket 端点 `/ws/agent`
- `@opencode-ai/sdk` 的 action 与 event 语义

## 消息类型

| 方向 | 类型 |
|---|---|
| 下行 | `invoke`, `status_query` |
| 上行 | `register`, `heartbeat`, `tool_event`, `tool_done`, `tool_error`, `session_created`, `status_response` |

## Envelope 契约

| 字段 | 必填 | 说明 |
|---|---|---|
| `version` | yes | envelope 版本 |
| `source` | yes | 消息来源 |
| `agentId` | yes | 当前 MVP 使用本地生成 agentId |
| `sessionId` | conditional | `status_response` 场景可选 |
| `sequenceNumber` | yes | scope 内单调递增 |
| `sequenceScope` | yes | `session` 或 `global` |

## 插件与 AI-Gateway 报文示例（上下行全量）

以下示例仅覆盖插件与 AI-Gateway 边界消息。`tool_event.event` 为透传字段，本文件不展开 `opencode-server` 原始完整报文。

### 下行消息（Gateway -> 插件）

#### `invoke`（通用外层）

```json
{
  "type": "invoke",
  "action": "chat",
  "payload": {
    "toolSessionId": "tool-001",
    "text": "请总结最近变更"
  },
  "envelope": {
    "version": "1.0",
    "messageId": "msg-0001",
    "timestamp": 1741334400000,
    "source": "message-bridge",
    "agentId": "bridge-a1b2c3d4",
    "sessionId": "sess-001",
    "sequenceNumber": 1,
    "sequenceScope": "session"
  }
}
```

`invoke.payload` 按 `action` 变化，示例如下：

`chat`

```json
{
  "toolSessionId": "tool-001",
  "text": "继续上一条任务"
}
```

`create_session`

```json
{
  "sessionId": "sess-002",
  "metadata": {
    "origin": "im-miniapp"
  }
}
```

`close_session`

```json
{
  "toolSessionId": "tool-001"
}
```

`permission_reply`

```json
{
  "permissionId": "perm-100",
  "toolSessionId": "tool-001",
  "response": "once"
}
```

#### `status_query`

```json
{
  "type": "status_query",
  "sessionId": "sess-001",
  "envelope": {
    "version": "1.0",
    "messageId": "msg-0002",
    "timestamp": 1741334401000,
    "source": "message-bridge",
    "agentId": "bridge-a1b2c3d4",
    "sessionId": "sess-001",
    "sequenceNumber": 2,
    "sequenceScope": "session"
  }
}
```

### 上行消息（插件 -> AI-Gateway）

#### `register`

```json
{
  "type": "register",
  "deviceName": "Local Machine",
  "os": "darwin",
  "toolType": "opencode",
  "toolVersion": "1.2.15"
}
```

#### `heartbeat`

```json
{
  "type": "heartbeat",
  "timestamp": "2026-03-07T10:00:00.000Z"
}
```

#### `tool_event`（通用外层）

```json
{
  "type": "tool_event",
  "sessionId": "sess-001",
  "event": { // opencode原始报文透传
    "type": "message.part.updated",
    "properties": {}
  },
  "envelope": {
    "version": "1.0",
    "messageId": "msg-1001",
    "timestamp": 1741334410000,
    "source": "message-bridge",
    "agentId": "bridge-a1b2c3d4",
    "sessionId": "sess-001",
    "sequenceNumber": 10,
    "sequenceScope": "session"
  }
}
```

#### `tool_done`

```json
{
  "type": "tool_done",
  "sessionId": "sess-001",
  "result": {
    "ok": true
  },
  "envelope": {
    "version": "1.0",
    "messageId": "msg-1002",
    "timestamp": 1741334415000,
    "source": "message-bridge",
    "agentId": "bridge-a1b2c3d4",
    "sessionId": "sess-001",
    "sequenceNumber": 11,
    "sequenceScope": "session"
  }
}
```

#### `tool_error`

```json
{
  "type": "tool_error",
  "sessionId": "sess-001",
  "error": "OpenCode SDK request timeout",
  "envelope": {
    "version": "1.0",
    "messageId": "msg-1003",
    "timestamp": 1741334420000,
    "source": "message-bridge",
    "agentId": "bridge-a1b2c3d4",
    "sessionId": "sess-001",
    "sequenceNumber": 12,
    "sequenceScope": "session"
  }
}
```

#### `session_created`

```json
{
  "type": "session_created",
  "sessionId": "sess-002",
  "envelope": {
    "version": "1.0",
    "messageId": "msg-1004",
    "timestamp": 1741334425000,
    "source": "message-bridge",
    "agentId": "bridge-a1b2c3d4",
    "sessionId": "sess-002",
    "sequenceNumber": 1,
    "sequenceScope": "session"
  }
}
```

#### `status_response`

```json
{
  "type": "status_response",
  "opencodeOnline": true,
  "sessionId": "sess-001",
  "envelope": {
    "version": "1.0",
    "messageId": "msg-1005",
    "timestamp": 1741334430000,
    "source": "message-bridge",
    "agentId": "bridge-a1b2c3d4",
    "sessionId": "sess-001",
    "sequenceNumber": 13,
    "sequenceScope": "session"
  }
}
```

`status_response.sessionId` 为可选字段；未绑定会话时可省略。
