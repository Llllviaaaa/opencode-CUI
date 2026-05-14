# fix: externalws L2 投递在 pod kill 后路由到已死实例

## Goal

修复 skill-server 多实例部署下，当某 pod 被 `kill -9` 后，其他 pod 的 outbound L2 投递有概率仍把消息 relay 到这个已死 pod 的 channel（无订阅者，消息静默丢失）。

根因不是"30s TTL 窗口残留"，而是 **Redis Hash 字段级 TTL 缺失 + 活实例 EXPIRE 跨实例续命**：死 pod 在共享 hash `external-ws:registry:{domain}` 上的字段被活实例的心跳无限期续命，TTL 永远不会过期。线上观测：kill 一天后死字段仍在；手动 DEL 后因为 `EXPIRE` 对不存在 key 是 no-op，无法重建。

## Background — 根因详解

代码证据链：

- `external-ws:registry:{domain}` 是 Redis Hash，TTL 设在整个 hash key 上（Redis <7.4 不支持字段级 TTL）。
- `RedisMessageBroker.registerWsConnection` 写字段 + `EXPIRE key`（`RedisMessageBroker.java:448-457`）。
- `ExternalStreamHandler.checkHeartbeatTimeouts` 周期 30s，对每个 source 调 `wsRegistry.heartbeat(source)` 即 `EXPIRE key 30s`（`ExternalStreamHandler.java:218-235`）。
- 任一活实例的 EXPIRE 续整个 hash → 死实例字段一起续命。
- `findInstanceWithConnection` 只判 `count > 0`，不判实例存活（`ExternalWsRegistry.java:52-65`）→ 选中死 pod → publish 到 `ss:external-relay:<deadPodA>`，无订阅者，消息丢失。

## Decision (ADR-lite)

**Context**：当前 `external-ws:registry:{domain}` 是**共享 hash**（多实例共写共续期），并且把它当**唯一路由真相源**用。这两点共同制造了"死字段永久残留 + 误投递"。

**Decision**：从架构上反转 —— **每个实例只写自己的 key，TTL 只能自己续**。投递时基于活实例花名册去查每个活实例的"我持有的 domain"卡片。删除共享 hash 结构。

**Consequences**：
- 死字段问题消除（无跨实例 TTL 污染）
- 误删 key 能自动重建（owner 心跳 `HSET` 会自动创建 hash key）
- 不需要 lazy GC / reconciler / 探活二次过滤等补丁
- 引入"活实例花名册" ZSET 作为枚举层（替代 SCAN，符合项目"禁用 SCAN/KEYS"硬规则 [[feedback-redis-no-scan-keys]]）
- 投递路径多一次 `ZRANGEBYSCORE`（亚毫秒）+ 一次 pipelined HGET，可接受

## Why other approaches were rejected

| 方案 | 拒绝原因 |
|---|---|
| 旧方案 A（registry + isInstanceAlive 过滤 + lazy HDEL + reconciler）| 双真相源；registry key 一旦丢失无法重建（EXPIRE 是 no-op on missing key）；补丁太多 |
| HEXPIRE（Redis 7.4 字段级 TTL）| 腾讯云/ValKey 未支持（valkey-io/valkey#1070 仍 open）；仍需 alive 校验 |
| 纯 fan-out 广播（Socket.IO 风格）| 同 domain 多 pod 持有时会重复推送；浪费 pub/sub 带宽 |
| 一致性哈希 / etcd lease / StatefulSet 直连 | 工程量过大，规模没到 |

研究详见 `research/industry-ws-presence-routing.md`、`research/redis-presence-patterns.md`、`research/sticky-routing-owner-election.md`。

## Requirements

### Redis 数据结构变更

| Key | 类型 | 写者 | 续命者 | TTL | 用途 |
|---|---|---|---|---|---|
| `ss:internal:instance:{id}` | string | owner 自己 | owner 自己 | 30s | **保留**：单点 alive 探活（`EXISTS`）|
| `instance:roster` | ZSET | 所有 owner（写自己）| 所有 owner（写自己）| 无 key 级 TTL，按 score 时间戳过期 | **新增**：枚举活实例花名册 |
| `external-ws:held-by:{id}` | hash | owner 自己 | owner 自己 | 30s | **新增**：每实例自管路由表 `{domain → connectionCount}` |
| ~~`external-ws:registry:{domain}`~~ | ~~hash~~ | — | — | — | **删除** |

