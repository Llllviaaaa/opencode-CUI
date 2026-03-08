# 当前路由协议现状

> 版本：1.0  
> 日期：2026-03-07

## 1. 当前实现概览

当前代码中的服务间长连接方向是：

```text
gateway -> skill-server
```

也就是说，`ai-gateway` 在启动后主动连接 `skill-server` 的内部 WebSocket 端点；`skill-server` 作为服务端暴露内部接入入口，接收 gateway 的上行事件，并把下行 invoke 写回同一条连接。

## 2. 当前关键实现

### 2.1 ai-gateway

| 文件 | 当前职责 |
| --- | --- |
| `ai-gateway/src/main/java/com/opencode/cui/gateway/ws/SkillServerWSClient.java` | 主动连接 `skill-server` 的 `/ws/internal/gateway` |
| `ai-gateway/src/main/java/com/opencode/cui/gateway/service/EventRelayService.java` | PCAgent 与 skill-server 的双向中继中枢 |
| `ai-gateway/src/main/java/com/opencode/cui/gateway/service/RedisMessageBroker.java` | 按 `agent:{agentId}` 做 gateway 集群内 invoke 定向路由 |
| `ai-gateway/src/main/java/com/opencode/cui/gateway/ws/AgentWebSocketHandler.java` | 接收 PCAgent 事件并调用 `relayToSkillServer()` |

### 2.2 skill-server

| 文件 | 当前职责 |
| --- | --- |
| `skill-server/src/main/java/com/opencode/cui/skill/ws/GatewayWSHandler.java` | 暴露 `/ws/internal/gateway` 给 gateway 建链 |
| `skill-server/src/main/java/com/opencode/cui/skill/service/GatewayRelayService.java` | 接收 gateway 上行消息、向 gateway 下发 invoke、广播到 skill Redis |
| `skill-server/src/main/java/com/opencode/cui/skill/service/RedisMessageBroker.java` | 广播 `session:{sessionId}`，并保留 `invoke_relay:{agentId}` 雏形 |
| `skill-server/src/main/java/com/opencode/cui/skill/config/SkillConfig.java` | 注册内部 gateway WebSocket 端点 |

## 3. 当前配置契约

### 3.1 ai-gateway

当前 `ai-gateway` 侧配置集中在：

- `gateway.skill-server.ws-url`
- `gateway.skill-server.internal-token`
- `gateway.skill-server.reconnect-initial-delay-ms`
- `gateway.skill-server.reconnect-max-delay-ms`

它们的含义是：“gateway 应该去连接哪个 skill-server 地址”。

### 3.2 skill-server

当前 `skill-server` 侧只有：

- `skill.gateway.internal-token`
- `skill.gateway.api-base-url`

其中缺少一个“由 skill-server 主动连接 gateway”的单一入口配置。

## 4. 当前消息流

### 4.1 下行：skill -> gateway -> agent

```text
Miniapp -> SkillMessageController
        -> GatewayRelayService.sendInvokeToGateway()
        -> GatewayWSHandler.sendToGateway()
        -> gateway 收到 invoke
        -> PUBLISH agent:{agentId}
        -> agent owner gateway 命中
        -> PCAgent
```

下行路由已经具备 gateway 集群内的 Redis 定向能力，核心 channel 是：

```text
agent:{agentId}
```

### 4.2 上行：agent -> gateway -> skill

```text
PCAgent -> AgentWebSocketHandler
        -> EventRelayService.relayToSkillServer()
        -> SkillServerWSClient.sendToSkillServer()
        -> skill-server GatewayWSHandler
        -> GatewayRelayService.handleGatewayMessage()
        -> PUBLISH session:{sessionId}
        -> 持有前端订阅的 skill 实例
```

上行主路径依赖的是 gateway 本地持有的一条 `SkillServerWSClient` 长连接。

## 5. 当前 Redis 使用边界

### 5.1 ai-gateway

当前 `ai-gateway` 的 Redis 仅承担：

- `agent:{agentId}` 级别的定向路由

没有承担：

- `gateway -> gateway -> skill` 的二段中继
- owner gateway 注册与发现
- session 级 sticky owner 记录

### 5.2 skill-server

当前 `skill-server` 的 Redis 已承担：

- `session:{sessionId}` 广播
- `stream:{sessionId}:*` 缓冲

并保留了尚未闭环的：

- `invoke_relay:{agentId}` 设计雏形

## 6. 当前缺口

### 6.1 架构职责不一致

`gateway` 作为网关，却反向依赖主动连接 `skill-server`，从职责上更像“客户端”。这与网关作为统一接入点的角色不完全一致，也使基于 ALB 的 skill 侧单地址接入不自然。

### 6.2 ALB 单地址接入无法自然落地

如果 `skill-server` 共享同一套配置，且只希望配置一个 ALB 地址，那么当前方向下仍需要 gateway 知道 skill-server 的具体目标地址，无法把“服务发现入口”统一收敛到 `skill-server` 侧。

### 6.3 上行路径缺少 gateway 集群内补跳

当前上行路径假设：

- 接收 agent 事件的 gateway 本地一定持有到 skill-server 的长连接

这个假设在 `skill-server -> ALB -> gateway` 目标拓扑下不再成立。届时：

- 某个 gateway 可能只持有 agent 连接，不持有任何 skill 长连接
- agent 上行事件必须有一条 `gateway -> Redis -> gateway -> skill` 的补跳路径

### 6.4 本地 session 连续性没有被显式建模

`skill-server` 当前存在多处本地内存态：

- `SequenceTracker`
- `OpenCodeEventTranslator`
- `MessagePersistenceService`

这意味着同一 `sessionId` 的上行事件不能随意在多个 skill 实例间漂移，否则会破坏：

- part 顺序
- message context
- gap tracking

当前代码未显式定义：

- `sessionId -> local skill link`
- `sessionId -> owner gateway`

## 7. 本文档对应的改造目标

后续文档将把目标实现调整为：

- `skill-server` 通过单一 ALB 地址建立到 gateway 集群的长连接
- `gateway` 集群内部新增 owner 注册、sticky session-owner 和定向 relay
- 上行事件优先命中本地 `sessionId -> local skill link` 绑定
- 本地无绑定时，通过 Redis 找到目标 owner gateway，再 `PUBLISH` 到 `gw:relay:{instanceId}`
