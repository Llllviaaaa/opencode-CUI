# Skill Server → MiniApp 流式协议文档

> WebSocket 端点 `/ws/skill/stream`，SS 向 MiniApp 前端推送的所有 StreamMessage 类型完整定义。

---

## 1. 传输格式

- **协议**：WebSocket（`/ws/skill/stream`）
- **认证**：Cookie `userId`
- **序列化**：直接 JSON 序列化 StreamMessage，无外层包装
- **空值处理**：`@JsonInclude(NON_NULL)`，null 字段不出现在 JSON 中
- **嵌套类平铺**：`@JsonUnwrapped` 注解使 ToolInfo、PermissionInfo、QuestionInfo、UsageInfo、FileInfo 的字段平铺到 JSON 顶层
- **seq 设置**：所有消息在 SkillStreamHandler 发送时统一设置 seq（Redis INCR），Translator 不设置

---

## 2. 字段总表

### 2.1 公共字段

| 字段 | 类型 | 说明 | 哪些 type 有 |
|------|------|------|-------------|
| type | String | 消息类型 | 所有 |
| seq | Long | 传输层序列号（Redis INCR），发送时统一赋值 | 所有 |
| welinkSessionId | String | 会话 ID | 所有（enrichStreamMessage 补充） |
| emittedAt | String | ISO 8601 时间戳 | 大部分。**排除**：permission.reply、agent.online、agent.offline、error |
| messageId | String | SS 持久化的消息 ID（可能被 ActiveMessageTracker 覆写） | Part 级消息、step.start/done、message.user、streaming |
| sourceMessageId | String | 上游原始消息 ID（Translator 设置，不被覆写） | Part 级消息、step.start/done |
| messageSeq | Integer | 消息在会话中的业务序号 | Part 级消息、message.user、streaming |
| role | String | `"assistant"` / `"user"` | Part 级消息、step.start/done、message.user、streaming |
| partId | String | Part 唯一标识 | Part 级消息（text/thinking/tool/question/file/permission.ask） |
| partSeq | Integer | Part 在消息中的排序序号 | Part 级消息 |
| content | String | 文本内容 | text/thinking/planning/permission.ask（title 回退）/error |
| status | String | 状态 | tool.update、question |
| title | String | 显示标题 | tool.update、session.title、permission.ask |
| error | String | 错误信息 | session.error、error 类型 |
| sessionStatus | String | 会话状态 `"busy"/"idle"/"retry"` | session.status、streaming |
| subagentSessionId | String | 子代理会话 ID | Part 级消息（可选） |
| subagentName | String | 子代理名称 | Part 级消息（可选） |

### 2.2 @JsonUnwrapped 嵌套字段（平铺到 JSON 顶层）

| 来源嵌套类 | JSON 字段 | 类型 | 哪些 type 用 |
|-----------|----------|------|------------|
| ToolInfo | toolName | String | tool.update、question |
| ToolInfo | toolCallId | String | tool.update、question |
| ToolInfo | input | Object | tool.update、question |
| ToolInfo | output | String | tool.update、question (completed) |
| PermissionInfo | permissionId | String | permission.ask、permission.reply |
| PermissionInfo | permType | String | permission.ask、permission.reply |
| PermissionInfo | metadata | Object | permission.ask |
| PermissionInfo | response | String | permission.reply |
| QuestionInfo | header | String | question (running 状态) |
| QuestionInfo | question | String | question (running 状态) |
| QuestionInfo | options | List\<String\> | question (running 状态) |
| UsageInfo | tokens | Map | step.done |
| UsageInfo | cost | Double | step.done |
| UsageInfo | reason | String | step.done |
| FileInfo | fileName | String | file |
| FileInfo | fileUrl | String | file |
| FileInfo | fileMime | String | file |

### 2.3 仅特定 type 使用的字段

| 字段 | 类型 | 哪些 type 用 |
|------|------|------------|
| messages | List\<Object\> | snapshot |
| parts | List\<Object\> | streaming |
| keywords | List\<String\> | searching（云端扩展） |
| searchResults | List\<SearchResultItem\> | search_result（云端扩展） |
| references | List\<ReferenceItem\> | reference（云端扩展） |
| askMoreQuestions | List\<String\> | ask_more（云端扩展） |

