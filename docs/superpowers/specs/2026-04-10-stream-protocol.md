# Skill Server → MiniApp 流式协议文档

> WebSocket 端点 `/ws/skill/stream`，SS 向 MiniApp 前端推送的所有 StreamMessage 类型完整定义。

---

## 1. 传输格式

- **协议**：WebSocket（`/ws/skill/stream`）
- **认证**：Cookie `userId`
- **序列化**：直接 JSON 序列化 StreamMessage，无外层包装
- **空值处理**：`@JsonInclude(NON_NULL)`，null 字段不出现在 JSON 中
- **嵌套类平铺**：`@JsonUnwrapped` 注解使 ToolInfo、PermissionInfo、QuestionInfo、UsageInfo、FileInfo 的字段平铺到顶层

---

## 2. 公共字段

所有 StreamMessage 共享以下可选字段（按 type 不同，部分字段可能缺失）：

| 字段 | 类型 | 说明 |
|------|------|------|
| type | String | 消息类型（必填） |
| seq | Long | 传输层序列号（Redis INCR 全局递增） |
| welinkSessionId | String | 会话 ID |
| emittedAt | String | ISO 8601 时间戳 |
| messageId | String | 所属消息 ID |
| sourceMessageId | String | 原始消息 ID（通常同 messageId） |
| role | String | `"assistant"` / `"user"` |
| partId | String | Part 唯一标识 |
| partSeq | Integer | Part 在消息中的排序序号 |
| content | String | 文本内容 |
| status | String | 状态 |
| error | String | 错误信息 |
| subagentSessionId | String | 子代理会话 ID（可选） |
| subagentName | String | 子代理名称（可选） |

---

## 3. 消息类型分类

### 3.1 处理层级

| 层级 | 说明 | 类型 |
|------|------|------|
| **Part 级**（StreamAssembler） | 创建/更新消息中的 Part | text.delta, text.done, thinking.delta, thinking.done, tool.update, question, file, permission.ask, permission.reply |
| **会话级**（useSkillStream 直接处理） | 操作会话状态、消息列表 | step.start, step.done, session.status, session.title, session.error, agent.online, agent.offline, error, message.user, snapshot, streaming |
| **云端扩展**（新增） | 云端业务助手特有 | planning.delta, planning.done, searching, search_result, reference, ask_more |

### 3.2 类型总览

| type | 来源 | 说明 |
|------|------|------|
| `text.delta` | 本地 Agent + 云端 | 流式文本增量 |
| `text.done` | 本地 Agent + 云端 | 文本片段完成 |
| `thinking.delta` | 本地 Agent + 云端 | 深度思考增量 |
| `thinking.done` | 本地 Agent + 云端 | 深度思考完成 |
| `tool.update` | 本地 Agent + 云端 | 工具调用状态更新 |
| `question` | 本地 Agent + 云端 | 交互式问答 |
| `file` | 本地 Agent + 云端 | 文件附件 |
| `step.start` | 本地 Agent + 云端 | 步骤开始 |
| `step.done` | 本地 Agent + 云端 | 步骤完成 |
| `session.status` | 本地 Agent + 云端 | 会话状态变更 |
| `session.title` | 本地 Agent + 云端 | 会话标题更新 |
| `session.error` | 本地 Agent + 云端 | 会话级错误 |
| `permission.ask` | 本地 Agent + 云端 | 权限请求 |
| `permission.reply` | 本地 Agent + 云端 | 权限应答 |
| `agent.online` | 仅本地 Agent | Agent 上线 |
| `agent.offline` | 仅本地 Agent | Agent 离线 |
| `error` | SS 内部 | 通用错误 |
| `message.user` | SS 内部 | 多端同步用户消息 |
| `snapshot` | SS 内部 | 连接/重连时历史快照 |
| `streaming` | SS 内部 | 连接/重连时流式状态 |
| `planning.delta` | 仅云端（新增） | 规划内容增量 |
| `planning.done` | 仅云端（新增） | 规划内容完成 |
| `searching` | 仅云端（新增） | 搜索关键词 |
| `search_result` | 仅云端（新增） | 搜索结果 |
| `reference` | 仅云端（新增） | 引用结果 |
| `ask_more` | 仅云端（新增） | 追问建议 |

