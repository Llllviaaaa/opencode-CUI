# 入站来源驱动的投递路由设计

> 日期：2026-04-15
> 状态：Draft

## 1. 问题描述

当前 `OutboundDeliveryDispatcher` 的投递策略是 first-match 模式，按优先级依次匹配：

1. **MiniappDeliveryStrategy**（order=1）— `isMiniappDomain()`
2. **ExternalWsDeliveryStrategy**（order=2）— `!isMiniappDomain() && hasActiveConnections(domain)`
3. **ImRestDeliveryStrategy**（order=3）— `isImDomain() && !hasActiveConnections(domain)`

`hasActiveConnections()` 是 **domain 级别**的检查。当 IM 平台迁移到 ExternalInbound + WS 后，只要有一个 External 客户端为 "im" domain 建立了 WS 连接，`hasActiveConnections("im")` 就为 true，导致所有 IM domain 的消息都被 ExternalWs 策略拦截，ImInbound 的消息无法走 REST 投递。

### 背景

- **ImInbound** 是过渡接口：IM 平台以前不支持流式，通过 REST 回调接收响应
- **ExternalInbound** 是通用接口：IM 平台已支持流式，正在迁移到 ExternalInbound + WS
- 过渡期内，同一个 IM domain 下，不同 session 可能走不同入口
- 同一个 session 不会被两个入口并发使用

### 期望行为

- ImInbound 接口进来的消息 → 响应只走 REST API 投递
- ExternalInbound 接口进来的消息 → 响应只走 WebSocket 投递
- ExternalInbound 投递时若无 WS 订阅者 → 打警告日志 + 丢弃（后续再加缓存）

## 2. 设计方案

### 核心思路：invoke-source Redis 标记

在入站请求时写入一个轻量的 Redis key，标记当前 session 的投递通道。响应回来时，Dispatcher 读取此标记决定走 REST 还是 WS。

### 2.1 标记写入

标记在 `InboundProcessingService.processChat()` 内部写入，而非 Controller 层。原因：
- session（welinkSessionId）在 `processChat()` 第 4 步才确定（查找或异步创建）
- 情况 A（新建 session）中 invoke 在异步流程中发出，Controller 层写标记可能晚于 AI 响应

**改动方式：** `processChat()` 新增 `inboundSource` 参数，Controller 传入 `"IM"` 或 `"EXTERNAL"`。在 session 确定后（情况 A/B/C 均有 session 可用时），写入 Redis 标记。`processQuestionReply()` 和 `processPermissionReply()` 同理，因为它们也触发 AI 响应流。

| 入口 | 传入 inboundSource | 写入时机 |
|------|-------------------|---------|
| `ImInboundController` | `"IM"` | `processChat()` 内部，session 确定后 |
| `ExternalInboundController` | `"EXTERNAL"` | `processChat()` 内部，session 确定后 |

- Redis 操作：`SET invoke-source:{welinkSessionId} {IM|EXTERNAL} EX 300`
- key 格式：`invoke-source:{welinkSessionId}`
- value：`IM` 或 `EXTERNAL`
- TTL：300 秒（5 分钟），防止不活跃 session 的 key 残留
- 同一 session 不会被并发使用，无竞争问题

### 2.2 标记读取与投递决策

`OutboundDeliveryDispatcher.deliver()` 在遍历策略之前，先读取 `invoke-source:{welinkSessionId}`：

| invoke-source | 投递策略 |
|---------------|---------|
| `IM` | 只走 `ImRestDeliveryStrategy` |
| `EXTERNAL` | 只走 `ExternalWsDeliveryStrategy` |
| 不存在（兜底） | 走当前 first-match 逻辑 |

### 2.3 ExternalWs 无订阅者检测

投递前通过 `PUBSUB NUMSUB stream:{domain}` 检查是否有订阅者：
- 有订阅者 → 正常 `publishToChannel` 投递
- 无订阅者 → 打警告日志 + 丢弃消息

### 2.4 MiniApp 路径不受影响