### 2.4 messageId 与 sourceMessageId 的区别

- **sourceMessageId**：上游协议（OpenCode/云端）中的原始消息 ID，由 Translator 设置，**不会被覆写**
- **messageId**：SS 内部持久化的消息 ID，可能被 `ActiveMessageTracker.applyMessageContext()` 覆写为 DB 中的 messageId
- 两者在大多数情况下相同，但当 SS 创建新消息时（自动生成 ID），messageId 可能与 sourceMessageId 不同

---

## 3. 消息类型分类

### 3.1 处理层级

| 层级 | 说明 | 类型 |
|------|------|------|
| **Part 级**（StreamAssembler） | 创建/更新消息中的 Part | text.delta, text.done, thinking.delta, thinking.done, tool.update, question, file, permission.ask, permission.reply |
| **会话级**（useSkillStream 直接处理） | 操作会话状态、消息列表 | step.start, step.done, session.status, session.title, session.error, agent.online, agent.offline, error, message.user, snapshot, streaming |
| **云端扩展**（新增） | 云端业务助手特有 | planning.delta, planning.done, searching, search_result, reference, ask_more |

### 3.2 各 type 携带字段速查表

✅ = 有值，- = 无

| type | seq | welinkSessionId | emittedAt | messageId | sourceMessageId | messageSeq | role | partId | partSeq | content |
|------|-----|----------------|-----------|-----------|----------------|------------|------|--------|---------|---------|
| text.delta | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| text.done | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| thinking.delta | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| thinking.done | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| tool.update | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | - |
| question (running) | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | - | ✅ | ✅ | - |
| question (completed) | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | - |
| file | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | - |
| permission.ask | ✅ | ✅ | ✅ | ✅ | ✅ | - | - | ✅ | - | - |
| permission.reply | ✅ | ✅ | **-** | - | - | - | ✅ | - | - | - |
| step.start | ✅ | ✅ | ✅ | ✅ | - | - | ✅ | - | - | - |
| step.done | ✅ | ✅ | ✅ | ✅ | ✅ | - | ✅ | - | - | - |
| session.status | ✅ | ✅ | ✅ | - | - | - | - | - | - | - |
| session.title | ✅ | ✅ | ✅ | - | - | - | - | - | - | - |
| session.error | ✅ | ✅ | ✅ | - | - | - | - | - | - | - |
| agent.online | ✅ | - | **-** | - | - | - | - | - | - | - |
| agent.offline | ✅ | - | **-** | - | - | - | - | - | - | - |
| error | ✅ | ✅ | **-** | - | - | - | - | - | - | - |
| message.user | ✅ | ✅ | ✅ | ✅ | - | ✅ | ✅ | - | - | ✅ |
| snapshot | ✅ | ✅ | ✅ | - | - | - | - | - | - | - |
| streaming | ✅ | ✅ | ✅ | ✅ | - | ✅ | ✅ | - | - | - |

> **加粗的 -** 表示该类型在代码中被显式排除（`EMITTED_AT_EXCLUDED_TYPES`）

---

## 4. Part 级消息详情

### 4.1 `text.delta` —— 流式文本增量

**语义**：assistant 回复正文的一个增量片段。

**前端处理**：查找/创建 text part，将 content **追加**到 `part.content`（`+=`），`isStreaming = true`

**示例**：

```json
{
  "type": "text.delta",
  "seq": 42,
  "welinkSessionId": "12345",
  "emittedAt": "2026-04-10T08:30:00Z",
  "messageId": "msg-001",
  "sourceMessageId": "msg-001",
  "messageSeq": 1,
  "role": "assistant",
  "partId": "part-text-01",
  "partSeq": 0,
  "content": "Hello, here is the "
}
```

---

### 4.2 `text.done` —— 文本片段完成

**语义**：文本 part 流式完成，携带完整内容。

**前端处理**：将 `part.content` **替换**为 `msg.content`（`=`，非追加），`isStreaming = false`

**示例**：

```json
{
  "type": "text.done",
  "seq": 43,
  "welinkSessionId": "12345",
  "emittedAt": "2026-04-10T08:30:01Z",
  "messageId": "msg-001",
  "sourceMessageId": "msg-001",
  "messageSeq": 1,
  "role": "assistant",
  "partId": "part-text-01",
  "partSeq": 0,
  "content": "Hello, here is the full answer text."
}
```

