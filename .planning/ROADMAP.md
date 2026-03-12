# Roadmap: opencode-CUI

## Milestones

- [x] **v1.0 Multi-Instance Routing Foundation** - shipped
- [x] **v1.1 Connection-Aligned Consumption Fix** - shipped 2026-03-12, archive: [v1.1-ROADMAP.md](/d:/02_Lab/Projects/sandbox/opencode-CUI/.planning/milestones/v1.1-ROADMAP.md)
- [x] **v1.2 Gateway Multi-Service Isolation** - shipped 2026-03-12, archive: [v1.2-ROADMAP.md](/d:/02_Lab/Projects/sandbox/opencode-CUI/.planning/milestones/v1.2-ROADMAP.md)
- [ ] **v1.3 Stream Session Affinity** - in planning

## Current Status

当前里程碑为 `v1.3 Stream Session Affinity`，聚焦修复 `skill-server -> miniapp` 流式事件缺少 `welinkSessionId` 导致的跨会话串流问题。

## Proposed Roadmap

**2 phases** | **6 requirements mapped** | All covered

| # | Phase | Goal | Requirements | Success Criteria |
|---|-------|------|--------------|------------------|
| 10 | Stream Event Affinity | 在 `skill-server` 广播前补齐并校验流式事件的会话归属 | `STREAM-01`, `STREAM-02`, `STREAM-03` | 4 |
| 11 | Frontend Consistency Regression | 统一 miniapp 分流行为并补齐回归验证 | `CONSIST-01`, `CONSIST-02`, `SAFE-03` | 4 |

## Phase Details

### Phase 10: Stream Event Affinity

**Goal**: 让所有会广播到 miniapp 的实时事件在离开 `skill-server` 前都具备正确的 `welinkSessionId`，并在无法确认归属时安全失败。
**Depends on**: Phase 9  
**Plans**: 3 plans

Plans:

- [ ] 10-01: 梳理并修复 `GatewayRelayService` / `OpenCodeEventTranslator` / `StreamMessage` 的会话标识补齐链路
- [ ] 10-02: 为缺失显式 `welinkSessionId` 的 `tool_event` / `tool_done` / `tool_error` / permission 事件补充归属恢复与告警
- [ ] 10-03: 补齐后端自动化测试，验证广播事件始终携带正确 `welinkSessionId`

Success criteria:

1. 所有实时广播到 miniapp 的事件 JSON 都包含数字型 `welinkSessionId`
2. 仅携带 `toolSessionId` 的事件也能在广播前定位到正确会话
3. 无法恢复会话归属的事件不会被错误广播，并留下明确日志
4. 后端测试覆盖“显式字段”“兜底恢复”“恢复失败”三种路径

### Phase 11: Frontend Consistency Regression

**Goal**: 让 miniapp 对 snapshot、恢复态和实时事件使用一致的会话分流规则，并用回归测试锁定 A/B session 隔离。
**Depends on**: Phase 10  
**Plans**: 3 plans

Plans:

- [ ] 11-01: 校准 `useSkillStream` 对不同流式事件类型的会话过滤与恢复策略
- [ ] 11-02: 补齐 miniapp 侧测试或联动测试，覆盖 A/B session 并发显示隔离
- [ ] 11-03: 形成 phase verification / summary，固化 v1.3 的串流隔离证据链

Success criteria:

1. `snapshot`、`streaming`、实时增量事件都按同一 `welinkSessionId` 规则分流
2. A session 产生的回复不会出现在 B session 视图中，反之亦然
3. 前端对缺少会话标识的异常事件表现为忽略或报错，不会错误渲染
4. 回归工件能直接支撑后续 milestone audit

## Next Step

执行 `$gsd-plan-phase 10`，开始 Phase 10 详细计划与实现。
