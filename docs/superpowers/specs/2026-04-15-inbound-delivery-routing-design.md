# 配置开关 + invoke-source 标记 + 精确投递的出站路由设计

> 日期：2026-04-15
> 状态：Draft

## 1. 问题描述

### 1.1 投递模式问题

当前 `OutboundDeliveryDispatcher` 的投递策略是 first-match 模式，`hasActiveConnections()` 是 domain 级别检查。当 IM 平台迁移到 ExternalInbound + WS 后，只要有一个 External 客户端为 "im" domain 建立了 WS 连接，ImInbound 的消息就被 WS 拦截，无法走 REST 投递。

### 1.2 广播浪费问题

当前 `ExternalWsDeliveryStrategy` 通过 Redis pub/sub 广播到所有 SS，上游同一 Pod 连了多台 SS 时会收到重复消息。

### 背景

- **ImInbound** 是过渡接口（IM 以前不支持流式）
- **ExternalInbound** 是通用接口（IM 已支持流式，正在迁移）
- 过渡期内同一个 session 可能从不同入口进来，但不会并发
- SS 分布式部署，上游每个 Pod 可能在多台 SS 上建立多条 WS 连接

## 2. 设计方案

### 2.1 配置开关

```yaml
skill:
  delivery:
    mode: rest   # rest | ws
    invoke-source-ttl-seconds: 300    # invoke-source Redis 标记 TTL
    registry-ttl-seconds: 30          # WS 连接注册表 TTL
    registry-heartbeat-interval-ms: 10000  # 注册表心跳刷新间隔
```

- `rest`：非 miniapp 域全部走 ImRest（过渡期）
- `ws`：通过 invoke-source 标记决定 REST 还是 WS，WS 走精确投递

### 2.2 invoke-source Redis 标记（mode=ws 时生效）

在 `InboundProcessingService.processChat()` 内部，session 确定后写入标记。`processQuestionReply()` 和 `processPermissionReply()` 同理。

| 入口 | 传入 inboundSource | 写入 |
|------|-------------------|------|
| `ImInboundController` | `"IM"` | `SET invoke-source:{welinkSessionId} IM EX {invoke-source-ttl-seconds}` |
| `ExternalInboundController` | `"EXTERNAL"` | `SET invoke-source:{welinkSessionId} EXTERNAL EX {invoke-source-ttl-seconds}` |

- TTL 由 `skill.delivery.invoke-source-ttl-seconds` 配置（默认 300 秒），Dispatcher 每次读取时续期
- 同一 session 不会被并发使用，无竞争

### 2.3 WS 连接注册表（ExternalWsRegistry）

每个 SS 实例向 Redis 注册持有的 WS 连接信息：

```
Key:    external-ws:registry:{domain}   (Hash)
Field:  {ss-instance-id}
Value:  {connectionCount}
TTL:    由 skill.delivery.registry-ttl-seconds 配置（默认 30 秒）
```

注册时机：
- `afterConnectionEstablished()`：注册/更新
- `afterConnectionClosed()`：更新，连接数为 0 时 HDEL
- 定时心跳（间隔由 `skill.delivery.registry-heartbeat-interval-ms` 配置，默认 10 秒）：续期 TTL

### 2.4 三层 WS 精确投递

当 invoke-source 为 EXTERNAL 时：

```
L1 本地：本 SS 有该 domain 的 WS 连接？
  └─ Yes → pushToOne()（选一条活跃连接直推）→ 完成

L2 Relay：查 external-ws:registry:{domain}，有其他 SS？
  └─ Yes → PUBLISH ss:external-relay:{targetInstanceId} → 目标 SS pushToOne() → 完成

L3 降级：无任何 SS 有连接
  ├─ isImDomain() → ImRest 降级
  └─ 非 IM domain → 警告日志 + 丢弃
```

## 3. 投递决策流程

```
StreamMessage 到达 OutboundDeliveryDispatcher.deliver(session, sessionId, userId, msg)
    │
    ├─ session.isMiniappDomain()? → Yes: MiniappDeliveryStrategy → return
    │
    ├─ mode == "rest"
    │  └─ session.isImDomain()? → Yes: ImRest → return
    │                            → No: 警告 + 丢弃 → return
    │
    └─ mode == "ws"
       ├─ 读取 invoke-source:{welinkSessionId}
       │
       ├─ value == "IM" → ImRest → return
       │
       ├─ value == "EXTERNAL"
       │  ├─ L1 本地有连接 → pushToOne() → return
       │  ├─ L2 Registry 有其他 SS → relay → return
       │  └─ L3 无连接 → isImDomain()? ImRest 降级 : 丢弃 → return
       │
       └─ value 不存在 → 走当前 first-match 兜底
```

## 4. 改动范围

### 4.1 新增

| 文件 | 说明 |
|------|------|
| `ExternalWsRegistry` | WS 连接注册表：注册/注销/查询/心跳续期 |

### 4.2 修改

| 文件 | 改动 |
|------|------|
| `InboundProcessingService` | `processChat()`、`processQuestionReply()`、`processPermissionReply()` 新增 `inboundSource` 参数，session 确定后写 Redis 标记 |
| `ImInboundController` | 调用时传入 `"IM"` |
| `ExternalInboundController` | 调用时传入 `"EXTERNAL"` |
| `OutboundDeliveryDispatcher` | 注入 mode 配置，按开关 + invoke-source 选择投递路径 |
| `ExternalWsDeliveryStrategy` | 改为三层精确投递（L1 → L2 → L3），去掉 pub/sub 广播 |
| `ImRestDeliveryStrategy` | `supports()` 去掉 `hasActiveConnections` 检查，去掉 `ExternalStreamHandler` 依赖 |
| `ExternalStreamHandler` | 连接建立/断开时调用 Registry 注册/注销；新增 `pushToOne()` 方法；启动时订阅自身 relay channel |
| `RedisMessageBroker` | 新增 invoke-source 读写方法、Registry Hash 操作方法、relay channel 方法 |
| `application.yml` | 新增 `skill.delivery.mode: rest` |

### 4.3 不改动

| 文件 | 原因 |
|------|------|
| `SkillSession` | 不改数据模型 |
| `MiniappDeliveryStrategy` | 不受影响 |
| `GatewayMessageRouter` | 路由逻辑不变 |

## 5. 场景验证

| # | 场景 | mode | invoke-source | 投递 | 结果 |
|---|------|------|--------------|------|------|
| 1 | 过渡期，任何消息 | rest | - | REST | 统一 REST |
| 2 | ws 模式，ImInbound 发消息 | ws | IM | REST | IM 用户收到回复 |
| 3 | ws 模式，ExternalInbound，本 SS 有连接 | ws | EXTERNAL | L1 直推 | 推一条，零网络开销 |
| 4 | ws 模式，ExternalInbound，本 SS 无连接 | ws | EXTERNAL | L2 relay | 跨 SS 推一条 |
| 5 | ws 模式，ExternalInbound，全无连接，IM domain | ws | EXTERNAL | L3 ImRest 降级 | 消息不丢 |
| 6 | ws 模式，ExternalInbound，全无连接，非 IM | ws | EXTERNAL | L3 丢弃 | 警告日志 |
| 7 | ws 模式，无标记（历史 session） | ws | (无) | first-match 兜底 | 向后兼容 |
| 8 | MiniApp 场景 | 任意 | - | MiniApp | 不受影响 |

## 6. 迁移路径

1. **过渡期**：`mode=rest`，行为不变
2. **迁移完成**：切到 `mode=ws`
3. **降级保障**：WS 全断时 IM domain 自动降级 REST
