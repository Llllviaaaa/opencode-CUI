# message-bridge 插件 — 架构设计文档

**Version:** V1  
**Date:** 2026-03-06  
**Status:** 评审版  
**Owner:** message-bridge maintainers  
**Related:** `../product/prd.md`, `../design/solution-design.md`, `../quality/validation-report.md`  

---

## 1. 系统概览

### 1.1 设计目标

message-bridge 是 OpenCode 原生插件，桥接本地 OpenCode 实例与远端 AI-Gateway。插件采用**透明透传**架构，保持事件与 action 的可扩展性，确保后续协议演进无需修改核心引擎。

补充约束（更正）：
1. 插件入口契约：`PluginInput -> Hooks`
2. 边界：上行事件走 `event` hook，下行 `invoke/status_query` 走后台 runtime 主循环

### 1.1.1 协议对齐更正（2026-03-09）

当前实现已与 `pc-agent` 协议口径对齐，以下规则优先生效：

1. 边界报文不再携带 `envelope`。
2. 会话字段采用双标识：`welinkSessionId`（技能侧）+ `toolSessionId`（OpenCode 侧）。
3. `close_session -> session.delete`，`abort_session -> session.abort`。
4. `question_reply` 不再走 `session.prompt`，改为 question API 链路（`GET /question` + `POST /question/{requestID}/reply`）。

### 1.2 设计原则

| 序号 | 原则 | 说明 |
|---|---|---|
| 1 | 透明透传优先 | 事件主体不做业务改写，仅补充协议必需路由字段 |
| 2 | 可扩展优先 | 事件白名单与 action registry 不写死，支持热扩展 |
| 3 | SDK 对齐优先 | 长期与 `@opencode-ai/sdk` 的 SSE/REST 语义收敛 |
| 4 | 差异可追踪 | 现网兼容项必须记录收敛计划与版本归属 |
| 5 | Fast Fail 优先 | 连接异常立即返回错误，不排队、不缓冲 |
| 6 | 无状态设计 | 插件不持久化业务数据，幂等一致性由服务端承担 |

### 1.3 系统边界

**插件负责的范围（In Scope）：**

| 组件 | 职责 |
|---|---|
| Gateway 连接 | WebSocket 连接、AK/SK 鉴权、心跳、指数退避重连 |
| 事件上行 | 白名单过滤、透传 |
| action 下行 | registry 路由、执行、错误映射 |
| 状态管理 | agentId 绑定生命周期、READY 状态机 |
| 配置管理 | 多源配置发现、JSONC 解析、版本校验 |
| 错误处理 | Fast Fail、结构化错误、脱敏日志 |

**插件不负责的范围（Out of Scope）：**

| 范围 | 说明 |
|---|---|
| Gateway 业务逻辑 | 不修改 ai-gateway 代码 |
| Skill Server 业务逻辑 | 不修改 skill-server 代码 |
| 服务端幂等去重 | 由服务端负责 |
| 监控告警平台 | 后续迭代接入 |
| 多平台 adapter | 仅支持 OpenCode 本地模式 |

**外部依赖（External Dependencies）：**

| 依赖 | 说明 |
|---|---|
| AI-Gateway | WebSocket 服务端，负责 AK/SK 验证与消息中继 |
| @opencode-ai/sdk | OpenCode 本地 SDK，提供 session/chat/event 能力 |
| OpenCode 本地实例 | 运行在 localhost:54321 |

---

## 2. 架构总览图