---

## 4. Part 级消息（经过 StreamAssembler）

### 4.1 `text.delta` —— 流式文本增量

**语义**：assistant 回复正文的一个增量片段，前端追加到对应 Part 的 content 中。

**字段**：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| type | String | ✅ | `"text.delta"` |
| seq | Long | ✅ | 序列号 |
| welinkSessionId | String | ✅ | 会话 ID |
| emittedAt | String | ✅ | 时间戳 |
| messageId | String | ✅ | 消息 ID |
| role | String | ✅ | `"assistant"` |
| partId | String | ✅ | Part ID |
| partSeq | Integer | | Part 排序 |
| content | String | ✅ | 增量文本 |
| subagentSessionId | String | | 子代理会话 ID |
| subagentName | String | | 子代理名称 |

**前端处理**：查找/创建 text part，将 content 追加到 `part.content`，`isStreaming = true`

**示例**：

```json
{
  "type": "text.delta",
  "seq": 42,
  "welinkSessionId": "12345",
  "emittedAt": "2026-04-10T08:30:00Z",
  "messageId": "msg-001",
  "role": "assistant",
  "partId": "part-text-01",
  "partSeq": 0,
  "content": "Hello, here is the "
}
```

---

### 4.2 `text.done` —— 文本片段完成

**语义**：文本 part 流式完成，携带最终完整内容，前端替换 part.content。

**字段**：同 `text.delta`，其中 content 为**完整文本**（非增量）。

**前端处理**：将 `part.content` 替换为 `msg.content`，`isStreaming = false`

**示例**：

```json
{
  "type": "text.done",
  "seq": 43,
  "welinkSessionId": "12345",
  "emittedAt": "2026-04-10T08:30:01Z",
  "messageId": "msg-001",
  "role": "assistant",
  "partId": "part-text-01",
  "partSeq": 0,
  "content": "Hello, here is the full answer text."
}
```

---

### 4.3 `thinking.delta` —— 深度思考增量

**语义**：推理/思考过程的增量推送。

**字段**：同 `text.delta` 结构，content 为思考内容增量。

**前端处理**：创建/查找 `type: 'thinking'` 的 part，content 追加，`isStreaming = true`

**示例**：

```json
{
  "type": "thinking.delta",
  "seq": 40,
  "welinkSessionId": "12345",
  "emittedAt": "2026-04-10T08:29:58Z",
  "messageId": "msg-001",
  "role": "assistant",
  "partId": "part-thinking-01",
  "partSeq": 0,
  "content": "Let me analyze this step by step..."
}
```

---

### 4.4 `thinking.done` —— 深度思考完成

**语义**：思考过程结束，携带完整思考内容。

**字段**：同 `thinking.delta`，content 为完整思考文本。

**前端处理**：将 thinking part 的 content 替换，`isStreaming = false`

**示例**：

```json
{
  "type": "thinking.done",
  "seq": 41,
  "welinkSessionId": "12345",
  "emittedAt": "2026-04-10T08:29:59Z",
  "messageId": "msg-001",
  "role": "assistant",
  "partId": "part-thinking-01",
  "partSeq": 0,
  "content": "Let me analyze this step by step. The user wants to understand..."
}
```

---

### 4.5 `tool.update` —— 工具调用状态更新

**语义**：工具调用的生命周期状态变更（pending → running → completed/error）。

**字段**：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| type | String | ✅ | `"tool.update"` |
| seq | Long | ✅ | 序列号 |
| welinkSessionId | String | ✅ | 会话 ID |
| emittedAt | String | ✅ | 时间戳 |
| messageId | String | ✅ | 消息 ID |
| role | String | ✅ | `"assistant"` |
| partId | String | ✅ | Part ID |
| partSeq | Integer | | Part 排序 |
| status | String | ✅ | `"pending"` / `"running"` / `"completed"` / `"error"` |
| title | String | | 工具调用显示标题 |
| toolName | String | ✅ | 工具名称 |
| toolCallId | String | ✅ | 工具调用 ID |
| input | Object | | 工具输入参数 |
| output | String | | 工具输出结果（completed 时） |
| error | String | | 错误信息（error 时） |
| subagentSessionId | String | | 子代理会话 ID |
| subagentName | String | | 子代理名称 |