### 数据流

**写路径（owner-only）：**

1. WS 连接 onConnect / onClose 时：`ExternalStreamHandler` 立即同步本实例的 `external-ws:held-by:{selfId}`（HSET / HDEL field）+ 续 TTL。
2. `ExternalStreamHandler.checkHeartbeatTimeouts` 定时任务（周期改为 10s，与 `SkillInstanceRegistry` 一致）：
   - 取本地 `connectionPool.snapshotCountsBySource()`
   - 若为空：`DEL external-ws:held-by:{selfId}`
   - 若非空：`HSET ... putAll(snapshot)` + `EXPIRE key 30s`
   - 一次 pipeline 或事务，避免多 RTT
3. `SkillInstanceRegistry.writeHeartbeat`（现有方法）追加：
   - `ZADD instance:roster {now-ms} {selfId}`
   - `ZREMRANGEBYSCORE instance:roster 0 {now-ms - 60000}`（lazy GC 60s 前的死实例）
4. `SkillInstanceRegistry.destroy`（现有 @PreDestroy）追加：`ZREM instance:roster {selfId}`。
5. `ExternalStreamHandler` 也需要 @PreDestroy 时 `DEL external-ws:held-by:{selfId}`（graceful 路径）。

**读路径（投递时）：**

```
deliver(domain, msg):
  L1: 本地 connectionPool 有 domain → pushToOne, 返回

  L2: findInstanceWithConnection(domain):
      aliveIds = ZRANGEBYSCORE instance:roster {now - 30000} +inf
      candidates = aliveIds.filter(id != selfId)
      pipelined HGET external-ws:held-by:{id} {domain} for each candidate
      return 第一个 count>0 的 id（或 null）

      命中 → PUBLISH ss:external-relay:{id} {domain, payload}, 返回
      未命中 → L3

  L3: IM 文本降级 / 非 IM 显式丢弃（行为不变）
```

### 改动文件清单

| 文件 | 改动 |
|---|---|
| `RedisMessageBroker.java` | 删 `registerWsConnection / unregisterWsConnection / getWsRegistry / expireWsRegistry`；新增 `heldByPutAll / heldByDeleteField / heldByDeleteKey / heldByGetBatch`（pipeline HGET 多实例）；新增 `addToInstanceRoster / removeFromInstanceRoster / rangeAliveInstances` |
| `SkillInstanceRegistry.java` | `writeHeartbeat` 增加 ZADD + lazy ZREMRANGEBYSCORE；`destroy` 增加 ZREM；新增 `listAliveInstances()` 返回 `List<String>`（基于 ZRANGEBYSCORE，cutoff = now - 30s） |
| `ExternalWsRegistry.java` | `register / unregister / heartbeat` 改写到 `held-by:{selfId}`；`findInstanceWithConnection` 改用 listAlive + pipelined HGET |
| `ExternalStreamHandler.java` | 定时任务周期 30s → 10s；定时 putAll snapshot + EXPIRE；新增 @PreDestroy 删 held-by key；onConnect/onClose 同步逻辑保留但落到新 key |
| `ExternalWsDeliveryStrategy.java` | 内部 L2 调用 `findInstanceWithConnection` 不变（只换底层实现） |
| `DeliveryProperties.java` | `registryTtlSeconds = 30s`（语义不变，但用于 held-by key TTL）；`registryHeartbeatIntervalMs` 改默认 10000 |
| `application.yml` | 同步默认值 |

### 边界与异常处理

- `listAliveInstances()` Redis 抛异常：返回空列表，调用方走 L3 降级（不退回旧逻辑，旧逻辑也没有更好的 fallback）
- `held-by:{id}` HGET 返回 null：候选淘汰
- `held-by:{id}` count = 0：候选淘汰（避免选到曾持有现已断开的实例）
- 自身 selfId 永远从候选中排除（与现状一致）
- pipeline HGET 任一报错：该候选跳过，不影响其余