```
┌──────────────────────────────────────────────────────────────────────┐
│  OpenCode (本地运行，localhost:54321)                                  │
│  HTTP REST + SSE 事件流                                               │
└────────────────────────┬─────────────────────────────────────────────┘
                         │ @opencode-ai/sdk
                         ▼
┌──────────────────────────────────────────────────────────────────────┐
│  message-bridge 插件 (OpenCode Native Plugin)                          │
│                                                                        │
│  ┌─────────────────────────────────────────────────────────────────┐  │
│  │  配置层 (Config Layer)                                            │  │
│  │  • 多源配置发现 (env/project/user/default)                         │  │
│  │  • JSONC 解析 + 版本校验                                           │  │
│  └─────────────────────────────────────────────────────────────────┘  │
│                              │                                         │
│  ┌───────────────────────────▼─────────────────────────────────────┐  │
│  │  连接层 (Connection Layer)                                         │  │
│  │  • GatewayConnection (WS 连接/断线重连/心跳)                        │  │
│  │  • AkSkAuth (AK/SK 签名生成)                                       │  │
│  │  • StateManager (READY 状态机/agentId 绑定)                         │  │
│  └─────────────────────────────────────────────────────────────────┘  │
│                              │                                         │
│  ┌───────────────────────────▼─────────────────────────────────────┐  │
│  │  事件层 (Event Layer) —— 上行                                       │  │
│  │  • EventFilter (白名单前缀匹配)                                     │  │
│  │  • BridgeRuntime.handleEvent (透传到 Gateway)                       │  │
│  └─────────────────────────────────────────────────────────────────┘  │
│                              │                                         │
│  ┌───────────────────────────▼─────────────────────────────────────┐  │
│  │  Action 层 (Action Layer) —— 下行                                  │  │
│  │  • ActionRegistry (action 注册表)                                  │  │
│  │  • ActionRouter (路由分发)                                         │  │
│  │  • BaseAction (validator/executor/errorMapper)                     │  │
│  │    - chat                                                         │  │
│  │    - create_session                                               │  │
│  │    - abort_session → session.abort                                │  │
│  │    - close_session → session.delete                               │  │
│  │    - permission_reply (response: once|always|reject)              │  │
│  │    - question_reply (toolSessionId + answer, toolCallId optional) │  │
│  │    - status_query                                                 │  │
│  └─────────────────────────────────────────────────────────────────┘  │
│                              │                                         │
│  ┌───────────────────────────▼─────────────────────────────────────┐  │
│  │  错误层 (Error Layer)                                              │  │
│  │  • Fast Fail 检测 (<=100ms 判定)                                    │  │
│  │  • ErrorMapper (code 映射)                                         │  │
│  │  • 脱敏日志输出                                                     │  │
│  └─────────────────────────────────────────────────────────────────┘  │
└────────────────────────┬─────────────────────────────────────────────┘
                         │ WebSocket (WSS in production)
                         │ 上行: tool_event/tool_error/session_created/status_response
                         │ 下行: invoke/status_query
                         ▼
┌──────────────────────────────────────────────────────────────────────┐
│  AI-Gateway (服务端)                                                   │
│  • /ws/agent 端点 (AK/SK 鉴权)                                         │
│  • 透传中继 (不做业务解析)                                              │
└──────────────────────────────────────────────────────────────────────┘
```

**核心数据流**：
- **上行**：OpenCode 事件 → 白名单过滤 → 扁平消息封装 → 透传到 Gateway
- **下行**：Gateway invoke → Action Registry 路由 → SDK 调用 → 返回结果

---

## 3. 组件设计

### 3.1 配置层 (Config Layer)

**定位**：多源配置发现与验证。

#### 3.1.1 模块划分

| 模块 | 文件 | 职责 |
|---|---|---|
| 配置发现 | `config/ConfigResolver.ts` | 按优先级加载配置源 |
| JSONC 解析 | `config/JsoncParser.ts` | 支持注释与尾逗号 |
| 配置校验 | `config/ConfigValidator.ts` | version=1 校验、结构验证 |
| 类型定义 | `config/types.ts` | Config 接口定义 |

#### 3.1.2 配置源优先级

配置从多个源加载，优先级从高到低：

```
env (BRIDGE_*) > project (.opencode/message-bridge.jsonc/.json) > user (~/.config/opencode/message-bridge.jsonc/.json) > default
```

**项目配置查找机制**：
- 从当前工作目录（或指定 workspace）向上查找到文件系统根
- 在每个目录中按顺序查找 `.opencode/message-bridge.jsonc`、`.opencode/message-bridge.json`
- 使用第一个找到的匹配文件
- 这允许在子目录中运行时也能正确加载项目配置

同一目录中若两者同时存在，优先 `message-bridge.jsonc`。

**示例场景**：
```
/workspace/project/
  ├── .opencode/
  │   ├── message-bridge.jsonc  ← 同目录双文件时优先
  │   └── message-bridge.json
  ├── src/
  │   └── components/
  │       └── Button.tsx        ← 在这里运行也能找到配置
  └── .git/
```

#### 3.1.3 配置示例

**最小配置（仅必填字段）：**

```jsonc
{
  "auth": {
    "ak": "your-access-key",
    "sk": "your-secret-key"
  }
}
```

**完整配置（所有字段）：**

