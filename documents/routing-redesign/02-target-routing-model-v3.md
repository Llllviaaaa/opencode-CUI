# Target Routing Model v3

## Design Principles

Phase 1 的目标不是给历史实现找更多兜底，而是把 Gateway/Source 路由重新整理成一套更简单、可解释、能匹配现网拓扑的正式模型。目标模型遵循以下原则：

- 新版 `Source` 只连接统一的 `ALB` WebSocket 入口，不再把跨集群实例直连当作正式前提。
- `GW 共享 Redis` 是唯一的 GW 内部共享路由真源，本地内存只做缓存和加速。
- 业务路由与传输路由分离：会话归属决定“应该回到哪个 `sourceInstance`”，连接归属决定“应该落到哪条 WebSocket 连接”。
- 显式绑定优先于被动学习：新版协议通过控制消息明确声明路由关系，而不是继续依赖 `route_confirm` 的历史学习。
- legacy 兼容路径保留，但不再把兼容策略误当成正式主路径。

## Current vs Target

| 维度 | Current | Target |
| --- | --- | --- |
| Source 接入 | 默认保留 `mesh/full-connection` 心智，`GatewayWSClient` 假定能发现多个 `GW` 实例 | 新版 `Source` 仅连接统一 `ALB` 入口，内部连接是否落到哪个 `GW` 由负载均衡决定 |
| GW 路由学习 | 大量依赖被动学习、`route_confirm` 和 fallback 广播 | 路由由协议显式绑定，学习式逻辑只保留在 legacy 路径 |
| 路由真源 | 本地内存、`GW 共享 Redis`、`SS Redis`、repository/DB 反查并存 | `GW 共享 Redis` 是唯一共享真源，本地状态只是缓存 |
| owner 粒度 | 更接近“实例级 owner”或“会话级 owner”混合语义 | 业务路由认 `sourceInstance`，传输路由认连接级 owner |
| 吞吐扩展 | 单条连接和广播兜底混合，难以明确容量边界 | 通过连接池提供并发承载，通过路由索引保证回路由正确性 |

## GW Routing Source Of Truth

目标模型明确规定：`GW 共享 Redis` 是唯一的 GW 内部共享路由真源。任何 GW 只要拿到一条上行或下行消息，都应该先查这一份共享索引，而不是依赖别的组件去“猜”应该投递到哪里。

这条原则直接排除了两种做法：

- 不再把 `SS Redis` 当作 GW 内部回路由真源；它可以继续服务 legacy/现有 `skill-server` 逻辑，但不负责定义 GW 侧正式模型。
- 不再把 repository/DB 反查当作热路径的正式组成部分；历史数据可以作为兼容信息源，但不是主设计的一部分。

在实现上，`GW` 可以继续保留本地缓存来降低 Redis 查询成本，但缓存失效后的补救动作应该是“回到 Redis 重新查询”，而不是“继续广播试探并期待学习成功”。

## Routing Index Model

目标模型把共享索引拆成四类，分别回答不同问题：

- `toolSessionId -> sourceInstance`
- `welinkSessionId -> sourceInstance`
- `sourceInstance -> activeConnectionSet`
- `connectionId -> owningGw`

这四个索引有明确分工：

- 前两个索引属于业务回路由层，回答“当前会话应该回到哪个 `sourceInstance`”。
- `sourceInstance -> activeConnectionSet` 回答“这个实例当前有哪些可用连接”。
- `connectionId -> owningGw` 回答“某条具体连接当前挂在哪个 `GW` 实例上”。

这样的设计比 `sourceInstance -> owningGw` 的单层抽象更准确，因为新版 `Source` 会使用小连接池承载吞吐，连接池里的每一条连接都可能落到不同的 `GW`。如果仍然只保留实例级单 owner，就无法同时满足多连接并发和精确回路由。

## Connection-Level Ownership

目标模型把 owner 的语义明确收缩到“连接级”，而不是“实例级单 owner”。

这意味着：

- `sourceInstance` 是业务层的归属单位，用来表达“哪个 Source 实例拥有某个会话”。
- `connectionId` 是传输层的归属单位，用来表达“具体通过哪条长连接回发消息”。
- `owningGw` 表示这条连接当前挂在哪个 `GW` 上；如果当前处理消息的 `GW` 不是 `owningGw`，就需要做一次内部 relay。

这样分层后，目标路径就变成：

1. 先根据 `toolSessionId` 或 `welinkSessionId` 找到 `sourceInstance`。
2. 再从 `sourceInstance -> activeConnectionSet` 找出可用连接。
3. 通过连接选择策略选中一条 `connectionId`。
4. 再由 `connectionId -> owningGw` 判断是否需要跨 GW relay。

这条路径既保留了会话级稳定归属，又为连接池并发和负载分散留出了空间。
