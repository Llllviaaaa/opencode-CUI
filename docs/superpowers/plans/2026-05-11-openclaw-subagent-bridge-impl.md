# OpenClaw Subagent 接入 message-bridge — 详细实施方案

> **Date**: 2026-05-11
> **Based on**: `docs/superpowers/specs/2026-05-11-openclaw-subagent-bridge-design.md` (v2.1)
> **Scope**: bridge / ai-gateway / skill-server / miniapp / DB
> **Estimate**: 12-17 人天

---

## 0. 概览

实现"openclaw 子任务事件桥接到小程序"的完整闭环。bridge 做主路径，其他层做配合（字段透传 + UI 渲染 + cleanup 改动）。

**5 个 PR 拆分建议**（按依赖顺序）：
1. **PR1 — bridge：先决修复**（permission 死代码迁移 + question 清理）—— 单独可上线，独立价值
2. **PR2 — bridge：父子图 + onAgentEvent 子任务分支** —— 让事件能定位到父对话
3. **PR3 — ai-gateway + skill-server + DB**（V9 migration + 字段扩展 + lifecycle 落库）—— 把 bridge 输出的字段一路串到 DB
4. **PR4 — miniapp 嵌套渲染 + lifecycle 终态**
5. **PR5 — bridge：下行子会话路由 + close/abort 改写 + late events 收尾**

每个 PR 内含对应测试。

---

## 1. 协议契约

### 1.1 bridge → ai-gateway 上行扩展

`ToolEventMessage / ToolDoneMessage / ToolErrorMessage` 新增可选字段：

```ts
interface ToolEventMessage {
  type: "tool_event";
  toolSessionId: string;            // 已有，指向"父对话"的 toolSessionId
  event: Record<string, unknown>;
  // ↓ 新增
  subagentSessionId?: string;       // 子任务 sessionKey (= openclaw childSessionKey)
  subagentName?: string;            // 子任务 label（来自 spawn 时的 label，回退到子 agentId）
  subagentDepth?: number;           // 嵌套层数（0=主对话，1=直接子任务，2+=孙子任务）
  subagentParentSessionId?: string; // 直接父 sessionKey（嵌套时用，1 层时 = 主 toolSessionId 对应的 sessionKey）
  subagentLifecycle?: SubagentLifecycleEvent; // 仅 lifecycle stream
}

type SubagentLifecycleEvent = {
  phase: "start" | "end" | "error" | "timeout" | "killed" | "aborted";
  outcome?: "ok" | "error" | "timeout" | "killed";
  error?: string;
  startedAt?: number;
  endedAt?: number;
};
```

> 字段约束：`subagentDepth=0` 时其它 subagent* 字段必须不存在。`subagentDepth>=1` 时 `subagentSessionId` 必填。

### 1.2 ai-gateway → bridge 下行扩展

`InvokeMessage` payload 新增可选 `subagentSessionId`：

```ts
interface ChatPayload {
  toolSessionId: string;
  text: string;
  subagentSessionId?: string;  // 新增：向子任务发追加消息（适用于 mode=session 的持久子任务）
}

interface PermissionReplyPayload {
  toolSessionId: string;
  permissionId: string;
  response: "once" | "always" | "reject";
  subagentSessionId?: string;  // 新增：标识 permission 属于哪个子任务
}

interface CloseSessionPayload {
  toolSessionId: string;
  subagentSessionId?: string;  // 新增：仅关闭指定子任务（不传=关父任务）
}

interface AbortSessionPayload {
  toolSessionId: string;
  subagentSessionId?: string;  // 新增：仅中止指定子任务
}

// question_reply: openclaw 没这能力，保留 schema 但 bridge 内部 fail-closed
```

### 1.3 GatewayMessage (Java)

```java
public class GatewayMessage {
  // 已有
  private String subagentSessionId;
  private String subagentName;
  // 新增
  private Integer subagentDepth;
  private String subagentParentSessionId;
  private SubagentLifecycle subagentLifecycle;  // 仅 lifecycle 透出
}
```

