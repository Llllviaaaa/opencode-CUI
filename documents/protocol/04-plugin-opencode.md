# Layer 4：Message Bridge Plugin ↔ OpenCode SDK 协议详解

## 概述

Message Bridge Plugin 通过 OpenCode SDK 与本地运行的 OpenCode CLI 交互。Plugin 注册为 OpenCode 的 hook，接收事件并调用 SDK API 执行操作。

```
┌──────────────┐     Hook 回调        ┌──────────────┐
│  Message     │ ←──────────────────── │  OpenCode    │
│  Bridge      │                      │  CLI         │
│  Plugin      │ ─────────────────── →│  (本地运行)   │
└──────────────┘     SDK API 调用      └──────────────┘
   BridgeRuntime                        OpenCode Process
   SdkAdapter                           REST API server
```

**校验基线（2026-03-23）：**
- **仓库：** `agent-plugin`
- **实现目录：** `plugins/message-bridge`
- **基线提交：** `main@f38ab2062ea6bacb89bec9264a21650b77632155`

---

## 一、OpenCode SDK 事件契约（Plugin 接收）

### 1.1 支持事件矩阵（实现基线）

Plugin 通过 allowlist 精确匹配，仅接收并转发以下 11 种事件：

| 事件类型 | `toolSessionId` 提取路径（Extractor） | 传输层是否字段投影 |
|---|---|---|
| `message.updated` | `properties.info.sessionID` | 是（仅此事件） |
| `message.part.updated` | `properties.part.sessionID` | 否（raw 透传） |
| `message.part.delta` | `properties.sessionID` | 否（raw 透传） |
| `message.part.removed` | `properties.sessionID` | 否（raw 透传） |
| `session.status` | `properties.sessionID` | 否（raw 透传） |
| `session.idle` | `properties.sessionID` | 否（raw 透传） |
| `session.updated` | `properties.info.id` | 否（raw 透传） |
| `session.error` | `properties.sessionID`（可选；缺失会被提取阶段拒绝） | 否（raw 透传） |
| `permission.updated` | `properties.sessionID` | 否（raw 透传） |
| `permission.asked` | `properties.sessionID` | 否（raw 透传） |
| `question.asked` | `properties.sessionID` | 否（raw 透传） |

### 1.2 通用事件外层结构

```typescript
interface OpenCodeEventEnvelope {
  type: string;
  properties: Record<string, unknown>;
}
```

### 1.3 11 类事件全量字段与 JSON 示例

#### 1.3.1 `message.updated`

| 字段路径 | 必填 | 类型 |
|---|---|---|
| `type` | 是 | `"message.updated"` |
| `properties.info` | 是 | `UserMessage \| AssistantMessage` |

**联合类型展开：`Message`（判别键 `properties.info.role`）**

| 字段 | 分支 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `properties.info.id` | user/assistant | `string` | 是 | 消息 ID |
| `properties.info.sessionID` | user/assistant | `string` | 是 | 会话 ID |
| `properties.info.role` | user/assistant | `"user" \| "assistant"` | 是 | 消息角色 |
| `properties.info.time.created` | user/assistant | `number` | 是 | 创建时间 |
| `properties.info.time.completed` | assistant | `number` | 否 | 完成时间 |
| `properties.info.agent` | user | `string` | 是 | 发起 agent |
| `properties.info.model` | user | `{ providerID: string; modelID: string }` | 是 | 用户消息模型 |
| `properties.info.summary` | user | `{ title?; body?; diffs: FileDiff[] }` | 否 | 用户摘要对象 |
| `properties.info.system` | user | `string` | 否 | 系统提示 |
| `properties.info.tools` | user | `Record<string, boolean>` | 否 | 工具开关 |
| `properties.info.parentID` | assistant | `string` | 是 | 父消息 ID |
| `properties.info.modelID` | assistant | `string` | 是 | 模型 ID |
| `properties.info.providerID` | assistant | `string` | 是 | 提供商 ID |
| `properties.info.mode` | assistant | `string` | 是 | 模式 |
| `properties.info.path` | assistant | `{ cwd: string; root: string }` | 是 | 路径对象 |
| `properties.info.summary` | assistant | `boolean` | 否 | assistant 摘要标记 |
| `properties.info.cost` | assistant | `number` | 是 | 成本 |
| `properties.info.tokens` | assistant | `{ input; output; reasoning; cache }` | 是 | token 统计 |
| `properties.info.finish` | assistant | `string` | 否 | 完成原因 |
| `properties.info.error` | assistant | `ProviderAuthError \| UnknownError \| MessageOutputLengthError \| MessageAbortedError \| ApiError` | 否 | 错误联合 |

**联合类型展开：`AssistantMessage.error`（判别键 `properties.info.error.name`）**

| `name` | `data` 关键字段 |
|---|---|
| `"ProviderAuthError"` | `providerID`, `message` |
| `"UnknownError"` | `message` |
| `"MessageOutputLengthError"` | 开放结构（`Record<string, unknown>`） |
| `"MessageAbortedError"` | `message` |
| `"APIError"` | `message`, `statusCode?`, `isRetryable`, `responseHeaders?`, `responseBody?` |

**示例 A（`UserMessage` 全量）：**
```json
{
  "type": "message.updated",
  "properties": {
    "info": {
      "id": "msg-user-001",
      "sessionID": "session-uuid",
      "role": "user",
      "time": { "created": 1710000000000 },
      "summary": {
        "title": "Refactor request",
        "body": "Need to refactor message bridge docs",
        "diffs": [
          {
            "file": "integration/opencode-cui/documents/protocol/04-plugin-opencode.md",
            "before": "old",
            "after": "new",
            "additions": 120,
            "deletions": 36
          }
        ]
      },
      "agent": "general",
      "model": {
        "providerID": "openai",
        "modelID": "gpt-5.4"
      },
      "system": "You are a coding assistant.",
      "tools": {
        "bash": true,
        "read": true
      }
    }
  }
}
```

