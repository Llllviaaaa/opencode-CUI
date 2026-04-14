# External Channel 接口文档

> 为 IM 及其他业务模块提供的 WebSocket 推送通道 + REST 入站接口。

---

## 概述

External Channel 提供两个接口：

| 接口 | 类型 | 用途 |
|------|------|------|
| `POST /api/external/invoke` | REST | 业务模块发送消息、回复 question/permission、重建 session |
| `ws://{host}/ws/external/stream` | WebSocket | 业务模块接收 AI 回复的实时流式推送 |

**数据流**：

```
业务模块                          Skill Server                    AI Agent
   │                                  │                              │
   ├── POST /api/external/invoke ──→  │  ── Gateway ──→              │
   │   (chat/question_reply/          │                              │
   │    permission_reply/rebuild)     │                              │
   │                                  │                              │
   │  ←── WS 推送 StreamMessage ───  │  ←── Gateway ──              │
   │   (text.delta/text.done/         │                              │
   │    permission.ask/question/...)  │                              │
```

---

## 1. REST 接口：POST /api/external/invoke

### 1.1 基本信息

| 项目 | 值 |
|------|------|
| URL | `POST /api/external/invoke` |
| Content-Type | `application/json` |
| 认证 | `Authorization: Bearer {token}` |
| Token 配置 | 服务端配置项 `skill.im.inbound-token`，与 IM Inbound 接口共用 |

### 1.2 请求格式

采用**固定信封 + 灵活 payload** 结构：

```json
{
  "action": "chat",
  "businessDomain": "im",
  "sessionType": "direct",
  "sessionId": "session-001",
  "assistantAccount": "assistant-001",
  "payload": { ... }
}
```

#### 信封字段（所有 action 必填）

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `action` | String | 是 | 操作类型：`chat` / `question_reply` / `permission_reply` / `rebuild` |
| `businessDomain` | String | 是 | 业务域标识，需与 WS 连接时的 `source` 一致（如 `im`） |
| `sessionType` | String | 是 | 会话类型：`group`（群聊）/ `direct`（单聊） |
| `sessionId` | String | 是 | 业务侧会话 ID |
| `assistantAccount` | String | 是 | 助手账号（用于解析对应的 AK 和 Agent） |
| `payload` | Object | 是 | 各 action 的专属数据（JSON 对象） |

### 1.3 各 Action 的 Payload 定义

#### action = `chat`（发送消息）

发送用户消息给 AI Agent，触发 AI 回复。

**Payload 字段：**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `content` | String | 是 | 消息文本内容 |
| `msgType` | String | 否 | 消息类型，默认 `text`（当前仅支持 `text`） |
| `imageUrl` | String | 否 | 图片 URL（`msgType=image` 时使用，暂不支持） |
| `chatHistory` | Array | 否 | 聊天上下文历史（群聊场景使用，见下方格式） |

**chatHistory 数组元素格式：**

| 字段 | 类型 | 说明 |
|------|------|------|
| `senderAccount` | String | 发送者账号 |
| `senderName` | String | 发送者显示名 |
| `content` | String | 消息内容 |
| `timestamp` | Long | 消息时间戳（毫秒） |

**请求示例：**

```json
{
  "action": "chat",
  "businessDomain": "im",
  "sessionType": "direct",
  "sessionId": "dm-001",
  "assistantAccount": "assistant-001",
  "payload": {
    "content": "帮我查一下今天的天气",
    "msgType": "text"
  }
}
```

**群聊带历史消息示例：**

```json
{
  "action": "chat",
  "businessDomain": "im",
  "sessionType": "group",
  "sessionId": "grp-001",
  "assistantAccount": "assistant-001",
  "payload": {
    "content": "总结一下上面的讨论",
    "msgType": "text",
    "chatHistory": [
      {"senderAccount": "user-A", "senderName": "张三", "content": "我觉得方案A好", "timestamp": 1713000000000},
      {"senderAccount": "user-B", "senderName": "李四", "content": "我支持方案B", "timestamp": 1713000060000}
    ]
  }
}
```

**处理逻辑：**
1. 解析 `assistantAccount` → 获取 AK 和 Agent 信息
2. 检查 Agent 是否在线（个人助手需要在线检查，业务助手跳过）
3. 群聊场景：将 `chatHistory` 拼接到 prompt 中
4. 查找或创建 Session：
   - Session 不存在 → 自动创建新 Session + 发送消息
   - Session 存在但 toolSessionId 未就绪 → 等待重建后自动发送
   - Session 就绪 → 直接发送给 AI Agent