### 1.4 DB schema (V9 migration)

```sql
-- V9__subagent_nested_message_part.sql
ALTER TABLE skill_message_part
  ADD COLUMN subagent_depth INT NULL COMMENT 'Nesting depth: 0=main, 1=direct child, 2+=grandchild',
  ADD COLUMN subagent_parent_session_id VARCHAR(64) NULL COMMENT 'Immediate parent sessionKey for nested rendering',
  ADD COLUMN subagent_lifecycle_phase VARCHAR(16) NULL COMMENT 'start|end|error|timeout|killed|aborted',
  ADD COLUMN subagent_outcome VARCHAR(16) NULL COMMENT 'ok|error|timeout|killed';

CREATE INDEX idx_subagent_parent ON skill_message_part(session_id, subagent_parent_session_id);
```

---

## 2. Bridge 层详细设计

### 2.1 SessionRegistry 父子图

`session/SessionRegistry.ts` 扩展：

```ts
interface SubagentEntry {
  childSessionKey: string;        // openclaw 的子 sessionKey
  parentSessionKey: string;       // 直接父 sessionKey（可能也是子）
  rootToolSessionId: string;      // 一路向上爬到的 bridge 父 toolSessionId
  depth: number;                  // 1=直接子任务，2+=孙
  label: string;                  // 来自 SessionEntry.label 或 spawnedBy 元数据
  registeredAt: number;
}

class SessionRegistry {
  // 已有
  private byToolSessionId = new Map<string, MessageBridgeSessionRecord>();

  // 新增
  private byChildSessionKey = new Map<string, SubagentEntry>();

  /** 注册一个子任务。可能是任意深度。返回该子任务对应的 root toolSessionId（找不到=null） */
  ensureSubagent(params: {
    childSessionKey: string;
    sessionStore: Record<string, SessionEntry>;
  }): SubagentEntry | null {
    const cached = this.byChildSessionKey.get(params.childSessionKey);
    if (cached) return cached;

    // 顺 spawnedBy 链向上爬
    const chain = this.walkSpawnedByChain(params.childSessionKey, params.sessionStore);
    if (!chain) return null;  // 找不到根

    const entry: SubagentEntry = {
      childSessionKey: params.childSessionKey,
      parentSessionKey: chain.parentSessionKey,
      rootToolSessionId: chain.rootToolSessionId,
      depth: chain.depth,
      label: chain.label,
      registeredAt: Date.now(),
    };
    this.byChildSessionKey.set(params.childSessionKey, entry);
    return entry;
  }

  private walkSpawnedByChain(
    startKey: string,
    sessionStore: Record<string, SessionEntry>
  ): { parentSessionKey: string; rootToolSessionId: string; depth: number; label: string } | null {
    let current = startKey;
    let depth = 0;
    let immediateParent: string | undefined;
    let label = sessionStore[current]?.label ?? this.deriveLabelFromKey(current);

    while (depth < MAX_SPAWN_DEPTH_GUARD) {
      const entry = sessionStore[current];
      const spawnedBy = entry?.spawnedBy;
      if (!spawnedBy) {
        // current 是顶层（应该是 bridge 主对话）
        const rootRecord = this.findRecordBySessionKey(current);
        if (rootRecord && depth > 0) {
          return {
            parentSessionKey: immediateParent ?? current,
            rootToolSessionId: rootRecord.toolSessionId,
            depth,
            label,
          };
        }
        return null;
      }
      immediateParent = immediateParent ?? spawnedBy;
      current = spawnedBy;
      depth += 1;
    }
    return null;  // 超过保护深度
  }

  private findRecordBySessionKey(sessionKey: string): MessageBridgeSessionRecord | undefined {
    for (const record of this.byToolSessionId.values()) {
      if (record.sessionKey === sessionKey) return record;
    }
    return undefined;
  }

  /** 父任务结束时清掉所有子映射 */
  clearSubagentsForRoot(rootToolSessionId: string): void {
    for (const [key, entry] of this.byChildSessionKey) {
      if (entry.rootToolSessionId === rootToolSessionId) {
        this.byChildSessionKey.delete(key);
      }
    }
  }

  getSubagent(childSessionKey: string): SubagentEntry | undefined {
    return this.byChildSessionKey.get(childSessionKey);
  }
}

const MAX_SPAWN_DEPTH_GUARD = 10;  // 防止 spawnedBy 环或异常深度
```