**示例 B（`AssistantMessage` 全量）：**
```json
{
  "type": "message.updated",
  "properties": {
    "info": {
      "id": "msg-assistant-001",
      "sessionID": "session-uuid",
      "role": "assistant",
      "time": {
        "created": 1710000001000,
        "completed": 1710000009000
      },
      "error": {
        "name": "APIError",
        "data": {
          "message": "upstream timeout",
          "statusCode": 504,
          "isRetryable": true,
          "responseHeaders": { "x-request-id": "req-001" },
          "responseBody": "{\"error\":\"timeout\"}"
        }
      },
      "parentID": "msg-user-001",
      "modelID": "gpt-5.4",
      "providerID": "openai",
      "mode": "default",
      "path": {
        "cwd": "/Users/zy/Code/agent-plugin",
        "root": "/Users/zy/Code/agent-plugin"
      },
      "summary": true,
      "cost": 0.0243,
      "tokens": {
        "input": 1800,
        "output": 920,
        "reasoning": 640,
        "cache": {
          "read": 420,
          "write": 110
        }
      },
      "finish": "stop"
    }
  }
}
```

#### 1.3.2 `message.part.updated`

| 字段路径 | 必填 | 类型 |
|---|---|---|
| `type` | 是 | `"message.part.updated"` |
| `properties.part` | 是 | `Part`（联合类型） |
| `properties.delta` | 否 | `string` |

**`Part` 公共字段（所有成员）**

| 字段 | 必填 | 类型 |
|---|---|---|
| `properties.part.id` | 是 | `string` |
| `properties.part.sessionID` | 是 | `string` |
| `properties.part.messageID` | 是 | `string` |
| `properties.part.type` | 是 | `"text" \| "subtask" \| "reasoning" \| "file" \| "tool" \| "step-start" \| "step-finish" \| "snapshot" \| "patch" \| "agent" \| "retry" \| "compaction"` |

**联合类型展开：`Part`（判别键 `properties.part.type`）**

| `part.type` | 字段级说明 |
|---|---|
| `text` | `text` 必填；`synthetic?`、`ignored?`、`time?`、`metadata?` |
| `subtask` | `prompt`、`description`、`agent` 必填 |
| `reasoning` | `text`、`time.start` 必填；`time.end?`、`metadata?` |
| `file` | `mime`、`url` 必填；`filename?`、`source?` |
| `tool` | `callID`、`tool`、`state` 必填；`metadata?` |
| `step-start` | `snapshot?` |
| `step-finish` | `reason`、`cost`、`tokens` 必填；`snapshot?` |
| `snapshot` | `snapshot` 必填 |
| `patch` | `hash`、`files` 必填 |
| `agent` | `name` 必填；`source?` |
| `retry` | `attempt`、`error`、`time.created` 必填 |
| `compaction` | `auto` 必填 |

`Part` 子类型在当前 `sdk` 版本共 12 种，以下给出逐类全量示例。

**A. TextPart**
```json
{
  "type": "message.part.updated",
  "properties": {
    "part": {
      "id": "part-text-001",
      "sessionID": "session-uuid",
      "messageID": "msg-001",
      "type": "text",
      "text": "Hello bridge",
      "synthetic": false,
      "ignored": false,
      "time": { "start": 1710000001100, "end": 1710000002100 },
      "metadata": { "lang": "zh-CN" }
    },
    "delta": "bridge"
  }
}
```

**B. SubtaskPart**
```json
{
  "type": "message.part.updated",
  "properties": {
    "part": {
      "id": "part-subtask-001",
      "sessionID": "session-uuid",
      "messageID": "msg-001",
      "type": "subtask",
      "prompt": "Review protocol docs",
      "description": "Check message bridge protocol consistency",
      "agent": "reviewer"
    }
  }
}
```

**C. ReasoningPart**
```json
{
  "type": "message.part.updated",
  "properties": {
    "part": {
      "id": "part-reasoning-001",
      "sessionID": "session-uuid",
      "messageID": "msg-001",
      "type": "reasoning",
      "text": "I should compare sdk types and runtime projection.",
      "metadata": { "phase": "analysis" },
      "time": { "start": 1710000001200, "end": 1710000001800 }
    }
  }
}
```

**D. FilePart**
```json
{
  "type": "message.part.updated",
  "properties": {
    "part": {
      "id": "part-file-001",
      "sessionID": "session-uuid",
      "messageID": "msg-001",
      "type": "file",
      "mime": "text/markdown",
      "filename": "04-plugin-opencode.md",
      "url": "/files/04-plugin-opencode.md",
      "source": {
        "type": "symbol",
        "path": "integration/opencode-cui/documents/protocol/04-plugin-opencode.md",
        "name": "message.updated",
        "kind": 12,
        "text": { "value": "message.updated", "start": 0, "end": 15 },
        "range": {
          "start": { "line": 1, "character": 1 },
          "end": { "line": 1, "character": 16 }
        }
      }
    }
  }
}
```

**E. ToolPart（state=completed）**
```json
{
  "type": "message.part.updated",
  "properties": {
    "part": {
      "id": "part-tool-001",
      "sessionID": "session-uuid",
      "messageID": "msg-001",
      "type": "tool",
      "callID": "call-001",
      "tool": "bash",
      "state": {
        "status": "completed",
        "input": { "command": "rg -n message.updated" },
        "output": "matched lines",
        "title": "Search message.updated",
        "metadata": { "exitCode": 0 },
        "time": { "start": 1710000003000, "end": 1710000005000, "compacted": 1710000006000 },
        "attachments": [
          {
            "id": "part-file-attach-001",
            "sessionID": "session-uuid",
            "messageID": "msg-001",
            "type": "file",
            "mime": "text/plain",
            "filename": "stdout.txt",
            "url": "/files/stdout.txt"
          }
        ]
      },
      "metadata": { "channel": "local" }
    }
  }
}
```

