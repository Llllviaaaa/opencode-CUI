# 广播路由 User 级升级：完整端到端链路文档

> 日期：2026-03-13  
> 视角：用户在 Miniapp 发消息 → 全链路穿透 → 收到 AI 回复

---

## 全链路总览

```
━━━━━━━━━━━━ 下行链路：用户发消息 ━━━━━━━━━━━━

  ❶ 前端 useSkillStream.sendMessage()
     │  乐观更新 UI
     ↓
  ❷ api.sendMessage() → HTTP POST
     │  POST /api/skill/sessions/{id}/messages
     ↓
  ❸ SkillMessageController
     │  权限校验 → 消息持久化 → 构建 invoke payload
     ↓
  ❹ GatewayRelayService.sendInvokeToGateway()
     │  封装 invoke WS 报文 → GatewayWSClient.sendToGateway()
     ↓
  ❺ [Gateway] SkillWebSocketHandler → SkillRelayService
     │  source 验证 → userId 校验 → 绑定 source → Redis PUBLISH agent:{ak}
     ↓
  ❻ [Gateway] EventRelayService → AgentWebSocketHandler
     │  Redis 订阅回调 → WS 转发给 PCAgent
     ↓
  ❼ PCAgent → OpenCode SDK
     │  prompt() 触发 AI 开始处理

━━━━━━━━━━━━ 上行链路：AI 回复推送 ━━━━━━━━━━━━

  ❽ PCAgent → [Gateway] AgentWebSocketHandler
     │  tool_event/tool_done/tool_error 分发
     ↓
  ❾ [Gateway] EventRelayService.relayToSkillServer()
     │  注入 ak/userId/source → SkillRelayService 路由
     ↓
  ❿ [Gateway] SkillRelayService.relayToSkill()
     │  本地 WS 直发 or Redis relay 跨实例
     ↓
  ⓫ [Skill] GatewayWSClient.onMessage()
     │  → GatewayRelayService.handleGatewayMessage()
     │  → 解析/翻译/持久化/缓冲/广播
     ↓
  ⓬ [Skill] broadcastStreamMessage() → Redis PUBLISH
     │  信封封装 → user-stream:{userId}
     ↓
  ⓭ [Skill] SkillStreamHandler.handleUserBroadcast()
     │  Redis 回调 → 解包 → seq 分配 → WS 推送
     ↓
  ⓮ 前端 handleStreamMessage()
     │  按 welinkSessionId 路由 → 按 type 分发渲染
     ↓
  ═══ 用户看到 AI 回复 ═══
```

---

# 下行链路

## ❶ 前端 `useSkillStream.sendMessage()`