```jsonc
{
  "config_version": 1,              // 默认: 1
  "enabled": true,                  // 默认: true，false 时安全禁用插件
  "gateway": {
    "url": "wss://gateway.example.com/ws/agent",  // 默认: ws://localhost:8081/ws/agent
    "deviceName": "My Device",      // 默认: Local Machine
    "toolType": "opencode",         // 默认: opencode
    "toolVersion": "1.0.0",         // 默认: 1.0.0
    "heartbeatIntervalMs": 30000,   // 默认: 30000
    "reconnect": {
      "baseMs": 1000,               // 默认: 1000
      "maxMs": 30000,               // 默认: 30000
      "exponential": true           // 默认: true
    },
    "ping": {
      "intervalMs": 30000,          // 默认: 30000
      "pongTimeoutMs": 10000        // 默认: 10000
    }
  },
  "sdk": {
    "timeoutMs": 10000              // 默认: 10000
  },
  "auth": {
    "ak": "${BRIDGE_AK}",           // 支持 env 占位符
    "sk": "${BRIDGE_SK}"
  },
  "events": {
    "allowlist": [                  // 默认: ['message.*', 'permission.*', 'question.*', ...]
      "message.*",
      "permission.*",
      "question.*",
      "session.*",
      "file.edited",
      "todo.updated",
      "command.executed"
    ]
  }
}
```

### 3.2 连接层 (Connection Layer)

**定位**：WebSocket 连接生命周期管理。

#### 3.2.1 模块划分

| 模块 | 文件 | 职责 |
|---|---|---|
| Gateway 连接 | `connection/GatewayConnection.ts` | WS 连接、指数退避重连、心跳、ping/pong 探活 |
| AK/SK 认证 | `connection/AkSkAuth.ts` | 签名生成 (HMAC-SHA256) |
| 状态管理 | `connection/StateManager.ts` | agentId 绑定、READY 状态机 |
| 消息收发 | `connection/MessageHandler.ts` | 消息序列化/反序列化 |

#### 3.2.2 状态机

```
                    connect()
                        │
                        ▼
    ┌─────────────────────────────────────┐
    │           DISCONNECTED              │
    └─────────────────┬───────────────────┘
                      │ onOpen
                      ▼
    ┌─────────────────────────────────────┐
    │           CONNECTED                 │
    │   (WS 已建立，等待 register 发送)      │
    └─────────────────┬───────────────────┘
                      │ send register
                      ▼
    ┌─────────────────────────────────────┐
    │              READY                  │
    │   (register 已发送，可收发业务消息)     │
    │   ⚠️ 注意：ai-gateway 无显式成功响应    │
    │      连接保持即表示注册成功            │
    └─────────────────┬───────────────────┘
                      │ onClose/onError
                      ▼
    ┌─────────────────────────────────────┐
    │           DISCONNECTED              │
    └─────────────────────────────────────┘
```

#### 3.2.3 agentId 绑定规则

1. WS 建立后发送 `register` 消息
2. **ai-gateway 无显式注册成功响应**，连接保持即表示注册成功
3. 发送 `register` 后进入 `READY` 状态，可开始收发业务消息
4. 运行时内部使用本地生成的唯一标识（如 `bridge-{uuid}`）
5. 连接重建后必须重新注册，生成新的 agentId

⚠️ **重要**：当前 ai-gateway 实现不会返回 `gatewayAgentId`，插件使用本地生成的 agentId。

#### 3.2.4 链路探活与重连判定

1. 应用层继续按固定间隔发送 `heartbeat`，用于 Gateway 更新 `last_seen_at`
2. 连接层通过 WebSocket 控制帧进行探活：客户端周期发送 `ws.ping`
3. `pongTimeoutMs` 探活判定作为 backlog（`REQ-MB-CONN-002`），当前版本未实现；当前重连主要由连接关闭/错误触发
4. 重连使用指数退避（`1s → 2s → 4s → ... → 30s`）
5. 不依赖 `heartbeat_ack`；`read-idle` 仅用于观测，不作为重连触发条件

#### 3.2.5 指数退避重连

```
延迟 = min(baseMs * 2^attempt, maxMs)
baseMs = 1000, maxMs = 30000
序列: 1s → 2s → 4s → 8s → 16s → 30s → 30s...
```

### 3.3 事件层 (Event Layer) —— 上行

**定位**：OpenCode 事件过滤与封装。

#### 3.3.1 模块划分

| 模块 | 文件 | 职责 |
|---|---|---|
| 事件过滤 | `event/EventFilter.ts` | 白名单前缀匹配 |
| 事件透传 | `runtime/BridgeRuntime.ts` | 过滤并发送扁平 `tool_event` |
| 事件订阅 | `runtime/BridgeRuntime.ts` | 通过插件 event hook 接收 SDK 事件流 |