**F. StepStartPart**
```json
{
  "type": "message.part.updated",
  "properties": {
    "part": {
      "id": "part-step-start-001",
      "sessionID": "session-uuid",
      "messageID": "msg-001",
      "type": "step-start",
      "snapshot": "snapshot-before-edit"
    }
  }
}
```

**G. StepFinishPart**
```json
{
  "type": "message.part.updated",
  "properties": {
    "part": {
      "id": "part-step-finish-001",
      "sessionID": "session-uuid",
      "messageID": "msg-001",
      "type": "step-finish",
      "reason": "stop",
      "snapshot": "snapshot-after-edit",
      "cost": 0.0125,
      "tokens": {
        "input": 1500,
        "output": 800,
        "reasoning": 500,
        "cache": { "read": 300, "write": 100 }
      }
    }
  }
}
```

**H. SnapshotPart**
```json
{
  "type": "message.part.updated",
  "properties": {
    "part": {
      "id": "part-snapshot-001",
      "sessionID": "session-uuid",
      "messageID": "msg-001",
      "type": "snapshot",
      "snapshot": "git-tree-sha"
    }
  }
}
```

**I. PatchPart**
```json
{
  "type": "message.part.updated",
  "properties": {
    "part": {
      "id": "part-patch-001",
      "sessionID": "session-uuid",
      "messageID": "msg-001",
      "type": "patch",
      "hash": "patch-sha256",
      "files": [
        "integration/opencode-cui/documents/protocol/04-plugin-opencode.md"
      ]
    }
  }
}
```

**J. AgentPart**
```json
{
  "type": "message.part.updated",
  "properties": {
    "part": {
      "id": "part-agent-001",
      "sessionID": "session-uuid",
      "messageID": "msg-001",
      "type": "agent",
      "name": "gpt-5.4-mini",
      "source": { "value": "worker-1", "start": 0, "end": 8 }
    }
  }
}
```

**K. RetryPart**
```json
{
  "type": "message.part.updated",
  "properties": {
    "part": {
      "id": "part-retry-001",
      "sessionID": "session-uuid",
      "messageID": "msg-001",
      "type": "retry",
      "attempt": 2,
      "error": {
        "name": "APIError",
        "data": {
          "message": "rate limited",
          "statusCode": 429,
          "isRetryable": true,
          "responseHeaders": { "retry-after": "1" },
          "responseBody": "{\"error\":\"rate_limit\"}"
        }
      },
      "time": { "created": 1710000007000 }
    }
  }
}
```

**L. CompactionPart**
```json
{
  "type": "message.part.updated",
  "properties": {
    "part": {
      "id": "part-compaction-001",
      "sessionID": "session-uuid",
      "messageID": "msg-001",
      "type": "compaction",
      "auto": true
    }
  }
}
```

#### 1.3.3 `message.part.delta`

| 字段路径 | 必填 | 类型 |
|---|---|---|
| `type` | 是 | `"message.part.delta"` |
| `properties.sessionID` | 是 | `string` |
| `properties.messageID` | 是 | `string` |
| `properties.partID` | 是 | `string` |
| `properties.field` | 是 | `string` |
| `properties.delta` | 是 | `string` |

```json
{
  "type": "message.part.delta",
  "properties": {
    "sessionID": "session-uuid",
    "messageID": "msg-001",
    "partID": "part-text-001",
    "field": "text",
    "delta": " incremental chunk"
  }
}
```

#### 1.3.4 `message.part.removed`

| 字段路径 | 必填 | 类型 |
|---|---|---|
| `type` | 是 | `"message.part.removed"` |
| `properties.sessionID` | 是 | `string` |
| `properties.messageID` | 是 | `string` |
| `properties.partID` | 是 | `string` |

```json
{
  "type": "message.part.removed",
  "properties": {
    "sessionID": "session-uuid",
    "messageID": "msg-001",
    "partID": "part-text-001"
  }
}
```

#### 1.3.5 `session.status`

| 字段路径 | 必填 | 类型 |
|---|---|---|
| `type` | 是 | `"session.status"` |
| `properties.sessionID` | 是 | `string` |
| `properties.status` | 是 | `SessionStatus` |
| `properties.status.type` | 是 | `"idle" \| "busy" \| "retry"` |

**联合类型展开：`SessionStatus`（判别键 `properties.status.type`）**

| `status.type` | 字段级说明 |
|---|---|
| `idle` | 仅 `type` 字段 |
| `busy` | 仅 `type` 字段 |
| `retry` | `type`、`attempt`、`message`、`next` 均必填 |

**busy：**
```json
{
  "type": "session.status",
  "properties": {
    "sessionID": "session-uuid",
    "status": { "type": "busy" }
  }
}
```

**idle：**
```json
{
  "type": "session.status",
  "properties": {
    "sessionID": "session-uuid",
    "status": { "type": "idle" }
  }
}
```

**retry：**
```json
{
  "type": "session.status",
  "properties": {
    "sessionID": "session-uuid",
    "status": {
      "type": "retry",
      "attempt": 3,
      "message": "temporary provider error",
      "next": 1710000010000
    }
  }
}
```

#### 1.3.6 `session.idle`

| 字段路径 | 必填 | 类型 |
|---|---|---|
| `type` | 是 | `"session.idle"` |
| `properties.sessionID` | 是 | `string` |

