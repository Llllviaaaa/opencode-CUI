# opencode-CUI

## What This Is

`opencode-CUI` 是一个 brownfield 的多组件 AI 会话系统，包含 `ai-gateway`、`skill-server`、`skill-miniapp` 和 `pc-agent`。它负责把用户在 miniapp 里发起的 Skill 会话路由到 OpenCode，并把流式事件稳定回传给对应客户端。

## Core Value

无论 `skill-server` 和 `gateway` 如何横向扩容，用户都应该只从自己真实在线的流连接实例稳定收到完整、连续的 AI 返回消息。

## Current State

- 已完成并验证 v1.1 `Connection-Aligned Consumption Fix`
- 实时广播主链路已切换为 `user-stream:{userId}`
- `gateway` 已具备 `ak -> userId` 的 invoke 校验与回流补全能力
- miniapp 实时回流问题已在真实环境修复并复测通过

## Next Milestone Goals

- 启动新的 milestone 定义下一阶段需求
- 继续把多实例联调步骤自动化，降低人工验证成本
- 视优先级继续整理 v1.0 / v1.1 设计文档收敛到统一入口

## Key Decisions

| Decision | Rationale | Outcome |
|----------|-----------|---------|
| 继续复用 `GatewayMessage` | 降低协议改造范围 | Good |
| 实时广播改为 `user-stream:{userId}` | 让消费归属和真实连接归属一致 | Good |
| `pc-agent` 不携带 `userId` | 保持 agent 侧协议边界清晰 | Good |
| `SkillStreamHandler` 清理逻辑必须幂等 | 避免 transport error + close 双重清理误退订 | Good |

---
*Last updated: 2026-03-12 after v1.1 milestone*
