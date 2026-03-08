# 故障切换与恢复设计

> 版本：1.0  
> 日期：2026-03-07

## 1. 目标

本设计关注以下异常场景：

- `skill-server -> ALB -> gateway` 长连接断开
- owner gateway 失效
- relay 目标过期
- 本地 `sessionId -> linkId` 绑定失效
- ALB 空闲超时或健康检查误判

## 2. skill-server 长连接重连

### 2.1 重连方向

由 `skill-server` 主动执行重连：

```text
skill-server -> ALB -> gateway
```

### 2.2 重连参数

重连参数固定使用以下配置：

- `skill.gateway.reconnect-initial-delay-ms`
- `skill.gateway.reconnect-max-delay-ms`

退避策略为：

- 指数退避
- 从 `initial-delay` 开始，逐步放大，直到 `max-delay`

### 2.3 重连后的影响

一旦重连成功，新的 gateway 需要：

1. 将该 skill 连接注册为本地 skill link
2. 进入 owner 心跳刷新
3. 允许无 session 事件和未来新 session 的下行 invoke 通过该 link 进入

旧 gateway 上的本地 `sessionId -> linkId` 绑定在进程存活期间可能还存在，但会随着：

- link 断开
- owner TTL 过期
- session TTL 过期

逐步被淘汰。

## 3. Owner Gateway 失效

### 3.1 判定标准

owner gateway 被判定为失效，当且仅当：

- `gw:skill:owner:{instanceId}` TTL key 不存在

### 3.2 处理流程

某个 agent owner gateway 查到 `gw:session:skill-owner:{sessionId}=gw-a` 后，必须再次校验：

- `gw:skill:owner:gw-a` 是否存在

若不存在：

1. 视为 sticky owner 已失效
2. 删除或覆盖 `gw:session:skill-owner:{sessionId}`
3. 从 `gw:skill:owners` 重新筛选活跃实例
4. 重新选出 owner gateway
5. 写入新的 sticky owner
6. 发布到新的 `gw:relay:{instanceId}`

## 4. Relay Miss

### 4.1 场景定义

relay miss 指：

- gateway 将消息发布到 `gw:relay:{instanceId}`
- 但目标 owner gateway 实际没有成功处理

原因可能包括：

- Redis key 陈旧
- owner gateway 刚掉线
- owner gateway 已失去本地 skill link
- Pub/Sub 期间发生瞬时故障

### 4.2 当前方案的处理方式

当前方案不对 relay 增加确认链路，因此 relay miss 的处理策略是：

1. 记录错误日志和诊断信息
2. 等待后续事件再次进入 owner 选择逻辑
3. 由 skill 侧现有 sequence/reconnect 机制承担恢复边界

### 4.3 明确边界

本方案不承诺：

- relay 成功确认
- relay 自动重试
- relay 消息持久化

## 5. Session 重绑定

### 5.1 触发条件

需要重绑定 `sessionId -> linkId` 或 `sessionId -> owner gateway` 的场景包括：

- 本地 link 断开
- sticky owner 失效
- gateway 重启
- `session.status=idle`
- 会话显式关闭

### 5.2 重绑定顺序

1. 优先尝试本地 `sessionId -> linkId`
2. 本地失效后查 `gw:session:skill-owner:{sessionId}`
3. Redis sticky 失效后重新选 owner
4. 目标 owner gateway 收到 relay 后，如果本地不存在该 session 的 link 绑定，则由其默认 active link 承接，并建立新的本地绑定

## 6. ALB 要求

### 6.1 健康检查

ALB 健康检查必须走 HTTP/HTTPS 端点，而不是 WebSocket 握手本身。建议：

- 使用标准健康检查端点，例如 `/actuator/health`

### 6.2 Idle Timeout

由于内部通道是长连接，ALB idle timeout 必须大于内部链路的心跳或 ping 周期。建议：

- 如果内部链路按 30 秒维持心跳，则 ALB idle timeout 至少设置为 120 秒

### 6.3 影响说明

ALB 只负责：

- 建链时选择一个健康 target

ALB 不负责：

- 运行时迁移现有 WebSocket
- 让所有 gateway 自动都具备 skill 出口

因此 gateway 集群内 Redis relay 仍然是必要的。

## 7. 现有恢复能力的承接点

当前 skill 侧已有以下能力可承接恢复：

- `SequenceTracker`：检测序列号间隙
- `StreamBufferService`：缓存流式中间态
- `GatewayRelayService`：在 gap 出现时可继续扩展 recovery 动作

这意味着在 relay miss 或 owner 切换导致短暂丢消息时，系统仍可通过：

- 后续消息的 gap 检测
- 客户端 reconnect / resume

降低用户面可见损失。

## 8. 故障切换原则

### 8.1 steady-state

steady-state 下，同一 session 必须尽量满足：

- 相同 agent owner gateway
- 相同 sticky owner gateway
- 相同 local skill link

### 8.2 failover

failover 发生时，优先保证：

- 链路可继续工作

不保证：

- 零丢包
- session 在 skill 实例间切换时完全无扰动

### 8.3 文档结论

本方案的 failover 目标是：

- 快速恢复通路
- 尽量维持 session 级稳定
- 明确接受 Pub/Sub at-most-once 的语义限制