```json
{
  "type": "session.idle",
  "properties": {
    "sessionID": "session-uuid"
  }
}
```

#### 1.3.7 `session.updated`

| 字段路径 | 必填 | 类型 |
|---|---|---|
| `type` | 是 | `"session.updated"` |
| `properties.info` | 是 | `Session` |
| `properties.info.id` | 是 | `string` |
| `properties.info.projectID` | 是 | `string` |
| `properties.info.directory` | 是 | `string` |
| `properties.info.parentID` | 否 | `string` |
| `properties.info.summary` | 否 | `{ additions; deletions; files; diffs? }` |
| `properties.info.share` | 否 | `{ url: string }` |
| `properties.info.title` | 是 | `string` |
| `properties.info.version` | 是 | `string` |
| `properties.info.time` | 是 | `{ created; updated; compacting? }` |
| `properties.info.revert` | 否 | `{ messageID; partID?; snapshot?; diff? }` |

```json
{
  "type": "session.updated",
  "properties": {
    "info": {
      "id": "session-uuid",
      "projectID": "project-001",
      "directory": "/Users/zy/Code/agent-plugin",
      "parentID": "session-parent-001",
      "summary": {
        "additions": 120,
        "deletions": 36,
        "files": 4,
        "diffs": [
          {
            "file": "integration/opencode-cui/documents/protocol/04-plugin-opencode.md",
            "before": "old",
            "after": "new",
            "additions": 120,
            "deletions": 36
          }
        ]
      },
      "share": { "url": "https://share.example/session-uuid" },
      "title": "Message bridge protocol review",
      "version": "1",
      "time": {
        "created": 1710000000000,
        "updated": 1710000010000,
        "compacting": 1710000015000
      },
      "revert": {
        "messageID": "msg-001",
        "partID": "part-patch-001",
        "snapshot": "snapshot-before-edit",
        "diff": "patch-content"
      }
    }
  }
}
```

#### 1.3.8 `session.error`

> 注意：`properties.sessionID` 与 `properties.error` 在 SDK 类型上均为可选；但在本 Plugin 的提取阶段，`sessionID` 缺失会被拒绝。

| 字段路径 | 必填（SDK） | 类型 |
|---|---|---|
| `type` | 是 | `"session.error"` |
| `properties.sessionID` | 否 | `string` |
| `properties.error` | 否 | `ProviderAuthError \| UnknownError \| MessageOutputLengthError \| MessageAbortedError \| ApiError` |

```json
{
  "type": "session.error",
  "properties": {
    "sessionID": "session-uuid",
    "error": {
      "name": "APIError",
      "data": {
        "message": "context window exceeded",
        "statusCode": 400,
        "isRetryable": false,
        "responseHeaders": { "x-request-id": "req-ctx-001" },
        "responseBody": "{\"error\":\"context_length_exceeded\"}"
      }
    }
  }
}
```

#### 1.3.9 `permission.updated`

| 字段路径 | 必填 | 类型 |
|---|---|---|
| `type` | 是 | `"permission.updated"` |
| `properties.id` | 是 | `string` |
| `properties.type` | 是 | `string` |
| `properties.pattern` | 否 | `string \| string[]` |
| `properties.sessionID` | 是 | `string` |
| `properties.messageID` | 是 | `string` |
| `properties.callID` | 否 | `string` |
| `properties.title` | 是 | `string` |
| `properties.metadata` | 是 | `Record<string, unknown>` |
| `properties.time.created` | 是 | `number` |

```json
{
  "type": "permission.updated",
  "properties": {
    "id": "perm-001",
    "type": "write",
    "pattern": [
      "integration/opencode-cui/documents/protocol/*"
    ],
    "sessionID": "session-uuid",
    "messageID": "msg-001",
    "callID": "call-001",
    "title": "Write protocol docs",
    "metadata": {
      "source": "tool_call",
      "risk": "medium"
    },
    "time": { "created": 1710000002000 }
  }
}
```

#### 1.3.10 `permission.asked`

| 字段路径 | 必填 | 类型 |
|---|---|---|
| `type` | 是 | `"permission.asked"` |
| `properties.id` | 是 | `string` |
| `properties.sessionID` | 是 | `string` |
| `properties.permission` | 是 | `string` |
| `properties.patterns` | 是 | `string[]` |
| `properties.metadata` | 是 | `Record<string, unknown>` |
| `properties.always` | 是 | `string[]` |
| `properties.tool` | 否 | `{ messageID: string; callID: string }` |

```json
{
  "type": "permission.asked",
  "properties": {
    "id": "permreq-001",
    "sessionID": "session-uuid",
    "permission": "write",
    "patterns": [
      "integration/opencode-cui/documents/protocol/04-plugin-opencode.md"
    ],
    "metadata": {
      "reason": "doc update",
      "source": "assistant"
    },
    "always": [
      "integration/opencode-cui/documents/protocol/*"
    ],
    "tool": {
      "messageID": "msg-001",
      "callID": "call-001"
    }
  }
}
```

#### 1.3.11 `question.asked`

| 字段路径 | 必填 | 类型 |
|---|---|---|
| `type` | 是 | `"question.asked"` |
| `properties.id` | 是 | `string` |
| `properties.sessionID` | 是 | `string` |
| `properties.questions` | 是 | `QuestionInfo[]` |
| `properties.tool` | 否 | `{ messageID: string; callID: string }` |

**`QuestionInfo` 字段**

| 字段 | 必填 | 类型 |
|---|---|---|
| `question` | 是 | `string` |
| `header` | 是 | `string` |
| `options` | 是 | `QuestionOption[]` |
| `multiple` | 否 | `boolean` |
| `custom` | 否 | `boolean` |

**`QuestionOption` 字段**

