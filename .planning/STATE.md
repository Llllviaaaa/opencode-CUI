---
gsd_state_version: 1.0
milestone: v1.3
milestone_name: Stream Session Affinity
status: defining_requirements
last_updated: "2026-03-12T17:35:00+08:00"
progress:
  total_phases: 2
  completed_phases: 0
  total_plans: 6
  completed_plans: 0
---

# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-03-12)

**Core value:** 无论 `gateway` 对接多少上游服务，每一条请求和每一段流式返回都必须只属于它真实的来源服务与会话，不允许串流、错投或 session 标识冲突。  
**Current focus:** v1.3 `Stream Session Affinity`

## Current Position

Milestone: v1.3 Stream Session Affinity  
Phase: Not started  
Plan: Requirements and roadmap defined  
Status: Defining requirements  
Last activity: 2026-03-12 - started milestone v1.3 to fix missing `welinkSessionId` in stream events and prevent cross-session leakage

## Current Requirements

- `STREAM-01`: miniapp 收到的实时流式事件始终携带正确的 `welinkSessionId`
- `STREAM-02`: `skill-server` 能在广播前恢复缺失显式会话字段的事件归属
- `STREAM-03`: 无法恢复归属时不广播到任何会话，并保留告警
- `CONSIST-01`: 恢复态与实时增量事件的会话标识语义一致
- `CONSIST-02`: A/B session 并发时不会互相看到对方返回
- `SAFE-03`: 自动化测试覆盖关键回归场景

## Accumulated Context

### Stable Context

- v1.1 修复了连接归属与实时回流错投问题
- v1.2 完成了多来源服务隔离、统一 Snowflake ID 治理和 `welinkSessionId` 数字型收口
- 当前新问题出现在 `skill-server -> miniapp` 的流式事件出口，影响用户可见的会话隔离

## Next Step

执行 `$gsd-plan-phase 10`，为会话亲和性修复创建详细实施计划。
