# External WebSocket Channel 设计文档

> 为 IM 及其他业务模块提供 WebSocket 出站推送通道 + 统一 REST 入站接口。

---

## 1. 背景

当前 IM 集成架构：
- **入站**：IM 调用 `POST /api/inbound/messages`（ImInboundController），仅支持 chat
- **出站**：skill-server 调用 IM 的 REST API（ImOutboundService.sendTextToIm），推送纯文本

局限性：
- 出站仅支持纯文本，无法传递 StreamMessage 富协议（delta、snapshot、permission_ask 等）
- 入站不支持 question_reply / permission_reply
- 新业务模块接入需要为每个模块编写专属出站代码

## 2. 目标

1. 提供通用 WebSocket 端点，业务模块（IM、CRM 等）建立服务级长连接接收 StreamMessage 推送
2. 提供统一 REST 入站接口，支持 chat / question_reply / permission_reply / rebuild
3. 不影响现有 ImInboundController 和 miniapp WebSocket 的行为
4. 新模块接入零代码改动：建 WS 连接 + 调 REST 接口即可

## 3. 整体架构

```
                          ┌─────────────────────────┐
                          │     业务模块（IM 等）     │
                          └──────┬──────────┬────────┘
                                 │          │
                    [入站 REST]  │          │  [出站 WS]
                                 │          │
                                 ▼          ▼
                   POST /api/external/invoke
                   (action: chat |     /ws/external/stream
                    question_reply |   (Sec-WebSocket-Protocol 认证)
                    permission_reply |        ▲
                    rebuild)               │
                                 │          │
                                 ▼          │
                   ExternalInbound    ExternalStream
                   Controller         Handler
                         │            (通用服务级 WS 网关)
                         │                  ▲
                         ▼                  │
                   InboundProcessing   OutboundDelivery
                   Service             Dispatcher
                   (共享入站逻辑)       (策略模式路由)
                         │                  ▲
                         ▼                  │
                   GatewayRelay ──→ GatewayMessageRouter
                   Service
```

## 4. 数据流

### 4.1 入站（业务模块 → AI）

```
业务模块 → POST /api/external/invoke
  → ExternalInboundController（参数校验、action 路由）
  → InboundProcessingService（共享逻辑：resolve、scope 策略、session 管理）
  → GatewayRelayService → AI Gateway → Agent
```

### 4.2 出站（AI → 业务模块）

```
Agent → AI Gateway → GatewayMessageRouter
  → OutboundDeliveryDispatcher.deliver(session, msg)
    → 策略匹配：
      - MiniappDeliveryStrategy    → Redis user-stream:{userId} → SkillStreamHandler → miniapp
      - ExternalWsDeliveryStrategy → Redis stream:{domain}      → ExternalStreamHandler → 业务模块 WS
      - ImRestDeliveryStrategy     → ImOutboundService.sendTextToIm() → IM REST API（兜底）
```

### 4.3 策略匹配规则

| 优先级 | 策略 | supports 条件 | 投递方式 |
|--------|------|--------------|----------|
| 1 | MiniappDeliveryStrategy | domain = miniapp | Redis user-stream:{userId} |
| 2 | ExternalWsDeliveryStrategy | domain != miniapp && 该 domain 有活跃 WS 连接 | Redis stream:{domain} |
| 3 | ImRestDeliveryStrategy | domain = im（兜底） | sendTextToIm() REST |

连接存在性即开关：业务模块建立 WS 连接后自动走 WS 推送，断开后 IM 自动降级回 REST。

## 5. 详细设计

### 5.1 ExternalInvokeRequest（统一请求 DTO）

固定信封 + 灵活 payload 结构，与项目内 GatewayMessage 风格一致。

**信封（顶层固定字段）**：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| action | String | 是 | chat / question_reply / permission_reply / rebuild |
| businessDomain | String | 是 | 业务域，需与 WS source 一致 |
| sessionType | String | 是 | group / direct |
| sessionId | String | 是 | 业务侧会话 ID |
| assistantAccount | String | 是 | 助手账号 |
| payload | JsonNode | 是 | action 专属数据，按 action 解析校验 |