| 字段 | 必填 | 类型 |
|---|---|---|
| `label` | 是 | `string` |
| `description` | 是 | `string` |

```json
{
  "type": "question.asked",
  "properties": {
    "id": "question-001",
    "sessionID": "session-uuid",
    "questions": [
      {
        "question": "文档是否切换到全量字段模式？",
        "header": "确认修订",
        "options": [
          { "label": "是", "description": "按全量字段重写" },
          { "label": "否", "description": "保持当前摘要版" }
        ],
        "multiple": false,
        "custom": true
      }
    ],
    "tool": {
      "messageID": "msg-001",
      "callID": "call-q1"
    }
  }
}
```

### 1.4 过滤与投影契约（Raw vs Projected）

#### 1.4.1 投影规则

- `DefaultUpstreamTransportProjector` 中仅当 `eventType === "message.updated"` 才执行投影。
- 其他 10 类事件全部透传 `normalized.raw`。

#### 1.4.2 `message.updated` 的原始与投影对照

**Raw（OpenCode SDK 原始事件）/ Projected（发送 Gateway 的 `tool_event.event`）按联合分支展示：**

**分支 A：`UserMessage` Raw -> Projected（展示 `summary` 子字段裁剪）**

Raw：
```json
{
  "type": "message.updated",
  "properties": {
    "info": {
      "id": "msg-user-001",
      "sessionID": "session-uuid",
      "role": "user",
      "time": { "created": 1710000000000 },
      "summary": {
        "title": "session summary",
        "body": "long body",
        "diffs": [
          {
            "file": "a.ts",
            "before": "old",
            "after": "new",
            "additions": 10,
            "deletions": 3
          }
        ]
      },
      "agent": "general",
      "model": { "providerID": "openai", "modelID": "gpt-5.4" },
      "system": "system prompt",
      "tools": { "bash": true }
    }
  }
}
```

Projected：
```json
{
  "type": "message.updated",
  "properties": {
    "info": {
      "id": "msg-user-001",
      "sessionID": "session-uuid",
      "role": "user",
      "time": { "created": 1710000000000 },
      "model": { "providerID": "openai", "modelID": "gpt-5.4" },
      "summary": {
        "diffs": [
          {
            "file": "a.ts",
            "additions": 10,
            "deletions": 3
          }
        ]
      }
    }
  }
}
```

**分支 B：`AssistantMessage` Raw -> Projected（展示 assistant 元字段裁剪）**

Raw：
```json
{
  "type": "message.updated",
  "properties": {
    "info": {
      "id": "msg-assistant-001",
      "sessionID": "session-uuid",
      "role": "assistant",
      "time": { "created": 1710000001000, "completed": 1710000009000 },
      "parentID": "msg-user-001",
      "modelID": "gpt-5.4",
      "providerID": "openai",
      "mode": "default",
      "path": { "cwd": "/cwd", "root": "/root" },
      "summary": true,
      "cost": 0.0243,
      "tokens": { "input": 1800, "output": 920, "reasoning": 640, "cache": { "read": 420, "write": 110 } },
      "finish": "stop"
    }
  }
}
```

Projected：
```json
{
  "type": "message.updated",
  "properties": {
    "info": {
      "id": "msg-assistant-001",
      "sessionID": "session-uuid",
      "role": "assistant",
      "time": { "created": 1710000001000, "completed": 1710000009000 }
    }
  }
}
```

#### 1.4.3 保留字段 / 过滤字段矩阵

| 路径 | 处理方式 | 说明 |
|---|---|---|
| `properties.info.id` | 保留 | 直接透传 |
| `properties.info.sessionID` | 保留 | 直接透传 |
| `properties.info.role` | 保留 | 直接透传 |
| `properties.info.time` | 保留 | 作为对象整体保留 |
| `properties.info.model` | 保留 | 仅当原始事件存在该对象时保留 |
| `properties.info.summary.diffs[].file/additions/deletions` | 保留 | SDK 类型字段的子字段白名单 |
| `properties.info.summary.additions/deletions/files` | 条件保留 | 类型外输入若携带数值字段，投影会保留 |
| `properties.info.summary.diffs[].status` | 条件保留 | 类型外输入若携带字符串 `status`，投影会保留 |
| `properties.info.summary.title/body` | 过滤 | 投影时移除 |
| `properties.info.summary.diffs[].before/after` | 过滤 | 投影时移除 |
| `properties.info.parentID/modelID/providerID/mode/path/cost/tokens/finish/error/system/tools` | 过滤 | 投影时移除 |

---

## 二、事件提取（UpstreamEventExtractor）

### 2.1 两层提取模型

```
OpenCode 原始事件
  ↓
extractCommon(event):
  eventType = event.type
  toolSessionId = 从 properties 提取（路径因事件类型而异）
  ↓
extractExtra(event, common):
  根据 eventType 提取特定字段
  ↓
NormalizedUpstreamEvent {
  common: { eventType, toolSessionId },
  extra: EventSpecificFields | undefined,
  raw: 原始事件（保留用于 tool_event 转发）
}
```

### 2.2 toolSessionId 提取路径

| 事件类型 | toolSessionId 路径 |
|---------|-------------------|
| `message.updated` | `properties.info.sessionID` |
| `message.part.updated` | `properties.part.sessionID` |
| `message.part.delta` | `properties.sessionID` |
| `message.part.removed` | `properties.sessionID` |
| `session.*` | `properties.sessionID` 或 `properties.info.id` |
| `permission.*` | `properties.sessionID` |
| `question.asked` | `properties.sessionID` |

### 2.3 事件特定字段

