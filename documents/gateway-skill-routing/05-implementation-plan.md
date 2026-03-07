# 实施计划

> 版本：1.0  
> 日期：2026-03-07

## 总览

本改造建议拆为 5 个阶段，先补 gateway 侧接入与 relay 能力，再切换 skill 侧长连接方向，最后清理旧实现并做联调验证。

```text
Phase 1: Gateway 新增内部 skill 接入端点与 owner 状态
Phase 2: Skill Server 新增到 ALB 的长连接客户端
Phase 3: Gateway 上行 relay 与 session sticky 路由
Phase 4: 双向联调、故障切换、配置切换
Phase 5: 清理旧实现与文档收口
```

## Phase 1：Gateway 接入与 owner 状态

### 目标

让 gateway 可以作为内部服务端接收来自 `skill-server` 的长连接，并对外暴露 owner 注册能力。

### 主要改造

- 新增 gateway 内部 WebSocket 端点，例如 `/ws/internal/skill`
- 新增内部 token 校验逻辑
- 为 gateway 引入 `gateway.instance-id`
- 新增本地 skill link 管理器：
  - `linkId -> WebSocketSession`
  - 默认 active link 选择
- 新增 owner 心跳刷新：
  - `SETEX gw:skill:owner:{instanceId}`
  - `SADD gw:skill:owners`
- 订阅 `gw:relay:{instanceId}`

### 验证点

- `skill-server` 还未切换前，gateway 新端点能独立启动
- 本地 skill link 建立后，owner TTL 会持续刷新
- 本地最后一条 skill link 断开时，owner 状态会清理

## Phase 2：Skill Server 切换主动建链

### 目标

把当前 `gateway -> skill-server` 的主动连接改为 `skill-server -> ALB -> gateway`。

### 主要改造

- 在 `skill-server` 新增 `GatewayWSClient`
- 新增配置：
  - `skill.gateway.ws-url`
  - `skill.gateway.internal-token`
  - `skill.gateway.reconnect-initial-delay-ms`
  - `skill.gateway.reconnect-max-delay-ms`
- `GatewayRelayService.sendInvokeToGateway()` 改为优先使用新的 `GatewayWSClient`
- 将 `GatewayWSHandler` 从主路径移除

### 验证点

- `skill-server` 能通过单一 ALB 地址建立长连接
- invoke 可以从 `skill-server` 经 ALB 命中的 gateway 发送出去
- 旧 `gateway.skill-server.ws-url` 不再参与新路径

## Phase 3：Gateway 上行 relay 与 sticky 路由

### 目标

补齐 non-owner gateway 向 skill 集群回流上行事件的路径，并维持 session 连续性。

### 主要改造

- gateway 新增本地 `sessionId -> linkId` 绑定
- gateway 新增 Redis sticky owner 逻辑：
  - `gw:session:skill-owner:{sessionId}`
- 新增 owner 选择算法：
  - 从 `gw:skill:owners` 读取活跃 owner
  - 使用 Rendezvous Hashing 选择目标 owner
- non-owner gateway 在本地无绑定时：
  - `PUBLISH gw:relay:{instanceId}`
- owner gateway 收到 relay 后：
  - 优先使用本地绑定的 link
  - 不存在绑定时使用默认 active link 并建立新绑定

### 验证点

- 同一 `sessionId` 的多条上行事件会稳定回到同一条本地 skill link
- 某个 gateway 本地无 skill link 时，依旧能把事件送入 skill 集群
- `agent_online` / `agent_offline` 这类无 `sessionId` 事件能通过默认 active link 回流

## Phase 4：联调与配置切换

### 目标

在保持现网可控的前提下完成配置切换，并验证 ALB 与 Redis 约束。

### 迁移路径

推荐采用两阶段切换：

1. 先发布支持新 gateway 内部接入的版本
2. 再发布 `skill-server` 主动建链版本，并通过配置启用

### 部署要求

- skill 集群共享同一 `skill.gateway.ws-url`
- ALB 健康检查命中 gateway HTTP 端点
- ALB idle timeout 大于内部心跳周期
- Redis 中 owner TTL 与 session sticky TTL 配置完整

### 验证点

- 多个 `skill-server` 实例通过同一 ALB URL 正常建链
- 多个 `gateway` 实例中，只有持有 skill link 的实例注册为 owner
- 下行 invoke 与上行 relay 在双实例场景下闭环成功

## Phase 5：清理旧实现

### 目标

移除过时的反向长连接实现和不再使用的旧配置。

### 主要改造

- 删除或废弃：
  - `SkillServerWSClient`
  - `gateway.skill-server.ws-url`
  - `gateway.skill-server.internal-token`
  - `gateway.skill-server.reconnect-*`
- 从 `skill-server` 中移除旧的内部入站主路径：
  - `GatewayWSHandler`
  - `/ws/internal/gateway`
- 清理未闭环的旧 `invoke_relay` 主路径说明

### 验证点

- 代码中不再依赖 `gateway -> skill-server` 主动建链
- 运行时只保留新配置项和新长连接方向

## 代码改造顺序

推荐执行顺序：

```text
1. Gateway 内部 skill 端点 + owner 状态
2. Skill GatewayWSClient + 新配置
3. Gateway 上行 relay + session sticky 路由
4. 多实例联调与故障切换验证
5. 删除旧反向建链实现
```

## 验证清单

- 下行 invoke 仍通过 `agent:{agentId}` 命中 agent owner gateway
- 上行 agent 事件在本地无 skill link 时，经 `gw:relay:{instanceId}` 定向中继
- 同一 `sessionId` 命中稳定的 local skill link
- owner 失效后可重新选择并写回 `gw:session:skill-owner:{sessionId}`
- `GatewayMessage` 协议字段与当前实现保持兼容
