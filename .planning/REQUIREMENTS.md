# Requirements: opencode-CUI

**Defined:** 2026-03-12
**Core Value:** 无论 `gateway` 对接多少上游服务，每一条请求和每一段流式返回都必须只属于它真实的来源服务与会话，不允许串流、错投或 session 标识冲突。

## v1 Requirements

### Routing Isolation

- [ ] **ROUT-01**: `gateway` 可以识别请求来源服务类型，并在内部消息上下文中透传该来源标识
- [ ] **ROUT-02**: `gateway` 只能将某次请求对应的 OpenCode 返回投递给发起该请求的来源服务

### Session Identity

- [ ] **SESS-01**: `gateway` 可以为不同来源服务生成全局唯一的 session id，且同一算法下不会与 `skill-server` 现有链路冲突
- [ ] **SESS-02**: 接入服务可以基于生成后的 session id 完成会话追踪，而不会误关联到其他服务的会话

### Observability & Safety

- [ ] **SAFE-01**: 系统可以在日志或调试上下文中明确标识来源服务与 session id 的对应关系，支持排查错投与冲突风险
- [ ] **SAFE-02**: `skill-server` 与新增服务并发运行时，系统可以验证不存在跨服务消息串流或 session id 重复

## v2 Requirements

### Platformization

- **PLAT-01**: `gateway` 为任意新增上游服务提供标准化接入注册机制
- **PLAT-02**: `gateway` 为不同服务定义统一的能力协商与版本兼容策略

## Out of Scope

| Feature | Reason |
|---------|--------|
| 重做 `skill-server` 广播层 | 当前问题核心在 `gateway` 的来源识别与回流隔离 |
| 建立独立服务注册中心 | 目前只有两个已知接入方，先用轻量设计完成隔离 |
| 大规模开放第三方接入能力 | 本 milestone 先验证双服务并行模型是否稳定 |

## Traceability

| Requirement | Phase | Status |
|-------------|-------|--------|
| ROUT-01 | Phase 7 | Completed |
| ROUT-02 | Phase 7 | Completed |
| SAFE-01 | Phase 7 | Completed |
| SESS-01 | Phase 8 | Completed |
| SESS-02 | Phase 8 | Completed |
| SAFE-02 | Phase 8 | Completed |

**Coverage:**
- v1 requirements: 6 total
- Mapped to phases: 6
- Unmapped: 0

---
*Requirements defined: 2026-03-12*
## Phase 7 Delivery Notes

- `GatewayMessage` 顶层已新增 `source` 与 `traceId`，用于上游来源隔离和路由观测。
- `source` 只属于上游服务与 `gateway` 之间的标准协议，以及 `gateway` 内部路由上下文，不下沉到 `pc-agent`。
- `gateway` 已在握手阶段绑定可信 `source`，并在消息阶段校验消息体 `source` 与连接绑定 `source` 一致。
- owner 注册与查询已升级为按 `source` 分域，owner key 使用 `source:instanceId`。
- 回流路由已升级为先按 `source` 分服务域，再在同域内选择 owner；同域可 fallback，跨域严格禁止。
- 关键路由日志已补充 `traceId`、`source`、`ownerKey`、`routeDecision`、`fallbackUsed`、`errorCode` 等结构化字段。

*Last updated: 2026-03-12 after Phase 7 delivery sync*

## Phase 8 Implementation Notes

- `skill-server` 与 `ai-gateway` 已各自引入统一 bit layout 的 Snowflake ID generator，并固定默认 `serviceCode` 分别为 `1` / `2`。
- `skill_session`、`skill_message`、`skill_message_part`、`agent_connection`、`ak_sk_credential` 的写入链路已切换为应用侧显式生成 `Long` 主键。
- 对应 MyBatis mapper 已移除 `useGeneratedKeys` 依赖，并新增 `V5__snowflake_primary_keys.sql` 迁移脚本以去除目标表的自增依赖。
- 测试环境重建约束与服务编码治理已记录到 `documents/protocol/05-snowflake-session-id-governance.md`。