| 事件类型 | 额外提取字段 | 路径 |
|---------|------------|------|
| `message.updated` | `messageId, role` | `info.id, info.role` |
| `message.part.updated` | `messageId, partId` | `part.messageID, part.id` |
| `message.part.delta` | `messageId, partId` | `properties.messageID, properties.partID` |
| `message.part.removed` | `messageId, partId` | `properties.messageID, properties.partID` |
| `session.status` | `status` | `properties.status.type` |
| 其他 | — | — |

### 2.4 验证与错误处理

- 必需字段缺失 → `missing_required_field` 错误
- 字段类型错误 → `invalid_field_type` 错误
- 不支持的事件 → `unsupported_event` 错误
- 所有错误包含 `stage`（common/extra）用于定位

---

## 三、OpenCode SDK API 调用（Plugin 发出）

### 3.1 会话管理

#### 创建会话

```typescript
client.session.create({ body: payload })
```

**请求：** POST `/session`
**响应：** `{ sessionId?, id?, data?: { sessionId?, id? }, ... }`

**sessionId 提取优先级：**
1. `response.sessionId`
2. `response.id`
3. `response.data.sessionId`
4. `response.data.id`

#### 关闭会话

```typescript
client.session.delete({ path: { id: toolSessionId } })
```

**请求：** DELETE `/session/{id}`

#### 中止会话

```typescript
client.session.abort({ path: { id: toolSessionId } })
```

**请求：** POST `/session/{id}/abort`

### 3.2 消息发送

```typescript
client.session.prompt({
  path: { id: toolSessionId },
  body: {
    parts: [{ type: 'text', text: messageText }]
  }
})
```

**请求：** POST `/session/{id}/prompt`
**请求体：**
```json
{
  "parts": [
    { "type": "text", "text": "用户消息内容" }
  ]
}
```

**注意：** `prompt()` 是异步的，结果通过事件流返回（message.part.delta, message.updated 等）。

### 3.3 权限回复

```typescript
client.postSessionIdPermissionsPermissionId({
  path: { id: toolSessionId, permissionID: permissionId },
  body: { response: 'once' | 'always' | 'reject' }
})
```

**请求：** POST `/session/{id}/permissions/{permissionId}`
**请求体：**
```json
{
  "response": "once"
}
```

### 3.4 问题回复

**Step 1: 查询待答问题**

```typescript
const response = await client._client.get({ url: '/question' });
const questions = response.data;
```

**返回的问题列表结构：**
```json
[
  {
    "id": "req-001",
    "sessionID": "session-uuid",
    "tool": { "callID": "call-q1" },
    "question": "确认操作？",
    "options": ["是", "否"]
  }
]
```

**Step 2: 匹配目标问题**

```typescript
// 筛选条件
const matched = questions.filter(q => q.sessionID === toolSessionId);

// 若提供了 toolCallId
if (toolCallId) {
  return matched.find(q => q.tool?.callID === toolCallId);
}

// 若未提供 toolCallId，仅在唯一匹配时返回
if (matched.length === 1) return matched[0];
return null;  // 多个匹配 → 无法确定
```

**Step 3: 提交答案**

```typescript
const requestId = matched.id;
client._client.post({
  url: '/question/{requestID}/reply',
  path: { requestID: requestId },
  body: { answers: [[answerText]] }
})
```

**请求：** POST `/question/{requestID}/reply`
**请求体：**
```json
{
  "answers": [["用户的回答文本"]]
}
```

**注意：** `answers` 是二维数组，第一层对应多个问题，第二层对应多个答案选项。

### 3.5 健康检查

```typescript
const result = await hostClient.global.health();
const isOnline = result?.healthy === true;
```

**请求：** GET `/health`（或 SDK 封装的 health endpoint）

### 3.6 Downstream 消息类型（Gateway → Plugin）

Plugin 支持的 downstream 顶层消息类型为：

```typescript
type DownstreamMessage = InvokeMessage | StatusQueryMessage;
// InvokeMessage.type === 'invoke'
// StatusQueryMessage.type === 'status_query'
```

- `invoke`：业务动作（`chat`、`create_session`、`close_session`、`permission_reply`、`abort_session`、`question_reply`）
- `status_query`：独立健康查询消息（非 invoke action）

---

## 四、Plugin 事件处理流程

### 4.1 事件接收到转发

```
OpenCode SDK 事件回调
  ↓
BridgeRuntime.handleEvent(rawEvent)
  ↓
Step 1: 事件提取
  UpstreamEventExtractor.extract(rawEvent)
  → NormalizedUpstreamEvent { common, extra, raw }
  → 提取失败 → 日志记录，忽略
  ↓
Step 2: 状态检查
  connectionState === READY ?
  → 否 → 日志记录，忽略
  ↓
Step 3: Allowlist 检查
  eventType ∈ configuredAllowlist ?
  → 否 → 日志 "event.rejected_allowlist"，忽略
  ↓
Step 4: 生成消息 ID
  bridgeMessageId = randomUUID()
  ↓
Step 5: 构建 tool_event 消息
  {
    type: 'tool_event',
    toolSessionId: common.toolSessionId,
    event: transportEvent  // 默认透传；message.updated 会投影为轻量结构
  }
  ↓
Step 6: 发送到 Gateway
  gatewayConnection.send(toolEventMessage)
  ↓
Step 7: 特殊事件处理
  若 eventType === 'session.idle':
    ToolDoneCompat.handleSessionIdle(toolSessionId)
    → 可能发送 tool_done（见 4.2）
```

### 4.2 ToolDoneCompat 状态机

**问题：** `tool_done` 何时发送？

OpenCode 的 `session.idle` 和 Plugin 的 `chat` Action 完成是两个独立事件，可能以任意顺序到达。ToolDoneCompat 确保 `tool_done` 只发送一次且时机正确。