5. AI 回复通过 WebSocket 推送（若已建立连接）或 IM REST 回调（降级）

---

#### action = `question_reply`（回复 Agent 提问）

当 AI Agent 通过 WebSocket 推送了 `question` 类型消息（询问用户选择），用此 action 回复。

**Payload 字段：**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `content` | String | 是 | 回答内容（选项文本或自由文本） |
| `toolCallId` | String | 是 | 对应 question 消息中的 `toolCallId` |
| `subagentSessionId` | String | 否 | 子 Agent session（多 Agent 场景路由用） |

**请求示例：**

```json
{
  "action": "question_reply",
  "businessDomain": "im",
  "sessionType": "direct",
  "sessionId": "dm-001",
  "assistantAccount": "assistant-001",
  "payload": {
    "content": "A",
    "toolCallId": "toolu_functions.question:0"
  }
}
```

**前置条件：** 必须先通过 WS 收到 `question` 类型的推送消息，从中提取 `toolCallId`。

---

#### action = `permission_reply`（回复权限请求）

当 AI Agent 通过 WebSocket 推送了 `permission.ask` 类型消息（请求执行工具的权限），用此 action 回复。

**Payload 字段：**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `permissionId` | String | 是 | 对应 permission.ask 消息中的 `permissionId` |
| `response` | String | 是 | 授权结果，枚举值：`once` / `always` / `reject` |
| `subagentSessionId` | String | 否 | 子 Agent session（多 Agent 场景路由用） |

**response 枚举说明：**

| 值 | 含义 |
|------|------|
| `once` | 仅本次授权 |
| `always` | 本会话内永久授权（同类型工具不再询问） |
| `reject` | 拒绝授权（Agent 将取消本次工具调用） |

**请求示例：**

```json
{
  "action": "permission_reply",
  "businessDomain": "im",
  "sessionType": "direct",
  "sessionId": "dm-001",
  "assistantAccount": "assistant-001",
  "payload": {
    "permissionId": "per_d897d347c001WFYI2DbWVsuMuA",
    "response": "once"
  }
}
```

**前置条件：** 必须先通过 WS 收到 `permission.ask` 类型的推送消息，从中提取 `permissionId`。

---

#### action = `rebuild`（重建会话）

主动触发 Session 的 toolSession 重建。适用于 Session 异常需要恢复的场景。

**Payload 字段：** 无必填字段，传空对象 `{}` 即可。

**请求示例：**

```json
{
  "action": "rebuild",
  "businessDomain": "im",
  "sessionType": "direct",
  "sessionId": "dm-001",
  "assistantAccount": "assistant-001",
  "payload": {}
}
```

**处理逻辑：**
- Session 存在 → 清空 toolSessionId，重新请求 Gateway 创建
- Session 不存在 → 创建新 Session + toolSession

---

### 1.4 响应格式

所有响应均为 JSON，HTTP 状态码 + body 两层表示。

#### 成功响应

**HTTP 200**

```json
{
  "code": 0
}
```

> `code=0` 表示消息已接收，不代表 AI 已回复。AI 回复通过 WebSocket 异步推送。

#### 错误响应

**HTTP 400（信封/Payload 校验失败）**

```json
{
  "code": 400,
  "errormsg": "具体错误信息"
}
```

| errormsg | 原因 |
|----------|------|
| `action is required` | 缺少 action 字段 |
| `Invalid action: xxx` | action 不是 chat/question_reply/permission_reply/rebuild |
| `businessDomain is required` | 缺少 businessDomain |
| `Invalid sessionType` | sessionType 不是 group/direct |
| `sessionId is required` | 缺少 sessionId |
| `assistantAccount is required` | 缺少 assistantAccount |
| `payload.content is required for chat` | chat 缺少 content |
| `payload.content is required for question_reply` | question_reply 缺少 content |
| `payload.toolCallId is required for question_reply` | question_reply 缺少 toolCallId |
| `payload.permissionId is required` | permission_reply 缺少 permissionId |
| `payload.response must be once/always/reject` | response 值不合法 |

**HTTP 401（认证失败）**

```json
{
  "code": 401,
  "errormsg": "Missing token"
}
```

