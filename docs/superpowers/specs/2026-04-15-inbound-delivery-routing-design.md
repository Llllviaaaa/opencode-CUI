# 配置开关驱动的投递路由设计

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
- 过渡期结束后，所有上游系统都会切到 ExternalInbound + WS

### 期望行为

- 通过配置开关控制非 miniapp 域的投递方式
- `mode=rest`：所有消息走 REST 投递（过渡期使用）
- `mode=ws`：优先走 WS 投递；若所有 SS 都没有对应 domain 的 WS 连接，降级走 REST
- MiniApp 路径不受影响

## 2. 设计方案

### 核心思路：配置开关 + WS 降级

用一个配置项控制投递模式，WS 模式下通过 Redis `PUBSUB NUMSUB` 检测全局订阅者数量，无订阅者时降级到 REST。

### 2.1 配置项

```yaml
skill:
  delivery:
    mode: rest   # rest | ws
```

- `rest`：非 miniapp 域的消息全部走 ImRest 投递
- `ws`：非 miniapp 域的消息优先走 ExternalWs 投递，无订阅者时降级到 ImRest

### 2.2 WS 无订阅者降级

`mode=ws` 时，投递前通过 `PUBSUB NUMSUB stream:{domain}` 检查是否有订阅者：

- 订阅者数 > 0 → 走 ExternalWs 投递（`publishToChannel`）
- 订阅者数 = 0 → 说明所有 SS 都没有该 domain 的 WS 连接，降级走 ImRest 投递

这利用了现有机制：`ExternalStreamHandler` 在 WS 连接建立时 `subscribe`，全部断开时 `unsubscribe`，`PUBSUB NUMSUB` 天然反映全局连接状态。

### 2.3 MiniApp 路径不受影响

MiniApp 域的判断优先于开关。`isMiniappDomain()` 为 true 时直接走 MiniappDeliveryStrategy，不看开关。

## 3. 改动范围

### 3.1 修改

| 文件 | 改动 |
|------|------|
| `OutboundDeliveryDispatcher` | 注入配置项 `skill.delivery.mode`，按开关选择投递策略；ws 模式下检查订阅者数量，无订阅者降级 |
| `ImRestDeliveryStrategy` | `supports()` 去掉 `hasActiveConnections` 检查，去掉 `ExternalStreamHandler` 依赖。简化为 `!isMiniappDomain() && isImDomain()` |
| `ExternalWsDeliveryStrategy` | `supports()` 去掉 `hasActiveConnections` 检查（改由 Dispatcher 统一判断）。简化为 `!isMiniappDomain()` |
| `RedisMessageBroker` | 新增 `getChannelSubscriberCount(channel)` 方法，封装 `PUBSUB NUMSUB` |
| `application.yml` | 新增 `skill.delivery.mode: rest` 配置项 |

### 3.2 不改动

| 文件 | 原因 |
|------|------|
| `SkillSession` | 不改数据模型 |
| `MiniappDeliveryStrategy` | MiniApp 路径不受影响 |
| `InboundProcessingService` | 不需要标记入站来源 |
| `ImInboundController` | 无改动 |
| `ExternalInboundController` | 无改动 |
| `GatewayMessageRouter` | 路由逻辑不变 |
| `ExternalStreamHandler` | WS 连接管理不变 |

## 4. 投递决策流程

```
StreamMessage 到达 OutboundDeliveryDispatcher.deliver(session, sessionId, userId, msg)
    │
    ├─ session.isMiniappDomain()? → Yes: MiniappDeliveryStrategy 投递 → return
    │
    ├─ 读取配置: skill.delivery.mode
    │
    ├─ mode == "rest"
    │  └─ ImRestDeliveryStrategy 投递 → return
    │
    └─ mode == "ws"
       ├─ PUBSUB NUMSUB stream:{domain} > 0?
       │  ├─ Yes → ExternalWsDeliveryStrategy 投递 → return
       │  └─ No → 降级: ImRestDeliveryStrategy 投递 → return
```

## 5. 场景验证

| # | 场景 | mode | 订阅者 | 投递方式 | 结果 |
|---|------|------|--------|---------|------|
| 1 | 过渡期，IM 用户通过 ImInbound 发消息 | rest | - | REST | IM 用户收到回复 |
| 2 | 过渡期，External 通过 ExternalInbound 发消息 | rest | - | REST | 响应走 REST（过渡期统一） |
| 3 | 迁移完成，External 发消息，有 WS 连接 | ws | >0 | WS | 外部客户端通过 WS 收到响应 |
| 4 | 迁移完成，External 发消息，WS 全断 | ws | 0 | REST（降级） | 降级到 REST，消息不丢 |
| 5 | MiniApp 场景 | 任意 | - | MiniApp | 不受影响 |

## 6. 迁移路径

1. **当前（过渡期）**：配置 `mode=rest`，所有非 miniapp 消息走 REST，行为不变
2. **迁移完成**：IM 平台全部切到 ExternalInbound + WS 后，配置改为 `mode=ws`
3. **降级保障**：ws 模式下若 WS 全断，自动降级到 REST，不会丢消息
