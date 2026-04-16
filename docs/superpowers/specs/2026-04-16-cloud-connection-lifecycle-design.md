# 云端长连接生命周期管理

> Gateway 对云端助手 SSE / WebSocket 连接的超时控制与主动关闭机制。

---

## 1. 背景与问题

### 1.1 现状

Gateway 通过 `SseProtocolStrategy` 对接云端助手，采用 HTTP POST + SSE 流式响应。当前存在以下问题：

| 问题 | 现状 | 影响 |
|------|------|------|
| 无读超时 | 代码注释："不设请求级超时，由云端关闭 SSE 流来结束连接" | 云端挂死时 `reader.readLine()` 永久阻塞 |
| 无空闲检测 | 无心跳机制 | 无法区分"云端在思考"和"云端挂死" |
| 无总时长限制 | 无 maxDuration | 云端不发 `tool_done` 时连接永不释放 |
| 无主动关闭 | 收到 `tool_done` 后仍等云端关流 | 云端发完 `tool_done` 但不关流时再次陷入等待 |

### 1.2 用户体验影响

云端流式输出到一半静默时，用户看到：
- 部分回答文字已渲染
- `session.status` 停留在 `"busy"`（前端显示"正在输入..."）
- **永远不会停止**：GW 不发 `tool_done`，SS 不发 `session.status: "idle"`
- 刷新页面后 `StreamBufferService` 恢复快照，依然是 busy + 半截文字

全链路（GW → SS → 前端）没有任何一层有兜底超时。

### 1.3 目标

- 为云端长连接提供完整的超时管理，覆盖建联、首条报文、空闲、总时长四个阶段
- 收到 `tool_done` / `tool_error` 后主动关闭连接
- 同时适用于 SSE 和 WebSocket 两种协议
- 超时触发时向 SS 发送 `tool_error`，用户看到明确的错误提示而非永久等待

---

## 2. 超时模型

### 2.1 四层超时体系

```
① connectTimeout        ② firstEventTimeout
┌─────────────┐         ┌──────────────────┐
│ TCP+TLS+HTTP│────OK──→│等待首条数据/心跳  │
│ 握手阶段     │         │                  │
└──────┬──────┘         └────────┬─────────┘
       │fail                     │OK
       ↓                         ↓
  tool_error              ③ idleTimeout
 "建联超时"               ┌──────────────────┐
                         │每收到数据或心跳    │
                         │重置计时器         │
                         └────────┬─────────┘
                                  │超时
                                  ↓
                            tool_error
                           "响应超时"

④ maxDuration ─── 贯穿整个连接生命周期 ─── 安全网
```

### 2.2 各层职责

| 超时 | 覆盖场景 | 计时起点 | 重置条件 | 触发动作 |
|------|---------|---------|---------|---------|
| ① `connectTimeout` | 云端不可达、DNS 超时、TLS 失败 | 发起请求 | 不可重置 | `tool_error` + 关闭连接 |
| ② `firstEventTimeout` | 建联成功但云端排队/无响应 | HTTP 200 / WS 握手完成 | 收到首条数据**或**心跳 | `tool_error` + 关闭连接 |
| ③ `idleTimeout` | 中途挂死、网络分区、half-open | 每次收到数据/心跳 | 收到任何数据**或**心跳 | `tool_error` + 关闭连接 |
| ④ `maxDuration` | 云端死循环、永不发 `tool_done` | 连接建立 | 不可重置 | `tool_error` + 关闭连接 |

### 2.3 默认值

| 超时 | SSE 默认值 | WebSocket 默认值 | 依据 |
|------|-----------|-----------------|------|
| `connectTimeout` | 30s | 30s | TCP + TLS + 握手 |
| `firstEventTimeout` | 120s | 120s | 大模型冷启动 + 排队可能较慢 |
| `idleTimeout` | 90s | 60s | SSE 无原生心跳稍宽松；WS 有 ping/pong 更精确 |
| `maxDuration` | 10min | 10min | 单轮对话合理上限 |

### 2.4 心跳机制

| 协议 | 心跳方式 | 建议间隔 | 说明 |
|------|---------|---------|------|
| SSE | 注释行 `: heartbeat\n\n` | 云端每 30s | SSE 规范标准特性，客户端自动忽略冒号开头行 |
| WebSocket | RFC 6455 Ping/Pong | GW 每 30s 发 Ping | 原生协议支持，不需要云端额外开发 |

**降级策略**：如果云端不发 SSE 心跳，`idleTimeout` 仍然生效作为兜底（退化为无心跳的简单空闲超时），不会导致功能异常，只是区分精度降低。

---

## 3. 正常断联流程

### 3.1 主动关闭规则

> **GW 收到 `tool_done` 或 `tool_error` 后，立即主动关闭连接，不等云端关。**

`tool_done` / `tool_error` 是协议定义的一轮结束信号，语义明确。主动关闭避免了"云端发完 `tool_done` 但不关流"的边界问题。

### 3.2 正常流程时序

```
云端发送最后一条 tool_event (text.done)
        ↓
云端发送 tool_done
        ↓
GW 转发 tool_done 给 SS
        ↓
GW 主动关闭连接
  ├─ SSE: 关闭 InputStream
  └─ WebSocket: 发送 Close Frame (1000 Normal)
        ↓
lifecycle.close() → 取消所有计时器
        ↓
SS 广播 session.status: "idle"
        ↓
前端：停止 loading，显示完整回答
```

