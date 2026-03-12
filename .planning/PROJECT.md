# opencode-CUI

## What This Is

`opencode-CUI` 是一个 brownfield 的多组件 AI 会话系统，包含 `ai-gateway`、`skill-server`、`skill-miniapp` 和相关 agent 侧能力。它负责把不同上游服务发起的会话请求路由到 OpenCode，并把流式返回稳定、准确地回传给对应来源服务。

## Core Value

无论 `gateway` 对接多少上游服务，每一条请求和每一段流式返回都必须只属于它真实的来源服务与会话，不允许串流、错投或 session 标识冲突。

## Current Milestone: v1.2 Gateway Multi-Service Isolation

**Goal:** 让 `gateway` 在同时对接 `skill-server` 与其他服务时，既能稳定隔离消息来源、正确回流响应，又能通过统一 Snowflake ID 基础设施保证全局唯一标识。  
**Target features:**
- 区分 `skill-server` 与其他接入服务的来源身份，并在全链路透传
- 保证来自某个服务的请求只会收到属于该服务的 OpenCode 返回
- 设计并落地不会与其他服务冲突的 Snowflake session / entity ID 生成方案

## Requirements

### Validated

- [x] `gateway` 已能在 `skill-server` 链路上补齐并校验 `userId` 上下文
- [x] `skill-server` 实时广播已切换到 `user-stream:{userId}`，解决旧的消费归属错误
- [x] v1.1 已验证多实例场景下，真实连接归属优先于 REST 命中实例
- [x] `skill-server` 与 `ai-gateway` 已引入统一 Snowflake bit layout、epoch 和 rollback policy
- [x] `skill_session`、`skill_message`、`skill_message_part`、`agent_connection`、`ak_sk_credential` 已切换为应用侧生成 `Long` 主键
- [x] MyBatis insert 已显式写入 `id`，不再依赖 `useGeneratedKeys`

### Active

- [ ] 在真实测试数据库上执行清库重建并完成人工联调验证
- [ ] 对 v1.2 做最终 milestone audit，确认是否存在 gap closure

### Out of Scope

- 重写 `skill-server` 现有实时广播模型
- 引入独立的全局发号服务
- 对外开放通用第三方接入平台

## Context

- v1.1 已完成 `gateway` 到 `skill-server` 的连接归属修复，并验证真实环境下的实时回流正确性
- v1.2 已完成来源服务隔离与统一 Snowflake ID 基础设施治理
- 当前剩余工作主要是测试环境实库重建验证与 milestone 收口

## Constraints

- **Compatibility**: 不能破坏现有 `skill-server` 链路
- **Protocol**: 优先在现有 `GatewayMessage` / gateway routing 模型上演进
- **Reliability**: 多服务并发时必须避免消息错投
- **Traceability**: session id 方案需要可观测、可排障

## Key Decisions

| Decision | Rationale | Outcome |
|----------|-----------|---------|
| 将“来源服务”提升为显式路由维度 | 支撑多服务隔离 | Completed |
| 采用统一 Snowflake 基础设施而非 DB 自增 | 从标识层规避跨服务冲突 | Completed |
| 当前 phase 不引入共享 module，而是双服务复制同一实现 | 保持 phase 8 范围可控 | Completed |

---
*Last updated: 2026-03-12 after Phase 8 automated verification*
