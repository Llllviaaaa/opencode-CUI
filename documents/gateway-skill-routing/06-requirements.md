# 需求文档

> 版本：1.0  
> 日期：2026-03-07

## 1. 背景

当前系统的内部服务间长连接方向是 `gateway -> skill-server`。这一方向在单实例下可以工作，但在以下条件叠加时会带来明显问题：

- gateway 是多实例部署
- skill-server 是多实例部署
- skill-server 共用同一套配置
- gateway 对 skill-server 的入口希望统一收敛为 ALB 单地址

在这样的约束下，单纯保持现状会使：

- 网关职责不够清晰
- skill-server 难以只配置一个统一入口
- agent 上行事件在 non-owner gateway 上缺少补跳路径

## 2. 目标

### 2.1 核心目标

建立一套明确的目标架构，使内部路由满足：

1. `skill-server` 通过单一 ALB URL 建立到 gateway 集群的长连接
2. `gateway -> skill-server` 仍以长连接为主通道
3. `gateway` 集群内部通过 Redis Pub/Sub 为 non-owner gateway 提供上行补跳
4. 同一 `sessionId` 的上行事件在 steady-state 下稳定回到同一 skill 实例

### 2.2 具体需求

| 编号 | 需求 | 优先级 |
| --- | --- | --- |
| R1 | `skill-server` 支持 `skill.gateway.ws-url` 单一入口配置 | P0 |
| R2 | gateway 支持内部 skill 接入端点与内部 token 鉴权 | P0 |
| R3 | gateway 支持 owner 注册、TTL 心跳、活跃 owner 集合 | P0 |
| R4 | gateway 支持 `gw:relay:{instanceId}` 定向中继上行事件 | P0 |
| R5 | gateway 支持 `gw:session:skill-owner:{sessionId}` sticky owner 映射 | P0 |
| R6 | gateway 支持本地 `sessionId -> local skill link` 绑定 | P0 |
| R7 | 下行 invoke 继续复用 `agent:{agentId}` 路由 | P0 |
| R8 | 继续复用 `GatewayMessage`，不新增服务间 DTO | P0 |
| R9 | owner 失效后可以自动重选，并恢复上行事件回流 | P1 |
| R10 | 文档和测试用例完整覆盖 ALB、多实例、Redis Pub/Sub 语义边界 | P1 |

## 3. 非目标

本需求不包括：

- 把 `gateway <-> skill-server` 改造成纯 Redis 通道
- 引入 Nacos、Consul、Eureka 等服务发现
- 引入 Kafka、RabbitMQ、NATS 等新消息中间件
- 改造 Miniapp 渲染协议
- 改造 MySQL 持久化模型

## 4. 约束条件

### 4.1 基础设施

- MySQL 版本：5.7.x
- Redis 版本：5.x
- gateway 对 skill-server 的统一暴露方式：ELB + ALB
- skill-server 共享同一套配置

### 4.2 协议与实现

- 当前下行 `agent:{agentId}` Redis 路由必须保留
- 当前 skill 侧 `session:{sessionId}` 广播必须保留
- 继续使用 `GatewayMessage`
- 不新增新的服务间协议 DTO

### 4.3 语义边界

- 接受 Redis Pub/Sub 的 at-most-once 语义
- 不要求 relay 层具备确认、重试、持久化能力
- 需要用 sticky 路由尽量降低 skill 侧本地内存态漂移

## 5. 关键设计约束

### 5.1 配置约束

文档中必须固定以下新增/变更配置：

- `skill.gateway.ws-url`
- `skill.gateway.internal-token`
- `skill.gateway.reconnect-initial-delay-ms`
- `skill.gateway.reconnect-max-delay-ms`
- `gateway.instance-id`
- `gateway.skill-relay.owner-heartbeat-interval-seconds`
- `gateway.skill-relay.owner-ttl-seconds`
- `gateway.skill-relay.session-route-ttl-seconds`

### 5.2 Redis 契约约束

文档中必须固定以下 Redis 契约：

- 保留 `agent:{agentId}`
- 保留 `session:{sessionId}`
- 新增 `gw:relay:{instanceId}`
- 新增 `gw:skill:owner:{instanceId}`
- 新增 `gw:session:skill-owner:{sessionId}`
- 新增 `gw:skill:owners`

### 5.3 路由约束

下行固定为：

```text
skill -> connected gateway -> agent:{agentId} -> agent owner gateway -> PCAgent
```

上行固定为：

```text
优先本地 sessionId -> local skill link
否则查 gw:session:skill-owner:{sessionId}
失效后从 gw:skill:owners 选活跃 owner
写回 sticky 映射
PUBLISH gw:relay:{instanceId}
```

## 6. 验收标准

### 6.1 必须满足

- AC-1：所有 `skill-server` 实例仅配置一个 `skill.gateway.ws-url`
- AC-2：`skill-server` 能通过 ALB 与任意健康 gateway 建立长连接
- AC-3：任意 `skill-server` 发起的 invoke 都能通过 `agent:{agentId}` 命中 agent owner gateway
- AC-4：agent 上行事件在接入 non-owner gateway 时，仍能通过 `gw:relay:{instanceId}` 回流到 skill 集群
- AC-5：同一 `sessionId` 的连续上行事件在 steady-state 下回到同一条本地 skill link
- AC-6：owner gateway 失效后，sticky owner 能被重新选择
- AC-7：文档中所有配置名、channel 名、key 名、消息方向完全一致

### 6.2 明确接受的限制

- AC-8：Redis Pub/Sub relay 仍是 at-most-once，不保证零丢包
- AC-9：failover 期间 skill 实例切换可能导致少量流式上下文扰动

## 7. 受影响模块

- `ai-gateway`
- `skill-server`
- `documents/`

不直接改造：

- `src/main/pc-agent`
- `skill-miniapp`
- `test-simulator`
