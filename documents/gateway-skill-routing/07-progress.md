# 进度记录

> 状态更新时间：2026-03-07

## 当前状态

本目录文档已完成，代码实现尚未开始。

```text
文档状态：已完成
代码状态：未改造
联调状态：未开始
```

## 已确认决策

- 内部长连接方向调整为 `skill-server -> ALB -> gateway`
- `gateway -> skill-server` 仍以长连接为主通道，不改成纯 Redis
- gateway 集群内部通过 Redis Pub/Sub 做定向 relay
- relay channel 固定为 `gw:relay:{instanceId}`
- owner 心跳固定使用：
  - `gw:skill:owner:{instanceId}`
  - `gw:skill:owners`
- session sticky owner 固定使用：
  - `gw:session:skill-owner:{sessionId}`
- 同一 `sessionId` 优先命中本地 `sessionId -> local skill link` 绑定
- owner 选择算法固定为 Rendezvous Hashing
- 不新增新的服务间 DTO，继续复用 `GatewayMessage`

## 已识别风险

### 1. Redis Pub/Sub 语义限制

- relay 为 at-most-once
- owner gateway 瞬时掉线时，可能丢一条中继消息

### 2. skill-server 本地内存态

以下组件仍依赖本地内存态：

- `SequenceTracker`
- `OpenCodeEventTranslator`
- `MessagePersistenceService`

这要求实现过程中必须优先保证 session 级 sticky 路由。

### 3. ALB 行为约束

- ALB 只在建链时做目标选择
- 现有 WebSocket 建立后不会被 ALB 动态迁移

因此 gateway 内部 Redis relay 是必需能力，不是可选优化。

## 待实施项

- gateway 内部 skill WebSocket 接入端点
- gateway owner 注册、TTL 心跳、relay 订阅
- skill-server 新的 WebSocket 客户端与 ALB 单 URL 配置
- gateway 本地 `sessionId -> local skill link` 绑定
- gateway `gw:session:skill-owner:{sessionId}` sticky 映射
- owner 失效后的自动重选
- 旧 `gateway -> skill-server` 主动建链实现清理

## 建议下一步

1. 先在 `ai-gateway` 中补齐内部 skill 接入端点与 owner 状态管理
2. 再在 `skill-server` 中实现到 ALB 的主动建链客户端
3. 最后补 gateway 上行 relay、故障切换和联调测试

## 备注

当前仓库没有 `.gsd/ROADMAP.md`，因此本轮仅补齐了文档包，没有将该需求写入 GSD roadmap。