**内部状态：**
```typescript
pendingPromptSessions: Set<string>                 // 正在执行 chat 的会话
completedSessionsAwaitingIdleDrop: Set<string>     // chat 完成但等待 session.idle 确认的会话
```

**状态转换表：**

```
事件                    条件                           动作
─────────────────────────────────────────────────────────────────────
invoke(chat).start     —                              pending.add(sessionId)

invoke(chat).success   sessionId ∈ pending             pending.delete(sessionId)
                                                      awaiting.add(sessionId)
                                                      → 发送 tool_done (source: invoke_complete)

invoke(chat).fail      sessionId ∈ pending             pending.delete(sessionId)
                                                      → 不发送 tool_done

session.idle           sessionId ∈ pending             → 不发送（chat 还在执行）

session.idle           sessionId ∈ awaiting            awaiting.delete(sessionId)
                                                      → 不发送（已由 invoke_complete 发过）

session.idle           sessionId ∉ pending ∧ awaiting  → 发送 tool_done (source: session_idle)
```

**设计意图：**
- chat 成功完成 → 立即发 `tool_done`（不等 session.idle）
- session.idle 到达时：
  - 如果 chat 还在执行 → 等 chat 完成
  - 如果 chat 已完成 → 不重复发送
  - 如果没有 pending chat → 兜底发送（非 chat 场景）

---

## 五、Plugin Action 执行详解

### 5.1 通用执行模式

```typescript
async execute(payload, context): Promise<ActionResult> {
  // 1. 状态检查
  if (context.connectionState !== 'READY') {
    return failure(stateToErrorCode(state));
  }

  // 2. 日志开始
  logger.info('action.{name}.started', { payload摘要 });

  // 3. SDK 调用
  try {
    const result = await sdkCall(payload);

    // 4. 验证结果
    if (hasError(result)) {
      return failure(errorCode, errorMessage);
    }

    // 5. 成功返回
    logger.info('action.{name}.completed', { latencyMs });
    return success(data);

  } catch (error) {
    // 6. 异常处理
    const mapped = errorMapper(error);
    logger.error('action.{name}.exception', { error, latencyMs });
    return failure(mapped.code, mapped.message);

  } finally {
    logger.debug('action.{name}.finished', { latencyMs });
  }
}
```

### 5.2 各 Action 详细流程

#### ChatAction

```
输入: { toolSessionId, text }
  ↓
client.session.prompt({
  path: { id: toolSessionId },
  body: { parts: [{ type: 'text', text }] }
})
  ↓
成功: ActionSuccess<void>
失败: 错误映射 →
  timeout/timed out → SDK_TIMEOUT
  unreachable/connect/connection → SDK_UNREACHABLE
  not found/session → INVALID_PAYLOAD
  abort/cancelled → INVALID_PAYLOAD
  其他 → SDK_UNREACHABLE
```

#### CreateSessionAction

```
输入: { title? }
  ↓
client.session.create({ body: payload })
  ↓
提取 sessionId:
  response.sessionId → response.id →
  response.data.sessionId → response.data.id
  ↓
成功: ActionSuccess<{ sessionId?, session? }>
失败: SDK_UNREACHABLE / SDK_TIMEOUT
```

#### CloseSessionAction

```
输入: { toolSessionId }
  ↓
client.session.delete({ path: { id: toolSessionId } })
  ↓
成功: ActionSuccess<{ sessionId, closed: true }>
```

#### AbortSessionAction

```
输入: { toolSessionId }
  ↓
client.session.abort({ path: { id: toolSessionId } })
  ↓
成功: ActionSuccess<{ sessionId, aborted: true }>
```

#### PermissionReplyAction

```
输入: { permissionId, toolSessionId, response: 'once'|'always'|'reject' }
  ↓
client.postSessionIdPermissionsPermissionId({
  path: { id: toolSessionId, permissionID: permissionId },
  body: { response }
})
  ↓
成功: ActionSuccess<{ permissionId, response, applied: true }>
```

#### QuestionReplyAction

```
输入: { toolSessionId, answer, toolCallId? }
  ↓
Step 1: GET /question → 获取所有待答问题
Step 2: 筛选 sessionID === toolSessionId
Step 3: 若有 toolCallId → 精确匹配 tool.callID
         若无 toolCallId → 仅唯一匹配时使用
Step 4: 未找到 → INVALID_PAYLOAD
  ↓
POST /question/{requestID}/reply { answers: [[answer]] }
  ↓
成功: ActionSuccess<{ requestId, replied: true }>
```

#### StatusQueryAction

```
输入: (无)
  ↓
hostClient.global.health()
  ↓
healthy === true → { opencodeOnline: true }
其他/异常 → { opencodeOnline: false }
```

---

## 六、Plugin 配置

### 6.1 配置优先级

```
环境变量 (BRIDGE_*) > 项目配置 (.opencode/message-bridge.json) > 用户配置 (~/.config/opencode/message-bridge.json) > 默认值
```

### 6.2 完整配置结构

```json
{
  "enabled": true,
  "debug": false,
  "config_version": 1,
  "gateway": {
    "url": "ws://localhost:8081/ws/agent",
    "channel": "opencode",
    "heartbeatIntervalMs": 30000,
    "reconnect": {
      "baseMs": 1000,
      "maxMs": 30000,
      "exponential": true
    },
    "ping": {
      "intervalMs": 30000
    }
  },
  "sdk": {
    "timeoutMs": 10000
  },
  "auth": {
    "ak": "your-access-key",
    "sk": "your-secret-key"
  },
  "events": {
    "allowlist": [
      "message.updated",
      "message.part.updated",
      "message.part.delta",
      "message.part.removed",
      "session.status",
      "session.idle",
      "session.updated",
      "session.error",
      "permission.updated",
      "permission.asked",
      "question.asked"
    ]
  }
}
```