| errormsg | 原因 |
|----------|------|
| `Missing token` | 缺少 Authorization header 或格式不是 `Bearer xxx` |
| `Invalid token` | Token 不匹配 |
| `Inbound token is not configured` | 服务端未配置 token |

**HTTP 200 + 业务错误码**

```json
{
  "code": 404,
  "errormsg": "Invalid assistant account"
}
```

| code | errormsg | 原因 |
|------|----------|------|
| 404 | `Invalid assistant account` | assistantAccount 解析失败（无对应 AK） |
| 404 | `Session not found or not ready` | question_reply/permission_reply 时 Session 不存在或 toolSessionId 未就绪 |

---

## 2. WebSocket 接口：/ws/external/stream

### 2.1 基本信息

| 项目 | 值 |
|------|------|
| URL | `ws://{host}:{port}/ws/external/stream` |
| 协议 | WebSocket (RFC 6455) |
| 认证 | Sec-WebSocket-Protocol 子协议 |
| 方向 | 服务端 → 客户端推送（客户端只发心跳） |

### 2.2 连接认证

通过 `Sec-WebSocket-Protocol` HTTP header 传递认证信息。

**格式：** `auth.{base64url_encoded_json}`

**JSON 内容：**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `token` | String | 是 | 认证 Token（与 REST 接口的 Token 相同） |
| `source` | String | 是 | 模块标识（如 `im`），需与 REST 请求的 `businessDomain` 一致 |
| `instanceId` | String | 是 | 实例标识（如 `im-node-1`），用于区分同一模块的多个实例 |

**构造示例（伪代码）：**

```javascript
const authPayload = JSON.stringify({
  token: "your-token",
  source: "im",
  instanceId: "im-node-1"
});
const subprotocol = "auth." + base64url_encode(authPayload);

// WebSocket 握手
ws = new WebSocket("ws://host:port/ws/external/stream", [subprotocol]);
```

**Java 示例：**

```java
String json = "{\"token\":\"xxx\",\"source\":\"im\",\"instanceId\":\"im-1\"}";
String protocol = "auth." + Base64.getUrlEncoder().withoutPadding()
    .encodeToString(json.getBytes(StandardCharsets.UTF_8));

WebSocketClient client = new StandardWebSocketClient();
WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
headers.setSecWebSocketProtocol(Collections.singletonList(protocol));
client.execute(handler, headers, URI.create("ws://host:port/ws/external/stream"));
```

**认证失败：** 握手被拒绝，HTTP 返回非 101 状态码。

| 失败原因 | 说明 |
|----------|------|
| 无 Sec-WebSocket-Protocol header | 连接拒绝 |
| Token 不匹配 | 连接拒绝 |
| 缺少 source | 连接拒绝 |
| 缺少 instanceId | 连接拒绝 |

### 2.3 心跳机制

| 方向 | 消息 | 间隔 |
|------|------|------|
| 客户端 → 服务端 | `{"action":"ping"}` | 建议每 **10~30 秒** 发送一次 |
| 服务端 → 客户端 | `{"action":"pong"}` | 收到 ping 后立即回复 |
| 服务端超时断开 | - | **60 秒**无任何客户端消息则断开连接 |

**重要：** 客户端必须定期发送 ping 保持连接活跃，否则将被服务端超时断开。

### 2.4 连接模型

**服务级连接**：一个 `source`（如 `im`）可以有多个实例（`instanceId`）连接。服务端维护连接池：

```
source → { instanceId → WebSocketSession }

示例：
"im"  → { "im-node-1" → ws1, "im-node-2" → ws2 }
"crm" → { "crm-node-1" → ws3 }
```

推送消息时，**同一 source 的所有实例都会收到**（广播），由业务方根据消息中的 `sessionId` 自行路由。

同一 `source+instanceId` 重连时，旧连接自动被替换。

### 2.5 推送消息格式

所有推送消息为 JSON 文本帧，外层信封 + 内层 StreamMessage：

```json
{
  "sessionId": "2711748171393929216",
  "userId": "900001",
  "domain": "im",
  "message": {
    "type": "text.delta",
    "welinkSessionId": "...",
    "emittedAt": "2026-04-14T00:36:00.165Z",
    "role": "assistant",
    "content": "你好"
  }
}
```

**信封字段：**

