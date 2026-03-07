# Gateway-Skill Routing 文档包

> 版本：1.0  
> 日期：2026-03-07

本目录用于沉淀 `skill-server -> ALB -> gateway` 内部长连接改造的完整设计文档。目标是把当前 `gateway` 主动连接 `skill-server` 的实现，调整为由 `skill-server` 通过单一 ALB 地址建立到 `gateway` 集群的长连接，同时保留 `gateway` 集群内部基于 Redis Pub/Sub 的多实例定向中继能力。

## 锁定决策

- 内部长连接方向固定为：`skill-server -> ALB -> gateway`
- `gateway -> skill-server` 不改为纯 Redis 通道，仍以 WebSocket 长连接为主通道
- `gateway` 集群内部新增 Redis 定向 relay，补齐 non-owner gateway 向 skill 集群转发上行事件的路径
- 继续复用现有 `GatewayMessage` 作为 `gateway` 与 `skill-server` 之间的消息载体
- `skill-server` 共享一套 `skill.gateway.ws-url` 配置，指向单一 ALB 地址
- 同一 `sessionId` 的上行事件在 steady-state 下必须回到同一条本地 skill 长连接

## 范围

本目录只覆盖：

- 内部长连接方向调整
- ALB 接入与多实例 gateway 路由
- Redis owner 注册、心跳、sticky session-owner 映射
- 故障切换、恢复边界、测试与实施计划

本目录不覆盖：

- PC Agent 协议扩展
- Miniapp UI 改造
- MySQL 表结构设计变更
- 新的消息 DTO 体系

## 文档索引

| 文件 | 内容 | 状态 |
| --- | --- | --- |
| [01-current-routing-protocol.md](./01-current-routing-protocol.md) | 当前代码现状、消息流、缺口与问题 | 已完成 |
| [02-target-routing-protocol-design.md](./02-target-routing-protocol-design.md) | 目标架构、长连接建立、上下行路由、session 绑定 | 已完成 |
| [03-relay-state-design.md](./03-relay-state-design.md) | Redis channel/key、owner 状态、sticky 路由、语义边界 | 已完成 |
| [04-failover-design.md](./04-failover-design.md) | 长连接重连、owner 失效、relay miss、ALB 约束 | 已完成 |
| [05-implementation-plan.md](./05-implementation-plan.md) | 模块改造顺序、迁移路径、验证节点 | 已完成 |
| [06-requirements.md](./06-requirements.md) | 背景、目标、非目标、约束、验收标准 | 已完成 |
| [07-progress.md](./07-progress.md) | 当前进展、已确认决策、风险、下一步 | 已完成 |
| [08-test-cases.md](./08-test-cases.md) | 单测、集成、故障切换、部署与 UAT 用例 | 已完成 |
| [routing-sequence.puml](./routing-sequence.puml) | 标准时序图：长连接主通道 + gateway Redis 定向中继 | 已完成 |
| [routing-topology.puml](./routing-topology.puml) | 组件/部署图：ALB、gateway 集群、skill 集群、Redis、PCAgent、Miniapp | 已完成 |

## 术语

| 术语 | 定义 |
| --- | --- |
| Agent Owner Gateway | 持有某个 PCAgent WebSocket 连接的 gateway 实例 |
| Skill Owner Gateway | 本地至少持有一条来自 `skill-server` 长连接，且可向 skill 集群转发上行事件的 gateway 实例 |
| Local Skill Link | 某个 gateway 本地的一条 `skill-server -> gateway` WebSocket 连接 |
| Sticky Session Owner | 通过 Redis 记录的 `sessionId -> gateway instanceId` 映射，用于跨 gateway 集群稳定回流 |
| Local Session Binding | 某个 gateway 本地内存中的 `sessionId -> local skill link` 绑定，用于同一 session 回到相同的 skill 连接 |

## 新增配置契约

| 配置项 | 所属服务 | 说明 |
| --- | --- | --- |
| `skill.gateway.ws-url` | skill-server | 指向 ALB 的单一 WebSocket 地址 |
| `skill.gateway.internal-token` | skill-server | 与 gateway 内部通道的认证 token |
| `skill.gateway.reconnect-initial-delay-ms` | skill-server | 首次重连等待时间 |
| `skill.gateway.reconnect-max-delay-ms` | skill-server | 最大重连等待时间 |
| `gateway.instance-id` | ai-gateway | gateway 实例唯一标识 |
| `gateway.skill-relay.owner-heartbeat-interval-seconds` | ai-gateway | owner 心跳刷新周期 |
| `gateway.skill-relay.owner-ttl-seconds` | ai-gateway | owner 心跳 TTL |
| `gateway.skill-relay.session-route-ttl-seconds` | ai-gateway | `sessionId -> owner gateway` sticky 映射 TTL |

## Redis 契约

| 名称 | 类型 | 用途 |
| --- | --- | --- |
| `agent:{agentId}` | Pub/Sub Channel | skill 下行 invoke 命中 agent owner gateway |
| `session:{sessionId}` | Pub/Sub Channel | skill 集群内广播 StreamMessage 给前端订阅者 |
| `gw:relay:{instanceId}` | Pub/Sub Channel | non-owner gateway 向指定 owner gateway 定向中继上行事件 |
| `gw:skill:owner:{instanceId}` | TTL Key | 标记 gateway 当前为活跃 skill owner |
| `gw:session:skill-owner:{sessionId}` | TTL Key | 记录某个 session 当前绑定的 owner gateway |
| `gw:skill:owners` | Set | 活跃 skill owner gateway 实例集合 |

## 图示入口

- [routing-sequence.puml](./routing-sequence.puml)
- [routing-topology.puml](./routing-topology.puml)

## 参考草稿

- 原始草稿：[`../mult-instance-router.plantuml`](../mult-instance-router.plantuml)

该草稿保留在 `documents/` 根目录，不在本次文档整理中修改；本目录内的 PlantUML 文件是基于该草稿整理后的规范版本。
