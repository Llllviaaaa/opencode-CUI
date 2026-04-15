# 配置开关 + 精确投递的出站路由设计

> 日期：2026-04-15
> 状态：Draft

## 1. 问题描述

### 1.1 投递模式问题

当前 `OutboundDeliveryDispatcher` 的投递策略是 first-match 模式，按优先级依次匹配：

1. **MiniappDeliveryStrategy**（order=1）— `isMiniappDomain()`
2. **ExternalWsDeliveryStrategy**（order=2）— `!isMiniappDomain() && hasActiveConnections(domain)`
3. **ImRestDeliveryStrategy**（order=3）— `isImDomain() && !hasActiveConnections(domain)`

`hasActiveConnections()` 是 **domain 级别**的检查。当 IM 平台迁移到 ExternalInbound + WS 后，只要有一个 External 客户端为 "im" domain 建立了 WS 连接，`hasActiveConnections("im")` 就为 true，导致所有 IM domain 的消息都被 ExternalWs 策略拦截，ImInbound 的消息无法走 REST 投递。

### 1.2 广播浪费问题

当前 `ExternalWsDeliveryStrategy` 通过 Redis pub/sub 广播到 `stream:{domain}`，所有订阅的 SS 实例都会收到消息并推送到各自的 WS 连接。如果上游同一个 Pod 连接了多台 SS，会收到重复消息，造成无效带宽浪费。

### 背景

- **ImInbound** 是过渡接口：IM 平台以前不支持流式，通过 REST 回调接收响应
- **ExternalInbound** 是通用接口：IM 平台已支持流式，正在迁移到 ExternalInbound + WS
- 过渡期结束后，所有上游系统都会切到 ExternalInbound + WS
- SS 是分布式部署，上游每个 Pod 可能在多台 SS 上建立多条 WS 连接

### 期望行为

- 通过配置开关控制非 miniapp 域的投递方式
- `mode=rest`：所有消息走 REST 投递（过渡期使用）
- `mode=ws`：精确投递到一条 WS 连接；若所有 SS 都没有对应 domain 的 WS 连接，降级走 REST
- 每条消息只投递一次，不广播

## 2. 设计方案

### 核心思路：配置开关 + WS 连接注册表 + 精确投递

借鉴 GW → SS 的三层路由模式（L1 本地 → L2 Redis relay → L3 兜底），为 SS → External WS 投递建立类似的精确投递机制。

### 2.1 配置项

```yaml
skill:
  delivery:
    mode: rest   # rest | ws
```

- `rest`：非 miniapp 域的消息全部走 ImRest 投递
- `ws`：非 miniapp 域的消息走 WS 精确投递，无连接时降级到 ImRest

### 2.2 WS 连接注册表

每个 SS 实例在 `ExternalStreamHandler` 中维护本地连接，同时向 Redis 注册全局连接信息。

**Redis 数据结构：**

```
# 每个 SS 实例注册自己持有的 domain 连接
# Key: external-ws:registry:{domain}
# Type: Hash
# Field: ss-instance-id
# Value: 连接数量
# 通过定时心跳续期

HSET external-ws:registry:{domain} {ss-instance-id} {connectionCount}
EXPIRE external-ws:registry:{domain} 30
```

**注册时机：**
- `afterConnectionEstablished()`：注册/更新连接数
- `afterConnectionClosed()`：更新连接数，连接数为 0 时 HDEL
- 定时心跳（每 10 秒）：续期 TTL，防止 SS 宕机后残留

### 2.3 三层投递路由

```
SS（session owner）需要投递 External WS 消息
    │
    ├─ L1 本地投递：本 SS 有该 domain 的 WS 连接？
    │  └─ Yes → 选一条本地 WS 连接直推 → 完成
    │
    ├─ L2 Redis relay：查 external-ws:registry:{domain}，找到有连接的 SS？
    │  └─ Yes → 选一台 SS，通过 Redis relay 投递 → 完成
    │     （发布到 ss:external-relay:{targetInstanceId} channel）
    │
    └─ L3 降级：无任何 SS 有连接
       └─ mode=ws → 降级走 ImRest（仅 IM domain）
       └─ 非 IM domain → 警告日志 + 丢弃
```

### 2.4 L1 本地投递

`ExternalStreamHandler.pushToOne(domain, message)`：

- 从本地 `connectionPool.get(domain)` 中选**一条**活跃连接推送
- 选择策略：遍历取第一条 `isOpen()` 的连接即可（简单可靠）
- 不再广播到所有连接

### 2.5 L2 跨 SS relay

当本 SS 没有目标 domain 的 WS 连接时：

1. 查询 Redis：`HGETALL external-ws:registry:{domain}` → 获取有连接的 SS 实例列表
2. 选择一台 SS（取第一个即可）
3. 发布到目标 SS 的 relay channel：`PUBLISH ss:external-relay:{targetInstanceId} {message}`
4. 目标 SS 收到后调用本地 `pushToOne()` 推送

**每个 SS 启动时订阅自己的 relay channel：** `ss:external-relay:{自己的instanceId}`