### 2.2 handleRuntimeAgentEvent 新版

```ts
private async handleRuntimeAgentEvent(evt: ToolAgentEvent): Promise<void> {
  if (!isRecord(evt.data)) return;

  const sessionKey = typeof evt.sessionKey === "string" ? evt.sessionKey : undefined;

  // ── 主对话分支（按现状）──
  if (sessionKey) {
    const mainRecord = this.findRecordBySessionKey(sessionKey);
    if (mainRecord) {
      this.handleMainSessionEvent(mainRecord, evt);
      return;
    }
  }

  // 兼容：sessionKey 缺失但 runId 在 active 映射里（fallback 路径）
  if (!sessionKey && typeof evt.runId === "string") {
    const mappedKey = this.activeRunToSessionKey.get(evt.runId);
    if (mappedKey) {
      const mainRecord = this.findRecordBySessionKey(mappedKey);
      if (mainRecord) {
        this.handleMainSessionEvent(mainRecord, evt);
        return;
      }
    }
  }

  // ── 子任务分支 ──
  if (!sessionKey) return;  // 没 sessionKey 也不在 runId 映射 → 丢
  if (!this.looksLikeSubagentSessionKey(sessionKey)) return;  // 不是 subagent 格式

  // late event 检查
  if (this.terminatedSessionKeys.has(sessionKey)) {
    this.logger.debug("subagent.event.late_drop", { sessionKey, stream: evt.stream });
    return;
  }

  // 反查 / 注册子任务
  const subagent = await this.resolveSubagent(sessionKey);
  if (!subagent) {
    this.logger.warn("subagent.event.unresolved_parent", { sessionKey });
    return;
  }

  // 检查 root 父任务是否还活着
  if (this.terminatedToolSessionIds.has(subagent.rootToolSessionId)) {
    this.logger.debug("subagent.event.parent_terminated", { sessionKey, root: subagent.rootToolSessionId });
    return;
  }

  this.handleSubagentEvent(subagent, evt);
}

private async resolveSubagent(childSessionKey: string): Promise<SubagentEntry | null> {
  const cached = this.sessionRegistry.getSubagent(childSessionKey);
  if (cached) return cached;

  // 读 session store（同步 RPC）
  try {
    const sessionStore = await this.loadSessionStore();
    return this.sessionRegistry.ensureSubagent({
      childSessionKey,
      sessionStore,
    });
  } catch (err) {
    this.logger.warn("subagent.resolve.store_load_failed", { error: String(err) });
    return null;
  }
}

private looksLikeSubagentSessionKey(key: string): boolean {
  return /^agent:[^:]+:subagent:/.test(key);
}

private async loadSessionStore(): Promise<Record<string, SessionEntry>> {
  const session = this.runtime.agent.session;
  const storePath = session.resolveStorePath();
  return await session.loadSessionStore(storePath);
}
```

### 2.3 handleSubagentEvent 按 stream 分发