## Acceptance Criteria

- [ ] kill -9 一个 pod 后 ≤ **30s** 内，其他 pod 的 L2 投递不再选中该死 pod（窗口 = held-by TTL）
- [ ] 手动 `DEL external-ws:held-by:{id}` 后，下一次心跳（≤ 10s）该 key 自动重建
- [ ] 手动 `DEL instance:roster` 后，下一次心跳（≤ 10s）花名册自动重建
- [ ] 活 pod 的 held-by 不会因为 GC pause / 网络抖动误过期（TTL ≥ 3× heartbeat）
- [ ] 同 domain 跨多 pod 持有时，仍只选其中一个 pod 投递（不重复推送）
- [ ] 现有所有 `ExternalWsRegistryTest` / `ExternalWsDeliveryStrategyTest` / `SkillInstanceRegistryTest` 单测调整后 green
- [ ] 新增单测：
  - listAliveInstances 正常 / 过期实例被过滤 / ZSET 不存在时返回空
  - held-by putAll / delete / get 正确性
  - findInstanceWithConnection 死实例（roster 没它）跳过、roster 有但 held-by 没该 domain 跳过、命中第一个
  - heartbeat 重建：先 DEL key 再触发心跳，断言 key 回来
  - graceful @PreDestroy：触发后 held-by key 不在
- [ ] 代码中**无任何 SCAN / KEYS 命令**

## Definition of Done

- 单元测试覆盖以上 AC，全部 green
- `mvn -pl skill-server verify` 通过
- 没有破坏现有 SS relay takeover / IM 投递路径的单测
- 手工 reproduce：本地起 2 实例 + Redis，建 WS 连接，kill 一个，验证 30s 内不再误投递
- 不需要新增 spec 文档（属 bug fix；如设计有重大遗留可考虑 follow-up spec）

## Out of Scope

- HEXPIRE（Redis 7.4 字段级 TTL）—— 厂商兼容性不确定
- L2 投递 ACK + 重试机制 —— 与本 bug 解耦，独立工程
- 同 domain 多 pod 持有时的去重策略 —— 业务语义"任选一推"维持现状
- 一致性哈希 / pod-direct 寻址等架构级改造
- 监控埋点完善（仅日志，不加 metrics）

## Technical Notes

### 关键约束

- **禁用 SCAN/KEYS** [[feedback-redis-no-scan-keys]]：枚举活实例必须靠维护好的 ZSET，不能扫 prefix
- Redis 是单实例（非 Cluster），Lettuce 客户端
- Spring Boot 2.x，pub/sub via `RedisMessageListenerContainer`
- 已有的 `SkillInstanceRegistry` 有完善的自愈机制（pub/sub silent failure 检测 + force reconnect），可以直接信任其 alive 信号

### 复用现有基建

- `ss:internal:instance:{id}` 心跳 key 保留 —— 用于单点 alive 校验场景（如未来其他模块）
- `ss:external-relay:{instanceId}` pub/sub 频道不变
- `SkillInstanceRegistry.heartbeat` 现有调度复用（追加 ZADD）

### 业界对照

调研三份文件均验证：

- 当前架构（registry + heartbeat 双源）与 Discord/Centrifugo **同形**，问题是"两个真相源只有一个有 TTL"
- "每实例自管 + 活实例花名册"模式是 Centrifugo / Phoenix Tracker / rushsocket 生产用法
- 在 ~10-50 pod 规模下，无需引入 etcd/StatefulSet 等重型基建

研究文件：
- `research/industry-ws-presence-routing.md`
- `research/redis-presence-patterns.md`
- `research/sticky-routing-owner-election.md`

### 演化路径（可选，非本任务范围）

- 如未来 pod 数破百、domain 数破万：把 `held-by:{id}` 改为 Sorted Set，按 last-touched-ms 评分
- 如运维确认 Redis 厂商支持 HEXPIRE：可叠加为防御性深度，但不替代本方案
