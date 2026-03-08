# DECISIONS.md — Architecture Decision Records

> Project: OpenCode CUI

## ADR-001: WebSocket 双向长连接作为主通信通道

**Date:** 2026-03-07
**Status:** Accepted
**Context:** 需要在 PC Agent、Gateway、Skill Server、前端之间实现低延迟实时通信
**Decision:** 全链路使用 WebSocket 长连接，JSON 消息格式
**Consequence:** 需要处理断线重连、消息缓冲、多实例路由

## ADR-002: 内部长连接方向为 Skill Server → ALB → Gateway

**Date:** 2026-03-07
**Status:** Accepted (documented, not yet implemented)
**Context:** 多实例 Gateway 部署下需要 Skill Server 能连接到任意 Gateway
**Decision:** Skill Server 主动连接 ALB，ALB 分发到 Gateway 集群
**Consequence:** Gateway 需要 Redis Pub/Sub 做跨实例定向中继

## ADR-003: AK/SK HMAC-SHA256 签名认证

**Date:** 2026-03-07
**Status:** Accepted (implemented)
**Context:** PC Agent 需要安全地向 Gateway 证明身份
**Decision:** 采用 AK/SK + timestamp + nonce 的 HMAC-SHA256 签名方案
**Consequence:** 需要 Redis 存储 nonce 防重放攻击