**前端处理**：创建 `type: 'tool'` 的 part，映射字段，`isStreaming = (status === 'pending' || status === 'running')`

**示例**：

```json
{
  "type": "tool.update",
  "seq": 44,
  "welinkSessionId": "12345",
  "emittedAt": "2026-04-10T08:30:02Z",
  "messageId": "msg-001",
  "role": "assistant",
  "partId": "part-tool-01",
  "partSeq": 1,
  "status": "completed",
  "title": "Read file",
  "toolName": "Read",
  "toolCallId": "call-abc123",
  "input": {"file_path": "/src/main.ts"},
  "output": "file content here..."
}
```

---

### 4.6 `question` —— 交互式问答

**语义**：Agent 向用户提问，等待回答。

**字段**：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| type | String | ✅ | `"question"` |
| seq, welinkSessionId, emittedAt | - | ✅ | 公共字段 |
| messageId | String | ✅ | 消息 ID |
| role | String | ✅ | `"assistant"` |
| partId | String | ✅ | Part ID |
| partSeq | Integer | | Part 排序 |
| status | String | ✅ | `"running"`（提问中）/ `"completed"` / `"error"` |
| toolName | String | ✅ | `"question"` |
| toolCallId | String | ✅ | 工具调用 ID（用户回答时需回传） |
| input | Object | | 完整 question 参数 |
| output | String | | 用户回答（completed 时） |
| header | String | | 问题标题 |
| question | String | ✅ | 问题内容 |
| options | List\<String\> | | 可选项列表 |
| subagentSessionId | String | | 子代理会话 ID |
| subagentName | String | | 子代理名称 |

**前端处理**：创建 `type: 'question'` 的 part，`isStreaming = false`

**示例**：

```json
{
  "type": "question",
  "seq": 45,
  "welinkSessionId": "12345",
  "emittedAt": "2026-04-10T08:30:03Z",
  "messageId": "msg-001",
  "role": "assistant",
  "partId": "q-part-01",
  "partSeq": 2,
  "status": "running",
  "toolName": "question",
  "toolCallId": "call-q001",
  "header": "Confirm action",
  "question": "Do you want to proceed?",
  "options": ["Yes", "No", "Skip"]
}
```

---

### 4.7 `file` —— 文件附件

**语义**：Agent 输出的文件附件。

**字段**：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| type | String | ✅ | `"file"` |
| seq, welinkSessionId, emittedAt | - | ✅ | 公共字段 |
| messageId | String | ✅ | 消息 ID |
| role | String | ✅ | `"assistant"` |
| partId | String | ✅ | Part ID |
| partSeq | Integer | | Part 排序 |
| fileName | String | ✅ | 文件名 |
| fileUrl | String | ✅ | 文件下载 URL |
| fileMime | String | | MIME 类型 |

**前端处理**：创建 `type: 'file'` 的 part，`isStreaming = false`

**示例**：

```json
{
  "type": "file",
  "seq": 46,
  "welinkSessionId": "12345",
  "emittedAt": "2026-04-10T08:30:04Z",
  "messageId": "msg-001",
  "role": "assistant",
  "partId": "part-file-01",
  "partSeq": 3,
  "fileName": "screenshot.png",
  "fileUrl": "https://storage.example.com/files/screenshot.png",
  "fileMime": "image/png"
}
```

---

### 4.8 `permission.ask` —— 权限请求

**语义**：Agent 请求用户授权某项操作。

**字段**：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| type | String | ✅ | `"permission.ask"` |
| seq, welinkSessionId, emittedAt | - | ✅ | 公共字段 |
| messageId | String | ✅ | 消息 ID |
| status | String | | 权限状态 |
| title | String | ✅ | 权限请求显示标题 |
| permissionId | String | ✅ | 权限请求 ID |
| permType | String | ✅ | 权限类型 |
| metadata | Object | | 额外信息 |
| partId | String | | Part ID |
| subagentSessionId | String | | 子代理会话 ID |
| subagentName | String | | 子代理名称 |