MiniApp 域的判断优先于 invoke-source 标记。`isMiniappDomain()` 为 true 时直接走 MiniappDeliveryStrategy，不读取 Redis 标记。

## 3. 改动范围

### 3.1 新增

| 文件 | 改动 |
|------|------|
| `RedisMessageBroker` | 新增 `setInvokeSource(sessionId, source)` 和 `getInvokeSource(sessionId)` 方法；新增 `getChannelSubscriberCount(channel)` 方法 |

### 3.2 修改

| 文件 | 改动 |
|------|------|
| `InboundProcessingService` | `processChat()`、`processQuestionReply()`、`processPermissionReply()` 新增 `inboundSource` 参数，session 确定后写 Redis 标记 |
| `ImInboundController` | 调用 `processChat()` 时传入 `"IM"` |
| `ExternalInboundController` | 调用 `processChat()`、`processQuestionReply()`、`processPermissionReply()` 时传入 `"EXTERNAL"` |
| `OutboundDeliveryDispatcher` | 读取 invoke-source 标记，按标记选择策略；MiniApp 优先判断不变 |
| `ImRestDeliveryStrategy` | `supports()` 去掉 `hasActiveConnections` 检查，去掉 `ExternalStreamHandler` 依赖 |
| `ExternalWsDeliveryStrategy` | 投递前检查 `PUBSUB NUMSUB`，无订阅者时打警告丢弃 |

### 3.3 不改动

| 文件 | 原因 |
|------|------|
| `SkillSession` | 不改数据模型 |
| `MiniappDeliveryStrategy` | MiniApp 路径不受影响 |
| `GatewayMessageRouter` | 路由逻辑不变 |
| `ExternalStreamHandler` | WS 连接管理不变 |

## 4. 投递决策流程

```
StreamMessage 到达 OutboundDeliveryDispatcher.deliver(session, sessionId, userId, msg)
    │
    ├─ session.isMiniappDomain()? → Yes: MiniappDeliveryStrategy 投递 → return
    │
    ├─ 读取 Redis: invoke-source:{welinkSessionId}
    │
    ├─ value == "IM"
    │  └─ ImRestDeliveryStrategy 投递 → return
    │
    ├─ value == "EXTERNAL"
    │  ├─ PUBSUB NUMSUB stream:{domain} > 0?
    │  │  ├─ Yes → ExternalWsDeliveryStrategy 投递 → return
    │  │  └─ No → 警告日志 + 丢弃 → return
    │  │
    │
    └─ value 不存在（兜底）
       └─ 走当前 first-match 逻辑（向后兼容）
```

## 5. 场景验证

| # | 场景 | invoke-source | 投递方式 | 结果 |
|---|------|--------------|---------|------|
| 1 | IM 用户通过 ImInbound 发消息 | IM | REST | IM 用户在 IM 收到回复 |
| 2 | IM 用户通过 ImInbound 发消息，同时有其他 External WS 连接 | IM | REST | 不被 WS 拦截 |
| 3 | External 通过 ExternalInbound 发消息（domain=im），有 WS | EXTERNAL | WS | 外部客户端通过 WS 收到响应 |
| 4 | External 通过 ExternalInbound 发消息（domain=custom），有 WS | EXTERNAL | WS | 正常 WS 投递 |
| 5 | External 通过 ExternalInbound 发消息，无 WS 连接 | EXTERNAL | 丢弃 | 警告日志，后续加缓存 |
| 6 | MiniApp 场景 | (不读取) | MiniApp | 不受影响 |
| 7 | 历史 session，无 invoke-source 标记 | (不存在) | first-match 兜底 | 向后兼容 |

## 6. TTL 续期

invoke-source key 的 TTL 为 300 秒。AI 流式响应可能持续较长时间，需要在响应流活跃期间续期：

- `OutboundDeliveryDispatcher` 每次读取 invoke-source 时，若 key 存在则续期（`EXPIRE invoke-source:{welinkSessionId} 300`）
- 确保长时间流式响应期间标记不会过期