### 2.6 MiniApp 路径不受影响

MiniApp 域的判断优先于开关。`isMiniappDomain()` 为 true 时直接走 MiniappDeliveryStrategy。

## 3. 改动范围

### 3.1 新增

| 文件 | 说明 |
|------|------|
| `ExternalWsRegistry` | WS 连接注册表服务：注册/注销/查询/心跳续期 |

### 3.2 修改

| 文件 | 改动 |
|------|------|
| `OutboundDeliveryDispatcher` | 注入配置项 `skill.delivery.mode`，按开关选择投递策略 |
| `ExternalStreamHandler` | 连接建立/断开时调用 `ExternalWsRegistry` 注册/注销；新增 `pushToOne()` 方法（选一条连接推送）；启动时订阅自身 relay channel |
| `ImRestDeliveryStrategy` | `supports()` 去掉 `hasActiveConnections` 检查，去掉 `ExternalStreamHandler` 依赖 |
| `ExternalWsDeliveryStrategy` | 改为三层路由：L1 本地 → L2 relay → L3 降级；去掉 pub/sub 广播 |
| `RedisMessageBroker` | 新增 registry 相关方法（HSET/HGETALL/HDEL）和 relay channel 订阅方法 |
| `application.yml` | 新增 `skill.delivery.mode: rest` 配置项 |

### 3.3 不改动

| 文件 | 原因 |
|------|------|
| `SkillSession` | 不改数据模型 |
| `MiniappDeliveryStrategy` | MiniApp 路径不受影响 |
| `InboundProcessingService` | 不需要标记入站来源 |
| `ImInboundController` | 无改动 |
| `ExternalInboundController` | 无改动 |
| `GatewayMessageRouter` | 路由逻辑不变 |

## 4. 投递决策流程

```
StreamMessage 到达 OutboundDeliveryDispatcher.deliver(session, sessionId, userId, msg)
    │
    ├─ session.isMiniappDomain()? → Yes: MiniappDeliveryStrategy 投递 → return
    │
    ├─ 读取配置: skill.delivery.mode
    │
    ├─ mode == "rest"
    │  └─ session.isImDomain()? → Yes: ImRestDeliveryStrategy 投递 → return
    │                            → No: 警告日志 + 丢弃 → return
    │
    └─ mode == "ws"
       ├─ L1: 本地有 WS 连接?
       │  └─ Yes → pushToOne() 直推 → return
       │
       ├─ L2: Redis registry 有其他 SS 持有连接?
       │  └─ Yes → relay 到目标 SS → return
       │
       └─ L3: 无任何 WS 连接
          ├─ session.isImDomain()? → Yes: ImRest 降级 → return
          └─ No: 警告日志 + 丢弃 → return
```

## 5. 场景验证

| # | 场景 | mode | L1/L2/L3 | 投递方式 | 结果 |
|---|------|------|----------|---------|------|
| 1 | 过渡期，IM 用户发消息 | rest | - | REST | IM 用户收到回复 |
| 2 | 迁移完成，本 SS 有 WS 连接 | ws | L1 | WS 直推 | 零网络开销，推一条 |
| 3 | 迁移完成，本 SS 无连接，其他 SS 有 | ws | L2 | Redis relay → WS | 跨 SS 精确投递，推一条 |
| 4 | 迁移完成，所有 SS 都无连接（IM domain） | ws | L3 | REST 降级 | 消息不丢 |
| 5 | 迁移完成，所有 SS 都无连接（非 IM domain） | ws | L3 | 丢弃 | 警告日志 |
| 6 | MiniApp 场景 | 任意 | - | MiniApp | 不受影响 |

## 6. 连接注册表生命周期

```
ExternalStreamHandler
    │
    ├─ afterConnectionEstablished(session)
    │  ├─ 加入本地 connectionPool（现有逻辑不变）
    │  └─ ExternalWsRegistry.register(domain, instanceId, connectionCount)
    │     → HSET external-ws:registry:{domain} {instanceId} {count}
    │     → EXPIRE external-ws:registry:{domain} 30
    │
    ├─ afterConnectionClosed(session)
    │  ├─ 从本地 connectionPool 移除（现有逻辑不变）
    │  └─ connectionCount > 0?
    │     ├─ Yes → ExternalWsRegistry.update(domain, instanceId, count)
    │     └─ No  → ExternalWsRegistry.unregister(domain, instanceId)
    │        → HDEL external-ws:registry:{domain} {instanceId}
    │
    └─ @Scheduled(fixedRate = 10_000) heartbeat()
       └─ 遍历本地 connectionPool 中的所有 domain
          → EXPIRE external-ws:registry:{domain} 30
          （SS 宕机后 30 秒自动过期，不会残留）
```

## 7. 迁移路径

1. **当前（过渡期）**：配置 `mode=rest`，所有非 miniapp 消息走 REST，行为不变
2. **迁移完成**：IM 平台全部切到 ExternalInbound + WS 后，配置改为 `mode=ws`
3. **降级保障**：ws 模式下若所有 SS 的 WS 全断，IM domain 自动降级到 REST
