# 单聊 sender 不再 fallback 为 ownerWelinkId（放开非 owner 单聊）

> 由 `05-20-send-to-im-refactor` brainstorm 阶段拆分独立。
> 入站（inbound）路径协议语义变更，不与 send-to-im 出站改造耦合。

## Goal

放开"只有助手 owner 才能跟助手做单聊"的隐性限制。现状是 inbound chat 路径里 3 处把单聊场景的 `sendUserAccount` 强制覆盖为 `ownerWelinkId`，导致 ai-gateway 收到的单聊 sendUserAccount 永远是 owner，等价于"非 owner 单聊"被吞掉真实身份。本任务把这 3 处 fallback 砍掉，单聊也用真实 senderUserAccount。

## What I already know

3 处共享 fallback：
```java
String effectiveSender = "group".equals(sessionType)
        && senderUserAccount != null && !senderUserAccount.isBlank()
        ? senderUserAccount : ownerWelinkId;
```

| # | 文件 | 行号 | 上下文 |
|---|---|---|---|
| 1 | `service/InboundProcessingService.java` | 355-358 | `dispatchChatToGateway`：session 就绪后下发 chat invoke 的 sendUserAccount 字段 |
| 2 | `service/ImSessionManager.java` | 160-162 | `createSessionAsync` 业务助手分支：本地预生成 toolSessionId 后立即下发首条 chat 的 sendUserAccount |
| 3 | `service/ImSessionManager.java` | 188-190 | `createSessionAsync` 个人助手分支：构造 PendingChatRequest 入 Redis pending list 时的 sendUserAccount |

## Open Questions

- **[BLOCKING]** ai-gateway 那边收到非 owner 的 `sendUserAccount`（任意 welinkId / 账号）是否会被认证 / scope 策略拒？需要 research 确认。如果会被拒，本任务还得连带改 gateway 端。
- **[PREFERENCE]** senderUserAccount blank 时（IM 平台没传）的兜底：(a) 仍然回落到 ownerWelinkId（保守，不引入 null payload）；(b) 直接传 null/空，让 gateway 端处理；(c) 拒绝该 inbound（404）。
- **[PREFERENCE]** PendingChatRequest 入队字段（影响后续 retryPendingMessages 重放）：要不要把真实 senderUserAccount 持久化到 Redis pending list？需要兼容老 entry。

## Requirements (evolving)

### R1 移除 3 处 fallback
- 3 处统一替换为：`effectiveSender = (senderUserAccount != null && !senderUserAccount.isBlank()) ? senderUserAccount : ownerWelinkId;`（**去掉 group 前置条件**，单聊也用真实 sender）。
- 或更激进：完全不再 fallback，blank/null 直接保留 null 透传——视 Open Question #2 决定。

### R2 兼容性
- ai-gateway 侧验证（research 阶段）：单聊 sendUserAccount = 非 owner 的真实账号时，gateway 处理无回归。
- PendingChatRequest Redis 持久化字段（v2/v3 序列化）保持兼容。

## Acceptance Criteria (evolving)

- [ ] inbound chat（direct sessionType）+ senderUserAccount=`userA` + ownerWelinkId=`owner` → 下发 gateway 的 payload `sendUserAccount=userA`，不是 `owner`。
- [ ] inbound chat（group sessionType）行为不变（已经是真实 sender）。
- [ ] senderUserAccount blank → 按 Q2 决定的兜底执行。
- [ ] business 助手首次建会话路径同步生效。
- [ ] personal 助手 pending list 入队 + retryPendingMessages 重放路径同步生效。
- [ ] 4 inbound action（chat / question_reply / permission_reply / rebuild）的单聊 sendUserAccount 都用真实值。
- [ ] InboundProcessingServiceTest / ImSessionManagerTest 覆盖单聊 sender 不被覆盖的回归。

## Definition of Done

- 单测覆盖以上 AC。
- mvn test / lint / CI 通过。
- ai-gateway 端如需联动，独立 PR 标记 blocked-by 本任务。
- 按用户偏好走 codex 评审 Critical 一轮再开 PR1。

## Out of Scope

- 不动 group 路径（已经是真实 sender）。
- 不改 ai-gateway 端身份认证逻辑（如有需要，开独立任务）。
- 不动 send-to-im 出站接口（见 `05-20-send-to-im-refactor`）。
- 不改 SkillSession.userId 字段的写入语义（仍然 = ownerWelinkId，会话归属 / WS 订阅依赖）。

## Technical Notes

### 副作用范围（需 research 确认）
- payload.sendUserAccount 是 ai-gateway 决定 plugin 上下文 / 权限边界的关键字段——改了之后 gateway 侧 `AssistantScopeStrategy.requiresOnlineCheck` / 计费 / 日志关联都会看到非 owner。
- Redis pending list 老 entry（含 owner 占位 sender 的）retry 重放时不能崩。

### Review History
- v1 (2026-05-20): 从 send-to-im-refactor brainstorm 阶段拆分。
