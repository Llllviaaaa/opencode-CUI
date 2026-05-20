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

## Resolved Decisions (2026-05-20)

- **[RESOLVED · BLOCKING]** ai-gateway 不读 `payload.sendUserAccount`，作为 opaque 字段透传。`SkillRelayService.validateInvokeMessage` 只校验顶层 `source / ak / userId`，顶层 `userId` 仍 = `ownerWelinkId`（本任务 Out of Scope 已保留）。证据见 `research/ai-gateway-sender-impact.md`。gateway 端无需联动改造。
  - 备注：`CloudPushController.imPush` 反向通道（cloud → IM 推送）有 "userAccount == create_by 否则 403" 校验，但用的是 `ImPushRequest.userAccount`，不同字段不同方向，与本任务无关。
  - 未覆盖：云端 webhook/SSE 后端（不在本仓）是否对非 owner sendUserAccount 有额外限制——超出本仓代码可读边界，需要人工确认。
- **[RESOLVED · Blank 兜底]** senderUserAccount blank/null 直接拒绝该 inbound（4xx），视为 IM 平台协议违反。**不再保留 ownerWelinkId fallback**。
- **[RESOLVED · Pending 兼容]** 新 PendingChatRequest entry 写真实 sender；老 entry 重放时 owner 占位仍可解析（序列化版本不 bump），反序列化容错。

## Requirements (evolving)

### R1 移除 3 处 fallback
- 3 处统一替换为：`senderUserAccount` 非空时直接用；blank/null 时抛 4xx 拒绝该 inbound（按 Blank 兜底决策）。
- **去掉 group 前置条件**，单聊也用真实 sender。
- 完全不再以 ownerWelinkId 作为兜底。

### R2 兼容性
- ai-gateway 侧已验证（research）：透传字段，无需联动。
- PendingChatRequest Redis 持久化字段序列化版本不 bump，老 entry（owner 占位）重放兼容。

## Acceptance Criteria (evolving)

- [ ] inbound chat（direct sessionType）+ senderUserAccount=`userA` + ownerWelinkId=`owner` → 下发 gateway 的 payload `sendUserAccount=userA`，不是 `owner`。
- [ ] inbound chat（group sessionType）行为不变（已经是真实 sender）。
- [ ] senderUserAccount blank/null → 直接拒绝 inbound 返回 4xx，不再回落 owner。
- [ ] business 助手首次建会话路径同步生效。
- [ ] personal 助手 pending list 入队 + retryPendingMessages 重放路径同步生效。
- [ ] 4 inbound action（chat / question_reply / permission_reply / rebuild）的单聊 sendUserAccount 都用真实值。
- [ ] InboundProcessingServiceTest / ImSessionManagerTest 覆盖单聊 sender 不被覆盖的回归。

## Definition of Done

- 单测覆盖以上 AC。
- mvn test / lint / CI 通过。
- ai-gateway 端确认无需联动（research 已验证）。
- 按用户偏好走 codex (gpt-5.5 + xhigh) 评审 Critical 一轮再开 PR1。

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
- v2 (2026-05-20): research 收口 BLOCKING（gateway 不读 sendUserAccount）；Blank 兜底定为 4xx 拒绝；Pending 序列化不 bump，老 entry 重放兼容。
