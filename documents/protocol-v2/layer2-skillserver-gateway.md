# Layer② 协议：Skill Server ↔ AI-Gateway

> 本文档基于代码实现逐项核对，确保协议描述与实际行为一致。

## 概述

| 方向 | 传输方式 | 端点 |
|---|---|---|
| Skill Server → Gateway | 内部 WebSocket（invoke 消息） | `ws://{gateway}/ws/skill` |
| Gateway → Skill Server | 内部 WebSocket（事件推送） | 同上连接 |
| Skill Server → Gateway | REST API (HTTP) | `{gateway}/api/gateway/agents` |

**认证方式**：
- WebSocket：`Sec-WebSocket-Protocol` 子协议传递 Base64 编码的 JSON 凭据
- REST API：`Authorization: Bearer {internal-token}` 请求头

> [!IMPORTANT]
> `welinkSessionId` 在所有 JSON 传输中必须编码为 **字符串**，禁止使用 JSON 数字类型。
> 因 JavaScript IEEE 754 对 Long 型大数存在精度丢失问题。

---

## 一、内部 WebSocket 连接

### 连接端点

```
ws://{gateway-host}/ws/skill
```

### 认证机制

连接时通过 WebSocket 子协议（`Sec-WebSocket-Protocol` 头）传递凭据。格式为固定前缀 `auth.` + Base64url 编码的 JSON：

```
auth.<base64url-no-padding>
```

Base64url 解码后的 JSON 内容：

```json
{
  "token": "sk-intl-9f2a7d3e4b1c",
  "source": "skill-server"
}
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `token` | string | ✅ | 内部认证令牌，由 `skill.gateway.internal-token` 配置 |
| `source` | string | ✅ | 固定值 `"skill-server"`，标识调用来源 |

**认证失败处理**：Gateway 关闭连接并在 close reason 中包含 `"invalid internal token"`，客户端收到后**不再重连**。

**代码**：[GatewayWSClient.java#buildAuthProtocol L143-L154](file:///D:/02_Lab/Projects/sandbox/opencode-CUI/skill-server/src/main/java/com/opencode/cui/skill/ws/GatewayWSClient.java#L143-L154)

---

### 连接管理

| 参数 | 配置项 | 默认值 | 说明 |
|---|---|---|---|
| WS 端点 | `skill.gateway.ws-url` | `ws://localhost:8081/ws/skill` | Gateway WebSocket 地址 |
| 认证令牌 | `skill.gateway.internal-token` | `sk-intl-9f2a7d3e4b1c` | 内部认证 Token |
| 重连初始延迟 | `skill.gateway.reconnect-initial-delay-ms` | `1000` | 首次重连等待（ms） |
| 重连最大延迟 | `skill.gateway.reconnect-max-delay-ms` | `30000` | 最大重连等待（ms） |

**重连策略**：
- **指数退避**：`delay = min(initialDelay × 2^(attempts-1), maxDelay)`
- **连接超时**：10 秒
- **心跳超时**：`connectionLostTimeout = 30` 秒
- **认证失败不重连**：close reason 包含 `"invalid internal token"` 时停止重连
- **生命周期**：Spring `@PostConstruct` 自动连接，`@PreDestroy` 优雅关闭