**各 action 的 payload 定义**：

**chat**：
```json
{
  "action": "chat",
  "businessDomain": "im",
  "sessionType": "group",
  "sessionId": "grp-001",
  "assistantAccount": "assistant-01",
  "payload": {
    "content": "你好",
    "msgType": "text",
    "imageUrl": null,
    "chatHistory": [
      {"senderAccount": "user1", "senderName": "张三", "content": "之前的消息", "timestamp": 1713000000000}
    ]
  }
}
```

**question_reply**：
```json
{
  "action": "question_reply",
  "businessDomain": "im",
  "sessionType": "direct",
  "sessionId": "dm-001",
  "assistantAccount": "assistant-01",
  "payload": {
    "content": "选A",
    "toolCallId": "tc-xxx",
    "subagentSessionId": null
  }
}
```

**permission_reply**：
```json
{
  "action": "permission_reply",
  "businessDomain": "im",
  "sessionType": "direct",
  "sessionId": "dm-001",
  "assistantAccount": "assistant-01",
  "payload": {
    "permissionId": "perm-xxx",
    "response": "once",
    "subagentSessionId": null
  }
}
```

**rebuild**：
```json
{
  "action": "rebuild",
  "businessDomain": "im",
  "sessionType": "direct",
  "sessionId": "dm-001",
  "assistantAccount": "assistant-01",
  "payload": {}
}
```

**payload 校验规则**：

| action | payload 必填 | payload 可选 |
|--------|-------------|-------------|
| chat | content | msgType, imageUrl, chatHistory |
| question_reply | content, toolCallId | subagentSessionId |
| permission_reply | permissionId, response | subagentSessionId |
| rebuild | （无） | （无） |

### 5.2 ExternalInboundController

- **端点**：`POST /api/external/invoke`
- **认证**：复用 ImTokenAuthInterceptor（`Authorization: Bearer {skill.im.inbound-token}`）
- **响应**：`ApiResponse<Void>`，code=0 表示已接收

按 action 路由到 InboundProcessingService 对应方法。

### 5.3 InboundProcessingService（入站处理共享逻辑）

从 ImInboundController 提取的共享逻辑，两个 Controller 都调用：

**processChat()**：
1. assistantAccount → resolve(ak, ownerWelinkId)
2. scopeStrategy = scopeDispatcher.getStrategy(assistantInfoService.getCachedScope(ak))
3. Agent 在线检查（双重开关：`assistantIdProperties.isEnabled()` 总开关 + `scopeStrategy.requiresOnlineCheck()`）
   - 若离线：
     - 通过 OutboundDeliveryDispatcher 回复离线提示消息
     - 单聊 + 已有 session：保存系统消息到 DB
     - 返回成功（不继续处理）
4. 上下文注入（群聊 chatHistory 拼接到 prompt，通过 ContextInjectionService.resolvePrompt()）
5. 查找已有 session（ImSessionManager.findSession(domain, type, sessionId, ak)）
6. 三种情况：
   - **A: session 不存在** → ImSessionManager.createSessionAsync()
     - Personal：异步创建 SkillSession → 发送 create_session 到 Gateway → 等 session_created 回调绑定 toolSessionId → 自动重发缓存消息
     - Business：预生成 toolSessionId（`cloud-{uuid}`）→ 立即发送 CHAT
     - 消息缓存到 Redis 待重发
   - **B: session 存在但 toolSessionId 为空** → ImSessionManager.requestToolSession(session, prompt)
     - 缓存消息 → 请求 Gateway 重建 → 回调后自动重发
   - **C: session 就绪（toolSessionId 已绑定）** →
     - 单聊：保存用户消息到 DB（messageService.saveUserMessage）
     - 缓存消息到 Redis pending list（rebuildService.appendPendingMessage，供 session_not_found 重建后重发）
     - 构建 invoke payload（text, toolSessionId, assistantAccount, sendUserAccount, imGroupId, messageId）
     - 通过 GatewayRelayService.sendInvokeToGateway() 发送 CHAT invoke