**前端处理**：创建 `type: 'permission'` 的 part，`permResolved = false`，`isStreaming = false`

**示例**：

```json
{
  "type": "permission.ask",
  "seq": 47,
  "welinkSessionId": "12345",
  "emittedAt": "2026-04-10T08:30:05Z",
  "messageId": "msg-001",
  "status": "pending",
  "title": "bash: rm -rf /tmp/build",
  "permissionId": "perm-001",
  "permType": "Bash",
  "metadata": {"command": "rm -rf /tmp/build"}
}
```

---

### 4.9 `permission.reply` —— 权限应答

**语义**：权限请求的处理结果。

**字段**：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| type | String | ✅ | `"permission.reply"` |
| seq, welinkSessionId | - | ✅ | 公共字段 |
| messageId | String | | 消息 ID |
| permissionId | String | ✅ | 对应权限请求 ID |
| permType | String | | 权限类型 |
| response | String | ✅ | `"once"` / `"always"` / `"reject"` |
| status | String | | 状态 |

**前端处理**：查找匹配 permissionId 的 permission part，设置 `permResolved = true`

**示例**：

```json
{
  "type": "permission.reply",
  "seq": 48,
  "welinkSessionId": "12345",
  "messageId": "msg-001",
  "permissionId": "perm-001",
  "response": "once"
}
```

---

## 5. 会话级消息（useSkillStream 直接处理）

### 5.1 `step.start` —— 步骤开始

**语义**：LLM 推理步骤开始。

**字段**：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| type | String | ✅ | `"step.start"` |
| seq, welinkSessionId, emittedAt | - | ✅ | 公共字段 |
| messageId | String | ✅ | 消息 ID |
| role | String | | `"assistant"` |

**前端处理**：将 messageId 加入活跃消息列表，`isStreaming = true`

**示例**：

```json
{
  "type": "step.start",
  "seq": 39,
  "welinkSessionId": "12345",
  "emittedAt": "2026-04-10T08:29:57Z",
  "messageId": "msg-001",
  "role": "assistant"
}
```

---

### 5.2 `step.done` —— 步骤完成

**语义**：LLM 推理步骤完成，携带 token 用量。

**字段**：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| type | String | ✅ | `"step.done"` |
| seq, welinkSessionId, emittedAt | - | ✅ | 公共字段 |
| messageId | String | ✅ | 消息 ID |
| role | String | | `"assistant"` |
| tokens | Object | | token 用量（`{input, output, reasoning}`） |
| cost | Double | | 费用 |
| reason | String | | 结束原因（`"end_turn"`, `"max_tokens"`） |

**前端处理**：写入对应 message 的 meta 字段

**示例**：

```json
{
  "type": "step.done",
  "seq": 50,
  "welinkSessionId": "12345",
  "emittedAt": "2026-04-10T08:30:10Z",
  "messageId": "msg-001",
  "role": "assistant",
  "tokens": {"input": 1024, "output": 256, "reasoning": 512},
  "cost": 0.003,
  "reason": "end_turn"
}
```

---

### 5.3 `session.status` —— 会话状态变更

**语义**：通知前端会话的处理状态。

**字段**：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| type | String | ✅ | `"session.status"` |
| seq, welinkSessionId, emittedAt | - | ✅ | 公共字段 |
| sessionStatus | String | ✅ | `"busy"` / `"idle"` / `"retry"` |

**前端处理**：
- `idle`：`finalizeAllStreamingMessages()`，所有 part 标记 `isStreaming = false`
- `busy` / `retry`：设置 `isStreaming = true`

**示例**：

```json
{
  "type": "session.status",
  "seq": 51,
  "welinkSessionId": "12345",
  "sessionStatus": "idle"
}
```

---

### 5.4 `session.title` —— 会话标题更新

**字段**：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| type | String | ✅ | `"session.title"` |
| seq, welinkSessionId | - | ✅ | 公共字段 |
| title | String | ✅ | 新标题 |