#### 3.3.2 白名单规则

```typescript
// 支持前缀通配符与精确匹配
allowlist = [
  "message.*",      // 匹配 message.created, message.updated...
  "permission.*",   // 匹配 permission.request...
  "question.*",     // 匹配 question.asked...
  "session.*",      // 匹配 session.created...
  "file.edited",    // 精确匹配
  "todo.updated",
  "command.executed"
]

// 匹配逻辑
function isAllowed(eventType: string): boolean {
  return allowlist.some(pattern => {
    if (pattern.endsWith('.*')) {
      return eventType.startsWith(pattern.slice(0, -1));
    }
    return eventType === pattern;
  });
}
```

#### 3.3.3 当前事件上行结构

```typescript
interface ToolEventMessage {
  type: 'tool_event';
  toolSessionId?: string;
  event: unknown; // 原样透传 OpenCode event
}
```

### 3.4 Action 层 (Action Layer) —— 下行

**定位**：Gateway 指令路由与执行。

#### 3.4.1 模块划分

| 模块 | 文件 | 职责 |
|---|---|---|
| Action Registry | `action/ActionRegistry.ts` | action 注册与查找 |
| Action Router | `action/ActionRouter.ts` | invoke 路由分发 |
| Base Action | `action/BaseAction.ts` | 抽象基类 |
| Chat Action | `action/ChatAction.ts` | chat 实现 |
| Create Session Action | `action/CreateSessionAction.ts` | create_session 实现 |
| Abort Session Action | `action/AbortSessionAction.ts` | abort_session → abort 实现 |
| Close Session Action | `action/CloseSessionAction.ts` | close_session → delete 实现 |
| Permission Reply Action | `action/PermissionReplyAction.ts` | permission_reply 实现 |
| Question Reply Action | `action/QuestionReplyAction.ts` | question_reply 实现 |
| Status Query Action | `action/StatusQueryAction.ts` | status_query 实现 |

#### 3.4.2 Action 接口

```typescript
interface Action {
  readonly name: string;
  
  // 验证 payload
  validate(payload: unknown): ValidationResult;
  
  // 执行 action
  execute(payload: unknown, context: ActionContext): Promise<ActionResult>;
  
  // 错误映射
  mapError(error: Error): ToolError;
}

interface ActionContext {
  sessionId?: string; // 内部保留，用于透传 welinkSessionId 到 action context
  toolSessionId?: string;
  opencode: OpenCodeSDK;
}
```

#### 3.4.3 内置 Actions

| Action | 说明 |
|---|---|
| `chat` | `session.prompt(...text parts...)` |
| `create_session` | `session.create(payload)` |
| `abort_session` | `session.abort(toolSessionId)` |
| `close_session` | `session.delete(toolSessionId)` |
| `permission_reply` | 标准协议字段 `response` |
| `question_reply` | `GET /question` 查找 pending request，再 `POST /question/{requestID}/reply` |
| `status_query` | 返回 `status_response` |

#### 3.4.4 Permission Reply

```typescript
interface PermissionReplyPayload {
  permissionId: string;
  toolSessionId: string;
  response: 'once' | 'always' | 'reject';
}
```

### 3.5 错误层 (Error Layer)

**定位**：错误检测、映射与 Fast Fail。

#### 3.5.1 Fast Fail 机制

```typescript
// 连接态定义（连接层判定来源：WS close/error 或 ping/pong 超时）
const isGatewayUnreachable = connectionState === 'DISCONNECTED' || connectionState === 'CONNECTING';
const isOpenCodeUnreachable = sdkCallTimeout || connectionError;

// 时限（可配置）
const SDK_TIMEOUT_MS = 10000;
const CONNECTION_CHECK_TIMEOUT_MS = 100;

// 行为
async function handleInvoke(invoke: InvokeMessage): Promise<void> {
  const startedAt = Date.now();
  if (Date.now() - startedAt > CONNECTION_CHECK_TIMEOUT_MS) {
    return sendToolError({
      code: 'AGENT_NOT_READY',
      error: `Connection state check timeout after ${CONNECTION_CHECK_TIMEOUT_MS}ms`,
      welinkSessionId: invoke.welinkSessionId
    });
  }
  
  // 1. 连接不可达：DISCONNECTED / CONNECTING
  if (isGatewayUnreachable) {
    return sendToolError({
      code: 'GATEWAY_UNREACHABLE',
      error: 'Gateway connection is not active',
      welinkSessionId: invoke.welinkSessionId
    });
  }

  // 2. 已连通但未 READY（CONNECTED）
  if (connectionState === 'CONNECTED') {
    return sendToolError({
      code: 'AGENT_NOT_READY',
      error: 'Agent not ready, cannot process invoke',
      welinkSessionId: invoke.welinkSessionId
    });
  }

  // 3. SDK 不可达
  if (isOpenCodeUnreachable) {
    return sendToolError({
      code: 'SDK_UNREACHABLE',
      error: 'OpenCode service unreachable',
      welinkSessionId: invoke.welinkSessionId
    });
  }
  
  // 4. 不排队、不缓冲
  // 5. 连接层继续重连，不退出进程
}
```

