# opencode-CUI

## What This Is

`opencode-CUI` 是一个围绕 Agent 协作场景构建的 brownfield 分布式系统，包含 `ai-gateway`、`skill-server`、`skill-miniapp` 和本地插件桥接能力。当前项目的核心工作不是整体重写系统，而是围绕 Gateway/Source 路由重构建立一套可以持续演进的设计、规划和后续实现基线。

## Core Value

在双集群、统一 ALB 入口、legacy 服务并存的生产约束下，Gateway 与 Source 之间的路由模型必须简单、可解释、可扩展，并且能稳定把消息送到正确的 Agent 与 Source 实例。

## Requirements

### Validated

- [x] Gateway 已具备 Agent 与 Source 的 WebSocket 接入、鉴权和基础消息中继能力 - existing
- [x] Skill Server 已具备会话、消息持久化、前端流式推送和对 Gateway 的下行调用能力 - existing
- [x] 仓库内已有路由重构资料和部分实现，可作为 GSD 初始化的直接背景 - existing
- [x] 团队已经可以用统一文档解释当前 `Source -> GW -> Agent` 与 `Agent -> GW -> Source` 的真实链路 - Phase 1
- [x] 新版 Source 仅连接统一 ALB 入口、GW 内部负责共享索引和跨 GW relay 的目标模型已经定稿 - Phase 1
- [x] `Source Protocol v3`、legacy 兼容边界、失败恢复场景与设计验收口径已经定稿 - Phase 1

### Active

- [ ] 在 `ai-gateway` 与 `skill-server` 中实现 `Source Protocol v3`、共享路由索引和连接池模型
- [ ] 为 legacy/new mixed mode 增加自动化测试、灰度策略和回归验证
- [ ] 针对连接 draining、观测性和 `60 万用户 / 120 万 agent` 的容量目标补齐实现与压测 phase

### Out of Scope

- 直接切换生产路由到 v3 - Phase 1 只做设计定稿，不混入代码改造
- 改为 MQ-only 或纯 HTTP 回调模型 - 当前正式方案继续以 WebSocket 作为核心通道
- 要求旧版 SS 和三方 Source 立即同步升级协议 - 第一阶段只要求兼容可用

## Context

- 仓库是典型 brownfield，多模块结构包括 `ai-gateway/`、`skill-server/`、`skill-miniapp/`、`plugins/message-bridge/`
- 现网背景是 SS 和 GW 双集群部署；每个集群各有 8 个实例，集群内可用服务 `IP:port` 互通，但跨集群不能依赖实例级长连接
- 集群外部统一通过 `server -> ingress -> ALB -> domain` 暴露访问入口，因此新版 Source 的正式接入模型必须服从统一 ALB 入口
- `SS` 与 `GW` 不共享 Redis，但 `GW` 集群内部可以共享 Redis 做连接注册、路由查询和 relay 协调
- Phase 1 已产出正式设计文档：
  - `documents/routing-redesign/01-current-routing-reality.md`
  - `documents/routing-redesign/02-target-routing-model-v3.md`
  - `documents/routing-redesign/03-legacy-compatibility-and-acceptance.md`
  - `documents/routing-redesign/README.md`

## Constraints

- **Topology**: 目标模型必须适配双集群、统一 ALB 入口、跨集群不可实例级直连的生产环境
- **State Sharing**: `SS` 与 `GW` 不共享 Redis；`GW` 集群内部允许共享 Redis
- **Compatibility**: 旧版 SS 和三方 Source 第一阶段必须继续可用
- **Transport**: 正式设计继续以 WebSocket 为核心链路，不转成 MQ-only 或纯 HTTP 回调
- **Scale Target**: 后续实现和压测拆分需要能承接 `60 万用户 / 120 万 agent` 的目标

## Key Decisions

| Decision | Rationale | Outcome |
|----------|-----------|---------|
| 以整仓方式初始化 GSD，而不是把项目缩成一次局部讨论 | 后续还会继续推进实现、压测和 rollout phase | Completed in Phase 1 |
| 当前 milestone 聚焦 Gateway/Source 路由重构 | 这是当前最关键、跨模块最强的主线问题 | Completed in Phase 1 |
| 首个 phase 只做路由方案定稿，不混入实现 | 先把目标模型、兼容策略和验收口径钉死，后续实现才不会反复返工 | Completed in Phase 1 |
| `GW 共享 Redis` 作为唯一的 GW 内部共享路由真源 | 解决“多层状态并存导致真源不唯一”的问题 | Completed in Phase 1 |
| 新版 `Source Protocol v3` 使用显式 `route_bind` / `route_unbind` / `protocol_error` | 用显式绑定替代被动学习，`route_confirm` / `route_reject` 仅留给 legacy | Completed in Phase 1 |
| 新版 Source 只连统一 ALB 入口，并使用默认 4 条、可扩到 8 条的小连接池 | 同时满足现网入口约束和高并发回复下的吞吐需求 | Completed in Phase 1 |
| owner 语义调整为连接级 owner，而不是实例级单 owner | 兼顾会话归属稳定性和连接池并发扩展 | Completed in Phase 1 |

## Evolution

This document evolves at phase transitions and milestone boundaries.

**After each phase transition**:
1. Requirements validated? -> move to Validated with phase reference
2. New implementation work emerged? -> add to Active
3. Decisions finalized? -> record them in Key Decisions
4. Context changed? -> update design artifacts and deployment assumptions

**After each milestone**:
1. Review Core Value and current milestone fit
2. Audit Active requirements against delivered artifacts
3. Add or remove future phases based on validated design and rollout needs

---
*Last updated: 2026-03-30 after Phase 1 completion*
