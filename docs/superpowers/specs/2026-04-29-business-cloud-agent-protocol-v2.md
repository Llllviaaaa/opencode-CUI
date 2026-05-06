# 业务云端助理协议 v2（云端对接完整规范）

> **目的**：为云端业务助理团队提供完整、可直接对照开发的协议契约。
> **协议版本**：v2 — 对应 ai-gateway / skill-server 实施版本（commit `aa27c49`）
> **生效路径**：SS SysConfig `cloud_route.v2_enabled = "1"` 启用 v2
> **代码源**：见 §13 字段-代码映射

---

## 目录

- [1. 概览](#1-概览)
- [2. 接入契约（callback config 接口）](#2-接入契约callback-config-接口)
- [3. 鉴权](#3-鉴权)
- [4. cloudRequest 公共字段](#4-cloudrequest-公共字段)
- [5. Chat 协议（channelType=2 sse / 3 websocket）](#5-chat-协议channeltype2-sse--3-websocket)
- [6. 云端推送事件清单（chat SSE 流内）](#6-云端推送事件清单chat-sse-流内)
- [7. Question Reply 协议（channelType=1 webhook）](#7-question-reply-协议channeltype1-webhook)
- [8. Permission Reply 协议（channelType=1 webhook）](#8-permission-reply-协议channeltype1-webhook)
- [9. chat SSE / WebSocket 长连接保活](#9-chat-sse--websocket-长连接保活)
- [10. v1 / v2 切换](#10-v1--v2-切换)
- [11. 错误处理矩阵](#11-错误处理矩阵)
- [12. 配置项一览](#12-配置项一览)
- [13. 字段↔代码映射（实现验证）](#13-字段代码映射实现验证)
- [14. 给云端开发者的注意事项](#14-给云端开发者的注意事项)

---

## 1. 概览

### 1.1 角色

| 角色 | 职责 |
|---|---|
| **api-server** | 提供回调订阅查询接口（§2），按 `(ak, scope)` 返回云端 endpoint |
| **ai-gateway**（GW） | 收到 SS INVOKE → 查 callback 配置 → 按 channelType 调用云端 |
| **skill-server**（SS） | 业务入站翻译为 INVOKE 经内部 WS 发给 GW |
| **云端业务助理**（你们） | 实现 chat / q_r / p_r 三个 endpoint，按本协议消费 cloudRequest 与回推事件 |

### 1.2 总链路

```
[业务方 / IM / miniapp]
        │ HTTP
        ▼
   skill-server                                       api-server
        │ INVOKE (internal WS)                          ▲
        ▼                                              │
    ai-gateway ─── POST /gateway/callbacks/config ─────┘
        │
        ├── channelType=2/3 (sse/ws) ──────► 云端 chat endpoint        (你们要实现)
        │
        ├── channelType=1 (webhook, q_r) ──► 云端 question_reply endpoint  (你们要实现)
        │
        └── channelType=1 (webhook, p_r) ──► 云端 permission_reply endpoint (你们要实现)
```

### 1.3 你们要实现的三个 endpoint

| Scope（订阅时声明） | channelType（订阅时声明） | 云端 endpoint 行为 |
|---|---|---|
| `callback:weagent:chat` | `2` (sse) 或 `3` (websocket) | 接 cloudRequest，**长连接**返回 SSE 事件流 |
| `callback:weagent:question_reply` | `1` (webhook) | 接 cloudRequest（含 `replyContext.type=question_reply`），返 200 ACK |
| `callback:weagent:permission_reply` | `1` (webhook) | 接 cloudRequest（含 `replyContext.type=permission_reply`），返 200 ACK |

> **q_r / p_r 是 fire-and-forget**：云端只要返 2xx 即认为成功，平台不消费响应 body。云端处理用户应答后，**通过原 chat 的 SSE/WS 长连接**继续推送后续 text.delta 等事件（chat 连接由平台自动保活，参见 §9）。

---

## 2. 接入契约（callback config 接口）

平台（GW）通过此接口向 api-server 查询云端 endpoint。**云端业务团队在订阅时**需要把 chat / q_r / p_r 三个 endpoint 注册到 api-server 的 `sys_callback_subscription` 表（或同级配置中心），api-server 据此响应平台。

### 2.1 接口定义

```
POST /gateway/callbacks/config
Content-Type: application/json
Authorization: Bearer <token>
```

### 2.2 请求体

```json
{
  "ak": "AK123456789",
  "scope": "callback:weagent:chat"
}
```

| 字段 | 类型 | 必填 | 取值 |
|---|---|---|---|
| `ak` | String | ✅ | 应用 Access Key |
| `scope` | String | ✅ | `callback:weagent:chat` / `callback:weagent:question_reply` / `callback:weagent:permission_reply` |

### 2.3 响应（已订阅）

```json
{
  "code": "200",
  "messageZh": "操作成功",
  "messageEn": "Success",
  "data": {
    "ak": "AK123456789",
    "scope": "callback:weagent:chat",
    "channelType": 2,
    "channelAddress": "https://cloud.example.com/api/v1/chat",
    "authType": 1
  }
}
```

| `data` 字段 | 类型 | 说明 |
|---|---|---|
| `ak` | String | 同请求 |
| `scope` | String | 同请求 |
| `channelType` | int | `1`=WebHook（仅 q_r/p_r 合法）；`2`=SSE（chat 合法）；`3`=WebSocket（chat 合法） |
| `channelAddress` | String | 云端 endpoint 完整 URL（含 https:// 协议头） |
| `authType` | int | `0`=NoAuth；`1`=SOA；`2`=APIG（鉴权 header 由 GW 按此值决定，详见 §3） |

### 2.4 响应（未订阅）

```json
{
  "code": "200",
  "messageZh": "操作成功",
  "messageEn": "Success",
  "data": null
}
```

### 2.5 响应（错误）

| code | 说明 |
|---|---|
| `400` | 请求参数错误（ak 或 scope 缺失/非法） |
| `404` | 回调资源不存在 |

GW 在 `data: null` 或 HTTP 非 200 时把任务回流为 `tool_error`。

### 2.6 缓存

GW 对此接口结果按 `(ak, scope)` 维度 Redis 缓存，key `gw:cloud:route:v2:{ak}:{scope}`，默认 TTL 300s（`gateway.cloud-route.cache-ttl-seconds`）。订阅变更后最长 5 分钟生效，云端如需立即生效可联系平台清缓存。

---

## 3. 鉴权

GW 调用云端 endpoint（chat / q_r / p_r）时根据 §2.3 的 `authType` 字段附加 header：

### 3.1 authType=0（NoAuth）

平台**不附加任何鉴权 header**。仅适合内网测试，生产慎用。

```http
POST <channelAddress> HTTP/1.1
Content-Type: application/json
X-Trace-Id: 5fed8def-084b-4e3d-94c6-7094a2b05c8a
```

### 3.2 authType=1（SOA）

```http
POST <channelAddress> HTTP/1.1
Content-Type: application/json
X-Trace-Id: 5fed8def-084b-4e3d-94c6-7094a2b05c8a
X-Auth-Type: soa
X-App-Id: app_test_001
```

### 3.3 authType=2（APIG）

```http
POST <channelAddress> HTTP/1.1
Content-Type: application/json
X-Trace-Id: 5fed8def-084b-4e3d-94c6-7094a2b05c8a
X-Auth-Type: apig
X-App-Id: app_test_001
```

### 3.4 公共 header

| Header | 何时出现 | 说明 |
|---|---|---|
| `Content-Type: application/json` | 总有 | body 永远是 JSON |
| `X-Trace-Id: <uuid>` | 当 `traceId` 非空时 | 用于跨服务链路追踪，建议云端日志透传 |
| `X-App-Id: <appId>` | 当 `appId` 非空且 authType ∈ {1,2} 时 | v1 模式由 `hisAppId` 提供；v2 模式 `appId=null` 时**不发** |
| `X-Auth-Type: soa\|apig` | authType ∈ {1,2} 时 | 标识鉴权方案 |

> **当前 SOA / APIG 实现是占位**（仅写 X-Auth-Type 与 X-App-Id），真实鉴权方案落地时由平台填充。云端在 v2 接入期可按 NoAuth (`authType=0`) 或基于 X-App-Id 的简单校验起步。

---

## 4. cloudRequest 公共字段

无论 chat / q_r / p_r，GW 发送给云端的 body 都是这个结构（基于 `DefaultCloudRequestStrategy.build()`）。q_r / p_r 在末尾追加 `replyContext` 嵌套对象。

### 4.1 字段表

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `type` | String | ✅ | 内容类型，默认 `"text"`（图片场景为 `"IMAGE-V1"`）。**注意**：这里的 `type` 是内容类型字段，不是事件类型 |
| `content` | String | ✅ | 用户输入文本；q_r/p_r 时为空字符串 `""` |
| `assistantAccount` | String | | 助理账号（业务方维度的助理标识） |
| `sendUserAccount` | String | | 发起请求的用户账号（IM/miniapp/external 入参传入） |
| `imGroupId` | String | | IM 群聊 ID（仅群聊场景；单聊和 miniapp/external 场景为 null） |
| `clientLang` | String | ✅ | 默认 `"zh"` |
| `clientType` | String | | 客户端类型（可选；当前总为 null） |
| `topicId` | String | ✅ | 平台维度的会话主题 ID，等价 `toolSessionId`，**关联整轮对话**与后续 q_r/p_r |
| `messageId` | String | | 消息 ID（业务方传入，用于幂等） |
| `extParameters` | Object | ✅ | 扩展参数容器，**始终为 object**（缺省时 `{}`） |
| `extParameters.businessExtParam` | Object | ✅ | 业务方自由扩展 JSON；平台不解析、不修改，从入参信封透传到此 |
| `extParameters.platformExtParam` | Object | ✅ | 平台扩展（首期占位 `{}`，未来可能含 traceId / 租户标识等） |
| `replyContext` | Object | 仅 q_r/p_r | 应答上下文（chat 时**不写入**），详见 §7 / §8 |

### 4.2 序列化注意

- 平台用 `@JsonInclude(NON_NULL)` 序列化：**null 字段省略**，但 `extParameters.businessExtParam` / `platformExtParam` 即使 `{}` 也会写入。
- chat 的 cloudRequest **不会有 `replyContext` 字段**（缺省即不出现）。

---

## 5. Chat 协议（channelType=2 sse / 3 websocket）

### 5.1 GW → 云端：建立连接 + 发 cloudRequest

#### channelType=2 (SSE)

```http
POST <channelAddress> HTTP/1.1
Content-Type: application/json
Accept: text/event-stream
X-Trace-Id: 5fed8def-084b-4e3d-94c6-7094a2b05c8a
X-Auth-Type: soa
X-App-Id: app_test_001

{
  "type": "text",
  "content": "请帮我查一下今天的日程",
  "assistantAccount": "e2e-cb-bot",
  "sendUserAccount": "900001",
  "imGroupId": null,
  "clientLang": "zh",
  "clientType": null,
  "topicId": "cloud-cb3eb844125245ed9fd6938da59fd8bd",
  "messageId": "1777476131508",
  "extParameters": {
    "businessExtParam": {},
    "platformExtParam": {}
  }
}
```

云端响应：

```http
HTTP/1.1 200 OK
Content-Type: text/event-stream

data: {"type":"tool_event","toolSessionId":"cloud-cb3eb...","event":{...}}

data: {"type":"tool_event","toolSessionId":"cloud-cb3eb...","event":{...}}

...

data: {"type":"tool_done","toolSessionId":"cloud-cb3eb...","usage":{...}}
```

每条 `data:` 行是独立 JSON。流以 `tool_done` 或 `tool_error` 终止；GW 收到终态事件后关闭连接。

#### channelType=3 (WebSocket)

GW 用 `Sec-WebSocket-Protocol` 协商（如有）+ 上述鉴权 header 完成 WebSocket 握手；连接建立后 **GW 主动发送 cloudRequest 作为首条 text frame**。云端按 SSE 同款 framing 推送事件（每个 text frame 是一个 JSON）。

### 5.2 事件包络

所有 chat 流内事件统一包络：

```json
{
  "type": "tool_event" | "tool_done" | "tool_error",
  "toolSessionId": "<同 cloudRequest.topicId>",
  "event": {
    "type": "<子事件类型>",
    "properties": { ... }
  }
}
```

| 包络字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `type` | String | ✅ | 三种之一：`tool_event`（中间事件）、`tool_done`（终态成功）、`tool_error`（终态失败） |
| `toolSessionId` | String | ✅ | 与 cloudRequest.topicId 完全一致；平台据此回路由到对应 SS session |
| `event` | Object | 仅 `tool_event` | 子事件载荷（**`tool_done` / `tool_error` 不需要 event 字段**，详见 §5.3） |

### 5.3 终态事件包装层

终态事件在外层包装层（不在 `event.properties` 内），完整字段见 §6.21（tool_done）和 §6.22（tool_error）。

---

## 6. 云端推送事件清单（chat SSE 流内）

每个子事件是包在 `tool_event` 内的 `event.properties`。**所有 Part 级事件 properties 必填 `messageId` 与 `partId`**（缺失时平台兜底自动生成 `cloud-msg-<uuid>` / `cloud-part-<type>-<uuid8>` 并打 WARN 日志，但建议云端总是发齐）。

下面 **22 个事件类型**按分类列出，每个含完整 JSON 示例 + 字段表。

### 6.0 事件类型总览

| 分类 | event.type | 说明 | 必须支持 | 章节 |
|---|---|---|---|---|
| **文本内容** | `text.delta` | 流式文本增量 | ✅ | §6.1 |
| | `text.done` | 文本片段完成（完整内容） | | §6.2 |
| | `thinking.delta` | 深度思考增量 | | §6.3 |
| | `thinking.done` | 深度思考完成（完整内容） | | §6.4 |
| **工具执行** | `tool.update` | 工具调用状态更新 | | §6.5 |
| | `step.start` | 执行步骤开始 | | §6.6 |
| | `step.done` | 执行步骤完成（含 usage） | | §6.7 |
| **云端扩展** | `planning.delta` | 规划内容增量 | | §6.8 |
| | `planning.done` | 规划内容完成 | | §6.9 |
| | `searching` | 搜索中（关键词列表） | | §6.10 |
| | `search_result` | 搜索结果（多条，按 index 排序） | | §6.11 |
| | `reference` | 引用结果（多条，按 index 排序） | | §6.12 |
| **文件** | `file` | 文件附件输出 | | §6.13 |
| **追问** | `ask_more` | 追问建议（仅展示，不触发 q_r） | | §6.14 |
| **会话状态** | `session.status` | 会话状态变更（busy/idle/retry） | | §6.15 |
| | `session.title` | 会话标题更新 | | §6.16 |
| | `session.error` | 会话级错误 | | §6.17 |
| **交互** | `question` | 向用户提问，等待 q_r | | §6.18 |
| | `permission.ask` | 请求用户授权，等待 p_r | | §6.19 |
| | `permission.reply` | 权限应答回放（assistant role） | | §6.20 |
| **结束标记** | `tool_done` | 本次请求处理完成，会话进入 idle | ✅ | §6.21 |
| | `tool_error` | 本次请求处理异常 | ✅ | §6.22 |

> **最小实现**：云端只需实现 `text.delta` + `tool_done` 即可完成最基本的 chat 对话。其余按业务需要选用。
>
> **顺序约束**：终态事件 (`tool_done` / `tool_error`) 必须是流的最后一条；之后云端 close 流即可。

### 6.1 `text.delta`（流式回复文本片段）

```json
{
  "type": "tool_event",
  "toolSessionId": "cloud-cb3eb844125245ed9fd6938da59fd8bd",
  "event": {
    "type": "text.delta",
    "properties": {
      "content": "您好",
      "role": "assistant",
      "messageId": "msg-001",
      "partId": "prt-text-01"
    }
  }
}
```

| properties 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `content` | String | ✅ | 当前片段文本（增量） |
| `role` | String | ✅ | 通常 `"assistant"` |
| `messageId` | String | ✅ | 消息 ID（同一条消息内的 N 个 delta 共享） |
| `partId` | String | ✅ | 片段 ID（同一文本块内的 N 个 delta 共享） |

### 6.2 `text.done`（流式回复结束）

```json
{
  "type": "tool_event",
  "toolSessionId": "cloud-cb3eb844125245ed9fd6938da59fd8bd",
  "event": {
    "type": "text.done",
    "properties": {
      "content": "您好！这是对您问题的完整回复...",
      "role": "assistant",
      "messageId": "msg-001",
      "partId": "prt-text-01"
    }
  }
}
```

| properties 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `content` | String | ✅ | 完整文本（不是 delta） |
| `role` | String | ✅ | `"assistant"` |
| `messageId` | String | ✅ | 与对应 text.delta 一致 |
| `partId` | String | ✅ | 与对应 text.delta 一致 |

### 6.3 `thinking.delta`（流式思考片段）

```json
{
  "type": "tool_event",
  "toolSessionId": "cloud-cb3eb844125245ed9fd6938da59fd8bd",
  "event": {
    "type": "thinking.delta",
    "properties": {
      "content": "让我整理一下",
      "role": "assistant",
      "messageId": "msg-001",
      "partId": "prt-think-01"
    }
  }
}
```

| properties 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `content` | String | ✅ | 思考片段（增量） |
| `role` | String | ✅ | `"assistant"` |
| `messageId` | String | ✅ | |
| `partId` | String | ✅ | |

### 6.4 `thinking.done`（思考结束）

```json
{
  "type": "tool_event",
  "toolSessionId": "cloud-cb3eb844125245ed9fd6938da59fd8bd",
  "event": {
    "type": "thinking.done",
    "properties": {
      "content": "让我整理一下搜索结果来回答",
      "role": "assistant",
      "messageId": "msg-001",
      "partId": "prt-think-01"
    }
  }
}
```

字段同 6.3，`content` 为完整思考文本。

### 6.5 `tool.update`（工具调用状态更新）

```json
{
  "type": "tool_event",
  "toolSessionId": "cloud-cb3eb844125245ed9fd6938da59fd8bd",
  "event": {
    "type": "tool.update",
    "properties": {
      "toolName": "calendar_query",
      "toolCallId": "call-tool-001",
      "input": "{\"date\":\"2026-04-29\"}",
      "output": "{\"events\":[]}",
      "status": "completed",
      "error": null,
      "title": "查询日程",
      "messageId": "msg-001",
      "partId": "prt-tool-01"
    }
  }
}
```

| properties 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `toolName` | String | ✅ | 工具名 |
| `toolCallId` | String | ✅ | 工具调用 ID |
| `input` | String | | 入参（JSON 字符串或自由文本） |
| `output` | String | | 出参（JSON 字符串或自由文本） |
| `status` | String | ✅ | 取值：`"running"` / `"completed"` / `"failed"` 等 |
| `error` | String | | 失败时的错误描述（status=failed 时填） |
| `title` | String | | 工具卡片标题（前端展示用） |
| `messageId` | String | ✅ | |
| `partId` | String | ✅ | |

### 6.6 `step.start`（推理步骤开始）

```json
{
  "type": "tool_event",
  "toolSessionId": "cloud-cb3eb844125245ed9fd6938da59fd8bd",
  "event": {
    "type": "step.start",
    "properties": {
      "messageId": "msg-001",
      "role": "assistant"
    }
  }
}
```

| properties 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `messageId` | String | ✅ | |
| `role` | String | | `"assistant"` |

> **step.start 不含 partId**（消息级事件，非 part 级）。

### 6.7 `step.done`（推理步骤结束 + 用量）

```json
{
  "type": "tool_event",
  "toolSessionId": "cloud-cb3eb844125245ed9fd6938da59fd8bd",
  "event": {
    "type": "step.done",
    "properties": {
      "messageId": "msg-001",
      "role": "assistant",
      "tokens": {
        "input": 150,
        "output": 320,
        "reasoning": 80
      },
      "cost": 0.0123,
      "reason": "max_tokens"
    }
  }
}
```

| properties 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `messageId` | String | ✅ | |
| `role` | String | | `"assistant"` |
| `tokens` | Object | | 各维度 token 计数；key 任意（如 `input`/`output`/`reasoning`），value 为数字 |
| `cost` | Number | | 本步骤计费（USD 等单位由云端定义）；`0` 时不输出 |
| `reason` | String | | 结束原因，例如 `"stop"` / `"max_tokens"` / `"tool_calls"` |

### 6.8 `planning.delta`（计划生成片段）

```json
{
  "type": "tool_event",
  "toolSessionId": "cloud-cb3eb844125245ed9fd6938da59fd8bd",
  "event": {
    "type": "planning.delta",
    "properties": {
      "content": "分析用户问题，",
      "messageId": "msg-001",
      "partId": "prt-plan-01"
    }
  }
}
```

| properties 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `content` | String | ✅ | 计划片段 |
| `messageId` | String | ✅ | |
| `partId` | String | ✅ | |

### 6.9 `planning.done`（计划生成结束）

```json
{
  "type": "tool_event",
  "toolSessionId": "cloud-cb3eb844125245ed9fd6938da59fd8bd",
  "event": {
    "type": "planning.done",
    "properties": {
      "content": "分析用户问题，准备搜索相关资料",
      "messageId": "msg-001",
      "partId": "prt-plan-01"
    }
  }
}
```

字段同 6.8，`content` 为完整计划文本。

### 6.10 `searching`（搜索中）

```json
{
  "type": "tool_event",
  "toolSessionId": "cloud-cb3eb844125245ed9fd6938da59fd8bd",
  "event": {
    "type": "searching",
    "properties": {
      "keywords": ["日程", "今天", "会议"],
      "messageId": "msg-001",
      "partId": "prt-search-01"
    }
  }
}
```

| properties 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `keywords` | List\<String\> | ✅ | 搜索关键词列表 |
| `messageId` | String | ✅ | |
| `partId` | String | ✅ | |

### 6.11 `search_result`（搜索结果）

```json
{
  "type": "tool_event",
  "toolSessionId": "cloud-cb3eb844125245ed9fd6938da59fd8bd",
  "event": {
    "type": "search_result",
    "properties": {
      "searchResults": [
        {"index": "1", "title": "今日会议安排", "source": "公司日历"},
        {"index": "2", "title": "项目周会", "source": "团队日历"}
      ],
      "messageId": "msg-001",
      "partId": "prt-search-01"
    }
  }
}
```

| properties 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `searchResults` | List\<Object\> | ✅ | **注意字段名是 `searchResults`，不是 `results`** |
| `searchResults[].index` | String | | 序号（字符串形式，便于与正文中的引用 `[1]`/`[2]` 对齐） |
| `searchResults[].title` | String | | 标题 |
| `searchResults[].source` | String | | 来源 |
| `messageId` | String | ✅ | |
| `partId` | String | ✅ | |

### 6.12 `reference`（引用列表）

```json
{
  "type": "tool_event",
  "toolSessionId": "cloud-cb3eb844125245ed9fd6938da59fd8bd",
  "event": {
    "type": "reference",
    "properties": {
      "references": [
        {
          "index": "1",
          "title": "今日会议安排",
          "source": "公司日历",
          "url": "https://example.com/calendar/1",
          "content": "10:00 项目周会，会议室 A"
        }
      ],
      "messageId": "msg-001",
      "partId": "prt-ref-01"
    }
  }
}
```

| properties 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `references` | List\<Object\> | ✅ | 引用项列表 |
| `references[].index` | String | | 序号 |
| `references[].title` | String | | 标题 |
| `references[].source` | String | | 来源 |
| `references[].url` | String | | URL |
| `references[].content` | String | | 摘要内容 |
| `messageId` | String | ✅ | |
| `partId` | String | ✅ | |

### 6.13 `file`（文件返回）

```json
{
  "type": "tool_event",
  "toolSessionId": "cloud-cb3eb844125245ed9fd6938da59fd8bd",
  "event": {
    "type": "file",
    "properties": {
      "fileName": "report.pdf",
      "fileUrl": "https://cdn.example.com/files/abc123.pdf",
      "fileMime": "application/pdf",
      "messageId": "msg-001",
      "partId": "prt-file-01"
    }
  }
}
```

| properties 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `fileName` | String | ✅ | 文件名（前端展示） |
| `fileUrl` | String | ✅ | 文件 URL |
| `fileMime` | String | | MIME 类型 |
| `messageId` | String | ✅ | |
| `partId` | String | ✅ | |

### 6.14 `ask_more`（追问列表）

```json
{
  "type": "tool_event",
  "toolSessionId": "cloud-cb3eb844125245ed9fd6938da59fd8bd",
  "event": {
    "type": "ask_more",
    "properties": {
      "askMoreQuestions": [
        "还有什么想了解的？",
        "需要更详细的说明吗？"
      ],
      "messageId": "msg-001",
      "partId": "prt-askmore-01"
    }
  }
}
```

| properties 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `askMoreQuestions` | List\<String\> | ✅ | **注意字段名是 `askMoreQuestions`，不是 `questions`**（避免与 §6.18 question 事件混淆） |
| `messageId` | String | ✅ | |
| `partId` | String | ✅ | |

> ask_more 是**单纯展示**给用户的"猜你想问"列表，**不会触发 question_reply**。如果云端要让用户应答，请用 §6.18 `question` 事件。

### 6.15 `session.status`（会话状态）

```json
{
  "type": "tool_event",
  "toolSessionId": "cloud-cb3eb844125245ed9fd6938da59fd8bd",
  "event": {
    "type": "session.status",
    "properties": {
      "sessionStatus": "busy"
    }
  }
}
```

| properties 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `sessionStatus` | String | ✅ | 取值 `"idle"` / `"busy"` 等（`"idle"` 时平台清理 partSeq 计数器） |

> session.status 是**会话级**事件（无 messageId / partId）。

### 6.16 `session.title`（会话标题）

```json
{
  "type": "tool_event",
  "toolSessionId": "cloud-cb3eb844125245ed9fd6938da59fd8bd",
  "event": {
    "type": "session.title",
    "properties": {
      "title": "查询今日日程"
    }
  }
}
```

| properties 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `title` | String | ✅ | 会话标题（前端用作侧边栏会话名） |

### 6.17 `session.error`（会话错误）

```json
{
  "type": "tool_event",
  "toolSessionId": "cloud-cb3eb844125245ed9fd6938da59fd8bd",
  "event": {
    "type": "session.error",
    "properties": {
      "error": "session expired"
    }
  }
}
```

| properties 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `error` | String | ✅ | 错误描述（前端给用户的提示） |

### 6.18 `question`（交互式提问 — 触发 question_reply）

支持**两种形态**，平台兼容：

#### 形态 1：单问题扁平

```json
{
  "type": "tool_event",
  "toolSessionId": "cloud-cb3eb844125245ed9fd6938da59fd8bd",
  "event": {
    "type": "question",
    "properties": {
      "toolCallId": "call-q-001",
      "messageId": "msg-001",
      "partId": "prt-q-01",
      "header": "请确认操作",
      "question": "您想查询哪个部门的信息？",
      "options": ["IT部", "研发部", "市场部"],
      "multiSelect": false,
      "extParam": {
        "category": "department-query",
        "ttlSeconds": 300
      }
    }
  }
}
```

#### 形态 2：多问题数组（OpenCode 风格）

```json
{
  "type": "tool_event",
  "toolSessionId": "cloud-cb3eb844125245ed9fd6938da59fd8bd",
  "event": {
    "type": "question",
    "properties": {
      "toolCallId": "call-q-002",
      "messageId": "msg-002",
      "partId": "prt-q-02",
      "questions": [
        {
          "header": "请选择部门",
          "question": "您想查询哪个部门的信息？",
          "options": ["IT部", "研发部"],
          "multiSelect": false
        },
        {
          "header": "请选择时间范围",
          "question": "想查询什么时间段？",
          "options": [
            { "label": "本月", "description": "近 30 天" },
            { "label": "本季", "description": "近 90 天" },
            { "label": "本年" }
          ],
          "multiSelect": true
        }
      ],
      "extParam": {
        "category": "multi-step-query"
      }
    }
  }
}
```

#### 字段表

| properties 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `toolCallId` | String | ✅ | 工具调用 ID（用户应答 q_r 时回传，关联**整组** questions） |
| `messageId` | String | ✅ | 消息 ID |
| `partId` | String | ✅ | 片段 ID |
| `header` | String | | 问题标题（仅形态 1） |
| `question` | String | 形态 1 必填 | 问题内容（仅形态 1） |
| `options` | List\<Option\> | | 选项数组（仅形态 1）；元素见下方"Option 形态" |
| `multiSelect` | Boolean | | 是否多选；缺省 / `false`=单选；`true`=多选（仅形态 1） |
| `questions` | List\<Object\> | 形态 2 必填 | 多问题数组（仅形态 2），每项含 `header / question / options / multiSelect`（同形态 1 的扁平字段） |
| `extParam` | Object | | 云端定义的扩展属性，平台**原样**透传到 SS / miniapp / external WS（**不解析、不修改**） |

#### Option 形态（`options` 数组每个元素）

| 形态 | 示例 | 说明 |
|---|---|---|
| 字符串 | `"IT部"` | 简单选项；平台规范化为 `{label: "IT部", description: null}` |
| 对象 | `{"label": "本月", "description": "近 30 天"}` | 含选项解释，前端可在选项下方展示 description |

> **重要**：
> - 同一次 question 事件的 N 个 question 项**共享同一个 toolCallId**，q_r 应答时 `answers` 数组按 `questions[i]` 的顺序对应（参见 §7）。
> - q_r 应答只回 option 的 **`label` 字符串**（即使 option 是对象形态，也不需要回 description）。
> - `options` 数组的元素若 label 为空或缺失，会被平台丢弃。

### 6.19 `permission.ask`（请求授权 — 触发 permission_reply）

```json
{
  "type": "tool_event",
  "toolSessionId": "cloud-cb3eb844125245ed9fd6938da59fd8bd",
  "event": {
    "type": "permission.ask",
    "properties": {
      "permissionId": "perm-001",
      "permType": "filesystem",
      "messageId": "msg-003",
      "partId": "prt-p-01",
      "title": "允许访问本地文件吗？",
      "metadata": {
        "scope": "read-only",
        "path": "/home/user/docs"
      }
    }
  }
}
```

| properties 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `permissionId` | String | ✅ | 权限请求 ID（用户应答 p_r 时回传） |
| `permType` | String | | 权限类型（云端自定义；平台原样透传，例如 `"filesystem"` / `"network"` / `"shell"`） |
| `messageId` | String | ✅ | |
| `partId` | String | ✅ | |
| `title` | String | | 标题（前端权限卡片展示） |
| `metadata` | Object | | 云端定义的扩展元数据，平台**原样透传** |

### 6.20 `permission.reply`（已授权回放 — 不触发新一轮 p_r）

云端在收到 p_r 后**可选地**推送此事件，作为对前端 UI 的"已应答"回执。**这不是新的 permission 请求**，平台不会触发 permission_reply。

```json
{
  "type": "tool_event",
  "toolSessionId": "cloud-cb3eb844125245ed9fd6938da59fd8bd",
  "event": {
    "type": "permission.reply",
    "properties": {
      "permissionId": "perm-001",
      "permType": "filesystem",
      "response": "once",
      "messageId": "msg-003",
      "partId": "prt-p-01"
    }
  }
}
```

| properties 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `permissionId` | String | ✅ | 必须与对应的 permission.ask 一致 |
| `permType` | String | | 同 permission.ask |
| `response` | String | ✅ | 取值 `"once"` / `"always"` / `"reject"`，**与用户实际应答的 p_r.response 一致** |
| `messageId` | String | ✅ | 与 permission.ask 一致 |
| `partId` | String | ✅ | 与 permission.ask 一致（前端用同一卡片更新状态） |

> permission.reply 事件 **不会触发**平台保活机制 pause（参见 §9）；只有 `question` 与 `permission.ask` 才触发。

### 6.21 `tool_done`（本次请求处理完成 / 终态事件）

终态事件，**外层 type 直接是 `tool_done`，不在 `event.properties` 内**。云端推送此事件后应主动关闭 SSE / WS 连接，平台收到后会清理会话状态并把 `session.status=idle` 投递给前端。

```json
{
  "type": "tool_done",
  "toolSessionId": "cloud-cb3eb844125245ed9fd6938da59fd8bd",
  "usage": {
    "input_tokens": 150,
    "output_tokens": 320,
    "reasoning_tokens": 80
  }
}
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `type` | String | ✅ | 固定 `"tool_done"` |
| `toolSessionId` | String | ✅ | 同 cloudRequest.topicId |
| `usage` | Object | | 用量统计；含 `input_tokens` / `output_tokens` 等数字字段 |
| `usage.input_tokens` | int | | 输入 token 数 |
| `usage.output_tokens` | int | | 输出 token 数 |
| `usage.<其他>` | int / Number | | 其他 token 维度，平台原样保留（如 `reasoning_tokens` / `cache_creation_tokens` / `cache_read_tokens` 等） |

> **重要**：`tool_done` 不是包在 `tool_event` 内的子事件，而是与 `tool_event` 平级的**终态包络**。这是与 §6.1-6.20 所有事件最大的结构差异。

### 6.22 `tool_error`（本次请求处理异常 / 终态事件）

终态事件，结构与 `tool_done` 同（外层）。表示本次请求处理失败，云端推送后应关闭连接。

```json
{
  "type": "tool_error",
  "toolSessionId": "cloud-cb3eb844125245ed9fd6938da59fd8bd",
  "error": "model timeout after 30s"
}
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `type` | String | ✅ | 固定 `"tool_error"` |
| `toolSessionId` | String | ✅ | 同 cloudRequest.topicId |
| `error` | String | ✅ | 错误描述（人类可读，会被透传到客户端展示） |

> 平台自身的错误（cloudRequest 解析失败 / 上游不可达等）也会用 `tool_error` 形态投递给 SS，不会走云端 SSE 流。云端只需关心**自己产生的业务错误**用此事件上报。

---

## 7. Question Reply 协议（channelType=1 webhook）

用户对 §6.18 `question` 事件的应答。

### 7.1 GW → 云端：WebHook 同步 POST

```http
POST <channelAddress> HTTP/1.1
Content-Type: application/json
X-Trace-Id: 1f56dca0-f92c-42d2-9a5f-aa7f9627b372
X-Auth-Type: soa
X-App-Id: app_test_001

{
  "type": "text",
  "content": "",
  "assistantAccount": "e2e-cb-bot",
  "sendUserAccount": "900001",
  "imGroupId": null,
  "clientLang": "zh",
  "clientType": null,
  "topicId": "cloud-cb3eb844125245ed9fd6938da59fd8bd",
  "messageId": null,
  "extParameters": {
    "businessExtParam": {},
    "platformExtParam": {}
  },
  "replyContext": {
    "type": "question_reply",
    "toolCallId": "call-q-001",
    "answers": [
      ["IT部"]
    ]
  }
}
```

### 7.2 `replyContext` 字段表（q_r）

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `type` | String | ✅ | 固定 `"question_reply"`（云端可据此分流） |
| `toolCallId` | String | ✅ | 关联回原 §6.18 question 事件的 `toolCallId`（同一组 questions 共享） |
| `answers` | List\<List\<String\>\> | ✅ | 应答数组。**外层** N 题（与 question 事件 `questions[i]` 顺序对应；形态 1 时只有 1 题，外层长度=1）；**内层** 每题 0-N 个选项 label 或自由文本 |

### 7.3 `answers` 全部形态完整示例

#### 形态 A：单题单选

云端推送（形态 1，multiSelect=false）：
```json
{ "questions": [{"options":["IT部","研发部"], "multiSelect":false}] /* 简化展示 */ }
```
平台 q_r：
```json
"answers": [["IT部"]]
```

#### 形态 B：单题多选

云端推送（multiSelect=true）：
```json
{ "questions": [{"options":["A","B","C"], "multiSelect":true}] }
```
平台 q_r：
```json
"answers": [["A", "C"]]
```

#### 形态 C：多题（每题独立单/多选）

云端推送（形态 2，2 题）：
```json
{
  "questions": [
    { "options":["IT部","研发部"], "multiSelect":false },
    { "options":["本月","本季","本年"], "multiSelect":true }
  ]
}
```
平台 q_r：
```json
"answers": [
  ["IT部"],
  ["本月", "本季"]
]
```

#### 形态 D：开放式问答（无 options）

云端推送（无 options 字段或 options 为空）：
```json
{ "question": "请描述您遇到的问题" }
```
平台 q_r（用户输入文本）：
```json
"answers": [["我无法登录系统，提示密码错误"]]
```

#### 形态 E：选项含 description（仅 label 回传）

云端推送（option 对象形态）：
```json
{
  "questions": [{
    "options": [
      { "label": "本月", "description": "近 30 天" },
      { "label": "本年" }
    ],
    "multiSelect": false
  }]
}
```
平台 q_r（**不带 description**）：
```json
"answers": [["本月"]]
```

### 7.4 云端 → GW：响应

#### 7.4.1 成功（fire-and-forget）

```http
HTTP/1.1 200 OK
Content-Type: application/json

{
  "code": "200",
  "message": "ok"
}
```

> 状态码只要在 **2xx 范围**（200/201/202/204 都接受）即认为成功。**响应 body 平台不消费**（任意结构都行，建议 `{code, message}` 便于排查）。
>
> 云端在返 200 后**通过原 chat 长连接**继续推 text.delta 等后续事件（参见 §9 保活）。

#### 7.4.2 失败

```http
HTTP/1.1 503 Service Unavailable
```

或网络/超时 / 任意非 2xx → GW 向 SS 回流 `tool_error`，**不重试**。

WebHook 总超时 `gateway.cloud.timeout.webhook-timeout-seconds`，默认 10s。

---

## 8. Permission Reply 协议（channelType=1 webhook）

用户对 §6.19 `permission.ask` 事件的授权决策。

### 8.1 GW → 云端：WebHook 同步 POST

```http
POST <channelAddress> HTTP/1.1
Content-Type: application/json
X-Trace-Id: 9f68447c-ec31-588d-a999-cc94a7d72e6a
X-Auth-Type: soa
X-App-Id: app_test_001

{
  "type": "text",
  "content": "",
  "assistantAccount": "e2e-cb-bot",
  "sendUserAccount": "900001",
  "imGroupId": null,
  "clientLang": "zh",
  "clientType": null,
  "topicId": "cloud-cb3eb844125245ed9fd6938da59fd8bd",
  "messageId": null,
  "extParameters": {
    "businessExtParam": {},
    "platformExtParam": {}
  },
  "replyContext": {
    "type": "permission_reply",
    "permissionId": "perm-001",
    "response": "once"
  }
}
```

### 8.2 `replyContext` 字段表（p_r）

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `type` | String | ✅ | 固定 `"permission_reply"` |
| `permissionId` | String | ✅ | 关联回原 §6.19 permission.ask 的 `permissionId` |
| `response` | String | ✅ | 取值之一：`"once"` / `"always"` / `"reject"` |

### 8.3 三种 response 完整示例

| response | 语义 | 示例 |
|---|---|---|
| `"once"` | 本次允许（一次性授权） | 见 §8.1 |
| `"always"` | 永久允许（云端可缓存该用户对此 permType 的授权） | 把 §8.1 中 `response` 改为 `"always"` |
| `"reject"` | 拒绝 | 把 §8.1 中 `response` 改为 `"reject"`，云端通常应停止后续动作 |

### 8.4 云端 → GW：响应

完全同 §7.4：2xx fire-and-forget；失败 GW 回 `tool_error` 不重试。

---

## 9. chat SSE / WebSocket 长连接保活

### 9.1 触发暂停

云端推送以下任一事件后，平台**暂停 chat 连接的 idle timer**：

- `event.type == "question"` （§6.18）
- `event.type == "permission.ask"` （§6.19）

### 9.2 触发恢复

平台收到下一个**非 question / permission.ask** 的事件（`text.delta` / `tool.update` / `step.done` / 任意普通事件）后，**自动恢复 idle timer**。

### 9.3 兜底超时

`maxDuration` 不暂停，作为兜底。默认 1800s（30min）。超过后 GW 主动关闭 chat 连接并向 SS 回 `tool_error("max_duration ...")`。

### 9.4 云端实现期望

```
云端推送 question 事件
   ↓
等待平台调 q_r WebHook（用户应答）
   ↓ 收到 q_r 后
通过原 chat SSE/WS 连接继续推 text.delta / tool.update / tool_done 等
```

云端**不要**主动关闭 chat SSE/WS 连接等待 q_r —— 必须保持开放，平台会自动暂停 idle 计时。

> 如果 q_r WebHook 失败（云端没收到用户应答），平台**不会主动 close 原 chat 连接**——chat 会一直挂着到 maxDuration 才被关闭，期间云端可以选择主动推 `tool_error` 提前结束。

---

## 10. v1 / v2 切换

### 10.1 切换机制

```
SS SysConfig 表
  configType: "cloud_route"
  configKey:  "v2_enabled"
  configValue: "1"  → v2 模式（走本协议 §2 新接口）
              其它 → v1 模式（走旧 GET-with-body 接口；q_r/p_r 在 v1 模式下被拒绝）
  status: 1
```

### 10.2 GW 端缓存

GW 通过 HTTP 调 `GET /api/admin/configs/value?type=cloud_route&key=v2_enabled` 拿到当前开关，结果在 GW 内存缓存 30 秒。配置切换最长 30s 全量生效。

### 10.3 平滑迁移

- **v1 模式现状**：仅 `callback:weagent:chat` scope 接通；q_r/p_r action 进入 GW 直接被拒（回 `tool_error`）。
- **切换路径**：先把 v2 接口在 api-server 上线 + chat scope 数据双写（v1 旧表 + v2 callback 表）→ SS SysConfig 设 `v2_enabled=1` → 验证 → 老 v1 接口下线。

---

## 11. 错误处理矩阵

| 场景 | GW 行为 | SS 收到 |
|---|---|---|
| api-server `/gateway/callbacks/config` 网络/超时 | 返 null | `tool_error("Cloud route info not found")` |
| api-server 返 `data: null`（未订阅） | 返 null | `tool_error("Cloud route info not found")` |
| api-server 返非 200 业务码 | 返 null + WARN | `tool_error("Cloud route info not found")` |
| chat 收到 channelType=1 (webhook) | 拒绝 | `tool_error("Invalid channel type for chat: webhook")` |
| q_r/p_r 收到 channelType=2/3 (sse/ws) | 拒绝 | `tool_error("Invalid channel type for reply: sse")` |
| WebHook 网络/超时 | 不重试 | `tool_error("WebHook delivery failed: <reason>")` |
| WebHook 返 5xx / 4xx | 不重试 | `tool_error("WebHook returned <status>")` |
| **WebHook 返 2xx** | **不回流任何 GatewayMessage**（fire-and-forget） | — |
| WebHook 调用被中断（Thread.interrupt） | 恢复中断标志 | `tool_error("WebHook interrupted")` |
| chat first-event-timeout（默认 30s） | 关闭连接 | `tool_error("first_event_timeout (elapsed: 30s)")` |
| chat idle-timeout（默认 30s，pause 期间不计） | 关闭连接 | `tool_error("idle_timeout (elapsed: 30s)")` |
| chat max-duration（默认 1800s） | 关闭连接 | `tool_error("max_duration (elapsed: 1800s)")` |
| v1 模式收到 q_r / p_r action | 拒绝 | `tool_error("question_reply not enabled (v1 mode or AK not subscribed)")` |
| `authType` 取值未知（非 0/1/2） | fail-fast 抛 IllegalArgumentException | `tool_error("Cloud agent error: Unknown cloud auth type: ...")` |
| 云端推送 `tool_error`（云端主动报错） | 直接转发 | 客户端收到 `tool_error` |

---

## 12. 配置项一览

| 配置项 | 默认 | 说明 |
|---|---|---|
| `gateway.cloud-route.api-url` | — | v1 旧接口 URL |
| `gateway.cloud-route.bearer-token` | — | v1 token |
| `gateway.cloud-route.cache-ttl-seconds` | 300 | callback config 路由结果缓存 TTL |
| `gateway.cloud-route.v2-api-url` | — | v2 接口 URL（指向 api-server） |
| `gateway.cloud-route.v2-bearer-token` | — | v2 token |
| `gateway.skill-server.api-base` | http://localhost:8082 | SS API 基础 URL（GW 查 SysConfig 用） |
| `gateway.skill-server.api-token` | — | GW 调 SS 的 Bearer token |
| `gateway.cloud.timeout.first-event-timeout-seconds` | 30 | chat 首事件超时 |
| `gateway.cloud.timeout.idle-timeout-seconds` | 30 | chat idle 超时（受 §9 pause/resume 控制） |
| `gateway.cloud.timeout.max-duration-seconds` | 1800 | chat 整体兜底超时（30min） |
| `gateway.cloud.timeout.webhook-timeout-seconds` | 10 | q_r/p_r WebHook POST 超时 |

---

## 13. 字段↔代码映射（实现验证）

| 协议要素 | 代码源 |
|---|---|
| cloudRequest 公共字段（chat / q_r / p_r） | `skill-server` `DefaultCloudRequestStrategy.build()` |
| q_r/p_r `replyContext` 嵌套对象 | 同上 line 60-74 |
| q_r `answers` 解析（stringified `[[...]]` / 一维 / 纯文本兜底） | `skill-server` `BusinessScopeStrategy.parseAnswers()` |
| 按 action 写 reply 字段 | `skill-server` `BusinessScopeStrategy.buildInvoke()` action 分支 |
| 21 个云端事件类型注册 | `skill-server` `CloudEventTranslator.init()` line 58-103 |
| `question` 兼容两种形态 + multiSelect + extParam | `CloudEventTranslator.handleQuestion()` + `extractMultiSelect()` |
| Option 字符串/对象规范化 | `skill-server` `ProtocolUtils.extractQuestionOptions()` |
| `permission.ask` / `permission.reply` 字段 | `CloudEventTranslator.handlePermissionAsk()` / `handlePermissionReply()` |
| `text.delta`/`text.done` 字段 | `CloudEventTranslator.handleTextDelta/Done()` |
| `thinking.delta`/`thinking.done` 字段 | `CloudEventTranslator.handleThinkingDelta/Done()` |
| `tool.update` 字段 | `CloudEventTranslator.handleToolUpdate()` |
| `step.start`/`step.done` 字段 | `CloudEventTranslator.handleStepStart/Done()` |
| `planning.delta`/`planning.done` 字段 | `CloudEventTranslator.handlePlanningDelta/Done()` |
| `searching` 字段（`keywords`） | `CloudEventTranslator.handleSearching()` |
| `search_result` 字段（`searchResults`） | `CloudEventTranslator.handleSearchResult()` |
| `reference` 字段（`references`） | `CloudEventTranslator.handleReference()` |
| `file` 字段 | `CloudEventTranslator.handleFile()` |
| `ask_more` 字段（`askMoreQuestions`） | `CloudEventTranslator.handleAskMore()` |
| `session.status`/`session.title`/`session.error` | `CloudEventTranslator.handleSession*()` |
| GW callback config 调用（v2） | `ai-gateway` `GatewayCallbackResolver.resolve()` |
| GW v1/v2 路由选择 + 30s 内存缓存 | `ai-gateway` `CallbackConfigService.currentVersion()` |
| GW 调 SS SysConfig 拉开关 | `ai-gateway` `SkillServerConfigClient.getConfigValue()` |
| chat SSE/WS 长连接保活（pause/resume） | `ai-gateway` `CloudConnectionLifecycle.pauseIdleTimer/resumeIdleTimer()` + `Sse/WebSocketProtocolStrategy` 事件触发 |
| WebHook 同步 POST + fire-and-forget + tool_error | `ai-gateway` `WebHookExecutor.execute()` |
| chat / q_r / p_r action→scope 映射 | `ai-gateway` `CloudAgentService.ACTION_TO_SCOPE` |
| channelType vs action 校验 | `ai-gateway` `CloudAgentService.handleInvoke()` |
| 鉴权 strategy（none/soa/apig） | `ai-gateway` `NoAuthStrategy` / `SoaAuthStrategy` / `ApigAuthStrategy` + `CloudAuthService` |

---

## 14. 给云端开发者的注意事项

### 14.1 高优先级 ⚠️

1. **`searchResults` 不是 `results`**：§6.11 `search_result` 事件的字段名严格是 `searchResults`，写错平台会忽略整个数组。
2. **`askMoreQuestions` 不是 `questions`**：§6.14 `ask_more` 事件的字段名严格是 `askMoreQuestions`（与 §6.18 question 事件区分）。
3. **`messageId` + `partId` 必填**：所有 Part 级事件（除 `step.start` 外）必须带这两个字段。缺失时平台会自动兜底生成但打 WARN，建议总是发齐以便日志追溯。
4. **`tool_done` / `tool_error` 没有 event 字段**：直接在外层带 `usage` / `error`，而不是包到 `event.properties` 里。
5. **q_r / p_r 是 fire-and-forget**：返 200 即结束本 WebHook，**继续推后续 chat 事件用原 SSE/WS 长连接**，不要再开新的连接。
6. **同一组 questions 共享 toolCallId**：N 个 question 项**不要**各自分配独立的 toolCallId；用同一个 toolCallId，由 `answers[i]` 索引对应回每个 question 项。
7. **option 形态**：选项可以是 `"label string"` 或 `{"label":"...","description":"..."}` 对象；q_r 应答**只回 label 字符串**，不要把 description 字段也带回来。
8. **chat SSE 流不能在 question / permission.ask 后立即 close**：必须保持开放等 q_r/p_r 后续事件，平台会暂停 idle timer，最长 30min（maxDuration 兜底）。

### 14.2 字段命名速查

| 协议字段 | 易混点 | 正确 |
|---|---|---|
| 搜索结果列表 | `results` ❌ | `searchResults` ✅ |
| 追问列表 | `questions` ❌ | `askMoreQuestions` ✅ |
| 提问字段（form 1） | `text` / `prompt` ❌ | `question` ✅ |
| 多选标识 | `multi` / `multiple` ❌ | `multiSelect` ✅ |
| 选项数组 | `choices` ❌ | `options` ✅ |
| 应答数组 | `answer` (单数) ❌ | `answers` ✅ |
| 选项对象 description | `desc` / `detail` ❌ | `description` ✅ |
| 工具调用 ID | `callId` / `toolId` ❌ | `toolCallId` ✅ |

### 14.3 测试支持

平台提供 mock 服务器 `tools/mock-cloud-server.py`，模拟云端 chat / q_r / p_r endpoint，可作为云端开发的对端镜像。

> **注意**：mock 当前 `search_result` 用 `results` / `ask_more` 用 `questions`（与 SS handler 实际接收字段不一致），这是 mock 文件的历史遗留问题，**云端实现请以本协议文档为准**。

---

## 15. 变更记录

| 日期 | 版本 | 改动 |
|---|---|---|
| 2026-04-29 | v2.0 | 首发，对应 commit `aa27c49`：含 `replyContext` 嵌套、`question` 多形态、`multiSelect`、`Option` 对象形态、`extParam` 透传、`SysConfig` 切换 |
