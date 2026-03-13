# Milestone v1.3 Requirements

**Milestone:** v1.3 Stream Session Affinity  
**Status:** Drafted on 2026-03-12

## Stream Routing

- [ ] **STREAM-01**: 用户在 miniapp 中接收任意实时流式事件时，事件都包含正确的数字型 `welinkSessionId`
- [ ] **STREAM-02**: 当上游事件未显式携带 `welinkSessionId` 但携带 `toolSessionId` 时，`skill-server` 可以在广播前恢复出正确会话并写回事件
- [ ] **STREAM-03**: 当 `toolSessionId -> welinkSessionId` 无法解析时，系统不会把该事件广播到任何会话，并记录可排查的告警信息

## Stream Consistency

- [ ] **CONSIST-01**: miniapp 收到的 `snapshot`、`streaming` 恢复态和实时增量事件在会话标识语义上保持一致，前端可统一按 `welinkSessionId` 分流
- [ ] **CONSIST-02**: 同一用户同时打开 A、B 两个 session 时，只会在对应会话视图中看到各自的流式返回
- [ ] **CONSIST-03**: 用户切换会话、离开后重新进入，或 WebSocket 重连后，正在生成中的回复会恢复已生成内容并继续追加，不会只显示重连后的新增片段

## Quality Gate

- [ ] **SAFE-03**: 存在自动化测试覆盖“缺失显式 `welinkSessionId` 的实时事件”“恢复态事件”“A/B session 并发隔离”“切会话/重连后进行中回复续接”四个回归场景

## Future Requirements

- 真实联调环境下补充端到端 UAT，覆盖 gateway / skill-server / miniapp 全链路观测
- 继续清理历史协议文档中的编码噪声与过时示例

## Out of Scope

- 重构 miniapp 的整套流式状态管理
- 修改 gateway 与 pc-agent 的整体事件模型，只为该问题新增并行协议
- 处理与会话分流无关的 planning 工件整理

## Traceability

| Requirement | Planned Phase | Status |
|-------------|---------------|--------|
| STREAM-01 | Phase 10 | Planned |
| STREAM-02 | Phase 10 | Planned |
| STREAM-03 | Phase 10 | Planned |
| CONSIST-01 | Phase 11 | Planned |
| CONSIST-02 | Phase 11 | Planned |
| CONSIST-03 | Phase 11 | Planned |
| SAFE-03 | Phase 11 | Planned |
