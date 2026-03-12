# Roadmap: opencode-CUI

## Milestones

- [x] **v1.0 Multi-Instance Routing Foundation** - shipped
- [x] **v1.1 Connection-Aligned Consumption Fix** - shipped 2026-03-12, archive: [v1.1-ROADMAP.md](D:/02_Lab/Projects/sandbox/opencode-CUI/.planning/milestones/v1.1-ROADMAP.md)
- [ ] **v1.2 Gateway Multi-Service Isolation** - phase delivery complete, awaiting milestone audit

## Current Milestone

**v1.2 Gateway Multi-Service Isolation**

Goal: 让 `gateway` 在同时对接 `skill-server` 与其他服务时，既能按来源服务稳定隔离请求与回流响应，又能通过统一 Snowflake ID 基础设施保证全局唯一标识。

### Phase 7: 多服务来源识别与回流隔离

**Goal**: 将来源服务类型提升为 `gateway` 链路中的显式维度，避免不同上游服务之间发生请求归属混淆和响应错投。  
**Depends on**: Phase 6  
**Requirements**: ROUT-01, ROUT-02, SAFE-01  
**Success Criteria**:
1. `gateway` 在接收请求时能明确识别并记录来源服务
2. 请求上下文在调用 OpenCode 和处理回流时持续携带来源服务信息
3. 不同来源服务的响应分发路径彼此隔离，不会把返回误发到 `skill-server`
4. 日志或调试信息能够直接关联 `source service -> session id -> response route`

**Status**: Delivered

### Phase 8: 统一 Snowflake ID 基础设施治理

**Goal**: 在 `skill-server + ai-gateway` 中统一 Snowflake ID 基础设施，消除 DB 自增主键依赖，并完成双模块自动化回归验证。  
**Depends on**: Phase 7  
**Requirements**: SESS-01, SESS-02, SAFE-02  
**Success Criteria**:
1. `skill-server` 与 `ai-gateway` 使用同一 bit layout / epoch / rollback policy 的 Snowflake 算法
2. `welinkSessionId` 和关键 DB 主键改为应用侧 Snowflake `Long`
3. `skill-server` 与 `ai-gateway` 的 mapper 不再依赖 `useGeneratedKeys`
4. 完成测试环境重建说明与双模块自动化回归验证

**Status**: Completed in code and automated verification on 2026-03-12

## Summary

**Phases:** 2  
**Phase Range:** 7-8  
**Mapped Requirements:** 6 / 6

## Next Step

执行 `milestone audit` / `verify-work`，确认 v1.2 是否可以归档，或是否还需要补 gap closure。