```ts
private handleSubagentEvent(subagent: SubagentEntry, evt: ToolAgentEvent): void {
  const context = this.buildSubagentEventContext(subagent);

  switch (evt.stream) {
    case "assistant":
      this.emitSubagentAssistantDelta(subagent, evt.data, context);
      break;
    case "reasoning":
      this.emitSubagentReasoning(subagent, evt.data, context);
      break;
    case "tool":
      this.emitSubagentTool(subagent, evt.data, context);
      break;
    case "approval":
      this.handleApprovalEvent(subagent, evt.data, context);
      break;
    case "lifecycle":
      this.handleLifecycleEvent(subagent, evt.data, context);
      break;
    case "item":
    case "plan":
    case "command_output":
    case "patch":
    case "compaction":
    case "thinking":
      // 暂不透传，未来按需
      break;
    default:
      this.logger.debug("subagent.event.unknown_stream", { stream: evt.stream });
  }
}

private buildSubagentEventContext(subagent: SubagentEntry): UpstreamSendContext {
  return {
    action: "chat",
    toolSessionId: subagent.rootToolSessionId,
    welinkSessionId: undefined,  // 由发送时合并
    subagentSessionId: subagent.childSessionKey,
    subagentName: subagent.label,
    subagentDepth: subagent.depth,
    subagentParentSessionId: subagent.parentSessionKey,
  };
}
```

### 2.4 approval 分支（先决修复 + 子任务通用）

```ts
private handleApprovalEvent(
  subagent: SubagentEntry | null,  // null = 主对话
  data: Record<string, unknown>,
  context: UpstreamSendContext,
): void {
  const phase = asString(data.phase);
  const approvalId = asString(data.approvalId);
  if (!approvalId) return;

  const toolSessionId = context.toolSessionId!;

  if (phase === "requested") {
    const record = this.approvalRegistry.upsertPending({
      toolSessionId,
      subagentSessionId: subagent?.childSessionKey,  // 关联子任务
      permissionId: approvalId,
      title: asString(data.title),
      messageId: asString(data.messageId),
      metadata: { command: asString(data.command), host: asString(data.host) },
    });
    this.sendToolEvent({
      type: "tool_event",
      toolSessionId,
      subagentSessionId: subagent?.childSessionKey,
      subagentName: subagent?.label,
      subagentDepth: subagent?.depth,
      event: buildPermissionAskedEvent(toolSessionId, approvalId, { /* ... */ }),
    }, context);
    return;
  }

  if (phase === "resolved") {
    const record = this.approvalRegistry.markResolved(approvalId);
    if (!record) return;
    this.sendToolEvent({
      type: "tool_event",
      toolSessionId,
      subagentSessionId: record.subagentSessionId,
      event: buildPermissionUpdatedEvent(toolSessionId, approvalId, {
        status: "resolved",
        decision: asString(data.status),
      }),
    }, context);
  }
}
```

注意 `ApprovalRegistry` 也要扩 `subagentSessionId` 字段，用于 permission_reply 时正确路由。

### 2.5 lifecycle 分支

```ts
private handleLifecycleEvent(
  subagent: SubagentEntry,
  data: Record<string, unknown>,
  context: UpstreamSendContext,
): void {
  const phase = asString(data.phase);   // start | end | error
  const aborted = data.aborted === true;
  const stopReason = asString(data.stopReason);
  const error = asString(data.error);

  // 状态归一化
  const lifecycle: SubagentLifecycleEvent = (() => {
    if (phase === "error") {
      return { phase: "error", outcome: "error", error, endedAt: typeof data.endedAt === "number" ? data.endedAt : Date.now() };
    }
    if (phase === "end") {
      if (aborted) return { phase: "aborted", outcome: "killed", endedAt: Date.now() };
      if (stopReason === "timeout") return { phase: "timeout", outcome: "timeout", endedAt: Date.now() };
      return { phase: "end", outcome: "ok", endedAt: Date.now() };
    }
    if (phase === "start") {
      return { phase: "start", startedAt: typeof data.startedAt === "number" ? data.startedAt : Date.now() };
    }
    return { phase: "end", outcome: "ok" };  // fallback
  })();

  this.sendToolEvent({
    type: "tool_event",
    toolSessionId: subagent.rootToolSessionId,
    subagentSessionId: subagent.childSessionKey,
    subagentName: subagent.label,
    subagentDepth: subagent.depth,
    subagentLifecycle: lifecycle,
    event: this.buildSubagentLifecycleEvent(subagent, lifecycle),
  }, context);

  // end / error / aborted / timeout：标记终止，准备处理 late events
  if (lifecycle.phase !== "start") {
    this.terminatedSessionKeys.add(subagent.childSessionKey);
    // 15 秒后再清缓存（grace 期内还可能来 late events）
    setTimeout(() => {
      this.sessionRegistry.removeSubagent?.(subagent.childSessionKey);
      this.terminatedSessionKeys.delete(subagent.childSessionKey);
    }, 15_000);
  }
}
```