### 6.3 环境变量映射

| 环境变量 | 配置路径 | 默认值 |
|---------|---------|--------|
| `BRIDGE_ENABLED` | `enabled` | true |
| `BRIDGE_DEBUG` | `debug` | false |
| `BRIDGE_DIRECTORY` | `bridgeDirectory` | — |
| `BRIDGE_CONFIG_VERSION` | `config_version` | 1 |
| `BRIDGE_GATEWAY_URL` | `gateway.url` | ws://localhost:8081/ws/agent |
| `BRIDGE_GATEWAY_CHANNEL` | `gateway.channel` | opencode |
| `BRIDGE_GATEWAY_HEARTBEAT_INTERVAL_MS` | `gateway.heartbeatIntervalMs` | 30000 |
| `BRIDGE_EVENT_HEARTBEAT_INTERVAL_MS` | `gateway.heartbeatIntervalMs` | 30000 |
| `BRIDGE_GATEWAY_RECONNECT_BASE_MS` | `gateway.reconnect.baseMs` | 1000 |
| `BRIDGE_GATEWAY_RECONNECT_MAX_MS` | `gateway.reconnect.maxMs` | 30000 |
| `BRIDGE_GATEWAY_RECONNECT_EXPONENTIAL` | `gateway.reconnect.exponential` | true |
| `BRIDGE_GATEWAY_PING_INTERVAL_MS` | `gateway.ping.intervalMs` | 30000 |
| `BRIDGE_SDK_TIMEOUT_MS` | `sdk.timeoutMs` | 10000 |
| `BRIDGE_AUTH_AK` | `auth.ak` | — |
| `BRIDGE_AUTH_SK` | `auth.sk` | — |
| `BRIDGE_EVENTS_ALLOWLIST` | `events.allowlist` | （逗号分隔） |

---

## 七、日志与追踪

### 7.1 Trace ID 传播

```
每条消息生成唯一标识:
  traceId: 跨服务追踪 (Gateway 消息的 traceId)
  bridgeMessageId: Plugin 内部消息 ID (UUID)
  runtimeTraceId: 运行时级追踪

传播路径:
  tool_event → Gateway → Skill Server (traceId 一致)
  invoke → Plugin → tool_error（traceId 一致）
  invoke(chat) / session.idle(兜底) → tool_done（traceId 一致）
```

### 7.2 关键日志事件

| 日志事件 | 级别 | 说明 |
|---------|------|------|
| `gateway.connect.started` | info | 开始连接 |
| `gateway.register.sent` | info | 发送 register |
| `gateway.register.accepted` | info | 收到 register_ok |
| `gateway.ready` | info | 进入 READY 状态 |
| `gateway.close` | warn | 连接关闭 |
| `gateway.error` | error | 连接错误 |
| `gateway.heartbeat.sent` | debug | 发送心跳 |
| `event.received` | debug | 收到 SDK 事件 |
| `event.rejected_allowlist` | warn | 事件被 allowlist 拦截 |
| `event.forwarding` | info | 开始转发事件 |
| `event.forwarded` | debug | 事件已转发 |
| `runtime.invoke.received` | info | 收到 invoke 命令 |
| `runtime.invoke.completed` | info | invoke 执行完成 |
| `runtime.tool_done.sending` | info | 发送 tool_done |
| `runtime.tool_error.sending` | error | 发送 tool_error |
| `action.{name}.started` | info | Action 开始执行 |
| `action.{name}.completed` | info | Action 执行成功 |
| `action.{name}.exception` | error | Action 执行异常 |

---

## 八、实现对齐矩阵（基线提交）

| 文档修订点 | 代码锚点（`main@f38ab206...`） |
|---|---|
| `tool_done` 仅 chat 成功/idle 兜底 | `plugins/message-bridge/src/runtime/compat/ToolDoneCompat.ts:40` |
| invoke 成功后是否发 `tool_done` 由 decision 决定 | `plugins/message-bridge/src/runtime/BridgeRuntime.ts:519` |
| 支持上游事件类型清单（11 类） | `plugins/message-bridge/src/contracts/upstream-events.ts:17` |
| 上游事件字段提取规则（toolSessionId/extra） | `plugins/message-bridge/src/protocol/upstream/UpstreamEventExtractor.ts:149` |
| `message.updated` 走上游投影 | `plugins/message-bridge/src/runtime/BridgeRuntime.ts:274` |
| 仅 `message.updated` 进入投影，其他事件 raw 透传 | `plugins/message-bridge/src/transport/upstream/DefaultUpstreamTransportProjector.ts:6` |
| `message.updated` 投影实现 | `plugins/message-bridge/src/transport/upstream/MessageUpdatedProjector.ts:45` |
| `create_session` payload 为 `{ title?: string }` | `plugins/message-bridge/src/contracts/downstream-messages.ts:22` |
| Question 请求 ID 从 `id` 读取 | `plugins/message-bridge/src/action/QuestionReplyAction.ts:65` |
| register `toolVersion` 来源（health.version） | `plugins/message-bridge/src/runtime/Startup.ts:72` |
| register 消息装载 `toolVersion` | `plugins/message-bridge/src/runtime/BridgeRuntime.ts:168` |
| `status_query` 为独立消息类型 | `plugins/message-bridge/src/contracts/downstream-messages.ts:1` |
| `status_query -> status_response` 处理路径 | `plugins/message-bridge/src/runtime/BridgeRuntime.ts:372` |
| 环境变量映射权威实现 | `plugins/message-bridge/src/config/ConfigResolver.ts:131` |
| 日志级别（allowlist/forwarding/tool_error） | `plugins/message-bridge/src/runtime/BridgeRuntime.ts:266` |