#### 3.5.2 错误码定义

| 错误码 | 说明 | HTTP 状态 |
|---|---|---|
| `GATEWAY_UNREACHABLE` | Gateway 连接断开 | 503 |
| `SDK_TIMEOUT` | SDK 调用超时 | 504 |
| `SDK_UNREACHABLE` | OpenCode 不可达 | 503 |
| `AGENT_NOT_READY` | 未注册完成，agentId 未绑定 | 503 |
| `INVALID_PAYLOAD` | payload 验证失败 | 400 |
| `UNSUPPORTED_ACTION` | 未注册的 action | 400 |

---

## 4. 数据流

### 4.1 事件上行流（OpenCode → Gateway）

```
OpenCode SSE 事件
  ↓
BridgeRuntime.handleEvent (event hook)
  ↓
EventFilter (白名单匹配)
  ├─ 匹配失败 → 记录 event.rejected_allowlist，丢弃
  ↓
Flat protocol mapping
  ↓
[READY 状态检查]
  ├─ 非 READY → 丢弃，记录警告
  ↓
MessageHandler (序列化)
  ↓
GatewayConnection (WS 发送)
  ↓
AI-Gateway
```

### 4.2 Action 下行流（Gateway → OpenCode）

```
Gateway invoke 消息
  ↓
MessageHandler (反序列化)
  ↓
[Fast Fail 检查 <=100ms]
  ├─ Gateway 不可达 → best effort 返回 tool_error(GATEWAY_UNREACHABLE)，失败则本地日志+计数
  ├─ CONNECTED（未 READY）→ 立即返回 tool_error(AGENT_NOT_READY)
  ↓
ActionRouter (路由分发)
  ├─ 未找到 action → 返回 tool_error(UNSUPPORTED_ACTION)
  ↓
Action.validate (payload 验证)
  ├─ 验证失败 → 返回 tool_error(INVALID_PAYLOAD)
  ↓
Action.execute (调用 SDK)
  ├─ SDK 超时 → 返回 tool_error(SDK_TIMEOUT)
  ├─ SDK 异常 → 返回 tool_error(SDK_UNREACHABLE)
  ↓
[结果处理]
  ├─ 成功 → 发送 session_created / status_response，或继续透传 tool_event
  ├─ 失败 → 发送 tool_error
  ↓
GatewayConnection (WS 发送)
  ↓
AI-Gateway
```

### 4.3 连接生命周期流

```
插件启动
  ↓
ConfigResolver (加载配置)
  ↓
GatewayConnection.connect()
  ↓
[WS 握手 + AK/SK 鉴权]
  ↓
onOpen
  ↓
发送 register (deviceName, os, toolType, toolVersion)
  ↓
READY 状态
  ├─ 当前行为：发送 register 后即进入 READY（无显式 register_success）
  ├─ 启动心跳 (30s 间隔)
  ├─ 启动 ping 探活 (建议 30s 间隔，10s pong 超时；可配置)
  ├─ 可收发业务消息
  ├─ onMessage (处理 invoke/status_query)
  ↓
[连接异常]
  ├─ onClose/onError → DISCONNECTED → 指数退避重连
  ├─ ping/pong 超时（未收到 pong）→ DISCONNECTED → 指数退避重连
  ↓
重连成功
  ├─ 重新发送 register
  ├─ 生成并使用新的本地 agentId (不复用旧)
  ↓
插件停止
  ├─ 关闭 WS 连接
  ├─ 清理资源
```

---

## 5. 通信协议

### 5.1 WebSocket 连接建立

```
ws://{gateway-host}/ws/agent?ak={ak}&ts={timestamp}&nonce={nonce}&sign={signature}
```

### 5.2 上行消息（插件 → Gateway）

#### 5.2.1 register

```json
{
  "type": "register",
  "deviceName": "MacBook-Pro-2023",
  "os": "MAC",
  "toolType": "OPENCODE",
  "toolVersion": "1.2.15"
}
```