> 注意：openclaw 普通 pi-embedded 路径**不一定 emit lifecycle:start**（已确认仅 ACP / CLI provider 路径 emit）。这意味着 bridge 可能首次看到子任务的是 assistant/tool/approval 事件。`handleSubagentEvent` 第一次见到子 sessionKey 时（无论什么 stream），应**同时**合成一个 `subagent.start` 上行事件给前端，让 SubtaskBlock 能立即创建。

### 2.6 下行子会话路由

`handleChat`、`handlePermissionReply`、`handleAbortSession`、`handleCloseSession` 增加分支：

```ts
private async handleChat(message: InvokeMessage & { action: "chat" }): Promise<boolean> {
  const subSessionKey = message.payload.subagentSessionId;
  if (subSessionKey) {
    // 向子任务追加消息（mode=session 的持久子任务）
    return this.dispatchChatToSubagent(subSessionKey, message);
  }
  // 现状：父对话 chat
  return this.dispatchChatToMain(message);
}

private async dispatchChatToSubagent(childSessionKey: string, message): Promise<boolean> {
  // 必须经 runtime.subagent.run，不能走 dispatchReplyFromConfig（那是父对话的渠道流）
  const subagentRuntime = this.getSubagentRuntime();
  if (!subagentRuntime) {
    this.sendToolError(/* ... */);
    return false;
  }
  const run = await subagentRuntime.run({
    sessionKey: childSessionKey,
    message: message.payload.text,
    deliver: false,
    idempotencyKey: `chat:${childSessionKey}:${randomUUID()}`,
  });
  // 事件流自然会通过 onAgentEvent 回到 handleSubagentEvent
  return true;
}

private async handlePermissionReply(message): Promise<boolean> {
  const record = this.approvalRegistry.get(message.payload.permissionId);
  if (!record) { /* tool_error */ return false; }
  // 验证 subagentSessionId 一致
  if (record.subagentSessionId !== message.payload.subagentSessionId) {
    this.sendToolError(/* permission_subagent_mismatch */);
    return false;
  }
  // 走 runtime gateway request (现状)
  await this.approvalPort.resolve({ permissionId: message.payload.permissionId, decision });
  return true;
}

private async handleAbortSession(message): Promise<boolean> {
  const subSessionKey = message.payload.subagentSessionId;
  if (subSessionKey) {
    // 调 openclaw gateway sessions.abort，不要 delete
    await this.gatewayRequest("sessions.abort", { key: subSessionKey });
    this.terminatedSessionKeys.add(subSessionKey);
    return true;
  }
  // 父任务 abort（现状）
  const record = this.sessionRegistry.get(message.payload.toolSessionId);
  if (!record) return false;
  this.markSessionTerminated(record);
  this.sessionRegistry.clearSubagentsForRoot(record.toolSessionId);  // 清子任务映射
  this.sendToolDone(/* ... */);
  return true;
}

private async handleCloseSession(message): Promise<boolean> {
  const subSessionKey = message.payload.subagentSessionId;
  if (subSessionKey) {
    // 只清 bridge 的映射，不调 openclaw delete（让 openclaw cleanup 自己来）
    this.sessionRegistry.removeSubagent?.(subSessionKey);
    return true;
  }
  // 父任务 close（仅清映射，不 delete openclaw session —— 改自现状）
  const record = this.sessionRegistry.get(message.payload.toolSessionId);
  if (!record) { /* tool_error */ return false; }
  this.markSessionTerminated(record);
  this.sessionRegistry.clearSubagentsForRoot(record.toolSessionId);
  this.sessionRegistry.delete(message.payload.toolSessionId);
  return true;
}
```