**前端处理**：回调通知上层更新标题

**示例**：

```json
{
  "type": "session.title",
  "seq": 52,
  "welinkSessionId": "12345",
  "title": "JDK8 与 JDK21 的区别"
}
```

---

### 5.5 `session.error` —— 会话级错误

**字段**：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| type | String | ✅ | `"session.error"` |
| seq, welinkSessionId | - | ✅ | 公共字段 |
| error | String | ✅ | 错误信息 |

**前端处理**：`finalizeAllStreamingMessages()` + `setError(msg.error)`

**示例**：

```json
{
  "type": "session.error",
  "seq": 53,
  "welinkSessionId": "12345",
  "error": "Agent process crashed unexpectedly"
}
```

---

### 5.6 `agent.online` —— Agent 上线

**字段**：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| type | String | ✅ | `"agent.online"` |

**前端处理**：`setAgentStatus('online')`

**示例**：`{"type":"agent.online"}`

---

### 5.7 `agent.offline` —— Agent 离线

**字段**：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| type | String | ✅ | `"agent.offline"` |

**前端处理**：`setAgentStatus('offline')`

**示例**：`{"type":"agent.offline"}`

---

### 5.8 `error` —— 通用错误

**字段**：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| type | String | ✅ | `"error"` |
| error | String | ✅ | 错误信息 |

**前端处理**：`finalizeAllStreamingMessages()` + `setError(msg.error)`

**示例**：`{"type":"error","error":"Session not found"}`

---

### 5.9 `message.user` —— 多端同步用户消息

**语义**：其他设备发送的用户消息推送到当前设备。

**字段**：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| type | String | ✅ | `"message.user"` |
| messageId | String | ✅ | 消息 ID |
| messageSeq | Integer | ✅ | 消息序号 |
| role | String | ✅ | `"user"` |
| content | String | ✅ | 用户消息文本 |
| welinkSessionId | String | ✅ | 会话 ID |

**前端处理**：去重检查后插入 messages 列表

**示例**：

```json
{
  "type": "message.user",
  "messageId": "msg-user-002",
  "messageSeq": 5,
  "role": "user",
  "content": "Please help me with this bug",
  "welinkSessionId": "12345"
}
```

---

### 5.10 `snapshot` —— 历史消息快照

**语义**：WebSocket 连接/重连时推送完整历史状态。

**字段**：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| type | String | ✅ | `"snapshot"` |
| seq | Long | ✅ | 序列号 |
| welinkSessionId | String | ✅ | 会话 ID |
| emittedAt | String | | 时间戳 |
| messages | List\<Object\> | ✅ | 完整历史消息数组 |

**前端处理**：清空所有 assembler，用快照内容替换 messages

**示例**：

```json
{
  "type": "snapshot",
  "seq": 1,
  "welinkSessionId": "12345",
  "messages": [
    {"id": "msg-001", "role": "user", "content": "Hello", "parts": []},
    {"id": "msg-002", "role": "assistant", "content": "Hi!", "parts": []}
  ]
}
```

---

### 5.11 `streaming` —— 实时流式状态

**语义**：与 snapshot 配对，snapshot 之后发送，表示当前流式中的状态。

**字段**：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| type | String | ✅ | `"streaming"` |
| seq | Long | ✅ | 序列号 |
| welinkSessionId | String | ✅ | 会话 ID |
| sessionStatus | String | ✅ | `"busy"` / `"idle"` |
| parts | List\<Object\> | | 当前流式中的 part 列表 |
| messageId | String | | 当前流式消息 ID |
| messageSeq | Integer | | 消息序号 |
| role | String | | 消息角色 |

**前端处理**：idle 则 finalize，busy + parts 非空则标记最后一条 assistant 消息为 streaming

**示例**：

```json
{
  "type": "streaming",
  "seq": 2,
  "welinkSessionId": "12345",
  "sessionStatus": "busy",
  "messageId": "msg-003",
  "messageSeq": 3,
  "role": "assistant",
  "parts": [
    {"type": "text.delta", "partId": "p1", "content": "I am currently..."}
  ]
}
```

---

