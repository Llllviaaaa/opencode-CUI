# Layer2 协议：上游服务 <-> Gateway

> 版本：1.2  
> 日期：2026-03-12  
> 状态：已实现并同步到当前代码

## 1. 目标

Layer2 不再被定义为“仅供 `skill-server` 使用”的私有协议，而是升级为“上游服务接入 `gateway` 的标准协议”。

- `skill-server` 是当前第一个实现方
- 后续新服务也必须遵守同一协议
- `gateway` 不负责为不同上游服务做私有协议适配

## 2. 核心原则

- 每条上游消息都必须显式携带 `source`
- `source` 表示“这条消息所属的上游服务”
- `gateway` 在握手阶段绑定可信 `source`
- 业务消息中的 `source` 必须与连接绑定的 `source` 一致
- `gateway` 回流时保留原始 `source` 作为内部路由上下文
- `source` 不下沉到 Layer3 `gateway <-> pc-agent` 协议

## 3. WebSocket 建链

连接地址：

```text
ws://gateway-host/ws/skill
```

认证方式：

- 使用 `Sec-WebSocket-Protocol`
- 子协议格式为 `auth.<base64url-encoded-json>`

解码后的 JSON 结构：

```json
{
  "token": "<internal-token>",
  "source": "skill-server"
}
```

约束：

- `token` 用于内部鉴权
- `source` 用于绑定连接所属上游服务
- 握手通过后，`gateway` 以该 `source` 作为该连接的可信身份

## 4. 下行消息：上游服务 -> Gateway

通用 `invoke` 结构：

```json
{
  "type": "invoke",
  "source": "skill-server",
  "ak": "ak_xxxxxxxx",
  "welinkSessionId": 42,
  "action": "chat",
  "payload": {
    "toolSessionId": "ses_abc",
    "text": "帮我创建一个 React 项目"
  }
}
```

字段说明：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `type` | String | 是 | 固定为 `invoke` |
| `source` | String | 是 | 上游服务标识，例如 `skill-server` |
| `ak` | String | 是 | 目标 Agent 的 Access Key |
| `welinkSessionId` | Long | 否 | 上游服务自己的会话标识 |
| `action` | String | 是 | 调用动作 |
| `payload` | Object | 是 | 动作参数 |

当前 Layer2 保持沿用的动作包括：

- `create_session`
- `chat`
- `abort_session`
- `close_session`
- `permission_reply`
- `question_reply`

## 5. 上行消息：Gateway -> 上游服务

当前主要回流消息包括：

- `session_created`
- `tool_event`
- `tool_done`
- `tool_error`
- `permission_request`
- `agent_online`
- `agent_offline`

说明：

- 这些消息在 `gateway` 内部仍然带有原始 `source` 上下文用于路由
- 但 Layer2 对上游服务暴露的消息体可以继续保持现有业务字段集合
- `pc-agent` 不需要也不应该感知 `source`

## 6. 标准错误语义

当上游消息未满足多来源隔离约束时，`gateway` 必须协议级拒绝，且消息不能进入主路由链路。

当前标准错误原因至少包括：

- `source_not_allowed`
- `source_mismatch`

典型场景：

- 握手未携带合法 `source`
- 消息体中的 `source` 与连接绑定的 `source` 不一致

## 7. 路由与隔离约束

- `gateway` 先按 `source` 分服务域
- 再在对应服务域内按 owner 路由
- owner key 使用 `source:instanceId`
- 允许同域 fallback
- 严禁跨 `source` fallback

## 8. 观测约束

关键路由链路需要统一携带：

- `traceId`
- `source`
- `ownerKey`
- `routeDecision`
- `fallbackUsed`
- `errorCode`

这样可以在多上游服务并行运行时，快速排查错投、拒绝和 fallback 行为。
