# Research: ai-gateway sender-account impact

- **Query**: ai-gateway 收到非 owner 的 `payload.sendUserAccount` 会不会被认证/scope/计费策略拒/异常？
- **Scope**: internal（ai-gateway 源码在本仓 monorepo `ai-gateway/` 模块）
- **Date**: 2026-05-20

## 结论

**兼容**。ai-gateway 完全不消费 `payload.sendUserAccount`，把它当作 opaque 字段透传给云端 / Agent。无论 sendUserAccount 是 owner 还是非 owner，gateway 侧都不会触发认证拒绝、scope 校验失败或计费异常。砍掉 skill-server 那 3 处 fallback **不需要** gateway 端联动改动。

下面给出关键证据。

## 关键代码引用

### 1. ai-gateway 全仓零引用 `sendUserAccount` / `senderUserAccount`

`grep -r "sendUserAccount\|senderUserAccount" ai-gateway/` → 0 hits。该字段对 gateway 来说不存在。

### 2. gateway 上行 invoke 校验只看 source / ak / userId

`ai-gateway/src/main/java/com/opencode/cui/gateway/service/SkillRelayService.java:621-660` `validateInvokeMessage`：

- 校验 `messageSource`（必须 == 已绑定 source 类型）
- 校验 `ak` 非空
- 校验顶层 `userId == redisMessageBroker.getAgentUser(ak)` —— 注意，这里的 `userId` 是 **GatewayMessage 顶层字段**（agent 注册时绑定的 owner welinkId），与 `payload.sendUserAccount` 是两回事。skill-server 现在透传的 `userId` 仍然 = ownerWelinkId（task PRD Out of Scope 明确"SkillSession.userId 字段写入语义不动"）。

> 含义：gateway 用 `userId == agent owner` 这一条做"消息来源校验"，但本任务改的是 **payload 内的 `sendUserAccount`**，不动顶层 `userId`，所以 gateway 校验链路完全不感知。

### 3. gateway 业务助手云端路由：payload 只取 cloudRequest / toolSessionId / cloudProfile

`ai-gateway/src/main/java/com/opencode/cui/gateway/service/CloudAgentService.java:73-82`：

```java
JsonNode cloudRequest    = invokeMessage.getPayload().path("cloudRequest");
String   toolSessionId  = invokeMessage.getPayload().path("toolSessionId").asText(null);
String   cloudProfile   = invokeMessage.getPayload().path("cloudProfile").asText("default");
```

`cloudRequest` 是云端约定的 inner JSON blob，gateway 不解析、不校验、直接交给 `CloudProtocolClient` (sse/websocket/webhook) 透传给云端服务。`sendUserAccount` 即便嵌在 `cloudRequest` 里，也只是字节流。

### 4. gateway 个人助手路由：完全不看 payload 内容

`ai-gateway/src/main/java/com/opencode/cui/gateway/service/cloud/PersonalInvokeRouteStrategy.java`（同目录），以及 `SkillRelayService.dispatchToAgent` (line 684-706) —— personal scope 只用 `ak` 找本地 Agent / 远端 GW / pending 队列，payload 整体序列化后转发到 Agent WS，gateway 自身不读字段。

### 5. gateway 上 payload toolSessionId 提取

`SkillRelayService.extractToolSessionIdFromPayload` (line 822-830) 和 `UpstreamRoutingTable` (line 167-170) 只读 `payload.toolSessionId`，没碰 sendUserAccount。

### 6. AssistantScopeStrategy 在 skill-server 不在 gateway

`AssistantScopeStrategy` 位置：`skill-server/src/main/java/com/opencode/cui/skill/service/scope/AssistantScopeStrategy.java`。这是 skill-server 内的 scope 策略接口（business / personal），用于本地 invoke 构建、event 翻译、`requiresOnlineCheck` 等。**与 gateway 端校验无关**。`requiresOnlineCheck()` 是 skill-server 内决定是否做 Agent 在线探测的开关，不读 `sendUserAccount`，也不会用它做 owner 比对。

### 7. 注意：有一处看似相似但方向相反的 owner 校验（不影响本任务）

`ai-gateway/src/main/java/com/opencode/cui/gateway/controller/CloudPushController.java:43-85`：

```
单聊（imGroupId 为空）：校验 userAccount == create_by，不匹配 → 403
```

- 方向：**反向**——外部云端 → gateway `/api/gateway/cloud/im-push` → 转发到 SS → 推 IM。
- 字段：`ImPushRequest.userAccount`，与本任务改的 inbound chat 路径里的 `payload.sendUserAccount` 是不同字段、不同方向。
- 结论：本任务不动 `/im-push` 路径，不受影响。但值得在 PRD 备注里点一下：cloud → IM 推送方向上 gateway 仍强约束"只能推给 owner"。

## 风险点

| 维度 | 评估 |
|---|---|
| 认证 / scope 拒绝 | 无。gateway 完全不读 `sendUserAccount`。 |
| 计费 / 配额 | gateway 侧无计费逻辑读该字段。若云端服务（webhook / SSE 后端）按 `sendUserAccount` 计费/审计，那是云端职责，与 gateway 解耦。 |
| 日志关联 | gateway 日志只打 `ak / action / userId / traceId / toolSessionId`，不打 `sendUserAccount`。改动不影响 gateway 侧追踪。 |
| 权限边界 | gateway 顶层 `userId` 仍 = ownerWelinkId（PRD Out of Scope 保留），通过 agent owner 比对。`sendUserAccount` 改成真实账号不影响这条校验。 |
| `/im-push` 反向通道 | 不在本任务路径，保留现有 owner 强约束即可。 |

## 联动建议

**不需要 gateway 端联动 PR**。本任务在 skill-server 单边完成即可：

1. 砍掉 3 处 `String effectiveSender = ...` fallback。
2. AC §7 "ai-gateway 端如需联动，独立 PR 标记 blocked-by 本任务" → 可以从 DoD 里删掉，或保留作为"已确认不需要"的痕迹。
3. 真正可能受影响的是 **云端服务自己**（cloudRequest 解码后做的事）—— 但那不是 gateway 范畴，且超出本仓代码可读边界，需要走云端服务的契约确认（不在本研究范围）。

## 未覆盖 / 需人工确认

- 云端 webhook / SSE 后端（不在本仓）对 `sendUserAccount` 的处理：是否有按 owner 白名单的策略。建议在 PRD Open Question 里加一条："cloudRequest 接收端（云端服务）是否对非 owner sendUserAccount 有额外限制？"
- `payload.sendUserAccount` 在 skill-server 出站打日志 / 计费 / 审计的下游：本研究只覆盖 gateway 端，不覆盖 skill-server 自身在收到非 owner 后的副作用（PendingChatRequest 持久化、retry 重放、SkillSession.userId 与 sendUserAccount 分裂带来的日志关联差异等）。这些已在 PRD R2 / Open Question #3 列出，本研究不再展开。
