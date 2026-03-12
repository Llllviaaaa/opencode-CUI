# opencode-CUI

## What This Is

`opencode-CUI` 是一个 brownfield 的多组件 AI 会话系统，包含 `ai-gateway`、`skill-server`、`skill-miniapp` 和相关 agent 能力。它负责把不同上游服务发起的会话请求路由到 OpenCode，并把流式返回稳定、准确地回传给对应来源服务。

## Core Value

无论 `gateway` 对接多少上游服务，每一条请求和每一段流式返回都必须只属于它真实的来源服务与会话，不允许串流、错投或 session 标识冲突。

## Current Milestone: v1.3 Stream Session Affinity

**Goal:** 修复 `skill-server -> miniapp` 流式事件缺少 `welinkSessionId` 的问题，确保前端只能看到属于当前会话的实时返回。

**Target features:**
- 为所有会进入 miniapp 流式消费链路的事件补齐稳定的 `welinkSessionId`
- 在 `toolSessionId -> welinkSessionId` 解析失败时阻断错误广播并补足可观测性
- 用自动化回归验证 A/B session 并发时不会互相看到对方返回

## Current State

已完成并归档 v1.2 `Gateway Multi-Service Isolation`。

当前系统已具备：
- 多来源服务的 `source` 识别、握手绑定与回流隔离
- `skill-server` 与 `ai-gateway` 统一 Snowflake ID 基础设施
- `welinkSessionId` 在对外协议上的数字型基线
- 可支撑里程碑审计的 verification / summary / traceability 证据链

## Requirements

### Validated

- [x] 多来源服务接入下，`gateway` 能识别并透传来源服务身份
- [x] OpenCode 返回只会回到发起请求的来源服务
- [x] `gateway` 可为不同来源服务生成全局唯一的 session / entity ID
- [x] 并发运行时不存在跨服务消息串流或 session id 冲突
- [x] 路由链路具备结构化观测字段，可排查来源错投与 owner 选择
- [x] `welinkSessionId` 在 REST / WebSocket / relay / frontend 上统一为数字型

### Active

- [ ] miniapp 收到的实时流式事件始终携带正确的 `welinkSessionId`
- [ ] `skill-server` 在缺少显式 `welinkSessionId` 时可以稳定通过 `toolSessionId` 恢复会话归属，失败时不会把事件广播到错误会话
- [ ] snapshot / streaming 恢复态与实时增量事件使用一致的会话标识语义
- [ ] 存在自动化回归覆盖 A/B session 并发流式场景，证明不会跨会话显示内容

### Out of Scope

- 重写 `skill-server` 现有实时广播模型
- 引入独立的全局流式路由服务
- 在本 milestone 内处理与本问题无关的文档编码清理或通用协议重构

## Context

- v1.1 解决了连接归属与实时回流错投问题
- v1.2 解决了多服务隔离、全局 ID 治理与 `welinkSessionId` 类型收口问题
- 新发现的问题是：`skillserver` 给 `miniapp` 的部分流式事件未携带 `welinkSessionId`，前端无法按会话分流，导致 A session 的返回可能出现在 B session

## Constraints

- **Compatibility**: 不能破坏现有 `skill-server` 链路与现有前端恢复逻辑
- **Protocol**: 优先沿用现有 `StreamMessage` / `GatewayMessage` 模型补齐字段，不引入并行新协议
- **Reliability**: 当无法确认事件归属时，宁可丢弃并告警，也不能错误投递到其他会话
- **Traceability**: 需要能从日志与测试中定位“哪类事件缺字段、由什么兜底恢复、最终广播到哪个会话”

## Key Decisions

| Decision | Rationale | Outcome |
|----------|-----------|---------|
| 把“流式事件的会话亲和性”作为 v1.3 核心目标 | 这是当前直接导致用户可见串流的缺陷 | Active |
| 优先在 `skill-server` 广播前补齐 `welinkSessionId` | 问题发生在 miniapp 消费边界，离出口最近的位置修复风险最小 | Active |
| 将 `toolSessionId -> welinkSessionId` 解析失败视为安全问题而非兼容问题 | 无法确认归属时继续广播会造成跨会话串流 | Active |
| 用前后端联动回归测试锁定 A/B session 串流场景 | 需要证明用户视角下不再出现错误分流 | Active |

## Next Milestone Goals

- 修复 `skill-server -> miniapp` 流式事件缺少 `welinkSessionId` 的会话归属漏洞
- 收敛实时增量事件与恢复态事件的会话标识行为
- 增加自动化测试，阻止 A/B session 串流回归

---
*Last updated: 2026-03-12 for v1.3 milestone start*
