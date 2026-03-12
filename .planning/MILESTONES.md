# Milestones

## v1.1 Connection-Aligned Consumption Fix

**Shipped:** 2026-03-12  
**Phases:** 1  
**Plans:** 3

### Accomplishments

1. 为 `gateway / skill-server` 链路补齐了可信 `userId` 上下文，并在 agent 回流时完成一致性补全。
2. 将 `skill-server` 的实时广播从 `session:{sessionId}` 切换为 `user-stream:{userId}`。
3. 建立了用户级流连接的首订阅 / 末退订生命周期。
4. 清理了旧的 session 级实时广播 API，使其退出主消费链路。
5. 在真实 UAT 中定位并修复了 `SkillStreamHandler` 双重清理导致的误退订问题。
6. 完成了 miniapp 实时回流复测，确认问题已解决。
