# message-bridge-openclaw 协议兼容矩阵（对齐 message-bridge）

本文用于说明：在对接 `ai-gateway` 协议时，`message-bridge-openclaw` 与 `message-bridge` 的语义对齐情况、OpenClaw 接口依赖、触发时机与实现差异。

## 1. 下行消息矩阵

| message/action | message-bridge 实现 | openclaw 实现 | 依赖 OpenClaw 接口 | 触发时机 | 语义是否一致 | 差异说明 |
|---|---|---|---|---|---|---|
| `status_query` | 路由到 `status_query` action，读取宿主健康状态 | 直接返回桥接链路状态 `running && connection.isConnected()` | 无额外 runtime 子接口 | 收到 `type=status_query` 下行消息后立即响应 | 是 | 宿主探活来源不同，但 `status_response.opencodeOnline` 语义都表示“当前可用性” |
| `invoke.chat` | 调 SDK `session.prompt`，再转发上游事件，兼容 `tool_done` 去重 | 走 `channel.reply/routing` 主路径，缺失时走 `subagent` 回退，插件内发送 `tool_event/tool_done` | `runtime.channel.routing.resolveAgentRoute`、`runtime.channel.reply.dispatchReplyWithBufferedBlockDispatcher`、`runtime.subagent.run/waitForRun/getSessionMessages` | 收到 `invoke.chat` 后立即进入执行 | 是 | 事件来源机制不同（转发 vs 合成/分发），但网关可观察语义一致 |
| `invoke.create_session` | 调宿主创建会话接口后返回 `session_created` | 桥接层分配 `toolSessionId`（payload 优先，否则 UUID），建立映射后返回 `session_created` | 无 | 收到 `invoke.create_session` 后立即响应 | 是 | 即时宿主创建 vs 首次 chat 懒创建，协议语义一致 |
| `invoke.close_session` | 关闭/删除会话，不发 `tool_done` | 调 `subagent.deleteSession({sessionKey})`，不发 `tool_done` | `runtime.subagent.deleteSession` | 收到 `invoke.close_session` 且会话存在时执行 | 是 | OpenClaw 仅使用公开删除能力 |
| `invoke.abort_session` | 中止会话后回 `tool_done` | 软中止（终止标记+抑制晚到输出+清理活跃 run），不删除会话，回 `tool_done` | 无必需删除接口；利用内部终止状态 | 收到 `invoke.abort_session` 且会话存在时执行 | 是 | OpenClaw 侧 `abort` 明确为“中止不删除” |
| `invoke.permission_reply` | 可执行（随版本能力） | fail-closed 返回 `tool_error` | 无 | 收到后立即失败返回 | 当前版本否（设计差异） | V1 范围内明确不支持 |
| `invoke.question_reply` | 可执行（随版本能力） | fail-closed 返回 `tool_error` | 无 | 收到后立即失败返回 | 当前版本否（设计差异） | V1 范围内明确不支持 |

## 2. 上行消息矩阵

| message type | 产出路径 | 触发条件 | 关键字段 | 与 message-bridge 对齐情况 |
|---|---|---|---|---|
| `register` | 启动连接后发送 | 网关连接建立后 | `deviceName/macAddress/os/toolType/toolVersion` | 对齐 |
| `heartbeat` | 连接层周期发送 | 心跳定时器触发 | `timestamp` | 对齐 |
| `status_response` | 下行 `status_query` 响应 | 收到 `status_query` | `opencodeOnline` | 对齐（语义一致，判定来源不同） |
| `session_created` | `invoke.create_session` 成功 | 会话 ID 已确定并映射后 | `welinkSessionId/toolSessionId/session.sessionId` | 对齐 |
| `tool_event` | chat 过程事件 | busy/流式文本/tool 事件/idle/error | `toolSessionId/event` | 对齐（来源机制不同） |
| `tool_done` | `chat` 或 `abort_session` 完成 | chat 正常结束或 abort 成功 | `toolSessionId/welinkSessionId` | 对齐 |
| `tool_error` | 下行校验失败/执行失败 | 任一 action 失败 | `welinkSessionId?/toolSessionId?/error/reason?` | 对齐 |

## 3. 同一消息关键异同

### 3.1 `chat`

- 相同点：都对 `ai-gateway` 产出 `tool_event -> tool_done/tool_error` 语义闭环。
- 不同点：
  - `message-bridge`：转发宿主原生事件并做 `tool_done` 兼容去重。
  - `message-bridge-openclaw`：通过 reply/subagent 路径在插件内合成并发送协议事件。

### 3.2 `create_session`

- 相同点：都返回可用于后续 `chat/close/abort` 的 `toolSessionId`，并回 `session_created`。
- 不同点：
  - `message-bridge`：在 action 时调用宿主会话创建。
  - `message-bridge-openclaw`：桥接层分配 ID，首次 chat 以 `sessionKey` 进入会话上下文（懒创建）。

### 3.3 `abort_session`

- 相同点：成功后回 `tool_done`，并抑制后续无效输出。
- 不同点：
  - `message-bridge-openclaw` 当前定义为软中止，不执行会话删除。
  - `close_session` 才承担删除语义。

## 4. 兼容性结论

- `status_query/chat/create_session/close_session/abort_session` 在 `ai-gateway` 可观察协议语义上与 `message-bridge` 对齐。
- `permission_reply/question_reply` 在 openclaw 侧当前版本继续 fail-closed，属于已知范围差异。
- openclaw 实现严格以公开 runtime 能力为准，不依赖私有 `channel.session.create/delete/abort` 接口。