> **重要改动**：`handleCloseSession` 旧实现调 `deleteHostSession`（即 `runtime.subagent.deleteSession`）。v2.1 起 bridge 不再做这件事 —— 让 openclaw 内部 cleanup 处理。如果 openclaw 实际上不在 cleanup 里删主对话 session（只清 subagent），那 bridge 还要保留这条路径仅对**非 subagent sessionKey** 调 delete。**开工前必须确认这点**。

### 2.7 死代码清理

- 删除整个 `subscribeRuntimeGatewayEvents` 函数及其调用（`OpenClawGatewayBridge.ts:1917-2044` 及 `start()` 里的 setup）
- 删除 `handleRuntimeGatewayEvent` 函数
- 删除 question 相关代码：
  - `session/upstreamEvents.ts` `buildQuestionAskedEvent`、`QuestionAskedEventOptions`
  - `OpenClawGatewayBridge.ts` `handleQuestionReply`
  - `runtime/QuestionRegistry.ts` 整个文件
  - `runtime/InteractionPorts.ts` `QuestionReplyPort / RuntimeQuestionReplyPort`
  - `contracts/downstream.ts` 保留 `QuestionReplyPayload` 但 `handleInvoke` 里直接 fail-closed

---

## 3. ai-gateway 层

### 3.1 GatewayMessage 字段扩展

`ai-gateway/src/main/java/com/opencode/cui/gateway/model/GatewayMessage.java`：

```java
public class GatewayMessage {
  // 已有
  private String subagentSessionId;
  private String subagentName;
  // 新增
  private Integer subagentDepth;
  private String subagentParentSessionId;
  private SubagentLifecycle subagentLifecycle;

  public static class SubagentLifecycle {
    private String phase;      // start | end | error | timeout | killed | aborted
    private String outcome;    // ok | error | timeout | killed
    private String error;
    private Long startedAt;
    private Long endedAt;
  }
}
```

### 3.2 GatewayMessageRouter 透传新字段

`GatewayMessageRouter.java:551` 附近已有 subagentSessionId/Name 注入，照样补：

```java
JsonNode subagentNode = node.path("subagentSessionId");
if (!subagentNode.isMissingNode() && !subagentNode.isNull()) {
    msg.setSubagentSessionId(subagentNode.asText());
    msg.setSubagentName(node.path("subagentName").asText(null));
    // 新增
    JsonNode depthNode = node.path("subagentDepth");
    if (depthNode.isInt()) msg.setSubagentDepth(depthNode.asInt());
    msg.setSubagentParentSessionId(node.path("subagentParentSessionId").asText(null));
    JsonNode lifecycleNode = node.path("subagentLifecycle");
    if (lifecycleNode.isObject()) msg.setSubagentLifecycle(parseSubagentLifecycle(lifecycleNode));
}
```

两处都要补（`:551` 和 `:915`）。

### 3.3 下行 inject

q_r / p_r / chat / close / abort 下行 JSON 加 `subagentSessionId` 字段。位置在外发 plugin payload 序列化处。

---

## 4. skill-server 层

### 4.1 V9 migration

见 §1.4。

### 4.2 ProtocolMessagePart / SkillMessagePart 增字段

对应 V9 的 4 个新列：

```java
public class SkillMessagePart {
  private Integer subagentDepth;
  private String subagentParentSessionId;
  private String subagentLifecyclePhase;
  private String subagentOutcome;
}
```

`SkillMessagePartMapper.xml` 对应 INSERT/UPDATE 加字段。

### 4.3 lifecycle 事件落库