---

### 4.3 `thinking.delta` —— 深度思考增量

**语义**：推理/思考过程的增量推送。

**字段**：同 `text.delta` 结构。

**前端处理**：创建/查找 `type: 'thinking'` 的 part，content 追加，`isStreaming = true`

**示例**：

```json
{
  "type": "thinking.delta",
  "seq": 40,
  "welinkSessionId": "12345",
  "emittedAt": "2026-04-10T08:29:58Z",
  "messageId": "msg-001",
  "sourceMessageId": "msg-001",
  "messageSeq": 1,
  "role": "assistant",
  "partId": "part-thinking-01",
  "partSeq": 0,
  "content": "Let me analyze this step by step..."
}
```

---

### 4.4 `thinking.done` —— 深度思考完成

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
  "sourceMessageId": "msg-001",
  "messageSeq": 1,
  "role": "assistant",
  "partId": "part-thinking-01",
  "partSeq": 0,
  "content": "Let me analyze this step by step. The user wants to understand..."
}
```

---

### 4.5 `tool.update` —— 工具调用状态更新

**语义**：工具调用的生命周期状态变更（pending → running → completed/error）。

**特有字段**：

| 字段 | 类型 | 说明 |
|------|------|------|
| status | String | `"pending"` / `"running"` / `"completed"` / `"error"` |
| title | String | 工具调用显示标题（可选） |
| toolName | String | 工具名称 |
| toolCallId | String | 工具调用 ID |
| input | Object | 工具输入参数（可选） |
| output | String | 工具输出结果（completed 时，可选） |
| error | String | 错误信息（error 时，可选） |

**前端处理**：创建 `type: 'tool'` 的 part，`isStreaming = (status === 'pending' || status === 'running')`

**示例**：

```json
{
  "type": "tool.update",
  "seq": 44,
  "welinkSessionId": "12345",
  "emittedAt": "2026-04-10T08:30:02Z",
  "messageId": "msg-001",
  "sourceMessageId": "msg-001",
  "messageSeq": 1,
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

**语义**：Agent 向用户提问。分两个阶段：
- **running**（提问中）：携带 questionInfo（header/question/options）和 toolInfo
- **completed/error**（已回答）：只携带 toolInfo 和 status，**不携带 questionInfo**

**running 状态特有字段**：

| 字段 | 类型 | 说明 |
|------|------|------|
| status | String | `"running"` |
| toolName | String | `"question"` |
| toolCallId | String | 工具调用 ID（用户回答时需回传） |
| input | Object | 完整 question 参数（含 questions 数组） |
| header | String | 问题标题 |
| question | String | 问题内容 |
| options | List\<String\> | 可选项列表（前端做了兼容处理：string 数组自动包装为 `{label}` 对象） |

**completed/error 状态特有字段**：

| 字段 | 类型 | 说明 |
|------|------|------|
| status | String | `"completed"` / `"error"` |
| toolName | String | `"question"` |
| toolCallId | String | 工具调用 ID |
| output | String | 用户回答内容（completed 时） |

> **注意**：completed 状态**没有** header/question/options。前端通过之前 running 状态缓存的 part 数据展示。

**前端处理**：
- running：创建 `type: 'question'` 的 part，从 `msg.header`/`msg.question`/`msg.options` 或 `msg.input.questions[0]` 提取问题信息
- completed：查找已有 question part，标记 `answered = true`

**示例（running）**：

```json
{
  "type": "question",
  "seq": 45,
  "welinkSessionId": "12345",
  "emittedAt": "2026-04-10T08:30:03Z",
  "messageId": "msg-001",
  "sourceMessageId": "msg-001",
  "partId": "q-part-01",
  "partSeq": 2,
  "status": "running",
  "toolName": "question",
  "toolCallId": "call-q001",
  "input": {"questions": [{"header": "Confirm action", "question": "Do you want to proceed?", "options": ["Yes", "No"]}]},
  "header": "Confirm action",
  "question": "Do you want to proceed?",
  "options": ["Yes", "No"]
}
```

**示例（completed）**：

```json
{
  "type": "question",
  "seq": 55,
  "welinkSessionId": "12345",
  "emittedAt": "2026-04-10T08:31:00Z",
  "messageId": "msg-001",
  "sourceMessageId": "msg-001",
  "messageSeq": 1,
  "role": "assistant",
  "partId": "q-part-01",
  "partSeq": 2,
  "status": "completed",
  "toolName": "question",
  "toolCallId": "call-q001",
  "output": "Yes"
}
```

---

### 4.7 `file` —— 文件附件

**特有字段**：

| 字段 | 类型 | 说明 |
|------|------|------|
| fileName | String | 文件名 |
| fileUrl | String | 文件下载 URL |
| fileMime | String | MIME 类型（可选） |

**前端处理**：创建 `type: 'file'` 的 part，`isStreaming = false`

**示例**：

```json
{
  "type": "file",
  "seq": 46,
  "welinkSessionId": "12345",
  "emittedAt": "2026-04-10T08:30:04Z",
  "messageId": "msg-001",
  "sourceMessageId": "msg-001",
  "messageSeq": 1,
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

**特有字段**：

| 字段 | 类型 | 说明 |
|------|------|------|
| title | String | 权限请求显示标题（如命令内容） |
| permissionId | String | 权限请求 ID |
| permType | String | 权限类型 |
| metadata | Object | 额外信息（可选） |
| status | String | 状态（可选） |

> **注意**：permission.ask 有 messageId、sourceMessageId、partId，但**没有** messageSeq、partSeq。

**前端处理**：创建 `type: 'permission'` 的 part，`content = msg.title ?? msg.content`，`permResolved = false`

**示例**：

```json
{
  "type": "permission.ask",
  "seq": 47,
  "welinkSessionId": "12345",
  "emittedAt": "2026-04-10T08:30:05Z",
  "messageId": "msg-001",
  "sourceMessageId": "msg-001",
  "partId": "perm-part-01",
  "title": "bash: rm -rf /tmp/build",
  "permissionId": "perm-001",
  "permType": "Bash",
  "metadata": {"command": "rm -rf /tmp/build", "cwd": "/home/user"}
}
```

---

### 4.9 `permission.reply` —— 权限应答

**语义**：权限请求的处理结果。

> **注意**：permission.reply 的字段非常少——**没有** messageId、sourceMessageId、partId、partSeq、status、title、emittedAt。

**特有字段**：

| 字段 | 类型 | 说明 |
|------|------|------|
| permissionId | String | 对应权限请求 ID |
| response | String | `"once"` / `"always"` / `"reject"` |
| role | String | `"assistant"` |
| subagentSessionId | String | 子代理会话 ID（可选） |

**前端处理**：通过 `permissionId` 匹配已有 permission part，设置 `permResolved = true`

**示例**：

```json
{
  "type": "permission.reply",
  "seq": 48,
  "welinkSessionId": "12345",
  "role": "assistant",
  "permissionId": "perm-001",
  "response": "once"
}
```

---

## 5. 会话级消息详情

### 5.1 `step.start` —— 步骤开始

**语义**：LLM 推理步骤开始。

**字段**：type、seq、welinkSessionId、emittedAt、messageId、role

> **没有** sourceMessageId、partId、partSeq

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

**特有字段**：

| 字段 | 类型 | 说明 |
|------|------|------|
| tokens | Map | token 用量（`{input, output, reasoning}`） |
| cost | Double | 费用（可选） |
| reason | String | 结束原因（`"end_turn"`, `"max_tokens"` 等，可选） |

> **注意**：step.done 可能来自两个来源：
> 1. `message.part.updated (type=step-finish)` —— 携带 tokens、cost、reason
> 2. `message.updated (有 finish)` —— 只携带 reason

**前端处理**：写入对应 message 的 meta 字段

**示例**：

```json
{
  "type": "step.done",
  "seq": 50,
  "welinkSessionId": "12345",
  "emittedAt": "2026-04-10T08:30:10Z",
  "messageId": "msg-001",
  "sourceMessageId": "msg-001",
  "role": "assistant",
  "tokens": {"input": 1024, "output": 256, "reasoning": 512},
  "cost": 0.003,
  "reason": "end_turn"
}
```

---

### 5.3 `session.status` —— 会话状态变更

**语义**：通知前端会话的处理状态。

**字段**：type、seq、welinkSessionId、emittedAt、sessionStatus

> **没有** messageId、role、partId 等。这是纯会话级消息。

| sessionStatus | 含义 |
|--------------|------|
| `"busy"` | 会话处理中（active/running/busy 归一化） |
| `"idle"` | 会话空闲（idle/completed 归一化） |
| `"retry"` | 重连/恢复中（reconnecting/retry/recovering 归一化） |

**前端处理**：
- `idle`：`finalizeAllStreamingMessages()`，所有 part 标记 `isStreaming = false`
- `busy` / `retry`：设置 `isStreaming = true`

**示例**：

```json
{
  "type": "session.status",
  "seq": 51,
  "welinkSessionId": "12345",
  "emittedAt": "2026-04-10T08:30:11Z",
  "sessionStatus": "idle"
}
```

---

### 5.4 `session.title` —— 会话标题更新

**字段**：type、seq、welinkSessionId、emittedAt、title

> **没有** messageId、role、partId 等。

**前端处理**：回调通知上层更新标题

**示例**：

```json
{
  "type": "session.title",
  "seq": 52,
  "welinkSessionId": "12345",
  "emittedAt": "2026-04-10T08:30:12Z",
  "title": "JDK8 与 JDK21 的区别"
}
```

---

### 5.5 `session.error` —— 会话级错误

**字段**：type、seq、welinkSessionId、emittedAt、error

> **没有** messageId、role、partId 等。

**前端处理**：`finalizeAllStreamingMessages()` + `setError(msg.error)`

**示例**：

```json
{
  "type": "session.error",
  "seq": 53,
  "welinkSessionId": "12345",
  "emittedAt": "2026-04-10T08:30:13Z",
  "error": "Agent process crashed unexpectedly"
}
```

---

### 5.6 `agent.online` —— Agent 上线

**字段**：type、seq

> **没有** welinkSessionId、emittedAt 等。最精简的消息。

**前端处理**：`setAgentStatus('online')`

**示例**：`{"type":"agent.online","seq":60}`

---

### 5.7 `agent.offline` —— Agent 离线

**字段**：type、seq

**前端处理**：`setAgentStatus('offline')`

**示例**：`{"type":"agent.offline","seq":61}`

---

### 5.8 `error` —— 通用错误

**字段**：type、seq、welinkSessionId、error

> **没有** emittedAt（在排除列表中）。

**前端处理**：`finalizeAllStreamingMessages()` + `setError(msg.error)`

**示例**：

```json
{
  "type": "error",
  "seq": 70,
  "welinkSessionId": "12345",
  "error": "Session not found"
}
```

---

### 5.9 `message.user` —— 多端同步用户消息

**语义**：其他设备发送的用户消息推送到当前设备。

**字段**：type、seq、welinkSessionId、emittedAt、messageId、messageSeq、role（`"user"`）、content

> **没有** sourceMessageId、partId。

**前端处理**：去重检查后插入 messages 列表

**示例**：

```json
{
  "type": "message.user",
  "seq": 80,
  "welinkSessionId": "12345",
  "emittedAt": "2026-04-10T08:30:15Z",
  "messageId": "msg-user-002",
  "messageSeq": 5,
  "role": "user",
  "content": "Please help me with this bug"
}
```

---

### 5.10 `snapshot` —— 历史消息快照

**语义**：WebSocket 连接/重连时推送完整历史状态。

**字段**：type、seq、welinkSessionId、emittedAt、messages

**前端处理**：清空所有 assembler，用快照内容替换 messages

**示例**：

```json
{
  "type": "snapshot",
  "seq": 1,
  "welinkSessionId": "12345",
  "emittedAt": "2026-04-10T08:29:50Z",
  "messages": [
    {"id": "msg-001", "role": "user", "content": "Hello", "parts": []},
    {"id": "msg-002", "role": "assistant", "content": "Hi!", "parts": []}
  ]
}
```

---

### 5.11 `streaming` —— 实时流式状态

**语义**：与 snapshot 配对，snapshot 之后发送，表示当前流式中的状态。

**字段**：type、seq、welinkSessionId、emittedAt、sessionStatus、messageId（可选）、messageSeq（可选）、role（可选）、parts（可选）

**前端处理**：idle 则 finalize，busy + parts 非空则标记最后一条 assistant 消息为 streaming

**示例**：

```json
{
  "type": "streaming",
  "seq": 2,
  "welinkSessionId": "12345",
  "emittedAt": "2026-04-10T08:29:51Z",
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

以下类型仅由业务助手（`assistantScope = "business"`）产生。

### 6.1 `planning.delta` —— 规划内容增量

**字段**：同 `text.delta` 结构（seq、welinkSessionId、emittedAt、messageId、sourceMessageId、messageSeq、role、partId、partSeq、content）

**前端处理**：创建 `type: 'planning'` 的 part，content 追加，`isStreaming = true`。展示为可折叠的"规划中"区域。

**示例**：

```json
{
  "type": "planning.delta",
  "seq": 35,
  "welinkSessionId": "12345",
  "emittedAt": "2026-04-10T08:29:55Z",
  "messageId": "msg-001",
  "sourceMessageId": "msg-001",
  "messageSeq": 1,
  "role": "assistant",
  "partId": "part-planning-01",
  "partSeq": 0,
  "content": "用户询问JDK8和JDK21的区别，需要搜索对比"
}
```

---

### 6.2 `planning.done` —— 规划内容完成

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
  "sourceMessageId": "msg-001",
  "messageSeq": 1,
  "role": "assistant",
  "partId": "part-planning-01",
  "partSeq": 0,
  "content": "用户询问JDK8和JDK21的区别，需要分别搜索两者的特性并进行对比"
}
```

---

### 6.3 `searching` —— 搜索关键词

**特有字段**：

| 字段 | 类型 | 说明 |
|------|------|------|
| keywords | List\<String\> | 搜索关键词列表 |

> 没有 partId、partSeq、role、content 等 Part 级字段。

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

**特有字段**：

| 字段 | 类型 | 说明 |
|------|------|------|
| searchResults | List\<SearchResultItem\> | 搜索结果列表，按 index 升序 |

**SearchResultItem**：

| 字段 | 类型 | 说明 |
|------|------|------|
| index | String | 序号（从 "1" 开始） |
| title | String | 标题 |
| source | String | 来源 |

**前端处理**：创建 `type: 'search_result'` 的 part，展示搜索结果列表，`isStreaming = false`

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
    {"index": "2", "title": "JAVA8新特性", "source": "菜鸟教程"}
  ]
}
```

---

### 6.5 `reference` —— 引用结果

**特有字段**：

| 字段 | 类型 | 说明 |
|------|------|------|
| references | List\<ReferenceItem\> | 引用列表，按 index 升序 |

**ReferenceItem**：

| 字段 | 类型 | 说明 |
|------|------|------|
| index | String | 序号（与 search_result 的 index 对应） |
| title | String | 标题 |
| source | String | 来源 |
| url | String | 链接（https/http） |
| content | String | 内容摘要 |

**前端处理**：创建 `type: 'reference'` 的 part，展示引用卡片，`isStreaming = false`

**示例**：

```json
{
  "type": "reference",
  "seq": 39,
  "welinkSessionId": "12345",
  "emittedAt": "2026-04-10T08:29:58Z",
  "messageId": "msg-001",
  "references": [
    {"index": "1", "title": "java学习系列15", "url": "https://blog.csdn.net/xxx", "source": "CSDN博客", "content": "JDK8是JAVA开发工具包..."},
    {"index": "2", "title": "Java8新特性", "url": "https://www.runoob.com/java/java8-new-features.html", "source": "菜鸟教程", "content": "Java8是java语言开发的一个主要版本..."}
  ]
}
```

---

### 6.6 `ask_more` —— 追问建议

**特有字段**：

| 字段 | 类型 | 说明 |
|------|------|------|
| askMoreQuestions | List\<String\> | 追问问题列表 |

**前端处理**：创建 `type: 'ask_more'` 的 part，展示可点击按钮列表，`isStreaming = false`

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
→ session.status  {sessionStatus:"busy"}
→ step.start      {messageId:"msg-001", role:"assistant"}
→ thinking.delta  {partId:"prt-r1", content:"让我想想..."}
→ thinking.done   {partId:"prt-r1", content:"让我想想...用户在问部署配置"}
→ text.delta      {partId:"prt-t1", content:"你好，"}
→ text.delta      {partId:"prt-t1", content:"以下是部署配置说明"}
→ text.done       {partId:"prt-t1", content:"你好，以下是部署配置说明"}
→ step.done       {messageId:"msg-001", tokens:{input:100,output:50}, reason:"end_turn"}
→ session.status  {sessionStatus:"idle"}
```

### 7.2 个人助手：带工具调用

```
→ session.status  {sessionStatus:"busy"}
→ step.start      {messageId:"msg-001", role:"assistant"}
→ thinking.delta  {partId:"prt-r1", content:"需要读取文件..."}
→ thinking.done   {partId:"prt-r1", content:"需要读取文件来分析代码"}
→ tool.update     {partId:"prt-tool-1", toolName:"Read", status:"pending"}
→ tool.update     {partId:"prt-tool-1", toolName:"Read", status:"running"}
→ tool.update     {partId:"prt-tool-1", toolName:"Read", status:"completed", output:"file content"}
→ text.delta      {partId:"prt-t1", content:"分析结果如下..."}
→ text.done       {partId:"prt-t1", content:"分析结果如下..."}
→ step.done       {messageId:"msg-001", tokens:{...}, reason:"end_turn"}
→ session.status  {sessionStatus:"idle"}
```

### 7.3 个人助手：带交互问答

```
→ step.start      {messageId:"msg-001"}
→ question        {partId:"q-part-01", status:"running", toolCallId:"call-q1", question:"选择哪个？", options:["A","B"]}
  ... 用户回答 ...
→ question        {partId:"q-part-01", status:"completed", toolCallId:"call-q1", output:"A"}
→ text.delta      {partId:"prt-t1", content:"好的，选择了A..."}
→ text.done       {partId:"prt-t1", content:"好的，选择了A..."}
→ step.done       {messageId:"msg-001"}
→ session.status  {sessionStatus:"idle"}
```

### 7.4 个人助手：带权限请求

```
→ step.start      {messageId:"msg-001"}
→ text.delta      {partId:"prt-t1", content:"需要执行命令"}
→ permission.ask  {partId:"perm-part-01", permissionId:"perm-001", permType:"Bash", title:"bash: rm -rf /tmp"}
  ... 用户授权 ...
→ permission.reply {permissionId:"perm-001", response:"once", role:"assistant"}
→ tool.update     {partId:"prt-tool-1", toolName:"Bash", status:"completed"}
→ text.delta      {partId:"prt-t1", content:"命令执行成功"}
→ text.done       {partId:"prt-t1", content:"需要执行命令\n命令执行成功"}
→ step.done       {messageId:"msg-001"}
→ session.status  {sessionStatus:"idle"}
```

### 7.5 业务助手：带搜索和引用（云端）

```
→ session.status  {sessionStatus:"busy"}
→ planning.delta  {partId:"prt-plan-1", content:"用户询问JDK对比，需要搜索"}
→ planning.done   {partId:"prt-plan-1", content:"用户询问JDK对比，需要搜索对比"}
→ searching       {messageId:"msg-001", keywords:["JDK8特性","JDK21特性"]}
→ search_result   {messageId:"msg-001", searchResults:[{index:"1",...}]}
→ reference       {messageId:"msg-001", references:[{index:"1",url:"...",...}]}
→ thinking.delta  {partId:"prt-r1", content:"整理对比结果..."}
→ thinking.done   {partId:"prt-r1", content:"整理对比结果..."}
→ text.delta      {partId:"prt-t1", content:"# JDK8 vs JDK21\n\n"}
→ text.delta      {partId:"prt-t1", content:"JDK8 引入了 Lambda[1]..."}
→ text.done       {partId:"prt-t1", content:"# JDK8 vs JDK21\n\nJDK8 引入了 Lambda[1]..."}
→ ask_more        {messageId:"msg-001", askMoreQuestions:["Lambda场景？","虚拟线程？"]}
→ session.status  {sessionStatus:"idle"}
```

### 7.6 WebSocket 重连

```
→ snapshot        {messages:[...完整历史...]}
→ streaming       {sessionStatus:"busy", messageId:"msg-003", parts:[{type:"text.delta",content:"正在回复..."}]}
→ text.delta      {partId:"p1", content:"继续回复..."}
  ...正常消息流继续
```