**processQuestionReply()**：
1. assistantAccount → resolve(ak, ownerWelinkId)
2. 查找 session（必须已存在且 toolSessionId 就绪）
3. 构建 QUESTION_REPLY payload（answer, toolCallId, toolSessionId）→ 发送到 Gateway

**processPermissionReply()**：
1. assistantAccount → resolve(ak, ownerWelinkId)
2. 查找 session（必须已存在且 toolSessionId 就绪）
3. 构建 PERMISSION_REPLY payload（permissionId, response, toolSessionId）→ 发送到 Gateway
4. 广播 permission.reply StreamMessage

**processRebuild()**：
1. assistantAccount → resolve(ak, ownerWelinkId)
2. scopeStrategy = scopeDispatcher.getStrategy(scope)
3. 查找已有 session（by domain + type + sessionId + ak）
4. 两种情况：
   - **session 存在** → 清空 toolSessionId → 重新请求 Gateway 创建 toolSession
     - Personal：发送 create_session → 等回调绑定新 toolSessionId
     - Business：generateToolSessionId() 预生成 → 更新到 session
   - **session 不存在** → 创建新 SkillSession + 请求 Gateway 创建 toolSession（同 chat 的新建流程）
5. 返回成功（toolSession 异步创建，后续消息发送时自动使用新 toolSessionId）

ImInboundController 重构为调用 processChat()，外部行为不变。

### 5.4 ExternalStreamHandler（通用服务级 WS 网关）

- **端点**：`/ws/external/stream`

**握手认证**：Sec-WebSocket-Protocol 子协议，`auth.{base64json}`

```json
{
  "token": "xxx",
  "source": "im",
  "instanceId": "im-node-1"
}
```

- token：复用 `skill.im.inbound-token` 配置
- source：模块标识，对应 session 的 businessSessionDomain
- instanceId：实例标识，区分同一模块的多个实例

**连接池管理**：

```
source → { instanceId → WebSocketSession }

示例：
"im"  → { "im-node-1" → ws1, "im-node-2" → ws2 }
"crm" → { "crm-node-1" → ws3 }
```

核心方法：
- `hasActiveConnections(String source)` — 供 ExternalWsDeliveryStrategy 判断
- `pushToSource(String source, String message)` — 广播到该 source 所有连接

**心跳机制**：
- 客户端定时发送 `{"action": "ping"}`
- 服务端回复 `{"action": "pong"}`
- 服务端超时检测（60s 无消息）→ 主动断开
- 断连后从连接池移除，若该 source 无连接 → 取消 Redis 订阅

**连接生命周期**：
```
握手 → 验证 token + source + instanceId
  → 注册到连接池
  → 动态订阅 Redis stream:{source}
  → 推送 StreamMessage...
  → 断连 → 移除连接池 → 若该 source 无连接 → 取消订阅
```

**推送消息格式**：StreamMessage 协议，与 miniapp 完全一致。消息体包含 sessionId、assistantAccount 等字段，业务模块据此路由到对应会话。

### 5.5 OutboundDeliveryStrategy（出站投递策略模式）

```java
public interface OutboundDeliveryStrategy {
    boolean supports(SkillSession session);
    int order();
    void deliver(String sessionId, String userId, StreamMessage msg);
}
```

**OutboundDeliveryDispatcher**：按 order 排序策略列表，匹配第一个 supports 的策略执行。

**MiniappDeliveryStrategy (order=1)**：
- supports：domain = miniapp
- deliver：Redis `user-stream:{userId}` → SkillStreamHandler 消费