| 字段 | 类型 | 说明 |
|------|------|------|
| `sessionId` | String | Skill Server 内部会话 ID |
| `userId` | String | 用户 ID |
| `domain` | String | 业务域（与 WS 连接的 source 一致） |
| `message` | Object | StreamMessage 消息体（见下方类型定义） |

### 2.6 StreamMessage 类型定义

`message` 字段中的 `type` 决定消息类型。以下是所有可能的类型：

---

#### type = `text.delta`（流式文本片段）

AI 回复的流式文本片段，逐 token 推送。客户端应追加显示。

```json
{
  "type": "text.delta",
  "welinkSessionId": "ses_xxx",
  "emittedAt": "2026-04-14T00:36:00Z",
  "role": "assistant",
  "messageId": "msg_xxx",
  "content": "你好"
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `content` | String | 文本片段（追加到已有内容后） |
| `role` | String | 固定 `assistant` |
| `messageId` | String | 消息 ID（同一轮回复的 delta 共享相同 messageId） |

---

#### type = `text.done`（文本完成）

一轮文本回复完成。`content` 包含完整文本（= 所有同 messageId 的 `text.delta` 拼接结果）。

```json
{
  "type": "text.done",
  "welinkSessionId": "ses_xxx",
  "emittedAt": "2026-04-14T00:36:01Z",
  "role": "assistant",
  "messageId": "msg_xxx",
  "partId": "part_xxx",
  "content": "你好，这是完整的回复内容。"
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `content` | String | 完整的回复文本 |
| `messageId` | String | 消息 ID |
| `partId` | String | 消息部分 ID |

---

#### type = `thinking.delta`（思考过程片段）

AI 的思考/推理过程（流式）。可选择性展示给用户。

```json
{
  "type": "thinking.delta",
  "content": "让我分析一下这个问题...",
  "role": "assistant"
}
```

---

#### type = `thinking.done`（思考过程完成）

```json
{
  "type": "thinking.done",
  "content": "让我分析一下这个问题，需要考虑以下几个方面...",
  "role": "assistant"
}
```

---

#### type = `tool.update`（工具调用更新）

AI Agent 正在调用工具（如执行命令、读取文件等）。

```json
{
  "type": "tool.update",
  "toolName": "bash",
  "toolCallId": "toolu_xxx",
  "input": {"command": "echo hello"},
  "output": "hello\n",
  "status": "running"
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `toolName` | String | 工具名称（如 `bash`, `read`, `write`, `edit`） |
| `toolCallId` | String | 工具调用 ID |
| `input` | Object | 工具输入参数 |
| `output` | String | 工具执行输出 |
| `status` | String | 工具状态：`running` / `completed` / `failed` |

---

#### type = `permission.ask`（权限请求）⭐

AI Agent 请求执行工具的权限。**客户端必须通过 REST 接口的 `permission_reply` action 回复**，否则 Agent 将等待。

```json
{
  "type": "permission.ask",
  "welinkSessionId": "ses_xxx",
  "emittedAt": "2026-04-14T00:56:17Z",
  "role": "assistant",
  "title": "bash",
  "permissionId": "per_d897d347c001WFYI2DbWVsuMuA",
  "permType": "bash",
  "metadata": {}
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `permissionId` | String | **权限请求 ID（回复时必须使用此 ID）** |
| `permType` | String | 工具类型（如 `bash`, `write`, `edit`） |
| `title` | String | 工具名称 |
| `metadata` | Object | 工具调用的元数据 |

**收到后的操作：** 调用 `POST /api/external/invoke`，action=`permission_reply`，payload 中带 `permissionId` 和 `response`。

---

#### type = `permission.reply`（权限回复确认）

服务端确认已收到权限回复。

```json
{
  "type": "permission.reply",
  "role": "assistant",
  "permissionId": "per_xxx",
  "response": "once"
}
```

---

#### type = `question`（Agent 提问）⭐

AI Agent 向用户提出问题，等待回答。**客户端必须通过 REST 接口的 `question_reply` action 回复**。

```json
{
  "type": "question",
  "welinkSessionId": "ses_xxx",
  "emittedAt": "2026-04-14T01:38:51Z",
  "role": "assistant",
  "messageId": "msg_xxx",
  "status": "running",
  "toolName": "question",
  "toolCallId": "toolu_functions.question:0",
  "header": "Approach",
  "question": "Which approach: A or B?",
  "options": ["A (Recommended)", "B"]
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `toolCallId` | String | **问题调用 ID（回复时必须使用此 ID）** |
| `header` | String | 问题标题/分类 |
| `question` | String | 问题文本 |
| `options` | String[] | 选项列表（可能为空，表示开放式问题） |
| `status` | String | 状态：`running`（等待回答）/ `completed`（已回答） |

**收到后的操作：** 调用 `POST /api/external/invoke`，action=`question_reply`，payload 中带 `toolCallId` 和 `content`（回答内容）。

---

#### type = `step.start`（步骤开始）

Agent 开始一个新的处理步骤。

```json
{
  "type": "step.start"
}
```

---

#### type = `step.done`（步骤完成）

Agent 完成一个处理步骤。

```json
{
  "type": "step.done",
  "tokens": {"input": 1500, "output": 200},
  "cost": 0.005,
  "reason": "end_turn"
}
```

---

#### type = `session.status`（会话状态变更）

```json
{
  "type": "session.status",
  "sessionStatus": "busy"
}
```

| sessionStatus | 含义 |
|---------------|------|
| `busy` | Agent 正在处理中 |
| `idle` | Agent 处理完毕，等待新消息 |

---

#### type = `session.title`（会话标题更新）

```json
{
  "type": "session.title",
  "title": "天气查询对话"
}
```

---

#### type = `session.error`（会话错误）

```json
{
  "type": "session.error",
  "error": "ContextOverflowError: 上下文超出限制"
}
```

---

#### type = `error`（通用错误）

```json
{
  "type": "error",
  "error": "Agent 离线或处理异常"
}
```

---

#### type = `agent.online` / `agent.offline`

Agent 上线/下线通知。

```json
{"type": "agent.online"}
{"type": "agent.offline"}
```

---

#### 云端扩展类型（业务助手专属）

以下类型仅在业务助手（走云端 SSE）场景下出现：

| type | 说明 | 关键字段 |
|------|------|----------|
| `planning.delta` | 规划过程片段 | `content` |
| `planning.done` | 规划过程完成 | `content` |
| `searching` | 正在搜索 | `keywords`: String[] |
| `search_result` | 搜索结果 | `searchResults`: [{index, title, source}] |
| `reference` | 引用来源 | `references`: [{index, title, source, url, content}] |
| `ask_more` | 追问建议 | `askMoreQuestions`: String[] |

---

## 3. 出站路由策略

当 AI Agent 产生回复时，系统按以下优先级选择出站方式：

| 优先级 | 条件 | 出站方式 |
|--------|------|----------|
| 1 | 该 `businessDomain` 有活跃 WS 连接 | 通过 WebSocket 推送 StreamMessage |
| 2 | 该 `businessDomain` = `im` 且无 WS 连接 | 通过 IM REST API 推送纯文本（仅 text.done 内容） |
| 3 | 其他 domain 无 WS 连接 | 不投递（消息丢失） |

**建议：** 业务方应始终保持 WS 连接，确保能接收所有类型的 StreamMessage。WS 断开后仅 IM 有 REST 降级兜底，且只推送最终文本，丢失流式过程、权限请求、提问等交互事件。

---

## 4. 接入指南

### 4.1 接入步骤

1. **获取 Token** — 向管理员申请 `skill.im.inbound-token`
2. **建立 WS 连接** — 连接 `/ws/external/stream`，握手带 `token` + `source` + `instanceId`
3. **启动心跳** — 每 10~30 秒发送 `{"action":"ping"}`
4. **发送消息** — 通过 `POST /api/external/invoke` 发送 chat
5. **接收推送** — 在 WS 上监听 StreamMessage，按 `type` 处理
6. **处理交互** — 收到 `permission.ask` / `question` 时，通过 REST 接口回复

### 4.2 接入时序图

```
IM Server                     Skill Server                    AI Agent
    │                              │                              │
    ├── WS Connect ──────────────→ │                              │
    │   (auth.{token,source,id})   │                              │
    │ ←── WS Connected ──────────  │                              │
    │                              │                              │
    ├── ping ─────────────────────→│                              │
    │ ←── pong ───────────────────  │                              │
    │                              │                              │
    ├── POST /invoke (chat) ─────→ │ ── create_session ─────────→ │
    │ ←── {"code":0} ────────────  │                              │
    │                              │ ←── session_created ────────  │
    │                              │ ── chat ────────────────────→ │
    │                              │                              │
    │ ←── WS: session.status=busy  │ ←── thinking.delta ────────  │
    │ ←── WS: thinking.delta ───── │                              │
    │ ←── WS: thinking.done ────── │                              │
    │                              │                              │
    │ ←── WS: permission.ask ───── │ ←── permission_request ────  │
    │     (permissionId=per_xxx)   │                              │
    │                              │                              │
    ├── POST /invoke ────────────→ │                              │
    │   (permission_reply,         │ ── permission_reply ───────→ │
    │    permissionId=per_xxx,     │                              │
    │    response=once)            │                              │
    │ ←── {"code":0} ────────────  │                              │
    │                              │                              │
    │ ←── WS: tool.update ──────── │ ←── tool_event ────────────  │
    │ ←── WS: text.delta ───────── │ ←── tool_event ────────────  │
    │ ←── WS: text.delta ───────── │                              │
    │ ←── WS: text.done ────────── │ ←── tool_event ────────────  │
    │ ←── WS: session.status=idle  │ ←── tool_done ─────────────  │
    │                              │                              │
```

### 4.3 客户端代码示例（Python）

```python
import websocket
import json
import base64
import threading
import requests

# 配置
SS_URL = "http://localhost:8082"
WS_URL = "ws://localhost:8082/ws/external/stream"
TOKEN = "your-token"
SOURCE = "im"
INSTANCE_ID = "im-node-1"

# 1. 建立 WS 连接
auth = json.dumps({"token": TOKEN, "source": SOURCE, "instanceId": INSTANCE_ID})
protocol = "auth." + base64.urlsafe_b64encode(auth.encode()).decode().rstrip("=")

ws = websocket.WebSocket()
ws.settimeout(3)
ws.connect(WS_URL, subprotocols=[protocol])
print("WS connected")

# 2. 心跳线程
def heartbeat():
    while ws.connected:
        try:
            ws.send(json.dumps({"action": "ping"}))
            time.sleep(15)
        except:
            break

threading.Thread(target=heartbeat, daemon=True).start()

# 3. 发送消息
resp = requests.post(f"{SS_URL}/api/external/invoke",
    headers={"Content-Type": "application/json", "Authorization": f"Bearer {TOKEN}"},
    json={
        "action": "chat",
        "businessDomain": SOURCE,
        "sessionType": "direct",
        "sessionId": "my-session-001",
        "assistantAccount": "my-assistant",
        "payload": {"content": "你好", "msgType": "text"}
    })
print(f"Invoke: {resp.json()}")

# 4. 接收推送
while True:
    try:
        raw = ws.recv()
        msg = json.loads(raw)
        msg_type = msg.get("message", {}).get("type", "")

        if msg.get("action") == "pong":
            continue

        if msg_type == "text.delta":
            print(msg["message"]["content"], end="", flush=True)
        elif msg_type == "text.done":
            print(f"\n[完成] {msg['message']['content']}")
        elif msg_type == "permission.ask":
            perm_id = msg["message"]["permissionId"]
            print(f"\n[权限请求] {msg['message']['title']}, permissionId={perm_id}")
            # 回复权限
            requests.post(f"{SS_URL}/api/external/invoke",
                headers={"Content-Type": "application/json", "Authorization": f"Bearer {TOKEN}"},
                json={
                    "action": "permission_reply",
                    "businessDomain": SOURCE,
                    "sessionType": "direct",
                    "sessionId": "my-session-001",
                    "assistantAccount": "my-assistant",
                    "payload": {"permissionId": perm_id, "response": "once"}
                })
        elif msg_type == "question":
            tool_call_id = msg["message"]["toolCallId"]
            print(f"\n[提问] {msg['message']['question']}")
            print(f"  选项: {msg['message'].get('options', [])}")
            answer = input("请回答: ")
            requests.post(f"{SS_URL}/api/external/invoke",
                headers={"Content-Type": "application/json", "Authorization": f"Bearer {TOKEN}"},
                json={
                    "action": "question_reply",
                    "businessDomain": SOURCE,
                    "sessionType": "direct",
                    "sessionId": "my-session-001",
                    "assistantAccount": "my-assistant",
                    "payload": {"content": answer, "toolCallId": tool_call_id}
                })
        elif msg_type == "session.status":
            print(f"\n[状态] {msg['message'].get('sessionStatus')}")

    except websocket.WebSocketTimeoutException:
        continue
    except KeyboardInterrupt:
        break

ws.close()
```