#### 5.2.2 heartbeat

```json
{
  "type": "heartbeat",
  "timestamp": "2026-03-09T10:00:00.000Z"
}
```

说明：
- Gateway 不返回 `heartbeat_ack`，仅更新 agent `last_seen_at`。
- `heartbeat` 用于在线打点，不作为链路重连判定依据。

> 协议注记：`ping/pong` 属于 WebSocket 控制帧，不属于业务 JSON 消息体，不在本节消息类型中定义。

#### 5.2.3 tool_event

```json
{
  "type": "tool_event",
  "toolSessionId": "sess_123",
  "event": {
    "type": "message.updated",
    ...
  }
}
```

#### 5.2.5 tool_error

```json
{
  "type": "tool_error",
  "welinkSessionId": "sess_123",
  "toolSessionId": "oc_sess_789",
  "error": "SDK call timeout after 10000ms"
}
```

#### 5.2.6 session_created

```json
{
  "type": "session_created",
  "welinkSessionId": "sess_123",
  "toolSessionId": "oc_sess_789"
}
```

#### 5.2.7 status_response

```json
{
  "type": "status_response",
  "opencodeOnline": true
}
```

说明：
- `status_query` / `status_response` 均不携带会话字段。

### 5.3 下行消息（Gateway → 插件）

#### 5.3.1 invoke

```json
{
  "type": "invoke",
  "welinkSessionId": "sess_123",
  "action": "chat",
  "payload": {
    "text": "请帮我写一个函数",
    "toolSessionId": "oc_sess_789"
  }
}
```

#### 5.3.2 status_query

```json
{
  "type": "status_query"
}
```

说明：
- `status_query` 不携带会话字段。

---

## 6. 目录结构

```
plugins/message-bridge/
├── src/
│   ├── index.ts                      # 插件主入口
│   ├── plugin/
│   │   └── MessageBridgePlugin.ts    # 插件生命周期管理
│   ├── config/
│   │   ├── ConfigResolver.ts         # 配置发现
│   │   ├── ConfigValidator.ts        # 配置校验
│   │   ├── JsoncParser.ts            # JSONC 解析
│   │   └── types.ts                  # 配置类型定义
│   ├── connection/
│   │   ├── GatewayConnection.ts      # WS 连接管理
│   │   ├── StateManager.ts           # READY 状态机
│   │   ├── MessageHandler.ts         # 消息序列化
│   │   └── AkSkAuth.ts               # AK/SK 签名
│   ├── event/
│   │   ├── EventFilter.ts            # 白名单过滤
│   │   └── index.ts                  # 事件过滤导出
│   ├── action/
│   │   ├── ActionRegistry.ts         # Action 注册表
│   │   ├── ActionRouter.ts           # Action 路由
│   │   ├── BaseAction.ts             # 抽象基类
│   │   ├── ChatAction.ts
│   │   ├── CreateSessionAction.ts
│   │   ├── CloseSessionAction.ts
│   │   ├── PermissionReplyAction.ts
│   │   └── StatusQueryAction.ts
│   ├── error/
│   │   ├── ErrorMapper.ts            # 错误码映射
│   │   ├── FastFailDetector.ts       # Fast Fail 检测
│   │   └── types.ts                  # 错误类型定义
│   └── types/
│       └── index.ts                  # 公共类型定义
├── tests/
│   ├── unit/                         # 单元测试
│   ├── integration/                  # 集成测试
│   └── e2e/                          # E2E 测试
├── package.json
├── tsconfig.json
└── README.md
```

---

## 7. 依赖与兼容性

### 7.1 外部依赖

| 依赖 | 版本 | 用途 |
|---|---|---|
| `@opencode-ai/sdk` | ^1.2.15 | OpenCode 本地 SDK |
| `ws` | ^8.x | WebSocket 客户端 |
| `jsonc-parser` | 最新 | JSONC 解析 |

### 7.2 兼容性矩阵

| 组件 | 已验证版本 | 兼容范围 |
|---|---|---|
| `@opencode-ai/sdk` | 1.2.15 | 1.2.x (需回归验证) |
| AI-Gateway | - | 当前部署版本 |

### 7.3 SDK 对齐策略

| 差异项 | 当前行为 | 目标行为 | 收敛版本 |
|---|---|---|---|
| permission_reply | 历史存在 approved 偏差 | 仅 response | v1.5 |
| close_session | 映射到 delete | 与当前 SDK 路径一致 | 已完成 |
| abort_session | 映射到 abort | 与当前 SDK 路径一致 | 已完成 |
| agentId 绑定 | 插件本地生成 agentId | Gateway 分配 gatewayAgentId | 待定 |

