# Message Bridge OpenClaw 日志事件矩阵

本文档用于联调和故障定位时快速对照 `message-bridge-openclaw` 的关键日志事件。

日志命名风格对齐 `message-bridge`：`runtime.* / downstream.* / gateway.* / bridge.chat.*`。

## 1. 连接与注册（WebSocket）

| 阶段 | 事件名 | 级别 | 关键字段 | 说明 |
| --- | --- | --- | --- | --- |
| 发起连接 | `gateway.connect.started` | info | `url` | 开始建立 WebSocket 连接 |
| 连接建立 | `gateway.open` | info | `url` | socket 已打开 |
| 发送注册 | `gateway.register.sent` | info | - | register 报文已发送 |
| 注册成功 | `gateway.register.accepted` | info | - | 收到 `register_ok` |
| 进入就绪 | `gateway.ready` | info | - | 连接进入 READY，开始心跳 |
| 状态变化 | `gateway.state.changed` | info | `state` | 连接状态机变化（CONNECTING/CONNECTED/READY/DISCONNECTED） |
| 注册拒绝 | `gateway.register.rejected` | error | `reason` | 网关拒绝注册 |
| 连接错误 | `gateway.error` | error | - | WebSocket error |
| 重连计划 | `gateway.reconnect.scheduled` | info | `reconnectAttempts`,`delayMs` | 已安排下一次重连 |
| 重连执行 | `gateway.reconnect.attempt` | info | `reconnectAttempts` | 开始执行重连 |
| 重连失败 | `gateway.reconnect.failed` | warn | `error` | 单次重连失败 |

## 2. 网关收发与下行归一化

| 阶段 | 事件名 | 级别 | 关键字段 | 说明 |
| --- | --- | --- | --- | --- |
| 收到帧 | `gateway.message.received` | debug/info* | `messageType`,`frameBytes` | 收到并解析 JSON 帧 |
| 非 JSON 帧 | `gateway.message.ignored_non_json` | debug/info* | `payloadLength`,`frameBytes` | 非 JSON 帧被忽略 |
| 未 READY 消息 | `gateway.message.received_not_ready` | debug/info* | `messageType`,`state` | READY 前收到业务消息（观测告警，不会直接丢弃） |
| 发送帧 | `gateway.send` | debug/info* | `messageType`,`payloadBytes` | 向网关发送报文 |
| runtime 收到下行 | `runtime.downstream.received` | debug/info* | `messageType`,`action`,`welinkSessionId`,`toolSessionId` | runtime 下行入口 |
| 无连接丢弃 | `runtime.downstream_ignored_no_connection` | warn | - | runtime 收到消息但连接不可用 |
| 归一化失败 | `downstream.normalization_failed` | warn | `stage`,`errorCode`,`field`,`messageType`,`action`,`welinkSessionId`,`messagePreview` | 协议校验失败 |
| 非协议下行 | `runtime.downstream_ignored_non_protocol` | warn | `errorCode`,`stage`,`field`,`errorMessage` | runtime 丢弃非法消息 |
| 归一化成功 | `downstream.normalization_succeeded` | debug/info* | `messageType`,`action`,`welinkSessionId`,`toolSessionId` | 归一化通过 |

\* `debug` 不可用时会降级到 `info`。

## 3. 调用路由与执行结果

| 阶段 | 事件名 | 级别 | 关键字段 | 说明 |
| --- | --- | --- | --- | --- |
| status_query 收到 | `runtime.status_query.received` | info | `messageType` | 收到状态查询 |
| status_query 回应 | `runtime.status_query.responded` | info | `latencyMs` | 已回传 `status_response` |
| invoke 收到 | `runtime.invoke.received` | info | `action`,`welinkSessionId`,`toolSessionId` | invoke 分发入口 |
| invoke 完成 | `runtime.invoke.completed` | info | `action`,`welinkSessionId`,`toolSessionId`,`latencyMs` | invoke 执行完成 |

## 4. 上行发送（tool_event/tool_done/tool_error）

| 阶段 | 事件名 | 级别 | 关键字段 | 说明 |
| --- | --- | --- | --- | --- |
| 发送 tool_event | `runtime.tool_event.sending` | debug/info* | `toolSessionId`,`eventType` | 发送 `tool_event` 前 |
| 发送 tool_done | `runtime.tool_done.sending` | info | `toolSessionId`,`welinkSessionId` | 发送 `tool_done` 前 |
| tool_done 跳过 | `runtime.tool_done.skipped_no_connection` | warn | `toolSessionId`,`welinkSessionId` | 无连接，`tool_done` 未实际发出 |
| 发送 tool_error | `runtime.tool_error.sending` | error | `toolSessionId`,`welinkSessionId`,`error`,`reason` | 发送 `tool_error` 前 |
| tool_error 跳过 | `runtime.tool_error.skipped_no_connection` | warn | `toolSessionId`,`welinkSessionId` | 无连接，`tool_error` 未实际发出 |

## 5. Chat 业务链路（明文）

