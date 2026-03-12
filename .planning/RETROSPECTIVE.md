# Retrospective

## Milestone: v1.1 - Connection-Aligned Consumption Fix

**Shipped:** 2026-03-12  
**Phases:** 1 | **Plans:** 3

### What Was Built

- 实时广播模型从 `session` 维度切换到 `userId` 维度
- 建立了 `gateway` 的 `ak -> userId` 校验与回流补全
- 修复了 miniapp 实时流连接误退订问题

### What Worked

- 先通过 Phase 文档把问题模型收敛清楚，再落代码，减少了返工
- 真实日志排查和自动化回归结合得比较顺，能快速锁定 root cause

### What Was Inefficient

- `.planning` 与设计文档存在编码和状态不同步问题，收口时需要额外补文档
- 多实例联调步骤还偏手工

### Key Lessons

- 多实例实时问题要把“消息归属”和“连接归属”分开建模
- 用户级订阅生命周期要特别关注连接异常和重复清理

## Cross-Milestone Trends

- 文档与实现需要更早同步，避免 milestone 收口时集中修正
- 对 WebSocket 生命周期的幂等清理值得作为后续默认准则