**代码**：[GatewayWSClient.java](file:///D:/02_Lab/Projects/sandbox/opencode-CUI/skill-server/src/main/java/com/opencode/cui/skill/ws/GatewayWSClient.java)

---

## 二、下行协议（Skill Server → Gateway）

所有下行指令通过内部 WebSocket 发送，统一使用 `invoke` 消息格式。

### 通用 invoke 消息结构

```json
{
  "type": "invoke",
  "ak": "agent-access-key",
  "source": "skill-server",
  "userId": "user-123",
  "welinkSessionId": "1234567890123456",
  "action": "chat",
  "payload": { ... }
}
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `type` | string | ✅ | 固定值 `"invoke"` |
| `ak` | string | ✅ | 目标 Agent 的 Access Key |
| `source` | string | ✅ | 固定值 `"skill-server"`，标识消息来源 |
| `userId` | string | ❌ | 发起操作的用户 ID，非空且非 blank 时传递 |
| `welinkSessionId` | string | ❌ | **仅 `create_session` 时传递**，字符串格式（防止 JS IEEE 754 精度丢失） |
| `action` | string | ✅ | 操作类型（见下方 Invoke-1 至 Invoke-7） |
| `payload` | object | ❌ | 操作载荷 JSON 对象；若 payload 字符串无法解析为 JSON，则降级为字符串传输 |

> `payload` 在发送前会尝试 `objectMapper.readTree(payload)` 解析为 JSON 节点嵌入；解析失败时以字符串形式 `message.put("payload", payload)` 传输。

**代码**：[GatewayRelayService.java#sendInvokeToGateway L85-L132](file:///D:/02_Lab/Projects/sandbox/opencode-CUI/skill-server/src/main/java/com/opencode/cui/skill/service/GatewayRelayService.java#L85-L132)

---

### Invoke-1：`create_session` — 创建 OpenCode 会话

```json
{
  "type": "invoke",
  "ak": "agent-key-001",
  "source": "skill-server",
  "userId": "user-123",
  "welinkSessionId": "1234567890123456",
  "action": "create_session",
  "payload": {
    "title": "我的代码助手"
  }
}
```

**Payload 字段**：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `title` | string | ❌ | 会话标题，为空或 blank 时 payload 为 `{}` |

**特殊字段**：消息顶层携带 `welinkSessionId`（字符串格式），仅此 action 在顶层传递。

**触发场景**（两种路径）：

1. **用户创建新会话**：`POST /api/skill/sessions` 请求中 `ak` 非空时触发
2. **Session 重建**：`toolSessionId` 失效后自动重建（见第五节）

**副作用**：Gateway 成功创建 OpenCode 会话后，回报 `session_created` 事件（见 Upstream-4）。

**代码**：
- 新建会话：[SkillSessionController.java L65-L72](file:///D:/02_Lab/Projects/sandbox/opencode-CUI/skill-server/src/main/java/com/opencode/cui/skill/controller/SkillSessionController.java#L65-L72)
- Rebuild 路径：[SessionRebuildService.java L94-L104](file:///D:/02_Lab/Projects/sandbox/opencode-CUI/skill-server/src/main/java/com/opencode/cui/skill/service/SessionRebuildService.java#L94-L104)
- Payload 构建：[SkillSessionController.java#buildCreateSessionPayload L190-L201](file:///D:/02_Lab/Projects/sandbox/opencode-CUI/skill-server/src/main/java/com/opencode/cui/skill/controller/SkillSessionController.java#L190-L201)

---

### Invoke-2：`chat` — 发送用户消息

```json
{
  "type": "invoke",
  "ak": "agent-key-001",
  "source": "skill-server",
  "userId": "user-123",
  "action": "chat",
  "payload": {
    "text": "帮我分析这段代码",
    "toolSessionId": "opencode-session-abc"
  }
}
```

**Payload 字段**：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `text` | string | ✅ | 用户消息文本内容 |
| `toolSessionId` | string | ❌ | OpenCode 侧的 session ID；非空时传递 |

**触发场景**（两种路径）：

1. **用户发送消息**：`POST /api/skill/sessions/{id}/messages`，`toolCallId` 为空时走此分支
2. **Session 重建后自动重发**：`session_created` 回调中检测到待重发消息时自动触发

**前置条件**：`session.ak != null && session.toolSessionId != null`。若 `toolSessionId` 为 null，走 rebuild 流程而非直接 chat。

**代码**：
- 消息发送：[SkillMessageController.java L120-L130](file:///D:/02_Lab/Projects/sandbox/opencode-CUI/skill-server/src/main/java/com/opencode/cui/skill/controller/SkillMessageController.java#L120-L130)
- Payload 构建：[SkillMessageController.java#buildChatPayload L293-L305](file:///D:/02_Lab/Projects/sandbox/opencode-CUI/skill-server/src/main/java/com/opencode/cui/skill/controller/SkillMessageController.java#L293-L305)
- 重建后重发：[GatewayRelayService.java L410-L421](file:///D:/02_Lab/Projects/sandbox/opencode-CUI/skill-server/src/main/java/com/opencode/cui/skill/service/GatewayRelayService.java#L410-L421)

---

### Invoke-3：`question_reply` — 回复交互式提问

```json
{
  "type": "invoke",
  "ak": "agent-key-001",
  "source": "skill-server",
  "userId": "user-123",
  "action": "question_reply",
  "payload": {
    "answer": "是的，请继续",
    "toolCallId": "call_abc123",
    "toolSessionId": "opencode-session-abc"
  }
}
```

**Payload 字段**：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `answer` | string | ✅ | 用户的回答文本 |
| `toolCallId` | string | ✅ | 对应的工具调用 ID（来自 `question` 事件的 `toolCallId`） |
| `toolSessionId` | string | ❌ | OpenCode 侧的 session ID；非空时传递 |

**触发场景**：`POST /api/skill/sessions/{id}/messages` 请求中 `toolCallId` 非空时走此分支。

**前置条件**：`session.ak != null && session.toolSessionId != null`

**代码**：
- 路由判断：[SkillMessageController.java L116-L119](file:///D:/02_Lab/Projects/sandbox/opencode-CUI/skill-server/src/main/java/com/opencode/cui/skill/controller/SkillMessageController.java#L116-L119)
- Payload 构建：[SkillMessageController.java#buildQuestionReplyPayload L310-L323](file:///D:/02_Lab/Projects/sandbox/opencode-CUI/skill-server/src/main/java/com/opencode/cui/skill/controller/SkillMessageController.java#L310-L323)

---

### Invoke-4：`permission_reply` — 回复权限请求

```json
{
  "type": "invoke",
  "ak": "agent-key-001",
  "source": "skill-server",
  "userId": "user-123",
  "action": "permission_reply",
  "payload": {
    "permissionId": "perm_xyz789",
    "response": "once",
    "toolSessionId": "opencode-session-abc"
  }
}
```

**Payload 字段**：

| 字段 | 类型 | 必填 | 合法值 |
|---|---|---|---|
| `permissionId` | string | ✅ | 被回复的权限请求 ID |
| `response` | string | ✅ | `once`（单次允许）/ `always`（始终允许）/ `reject`（拒绝） |
| `toolSessionId` | string | ❌ | OpenCode 侧的 session ID；非空时传递 |

**触发场景**：`POST /api/skill/sessions/{id}/permissions/{permId}` REST API。

**前置条件**：`session.ak != null && session.toolSessionId != null && !session.toolSessionId.isBlank()`

**副作用**：发送 invoke 后，同时推送 `permission.reply` StreamMessage 到前端 WS（确认回执）。

**代码**：
- 发送 invoke：[SkillMessageController.java L265-L271](file:///D:/02_Lab/Projects/sandbox/opencode-CUI/skill-server/src/main/java/com/opencode/cui/skill/controller/SkillMessageController.java#L265-L271)
- Payload 构建：[SkillMessageController.java#buildPermissionReplyPayload L328-L342](file:///D:/02_Lab/Projects/sandbox/opencode-CUI/skill-server/src/main/java/com/opencode/cui/skill/controller/SkillMessageController.java#L328-L342)
- WS 推送：[SkillMessageController.java L273-L279](file:///D:/02_Lab/Projects/sandbox/opencode-CUI/skill-server/src/main/java/com/opencode/cui/skill/controller/SkillMessageController.java#L273-L279)

---

### Invoke-5：`close_session` — 关闭会话

```json
{
  "type": "invoke",
  "ak": "agent-key-001",
  "source": "skill-server",
  "userId": "user-123",
  "action": "close_session",
  "payload": {
    "toolSessionId": "opencode-session-abc"
  }
}
```

**Payload 字段**：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `toolSessionId` | string | ✅ | 要关闭的 OpenCode session ID |

**触发场景**：`DELETE /api/skill/sessions/{id}` REST API。

**前置条件**：`session.ak != null && session.toolSessionId != null`

**副作用**：发送后在 SkillServer 侧将 session 状态改为 `CLOSED`。

**代码**：[SkillSessionController.java L126-L141](file:///D:/02_Lab/Projects/sandbox/opencode-CUI/skill-server/src/main/java/com/opencode/cui/skill/controller/SkillSessionController.java#L126-L141)

---

### Invoke-6：`abort_session` — 中止会话

```json
{
  "type": "invoke",
  "ak": "agent-key-001",
  "source": "skill-server",
  "userId": "user-123",
  "action": "abort_session",
  "payload": {
    "toolSessionId": "opencode-session-abc"
  }
}
```

**Payload 字段**：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `toolSessionId` | string | ✅ | 要中止的 OpenCode session ID |

**触发场景**：`POST /api/skill/sessions/{id}/abort` REST API。

**前置条件**：`session.ak != null && session.toolSessionId != null`

**副作用**：不改变 SkillServer 侧 session 状态（仅发送中止信号给 Gateway/OpenCode）。

**代码**：[SkillSessionController.java L166-L181](file:///D:/02_Lab/Projects/sandbox/opencode-CUI/skill-server/src/main/java/com/opencode/cui/skill/controller/SkillSessionController.java#L166-L181)

---

### Invoke-7：`request_recovery` — 请求消息恢复

```json
{
  "type": "invoke",
  "ak": "agent-key-001",
  "source": "skill-server",
  "userId": "user-123",
  "action": "request_recovery",
  "payload": {
    "fromSequence": 42,
    "sessionId": "1234567890123456"
  }
}
```

**Payload 字段**：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `fromSequence` | long | ✅ | 请求从此序号之后恢复缺失的消息 |
| `sessionId` | string | ✅ | welinkSessionId |

**触发场景**：内部方法 `requestRecovery()`，用于缺失消息检测后的恢复请求（当前代码中定义但未暴露外部触发路径）。

**前置条件**：`session.ak != null`

**代码**：[GatewayRelayService.java#requestRecovery L534-L560](file:///D:/02_Lab/Projects/sandbox/opencode-CUI/skill-server/src/main/java/com/opencode/cui/skill/service/GatewayRelayService.java#L534-L560)

---

### 下行 Action 路由总览

| 用户操作 | 前置条件 | Action | Payload 关键字段 | 触发源 |
|---|---|---|---|---|
| 创建会话 | `ak` 非空 | `create_session` | `title` | `SkillSessionController` |
| 发送消息 | `ak` + `toolSessionId` 存在 | `chat` | `text`, `toolSessionId` | `SkillMessageController` |
| 发送消息 | `ak` 存在, `toolSessionId` 为 null | `create_session` → `chat` | rebuild 流程 | `SessionRebuildService` |
| 回复提问 | `toolCallId` 非空 | `question_reply` | `answer`, `toolCallId`, `toolSessionId` | `SkillMessageController` |
| 回复权限 | `ak` + `toolSessionId` 存在 | `permission_reply` | `permissionId`, `response`, `toolSessionId` | `SkillMessageController` |
| 关闭会话 | `ak` + `toolSessionId` 存在 | `close_session` | `toolSessionId` | `SkillSessionController` |
| 中止会话 | `ak` + `toolSessionId` 存在 | `abort_session` | `toolSessionId` | `SkillSessionController` |
| 消息恢复 | `ak` 存在 | `request_recovery` | `fromSequence`, `sessionId` | `GatewayRelayService` 内部 |
| 重建后重发 | `session_created` 后有待发消息 | `chat` | `text`, `toolSessionId`（新） | `GatewayRelayService` |

---

## 三、上行协议（Gateway → Skill Server）

Gateway 通过同一内部 WebSocket 连接推送事件到 Skill Server。消息为 JSON 格式，通过 `type` 字段区分事件类型。

### 消息路由与 Session 关联

上行消息到达 `GatewayRelayService.handleGatewayMessage()` 后：

1. **解析顶层字段**：`type`、`ak`（回退 `agentId`）、`userId`
2. **Session 关联**（仅 `tool_event` / `tool_done` / `tool_error` / `permission_request` 需要）：
   - 优先取 `welinkSessionId` 字段
   - 回退：取 `toolSessionId` → DB 查库 `sessionService.findByToolSessionId()` 映射到 `welinkSessionId`
   - 若两者都无法解析 → 丢弃消息并记录 warn 日志

**代码**：[GatewayRelayService.java#handleGatewayMessage L140-L174](file:///D:/02_Lab/Projects/sandbox/opencode-CUI/skill-server/src/main/java/com/opencode/cui/skill/service/GatewayRelayService.java#L140-L174)、[resolveSessionId L181-L210](file:///D:/02_Lab/Projects/sandbox/opencode-CUI/skill-server/src/main/java/com/opencode/cui/skill/service/GatewayRelayService.java#L181-L210)

---

### 上行事件类型总览

| 事件类型 | 需要 Session 关联 | 用途 | SkillServer 处理 |
|---|---|---|---|
| `tool_event` | ✅ | OpenCode 事件流中继 | 翻译 → 广播 → 缓冲 → 持久化 |
| `tool_done` | ✅ | 工具执行完成/会话空闲 | 广播 idle → 清缓冲 |
| `tool_error` | ✅ | 工具执行/会话错误 | 错误处理 / session 重建 |
| `session_created` | ❌ | OpenCode 会话已创建 | 回填 toolSessionId → 重发消息 |
| `agent_online` | ❌ | Agent 上线通知 | 广播到关联 session |
| `agent_offline` | ❌ | Agent 下线通知 | 广播到关联 session |
| `permission_request` | ✅ | 权限请求（Gateway 中转路径） | 翻译 → 广播到前端 |

---

### Upstream-1：`tool_event` — OpenCode 事件流

**消息结构**：

```json
{
  "type": "tool_event",
  "ak": "agent-key-001",
  "userId": "user-123",
  "welinkSessionId": "1234567890123456",
  "toolSessionId": "opencode-session-abc",
  "event": {
    "type": "message.part.updated",
    "properties": {
      "sessionID": "opencode-session-abc",
      "messageID": "msg-001",
      "part": { "id": "part-001", "type": "text", "text": "Hello" },
      "delta": "Hello"
    }
  }
}
```

**顶层字段**：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `type` | string | ✅ | 固定 `"tool_event"` |
| `ak` / `agentId` | string | ❌ | Agent 标识（优先取 `ak`，回退 `agentId`） |
| `userId` | string | ❌ | 用户标识 |
| `welinkSessionId` | string | ❌ | Welink 会话 ID（优先级最高的 session 标识） |
| `toolSessionId` | string | ❌ | OpenCode 会话 ID（用于 DB 反查 welinkSessionId） |
| `event` | object | ✅ | **OpenCode 原始事件对象**，包含 `type` 和 `properties` |

**OpenCode 事件翻译**：`event` 字段由 `OpenCodeEventTranslator.translate()` 处理，按 `event.type` 分发：

| OpenCode 事件 `type` | 翻译后的 StreamMessage `type` | 说明 |
|---|---|---|
| `message.part.updated` (text) | `text.delta` / `text.done` | 有 `delta` → delta，否则 → done |
| `message.part.updated` (reasoning) | `thinking.delta` / `thinking.done` | 同上逻辑，`reasoning` 映射为 `thinking` |
| `message.part.updated` (tool) | `tool.update` 或 `question` | `toolName=="question" && status=="running"` → question |
| `message.part.updated` (step-start) | `step.start` | 步骤开始 |
| `message.part.updated` (step-finish) | `step.done` | 步骤完成（含 tokens/cost/reason） |
| `message.part.updated` (file) | `file` | 文件附件 |
| `message.part.delta` | `text.delta` / `thinking.delta` | 纯增量流（按已缓存的 partType 分发） |
| `message.part.removed` | *(缓存清理，不广播)* | 清除 part 追踪缓存 |
| `message.updated` (带 finish) | `step.done` | 消息完成（含 reason） |
| `message.updated` (无 finish) | *(忽略)* | 仅记录 role，不广播 |
| `session.status` | `session.status` | 映射状态值（见下方状态映射表） |
| `session.idle` | `session.status` (idle) | 全局空闲信号，同时清除 session 缓存 |
| `session.updated` | `session.title` | 提取 `info.title` 或 `title` |
| `session.error` | `session.error` | 会话级错误 |
| `permission.updated` / `permission.asked` | `permission.ask` | 权限请求 |
| `question.asked` | `question` | 直接推送的交互式提问 |

> 未列出的 OpenCode 事件类型在 `default` 分支中被忽略并记录 debug 日志。
>
> `role == "user"` 的消息会被 `shouldIgnoreMessage()` 过滤，不广播到前端。

**Session 状态映射**（`normalizeSessionStatus()`）：

| OpenCode 原始值 | 映射后 | 含义 |
|---|---|---|
| `idle` / `completed` | `idle` | AI 空闲，可接收新消息 |
| `active` / `running` / `busy` | `busy` | AI 正在处理中 |
| `reconnecting` / `retry` / `recovering` | `retry` | 连接恢复中 |

**副作用**（按顺序执行）：

1. **激活 IDLE session**：首次 `tool_event` 将 `IDLE` session 改为 `ACTIVE`，广播 `session.status = "busy"`
2. **翻译事件**：调用 `OpenCodeEventTranslator.translate(event)` 生成 `StreamMessage`
3. **广播**：通过 Redis `user-stream:{userId}` 广播到所有 Skill 实例
4. **缓冲累积**：`bufferService.accumulate()` 存入 Redis（供 WS 重连 resume 使用）
5. **持久化终态**：`persistenceService.persistIfFinal()` 持久化 `text.done` / `tool.update(completed)` 等终态

**代码**：[GatewayRelayService.java#handleToolEvent L221-L268](file:///D:/02_Lab/Projects/sandbox/opencode-CUI/skill-server/src/main/java/com/opencode/cui/skill/service/GatewayRelayService.java#L221-L268)、[OpenCodeEventTranslator.java#translate L40-L75](file:///D:/02_Lab/Projects/sandbox/opencode-CUI/skill-server/src/main/java/com/opencode/cui/skill/service/OpenCodeEventTranslator.java#L40-L75)

---

### Upstream-2：`tool_done` — 工具流结束

**消息结构**：

```json
{
  "type": "tool_done",
  "ak": "agent-key-001",
  "userId": "user-123",
  "welinkSessionId": "1234567890123456",
  "toolSessionId": "opencode-session-abc"
}
```

**字段**（同 `tool_event` 顶层，无 `event` 字段）：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `type` | string | ✅ | 固定 `"tool_done"` |
| `ak` / `agentId` | string | ❌ | Agent 标识 |
| `userId` | string | ❌ | 用户标识 |
| `welinkSessionId` | string | ❌ | Welink 会话 ID |
| `toolSessionId` | string | ❌ | OpenCode 会话 ID |

**SkillServer 处理**：

1. 广播 `session.status` = `"idle"` StreamMessage 到前端
2. `bufferService.accumulate()` — 清除流式缓冲区
3. `persistenceService.persistIfFinal()` — 持久化 idle 状态

**代码**：[GatewayRelayService.java#handleToolDone L270-L292](file:///D:/02_Lab/Projects/sandbox/opencode-CUI/skill-server/src/main/java/com/opencode/cui/skill/service/GatewayRelayService.java#L270-L292)

---

### Upstream-3：`tool_error` — 工具/会话错误

**消息结构**：

```json
{
  "type": "tool_error",
  "ak": "agent-key-001",
  "userId": "user-123",
  "welinkSessionId": "1234567890123456",
  "toolSessionId": "opencode-session-abc",
  "error": "session not found: opencode-session-abc",
  "reason": "session_not_found"
}
```

**字段**：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `type` | string | ✅ | 固定 `"tool_error"` |
| `ak` / `agentId` | string | ❌ | Agent 标识 |
| `userId` | string | ❌ | 用户标识 |
| `welinkSessionId` | string | ❌ | Welink 会话 ID |
| `toolSessionId` | string | ❌ | OpenCode 会话 ID |
| `error` | string | ✅ | 错误描述（默认 `"Unknown error"`） |
| `reason` | string | ❌ | 错误原因代码，如 `"session_not_found"` |

**SkillServer 处理**（两条分支）：

**分支 A — Session 失效（触发重建）**：

当 `reason == "session_not_found"` 或 `error` 文本匹配以下模式时触发：

| 匹配模式（大小写不敏感） | 说明 |
|---|---|
| `"not found"` | 通用 session 无效 |
| `"session_not_found"` | 明确 session 无效 |
| `"json parse error"` | 协议解析失败（可能 session 已被清理） |
| `"unexpected eof"` | 连接异常中断 |

处理流程 → 进入 Session 重建（见第五节）。

**分支 B — 一般错误**：

1. 保存系统消息到 DB：`"Error: " + error`
2. 广播 `error` StreamMessage 到前端
3. 关闭当前 assistant 轮次：`persistenceService.finalizeActiveAssistantTurn()`

**代码**：[GatewayRelayService.java#handleToolError L294-L325](file:///D:/02_Lab/Projects/sandbox/opencode-CUI/skill-server/src/main/java/com/opencode/cui/skill/service/GatewayRelayService.java#L294-L325)、[isSessionInvalidError L332-L340](file:///D:/02_Lab/Projects/sandbox/opencode-CUI/skill-server/src/main/java/com/opencode/cui/skill/service/GatewayRelayService.java#L332-L340)

---

### Upstream-4：`session_created` — OpenCode 会话已创建

**消息结构**：

```json
{
  "type": "session_created",
  "ak": "agent-key-001",
  "userId": "user-123",
  "welinkSessionId": "1234567890123456",
  "toolSessionId": "opencode-session-abc"
}
```

**字段**：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `type` | string | ✅ | 固定 `"session_created"` |
| `ak` | string | ❌ | Agent 标识 |
| `userId` | string | ❌ | 用户标识 |
| `welinkSessionId` | string | ✅ | Skill 侧会话 ID（字符串格式） |
| `toolSessionId` | string | ✅ | OpenCode 分配的 session ID |

> 若 `welinkSessionId` 或 `toolSessionId` 为 null，则记录 warn 日志并丢弃。

**SkillServer 处理**：

1. **回填 toolSessionId**：`sessionService.updateToolSessionId(sessionId, toolSessionId)`
2. **检查待重发消息**：`rebuildService.consumePendingMessage(sessionId)`
3. **若有待重发消息**：
   - 构建 `chat` payload（`text` = 待重发内容, `toolSessionId` = 新 toolSessionId）
   - 发送 `chat` invoke 到 Gateway
   - 广播 `session.status = "busy"` 到前端
4. **异常处理**：更新失败时清除待重发消息 `rebuildService.clearPendingMessage()`

**代码**：[GatewayRelayService.java#handleSessionCreated L391-L430](file:///D:/02_Lab/Projects/sandbox/opencode-CUI/skill-server/src/main/java/com/opencode/cui/skill/service/GatewayRelayService.java#L391-L430)

---

### Upstream-5：`agent_online` — Agent 上线

**消息结构**：

```json
{
  "type": "agent_online",
  "ak": "agent-key-001",
  "userId": "user-123",
  "toolType": "opencode",
  "toolVersion": "0.3.0"
}
```

**字段**：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `type` | string | ✅ | 固定 `"agent_online"` |
| `ak` | string | ❌ | Agent 标识 |
| `userId` | string | ❌ | 用户标识 |
| `toolType` | string | ❌ | 工具类型（默认 `"UNKNOWN"`） |
| `toolVersion` | string | ❌ | 工具版本（默认 `"UNKNOWN"`） |

**SkillServer 处理**：

1. 查找该 `ak` 关联的所有 session：`sessionService.findByAk(ak)`
2. 对每个 session 广播 `agent.online` StreamMessage
3. `userId` 优先取消息中的值，回退取 session 的 userId

**广播的 StreamMessage**：

```json
{
  "type": "agent.online",
  "welinkSessionId": "<session-id>",
  "seq": 42
}
```

> 无 `emittedAt`、`role` 等字段。`seq` 和 `welinkSessionId` 由广播层动态注入。

**代码**：[GatewayRelayService.java#handleAgentOnline L365-L377](file:///D:/02_Lab/Projects/sandbox/opencode-CUI/skill-server/src/main/java/com/opencode/cui/skill/service/GatewayRelayService.java#L365-L377)

---

### Upstream-6：`agent_offline` — Agent 下线

**消息结构**：

```json
{
  "type": "agent_offline",
  "ak": "agent-key-001",
  "userId": "user-123"
}
```

**字段**：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `type` | string | ✅ | 固定 `"agent_offline"` |
| `ak` | string | ❌ | Agent 标识 |
| `userId` | string | ❌ | 用户标识 |

**SkillServer 处理**：与 `agent_online` 相同逻辑，广播 `agent.offline` StreamMessage 到关联 session。

**代码**：[GatewayRelayService.java#handleAgentOffline L379-L389](file:///D:/02_Lab/Projects/sandbox/opencode-CUI/skill-server/src/main/java/com/opencode/cui/skill/service/GatewayRelayService.java#L379-L389)

---

### Upstream-7：`permission_request` — 权限请求（Gateway 中转路径）

**消息结构**：

```json
{
  "type": "permission_request",
  "ak": "agent-key-001",
  "userId": "user-123",
  "welinkSessionId": "1234567890123456",
  "toolSessionId": "opencode-session-abc",
  "permissionId": "perm_xyz789",
  "permType": "file-edit",
  "command": "edit /src/main.ts",
  "messageId": "msg-002",
  "metadata": {
    "path": "/src/main.ts"
  }
}
```

**字段**：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `type` | string | ✅ | 固定 `"permission_request"` |
| `ak` / `agentId` | string | ❌ | Agent 标识 |
| `userId` | string | ❌ | 用户标识 |
| `welinkSessionId` | string | ❌ | Welink 会话 ID |
| `toolSessionId` | string | ❌ | OpenCode 会话 ID |
| `permissionId` | string | ✅ | 权限请求唯一 ID |
| `permType` | string | ✅ | 权限类型（如 `file-edit`, `bash`） |
| `command` | string | ❌ | 命令描述（映射为 StreamMessage 的 `title`） |
| `messageId` | string | ❌ | 所属消息 ID |
| `metadata` | object | ❌ | 权限相关元数据（如 `{"path": "/src/main.ts"}`） |

> 与 `tool_event` 中内嵌的 `permission.updated` / `permission.asked` 事件不同，这是 Gateway 直接中转的权限请求路径。

**翻译结果**（`translatePermissionFromGateway()`）：

| StreamMessage 字段 | 来源映射 |
|---|---|
| `type` | 固定 `"permission.ask"` |
| `messageId` | `node.messageId` |
| `permissionId` | `node.permissionId` |
| `permType` | `node.permType` |
| `title` | `node.command` |
| `metadata` | `node.metadata` |

**代码**：[GatewayRelayService.java#handlePermissionRequest L436-L447](file:///D:/02_Lab/Projects/sandbox/opencode-CUI/skill-server/src/main/java/com/opencode/cui/skill/service/GatewayRelayService.java#L436-L447)、[OpenCodeEventTranslator.java#translatePermissionFromGateway L80-L89](file:///D:/02_Lab/Projects/sandbox/opencode-CUI/skill-server/src/main/java/com/opencode/cui/skill/service/OpenCodeEventTranslator.java#L80-L89)

---

## 四、REST API（Skill Server → Gateway）

### API-GW-1：查询在线 Agent 列表

```
GET {gateway-base-url}/api/gateway/agents?userId={userId}
```

**配置**：

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `skill.gateway.api-base-url` | `http://localhost:8081` | Gateway REST 基础 URL |
| `skill.gateway.internal-token` | `sk-intl-9f2a7d3e4b1c` | 认证令牌 |

**请求 Header**：

```
Authorization: Bearer sk-intl-9f2a7d3e4b1c
```

**Query 参数**：

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `userId` | string | ✅ | 按用户 ID 过滤 |

**响应结构**：

```json
{
  "data": [
    {
      "ak": "agent-key-001",
      "status": "ONLINE",
      "deviceName": "My-MacBook-Pro",
      "os": "macOS",
      "toolType": "opencode",
      "toolVersion": "0.3.0",
      "connectedAt": "2024-03-13T06:00:00Z"
    }
  ]
}
```

**响应 `data` 数组中每个元素的字段**：

| 字段 | 类型 | 必返回 | 说明 |
|---|---|---|---|
| `ak` | string | ✅ | Agent Access Key，唯一标识 |
| `status` | string | ❌ | 连接状态，如 `ONLINE` |
| `deviceName` | string | ❌ | 设备名称 |
| `os` | string | ❌ | 操作系统（如 `Windows`, `macOS`, `Linux`） |
| `toolType` | string | ❌ | 工具类型（小写，如 `opencode`） |
| `toolVersion` | string | ❌ | 工具版本号 |
| `connectedAt` | string | ❌ | 连接时间（ISO 8601） |

> 字段来自 Gateway API 透传，除 `ak` 外其他字段取决于 Agent 上报的注册信息。
> Skill Server 通过 `GatewayApiClient.toAgentSummary()` 将 `Map<String, Object>` 转换为类型化的 `AgentSummary` DTO。

**错误处理**：任何异常（网络错误、非 2xx 状态码、JSON 解析失败）均返回空列表 `Collections.emptyList()`。

**调用方**：`AgentQueryController.getOnlineAgents()` — 被 Miniapp 通过 `GET /api/skill/agents` 调用，作为 Gateway 查询的代理层。

**代码**：[GatewayApiClient.java#getOnlineAgentsByUserId L48-L81](file:///D:/02_Lab/Projects/sandbox/opencode-CUI/skill-server/src/main/java/com/opencode/cui/skill/service/GatewayApiClient.java#L48-L81)、[AgentQueryController.java](file:///D:/02_Lab/Projects/sandbox/opencode-CUI/skill-server/src/main/java/com/opencode/cui/skill/controller/AgentQueryController.java)

---

### API-GW-2：校验 AK 归属

`GatewayApiClient.isAkOwnedByUser(ak, userId)` — 内部方法，通过查询在线 Agent 列表验证指定 `ak` 是否属于指定用户。

**逻辑**：获取 `getOnlineAgentsByUserId(userId)` 返回列表 → 检查是否包含匹配的 `ak`。

**代码**：[GatewayApiClient.java#isAkOwnedByUser L109-L119](file:///D:/02_Lab/Projects/sandbox/opencode-CUI/skill-server/src/main/java/com/opencode/cui/skill/service/GatewayApiClient.java#L109-L119)

---

## 五、Session 重建流程

当 OpenCode 侧的 `toolSessionId` 失效时（被清理、过期、进程重启等），Skill Server 自动检测并透明重建。

### 触发条件

| 触发源 | 触发条件 | 代码位置 |
|---|---|---|
| `tool_error` 上行事件 | `reason == "session_not_found"` 或 `error` 文本匹配无效模式 | `GatewayRelayService#handleToolError` |
| 用户发消息 API | `session.toolSessionId == null` | `SkillMessageController#sendMessage` |

### 重建时序

```mermaid
sequenceDiagram
    participant FE as Miniapp 前端
    participant SS as Skill Server
    participant GW as AI-Gateway

    Note over SS: 检测到 session 失效

    rect rgb(255, 245, 238)
        Note right of SS: 阶段 1: 准备
        SS->>SS: clearToolSessionId()<br/>清除无效 toolSessionId
        SS->>SS: 缓存待重发消息<br/>(Caffeine Cache, 5min TTL, max 1000)
    end

    rect rgb(240, 248, 255)
        Note right of SS: 阶段 2: 通知 + 重建
        SS->>FE: 广播 session.status = "retry"
        SS->>GW: invoke: create_session<br/>(带 welinkSessionId + title)
    end

    rect rgb(245, 255, 245)
        Note right of SS: 阶段 3: 回调处理
        GW-->>SS: session_created<br/>(返回新 toolSessionId)
        SS->>SS: updateToolSessionId(新值)
        SS->>SS: consumePendingMessage()
        alt 有待重发消息
            SS->>GW: invoke: chat<br/>(text=待重发, toolSessionId=新值)
            SS->>FE: 广播 session.status = "busy"
        end
    end
```

### 待重发消息管理

| 配置 | 值 | 说明 |
|---|---|---|
| 存储方式 | Caffeine Cache | 本地内存缓存 |
| 过期时间 | 5 分钟 (`expireAfterWrite`) | 防止 rebuild 失败后内存泄漏 |
| 最大容量 | 1,000 条 | 超出时按 LRU 淘汰 |

**消息来源**：
- `tool_error` 触发：从 DB 查最后一条用户消息 `messageRepository.findLastUserMessage()`
- 用户发消息触发：直接使用请求中的 `content`

**代码**：[SessionRebuildService.java](file:///D:/02_Lab/Projects/sandbox/opencode-CUI/skill-server/src/main/java/com/opencode/cui/skill/service/SessionRebuildService.java)

---

## 六、广播内部机制

所有从 Gateway 上行的事件，经翻译和丰富后，通过 Redis Pub/Sub 广播到所有 Skill Server 实例：

### 广播信封结构

```json
{
  "sessionId": "1234567890123456",
  "userId": "user-123",
  "message": {
    "type": "text.delta",
    "welinkSessionId": "1234567890123456",
    "emittedAt": "2024-03-13T06:00:00.000Z",
    "messageId": "msg-001",
    "sourceMessageId": "msg-001",
    "role": "assistant",
    "partId": "part-001",
    "partSeq": 1,
    "content": "Hello"
  }
}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `sessionId` | string | 用于路由到正确的前端 session |
| `userId` | string | 用于路由到正确的用户 WS 连接 |
| `message` | object | 完整的 `StreamMessage` 对象（`@JsonInclude(NON_NULL)`） |

### 字段丰富逻辑（`enrichStreamMessage`）

在广播前对每条 `StreamMessage` 进行丰富：

1. **注入 sessionId**：`msg.sessionId` + `msg.welinkSessionId` = welinkSessionId
2. **注入 emittedAt**：非排除类型且为空时填充 `Instant.now().toString()`
3. **准备消息上下文**：`persistenceService.prepareMessageContext()` 关联 DB 消息记录

**emittedAt 排除类型**（这些类型不含 `emittedAt`）：

| 类型 | 说明 |
|---|---|
| `permission.reply` | 直接构造 |
| `agent.online` | 仅 type + seq |
| `agent.offline` | 仅 type + seq |
| `error` | 仅 type + error |

**userId 解析优先级**：
1. 消息携带的 `userId` hint
2. 回退：从 DB 查 session 的 userId

**代码**：[GatewayRelayService.java#broadcastStreamMessage L477-L497](file:///D:/02_Lab/Projects/sandbox/opencode-CUI/skill-server/src/main/java/com/opencode/cui/skill/service/GatewayRelayService.java#L477-L497)、[enrichStreamMessage L514-L529](file:///D:/02_Lab/Projects/sandbox/opencode-CUI/skill-server/src/main/java/com/opencode/cui/skill/service/GatewayRelayService.java#L514-L529)
