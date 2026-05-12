# External ↔ Skill-Server 接入协议（业务模块对接完整规范）

> **目的**：为 IM / CRM / 其他服务级业务模块提供与 Skill-Server 对接的完整协议契约。
> **协议范围**：external WebSocket 出站 + HTTP 入站（4 个 action）。
> **代码源**：见 §10。
> **状态**：草案（待补）

---

## 目录
- [1. 概览](#1-概览)
- [2. 接入契约](#2-接入契约)
- [3. 鉴权（AKSK + Sec-WebSocket-Protocol）](#3-鉴权)
- [4. 入站请求公共字段](#4-入站请求公共字段)
- [5. 入站动作清单（4 个 action）](#5-入站动作清单)
- [6. 出站 WebSocket 与 StreamMessage 差异点](#6-出站-ws)
- [7. 多连接路由与 sender envelope](#7-多连接)
- [8. 长连接保活与离线 fallback](#8-保活)
- [9. 错误处理矩阵](#9-错误)
- [10. 字段↔代码映射](#10-映射)
- [11. 配置项一览](#11-配置)
- [12. 给接入方的注意事项](#12-注意)

---

## 1. 概览

### 1.1 适用受众

External 通道服务于**服务级业务模块**：IM 平台后端、CRM 后端、客服系统后端等。区别于 miniapp 通道（每用户一条 Cookie 鉴权的 WS）：

| 维度 | miniapp 通道 | external 通道 |
|------|--------------|--------------|
| 接入者 | C 端浏览器/小程序 | 业务服务端 |
| 连接粒度 | 每用户一条 | 每业务实例一条或多条 |
| 鉴权 | Cookie userId | `Sec-WebSocket-Protocol` AKSK token |
| 入站接口 | `POST /api/skill/sessions/...`（多条 REST） | `POST /api/external/invoke`（单入口 4 action） |
| 出站通道 | `/ws/skill/stream` + Redis `user-stream:{userId}` | `/ws/external/stream` + Redis `stream:{domain}` |
| 路由维度 | userId | businessDomain（如 `im`/`crm`） |

### 1.2 链路概览

```
业务模块（IM / CRM / ...）
  │
  ├── 入站：POST /api/external/invoke ──▶ ExternalInboundController
  │       └─ action = chat | question_reply | permission_reply | rebuild
  │
  └── 出站：WS /ws/external/stream    ◀── ExternalStreamHandler
          (Sec-WebSocket-Protocol auth.{base64 json})
          ◀── Redis stream:{businessDomain}
          ◀── Redis ss:external-relay:{instanceId}  (跨 SS 实例 L2 中转)
          ◀── OutboundDeliveryDispatcher → ExternalWsDeliveryStrategy
```

入站统一信封 + payload；出站 StreamMessage 协议**与 miniapp 完全一致**（见 §6）。

### 1.3 关键不变量

- `ExternalInvokeRequest.businessDomain` 必须等于 WS 握手时 auth 的 `source`。
- `assistantAccount` 是路由出站到哪个会话的关键键；`senderUserAccount` 是审计/群聊归属键。
- 同一 `(source, instanceId)` 可以建立多条 WS 连接以做冗余（详见 §7）。
- 协议变更（v1.4.0 起）：`senderUserAccount` 已从 `chat.payload` 提升到信封顶层，4 个 action 全部必填。


## 2. 接入契约

### 2.1 Endpoint 一览

| 用途 | 协议 | 路径 | Content-Type / Subprotocol | 鉴权 |
|------|------|------|----------------------------|------|
| 入站（业务 → SS） | HTTP/1.1 | `POST /api/external/invoke` | `application/json` | `Authorization: Bearer {token}` |
| 出站（SS → 业务） | WebSocket | `/ws/external/stream` | `Sec-WebSocket-Protocol: auth.{base64url(json)}` | 同一 token，封装在 base64 json 内 |

服务端口与部署一致（skill-server 默认 8080，按部署 ingress 配置）。

### 2.2 Token 配置

| 项 | 配置 | 说明 |
|----|------|------|
| Token 值 | `skill.im.inbound-token`（Spring 配置） | 与 IM Inbound (`POST /api/inbound/messages`) 接口**共用** |
| 默认值 | REST 拦截器侧默认空串（未配置即拒绝所有请求，返回 401）；WS 握手侧 fallback 为 `changeme` | 生产必须配置强随机串；两侧默认不一致是历史遗留，建议显式配置 |
| 拦截器路径 | `/api/inbound/**`、`/api/external/**` | 见 `WebMvcConfig.java:37` |

### 2.3 入站响应公共结构

所有 `POST /api/external/invoke` 响应使用 `ApiResponse<T>` 封装，HTTP 状态码 200（除参数校验失败为 400）：

```json
{
  "code": 0,
  "errormsg": null,
  "data": {
    "businessSessionId": "ext-sess-123",
    "welinkSessionId": "8876543210987654321"
  }
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `code` | int | `0`=成功；`400`=入参错误；`404`=资源缺失（assistant/session）；`503`=助理离线 |
| `errormsg` | String | 错误描述；成功为 `null` |
| `data.businessSessionId` | String | 业务侧 sessionId（请求原样回传） |
| `data.welinkSessionId` | String | SS 内部数值 sessionId（用于关联出站 StreamMessage 的 `sessionId` 字段；首次创建走 chat/rebuild 异步建立时可能为 `null`） |

`data` 通过 `@JsonInclude(NON_NULL)` 序列化；为空时不出现。

### 2.4 出站消息载荷

WS 帧体为 UTF-8 JSON 字符串，结构是 `StreamMessage`（与 miniapp 通道字段对齐）。具体类型清单见 §6。

### 2.5 心跳

| 方向 | 帧 | 周期 |
|------|----|------|
| 业务 → SS | `{"action":"ping"}` | 建议 ≤30s 一次 |
| SS → 业务 | `{"action":"pong"}` | 收到 ping 立即回 |
| SS 主动断开 | `CloseStatus.GOING_AWAY` | 60s 内无任何消息（含 ping）则关闭，见 §8 |


## 3. 鉴权（AKSK + Sec-WebSocket-Protocol）

### 3.1 REST 鉴权

`POST /api/external/invoke` 走标准 `Authorization: Bearer {token}` header，由 `ImTokenAuthInterceptor` 校验：

```
Authorization: Bearer <skill.im.inbound-token>
```

- token 未配置或不匹配 → HTTP 401。
- 拦截路径在 `WebMvcConfig#addPathPatterns` 注册：`/api/inbound/**`、`/api/external/**`。

### 3.2 WS 鉴权（Sec-WebSocket-Protocol AKSK 风格）

`/ws/external/stream` 不接受 `Authorization` header（浏览器 WS 不支持自定义 header），而是用 **subprotocol 通道**承载鉴权信息。这沿用业界 K8s API server / Jupyter WS 的做法。

#### 3.2.1 握手请求

业务模块发起 WebSocket 握手时，必须设置 `Sec-WebSocket-Protocol`：

```
GET /ws/external/stream HTTP/1.1
Upgrade: websocket
Connection: Upgrade
Sec-WebSocket-Key: ...
Sec-WebSocket-Version: 13
Sec-WebSocket-Protocol: auth.<BASE64URL_NOPAD(json)>
```

`json` 体（UTF-8 序列化后做 Base64-URL 编码，不带 padding）：

```json
{
  "token": "<skill.im.inbound-token>",
  "source": "im",
  "instanceId": "im-node-1"
}
```

| 字段 | 必填 | 说明 |
|------|------|------|
| `token` | 是 | 与 `skill.im.inbound-token` 严格相等 |
| `source` | 是 | 业务域标识。**必须与后续 REST 调用的 `businessDomain` 一致**，是出站路由键 |
| `instanceId` | 是 | 业务模块实例标识。同 `source` 下多实例时区分用 |

#### 3.2.2 握手响应

校验通过：

- SS 在响应头回写 `Sec-WebSocket-Protocol: auth.<同样的 base64url>`（RFC 6455 要求 server 必须 echo 一个客户端发的子协议）
- session attributes 写入 `source`、`instanceId`，供后续生命周期使用
- 升级成功后即进入消息阶段

校验失败（token 错、source/instanceId 缺失、base64 解码失败、JSON 不合法）：

- `beforeHandshake` 返回 `false`，握手被拒，返回 HTTP 200/400（具体看 Spring 版本，无 `101 Switching Protocols`）
- SS 日志：`Rejected external handshake: invalid auth subprotocol`

#### 3.2.3 客户端 base64url 注意点

- 必须用 URL-safe Base64（`-` `_` 字符），SS 侧使用 `Base64.getUrlDecoder()` 解码（见 `ExternalStreamHandler.verifyToken`）。
- 不带 padding（`=`）也可，但带 padding 同样能解。
- subprotocol 整体形如 `auth.eyJ0b2tlbiI6...`，前缀 `auth.` 不可省。
- 多个 subprotocol 用逗号分隔时，SS 取第一个以 `auth.` 开头的有效项。

#### 3.2.4 示例（Node.js）

```js
import WebSocket from 'ws';

const auth = Buffer.from(JSON.stringify({
  token: process.env.SS_INBOUND_TOKEN,
  source: 'im',
  instanceId: 'im-node-1',
})).toString('base64url');

const ws = new WebSocket('wss://ss.example.com/ws/external/stream', [
  `auth.${auth}`,
]);
```


## 4. 入站请求公共字段

所有 4 个 action 共用同一个 DTO：`ExternalInvokeRequest`（`model/ExternalInvokeRequest.java`）。

```json
{
  "action": "chat",
  "businessDomain": "im",
  "sessionType": "direct",
  "sessionId": "ext-sess-123",
  "assistantAccount": "assistant-001",
  "senderUserAccount": "user-001",
  "businessExtParam": { "tenantId": "t-1" },
  "payload": { "...action 专属..." : "..." }
}
```

### 4.1 信封字段表

| 字段 | 类型 | 必填 | 校验 | 说明 |
|------|------|------|------|------|
| `action` | String | 是 | ∈ `{chat, question_reply, permission_reply, rebuild}` | 动作类型 |
| `businessDomain` | String | 是 | 非空 | 业务域；必须等于 WS 握手 `source` |
| `sessionType` | String | 是 | ∈ `{group, direct}` | `direct`=单聊（一对一），`group`=群聊（多对多） |
| `sessionId` | String | 是 | 非空 | 业务侧会话 ID（业务自定义，SS 用作 join key） |
| `assistantAccount` | String | 是 | 非空 | 助手账号，用于在 SS 解析对应 AK / Agent / scope |
| `senderUserAccount` | String | 是 | 非空 | 本次操作的发起用户账号（自 v1.4.0 必填，详见 §12） |
| `businessExtParam` | Object | 否 | JsonNode | 业务侧自由扩展；`chat`/`question_reply`/`permission_reply` 透传到下游云端 `extParameters.businessExtParam`；`rebuild` 不透传 |
| `payload` | Object | 是 | 按 action 校验 | action 专属内容（§5） |

### 4.2 校验顺序

`ExternalInboundController` 内部按以下顺序校验，任意一步失败立即返回 HTTP 400：

1. `request != null`
2. `action` 非空且属于白名单
3. `businessDomain` 非空
4. `sessionType ∈ {group, direct}`
5. `sessionId` 非空
6. `assistantAccount` 非空
7. `senderUserAccount` 非空
8. `payload` 按 action 校验（§5 各 action 分别说明必填字段）

### 4.3 响应

参见 §2.3。`businessSessionId` 始终是入参 `sessionId`；`welinkSessionId` 是 SS 数据库的 `skill_session.id`（数值字符串），出站 StreamMessage 中的 `sessionId` 字段即此值。


## 5. 入站动作清单（4 个 action）

下表汇总，逐 action 详见 §5.1 - §5.4：

| action | 必填 payload | 可选 payload | session 缺失行为 | 是否走 Gateway invoke |
|--------|--------------|--------------|------------------|-----------------------|
| `chat` | `content` | `msgType`, `imageUrl`, `chatHistory` | 异步创建 | 是 |
| `question_reply` | `content`, `toolCallId` | `subagentSessionId` | **HTTP 200 + code=404** | 是 |
| `permission_reply` | `permissionId`, `response` | `subagentSessionId` | **HTTP 200 + code=404** | 是 |
| `rebuild` | (无) | (无) | 异步创建 | 否（只触发 toolSession 重建） |

### 5.1 `chat` — 用户消息

#### Payload

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `content` | String | 是 | 用户消息文本 |
| `msgType` | String | 否 | 默认 `text`，当前仅支持 `text` |
| `imageUrl` | String | 否 | 图片 URL；`msgType=image` 时使用（暂不支持） |
| `chatHistory` | Array<ChatMessage> | 否 | 群聊上下文，元素见下 |

**chatHistory 元素：**

| 字段 | 类型 | 说明 |
|------|------|------|
| `senderAccount` | String | 发送者账号 |
| `senderName` | String | 显示名 |
| `content` | String | 消息内容 |
| `timestamp` | Long | 毫秒时间戳 |

#### 请求示例（单聊）

```json
{
  "action": "chat",
  "businessDomain": "im",
  "sessionType": "direct",
  "sessionId": "dm-001",
  "assistantAccount": "assistant-001",
  "senderUserAccount": "user-001",
  "businessExtParam": {"tenantId": "t-1"},
  "payload": {
    "content": "你好",
    "msgType": "text"
  }
}
```

#### 请求示例（群聊带历史）

```json
{
  "action": "chat",
  "businessDomain": "im",
  "sessionType": "group",
  "sessionId": "grp-001",
  "assistantAccount": "assistant-001",
  "senderUserAccount": "user-001",
  "payload": {
    "content": "@助手 帮我查一下昨天的会议纪要",
    "msgType": "text",
    "chatHistory": [
      {"senderAccount":"user-002","senderName":"张三","content":"会议改到下午了","timestamp":1713000000000},
      {"senderAccount":"user-003","senderName":"李四","content":"OK","timestamp":1713000060000}
    ]
  }
}
```

#### 处理流程

1. `resolverService.resolve(assistantAccount)` → `(ak, ownerWelinkId)`；失败返回 `code=404 Invalid assistant account`
2. `checkAgentOnline`（个人助手且总开关开时）→ 离线返回 `code=503`
3. `contextInjectionService.resolvePrompt`（群聊把 `chatHistory` 拼到 prompt）
4. `sessionManager.findSession(domain, type, sessionId, ak)`：
   - session 不存在 → `createSessionAsync`（personal 异步；business 预生成 toolSessionId）
   - toolSessionId 未就绪 → `requestToolSession`，缓存消息待重发
   - session 就绪 → 单聊持久化用户消息；构建 invoke payload → `GatewayRelayService.sendInvokeToGateway(CHAT, ...)`
5. 返回 `code=0` + `data.{businessSessionId, welinkSessionId}`

Gateway 出站 payload 含字段：`text` / `toolSessionId` / `assistantAccount` / `sendUserAccount` / `imGroupId` / `messageId`。注意 SS→Gateway 字段名是 `sendUserAccount`（少 "er"），与入站 `senderUserAccount` 命名不一致是历史遗留（详见 §12）。

#### 响应示例

```json
{
  "code": 0,
  "errormsg": null,
  "data": {
    "businessSessionId": "dm-001",
    "welinkSessionId": "8876543210987654321"
  }
}
```

session 异步创建中（首次 personal chat）`welinkSessionId` 可能为 `null`。

### 5.2 `question_reply` — 反问回复

业务模块响应 AI 发起的「反问」（出站 `question.ask`，见 §6）。

#### Payload

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `content` | String | 是 | 用户回答的文本 |
| `toolCallId` | String | 是 | 反问关联的 toolCallId（来自出站 `question.ask` 消息） |
| `subagentSessionId` | String | 否 | 子 agent 会话 ID（如果是嵌套 agent 触发，否则 null） |

#### 请求示例

```json
{
  "action": "question_reply",
  "businessDomain": "im",
  "sessionType": "direct",
  "sessionId": "dm-001",
  "assistantAccount": "assistant-001",
  "senderUserAccount": "user-001",
  "payload": {
    "content": "选 A 方案",
    "toolCallId": "tc-abc123",
    "subagentSessionId": null
  }
}
```

#### 处理流程

1. `resolverService.resolve` → 失败 `code=404 Invalid assistant account`
2. `sessionManager.findSession` → null 或 toolSessionId 未就绪：`code=404 Session not found or not ready`
3. `checkAgentOnline` → 离线 `code=503`（优先级低于 404）
4. 构建 payload `{answer, toolCallId, toolSessionId, sendUserAccount}` → `GatewayRelayService.sendInvokeToGateway(QUESTION_REPLY, ...)`

⚠️ 出站 payload 字段名是 `answer`（不是 `content`），SS 内部转换。

#### 响应示例

成功 `code=0`；session 未就绪 `code=404 Session not found or not ready`；agent 离线 `code=503`。

### 5.3 `permission_reply` — 权限授权回复

业务模块响应 AI 发起的「权限请求」（出站 `permission.ask`，见 §6）。

#### Payload

| 字段 | 类型 | 必填 | 校验 | 说明 |
|------|------|------|------|------|
| `permissionId` | String | 是 | 非空 | 权限请求 ID（来自出站 `permission.ask`） |
| `response` | String | 是 | ∈ `{once, always, reject}` | 授权决定 |
| `subagentSessionId` | String | 否 | — | 子 agent 会话 ID |

`response` 语义：

- `once`：本次允许
- `always`：永久允许（写入 Agent 端的允许列表）
- `reject`：拒绝

#### 请求示例

```json
{
  "action": "permission_reply",
  "businessDomain": "im",
  "sessionType": "direct",
  "sessionId": "dm-001",
  "assistantAccount": "assistant-001",
  "senderUserAccount": "user-001",
  "payload": {
    "permissionId": "perm-xyz789",
    "response": "once",
    "subagentSessionId": null
  }
}
```

#### 处理流程

1. `resolverService.resolve` → 失败 `code=404`
2. `findSession` → null/未就绪 `code=404 Session not found or not ready`
3. `checkAgentOnline` → 离线 `code=503`
4. 构建 payload `{permissionId, response, toolSessionId, sendUserAccount}` → `GatewayRelayService.sendInvokeToGateway(PERMISSION_REPLY, ...)`
5. SS 通过 OutboundDeliveryDispatcher 广播一条 `permission.reply` StreamMessage 给同 domain 的其他出站连接（详见 §6 出站消息差异）

#### 响应示例

成功：`{"code":0,"errormsg":null,"data":{"businessSessionId":"dm-001","welinkSessionId":"8876..."}}`

### 5.4 `rebuild` — 上下文重建

强制清空当前 toolSession（即 Agent 端会话上下文），下一条消息将以全新上下文开始。

#### Payload

无字段；传 `{}` 即可。

#### 请求示例

```json
{
  "action": "rebuild",
  "businessDomain": "im",
  "sessionType": "direct",
  "sessionId": "dm-001",
  "assistantAccount": "assistant-001",
  "senderUserAccount": "user-001",
  "payload": {}
}
```

#### 处理流程

1. `resolverService.resolve` → 失败 `code=404`
2. `checkAgentOnline` → 离线 `code=503`（前置，避免给离线 agent 建孤儿 session）
3. `sessionManager.findSession`：
   - 存在：清空 `toolSessionId` → `requestToolSession(session, null)`（personal 异步发 `create_session`；business 立即重新生成 `cloud-{uuid}`）
   - 不存在：`createSessionAsync(...)` 走 chat 同款新建流程
4. **不**走 `GatewayRelayService.sendInvokeToGateway`（即不发 CHAT/REPLY action 给 Agent）
5. 返回 `code=0`；新 toolSessionId 异步绑定，后续消息发送时自动使用

#### 响应示例

```json
{
  "code": 0,
  "errormsg": null,
  "data": {
    "businessSessionId": "dm-001",
    "welinkSessionId": "8876543210987654321"
  }
}
```

session 不存在时首次返回的 `welinkSessionId` 可能为 `null`（异步创建中）。


### 5.3 `permission_reply` — 权限授权回复
（待补）

### 5.4 `rebuild` — 上下文重建
（待补）

## 6. 出站 WebSocket 与 StreamMessage 差异点

### 6.1 共用基线

出站 WS 帧是 UTF-8 JSON 字符串，结构为 `StreamMessage`。**26 种 type 全表、字段语义、type 流转顺序与 miniapp 通道完全一致**，详见：

- 字段总表与 type 枚举：[miniapp ↔ skill-server 协议 §6 StreamMessage 通用字段](./2026-05-12-miniapp-skill-server-protocol.md#6-stream-message-字段表)
- 各 type 触发时机与示例：[miniapp ↔ skill-server 协议 §7 出站事件清单](./2026-05-12-miniapp-skill-server-protocol.md#7-出站事件清单)

> 关联代码：`StreamMessage.java` / `StreamMessage.Types`。

### 6.2 与 miniapp 通道的差异点

下表只列 external 通道**与 miniapp 不同**的部分；未列出的字段/type 完全一致。

| 维度 | miniapp | external | 备注 |
|------|---------|----------|------|
| Redis 投递 channel | `user-stream:{userId}` | `stream:{businessDomain}` | external 是 domain 维度广播 |
| WS 路由维度 | 按 `userId`（每用户一条 WS） | 按 `businessDomain`（每业务一条/多条 WS） | 业务端自行根据 `sessionId` 路由到内部用户 |
| 跨实例中转 channel | `user-stream:{userId}` 全局可订阅 | `ss:external-relay:{instanceId}` | external L2 用定向中转避免全集群广播 |
| 投递策略 | `pushToOne` 不适用（每用户唯一） | `pushToOne` + 失败重试 ≤3 次 | 多连接冗余下任选一条 |
| 字段裁剪 | 无 | 无 | external 全量透传，业务端自行忽略不关心字段 |
| seq 编号 | 按 sessionId | 按 sessionId | 同 miniapp（`redisMessageBroker.nextStreamSeq(sessionId)`） |
| 鉴权关联 | Cookie userId 与消息 userId 匹配 | 无字段匹配（business 侧自行验证） | external 信任 token 后不做消息级 ACL |

### 6.3 业务端路由建议

external 端收到 StreamMessage 后，应按以下字段自路由到内部业务会话：

| StreamMessage 字段 | 用途 |
|--------------------|------|
| `sessionId` | 对应 `welinkSessionId`（SS 数值 sessionId） |
| `assistantAccount` | 助手账号 |
| `userId` | 接收用户（如果有，需结合 sessionId 解析具体业务用户） |
| `type` | 事件类型 |

⚠️ 同一 `businessDomain` 下所有会话的消息都会通过同一 WS 推送，业务端**必须**按 `sessionId` 分发到内部业务会话。

### 6.4 L1/L2/L3 投递层级

`ExternalWsDeliveryStrategy.deliver` 实现：

1. **L1 本实例**：`externalStreamHandler.pushToOne(domain, json)` 任选一条本机活跃连接发送，失败重试 ≤3 次
2. **L2 跨实例**：本实例无连接 → `wsRegistry.findInstanceWithConnection(domain)` 查 Redis 注册表 → `redisMessageBroker.publishToChannel("ss:external-relay:" + targetInstance, ...)` 中转到持有连接的实例
3. **L3 降级**：无任何 WS 连接 → 若 `domain=im`，调 `imOutboundService.sendTextToIm` REST 兜底（仅 `text.done`/`error` 类型有 fallback 文本）；其他 domain 丢弃并 warn



## 7. 多连接路由与 sender envelope

### 7.1 连接池数据结构

`ExternalStreamHandler.ConnectionPool` 内部三级嵌套：

```
source → { instanceId → { wsSessionId → WebSocketSession } }
```

- `source`：业务域（取自握手 auth），即出站路由键
- `instanceId`：业务模块实例标识（业务自定义；同 source 多实例时用以区分）
- `wsSessionId`：Spring `WebSocketSession.getId()`，自动生成（用于支持同 `(source, instanceId)` 多条冗余连接）

### 7.2 多连接（高可用）

设计文档：`2026-04-16-external-ws-multi-connection-design.md`。

**关键性质**：

- 同一业务 pod 可同时连**多个 SS 实例**，也可在同一 SS 实例连**多条** WS。
- 出站时 `pushToOne` 任选一条**已 open** 的连接发送。
- 发送失败 → 移除该连接 → 重试下一条，最多 3 次。
- 3 次都失败 → 走 L2 中转到其他实例，再失败走 L3 降级。

**Registry 注册值**（Redis hash `ws:registry:{domain}` → field=instanceId, value=connectionCount）：每次 connect/close 时由 `wsRegistry.register(source, connectionPool.countBySource(source))` 写入；心跳 30s 续期 TTL（`DeliveryProperties.registryTtlSeconds`，默认 30s）。

### 7.3 Sender envelope（v1.4.0）

设计文档：`2026-04-22-external-sender-user-account-envelope-design.md`。

**规则**：

- `senderUserAccount` 是**信封顶层**字段，4 个 action 全部必填，缺失返回 HTTP 400 `senderUserAccount is required`。
- 旧形状 `payload.senderUserAccount` 已**硬切**废弃，SS 不再读取该位置；调用方放在 payload 里 SS 会忽略并因信封缺字段返回 400。
- 各 action 的下游 service 签名都新增 `senderUserAccount` 形参。
- 下游 SS→Gateway 出站 payload 字段名为 `sendUserAccount`（少一个 "er"），这是历史遗留命名，本次不改。

**跨 action 身份语义差异**：

| action | 下游 `sendUserAccount` 取值 |
|--------|----------------------------|
| `chat` (group) | `senderUserAccount` |
| `chat` (direct) | 当 `senderUserAccount` 为空时 fallback 为 `ownerWelinkId`（仅 IM 共享路径保留兜底，external 在 v1.4.0 后总非空） |
| `question_reply` / `permission_reply` | 无条件透传 `senderUserAccount`（"谁触发 reply" 就是发送者） |
| `rebuild` | 不走 Gateway invoke，仅用于 `createSessionAsync` 入参 |


## 8. 长连接保活与离线 fallback

### 8.1 心跳协议

- 客户端**周期性**发送 `{"action":"ping"}`，建议 ≤ 30s。
- SS 收到后**立刻**回 `{"action":"pong"}`。
- SS 在收到**任何**文本帧时更新 `lastActivity[sessionId] = now`，包括 ping、其他业务帧。

### 8.2 超时关闭

`@Scheduled(fixedRate = 30_000)` 周期任务 `checkHeartbeatTimeouts`：

- 若 `lastActivity` 距今超过 **60s**，调用 `session.close(CloseStatus.GOING_AWAY)`。
- 客户端应捕获该 close 后重连。
- 每轮还会调用 `wsRegistry.heartbeat(source)` 续期 Redis 注册项 TTL（默认 30s）。

### 8.3 连接生命周期

```
握手 (beforeHandshake)
  └─ verifyToken → attributes 写 source/instanceId

afterConnectionEstablished
  ├─ connectionPool.add(source, instanceId, session)
  ├─ lastActivity.put(sessionId, now)
  ├─ 若 stream:{source} 未订阅 → subscribeToChannel
  └─ wsRegistry.register(source, count)

handleTextMessage
  ├─ 更新 lastActivity
  └─ action=ping → 回 pong；其他帧记录 warn

afterConnectionClosed / handleTransportError
  ├─ connectionPool.remove
  ├─ lastActivity.remove
  ├─ remaining==0 → unsubscribeFromChannel(stream:{source}) + wsRegistry.unregister
  └─ 否则 wsRegistry.register(source, remaining) 更新连接数
```

### 8.4 离线 fallback 矩阵

设计文档：`2026-04-21-external-offline-response-design.md`。

| 入站场景 | REST 响应 | 副作用 |
|---------|-----------|--------|
| Agent 离线（任意 action，personal scope） | HTTP 200 + `code=503` + `errormsg=<sys_config 配置文案 or 默认>` + `data.{businessSessionId, welinkSessionId(若已存在)}` | `handleAgentOffline` 通过 OutboundDeliveryDispatcher 推 `type=ERROR` 流消息；单聊有 session 则 `saveSystemMessage` 持久化 |
| Agent 离线 + business scope | 跳过在线检查 → 正常 `code=0` 转发 | 由云端 HTTP/Agent 自行处理后续 |
| `question_reply` / `permission_reply` 时 session 不存在 | HTTP 200 + `code=404 Session not found or not ready` | 不调 Gateway，不写 DB |
| Assistant 账号无效 | HTTP 200 + `code=404 Invalid assistant account` | 无 |
| WS 全部连接断开 | 入站不受影响；出站走 L3 降级（IM→REST，其他→discard） | 见 §6.4 |

**优先级**（`question_reply` / `permission_reply`）：`resolve 404 > session 不存在 404 > 离线 503`。`chat` / `rebuild` 无 "session 不存在 404" 路径。

### 8.5 离线文案配置

`sys_config` 表 `config_type='assistant_offline', config_key='message'`：

- 未配置/`status=0`/空串 → fallback 到代码内 `DEFAULT_OFFLINE_MESSAGE`（"任务下发失败，请检查助理是否离线，确保助理在线后重试"）。
- 配置正常 → 返回该值。
- Redis 故障 → `SysConfigService` 内部降级直查 MySQL，不抛 500。



## 9. 错误处理矩阵

### 9.1 REST 错误码

| HTTP | code | errormsg | 触发场景 | 排查 |
|------|------|----------|----------|------|
| 401 | 401 | `Missing token` / `Invalid token` / `Inbound token is not configured` | `Authorization` 缺失或 token 不匹配；body 为 `{code:401, errormsg:...}` | 检查 `skill.im.inbound-token` |
| 400 | 400 | `Request body is required` | body 为空 | 检查 JSON 序列化 |
| 400 | 400 | `action is required` | `action` 缺失或空 | 信封校验 |
| 400 | 400 | `Invalid action: xxx` | action 不在白名单 | 检查拼写：`chat/question_reply/permission_reply/rebuild` |
| 400 | 400 | `businessDomain is required` | `businessDomain` 缺失 | 信封校验 |
| 400 | 400 | `Invalid sessionType` | `sessionType` 不属于 `{group, direct}` | 检查拼写 |
| 400 | 400 | `sessionId is required` | `sessionId` 缺失 | 信封校验 |
| 400 | 400 | `assistantAccount is required` | `assistantAccount` 缺失 | 信封校验 |
| 400 | 400 | `senderUserAccount is required` | `senderUserAccount` 缺失（v1.4.0+） | 信封必填；旧客户端硬切 |
| 400 | 400 | `payload.content is required for chat` | `chat.payload.content` 缺失 | 检查 payload |
| 400 | 400 | `payload.content is required for question_reply` | `question_reply.payload.content` 缺失 | 同上 |
| 400 | 400 | `payload.toolCallId is required for question_reply` | `question_reply.payload.toolCallId` 缺失 | 检查上次 `question.ask` 的 toolCallId |
| 400 | 400 | `payload.permissionId is required` | `permission_reply.payload.permissionId` 缺失 | 检查上次 `permission.ask` 的 permissionId |
| 400 | 400 | `payload.response must be once/always/reject` | `permission_reply.payload.response` 非法 | 三选一 |
| 200 | 0 | `null` | 成功 | — |
| 200 | 404 | `Invalid assistant account` | `assistantAccount` 解析不到 AK | 业务端检查 assistant 是否已配置 |
| 200 | 404 | `Session not found or not ready` | `question_reply`/`permission_reply` 时 session 不存在或 toolSessionId 未就绪 | 应先发 chat 建立 session |
| 200 | 503 | `<offline message>` | Personal 助理离线 | 上游应展示 errormsg，等助理上线后重试 |

### 9.2 WS 错误

| 场景 | 表现 | 业务端处置 |
|------|------|-----------|
| 握手 token 错 | HTTP 200 或 4xx；无 `101 Switching Protocols`；SS 日志 `Rejected external handshake` | 检查 token / source / instanceId 编码 |
| 握手通过后 60s 无任何帧 | SS 主动 `GOING_AWAY` 关闭 | 客户端识别 close 后重连，恢复 ping 周期 |
| 出站推送失败（业务端 socket 异常） | SS 移除该连接，尝试下一条；3 次失败走 L2/L3 | 业务端无需处理；重连后会重新订阅 |
| 业务端发送 ping 之外的非法 JSON | SS 日志 `Invalid message from external WS`，连接保留 | 修复客户端消息格式 |

### 9.3 出站消息内嵌错误

| `type` | 含义 |
|--------|------|
| `error` | 通用错误（含离线 fallback 文案） |
| `session_error` | 会话级错误（不可恢复，业务端可提示用户重建） |

具体字段含义复用 [miniapp doc §7](./2026-05-12-miniapp-skill-server-protocol.md#7-出站事件清单)。


## 10. 字段↔代码映射

### 10.1 入站

| 字段 / 行为 | 代码位置 |
|------------|----------|
| Endpoint `POST /api/external/invoke` | `controller/ExternalInboundController.java#invoke` |
| 信封 DTO | `model/ExternalInvokeRequest.java` |
| 信封 `VALID_ACTIONS` 白名单 | `controller/ExternalInboundController.java` (line 27) |
| 信封 `VALID_SESSION_TYPES` 白名单 | `controller/ExternalInboundController.java` (line 28) |
| `permission_reply.response` 三选一 | `controller/ExternalInboundController.java` (line 29) |
| 信封校验顺序 | `controller/ExternalInboundController.java#validateEnvelope` (line 110-120) |
| Payload 校验 | `controller/ExternalInboundController.java#validatePayload` (line 122-145) |
| chatHistory 解析 | `controller/ExternalInboundController.java#parseChatHistory` (line 147-165) |
| `chat` 入站处理 | `service/InboundProcessingService#processChat` |
| `question_reply` 入站处理 | `service/InboundProcessingService#processQuestionReply` |
| `permission_reply` 入站处理 | `service/InboundProcessingService#processPermissionReply` |
| `rebuild` 入站处理 | `service/InboundProcessingService#processRebuild` |
| 助理在线检查 | `service/InboundProcessingService#checkAgentOnline` |
| `senderUserAccount` 群聊 fallback | `service/InboundProcessingService.java:341-343` (dispatchChatToGateway)、`service/ImSessionManager.java:155-158` (业务助手预生成路径) |
| Token 拦截器 | `config/ImTokenAuthInterceptor.java` |
| 拦截路径 | `config/WebMvcConfig.java:37` (`/api/inbound/**, /api/external/**`) |

### 10.2 出站

| 字段 / 行为 | 代码位置 |
|------------|----------|
| WS endpoint 注册 `/ws/external/stream` | `config/SkillConfig.java:51-52` |
| 握手鉴权 | `ws/ExternalStreamHandler.java#beforeHandshake / verifyToken` |
| AUTH_PROTOCOL_PREFIX `auth.` | `ws/ExternalStreamHandler.java` (line 34) |
| session attribute `source` / `instanceId` | `ws/ExternalStreamHandler.java` (line 35-36, 72-73) |
| 多连接池 | `ws/ExternalStreamHandler.ConnectionPool` (line 276+) |
| `pushToOne` + 3 次重试 | `ws/ExternalStreamHandler.java#pushToOne` |
| `pushToSource` 广播 | `ws/ExternalStreamHandler.java#pushToSource` |
| Redis channel `stream:{source}` 订阅 | `ws/ExternalStreamHandler.java` (line 90-93) |
| Relay channel `ss:external-relay:{instanceId}` 订阅 | `ws/ExternalStreamHandler.java#subscribeRelayChannel` (`ApplicationReadyEvent`) |
| 心跳超时（60s） | `ws/ExternalStreamHandler.java#checkHeartbeatTimeouts` |
| `ping/pong` 处理 | `ws/ExternalStreamHandler.java#handleTextMessage` (line 99-110) |
| WS 注册表（domain→instance→count） | `service/ExternalWsRegistry.java` |
| 出站投递策略 | `service/delivery/ExternalWsDeliveryStrategy.java` |
| L1/L2/L3 投递 | `service/delivery/ExternalWsDeliveryStrategy.java#deliver` (line 47-94) |
| L3 IM REST 兜底 | `service/ImOutboundService#sendTextToIm` |
| StreamMessage DTO | `model/StreamMessage.java` + `model/StreamMessage.Types` |
| 离线文案 provider | `service/AssistantOfflineMessageProvider.java` |
| 离线响应 `code=503` 装配 | `service/InboundProcessingService#checkAgentOnline` + `controller/ExternalInboundController.java:99-107` |


## 11. 配置项一览

### 11.1 必要配置

| key | 默认 | 说明 |
|-----|------|------|
| `skill.im.inbound-token` | `changeme` | REST 与 WS 鉴权 token（共用） |
| `skill.delivery.registry-ttl-seconds` | `30` | WS 注册表 TTL（秒）；心跳 30s 续期 |

### 11.2 可选配置

| key | 默认 | 说明 |
|-----|------|------|
| `sys_config` 表行 `assistant_offline:message` | — | 离线提示文案；缺失/disabled 时使用代码内 `DEFAULT_OFFLINE_MESSAGE` |
| `assistantIdProperties.enabled` | (业务侧) | 助理在线检查总开关；关闭时所有 action 跳过检查 |

### 11.3 调用方建议配置

| 项 | 建议值 | 说明 |
|----|--------|------|
| WS ping 周期 | ≤ 30s | 避免被 60s 超时关闭 |
| WS 重连退避 | 指数退避 1s→30s | 避免雪崩 |
| WS 多连接数 | 1～3 条 | 同 SS 实例多条提升可用性；按业务 QPS 评估 |
| REST 超时 | 5～10s | invoke 路径多为异步转发，长尾 < 5s |



## 12. 给接入方的注意事项

### 12.1 易混字段速查

| 字段 | 出现位置 | 易错 |
|------|----------|------|
| `senderUserAccount` | 入站信封顶层 | v1.4.0 起**必填**，旧调用方放在 `payload` 里会 400。所有 4 个 action 都要传 |
| `sendUserAccount` | SS → Gateway 出站 payload | 比信封字段**少一个 "er"**；历史命名遗留，不要混淆。业务接入方一般不直接见到这个 |
| `businessDomain` (REST) vs `source` (WS) | 信封 vs 握手 | **必须一致**，否则 WS 收不到对应消息 |
| `sessionId` (REST 入参) vs `welinkSessionId` (响应 / WS 消息) | 入参业务 ID vs SS 数值 ID | StreamMessage 里 `sessionId` 字段是 `welinkSessionId` (数值)，不是业务侧 `sessionId` 字符串 |
| `sessionType=direct` vs `group` | 信封 | `direct`=单聊；不是 `dm`、不是 `private` |
| `permission_reply.response` | payload | `once`/`always`/`reject`；不是 `accept`/`deny` |
| `question_reply.payload.content` | payload | 字段名是 `content`（不是 `answer`），SS 内部转换为出站 `answer` |
| `question_reply.toolCallId` | payload | 必须来自上一条 `question.ask` 出站消息的同名字段 |
| `permission_reply.permissionId` | payload | 必须来自上一条 `permission.ask` 出站消息的同名字段 |
| `businessExtParam` 透传范围 | 信封 | 透传到 `chat`/`question_reply`/`permission_reply` 的云端 `extParameters.businessExtParam`；`rebuild` 不透传 |
| `subagentSessionId` | `question_reply`/`permission_reply` payload | 嵌套 agent 才有；非嵌套场景传 null 或省略 |
| `chatHistory` 适用场景 | `chat.payload` | 仅群聊有意义（拼到 prompt 给上下文），单聊建议省略 |
| Subprotocol 前缀 `auth.` | WS 握手 | 不可省；后跟 URL-safe Base64 编码的 JSON |
| `instanceId` 用途 | WS 握手 | 业务侧实例标识，不是 SS 实例；同 source 多实例时分开标识 |

### 12.2 接入 checklist

按顺序对照实施：

1. [ ] 拿到 `skill.im.inbound-token` 值（与运维对齐）
2. [ ] 定义业务侧 `source` 名（如 `im` / `crm` / `helpdesk`），后续所有 REST 请求 `businessDomain` 都用此值
3. [ ] 实现 WS 客户端：
   - [ ] 拼装 `Sec-WebSocket-Protocol: auth.<base64url(json)>`
   - [ ] 收到 close 后指数退避重连
   - [ ] ≤30s 一次 ping，收到消息更新 lastActivity
   - [ ] 收到 StreamMessage 按 `sessionId` + `assistantAccount` 路由到内部业务会话
4. [ ] 实现 REST 调用：
   - [ ] 信封 6 字段必填（含 `senderUserAccount`）
   - [ ] 按 action 填写 payload
   - [ ] 解析响应 `code`：0=成功；400/404/503 按 errormsg 处理；503 时展示文案、可重试
5. [ ] 处理出站事件：
   - [ ] `text.delta` / `text.done` → 流式渲染最终回答
   - [ ] `question.ask` → 触发反问 UI，记录 `toolCallId`，待用户回答后回 `question_reply`
   - [ ] `permission.ask` → 触发授权 UI，记录 `permissionId`，回 `permission_reply`
   - [ ] `error` / `session_error` → 提示用户
6. [ ] 高可用：建议同时连 2-3 个 SS 实例（同 `source`，不同 `instanceId` 或同 `instanceId` 多条 ws）

### 12.3 接入演练步骤

| # | 动作 | 预期 |
|---|------|------|
| 1 | 直连 WS（错 token） | 握手失败 |
| 2 | 直连 WS（正确 token） | 升级成功，发 ping 收 pong |
| 3 | REST `chat`（信封缺 `senderUserAccount`） | 400 `senderUserAccount is required` |
| 4 | REST `chat`（首条消息） | 200 `code=0`，`welinkSessionId` 异步绑定，几秒内 WS 收到 `text.delta` / `text.done` |
| 5 | REST `question_reply`（错 `sessionId`） | 200 `code=404 Session not found or not ready` |
| 6 | REST `permission_reply`（`response=accept`） | 400 `payload.response must be once/always/reject` |
| 7 | REST `rebuild` | 200 `code=0`；后续 chat 用全新上下文 |
| 8 | 模拟 agent 离线，发 `chat` | 200 `code=503` + 配置文案 |
| 9 | 断 WS，再发 chat（IM 域） | REST 仍 200，AI 回复走 IM REST 兜底 |
| 10 | 同 source 起 2 条 WS，停其一 | 出站仍稳定（pickOne 自动选活跃） |

### 12.4 边界与未支持

- **入站只支持 `text` 消息**：`msgType=image` 在 payload 表里保留字段但**当前不支持**。
- **WS 断连缓冲**：当前断连即丢，无重发队列。客户端应在重连后通过 REST `rebuild` 或业务侧重发触发会话恢复。
- **L3 降级仅 IM 有兜底**：其他 domain WS 全断时消息会被丢弃，请保证至少一条连接活跃。
- **多连接数无上限**：不要无限建连，建议 ≤ 5 条/source。
- **WS 帧大小**：受 Spring 默认配置约束（通常 64KB）；超大 snapshot 不会出现在 external 通道（与 miniapp 一致）。