## 6. 云端扩展消息（新增）

以下类型仅由业务助手（`assistantScope = "business"`）产生，个人助手不会返回这些类型。

### 6.1 `planning.delta` —— 规划内容增量

**语义**：云端 Agent 的规划过程增量推送。

**字段**：同 `text.delta` 结构，content 为规划内容增量。

**前端处理**：创建 `type: 'planning'` 的 part，content 追加，`isStreaming = true`。展示为可折叠的"规划中"区域。

**示例**：

```json
{
  "type": "planning.delta",
  "seq": 35,
  "welinkSessionId": "12345",
  "emittedAt": "2026-04-10T08:29:55Z",
  "messageId": "msg-001",
  "role": "assistant",
  "partId": "part-planning-01",
  "content": "用户询问JDK8和JDK21的区别，需要搜索对比"
}
```

---

### 6.2 `planning.done` —— 规划内容完成

**语义**：规划阶段结束，携带完整规划内容。

**字段**：同 `planning.delta`，content 为完整规划文本。

**前端处理**：将 planning part 的 content 替换，`isStreaming = false`

**示例**：

```json
{
  "type": "planning.done",
  "seq": 36,
  "welinkSessionId": "12345",
  "emittedAt": "2026-04-10T08:29:56Z",
  "messageId": "msg-001",
  "role": "assistant",
  "partId": "part-planning-01",
  "content": "用户询问JDK8和JDK21的区别，需要分别搜索两者的特性并进行对比"
}
```

---

### 6.3 `searching` —— 搜索关键词

**语义**：云端 Agent 正在搜索，展示搜索关键词。

**字段**：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| type | String | ✅ | `"searching"` |
| seq, welinkSessionId, emittedAt | - | ✅ | 公共字段 |
| messageId | String | ✅ | 消息 ID |
| keywords | List\<String\> | ✅ | 搜索关键词列表 |

**前端处理**：创建 `type: 'searching'` 的 part，展示关键词标签，`isStreaming = false`

**示例**：

```json
{
  "type": "searching",
  "seq": 37,
  "welinkSessionId": "12345",
  "emittedAt": "2026-04-10T08:29:56Z",
  "messageId": "msg-001",
  "keywords": ["JDK8特性", "JDK21特性"]
}
```

---

### 6.4 `search_result` —— 搜索结果

**语义**：搜索完成，返回结果列表，按 index 升序排列。

**字段**：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| type | String | ✅ | `"search_result"` |
| seq, welinkSessionId, emittedAt | - | ✅ | 公共字段 |
| messageId | String | ✅ | 消息 ID |
| searchResults | List\<SearchResultItem\> | ✅ | 搜索结果列表 |

**SearchResultItem**：

| 字段 | 类型 | 说明 |
|------|------|------|
| index | String | 序号（从 "1" 开始） |
| title | String | 标题 |
| source | String | 来源 |

**前端处理**：创建 `type: 'search_result'` 的 part，展示搜索结果列表卡片，`isStreaming = false`

**示例**：

```json
{
  "type": "search_result",
  "seq": 38,
  "welinkSessionId": "12345",
  "emittedAt": "2026-04-10T08:29:57Z",
  "messageId": "msg-001",
  "searchResults": [
    {"index": "1", "title": "java学习系列15", "source": "CSDN博客"},
    {"index": "2", "title": "JAVA8新特性", "source": "菜鸟教程"},
    {"index": "3", "title": "JDK21发布说明", "source": "Oracle官网"}
  ]
}
```

---

### 6.5 `reference` —— 引用结果

**语义**：返回引用列表，index 与 search_result 对应，用于文本中的角标引用（如 `[1]`）。

**字段**：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| type | String | ✅ | `"reference"` |
| seq, welinkSessionId, emittedAt | - | ✅ | 公共字段 |
| messageId | String | ✅ | 消息 ID |
| references | List\<ReferenceItem\> | ✅ | 引用列表 |

**ReferenceItem**：

| 字段 | 类型 | 说明 |
|------|------|------|
| index | String | 序号（与 search_result 中 index 对应） |
| title | String | 标题 |
| source | String | 来源 |
| url | String | 链接（https/http） |
| content | String | 内容摘要 |