`StreamMessage` 增 `subagentLifecycle` 字段。`InboundProcessingService` 处理 chat part 时：
- 普通 part：和现状一样
- 带 `subagentLifecycle.phase=start` 的事件：在 DB 写一条 `type='subtask_marker'` 的 part（让 miniapp 创建 SubtaskBlock）
- 带 `subagentLifecycle.phase ∈ {end,error,timeout,killed,aborted}` 的事件：在 DB 更新 part 的 `subagent_lifecycle_phase / subagent_outcome` 字段

### 4.4 close / abort 改动

`SkillMessageController` 关闭/中止处理：
- 检查 session 下有没有未结束的 subagent_message_part（subagent_lifecycle_phase 不在终态）
- **不要主动给 bridge 发 close 子任务的指令**（避免双删）—— 让 openclaw 内部 cleanup 收尾
- 仅在 session abort 时给 bridge 发 abort_session(无 subagentSessionId)，bridge 处理父任务终止

---

## 5. miniapp 层

### 5.1 SubtaskBlock 嵌套渲染

`SubtaskBlock.tsx` 增 props `depth: number`：

```tsx
<div className={`subtask-block depth-${Math.min(depth, 3)}`} style={{ marginLeft: depth * 16 }}>
  <SubtaskHeader name={name} status={status} depth={depth} />
  <SubtaskBody parts={parts}>
    {/* 嵌套：渲染孙子任务 */}
    {nestedSubtasks.map(child => <SubtaskBlock key={child.id} {...child} />)}
  </SubtaskBody>
</div>
```

`useSkillStream` 分组逻辑：
- 按 `subagentSessionId` 分组
- 按 `subagentParentSessionId` 构建树
- depth >= 2 的卡片放在 depth-1 的卡片内部

### 5.2 lifecycle 终态显示

SubtaskBlock 状态徽章：
- `running` → 黄色"运行中"
- `ok` → 绿色"完成"
- `error` → 红色"失败" + error 文本
- `timeout` → 灰色"超时"
- `killed` → 灰色"已中止"
- `aborted` → 灰色"已中止"

### 5.3 announce 回注无需特殊处理

子任务结束后父对话气泡冒出来的 assistant 流，按现状走主对话即可。SubtaskBlock 已经是"已完成"状态。

---

## 6. 测试矩阵

按 PR 拆分，每个 PR 自带必跑测试：

### PR1 - bridge 先决修复
- ✅ approval requested 经 onAgentEvent stream="approval" 触发 permission asked 上行
- ✅ approval resolved 触发 permission updated 上行
- ✅ permission_reply 下行成功 resolve approval
- ✅ question_reply 下行 fail-closed
- ✅ subscribeRuntimeGatewayEvents 已移除

### PR2 - bridge 父子图
- ✅ onAgentEvent 收到 subagent sessionKey 能反查 spawnedBy 到根
- ✅ 嵌套 2 层 subagent 能爬到 root toolSessionId
- ✅ subagent sessionKey 无法解析时静默丢弃 + 日志
- ✅ sessionStore 还没写入时（极少见）的处理
- ✅ subagent 第一次事件触发 subagent.start 合成上行
- ✅ assistant/reasoning/tool/approval/lifecycle 5 类 stream 都能正确路由
- ✅ late event：子任务终止后 15 秒内的事件被吞 + 记日志
- ✅ MAX_SPAWN_DEPTH_GUARD 防环

### PR3 - ai-gateway + skill-server
- ✅ 4 个新字段 GatewayMessage 透传双向
- ✅ V9 migration 上下兼容（升级 + 回滚）
- ✅ subagent.start lifecycle 触发 part 落库（subtask_marker）
- ✅ subagent.end 更新 part lifecycle_phase
- ✅ close_session 不再级联子任务 cleanup

### PR4 - miniapp
- ✅ depth=1 SubtaskBlock 正常渲染
- ✅ depth=2 嵌套渲染（缩进 / 折叠）
- ✅ 5 个 lifecycle 终态显示正确
- ✅ permission 在子任务卡片内可点击回复
- ✅ announce 回注后主对话气泡正常出