正常对话流程中四层超时都不会触发。

---

## 4. 架构设计

### 4.1 核心抽象：CloudConnectionLifecycle

SSE 和 WebSocket 的超时逻辑高度相似，抽取统一的连接生命周期管理器：

```
┌─────────────────────────────────────────────────┐
│            CloudConnectionLifecycle              │
│                                                  │
│  职责：                                           │
│  • 管理四层超时计时器                               │
│  • 监听心跳信号（SSE 注释行 / WS Pong）             │
│  • 监听 tool_done/tool_error 触发主动关闭           │
│  • 超时触发时调用 close() + 回调发送 tool_error      │
│                                                  │
│  接口：                                           │
│  • onConnected()      → 启动 firstEventTimeout    │
│  • onEventReceived()  → 重置 idleTimeout           │
│  • onHeartbeat()      → 重置 idleTimeout           │
│  • onTerminalEvent()  → 主动关闭连接               │
│  • close()            → 取消所有计时器 + 关闭连接    │
└────────────────┬────────────────────────────────┘
                 │
        ┌────────┴────────┐
        ↓                 ↓
  SseProtocol        WebSocketProtocol
  Strategy           Strategy (新增)
```

### 4.2 SSE 改造

改造 `SseProtocolStrategy.connect()` 方法：

1. HTTP POST 发起请求 → `connectTimeout` 由 `HttpClient.connectTimeout` 保障（已有）
2. 拿到 Response 200 → `lifecycle.onConnected()` → 启动 `firstEventTimeout` + `maxDuration`
3. 读取循环中每一行：
   - `: heartbeat` → `lifecycle.onHeartbeat()`
   - `data: {json}` → 解析为 `GatewayMessage` → `lifecycle.onEventReceived()` → `onEvent.accept(msg)` → 如果是 `tool_done`/`tool_error` → `lifecycle.onTerminalEvent()`
   - 空行 → 跳过
4. 超时触发时：从调度线程关闭 `InputStream` → `readLine()` 抛出 `IOException` → 退出循环 → `onError` 回调

**关键技术点**：`reader.readLine()` 是阻塞调用，超时关闭需要从 `ScheduledExecutorService` 调度的超时任务中关闭 `InputStream`，使阻塞读取抛出异常退出。

### 4.3 WebSocket 策略（新增）

新增 `WebSocketProtocolStrategy`，实现 `CloudProtocolStrategy` 接口：

1. 建立 WS 连接 → `connectTimeout` 由握手阶段控制 → `lifecycle.onConnected()`
2. `onMessage` 回调 → 解析 `GatewayMessage` → `lifecycle.onEventReceived()` → if `tool_done` → `lifecycle.onTerminalEvent()`
3. Ping/Pong 心跳 → GW 每 30s 发 Ping → 收到 Pong → `lifecycle.onHeartbeat()` → Pong 超时等同 `idleTimeout` 触发
4. 主动关闭 → 发送 Close Frame (1000 Normal Closure)

### 4.4 配置结构

```yaml
gateway:
  cloud:
    # 通用默认值
    connect-timeout-seconds: 30
    first-event-timeout-seconds: 120
    idle-timeout-seconds: 90
    max-duration-seconds: 600

    # 协议级覆盖（可选）
    sse:
      idle-timeout-seconds: 90
    websocket:
      idle-timeout-seconds: 60
      ping-interval-seconds: 30
```

### 4.5 tool_error 消息格式

超时触发时 GW 构造的错误消息：

```json
{
  "type": "tool_error",
  "ak": "...",
  "traceId": "...",
  "payload": {
    "errorCode": "CLOUD_TIMEOUT",
    "errorMessage": "云端服务响应超时",
    "timeoutType": "idle_timeout",
    "elapsedSeconds": 90
  }
}
```

`timeoutType` 取值：`connect_timeout` / `first_event_timeout` / `idle_timeout` / `max_duration`，方便 SS 侧区分展示不同的用户提示语。

---

## 5. 文件变更清单

| 文件 | 变更类型 | 说明 |
|------|---------|------|
| `CloudConnectionLifecycle.java` | 新增 | 统一超时管理器，ScheduledExecutorService 驱动 |
| `CloudTimeoutProperties.java` | 新增 | `@ConfigurationProperties` 绑定 yaml 配置 |
| `SseProtocolStrategy.java` | 改造 | 接入 lifecycle，识别心跳注释行，terminal event 主动关闭 |
| `WebSocketProtocolStrategy.java` | 新增 | WebSocket 协议策略实现 |
| `CloudAgentService.java` | 小改 | 创建 lifecycle 实例，传递 onError 中构造 tool_error |
| `application.yml` | 小改 | 新增超时配置项 |
| 测试文件 | 新增 | lifecycle 单测 + SSE/WS 超时集成测试 |

---

## 6. 不在范围内

- SS 侧超时检测（GW 已覆盖，SS 无需重复）
- 前端侧超时 UI（由 SS 转发 `tool_error` 后现有错误展示机制处理）
- 云端心跳实现（本设计仅定义契约，云端按需实现）
- 连接池/复用（每轮对话一个连接，当前模型不变）