**ExternalWsDeliveryStrategy (order=2)**：
- supports：domain != miniapp && `externalStreamHandler.hasActiveConnections(domain)`
- deliver：Redis `stream:{domain}` → ExternalStreamHandler 消费 → WS 推送

**ImRestDeliveryStrategy (order=3)**：
- supports：domain = im
- deliver：`buildImText(msg)` → `imOutboundService.sendTextToIm()`

### 5.6 GatewayMessageRouter 改动

将 6 处 `imOutboundService.sendTextToIm()` / `broadcastStreamMessage()` 的 if/else 分支统一替换为：

```java
outboundDeliveryDispatcher.deliver(session, sessionId, userId, msg);
```

覆盖所有出站场景：
- handleImAssistantMessage（AI 回复）
- handleToolDone（会话完成）
- handleToolError（错误消息）
- handlePermissionRequest（权限提示）
- handleImPush（云端推送）
- handleContextOverflow（上下文重置）
- ImInboundController 的 agent_offline 通知

### 5.7 认证

| 接口 | 认证方式 |
|------|---------|
| `POST /api/external/invoke` | `Authorization: Bearer {token}`，复用 ImTokenAuthInterceptor |
| `WS /ws/external/stream` | Sec-WebSocket-Protocol `auth.{base64json}`，token 复用同一配置 |

## 6. 文件变更清单

### 新增文件（skill-server）

| 文件 | 职责 |
|------|------|
| `model/ExternalInvokeRequest.java` | 统一请求 DTO |
| `controller/ExternalInboundController.java` | 新 REST 入站接口 |
| `service/InboundProcessingService.java` | 入站处理共享逻辑 |
| `ws/ExternalStreamHandler.java` | 通用服务级 WS 网关 |
| `service/delivery/OutboundDeliveryStrategy.java` | 出站投递策略接口 |
| `service/delivery/OutboundDeliveryDispatcher.java` | 策略调度器 |
| `service/delivery/MiniappDeliveryStrategy.java` | miniapp 投递策略 |
| `service/delivery/ExternalWsDeliveryStrategy.java` | 通用 WS 投递策略 |
| `service/delivery/ImRestDeliveryStrategy.java` | IM REST 兜底投递策略 |

### 修改文件

| 文件 | 改动 |
|------|------|
| `GatewayMessageRouter` | 6 处 if/else 替换为 `outboundDeliveryDispatcher.deliver()` |
| `ImInboundController` | chat 逻辑提取到 InboundProcessingService，Controller 变为薄调用层；agent_offline 出站迁入 Dispatcher |
| `WebMvcConfig` | `/api/external/**` 加入 ImTokenAuthInterceptor 拦截路径 |
| `SkillConfig`（或新 Config） | 注册 WS 端点 `/ws/external/stream` |
| `RedisMessageBroker` | 新增 `publishToChannel(channel, msg)` + 动态订阅/取消订阅能力 |

### 不改动

| 文件 | 说明 |
|------|------|
| `ImOutboundService` | 不动，被 ImRestDeliveryStrategy 内部调用 |
| `SkillStreamHandler` | 不动，miniapp WS 不受影响 |
| `SkillSession` | 不动，现有字段已满足 |
| `AssistantScopeStrategy` 体系 | 不动，直接复用 |

## 7. 扩展性

新业务模块接入流程（零代码改动）：

1. 建立 WS 连接到 `/ws/external/stream`，握手带 `source="模块名"` + `instanceId`
2. 调用 `POST /api/external/invoke`，`businessDomain` 与 WS `source` 一致
3. ExternalWsDeliveryStrategy 自动检测到该 domain 有活跃连接 → WS 推送
4. 断连后自动降级（IM 降级到 REST，其他 domain 不投递）

## 8. 遗留项

| 项目 | 说明 |
|------|------|
| 方案 C（Channel 统一抽象） | 将 inbound + outbound 打包为 Channel 接口，后续分支重构 |
| WS 断连消息补发 | 当前版本断连即丢，后续可加消息缓冲队列 |