**前端处理**：创建 `type: 'reference'` 的 part，展示引用卡片（标题 + 来源 + 可点击链接），`isStreaming = false`

**示例**：

```json
{
  "type": "reference",
  "seq": 39,
  "welinkSessionId": "12345",
  "emittedAt": "2026-04-10T08:29:58Z",
  "messageId": "msg-001",
  "references": [
    {"index": "1", "title": "java学习系列15", "url": "https://blog.csdn.net/xxx", "source": "CSDN博客", "content": "JDK8是JAVA开发工具包的一个版本..."},
    {"index": "2", "title": "Java8新特性", "url": "https://www.runoob.com/java/java8-new-features.html", "source": "菜鸟教程", "content": "Java8是java语言开发的一个主要版本..."}
  ]
}
```

---

### 6.6 `ask_more` —— 追问建议

**语义**：云端 Agent 提供的追问建议，用户点击后直接发送对应文本。

**字段**：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| type | String | ✅ | `"ask_more"` |
| seq, welinkSessionId, emittedAt | - | ✅ | 公共字段 |
| messageId | String | ✅ | 消息 ID |
| askMoreQuestions | List\<String\> | ✅ | 追问问题列表 |

**前端处理**：创建 `type: 'ask_more'` 的 part，展示可点击按钮列表，点击后将文本填入输入框并发送，`isStreaming = false`

**示例**：

```json
{
  "type": "ask_more",
  "seq": 49,
  "welinkSessionId": "12345",
  "emittedAt": "2026-04-10T08:30:09Z",
  "messageId": "msg-001",
  "askMoreQuestions": ["Lambda表达式可以用于哪些典型场景？", "JDK21的虚拟线程如何使用？"]
}
```

---

## 7. 完整消息流示例

### 7.1 个人助手：简单文本对话

```
→ step.start (messageId=msg-001)
→ thinking.delta (content="让我想想...")
→ thinking.done (content="让我想想...用户在问部署配置")
→ text.delta (content="你好，")
→ text.delta (content="以下是部署配置说明：\n\n")
→ text.delta (content="1. 首先确保...")
→ text.done (content="你好，以下是部署配置说明：\n\n1. 首先确保...")
→ step.done (tokens={input:100, output:50}, reason="end_turn")
→ session.status (sessionStatus="idle")
```

### 7.2 个人助手：带工具调用

```
→ step.start (messageId=msg-001)
→ thinking.delta (content="需要读取文件...")
→ thinking.done (content="需要读取文件来分析代码")
→ tool.update (toolName="Read", status="pending")
→ tool.update (toolName="Read", status="running")
→ tool.update (toolName="Read", status="completed", output="file content")
→ text.delta (content="分析结果如下...")
→ text.done (content="分析结果如下...")
→ step.done (tokens={...}, reason="end_turn")
→ session.status (sessionStatus="idle")
```

### 7.3 业务助手：带搜索和引用（云端）

```
→ planning.delta (content="用户询问JDK对比，需要搜索")
→ planning.done (content="用户询问JDK对比，需要搜索对比")
→ searching (keywords=["JDK8特性", "JDK21特性"])
→ search_result (searchResults=[{index:"1",...}, {index:"2",...}])
→ reference (references=[{index:"1", url:"...",...}])
→ thinking.delta (content="整理对比结果...")
→ thinking.done (content="整理对比结果...")
→ text.delta (content="# JDK8 vs JDK21\n\n")
→ text.delta (content="JDK8 引入了 Lambda[1]...")
→ text.done (content="# JDK8 vs JDK21\n\nJDK8 引入了 Lambda[1]...")
→ ask_more (askMoreQuestions=["Lambda表达式场景？", "虚拟线程如何使用？"])
→ session.status (sessionStatus="idle")
```

### 7.4 WebSocket 重连

```
→ snapshot (messages=[...完整历史...])
→ streaming (sessionStatus="busy", parts=[{type:"text.delta", content:"正在回复中..."}])
→ text.delta (content="继续回复...")
→ ...正常消息流继续
```