### PR5 - bridge 下行
- ✅ chat 带 subagentSessionId → 路由到子 sessionKey 跑 runtime.subagent.run
- ✅ permission_reply 带 subagentSessionId 校验匹配
- ✅ abort_session 带 subagentSessionId → sessions.abort 而非 delete
- ✅ close_session 父任务 → 清映射不删 openclaw session
- ✅ 父任务 close 后子任务 late event 被丢

### 端到端（PR5 之后跑）
- ✅ 主对话 → 派生 subagent → subagent 逐字流式回小程序 → 子任务结束 → announce 回注 → 主对话气泡
- ✅ 主对话 → subagent → grand-subagent → 卡片嵌套显示
- ✅ subagent 要权限 → 小程序弹卡 → 回复 → subagent 继续跑
- ✅ subagent 跑到一半 → 用户从小程序中止 → subagent killed 状态
- ✅ subagent 超时 → 显示 timeout 状态

---

## 7. 实施序列与依赖

```
PR1 (bridge: 先决修复)
  独立，可先合入上线
  └──┐
PR2 (bridge: 父子图 + onAgentEvent 子任务分支)
  依赖：无（schema 字段先在 bridge 上行 emit）
  └──┐
PR3 (ai-gateway + skill-server + DB V9)
  依赖：PR2 的协议字段
  └──┐
PR4 (miniapp 嵌套 + lifecycle)
  依赖：PR3 的字段落库
  └──┐
PR5 (bridge 下行子会话路由 + close/abort 改写)
  依赖：PR4 的 UI 能发带 subagentSessionId 的下行消息
```

> PR1 可与 PR2-5 并行评审，但 PR2-5 要顺序合入。

---

## 8. 风险与回滚

| 风险 | 应对 |
|---|---|
| sessionStore 读 IO 阻塞 onAgentEvent 处理 | `resolveSubagent` 设 200ms 超时，超时则记日志并丢该次事件（下次会重试） |
| MAX_SPAWN_DEPTH 不够 | 默认 10 层，配置可调；超限只记日志，事件丢 |
| openclaw cleanup 不主动删主对话 session | 开工前 spike 验证；如确认不删，bridge 保留对**非 subagent sessionKey** 的 delete 调用 |
| V9 migration 回滚 | 字段都是 NULL 可补，回滚只需 DROP COLUMN |
| miniapp 老版本碰到带 depth>=1 的 part | 老版本按 v1 逻辑渲染（无 depth），表现为平铺；新版本上线后即恢复嵌套 |
| announce 回注被当成主对话流，但子任务结果还没完整 | 子任务 lifecycle.end 比 announce run 早到（announce 是后置 flow），所以 SubtaskBlock 完成时间正确 |

回滚策略：每个 PR 独立可回滚，PR5 回滚后 bridge 退回到"只读子任务流"模式，依然能看到子任务输出，只是不能在子任务上执行下行操作。

---

## 9. 开工前还要确认 / spike 的 2 件事

1. **openclaw 现有 `close_session`（即 `runtime.subagent.deleteSession`）是否会删主对话 session**？bridge 当前 `handleCloseSession` 调它，v2.1 改为只清映射。如果 openclaw cleanup 不删主对话 session，bridge 还要保留这个调用仅用于非 subagent sessionKey。
2. **`sessionStore.spawnedBy` 写入时机与首次事件**到达的时序差距：理论上 spawnedBy 写在 `callGateway("agent")` 之前，agent run 启动后才 emit 事件，应当稳赢。但 macOS / Linux 文件系统的 fsync 异步性、加上 sessionStore 读是单独 IO，可能造成毫秒级时间窗。spike 跑实际验证一下。

---

## 10. 关键源码索引（实施时常翻）

参见设计文档 §"关键源码索引" 章节。