---

## 8. 质量门槛

### 8.1 测试要求

| 层级 | 要求 |
|---|---|
| Unit | 白名单、映射、路由、错误分支、协议字段 |
| Integration | Mock Gateway WS + Mock SDK Client |
| E2E Smoke | 注册、心跳、create+chat+abort+close、permission_reply、question_reply、断连重连、不可达启动失败 |

**测试框架基线（与测试验证文档一致）**：
- Unit / Integration：`bun test`（主框架）
- E2E 协议链路：`bun test` + Mock Gateway/SDK
- E2E Web UI（后续可选）：Playwright Test

### 8.2 覆盖率

| 指标 | 目标 |
|---|---|
| Lines | >= 80% |
| Branches | >= 70% |

### 8.3 必测场景

1. 六类 action 正常链路
2. 白名单允许/拒绝路径
3. `permission_reply.response` 使用 `once|always|reject`
4. `close_session -> delete`，`abort_session -> abort`
5. Fast Fail 返回 `tool_error`
6. 扁平协议字段一致性
7. `status_response` 不携带会话字段
8. 配置发现/优先级/JSONC/version 校验
9. 新增事件或 action 不改核心引擎的扩展性验证
10. 状态到错误码映射一致：`DISCONNECTED/CONNECTING -> GATEWAY_UNREACHABLE`，`CONNECTED -> AGENT_NOT_READY`

---

## 9. PRD 差异记录

本章节记录架构设计与 PRD 需求文档（v1.4）之间的实际差异。

### 9.1 已确认差异

| 差异项 | PRD 定义 | 实际实现 | 影响范围 | 处理方式 |
|---|---|---|---|---|
| **register 响应** | §4.5: 等待 `gatewayAgentId` 绑定后才进入 READY | ai-gateway 无显式 `register_success` 响应，连接保持即表示成功 | 插件使用本地生成的 `agentId` | 接受差异，文档已更新 |

### 9.2 差异说明

#### register 响应差异详情

**PRD §4.5 原文：**
> 2. 仅当收到 Gateway 注册成功确认并获得连接绑定标识 `gatewayAgentId` 后，插件进入 `READY`。
> 3. `READY` 前不发送业务消息。
> 4. `READY` 前若收到 `invoke`，返回 `tool_error(error=Agent not ready)`。
> 5. 当前边界协议不使用 `envelope.agentId`。

注：以上为 PRD 原文。当前实现口径已收敛为状态映射：
`DISCONNECTED/CONNECTING -> GATEWAY_UNREACHABLE`，`CONNECTED -> AGENT_NOT_READY`。

**实际实现（基于 ai-gateway 代码调查）：**
- ai-gateway 在收到 `register` 后**不会返回**任何成功响应消息
- 注册成功由连接持续保持这一事实**隐式确认**
- 没有 `gatewayAgentId` 返回字段

**架构调整：**
```typescript
// 调整后行为
1. WS 建立后发送 `register` 消息
2. 发送完成后立即进入 `READY` 状态
3. 内部本地标识使用 `bridge-${uuid}`
4. 连接重建后生成新的 agentId
```

**影响评估：**
- ✅ 不影响核心功能（事件上行/action 下行）
- ✅ 符合 Fast Fail 原则（无需等待响应）
- ⚠️ 与 PRD 的严格 READY 状态机有偏差
- ⚠️ agentId 由插件生成，非服务端分配

**建议：**
- 后续版本如需服务端分配 agentId，需要 ai-gateway 新增 `register_success` 响应（见 `TODO-MB-001`）
- 当前方案在日志追踪中增加 `localAgentId` 字段以便问题定位

---

## 10. 待确认事项

### 10.1 架构层面

