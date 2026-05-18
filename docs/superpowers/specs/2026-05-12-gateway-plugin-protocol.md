# Gateway ↔ Plugin 双向协议规范（message-bridge / 新引擎适配作者用）

> **目的**：为插件作者或新引擎适配者提供 ai-gateway ↔ message-bridge 双向协议的完整契约。
> **协议范围**：上行事件流（plugin → GW）+ 下行 7 个 action（GW → plugin）+ 连接生命周期。
> **代码源**：见 §13 字段-代码映射

---

## 目录

- [1. 概览](#1-概览)
- [2. 连接契约（endpoint / WebSocket 握手）](#2-连接契约endpoint--websocket-握手)
- [3. 鉴权（AK/SK）](#3-鉴权aksk)
- [4. 上行事件公共字段](#4-上行事件公共字段)
- [5. 上行事件清单（plugin → GW）](#5-上行事件清单plugin--gw)
- [6. 下行 Action 公共字段](#6-下行-action-公共字段)
- [7. 下行 Action 清单（GW → plugin）](#7-下行-action-清单gw--plugin)
- [8. 连接生命周期（重连、心跳、状态机）](#8-连接生命周期重连心跳状态机)
- [9. 错误处理矩阵](#9-错误处理矩阵)
- [10. 配置项一览](#10-配置项一览)
- [11. 字段↔代码映射](#11-字段代码映射)
- [12. 给插件作者 / 引擎适配者的注意事项](#12-给插件作者--引擎适配者的注意事项)
- [13. 变更记录](#13-变更记录)

---

## 1. 概览

本文档为 **ai-gateway**（以下简称 GW，Java/Spring 端）与 **message-bridge 插件**（以下简称 plugin，Node/TypeScript 端）之间的 WebSocket 双向协议规范，面向：

- **插件作者**：基于现有 message-bridge 框架编写自定义引擎适配器；
- **新引擎适配者**：要把另一套 Agent 引擎（不是 opencode）接入 ai-gateway 体系；
- **协议审阅者**：需要核对 GW 侧实现与 plugin 侧实现的字段是否对齐。

### 1.1 角色

| 角色 | 实现位置 | 职责 |
|------|---------|------|
| **GW（gateway 侧）** | `ai-gateway`（Java，Spring WebFlux） | WebSocket 服务端；下发 `action` 指令；接收并路由 plugin 上行事件 |
| **plugin（message-bridge 侧）** | `plugins/agent-plugin/plugins/message-bridge/`（Node + TS） | WebSocket 客户端；驱动本地 Agent 引擎（opencode）；把引擎产生的事件以白名单方式上报给 GW |

> ⚠️ 注：本协议仅描述 **GW ↔ plugin** 这一段链路。plugin 内部如何驱动 opencode SDK、如何聚合消息流，不在本规范范围内。

### 1.2 总链路图

```
 ┌──────────────┐         ws (subprotocol: auth.<base64url-json>)         ┌──────────────────────────┐
 │              │ ──────────────────────────────────────────────────────▶ │                          │
 │  message-    │                                                          │       ai-gateway         │
 │  bridge      │   ◀── action（chat/createSession/closeSession/...）──── │     (Java / Spring)      │
 │  (Node)      │                                                          │                          │
 │              │   ──── upstream event（message.*/session.*/...）─────▶  │                          │
 │              │                                                          │                          │
 │              │   ──── heartbeat ─────▶                                  │                          │
 └──────┬───────┘                                                          └────────────┬─────────────┘
        │                                                                                │
        ▼                                                                                ▼
   opencode SDK                                                                    业务方（welink、
   （本地 Agent 引擎）                                                              其他下游消费者）
```

### 1.3 协议要点速览

| 维度 | 取值 |
|------|------|
| 传输 | WebSocket（`ws://` 或 `wss://`），单连接 |
| 默认 URL | `ws://localhost:8081/ws/agent`（可通过 `__MB_DEFAULT_GATEWAY_URL__` 注入覆盖） |
| 鉴权 | AK/SK，通过 WebSocket 子协议头携带（subprotocol: `auth.<base64url(JSON)>`） |
| 编码 | UTF-8 JSON 文本帧（无二进制） |
| 状态机 | `DISCONNECTED → CONNECTING → CONNECTED → READY`（收到 `register_ok` 后才可发业务消息） |
| 心跳 | plugin → GW，默认 30s 一次 `heartbeat` |
| 注册 | 连接打开后 plugin 立即发 `register`，GW 回 `register_ok` 或 `register_rejected` |
| 上行事件 | plugin → GW，13 个白名单事件类型，全部带 envelope（信封）字段 |
| 下行 action | GW → plugin，7 个 action 类型，参见 §7 |
| 重连 | 指数退避 + 抖动（详见 §8） |
| 错误关闭码 | `4403`/`4408`/`4409` 视为 GW 拒绝，不再重连（详见 §8、§9） |

## 2. 连接契约（endpoint / WebSocket 握手）

### 2.1 endpoint

plugin 作为 WebSocket 客户端，主动连 GW。

| 项 | 值 |
|---|---|
| 协议 | `ws://`（内网/开发）或 `wss://`（生产） |
| 默认 URL | `ws://localhost:8081/ws/agent` |
| 注入方式 | 通过 `globalThis.__MB_DEFAULT_GATEWAY_URL__` 全局注入（host 端在加载插件前注入） |
| 配置覆盖 | `gateway.url`（jsonc / 环境变量），运行时以此为准 |
| 子协议头 | `Sec-WebSocket-Protocol: auth.<base64url(JSON)>`，详见 §3 |
| 编码 | UTF-8 JSON 文本帧；非文本帧或非 JSON 文本由 plugin DEBUG 日志记录后忽略 |

### 2.2 握手流程

```
plugin                                  GW
  │                                      │
  │── HTTP/1.1 GET /ws/agent ──────────▶ │   (含 Sec-WebSocket-Protocol: auth.xxx)
  │                                      │
  │◀── 101 Switching Protocols ────────  │   (鉴权通过)
  │                                      │
  │── { "type": "register", ... } ─────▶ │   (DefaultGatewayConnection.connect()
  │                                      │    在 onopen 中立即发送)
  │                                      │
  │◀── { "type": "register_ok" } ──────  │   (GW 接受 → plugin 进入 READY，开始心跳)
  │                                      │
  │── { "type": "heartbeat", ... } ────▶ │   (每 30s 一次)
  │                                      │
  │  …  双向业务消息收发 …                │
```

异常分支：
- GW 不接受 → 回 `{ "type": "register_rejected", "reason": "..." }`，plugin 触发 `registerRejected` 事件并主动 close（**不重连**）
- 4403 / 4408 / 4409 close code → 视为 GW 拒绝，plugin **不重连**
- 其他 close code → plugin 触发指数退避重连（详见 §8）

### 2.3 `register` 消息

plugin 一旦 onopen 立即发送，**作为唯一的非业务控制消息允许在 `CONNECTED`（未 READY）状态下发送**。

```json
{
  "type": "register",
  "deviceName": "Llllviaaaa-PC",
  "macAddress": "AA:BB:CC:DD:EE:FF",
  "os": "win32",
  "toolType": "openx",
  "toolVersion": "1.0.0"
}
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `type` | String | ✅ | 固定 `"register"` |
| `deviceName` | String | ✅ | 设备名（由 `RegisterMetadata` 解析 host 主机名） |
| `macAddress` | String | ✅ | MAC 地址；解析失败时填占位（GW 仅用作日志维度，不做强校验） |
| `os` | String | ✅ | 操作系统标识，与 Node `process.platform` 对齐（`win32` / `linux` / `darwin`） |
| `toolType` | String | ✅ | 引擎类型；当前已知值：`openx` / `uniassistant` / `codeagent`（见 `KNOWN_TOOL_TYPES`）；未知值 plugin 只打 WARN，**不阻断** |
| `toolVersion` | String | ✅ | 插件版本（来自 `package.json` 经 `resolvePluginVersion()` 取值） |

> **重要**：register 报文里**没有** AK/SK —— 鉴权信息通过 §3 的子协议头携带。register 仅用于把"我是谁、什么 host、什么引擎、什么版本"告诉 GW。

### 2.4 `register_ok` / `register_rejected`（GW → plugin）

```json
{ "type": "register_ok" }
```
```json
{ "type": "register_rejected", "reason": "ak not allowed" }
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `type` | String | ✅ | `"register_ok"` 或 `"register_rejected"` |
| `reason` | String | 仅 rejected | 拒绝原因（仅日志/排查用，plugin 不做语义解析） |

收到 `register_ok` 后 plugin 才进入 `READY` 状态：
1. `reconnectPolicy.reset()` 清空重连窗口
2. 启动心跳定时器（默认 30 秒）
3. 开放业务消息发送（在此之前任何非控制消息发送都会抛 `Gateway connection is not ready` 错）

收到 `register_rejected` 后 plugin **主动 close + 不重连**，等待外部干预（改配置、重启等）。

### 2.5 `heartbeat`（plugin → GW，单向）

```json
{ "type": "heartbeat", "timestamp": "2026-05-12T08:00:00.000Z" }
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `type` | String | ✅ | 固定 `"heartbeat"` |
| `timestamp` | String | ✅ | ISO 8601 当前时刻 |

间隔 `heartbeatIntervalMs`（默认 30000ms），plugin 单向发送，GW **不回包**。GW 端通过 idle timer 检测心跳缺失；plugin 端不依赖心跳维持连接活性，主要用作 GW 侧的存活探测。

心跳是允许在 `CONNECTED`（未 READY）状态下发送的另一类控制消息，但代码上 plugin 只在 READY 之后才启动心跳定时器。

## 3. 鉴权（AK/SK）

### 3.1 凭据来源

plugin 启动时从配置（`auth.ak` / `auth.sk`）读取一对 AK/SK，**不持久化、不下发、不打日志**。配置加载来源（按优先级）：
1. 环境变量 / host 注入
2. 配置文件 jsonc / json
3. host 默认值（开发期）

> AK = Access Key（明文标识），SK = Secret Key（**必须保密**，仅 plugin 与 GW 知晓）。

### 3.2 签名生成

每次握手前调用 `DefaultAkSkAuth.generateAuthPayload()` 生成一份新的鉴权 payload：

```ts
ts    = Math.floor(Date.now() / 1000).toString()    // Unix 秒
nonce = randomUUID()                                // RFC4122 v4
sign  = HMAC-SHA256(SK, AK + ts + nonce).base64()
```

结果是一个 4 字段的 JSON 对象：

```json
{
  "ak": "AK_xxx",
  "ts": "1737302400",
  "nonce": "550e8400-e29b-41d4-a716-446655440000",
  "sign": "MEUCIQDxxx..."
}
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `ak` | String | ✅ | Access Key 明文 |
| `ts` | String | ✅ | Unix 时间戳（**秒**，注意不是毫秒），字符串形式 |
| `nonce` | String | ✅ | 随机 UUID，单次握手一次性使用 |
| `sign` | String | ✅ | HMAC-SHA256(SK, AK+ts+nonce) 的 Base64 编码（**标准 Base64，含 `+/=`**，不是 URL-safe） |

### 3.3 编码与传输

把 §3.2 的 payload JSON 字符串做 **Base64URL** 编码，前缀 `auth.` 后作为 WebSocket 子协议头：

```
Sec-WebSocket-Protocol: auth.<base64url(JSON.stringify(payload))>
```

Base64URL 规则（`encodeBase64Url`）：
- 标准 Base64 输出 → 替换 `+→-` / `/→_` / 去掉末尾 `=` 填充

> **签名 `sign` 字段内部仍是标准 Base64**（含 `+/=`），不要二次替换；只有外层信封的整体 JSON 字符串才做 Base64URL。

### 3.4 GW 端校验逻辑

GW 收到握手请求后：
1. 解析 `Sec-WebSocket-Protocol` 头，剥去 `auth.` 前缀，Base64URL 解码得 JSON
2. 校验 `ak` 是否在白名单
3. 校验 `ts` 是否在容忍窗口内（防重放）
4. 校验 `nonce` 未使用过（一次性）
5. 用对应 SK 重算 `HMAC-SHA256(SK, AK+ts+nonce)` Base64，与 `sign` 字段比对

任一步失败 → GW 直接 HTTP 4xx 拒绝握手 或 在 WebSocket open 后立即 close（code 4403 = 拒绝/不再重连）。

### 3.5 凭据轮换

`generateAuthPayload()` **每次连接前调用一次**（`authPayloadProvider`），所以 plugin 重连会自动产生新的 `ts` / `nonce`，无需额外处理；只有 AK/SK 本身更换需要重启 plugin。

## 4. 上行事件公共字段

plugin → GW 的所有"业务事件"都包在以下五种**信封消息**之一里。除了 §2.3 / §2.5 的 control 消息，业务上行只有这五种 type：

```
register / heartbeat                  ← 控制消息（§2）
tool_event / tool_done / tool_error   ← 业务事件信封（本节）
session_created / status_response     ← 下行 action 的回执（§7 涉及）
```

### 4.1 `tool_event`（事件主信封）

承载 13 种白名单上行事件（详见 §5）。

```json
{
  "type": "tool_event",
  "toolSessionId": "sess_abc123",
  "subagentSessionId": "sub_xyz",
  "subagentName": "code-reviewer",
  "event": {
    "type": "<opencode event type>",
    "properties": { ... }
  }
}
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `type` | String | ✅ | 固定 `"tool_event"` |
| `toolSessionId` | String | ✅ | **父会话**的 sessionId（不是事件原始的 child session）；当事件来自 subagent 时，plugin 通过 `SubagentSessionMapper` 把子会话事件回归到父会话信封 |
| `subagentSessionId` | String | 仅 subagent | 子代理实际的 sessionId（原始事件来源） |
| `subagentName` | String | 仅 subagent | 子代理名（如 `code-reviewer` / `Explore`） |
| `event` | Object | ✅ | opencode 原始事件对象（含 `type` 与 `properties`），可能被 plugin 的 `UpstreamTransportProjector` 做小幅投影（当前仅对 `message.updated` 投影；其他事件原样透传） |

> **subagent 字段始终成对出现**：要么都没有（普通事件），要么 `subagentSessionId` + `subagentName` 同时有。

### 4.2 `tool_done`（会话本轮处理完成）

```json
{
  "type": "tool_done",
  "toolSessionId": "sess_abc123",
  "welinkSessionId": "welk_42",
  "usage": { "input_tokens": 150, "output_tokens": 320 }
}
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `type` | String | ✅ | 固定 `"tool_done"` |
| `toolSessionId` | String | ✅ | 同 `tool_event` |
| `welinkSessionId` | String | | 业务会话 ID（GW 下发时若带则原样回传，便于 GW 路由） |
| `usage` | Object | | 用量统计，结构自由（透传，GW 不解析） |

由 `ToolDoneCompat` 触发：本轮 invoke 完成（如 chat / question_reply / permission_reply 走完）或父会话收到 `session.idle` 时，plugin 主动补发 `tool_done`，标记"本轮 GW 可以认为我空闲"。**不是连接关闭**。

### 4.3 `tool_error`（上行错误）

```json
{
  "type": "tool_error",
  "welinkSessionId": "welk_42",
  "toolSessionId": "sess_abc123",
  "error": "Agent not ready. Current state: CONNECTED",
  "reason": "session_not_found"
}
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `type` | String | ✅ | 固定 `"tool_error"` |
| `welinkSessionId` | String | | 业务会话 ID（来自原 invoke） |
| `toolSessionId` | String | | 引擎 sessionId（若已知） |
| `error` | String | ✅ | 人类可读错误描述（透传到客户端） |
| `reason` | String | | 机器可读错误分类码；当前定义：`session_not_found`；未来可扩展 |

由 `sendToolError` 在以下场景发出：
- invoke 校验失败（`INVALID_PAYLOAD`）
- 状态不就绪（`AGENT_NOT_READY` / `GATEWAY_UNREACHABLE`）
- 下游 SDK 调用失败（`SDK_TIMEOUT` / `SDK_UNREACHABLE`）
- action 未注册（`UNSUPPORTED_ACTION`）

详细映射见 §9 错误处理矩阵。

### 4.4 `session_created`（create_session 回执）

```json
{
  "type": "session_created",
  "welinkSessionId": "welk_42",
  "toolSessionId": "sess_abc123",
  "session": {
    "sessionId": "sess_abc123",
    "session": { "id": "sess_abc123", "title": "..." }
  }
}
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `type` | String | ✅ | 固定 `"session_created"` |
| `welinkSessionId` | String | ✅ | 来自下行 invoke 的 `welinkSessionId`（GW 用以匹配创建请求） |
| `toolSessionId` | String | | 新建会话的引擎 sessionId（成功时必填，失败时走 `tool_error`） |
| `session` | Object | | 引擎返回的 session 对象 raw（含 `sessionId` 与 `session` 字段，详见 `CreateSessionResultData`） |

详见 §7.2 `create_session` action。

### 4.5 `status_response`（status_query 回执）

```json
{
  "type": "status_response",
  "opencodeOnline": true
}
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `type` | String | ✅ | 固定 `"status_response"` |
| `opencodeOnline` | Boolean | ✅ | 引擎（opencode SDK）是否健康 — 来自 `hostClient.global.health()`，调用失败也返回 `false` |

详见 §7.7 `status_query` action。

### 4.6 序列化规则

- JSON.stringify 默认行为；`undefined` 字段省略，`null` 字段保留
- 大 payload（≥ 1MB）plugin 端会打 WARN（不影响发送）
- 控制消息（`register` / `heartbeat`）不计入 `recentOutboundSummaries` 摘要环

## 5. 上行事件清单（plugin → GW）

白名单 13 个事件类型（来自 `SUPPORTED_UPSTREAM_EVENT_TYPES`）；其他 type 由 `EventFilter` / `extractUpstreamEvent` 在入口处过滤并打 `event.extraction_failed` WARN。所有事件统一包在 §4.1 `tool_event` 信封内。

### 5.0 事件总览

| # | event.type | 分类 | 关键 properties 路径 | 必须支持 | 章节 |
|---|---|---|---|---|---|
| 1 | `message.updated` | 消息 | `info.sessionID` / `info.id` / `info.role` | ✅ | §5.1 |
| 2 | `message.part.updated` | 消息 | `part.sessionID` / `part.messageID` / `part.id` | ✅ | §5.2 |
| 3 | `message.part.delta` | 消息 | `sessionID` / `messageID` / `partID` | ✅ | §5.3 |
| 4 | `message.part.removed` | 消息 | `sessionID` / `messageID` / `partID` | | §5.4 |
| 5 | `session.created` | 会话 | `info.id` / `info.title` / `info.parentID?` | | §5.5 |
| 6 | `session.status` | 会话 | `sessionID` / `status.type` | | §5.6 |
| 7 | `session.idle` | 会话 | `sessionID` | ✅ | §5.7 |
| 8 | `session.updated` | 会话 | `info.id` | | §5.8 |
| 9 | `session.error` | 会话 | `sessionID` | | §5.9 |
| 10 | `permission.updated` | 权限 | `sessionID` + permission 字段 | | §5.10 |
| 11 | `permission.asked` | 权限 | `sessionID` + permission 字段 | | §5.11 |
| 12 | `permission.replied` | 权限 | `sessionID` + reply 字段 | | §5.12 |
| 13 | `question.asked` | 交互 | `sessionID` + question 字段 | | §5.13 |

> **字段路径差异**：上面分两族——`info.*` 与 `part.*`（结构化对象）vs 顶层 `sessionID`/`messageID`/`partID`（平铺）。这是 opencode SDK 历史演进的产物（v1 → v2），plugin 在 extractor 里**按事件类型分别处理**，不要假设统一。

### 5.1 `message.updated`

完整 assistant/user message 的状态变更（标题、角色、reasoning 状态等）。每条消息一生中会有若干次 update。

```json
{
  "type": "tool_event",
  "toolSessionId": "sess_abc123",
  "event": {
    "type": "message.updated",
    "properties": {
      "info": {
        "id": "msg_001",
        "sessionID": "sess_abc123",
        "role": "assistant",
        "time": { "created": 1737302400000 },
        "tokens": { "input": 150, "output": 320 },
        "modelID": "claude-opus-4-7"
      }
    }
  }
}
```

| properties 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `info.id` | String | ✅ | 消息 ID |
| `info.sessionID` | String | ✅ | 会话 ID（plugin 提取为 envelope `toolSessionId`） |
| `info.role` | String | ✅ | `"user"` 或 `"assistant"`（其他值 plugin 拒收，打 WARN） |
| `info.*` 其他字段 | Any | | opencode SDK `EventMessageUpdated.properties.info` 的所有字段；plugin 透传，**`UpstreamTransportProjector` 可能对 `message.updated` 做轻量投影**（见 `MessageUpdatedProjector`） |

> 仅 `message.updated` 经过 plugin 端投影；其他事件全部原样透传。

### 5.2 `message.part.updated`

某个 Part（text / tool / thinking 等）的非增量更新（如 tool 完成、Part 删除前的最后状态）。

```json
{
  "type": "tool_event",
  "toolSessionId": "sess_abc123",
  "event": {
    "type": "message.part.updated",
    "properties": {
      "part": {
        "id": "prt_text_01",
        "messageID": "msg_001",
        "sessionID": "sess_abc123",
        "type": "text",
        "text": "完整回复内容…"
      }
    }
  }
}
```

| properties 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `part.id` | String | ✅ | Part ID |
| `part.messageID` | String | ✅ | 所属 message ID |
| `part.sessionID` | String | ✅ | 所属 session ID |
| `part.type` | String | | Part 类型（`text` / `tool` / `thinking` / `file` / `step-start` / `step-finish` 等）⚠️ 完整枚举见 opencode SDK |
| `part.*` 其他字段 | Any | | 透传 |

### 5.3 `message.part.delta`

Part 内部的**增量**变更（流式片段；最高频事件）。

```json
{
  "type": "tool_event",
  "toolSessionId": "sess_abc123",
  "event": {
    "type": "message.part.delta",
    "properties": {
      "sessionID": "sess_abc123",
      "messageID": "msg_001",
      "partID": "prt_text_01",
      "delta": "您好"
    }
  }
}
```

| properties 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `sessionID` | String | ✅ | 平铺，不在 `part.*` 嵌套下 |
| `messageID` | String | ✅ | 平铺 |
| `partID` | String | ✅ | 平铺 |
| `delta` | String / Object | | 增量负载；text 部分是 String，tool 等可能是结构化 ⚠️ 完整 schema 见 opencode SDK v2 |

> **字段名差异**：`messageID`（全大写 ID）/ `partID`，不是 `messageId` / `partId`。Extractor 严格按 opencode SDK 字段名读取。

### 5.4 `message.part.removed`

Part 被引擎丢弃/重写（如纠错回退）。

```json
{
  "type": "tool_event",
  "toolSessionId": "sess_abc123",
  "event": {
    "type": "message.part.removed",
    "properties": {
      "sessionID": "sess_abc123",
      "messageID": "msg_001",
      "partID": "prt_text_01"
    }
  }
}
```

字段路径与 §5.3 一致，仅 type 不同。

### 5.5 `session.created`

新会话（含子代理会话）被引擎创建。**plugin 在转发的同时**会通过 `SubagentSessionMapper.recordSessionCreated()` 记录 `parentSessionId` ↔ `childSessionId` ↔ `agentName` 映射，用于后续 §4.1 的 subagent 字段。

```json
{
  "type": "tool_event",
  "toolSessionId": "sess_child_xyz",
  "event": {
    "type": "session.created",
    "properties": {
      "info": {
        "id": "sess_child_xyz",
        "title": "code-reviewer",
        "parentID": "sess_abc123"
      }
    }
  }
}
```

| properties 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `info.id` | String | ✅ | 新会话 ID |
| `info.title` | String | ✅ | 会话标题；**对子代理而言这就是 `agentName`** |
| `info.parentID` | String | | 父会话 ID；存在时表示子代理会话 |

> ⚠️ 父会话首次创建时 `parentID` 缺省，事件原样转发；只是不会写入 SubagentSessionMapper。

### 5.6 `session.status`

会话状态机变更（running / idle / etc）。

```json
{
  "type": "tool_event",
  "toolSessionId": "sess_abc123",
  "event": {
    "type": "session.status",
    "properties": {
      "sessionID": "sess_abc123",
      "status": { "type": "busy" }
    }
  }
}
```

| properties 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `sessionID` | String | ✅ | 会话 ID |
| `status.type` | String | ✅ | 状态字符串（`busy` / `idle` / `retry` / ...）⚠️ 完整枚举见 opencode SDK |
| `status.*` 其他字段 | Any | | 透传 |

### 5.7 `session.idle`

会话空闲化（一次推理收尾）。**plugin 端会触发 `ToolDoneCompat`**：父会话 idle → 主动补发一条 `tool_done` 信封（§4.2），让 GW 把会话标记为"本轮完成"。**子代理会话的 idle 不补发**（避免父会话被误认为完成）。

```json
{
  "type": "tool_event",
  "toolSessionId": "sess_abc123",
  "event": {
    "type": "session.idle",
    "properties": {
      "sessionID": "sess_abc123"
    }
  }
}
```

| properties 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `sessionID` | String | ✅ | 会话 ID |

### 5.8 `session.updated`

会话元数据变更（如标题改写）。plugin 仅透传，不做特殊处理。

```json
{
  "type": "tool_event",
  "toolSessionId": "sess_abc123",
  "event": {
    "type": "session.updated",
    "properties": {
      "info": {
        "id": "sess_abc123",
        "title": "代码审查 - PR #24"
      }
    }
  }
}
```

| properties 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `info.id` | String | ✅ | 会话 ID |
| `info.*` 其他字段 | Any | | 透传 |

### 5.9 `session.error`

会话级错误（不同于 §4.3 `tool_error` 信封，这是 opencode 引擎抛出的事件）。

```json
{
  "type": "tool_event",
  "toolSessionId": "sess_abc123",
  "event": {
    "type": "session.error",
    "properties": {
      "sessionID": "sess_abc123",
      "error": { "name": "ProviderError", "message": "rate_limited" }
    }
  }
}
```

| properties 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `sessionID` | String | ✅ | 会话 ID |
| `error` | Object | | 引擎错误结构（透传，结构与 opencode SDK 一致） |

### 5.10 `permission.updated`

权限状态变更（如其他客户端已应答、超时回退）。

```json
{
  "type": "tool_event",
  "toolSessionId": "sess_abc123",
  "event": {
    "type": "permission.updated",
    "properties": {
      "sessionID": "sess_abc123",
      "permissionID": "perm_001",
      "status": "expired"
    }
  }
}
```

| properties 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `sessionID` | String | ✅ | 会话 ID |
| `permissionID` | String | | 权限请求 ID ⚠️ |
| 其他字段 | Any | | opencode SDK `EventPermissionUpdated.properties` 透传 ⚠️ |

### 5.11 `permission.asked`

引擎请求用户授权（典型场景：写盘、执行命令）。GW 收到后会路由给业务方（IM / miniapp / external）展示授权卡片。

```json
{
  "type": "tool_event",
  "toolSessionId": "sess_abc123",
  "event": {
    "type": "permission.asked",
    "properties": {
      "sessionID": "sess_abc123",
      "messageID": "msg_001",
      "callID": "call_tool_001",
      "permissionID": "perm_001",
      "type": "filesystem",
      "title": "允许写入文件吗？",
      "metadata": { "path": "/tmp/foo.txt" }
    }
  }
}
```

| properties 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `sessionID` | String | ✅ | 会话 ID |
| `permissionID` | String | ✅ | 权限请求 ID（用户应答 §7.4 `permission_reply` 时回传） |
| `messageID` / `callID` / `type` / `title` / `metadata` | Various | | opencode SDK v2 `EventPermissionAsked.properties` 字段族；透传 ⚠️ 具体字段名请以 SDK 类型为准 |

### 5.12 `permission.replied`

权限应答的事件回放（业务方应答 §7.4 后，引擎内部完成应用，再以事件形式回推）。**不是新一轮 ask**。

```json
{
  "type": "tool_event",
  "toolSessionId": "sess_abc123",
  "event": {
    "type": "permission.replied",
    "properties": {
      "sessionID": "sess_abc123",
      "permissionID": "perm_001",
      "reply": "once"
    }
  }
}
```

| properties 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `sessionID` | String | ✅ | 会话 ID |
| `permissionID` | String | ✅ | 与对应 `permission.asked` 一致 |
| `reply` | String | ✅ | `"once"` / `"always"` / `"reject"` ⚠️ |

> 历史上该事件曾被插件白名单遗漏，导致权限审批 UI 无法持久化；详见 `2026-04-02-plugin-reply-event-forwarding-design.md`。

### 5.13 `question.asked`

引擎向用户提问（向 IM / miniapp 弹起问题卡片）。

```json
{
  "type": "tool_event",
  "toolSessionId": "sess_abc123",
  "event": {
    "type": "question.asked",
    "properties": {
      "sessionID": "sess_abc123",
      "requestID": "q_001",
      "callID": "call_tool_001",
      "question": "您想查询哪个部门？",
      "options": ["IT 部", "研发部"],
      "multiSelect": false
    }
  }
}
```

| properties 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `sessionID` | String | ✅ | 会话 ID |
| `requestID` | String | ✅ | 问题请求 ID（业务方应答 §7.6 时回传） ⚠️ |
| `callID` / `question` / `options` / `multiSelect` | Various | | opencode SDK v2 `EventQuestionAsked.properties`，透传 ⚠️ 字段命名以 SDK 类型为准 |

> 还有 `question.replied` / `question.rejected` 两个事件：前者已在插件白名单内（§5.12 同款语义，针对 question 而非 permission；如有专门需求请扩展白名单或对照 SDK），后者尚未列入白名单。⚠️

### 5.14 事件过滤与丢弃

- **未在白名单的 type**：`isSupportedUpstreamEventType()` 返回 false → 在 `extractUpstreamEvent` 入口处丢弃 + WARN `event.extraction_failed code=unsupported_event`
- **白名单内但必填字段缺失/类型错误**：丢弃 + WARN `event.extraction_failed code=missing_required_field/invalid_field_type`，并附 `eventPreview`（type + propertyKeys 前 8 个）
- **DEFAULT_EVENT_ALLOWLIST**：plugin 默认仅订阅一个子集（去掉了 `session.created`），通过 `EventFilter` 控制；可由配置覆盖

## 6. 下行 Action 公共字段

GW → plugin 的下行消息只有 **两种 type**：`invoke` 和 `status_query`。

```
invoke + action ∈ {chat, create_session, close_session, abort_session,
                   question_reply, permission_reply}   ← §7.1 ~ §7.6
status_query                                            ← §7.7
```

### 6.1 `invoke` 信封

```json
{
  "type": "invoke",
  "action": "chat",
  "welinkSessionId": "welk_42",
  "payload": {
    "toolSessionId": "sess_abc123",
    "text": "请帮我查日程"
  }
}
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `type` | String | ✅ | 固定 `"invoke"` |
| `action` | String | ✅ | 6 个枚举值之一（见上 INVOKE_ACTIONS） |
| `welinkSessionId` | String | 视 action | **`create_session` 必填**；其他 action 可选；plugin 在回执（`session_created` / `tool_done` / `tool_error`）中透传回 GW |
| `payload` | Object | ✅ | 按 action 不同 schema 不同；详见 §7 各小节 |

> ⚠️ `welinkSessionId` 名字来自 welink 业务侧，但**对 plugin 是不透明的**：plugin 只在回执中回传，不做语义解析。其他业务方接入只要保证唯一即可。

### 6.2 `status_query` 信封

```json
{ "type": "status_query" }
```

无 payload。plugin 收到后立即调用引擎 health 探测 → 回 `status_response`（§4.5）。

### 6.3 校验与状态约束

- plugin 用 `normalizeDownstreamMessage()` 解析下行消息；非合法结构直接丢弃 + WARN `runtime.downstream_ignored_non_protocol`
- `invoke` schema 错误（如 `create_session` 缺 `welinkSessionId`）→ 回 `tool_error` 含 `errorCode: "INVALID_PAYLOAD"`
- 非 READY 状态收到的 `invoke` 直接忽略 + WARN（**不**回 `tool_error`），等 plugin 自己重连/恢复后由 GW 重发
- `status_query` 在任意非 DISCONNECTED 状态都能响应（不依赖 READY）

## 7. 下行 Action 清单（GW → plugin）

> **本节排版**：按 `INVOKE_ACTIONS` 顺序（chat / create_session / close_session / permission_reply / abort_session / question_reply） + `status_query`，共 7 节。每节给 payload、回执、错误码、ErrorCode 映射。

### 7.1 chat — 发起一次会话推理

向已存在的引擎会话投递用户输入，触发一次 LLM 推理（产生后续的 `message.*` / `session.idle` 等上行事件）。

#### 下行请求

```json
{
  "type": "invoke",
  "action": "chat",
  "welinkSessionId": "welk_42",
  "payload": {
    "toolSessionId": "sess_abc123",
    "text": "请帮我查今天的日程",
    "assistantId": "asst_default"
  }
}
```

| payload 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `toolSessionId` | String | ✅ | 目标会话 ID（必须先 `create_session` 创建过） |
| `text` | String | ✅ | 用户输入文本 |
| `assistantId` | String | | 助理标识；plugin 透传给 ChatUseCase；缺省时由引擎用默认助理 |

#### 回执

| 情况 | 回执 |
|---|---|
| 成功（仅表示 invoke 调用本身成功） | 不发任何即时回执；后续推理过程通过 §5 上行事件流式推送，最终 `session.idle` 触发 `tool_done`（§4.2） |
| 失败 | `tool_error`（§4.3）含 `welinkSessionId` / `toolSessionId` |

#### ErrorCode 映射（`ChatAction.errorMapper`）

| SDK 错误特征 | ErrorCode |
|---|---|
| `timeout` / `timed out` | `SDK_TIMEOUT` |
| `unreachable` / `connect` / `connection` | `SDK_UNREACHABLE` |
| `not found` / `session not found` | `INVALID_PAYLOAD` |
| `abort` / `cancelled` | `INVALID_PAYLOAD` |
| 其他 | `SDK_UNREACHABLE`（默认兜底） |

非 READY 状态的 chat → 直接拒绝，返回 `stateToErrorCode(state)`（`DISCONNECTED`/`CONNECTING` → `GATEWAY_UNREACHABLE`；`CONNECTED` → `AGENT_NOT_READY`）。

### 7.2 create_session — 创建新会话

#### 下行请求

```json
{
  "type": "invoke",
  "action": "create_session",
  "welinkSessionId": "welk_42",
  "payload": {
    "title": "代码审查 - PR #24",
    "assistantId": "asst_code_review"
  }
}
```

| payload 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `title` | String | | 会话标题（可选；缺省由引擎生成） |
| `assistantId` | String | | 助理标识；用于路由到对应 assistant directory（见 `JsonAssiantDirectoryMappingAdapter`） |

> **`welinkSessionId` 在本 action 必填**（`InvokeMessageByAction['create_session']` 类型约束）；缺失会回 `tool_error` 含 `errorCode: "INVALID_PAYLOAD"`，message=`welinkSessionId is required for create_session`。

#### 回执

成功：
```json
{
  "type": "session_created",
  "welinkSessionId": "welk_42",
  "toolSessionId": "sess_abc123",
  "session": {
    "sessionId": "sess_abc123",
    "session": { "id": "sess_abc123", "title": "代码审查 - PR #24" }
  }
}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `welinkSessionId` | String | 透传自请求 |
| `toolSessionId` | String | 新会话 ID（来自引擎返回） |
| `session` | Object | `CreateSessionResultData` 形态：`{ sessionId, session: { ...raw } }` |

失败：`tool_error` 含 `welinkSessionId`，**不含 `toolSessionId`**（创建未成功）；ErrorCode 同 §7.1 兜底规则，外加 `invalid` / `bad request` → `INVALID_PAYLOAD`。

#### 特殊回执条件

- 引擎返回成功但**没有 sessionId** → plugin 主动回 `tool_error` 含 `errorCode: "SDK_UNREACHABLE"`, message=`create_session returned without sessionId`（保护性兜底）

### 7.3 close_session — 关闭会话

#### 下行请求

```json
{
  "type": "invoke",
  "action": "close_session",
  "welinkSessionId": "welk_42",
  "payload": {
    "toolSessionId": "sess_abc123"
  }
}
```

| payload 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `toolSessionId` | String | ✅ | 要关闭的会话 ID |

#### 回执

成功（由 `ToolDoneCompat` 触发的 `tool_done`，**不是显式 ack**）：

```json
{
  "type": "tool_done",
  "toolSessionId": "sess_abc123",
  "welinkSessionId": "welk_42"
}
```

> ⚠️ `CloseSessionResultData = { sessionId, closed: true }` 在 plugin 内部用，**不直接通过 WS 回**给 GW。GW 通过 `tool_done` 信封感知关闭完成。

失败：`tool_error`（ErrorCode 同上：timeout/unreachable/not found 等映射）。

### 7.4 permission_reply — 应答权限请求

业务方（IM / miniapp）收到 §5.11 `permission.asked` 后，把用户决策回投到 GW，GW 透传给 plugin。

#### 下行请求

```json
{
  "type": "invoke",
  "action": "permission_reply",
  "welinkSessionId": "welk_42",
  "payload": {
    "permissionId": "perm_001",
    "toolSessionId": "sess_abc123",
    "response": "once"
  }
}
```

| payload 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `permissionId` | String | ✅ | 来自 §5.11 的 `permissionID` |
| `toolSessionId` | String | ✅ | 会话 ID |
| `response` | String | ✅ | **严格枚举**：`"once"` / `"always"` / `"reject"`（其他值会触发 errorMapper 走 `INVALID_PAYLOAD`） |

#### 回执

成功：`tool_done`（同 §7.3）。plugin 内部 `PermissionReplyResultData = { permissionId, response, applied: true }`，不直接回 GW。

失败：`tool_error`。

> 关联事件：plugin 应用应答后，opencode 引擎会发 §5.12 `permission.replied` 事件，业务方据此更新 UI 状态。

### 7.5 abort_session — 中止会话

立即中断当前推理（如用户点"停止"按钮）。

#### 下行请求

```json
{
  "type": "invoke",
  "action": "abort_session",
  "welinkSessionId": "welk_42",
  "payload": {
    "toolSessionId": "sess_abc123"
  }
}
```

| payload 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `toolSessionId` | String | ✅ | 要中止的会话 ID |

#### 回执

成功：`tool_done`。plugin 内部 `AbortSessionResultData = { sessionId, aborted: true }`，不直接回 GW。

失败：`tool_error`（注意 errorMapper 把 `not found` 映射为 `INVALID_PAYLOAD`，因为常见原因是会话已经自然完成）。

### 7.6 question_reply — 应答问题

业务方收到 §5.13 `question.asked` 后回投用户应答。

#### 下行请求

```json
{
  "type": "invoke",
  "action": "question_reply",
  "welinkSessionId": "welk_42",
  "payload": {
    "toolSessionId": "sess_abc123",
    "answer": "IT 部",
    "toolCallId": "call_tool_001",
    "requestId": "qreq-uuid-abc"
  }
}
```

| payload 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `toolSessionId` | String | ✅ | 会话 ID |
| `answer` | String | ✅ | 用户应答（**字符串**；多选场景由业务方自行拼接） |
| `toolCallId` | String | | 对应 §5.13 的 `callID` ⚠️；可选——某些场景下 question 与 tool 解耦 |
| `requestId` | String | | **personal scope 快路径**：opencode question request id。非空 → plugin adapter 直接 `POST /question/{requestID}/reply`，跳过 `GET /question` 反查；缺失/空白 → 走 fallback（toolCallId 反查）。空白字符串视为缺失（D8）。SS 端 normalizer 与 plugin 端 normalizer 都按这一规则处理 |

#### 回执

成功：`tool_done`。plugin 内部 `QuestionReplyResultData = { requestId, replied: true }`，不直接回 GW。

失败：`tool_error`。

> 兼容性：旧版 plugin 的 `DownstreamMessageNormalizer` 白名单不含 `requestId`，会将该字段静默丢弃，等同 fallback。新旧两侧自治，无握手。SS 端 rollout 必须先于 miniapp 端，避免老 SS + 新 miniapp 组合（D5）。

> ⚠️ `answer: String` 与云端协议 v2 的 `answers: List<List<String>>` 二维结构**不一致** —— 这是 GW↔plugin 与云端协议两条独立链路的设计差异。GW 在转发时会做协议转换。

### 7.7 status_query — 健康探测

#### 下行请求

```json
{ "type": "status_query" }
```

无 payload。

#### 回执

```json
{
  "type": "status_response",
  "opencodeOnline": true
}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `opencodeOnline` | Boolean | 引擎健康状态（`hostClient.global.health()?.healthy === true`） |

特殊：
- health() 调用本身失败 → plugin 内部 catch 并返回 `opencodeOnline: false`（**不**回 `tool_error`，保持探测语义"无信号也算一种信号"）
- 无 `hostClient.global.health` 实现 → 返回 `opencodeOnline: false`

`status_query` 是**唯一可在非 READY 状态响应**的下行消息（其他 invoke 在非 READY 时被忽略 + WARN）。

## 8. 连接生命周期（重连、心跳、状态机）

### 8.1 状态机

```
       ┌──────────────┐  connect()    ┌──────────────┐  ws.onopen  ┌──────────────┐  register_ok  ┌──────────┐
       │ DISCONNECTED │ ────────────▶ │  CONNECTING  │ ──────────▶ │  CONNECTED   │ ────────────▶ │  READY   │
       └──────┬───────┘               └──────┬───────┘             └──────┬───────┘               └────┬─────┘
              ▲                              │ ws.onerror /                │ register_rejected /        │ ws.onclose
              │                              │ ws.onclose                  │ ws.onclose 4403/4408/4409  │
              │                              ▼                              ▼                            ▼
              │                       ┌──────────────┐               ┌──────────────┐             ┌──────────────┐
              └──────────────────────│ DISCONNECTED │◀──────────────│ DISCONNECTED │◀────────────│ DISCONNECTED │
                                      └──────────────┘               └──────────────┘             └──────────────┘
                                              │
                                              │ attemptReconnect()  (除非 manuallyDisconnected / 4403/4408/4409)
                                              ▼
                                         exponential backoff
```

四种状态（`CONNECTION_STATES`）：

| 状态 | 含义 | 允许发送 |
|---|---|---|
| `DISCONNECTED` | 初始 / 已断 / 主动 disconnect | 无 |
| `CONNECTING` | 已调用 `connect()`，等 ws onopen | 无 |
| `CONNECTED` | ws 已 open，发出 `register`，等 `register_ok` | 仅控制消息（register / heartbeat） |
| `READY` | 收到 `register_ok` 后；心跳定时器已启动 | 全部消息 |

非 READY 状态下尝试发业务消息会抛 `Gateway connection is not ready. Cannot send business message.`。

### 8.2 心跳

- 触发时机：`register_ok` 后立即启动 `setupHeartbeat()`
- 间隔：`heartbeatIntervalMs`，默认 `30000ms`
- 发送：`{ type: "heartbeat", timestamp: <ISO> }`，**直接 `ws.send`**，不走 `send()` 状态校验（避免心跳本身被状态机拦截）
- GW 不回包；plugin 端 onmessage 收到非控制消息会按 §4 业务事件处理

> 心跳是单向探活，并不维持连接活性 — TCP/WebSocket 协议层的活性已经够；这里更多是 GW 侧 idle 检测的依据。

### 8.3 重连策略（`DefaultReconnectPolicy`）

```ts
ReconnectConfig {
  baseMs: 1000,            // 首次退避基数
  maxMs: 30000,            // 单次退避上限
  exponential: true,       // baseMs * 2^(attempt-1)，capped 到 maxMs
  jitter: 'full',          // 0 ~ delay 的均匀分布抖动
  maxElapsedMs: 600000,    // 窗口总预算 10 分钟
}
```

- 退避公式：`min(baseMs * 2^(attempt-1), maxMs)` 然后乘以 [0,1] 的随机数（FULL jitter）
- 窗口起点：首次 `scheduleNextAttempt()` 调用时；`reset()` 清零
- 耗尽条件：`elapsedMs >= maxElapsedMs`，或本次 `elapsedMs + delayMs >= maxElapsedMs`
- 耗尽后：plugin 打 `gateway.reconnect.exhausted` WARN，**停止重连**；需要外部触发重启

### 8.4 不重连场景

- `manuallyDisconnected = true`（用户调 `disconnect()`，或 `register_rejected` 触发）
- 收到 close code ∈ `{4403, 4408, 4409}`（GW 显式拒绝）
- `abortSignal.aborted`

### 8.5 重连时的鉴权

`authPayloadProvider` 在**每次** `connect()` 时调用，所以每次重连都会生成新的 `ts` / `nonce` / `sign`，防止重放窗口失效。AK/SK 本身不变。

### 8.6 lastMessageSummary & recentOutboundSummaries

`DefaultGatewayConnection` 维护两个调试摘要：
- `lastMessageSummary`：方向（sent/received）+ messageType + messageId + payloadBytes + eventType + opencodeMessageId
- `recentOutboundSummaries`：最近 3 条**业务**出站消息的 eventType/toolSessionId/payloadBytes（控制消息不计）

发生 `gateway.close` 时这两个会一起写入 WARN 日志，便于排查"连接是在做什么时断的"。

## 9. 错误处理矩阵

### 9.1 ErrorCode 枚举（`ERROR_CODES`）

| code | 触发场景 |
|---|---|
| `GATEWAY_UNREACHABLE` | plugin 处于 DISCONNECTED / CONNECTING 时收到 invoke |
| `AGENT_NOT_READY` | plugin 处于 CONNECTED（未 READY）时收到 invoke |
| `SDK_TIMEOUT` | 下游引擎 SDK 调用超时（错误信息含 `timeout`/`timed out`/`network`） |
| `SDK_UNREACHABLE` | 下游引擎 SDK 不可达 / 兜底默认 |
| `INVALID_PAYLOAD` | invoke schema 错误、session not found、abort/cancelled、permission_reply.response 非法 |
| `UNSUPPORTED_ACTION` | `ActionRegistry` 中未注册该 action |

### 9.2 场景 → 行为矩阵

| 场景 | plugin 行为 | GW 收到 |
|---|---|---|
| WS 握手鉴权失败 | ws onclose code=4403 | 无 plugin 端 tool_error；GW 自行处理 |
| `register_rejected` | 主动 close + 不重连 + emit `registerRejected` | 无回包 |
| 非 READY 状态收到 invoke | 丢弃 + WARN `runtime.invoke.ignored_not_ready` | **无回包**（GW 视为该 plugin 暂不可用） |
| `invoke` schema 不合法（如 create_session 缺 welinkSessionId） | `tool_error` errorCode=`INVALID_PAYLOAD` | tool_error |
| action 不存在 | `tool_error` errorCode=`UNSUPPORTED_ACTION` | tool_error |
| chat 时 SDK 超时 | `tool_error` errorCode=`SDK_TIMEOUT` | tool_error |
| chat 时 session not found | `tool_error` errorCode=`INVALID_PAYLOAD` | tool_error |
| chat 成功完成（session.idle） | 透传 idle 事件 + 主动补 `tool_done` | tool_event + tool_done |
| create_session 引擎返回空 sessionId | `tool_error` errorCode=`SDK_UNREACHABLE` | tool_error |
| status_query 时引擎不健康 | `status_response { opencodeOnline: false }` | status_response，**不是 tool_error** |
| 子代理会话 `session.idle` | 透传事件，**不**补 `tool_done` | 仅 tool_event |
| 未在白名单的上行事件 | 丢弃 + WARN `event.extraction_failed` | 无 |
| 上行事件必填字段缺失 | 丢弃 + WARN `code=missing_required_field` | 无 |
| 大 payload（≥ 1MB） | WARN `gateway.send.large_payload`，照常发 | 收到大消息 |
| WS 连接断开（非拒绝码） | 触发 `attemptReconnect()` 指数退避 | GW 看到 close |
| WS 重连耗尽（10 min 内未成功） | WARN `gateway.reconnect.exhausted` 停止重连 | GW 看到 close 且不再上线 |

### 9.3 `tool_error.reason` 分类

`ToolErrorClassifier.classify()` 把 `ActionResult` + action 名映射为 `reason`：

| reason | 含义 | 典型触发 |
|---|---|---|
| `session_not_found` | 引擎报告会话不存在 | chat / abort / close 时 sessionId 已失效 |
| (undefined) | 其他情况不分类 | 默认 |

> reason 字段是**可选**的二级分类，主要用来让 GW 判断是否值得回滚业务状态（session_not_found 通常意味着用户需要重新建会话）。

### 9.4 错误冒泡链路

```
SDK 抛错
   ↓
Action.execute() catch → errorMapper(error) → ErrorCode
   ↓
ActionResult.failure
   ↓
BridgeRuntime.sendToolError → ToolErrorClassifier.classify → reason
   ↓
WS send { type:"tool_error", error, reason, welinkSessionId, toolSessionId }
```

## 10. 配置项一览

### 10.1 `BridgeConfig` 结构

```jsonc
{
  "enabled": true,
  "debug": false,
  "bridgeDirectory": "/path/to/bridge",
  "config_version": 1,
  "gateway": {
    "url": "ws://localhost:8081/ws/agent",
    "channel": "openx",
    "heartbeatIntervalMs": 30000,
    "reconnect": {
      "baseMs": 1000,
      "maxMs": 30000,
      "exponential": true,
      "jitter": "full",
      "maxElapsedMs": 600000
    }
  },
  "sdk": { "timeoutMs": 10000 },
  "auth": { "ak": "AK_xxx", "sk": "SK_yyy" },
  "events": { "allowlist": [ "message.updated", "message.part.delta", "..." ] }
}
```

### 10.2 字段说明

| 路径 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `enabled` | bool | — | 总开关；false 时插件不连 GW |
| `debug` | bool | false | 详细日志（含 onOpen/onMessage 原始帧） |
| `bridgeDirectory` | string | host 默认 | 插件 IO 目录 |
| `config_version` | int | 1 | 协议版本，predict-保护配置 schema 演进 |
| `gateway.url` | string | `ws://localhost:8081/ws/agent` | GW WS 地址 |
| `gateway.channel` | string | `openx` | `BridgeChannelPort` 通道（影响 sessionDirectory 解析） |
| `gateway.heartbeatIntervalMs` | int | 30000 | 心跳间隔 |
| `gateway.reconnect.baseMs` | int | 1000 | 退避基数 |
| `gateway.reconnect.maxMs` | int | 30000 | 退避上限 |
| `gateway.reconnect.exponential` | bool | true | 是否指数退避 |
| `gateway.reconnect.jitter` | enum | `full` | `none` 或 `full` |
| `gateway.reconnect.maxElapsedMs` | int | 600000 | 重连总预算（10 min） |
| `sdk.timeoutMs` | int | 10000 | 下游引擎 SDK 调用超时 |
| `auth.ak` / `auth.sk` | string | — | AKSK 凭据 |
| `events.allowlist` | string[] | `DEFAULT_EVENT_ALLOWLIST`（12 项） | 上行事件订阅白名单（要 ⊆ §5 的 13 项总集） |

### 10.3 host 端注入

| 方式 | 说明 |
|---|---|
| `globalThis.__MB_DEFAULT_GATEWAY_URL__` | 在加载插件前注入字符串，作为 `gateway.url` 缺省值 |
| `process.env.BRIDGE_ASSISTANT_DIRECTORY_MAP_FILE` | JSON 文件路径，提供 assistant → directory 映射（`create_session` 用） |
| host 注入 client | 提供 opencode SDK 实例（`SdkAdapter` 适配） |

## 11. 字段↔代码映射

下表把协议关键概念映射到代码位置（`plugins/agent-plugin/plugins/message-bridge/` 相对路径），便于反查实现细节。

### 11.1 连接 / 鉴权

| 协议要素 | 代码源 |
|---|---|
| 默认 GW URL + `__MB_DEFAULT_GATEWAY_URL__` 注入 | `src/config/default-gateway-url.ts:1-14` |
| AKSK payload 生成（HMAC-SHA256 base64） | `src/connection/AkSkAuth.ts:29-44` |
| `Sec-WebSocket-Protocol: auth.<b64url>` 编码 | `src/connection/GatewayConnection.ts:185-195` (`encodeBase64Url` / `buildAuthSubprotocol`) |
| `connect()` 流程 + onopen 发 register | `src/connection/GatewayConnection.ts:263-417` |
| `register_ok` / `register_rejected` 处理 | `src/connection/GatewayConnection.ts:651-672` (`handleControlMessage`) |
| 状态机（4 个状态） | `src/types/common.ts:69-93` (`CONNECTION_STATES` + `stateToErrorCode`) |
| 心跳定时器 | `src/connection/GatewayConnection.ts:507-524` (`setupHeartbeat`) |
| 4403/4408/4409 不重连 | `src/connection/GatewayConnection.ts:88` (`GATEWAY_REJECTION_CLOSE_CODES`) |
| 指数退避 + jitter | `src/connection/ReconnectPolicy.ts:63-97` (`scheduleNextAttempt`) |
| 重连耗尽 | `src/connection/ReconnectPolicy.ts:99-111` (`getExhaustedDecision`) |

### 11.2 协议消息类型

| 协议要素 | 代码源 |
|---|---|
| 上行 5 种 type（register / heartbeat / tool_event / tool_done / tool_error / session_created / status_response） | `src/contracts/transport-messages.ts:6-14` (`TRANSPORT_UPSTREAM_MESSAGE_TYPES`) |
| 下行 2 种 type（invoke / status_query） | `src/contracts/downstream-messages.ts:1-2` (`DOWNSTREAM_MESSAGE_TYPES`) |
| 下行 6 个 action 枚举 | `src/contracts/downstream-messages.ts:4-11` (`INVOKE_ACTIONS`) |
| 每个 action 的 payload schema | `src/contracts/downstream-messages.ts:17-51` |
| 每个 action 的 ResultData schema | `src/contracts/downstream-messages.ts:68-117` |
| 上行 13 个白名单事件 | `src/contracts/upstream-events.ts:19-33` (`SUPPORTED_UPSTREAM_EVENT_TYPES`) |
| 默认订阅白名单（12 项，去掉 session.created） | `src/contracts/upstream-events.ts:74-87` (`DEFAULT_EVENT_ALLOWLIST`) |
| 上行事件 normalizer | `src/protocol/upstream/UpstreamEventExtractor.ts:484-519` (`extractUpstreamEvent`) |
| 每个事件的必填字段提取器 | `src/protocol/upstream/UpstreamEventExtractor.ts:154-440` (`UPSTREAM_EVENT_EXTRACTORS`) |
| 上行事件转 transport（仅 `message.updated` 投影） | `src/transport/upstream/DefaultUpstreamTransportProjector.ts:5-12` |

### 11.3 Action 路由与执行

| 协议要素 | 代码源 |
|---|---|
| `ActionRouter` 路由表 | `src/action/ActionRouter.ts:14-65` |
| `ActionRegistry`（7 个 action 注册） | `src/runtime/BridgeRuntime.ts:448-462` (`registerActions`) |
| 下行消息分发入口 | `src/runtime/BridgeRuntime.ts:464-670` (`handleDownstreamMessage`) |
| `chat` 行为 | `src/action/ChatAction.ts:14-111` |
| `create_session` 行为（含 directory 决策） | `src/action/CreateSessionAction.ts:19-203` |
| `close_session` 行为 | `src/action/CloseSessionAction.ts:15-90` |
| `permission_reply` 行为 | `src/action/PermissionReplyAction.ts:16-96` |
| `abort_session` 行为 | `src/action/AbortSessionAction.ts:13-90` |
| `question_reply` 行为 | `src/action/QuestionReplyAction.ts:13-107` |
| `status_query` 行为 + 健康探测 | `src/action/StatusQueryAction.ts:13-83` |

### 11.4 回执构造

| 协议要素 | 代码源 |
|---|---|
| `tool_event` 信封构造 + subagent 字段 | `src/runtime/BridgeRuntime.ts:378-411` |
| `session_created` 回执构造 | `src/runtime/BridgeRuntime.ts:603-615` |
| `status_response` 回执构造 | `src/runtime/BridgeRuntime.ts:524-532` |
| `tool_done` 主动补发（session.idle / invoke 完成） | `src/runtime/BridgeRuntime.ts:858-895` (`sendToolDone`) + `runtime/compat/ToolDoneCompat.ts` |
| `tool_error` 构造 + reason 分类 | `src/runtime/BridgeRuntime.ts:816-856` (`sendToolError`) + `src/error/ToolErrorClassifier.ts` |
| SubagentSessionMapper（父子会话映射） | `src/session/SubagentSessionMapper.ts` |

### 11.5 错误码

| 协议要素 | 代码源 |
|---|---|
| `ERROR_CODES` 枚举 | `src/types/common.ts:73-82` |
| `stateToErrorCode` 状态→错误码映射 | `src/types/common.ts:84-93` |
| 每个 action 的 errorMapper | 各 action 文件末尾的 `errorMapper(error)` 方法 |

## 12. 给插件作者 / 引擎适配者的注意事项

### 12.1 高优先级 ⚠️

1. **register 必须在 onopen 立即发**：`DefaultGatewayConnection.connect()` 已经做了这件事；自实现 plugin 时不要把 register 推到 `setTimeout` 或异步链路里——会触发 GW 端 idle 拒绝。
2. **AK/SK 不要写进 register**：鉴权在子协议头里完成，register 只携带 device/tool 元信息。
3. **`sign` 用标准 Base64，外层信封用 Base64URL**：`HMAC-SHA256(SK, AK+ts+nonce).digest('base64')` 输出含 `+/=` 不改；整个 payload JSON 字符串才做 URL-safe 编码。
4. **`ts` 是秒不是毫秒**：`Math.floor(Date.now() / 1000)` —— 容易写成 `Date.now()` 直接当字符串发出，GW 会拒签。
5. **`tool_done` 不是 ack**：成功的 invoke 不立刻回 ack；GW 通过 `session.idle` 事件 + `ToolDoneCompat` 补发的 `tool_done` 才感知到本轮完成。
6. **`create_session` 的 `welinkSessionId` 必填**：其他 action 都是可选的。失败时回 `tool_error` 含 `welinkSessionId`，**不含 `toolSessionId`**（因为还没建出来）。
7. **subagent 字段成对**：`subagentSessionId` 和 `subagentName` 要么都有要么都没有；不要单独发其中一个，否则 GW 路由层可能拿不到 agentName。
8. **子代理会话的 idle 不要补 tool_done**：父会话才是 GW 的"业务单位"。`SubagentSessionMapper` 已经处理；自实现时也要避免在子会话 idle 时上报 tool_done。
9. **非 READY 收到 invoke 静默丢弃**：不回 tool_error。GW 会自己决定要不要重试。
10. **重连耗尽后不会自愈**：10 分钟（默认）内连不上就停了；需要监控 + 外部重启策略兜底。

### 12.2 易混字段速查

| 协议字段 | 易混点 | 正确 |
|---|---|---|
| 上行事件大写 ID | `messageId` / `partId` ❌ | `messageID` / `partID`（注意是 §5.3/§5.4 平铺事件的字段名；§5.2 的 `part.messageID` 也是大写） ✅ |
| 平铺 vs 嵌套 sessionID | `properties.sessionID` 还是 `properties.part.sessionID` ❌ 混用 | `message.part.updated` 用 `part.sessionID`；`message.part.delta` / `.removed` 用顶层 `sessionID` ✅（见 extractor） |
| Action 命名 | `createSession` / `closeSession`（驼峰）❌ | `create_session` / `close_session`（**snake_case**） ✅ |
| Reply 命名 | `question_reply` 还是 `questionReply` | `question_reply` / `permission_reply`（snake_case） ✅ |
| 业务会话 ID | `sessionId` / `welinkSessionID` ❌ | `welinkSessionId`（**小写 d**） ✅ |
| 引擎会话 ID | `sessionId` 或 `toolSessionId` | 在 plugin 协议里**统一叫 `toolSessionId`**；只有 internal Action 接口才叫 `sessionId` ✅ |
| 应答字段（question） | `answers: [["IT 部"]]`（云端协议）❌ | plugin 协议是 `answer: "IT 部"`（**字符串单值**） ✅ |
| 应答字段（permission） | `reply: 'once'` ❌ | plugin 下行 payload 是 `response: 'once'`；上行事件 §5.12 才是 `reply` ✅ |
| 子代理字段名 | `subAgentSessionId` / `agentSessionId` ❌ | `subagentSessionId` / `subagentName`（**全小写连写**） ✅ |
| 事件白名单遗漏 | 漏 `permission.replied` / `question.replied` 导致 UI 状态丢失 | 检查 `SUPPORTED_UPSTREAM_EVENT_TYPES` 与白名单一致 ✅ |
| `tool_done` vs `tool_done` (action result) | 把 `CloseSessionResultData = {closed:true}` 直接当回执发 ❌ | 用 §4.2 信封 `{type:"tool_done", toolSessionId, welinkSessionId}` ✅ |
| `status_response.opencodeOnline` | 把 SDK 异常当 `tool_error` 上报 ❌ | 异常 catch + 回 `opencodeOnline:false`，**不**走 tool_error ✅ |

### 12.3 实现新引擎适配的步骤

如果你要把另一套 Agent 引擎（非 opencode）接入 GW，按以下顺序：

1. 实现 `GatewayConnection`（握手 + 鉴权 + register + 重连，可直接复用 `DefaultGatewayConnection` + `DefaultAkSkAuth`）
2. 实现 `Action` 接口的 7 个 action（execute + errorMapper），把引擎 SDK 调用包进去
3. 实现 `extractUpstreamEvent` 等价物：把引擎事件 normalize 到 §5 形态（关键字段：toolSessionId / messageID / partID / sessionID / status）
4. 注册 actions 到 `ActionRegistry`，启动 `BridgeRuntime`
5. 配置 `register.toolType` 为引擎名（GW 端用此区分；新引擎可向平台申请加入 `KNOWN_TOOL_TYPES`，未知值只 WARN）
6. 自测：用 mock GW（或真实 GW 测试环境）验证 7 个 action + 13 个上行事件全链路

## 13. 变更记录

| 日期 | 版本 | 改动 |
|---|---|---|
| 2026-05-12 | v1.0 | 首发，对应 commit `2f81faf` 后的当前 HEAD：含 4 状态机 / AKSK 子协议 / 13 上行事件 / 7 下行 action / SubagentSessionMapper / ToolDoneCompat / 指数退避重连 |