**模块**：[useSkillStream.ts](file:///d:/02_Lab/Projects/sandbox/opencode-CUI/skill-miniapp/src/hooks/useSkillStream.ts#L635-L667)

```
sendMessageFn(text="请帮我优化这段代码")
  ├── finalizeAllStreamingMessages()      // 结束进行中的流式消息
  ├── 乐观创建临时消息并立即渲染
  │     { id: "user_1710302848000_1", role: "user", content: "请帮我优化这段代码" }
  └── 异步调用 api.sendMessage(sessionId, text)
```

---

## ❷ `api.sendMessage()` → HTTP POST

**模块**：[api.ts](file:///d:/02_Lab/Projects/sandbox/opencode-CUI/skill-miniapp/src/utils/api.ts#L267-L276)

```http
POST /api/skill/sessions/10001/messages
Cookie: userId=user-42
Content-Type: application/json

{ "content": "请帮我优化这段代码" }
```

回复交互场景（`question_reply`）：
```json
{ "content": "Yes", "toolCallId": "call-abc-123" }
```

**HTTP 响应**：
```json
{
  "code": 0,
  "data": {
    "id": "msg-db-001",
    "role": "user",
    "content": "请帮我优化这段代码",
    "createdAt": "2026-03-13T06:00:00Z"
  }
}
```

前端用持久化消息替换 ❶ 的临时消息。

---

## ❸ `SkillMessageController.sendMessage()`

**模块**：[SkillMessageController.java](file:///d:/02_Lab/Projects/sandbox/opencode-CUI/skill-server/src/main/java/com/opencode/cui/skill/controller/SkillMessageController.java#L75-L139)

```
sendMessage(userId="user-42", sessionId="10001", request)
  │
  ├── 1. 权限校验 (SessionAccessControlService)
  │      requireSessionAccess(10001, "user-42")
  │        ├── session.userId == "user-42"? ✅
  │        └── gatewayApiClient.isAkOwnedByUser(ak, "user-42")? ✅
  │
  ├── 2. 消息持久化
  │      persistenceService.finalizeActiveAssistantTurn(10001)
  │        → 关闭上一轮未结束的 assistant 消息
  │      messageService.saveUserMessage(10001, "请帮我优化这段代码")
  │        → INSERT INTO skill_messages
  │
  ├── 3. invoke 路由判断
  │      session.ak = "agent-key-123" ✅
  │      session.toolSessionId = "ts-abc-def" ✅
  │      request.toolCallId 为 null → action = "chat"
  │
  └── 4. 发送 invoke
         gatewayRelayService.sendInvokeToGateway(
           ak="agent-key-123", userId="user-42",
           sessionId="10001", action="chat",
           payload = '{"text":"请帮我优化这段代码","toolSessionId":"ts-abc-def"}'
         )
```

---

## ❹ `GatewayRelayService.sendInvokeToGateway()`

**模块**：[GatewayRelayService.java](file:///d:/02_Lab/Projects/sandbox/opencode-CUI/skill-server/src/main/java/com/opencode/cui/skill/service/GatewayRelayService.java#L85-L132) → [GatewayWSClient.java](file:///d:/02_Lab/Projects/sandbox/opencode-CUI/skill-server/src/main/java/com/opencode/cui/skill/ws/GatewayWSClient.java#L84-L97)

**构建的 WS 报文**：
```json
{
  "type": "invoke",
  "ak": "agent-key-123",
  "source": "skill-server",
  "userId": "user-42",
  "action": "chat",
  "payload": {
    "text": "请帮我优化这段代码",
    "toolSessionId": "ts-abc-def"
  }
}
```

通过 `GatewayWSClient` 的 WS 长连接发送到 Gateway：
```
Skill Server ──WS──▶ AI-Gateway (ws://<gateway>/ws/skill)
                     认证: Sec-WebSocket-Protocol → auth.<base64({token, source:"skill-server"})>
```

---

## ❺ [Gateway] `SkillWebSocketHandler` → `SkillRelayService`

**模块**：[SkillWebSocketHandler.java](file:///d:/02_Lab/Projects/sandbox/opencode-CUI/ai-gateway/src/main/java/com/opencode/cui/gateway/ws/SkillWebSocketHandler.java#L69-L86) → [SkillRelayService.java](file:///d:/02_Lab/Projects/sandbox/opencode-CUI/ai-gateway/src/main/java/com/opencode/cui/gateway/service/SkillRelayService.java#L113-L155)

### 5.1 `SkillWebSocketHandler.handleTextMessage()`

```
收到 WS 报文 → 反序列化为 GatewayMessage
type == "invoke"? ✅
→ skillRelayService.handleInvokeFromSkill(session, message)
```

### 5.2 `SkillRelayService.handleInvokeFromSkill()`

```
handleInvokeFromSkill(session, message)
  │
  ├── 1. 生成 traceId (UUID)
  │      message.traceId = "550e8400-e29b-..."
  │
  ├── 2. source 验证
  │      boundSource（握手时绑定）= "skill-server"
  │      message.source = "skill-server"
  │      boundSource == messageSource? ✅
  │
  ├── 3. userId 校验
  │      expectedUserId = redisMessageBroker.getAgentUser("agent-key-123")
  │        → Redis GET gw:agent:user:agent-key-123 → "user-42"
  │      message.userId == expectedUserId? ✅
  │
  ├── 4. 绑定 source
  │      redisMessageBroker.bindAgentSource("agent-key-123", "skill-server")
  │        → Redis SET gw:agent:source:agent-key-123 = "skill-server"
  │
  └── 5. 发布到 Agent Redis 频道
         redisMessageBroker.publishToAgent("agent-key-123", message.withoutRoutingContext())
           → Redis PUBLISH agent:agent-key-123
```

**发到 Redis 的报文**（去除 userId/source 路由上下文）：
```json
{
  "type": "invoke",
  "ak": "agent-key-123",
  "traceId": "550e8400-e29b-...",
  "action": "chat",
  "payload": {
    "text": "请帮我优化这段代码",
    "toolSessionId": "ts-abc-def"
  }
}
```

---

## ❻ [Gateway] `EventRelayService` → PCAgent WS

**模块**：[EventRelayService.java](file:///d:/02_Lab/Projects/sandbox/opencode-CUI/ai-gateway/src/main/java/com/opencode/cui/gateway/service/EventRelayService.java#L124-L143) → [AgentWebSocketHandler.java](file:///d:/02_Lab/Projects/sandbox/opencode-CUI/ai-gateway/src/main/java/com/opencode/cui/gateway/ws/AgentWebSocketHandler.java)

### 6.1 Redis 订阅回调

```
Agent 注册时已订阅: redisMessageBroker.subscribeToAgent("agent-key-123", handler)
  → Redis SUBSCRIBE agent:agent-key-123

收到 ❺ 发布的消息 → sendToLocalAgent("agent-key-123", message)
  │
  ├── agentSessions.get("agent-key-123") → wsSession（PCAgent WS 连接）
  │
  └── wsSession.sendMessage(new TextMessage(json))
```

### 6.2 PCAgent 收到的 WS 报文

```json
{
  "type": "invoke",
  "ak": "agent-key-123",
  "traceId": "550e8400-e29b-...",
  "action": "chat",
  "payload": {
    "text": "请帮我优化这段代码",
    "toolSessionId": "ts-abc-def"
  }
}
```

---

## ❼ PCAgent → OpenCode

```
PCAgent 收到 invoke(action="chat")
  → 提取 toolSessionId → 找到对应 OpenCode session
  → openCodeSession.prompt("请帮我优化这段代码")
  → OpenCode 开始 LLM 推理，产生流式 SSE 事件
  → PCAgent 将每个 SSE event 包装为 tool_event 发回 Gateway
```

---

# 上行链路

## ❽ PCAgent → [Gateway] `AgentWebSocketHandler`

**模块**：[AgentWebSocketHandler.java](file:///d:/02_Lab/Projects/sandbox/opencode-CUI/ai-gateway/src/main/java/com/opencode/cui/gateway/ws/AgentWebSocketHandler.java#L338-L351)

**PCAgent 发送的 WS 报文**：
```json
{
  "type": "tool_event",
  "toolSessionId": "ts-abc-def",
  "welinkSessionId": "10001",
  "event": {
    "type": "message.part.delta",
    "properties": {
      "sessionID": "oc-session-xyz",
      "messageID": "msg-oc-001",
      "partID": "part-001",
      "delta": "我来看看"
    }
  }
}
```

**处理**：
```
handleTextMessage(session, textMessage)
  → message = objectMapper.readValue(payload, GatewayMessage.class)
  → type = "tool_event"
  → switch → handleRelayToSkillServer(session, message)
       ak = sessionAkMap.get(session.getId()) → "agent-key-123"
       → eventRelayService.relayToSkillServer("agent-key-123", message)
```

---

## ❾ [Gateway] `EventRelayService.relayToSkillServer()`

**模块**：[EventRelayService.java](file:///d:/02_Lab/Projects/sandbox/opencode-CUI/ai-gateway/src/main/java/com/opencode/cui/gateway/service/EventRelayService.java#L85-L110)

```
relayToSkillServer(ak="agent-key-123", message)
  │
  ├── 1. 生成/保持 traceId
  │
  ├── 2. 注入路由上下文
  │      userId = redisMessageBroker.getAgentUser("agent-key-123") → "user-42"
  │      source = redisMessageBroker.getAgentSource("agent-key-123") → "skill-server"
  │      forwarded = message.withAk("agent-key-123")
  │                         .withUserId("user-42")
  │                         .withSource("skill-server")
  │
  └── 3. 路由到 Skill Server
         skillRelayService.relayToSkill(forwarded)
```

**注入后的完整报文**：
```json
{
  "type": "tool_event",
  "ak": "agent-key-123",
  "userId": "user-42",
  "source": "skill-server",
  "traceId": "550e8400-e29b-...",
  "toolSessionId": "ts-abc-def",
  "welinkSessionId": "10001",
  "event": {
    "type": "message.part.delta",
    "properties": {
      "sessionID": "oc-session-xyz",
      "messageID": "msg-oc-001",
      "partID": "part-001",
      "delta": "我来看看"
    }
  }
}
```

---

## ❿ [Gateway] `SkillRelayService.relayToSkill()`

**模块**：[SkillRelayService.java](file:///d:/02_Lab/Projects/sandbox/opencode-CUI/ai-gateway/src/main/java/com/opencode/cui/gateway/service/SkillRelayService.java#L160-L200)

```
relayToSkill(message)
  │
  ├── 1. 解析目标 source
  │      message.source = "skill-server" ✅
  │
  ├── 2. 尝试本地直发
  │      sendViaDefaultLink("skill-server", message)
  │        ├── resolveDefaultSession("skill-server")
  │        │     → sourceSessions["skill-server"] 中选择活跃 WS 连接
  │        └── 如果有 → wsSession.sendMessage(json) ✅ 直接发送
  │
  └── 3. 如果本地无连接 → 跨实例路由
         selectOwner("skill-server", messageType)
           → Redis SMEMBERS gw:source:owners:skill-server
           → 通过 Rendezvous Hash 选择目标实例
         redisMessageBroker.publishToRelay(ownerId, message)
           → Redis PUBLISH gw:relay:{ownerId}
         目标实例的 handleRelayedMessage() → sendViaDefaultLink()
```

**最终通过 WS 发给 Skill Server 的报文**：
```json
{
  "type": "tool_event",
  "ak": "agent-key-123",
  "userId": "user-42",
  "source": "skill-server",
  "traceId": "550e8400-e29b-...",
  "toolSessionId": "ts-abc-def",
  "welinkSessionId": "10001",
  "event": {
    "type": "message.part.delta",
    "properties": {
      "sessionID": "oc-session-xyz",
      "messageID": "msg-oc-001",
      "partID": "part-001",
      "delta": "我来看看"
    }
  }
}
```

> [!NOTE]
> **Gateway 多实例路由**：每个 Gateway 实例通过 Redis sorted set `gw:source:owners:skill-server` 注册自己并定期心跳（30s TTL）。收到上行消息时，先尝试本地 WS 直发；若本地无 Skill Server 连接，则通过 `gw:relay:{instanceId}` Redis 频道跨实例中转。

---

## ⓫ [Skill Server] 接收 → 解析 → 翻译 → 持久化 → 缓冲

**模块**：[GatewayWSClient.java](file:///d:/02_Lab/Projects/sandbox/opencode-CUI/skill-server/src/main/java/com/opencode/cui/skill/ws/GatewayWSClient.java#L170-L172) → [GatewayRelayService.java](file:///d:/02_Lab/Projects/sandbox/opencode-CUI/skill-server/src/main/java/com/opencode/cui/skill/service/GatewayRelayService.java#L140-L174) → [OpenCodeEventTranslator.java](file:///d:/02_Lab/Projects/sandbox/opencode-CUI/skill-server/src/main/java/com/opencode/cui/skill/service/OpenCodeEventTranslator.java)

### 11.1 接收 WS 报文

```
GatewayWSClient.onMessage(rawMessage)
  → gatewayRelayService.handleGatewayMessage(rawMessage)
```

### 11.2 解析路由

```
handleGatewayMessage(rawMessage)
  ├── JSON 解析 → JsonNode
  ├── type     = "tool_event"
  ├── ak       = "agent-key-123"
  ├── userId   = "user-42"
  └── sessionId = resolveSessionId("tool_event", node)
        welinkSessionId = "10001" ← 直接使用 ✅
        (备选: toolSessionId → DB 查询 sessionService.findByToolSessionId())
```

### 11.3 handleToolEvent 完整流程

```
handleToolEvent(sessionId="10001", userId="user-42", node)
  │
  ├── A. 激活会话
  │      sessionService.activateSession(10001)
  │      如果 IDLE → ACTIVE:
  │        broadcastStreamMessage("10001", "user-42",
  │          StreamMessage{ type="session.status", sessionStatus="busy" })
  │
  ├── B. 提取 event 字段
  │      event = node.get("event")
  │      {
  │        "type": "message.part.delta",
  │        "properties": {
  │          "sessionID": "oc-session-xyz",
  │          "messageID": "msg-oc-001",
  │          "partID": "part-001",
  │          "delta": "我来看看"
  │        }
  │      }
  │
  ├── C. 事件翻译 (OpenCodeEventTranslator)
  │      translator.translate(event) →
  │      ┌────────────────────────────────────────────────────────┐
  │      │ translatePartDelta():                                  │
  │      │  partType 缓存查找("part-001") → "text"               │
  │      │  partSeq  缓存分配 → 1                                │
  │      │  role     缓存查找("msg-oc-001") → "assistant"        │
  │      │                                                        │
  │      │  输出 StreamMessage:                                   │
  │      │  {                                                     │
  │      │    type: "text.delta",                                 │
  │      │    sessionId: "oc-session-xyz",                        │
  │      │    messageId: "msg-oc-001",                            │
  │      │    partId: "part-001",                                 │
  │      │    partSeq: 1,                                         │
  │      │    role: "assistant",                                  │
  │      │    content: "我来看看",                                 │
  │      │    emittedAt: "2026-03-13T06:00:00.123Z"              │
  │      │  }                                                     │
  │      └────────────────────────────────────────────────────────┘
  │
  ├── D. 广播 (→ 进入 ⓬)
  │      broadcastStreamMessage("10001", "user-42", msg)
  │
  ├── E. 流式缓冲 (StreamBufferService)
  │      bufferService.accumulate("10001", msg)
  │        text.delta:
  │          Redis 追加内容: stream:10001:part:part-001 += "我来看看"
  │          Redis 设置状态: stream:10001:status = {"status":"busy"}
  │          Redis 维护顺序: stream:10001:parts_order RPUSH "part-001"
  │        (用于客户端断线重连后的流式状态恢复)
  │
  └── F. 持久化 (MessagePersistenceService)
         persistenceService.persistIfFinal(10001, msg)
           text.delta → 不持久化（中间态）
           text.done  → persistTextPart(): INSERT/UPDATE skill_message_parts
           tool.update(completed) → persistToolPart(): INSERT/UPDATE
           step.done  → persistStepDone(): 记录 tokens/cost
           session.status(idle) → 关闭活跃消息，清理流式缓冲

         prepareMessageContext(10001, msg):
           resolveActiveMessage() →
           ├── 存在活跃消息且 messageId 匹配 → 复用，设置 messageSeq
           ├── messageId 变更 → 关闭旧消息，创建新消息
           └── 首次出现 → saveMessage(INSERT) → 分配 messageSeq
```

> [!IMPORTANT]
> **事件翻译映射表**（部分）：
>
> | OpenCode event type | StreamMessage type | 说明 |
> |---|---|---|
> | `message.part.delta` (text) | `text.delta` | 文本流式增量 |
> | `message.part.updated` (text) | `text.done` | 文本部分完成 |
> | `message.part.delta` (reasoning) | `thinking.delta` | 思考过程增量 |
> | `message.part.updated` (tool) | `tool.update` | 工具调用状态更新 |
> | `message.part.updated` (tool, question) | `question` | 交互式提问 |
> | `session.status` | `session.status` | 会话状态 (busy/idle) |
> | `session.updated` | `session.title` | 会话标题更新 |

---

## ⓬ `broadcastStreamMessage()` → Redis PUBLISH

**模块**：[GatewayRelayService.java](file:///d:/02_Lab/Projects/sandbox/opencode-CUI/skill-server/src/main/java/com/opencode/cui/skill/service/GatewayRelayService.java#L477-L497) → [RedisMessageBroker.java](file:///d:/02_Lab/Projects/sandbox/opencode-CUI/skill-server/src/main/java/com/opencode/cui/skill/service/RedisMessageBroker.java#L54-L57)

```
broadcastStreamMessage(sessionId="10001", userIdHint="user-42", msg)
  │
  ├── enrichStreamMessage("10001", msg)
  │     msg.welinkSessionId = "10001"
  │     msg.emittedAt = 保持翻译时设置的值
  │     persistenceService.prepareMessageContext(10001L, msg)
  │       → msg.messageSeq = 5 (从 DB 解析)
  │
  ├── resolveUserId("10001", "user-42")
  │     userIdHint 非空 → "user-42"
  │
  ├── 构建信封 JSON
  │     {
  │       "sessionId": "10001",
  │       "userId": "user-42",
  │       "message": { <msg JSON> }
  │     }
  │
  └── redisMessageBroker.publishToUser("user-42", envelopeJson)
        → Redis PUBLISH user-stream:user-42
```

**发到 Redis 的完整信封**：
```json
{
  "sessionId": "10001",
  "userId": "user-42",
  "message": {
    "type": "text.delta",
    "welinkSessionId": "10001",
    "emittedAt": "2026-03-13T06:00:00.123Z",
    "messageId": "msg-oc-001",
    "messageSeq": 5,
    "role": "assistant",
    "sourceMessageId": "msg-oc-001",
    "partId": "part-001",
    "partSeq": 1,
    "content": "我来看看"
  }
}
```

> [!IMPORTANT]
> **User 级升级核心**：频道从旧的 `session:10001` 变为 `user-stream:user-42`。同一用户的所有会话事件走同一频道，Skill Server 多实例中只有持有该用户 WS 连接的实例订阅此频道。

---

## ⓭ `SkillStreamHandler.handleUserBroadcast()`

**模块**：[SkillStreamHandler.java](file:///d:/02_Lab/Projects/sandbox/opencode-CUI/skill-server/src/main/java/com/opencode/cui/skill/ws/SkillStreamHandler.java#L262-L323)

```
Redis 回调 → handleUserBroadcast(userId="user-42", rawMessage)
  │
  ├── 解析信封
  │     node = objectMapper.readTree(rawMessage)
  │     node.has("message") → true
  │     msg = treeToValue(node.get("message"), StreamMessage.class)
  │     msg.sessionId = "10001", msg.welinkSessionId = "10001"
  │
  └── pushStreamMessageToUser("user-42", msg)
        ├── recipients = userSubscribers["user-42"]
        │     → { wsSession-A, wsSession-B }
        ├── msg.setSeq(seqCounters["10001"].incrementAndGet()) → 42
        ├── messageText = objectMapper.writeValueAsString(msg)
        └── 遍历发送:
              wsSession-A.sendMessage(TextMessage(messageText)) ✅
              wsSession-B.sendMessage(TextMessage(messageText)) ✅
```

**最终 WS 推送报文**（Miniapp 收到）：
```json
{
  "type": "text.delta",
  "seq": 42,
  "welinkSessionId": "10001",
  "emittedAt": "2026-03-13T06:00:00.123Z",
  "messageId": "msg-oc-001",
  "messageSeq": 5,
  "role": "assistant",
  "sourceMessageId": "msg-oc-001",
  "partId": "part-001",
  "partSeq": 1,
  "content": "我来看看"
}
```

> [!NOTE]
> `sessionId` 标注 `@JsonIgnore` 不序列化。`seq` 按 sessionId 独立递增，用于前端检测乱序/丢包。

---

## ⓮ 前端 `handleStreamMessage()`

**模块**：[useSkillStream.ts](file:///d:/02_Lab/Projects/sandbox/opencode-CUI/skill-miniapp/src/hooks/useSkillStream.ts#L441-L530)

```
ws.onmessage(event)
  │
  ├── 1. JSON 解析 → normalizeIncomingStreamMessage(raw)
  │
  ├── 2. 会话过滤
  │      welinkSessionId("10001") == 当前 sessionId("10001")? ✅
  │      (不匹配 → 丢弃，只处理当前查看的会话)
  │
  └── 3. 按 type 分发
         "text.delta" → applyStreamedMessage(msg)
           │
           ├── assembler = assemblersRef.get("msg-oc-001") ?? new StreamAssembler()
           ├── assembler.handleMessage(msg) → 拼接 delta 文本
           ├── activeMessageIds.add("msg-oc-001")
           ├── setIsStreaming(true)
           └── setMessages(prev => upsertMessage(prev, {
                 id: "msg-oc-001",
                 role: "assistant",
                 content: "我来看看",
                 isStreaming: true,
                 parts: [...]
               }))
               → React 重渲染 → 用户看到逐字出现的回复 ✅
```

**完整 type 分发表**：

| StreamMessage type                    | 前端处理                                  |
| ------------------------------------- | ----------------------------------------- |
| `text.delta` / `text.done`            | StreamAssembler 拼接文本                  |
| `thinking.delta` / `thinking.done`    | StreamAssembler 拼接思考过程              |
| `tool.update` / `question` / `file`   | StreamAssembler 更新 part 状态            |
| `permission.ask` / `permission.reply` | StreamAssembler 显示权限交互              |
| `step.start`                          | 标记 streaming 开始                       |
| `step.done`                           | 记录 tokens/cost 元数据                   |
| `session.status(idle)`                | finalizeAllStreamingMessages 结束所有流式 |
| `session.status(busy)`                | setIsStreaming(true)                      |
| `agent.online/offline`                | 更新 Agent 状态指示器                     |
| `error` / `session.error`             | 终止流式 + 显示错误                       |
| `snapshot`                            | 重置并加载历史消息                        |
| `streaming`                           | 恢复断线前的流式状态                      |

---

# 附录

## A. WS 初始连接推送

Miniapp 连接 `/ws/skill/stream` 时，服务端为每个活跃会话推送：

```
afterConnectionEstablished(session)
  ├── extractUserId → "user-42"
  ├── registerUserSubscriber("user-42", session)
  │     userSubscribers["user-42"].add(session)
  │     activeConnectionCounts["user-42"]++ → 1
  │     → subscribeToUserStream: Redis SUBSCRIBE user-stream:user-42
  │     → preloadActiveSessionOwners: 缓存 sessionId→userId 映射
  │
  └── sendInitialStreamingState(session, "user-42")
        对每个活跃 session:
          ├── WS推送 snapshot:
          │   { "type":"snapshot", "seq":1, "welinkSessionId":"10001",
          │     "messages":[...历史消息...] }
          └── WS推送 streaming:
              { "type":"streaming", "seq":2, "welinkSessionId":"10001",
                "sessionStatus":"busy", "parts":[...正在进行的part...] }
```

## B. Redis 频道总览

| 系统         | 频道模式                | 用途                                        |
| ------------ | ----------------------- | ------------------------------------------- |
| Gateway      | `agent:{ak}`            | invoke 路由到持有 Agent WS 的 Gateway 实例  |
| Gateway      | `gw:relay:{instanceId}` | 多 Gateway 实例间跨实例消息中转             |
| Skill Server | `user-stream:{userId}`  | 上行事件广播到持有用户 WS 连接的 Skill 实例 |

## C. 核心文件索引

| 层级         | 文件                                                                                                                                                                       | 职责                               |
| ------------ | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------- |
| 前端         | [useSkillStream.ts](file:///d:/02_Lab/Projects/sandbox/opencode-CUI/skill-miniapp/src/hooks/useSkillStream.ts)                                                             | WS 管理、消息发送、流式组装        |
| 前端         | [api.ts](file:///d:/02_Lab/Projects/sandbox/opencode-CUI/skill-miniapp/src/utils/api.ts)                                                                                   | REST 请求封装                      |
| Skill Server | [SkillMessageController.java](file:///d:/02_Lab/Projects/sandbox/opencode-CUI/skill-server/src/main/java/com/opencode/cui/skill/controller/SkillMessageController.java)    | 消息 REST 入口                     |
| Skill Server | [GatewayRelayService.java](file:///d:/02_Lab/Projects/sandbox/opencode-CUI/skill-server/src/main/java/com/opencode/cui/skill/service/GatewayRelayService.java)             | invoke 封装 + 上行解析/翻译/广播   |
| Skill Server | [GatewayWSClient.java](file:///d:/02_Lab/Projects/sandbox/opencode-CUI/skill-server/src/main/java/com/opencode/cui/skill/ws/GatewayWSClient.java)                          | 与 Gateway WS 长连接               |
| Skill Server | [OpenCodeEventTranslator.java](file:///d:/02_Lab/Projects/sandbox/opencode-CUI/skill-server/src/main/java/com/opencode/cui/skill/service/OpenCodeEventTranslator.java)     | OpenCode event → StreamMessage     |
| Skill Server | [RedisMessageBroker.java](file:///d:/02_Lab/Projects/sandbox/opencode-CUI/skill-server/src/main/java/com/opencode/cui/skill/service/RedisMessageBroker.java)               | user-stream:{userId} pub/sub       |
| Skill Server | [SkillStreamHandler.java](file:///d:/02_Lab/Projects/sandbox/opencode-CUI/skill-server/src/main/java/com/opencode/cui/skill/ws/SkillStreamHandler.java)                    | 消费 Redis → WS 推送               |
| Skill Server | [MessagePersistenceService.java](file:///d:/02_Lab/Projects/sandbox/opencode-CUI/skill-server/src/main/java/com/opencode/cui/skill/service/MessagePersistenceService.java) | 消息上下文/持久化                  |
| Skill Server | [StreamBufferService.java](file:///d:/02_Lab/Projects/sandbox/opencode-CUI/skill-server/src/main/java/com/opencode/cui/skill/service/StreamBufferService.java)             | Redis 流式缓冲（断线恢复）         |
| Gateway      | [SkillWebSocketHandler.java](file:///d:/02_Lab/Projects/sandbox/opencode-CUI/ai-gateway/src/main/java/com/opencode/cui/gateway/ws/SkillWebSocketHandler.java)              | 接收 Skill WS                      |
| Gateway      | [SkillRelayService.java](file:///d:/02_Lab/Projects/sandbox/opencode-CUI/ai-gateway/src/main/java/com/opencode/cui/gateway/service/SkillRelayService.java)                 | invoke → Agent + 上行 → Skill 路由 |
| Gateway      | [AgentWebSocketHandler.java](file:///d:/02_Lab/Projects/sandbox/opencode-CUI/ai-gateway/src/main/java/com/opencode/cui/gateway/ws/AgentWebSocketHandler.java)              | PCAgent WS 注册/消息分发           |
| Gateway      | [EventRelayService.java](file:///d:/02_Lab/Projects/sandbox/opencode-CUI/ai-gateway/src/main/java/com/opencode/cui/gateway/service/EventRelayService.java)                 | Agent session 管理/上下行中转      |
| Gateway      | [RedisMessageBroker.java](file:///d:/02_Lab/Projects/sandbox/opencode-CUI/ai-gateway/src/main/java/com/opencode/cui/gateway/service/RedisMessageBroker.java)               | agent:{ak} + gw:relay:{id} pub/sub |
| Gateway      | [GatewayMessage.java](file:///d:/02_Lab/Projects/sandbox/opencode-CUI/ai-gateway/src/main/java/com/opencode/cui/gateway/model/GatewayMessage.java)                         | Gateway 协议 DTO                   |