| 序号 | 确认项 | 结论 | 优先级 |
|---|---|---|---|
| 1 | **心跳响应机制** | ✅ **已确认（更新）** - `heartbeat` 继续用于 Gateway 更新 `last_seen_at`。`pongTimeoutMs` 探活判定已登记 backlog（`REQ-MB-CONN-002`），当前版本不作为重连触发条件。 | P1 |
| 2 | **重连时的消息缓冲** | ✅ **已确认** - 现有 PC-Agent 实现：**事件被丢弃，不缓冲**。`GatewayConnection.send()` 在非 CONNECTED 状态时抛出错误。无队列、缓冲、缓存实现。遵循 Fast Fail 原则，继续丢弃并记录计数。 | P1 |
| 3 | **sequenceNumber 持久化** | ✅ **已确认** - 现有实现：内存中 Map 存储，插件重启后重置。按 session 独立计数器 + agent 级计数器。按 session 重置（与现有行为一致），文档中已明确说明。 | P2 |
| 4 | **并发 invoke 处理** | ✅ **已确认** - **不支持**同一 session 的并发 invoke。OpenCode SDK 设计为顺序操作，保持会话上下文完整性。用户期望线性对话流，消息乱序会导致体验混乱。技术上当前实现已为顺序处理，支持并发存在高风险的消息混乱和上下文损坏。 | P2 |

### 10.2 协议层面

| 序号 | 确认项 | 结论 | 优先级 |
|---|---|---|---|
| 5 | **register 响应格式** | ✅ **已确认** - ai-gateway 无显式响应，连接保持即表示注册成功。插件使用本地生成的 agentId；后续与服务端对齐显式确认机制。 | P0 |
| 6 | **status_response 触发时机** | ✅ **已确认** - **仅响应 status_query**，无定期上报。Gateway 转发 status_query 到插件，插件调用 OpenCode health API 后返回 status_response。字段：type, opencodeOnline (boolean)。 | P1 |
| 7 | **error 消息脱敏级别** | ✅ **已确认** - **SK (Secret Key)**: 绝不记录。**签名**: 只记录是否 present，不记录内容。**AK (Access Key)**: 作为标识符记录（非敏感）。**Payload**: 最小化记录，仅用于路由。遵循现有安全实践。 | P0 |

### 10.3 PRD 差异记录

本章节记录架构实现与 PRD 冻结基线的差异。

| 差异项 | PRD 描述 | 实际实现 | 影响范围 | 备注 |
|---|---|---|---|---|
| agentId 绑定规则 (§4.5) | 插件等待 Gateway 返回 `gatewayAgentId`，READY 后使用 | ai-gateway 无显式响应，插件本地生成 agentId (如 `bridge-{uuid}`) | 插件内部状态与日志关联 | 已确认 - 连接保持即表示注册成功 |

### 10.4 实现层面

| 序号 | 确认项 | 建议方案 | 优先级 |
|---|---|---|---|
| 8 | **日志级别规范** | ✅ **已确认** - 采用四级日志：debug(详细调试信息)、info(关键生命周期节点)、warn(可恢复错误，如短暂连接断开)、error(需关注的问题，如认证失败)。使用结构化日志格式，包含 timestamp、level、component、message、context。 | P2 |
| 9 | **指标采集点** | ✅ **已确认** - 采集以下指标：连接状态变化次数(按状态分类)、invoke 处理延迟(P99/P95/P50)、事件吞吐量(每秒事件数)、错误码分布(按 code 分类)、重连次数和间隔、ping/pong 超时次数。指标输出到日志，后续迭代接入监控平台。 | P2 |
| 10 | **OpenCode SDK 多版本兼容** | ✅ **已确认** - **首版验证基线 1.2.15**，兼容范围 `1.2.x`（其他小版本需回归验证），`1.3.x` 及以上版本需重新评估兼容性。在 README 中明确说明已验证版本范围。 | P3 |

### 10.5 追踪代办（Tracking TODO）

| ID | 代办项 | Owner | 优先级 | 状态 |
|---|---|---|---|---|
| TODO-MB-001 | 与 ai-gateway 对齐 register 显式确认机制（是否新增 `register_success` 与服务端分配 `gatewayAgentId`） | Gateway + Message-Bridge | P1 | OPEN |
| TODO-MB-002 | `status_query/status_response` 的无会话字段契约与服务端长期接口统一（是否保持极简响应） | Gateway + Skill-Server + Message-Bridge | P1 | OPEN |

---

## 11. 附录

### 11.1 术语表

| 术语 | 说明 |
|---|---|
| Gateway | AI-Gateway 服务端 |
| SDK | `@opencode-ai/sdk` |
| READY | 插件状态：已注册成功，可收发业务消息 |
| Fast Fail | 连接异常立即返回错误，不排队缓冲 |
| Flat Protocol | 当前边界消息形态，仅包含活跃路由字段与业务载荷，不再使用 envelope 包装 |
| Action | Gateway 下发的指令类型（chat、create_session 等） |

### 11.2 参考文档

- [prd.md](../product/prd.md)
- [AGENTS.md](../AGENTS.md)

---

**文档结束**