| 阶段 | 事件名 | 级别 | 关键字段 | 说明 |
| --- | --- | --- | --- | --- |
| 请求开始 | `bridge.chat.started` | info | `chatText`,`textLength`,`executionPath`,`configuredTimeoutMs`,`chatRequestId`,`retryAttempt` | chat 调用开始 |
| 模型选定 | `bridge.chat.model_selected` | info | `provider`,`model`,`thinkLevel` | runtime_reply 路径模型选择 |
| 首块输出 | `bridge.chat.first_chunk` | info | `deltaText`,`chunkLength`,`latencyMs`,`retryAttempt` | 第一段文本产出 |
| 分块输出 | `bridge.chat.chunk` | info | `deltaText`,`chunkIndex`,`chunkLength`,`sinceStartMs` | 后续文本分块 |
| 调用完成 | `bridge.chat.completed` | info | `finalText`,`responseLength`,`chunkCount`,`firstChunkLatencyMs`,`totalLatencyMs` | chat 成功结束 |
| 调用失败 | `bridge.chat.failed` | warn | `error`,`failureStage`,`errorCategory`,`timedOut`,`chunkCount` | chat 失败归因 |

## 6. 插件生命周期

| 阶段 | 事件名 | 级别 | 关键字段 | 说明 |
| --- | --- | --- | --- | --- |
| 启动请求 | `runtime.start.requested` | info | `accountId` | 账户实例启动请求 |
| 重复启动 | `runtime.start.skipped_already_started` | info | `accountId` | 已运行，跳过 |
| 启动完成 | `runtime.start.completed` | info | `accountId` | 启动完成 |
| 停止请求 | `runtime.stop.requested` | info | `accountId` | 停止请求 |
| 重复停止 | `runtime.stop.skipped_not_running` | info | `accountId` | 未运行，跳过 |
| 停止完成 | `runtime.stop.completed` | info | `accountId` | 停止完成 |

## 7. 建议检索顺序（定位“消息返回为空”）

1. `runtime.downstream.received`：确认请求是否到达插件。
2. `downstream.normalization_failed` / `runtime.downstream_ignored_non_protocol`：确认是否协议校验失败。
3. `runtime.invoke.received`：确认是否进入 action 分发。
4. `bridge.chat.started -> bridge.chat.first_chunk|bridge.chat.failed`：确认 chat 执行与首块状态。
5. `runtime.tool_error.sending` / `runtime.tool_done.sending` / `runtime.tool_event.sending`：确认上行回传是否发生。
6. 若看到 `*.skipped_no_connection`：优先排查 `gateway.state.changed`、`gateway.reconnect.*`。

## 8. 常用检索命令（按问题类型）

以下命令默认针对本地栈日志：

- `/Users/zy/Code/opencode/opencode-CUI/logs/local-stack/ai-gateway.log`

如需实时观察，可先执行：

```bash
tail -f /Users/zy/Code/opencode/opencode-CUI/logs/local-stack/ai-gateway.log
```

### 8.1 协议校验失败（常见“返回为空”）

```bash
rg -n "downstream.normalization_failed|runtime.downstream_ignored_non_protocol|runtime.tool_error.sending" \
  /Users/zy/Code/opencode/opencode-CUI/logs/local-stack/ai-gateway.log
```

重点看字段：

- `stage`
- `field`
- `errorCode`
- `welinkSessionId`
- `toolSessionId`

### 8.2 连接不稳定 / 断连重连

```bash
rg -n "gateway.state.changed|gateway.connect.started|gateway.register.sent|gateway.register.accepted|gateway.ready|gateway.reconnect.scheduled|gateway.reconnect.attempt|gateway.reconnect.failed|gateway.error" \
  /Users/zy/Code/opencode/opencode-CUI/logs/local-stack/ai-gateway.log
```

重点看链路是否完整：

- `connect.started -> open -> register.sent -> register.accepted -> ready`

### 8.3 消息到达但未执行 invoke

```bash
rg -n "runtime.downstream.received|runtime.invoke.received|runtime.downstream_ignored_no_connection|gateway.message.received_not_ready" \
  /Users/zy/Code/opencode/opencode-CUI/logs/local-stack/ai-gateway.log
```

判定思路：

- 有 `runtime.downstream.received`，无 `runtime.invoke.received`：通常在 normalize 或连接状态分支被拦截。

### 8.4 Chat 首块超时 / 首块后失败

```bash
rg -n "bridge.chat.started|bridge.chat.model_selected|bridge.chat.first_chunk|bridge.chat.chunk|bridge.chat.failed|bridge.chat.completed" \
  /Users/zy/Code/opencode/opencode-CUI/logs/local-stack/ai-gateway.log
```

重点看：

- `failureStage`（`before_first_chunk` / `after_first_chunk`）
- `errorCategory`（`timeout` / `runtime_error`）
- `configuredTimeoutMs`
- `retryAttempt`

### 8.5 上行发送是否成功（tool_event/tool_done/tool_error）

```bash
rg -n "runtime.tool_event.sending|runtime.tool_done.sending|runtime.tool_error.sending|runtime.tool_done.skipped_no_connection|runtime.tool_error.skipped_no_connection" \
  /Users/zy/Code/opencode/opencode-CUI/logs/local-stack/ai-gateway.log
```

判定思路：

- 出现 `*.sending` 但紧接着 `*.skipped_no_connection`：发送阶段连接已不可用。
