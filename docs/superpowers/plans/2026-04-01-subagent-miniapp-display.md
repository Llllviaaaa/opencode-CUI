# Subagent Miniapp 展示与交互 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 miniapp 能够展示 OpenCode subagent 的完整对话内容，并支持 subagent 中 permission/question 的交互操作。

**Architecture:** Plugin 层维护子 session→父 session 映射，重写 toolSessionId 后转发；Gateway 透传 subagent 字段；skill-server 翻译并持久化；miniapp 用折叠块展示 subagent 内容，permission/question 冒泡到主对话流。

**Tech Stack:** TypeScript (Plugin, miniapp)、Java 21 + Spring Boot 3.4 (skill-server)、MySQL (持久化)、React 18 (miniapp)

**Design Spec:** `docs/superpowers/specs/2026-04-01-subagent-miniapp-display-design.md`

---

## File Structure

### Plugin (message-bridge)

| Action | Path | Responsibility |
|--------|------|---------------|
| Create | `plugins/agent-plugin/plugins/message-bridge/src/session/SubagentSessionMapper.ts` | 子 session→父 session 映射，内存缓存+懒查询 |
| Modify | `plugins/agent-plugin/plugins/message-bridge/src/contracts/upstream-events.ts` | 新增 `session.created` 到事件白名单 |
| Modify | `plugins/agent-plugin/plugins/message-bridge/src/protocol/upstream/UpstreamEventExtractor.ts` | 新增 `session.created` 的提取器 |
| Modify | `plugins/agent-plugin/plugins/message-bridge/src/contracts/transport-messages.ts` | ToolEventMessage 新增 subagent 字段 |
| Modify | `plugins/agent-plugin/plugins/message-bridge/src/runtime/BridgeRuntime.ts` | handleEvent 中插入映射查询和重写逻辑 |

### skill-server

| Action | Path | Responsibility |
|--------|------|---------------|
| Create | `skill-server/src/main/resources/db/migration/V4__subagent_columns.sql` | message_parts 表新增 subagent 字段 |
| Modify | `skill-server/src/main/java/com/opencode/cui/skill/model/StreamMessage.java` | 新增 subagentSessionId、subagentName 字段 |
| Modify | `skill-server/src/main/java/com/opencode/cui/skill/service/OpenCodeEventTranslator.java` | 从 gateway node 提取 subagent 字段 |
| Modify | `skill-server/src/main/java/com/opencode/cui/skill/service/GatewayMessageRouter.java` | 透传 subagent 字段到 handleToolEvent/handlePermissionRequest |
| Modify | `skill-server/src/main/java/com/opencode/cui/skill/controller/SkillMessageController.java` | replyPermission/sendMessage 支持 subagentSessionId |
| Modify | `skill-server/src/main/java/com/opencode/cui/skill/service/MessagePersistenceService.java` | 持久化 subagent 字段 |

### skill-miniapp

| Action | Path | Responsibility |
|--------|------|---------------|
| Modify | `skill-miniapp/src/protocol/types.ts` | StreamMessage 新增 subagent 字段，新增 SubtaskPart 类型 |
| Create | `skill-miniapp/src/components/SubtaskBlock.tsx` | 折叠/展开块组件 |
| Modify | `skill-miniapp/src/hooks/useSkillStream.ts` | handleSubagentMessage 分发逻辑 |
| Modify | `skill-miniapp/src/components/PermissionCard.tsx` | 新增 subagentName 来源标注 |
| Modify | `skill-miniapp/src/components/QuestionCard.tsx` | 新增 subagentName 来源标注 |
| Modify | `skill-miniapp/src/index.css` | SubtaskBlock 样式 |

---

## Phase 1: Plugin (message-bridge)

### Task 1: SubagentSessionMapper 类

**Files:**
- Create: `plugins/agent-plugin/plugins/message-bridge/src/session/SubagentSessionMapper.ts`

- [ ] **Step 1: 创建 SubagentSessionMapper**

```typescript
// plugins/agent-plugin/plugins/message-bridge/src/session/SubagentSessionMapper.ts

import type { OpencodeClient } from '../types/sdk.js';
import type { BridgeLogger } from '../runtime/AppLogger.js';

export interface SubagentMapping {
  parentSessionId: string;
  agentName: string;
}

/**
 * 维护子 session → 父 session 的映射。
 * 正常流程：session.created 事件主动写入缓存。
 * 兜底流程：缓存 miss 时懒查询 OpenCode API。
 */
export class SubagentSessionMapper {
  /** sessionId → mapping | null (null = 已确认是主 session) */
  private readonly cache = new Map<string, SubagentMapping | null>();
  private readonly clientProvider: () => OpencodeClient | null;
  private readonly logger: BridgeLogger;

  constructor(clientProvider: () => OpencodeClient | null, logger: BridgeLogger) {
    this.clientProvider = clientProvider;
    this.logger = logger;
  }

  /**
   * session.created 事件到达时主动写入缓存。
   * 事件结构: { type: "session.created", properties: { info: { id, parentID?, ... } } }
   */
  onSessionCreated(event: { properties?: { info?: { id?: string; parentID?: string; title?: string } } }): void {
    const info = event?.properties?.info;
    if (!info?.id) return;

    if (info.parentID) {
      const mapping: SubagentMapping = {
        parentSessionId: info.parentID,
        agentName: info.title ?? 'unknown',
      };
      this.cache.set(info.id, mapping);
      this.logger.info('subagent.mapper.cached', {
        childSessionId: info.id,
        parentSessionId: info.parentID,
        agentName: mapping.agentName,
      });
    }
    // 不缓存主 session 的 session.created（它们不需要映射）
  }

  /**
   * 查询映射。缓存命中直接返回；miss 则懒查询 OpenCode API。
   * 返回 null 表示这是主 session（无需重写）。
   */
  async resolve(sessionId: string): Promise<SubagentMapping | null> {
    // 缓存命中
    if (this.cache.has(sessionId)) {
      return this.cache.get(sessionId) ?? null;
    }

    // 缓存 miss → 懒查询
    const client = this.clientProvider();
    if (!client) {
      this.logger.warn('subagent.mapper.no_client', { sessionId });
      return null;
    }

    try {
      const result = await client.session.get({ sessionID: sessionId });
      const session = result as Record<string, unknown>;
      const parentID = session?.parentID as string | undefined;

      if (parentID) {
        const mapping: SubagentMapping = {
          parentSessionId: parentID,
          agentName: (session?.title as string) ?? 'unknown',
        };
        this.cache.set(sessionId, mapping);
        this.logger.info('subagent.mapper.lazy_cached', {
          childSessionId: sessionId,
          parentSessionId: parentID,
        });
        return mapping;
      }

      // 确认是主 session
      this.cache.set(sessionId, null);
      return null;
    } catch (error) {
      this.logger.warn('subagent.mapper.query_failed', {
        sessionId,
        error: error instanceof Error ? error.message : String(error),
      });
      return null;
    }
  }

  /** 清空缓存（用于测试或重置） */
  clear(): void {
    this.cache.clear();
  }
}
```

- [ ] **Step 2: Commit**

```bash
git add plugins/agent-plugin/plugins/message-bridge/src/session/SubagentSessionMapper.ts
git commit -m "feat(message-bridge): add SubagentSessionMapper for child→parent session mapping"
```

---

### Task 2: 事件白名单新增 session.created

**Files:**
- Modify: `plugins/agent-plugin/plugins/message-bridge/src/contracts/upstream-events.ts`
- Modify: `plugins/agent-plugin/plugins/message-bridge/src/protocol/upstream/UpstreamEventExtractor.ts`

- [ ] **Step 1: upstream-events.ts 新增 session.created**

在 `upstream-events.ts` 中：

1. 新增 import：

```typescript
import type {
  EventSessionCreated,
} from '@opencode-ai/sdk' with { 'resolution-mode': 'import' };
```

添加到现有 `@opencode-ai/sdk` 的 import 块中。

2. `SUPPORTED_UPSTREAM_EVENT_TYPES` 数组中新增 `'session.created'`（在 `'session.idle'` 之后）。

3. 新增类型导出：

```typescript
export type SessionCreatedEvent = EventSessionCreated;
```

4. `SupportedUpstreamEvent` union 中新增 `| EventSessionCreated`。

- [ ] **Step 2: UpstreamEventExtractor.ts 新增 session.created 提取器**

在 `UpstreamEventExtractor.ts` 中：

1. 新增提取函数（在 `extractSessionIdleCommon` 附近）：

```typescript
function extractSessionCreatedCommon(
  event: SessionCreatedEvent,
): ExtractResult<CommonUpstreamFields> {
  const sessionId = event.properties?.info?.id;
  return requireNonEmptyString(sessionId, 'session.created', 'common', 'properties.info.id')
    ? ok({ eventType: 'session.created' as SupportedUpstreamEventType, toolSessionId: sessionId! })
    : fail({
        stage: 'common', code: 'missing_required_field',
        eventType: 'session.created', field: 'properties.info.id',
        message: 'Missing session ID in session.created event',
      });
}
```

2. `UPSTREAM_EVENT_EXTRACTORS` 映射表中新增条目：

```typescript
'session.created': { extractCommon: extractSessionCreatedCommon, extractExtra: noExtra },
```

- [ ] **Step 3: Commit**

```bash
git add plugins/agent-plugin/plugins/message-bridge/src/contracts/upstream-events.ts
git add plugins/agent-plugin/plugins/message-bridge/src/protocol/upstream/UpstreamEventExtractor.ts
git commit -m "feat(message-bridge): add session.created to supported upstream events"
```

---

### Task 3: ToolEventMessage 新增 subagent 字段

**Files:**
- Modify: `plugins/agent-plugin/plugins/message-bridge/src/contracts/transport-messages.ts`

- [ ] **Step 1: 修改 ToolEventMessage 接口**

在 `transport-messages.ts` 中，修改 `ToolEventMessage` 接口（约第 65-69 行）：

```typescript
export interface ToolEventMessage {
  type: 'tool_event';
  toolSessionId: string;
  subagentSessionId?: string;
  subagentName?: string;
  event: SupportedUpstreamEvent;
}
```

- [ ] **Step 2: Commit**

```bash
git add plugins/agent-plugin/plugins/message-bridge/src/contracts/transport-messages.ts
git commit -m "feat(message-bridge): add subagent fields to ToolEventMessage"
```

---

### Task 4: BridgeRuntime handleEvent 集成

**Files:**
- Modify: `plugins/agent-plugin/plugins/message-bridge/src/runtime/BridgeRuntime.ts`

- [ ] **Step 1: 新增 import 和成员变量**

在 BridgeRuntime.ts 的 import 区域新增：

```typescript
import { SubagentSessionMapper } from '../session/SubagentSessionMapper.js';
```

在类成员变量中（`toolDoneCompat` 附近）新增：

```typescript
private readonly subagentSessionMapper: SubagentSessionMapper;
```

在构造函数末尾（`this.actionRouter.setRegistry(this.registry)` 之前）新增：

```typescript
this.subagentSessionMapper = new SubagentSessionMapper(
  () => this.sdkClient,
  this.logger,
);
```

- [ ] **Step 2: 修改 handleEvent 方法**

在 `handleEvent` 方法中，`forwardingLogger.info('event.forwarding')` 之后、`const transportEvent = ...` 之前，插入 session.created 拦截逻辑：

```typescript
    // ===== Subagent: session.created 拦截（仅建立映射，不转发） =====
    if (normalized.common.eventType === 'session.created') {
      this.subagentSessionMapper.onSessionCreated(normalized.raw as {
        properties?: { info?: { id?: string; parentID?: string; title?: string } };
      });
      forwardingLogger.info('event.session_created_mapped');
      return;
    }
```

在 `const transportEvent = this.upstreamTransportProjector.project(normalized)` 之后、原有 `const transportEnvelope = { ... }` 之前，替换 transportEnvelope 构建逻辑：

```typescript
    const transportEvent = this.upstreamTransportProjector.project(normalized);

    // ===== Subagent: 子 session 事件映射重写 =====
    const subagentMapping = await this.subagentSessionMapper.resolve(
      normalized.common.toolSessionId,
    );

    const effectiveToolSessionId = subagentMapping
      ? subagentMapping.parentSessionId
      : normalized.common.toolSessionId;

    const transportEnvelope: Record<string, unknown> = {
      type: 'tool_event',
      toolSessionId: effectiveToolSessionId,
      event: transportEvent,
    };
    if (subagentMapping) {
      transportEnvelope.subagentSessionId = normalized.common.toolSessionId;
      transportEnvelope.subagentName = subagentMapping.agentName;
    }

    const originalEnvelope = {
      type: 'tool_event',
      toolSessionId: normalized.common.toolSessionId,
      event: normalized.raw,
    };
```

- [ ] **Step 3: 修改 session.idle 处理（防止子 session idle 误触父 session tool_done）**

在 `handleEvent` 方法末尾的 `session.idle` 处理块中，在 `const decision = ...` 之前插入子 session 跳过逻辑：

```typescript
    if (normalized.common.eventType === 'session.idle') {
      // 子 session 的 idle 不进入 ToolDoneCompat（避免误触父 session tool_done）
      if (subagentMapping) {
        forwardingLogger.debug('event.subagent_idle_skipped_tool_done', {
          childSessionId: normalized.common.toolSessionId,
          parentSessionId: subagentMapping.parentSessionId,
        });
        return;
      }
      const decision = this.toolDoneCompat.handleSessionIdle({
        toolSessionId: normalized.common.toolSessionId,
        logger: forwardingLogger,
      });
      if (decision.emit && decision.source) {
        this.sendToolDone(normalized.common.toolSessionId, undefined, decision.source, {
          logger: forwardingLogger,
          traceId: bridgeMessageId,
          gatewayMessageId: bridgeMessageId,
        });
      }
    }
```

注意：由于 `subagentMapping` 在前面已经 resolve 过了，这里直接使用即可。但要注意 `handleEvent` 方法需要变为 `async`（它已经是 async 的，因为 `resolve` 返回 Promise）。

- [ ] **Step 4: 更新 gatewayConnection.send 的 metadata**

在 `this.gatewayConnection.send(transportEnvelope, { ... })` 的 metadata 对象中，将 `toolSessionId` 改为 `effectiveToolSessionId`：

```typescript
    this.gatewayConnection.send(transportEnvelope, {
      traceId: bridgeMessageId,
      runtimeTraceId: this.logger.getTraceId(),
      gatewayMessageId: bridgeMessageId,
      toolSessionId: effectiveToolSessionId,
      eventType: normalized.common.eventType,
      opencodeMessageId: eventFields.opencodeMessageId,
      opencodePartId: eventFields.opencodePartId,
      toolCallId: eventFields.toolCallId ?? undefined,
      originalPayloadBytes: Buffer.byteLength(JSON.stringify(originalEnvelope), 'utf8'),
      transportPayloadBytes: Buffer.byteLength(JSON.stringify(transportEnvelope), 'utf8'),
    });
```

- [ ] **Step 5: 验证 Plugin 编译**

```bash
cd plugins/agent-plugin/plugins/message-bridge && npm run build
```

Expected: 编译成功，无类型错误。

- [ ] **Step 6: Commit**

```bash
git add plugins/agent-plugin/plugins/message-bridge/src/runtime/BridgeRuntime.ts
git commit -m "feat(message-bridge): integrate SubagentSessionMapper into handleEvent for child session rewriting"
```

---

## Phase 2: skill-server

### Task 5: 数据库迁移 — subagent 字段

**Files:**
- Create: `skill-server/src/main/resources/db/migration/V4__subagent_columns.sql`

- [ ] **Step 1: 创建迁移文件**

```sql
-- V4: Add subagent tracking columns to skill_message_part

ALTER TABLE skill_message_part
  ADD COLUMN subagent_session_id VARCHAR(64) NULL COMMENT 'OpenCode child session ID for subagent parts' AFTER finish_reason,
  ADD COLUMN subagent_name VARCHAR(128) NULL COMMENT 'Subagent name (e.g. code-reviewer)' AFTER subagent_session_id;

ALTER TABLE skill_message_part
  ADD INDEX idx_subagent_session (session_id, subagent_session_id);
```

注意：检查当前最新的迁移版本号。如果已有 V4，则使用 V5。可通过 `ls skill-server/src/main/resources/db/migration/` 确认。

- [ ] **Step 2: Commit**

```bash
git add skill-server/src/main/resources/db/migration/V4__subagent_columns.sql
git commit -m "feat(skill-server): add subagent columns to skill_message_part table"
```

---

### Task 6: StreamMessage 新增 subagent 字段

**Files:**
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/model/StreamMessage.java`

- [ ] **Step 1: 新增字段**

在 `StreamMessage.java` 的字段区域（`private List<Object> parts;` 之后、嵌套类之前）新增：

```java
    // ===== Subagent 字段 =====
    private String subagentSessionId;
    private String subagentName;
```

- [ ] **Step 2: Commit**

```bash
git add skill-server/src/main/java/com/opencode/cui/skill/model/StreamMessage.java
git commit -m "feat(skill-server): add subagent fields to StreamMessage"
```

---

### Task 7: GatewayMessageRouter 透传 subagent 字段

**Files:**
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/service/GatewayMessageRouter.java`

- [ ] **Step 1: handleToolEvent 中提取并传递 subagent 字段**

在 `handleToolEvent` 方法中，在调用 `translateEvent(node, sessionId)` 获得 `StreamMessage msg` 之后、在 `if (msg == null) return;` 之后，插入 subagent 字段注入：

```java
    // 注入 subagent 字段（Plugin 层映射重写后附加的）
    JsonNode subagentNode = node.path("subagentSessionId");
    if (subagentNode != null && !subagentNode.isMissingNode() && !subagentNode.isNull()) {
        msg.setSubagentSessionId(subagentNode.asText());
        msg.setSubagentName(node.path("subagentName").asText(null));
    }
```

- [ ] **Step 2: handlePermissionRequest 中提取 subagent 字段**

在 `handlePermissionRequest` 方法中，在调用 `translator.translatePermissionFromGateway(node)` 获得 `msg` 之后，插入相同的 subagent 字段注入：

```java
    // 注入 subagent 字段
    JsonNode subagentNode = node.path("subagentSessionId");
    if (subagentNode != null && !subagentNode.isMissingNode() && !subagentNode.isNull()) {
        msg.setSubagentSessionId(subagentNode.asText());
        msg.setSubagentName(node.path("subagentName").asText(null));
    }
```

- [ ] **Step 3: Commit**

```bash
git add skill-server/src/main/java/com/opencode/cui/skill/service/GatewayMessageRouter.java
git commit -m "feat(skill-server): pass through subagent fields in GatewayMessageRouter"
```

---

### Task 8: OpenCodeEventTranslator 提取 subagent 字段

**Files:**
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/service/OpenCodeEventTranslator.java`

- [ ] **Step 1: 修改 translate 方法**

当前 `translate(JsonNode event)` 只接收 `event` 节点（即 tool_event 中的 `event` 字段）。但 subagent 字段在外层 `node` 上。

有两种方案：
- (A) 在 `handleToolEvent` 中已经注入了 subagent 字段到 msg（Task 7 已完成），translator 不需要改动。
- (B) 如果 translator 需要处理，则需要传入外层 node。

**选择方案 A** — translator 不需要改动，subagent 字段由 `handleToolEvent` 在翻译后注入。Task 7 已覆盖此逻辑。

此 Task 无需修改 translator。但需确认 `translateQuestionAsked` 返回的 msg 也能接收 subagent 字段注入。由于 `handleToolEvent` 在 `translateEvent` 之后统一注入，所有事件类型都会被覆盖。

- [ ] **Step 2: 验证 question.asked 事件的 subagent 处理**

`question.asked` 事件通过 `translate()` → `translateQuestionAsked()` 路径处理，返回的 StreamMessage 在 `handleToolEvent` 中会被注入 subagent 字段。确认无遗漏。

同理 `permission.asked` 通过 `handlePermissionRequest` 路径处理（Task 7 Step 2 已覆盖）。

此 Task 标记为无需代码改动，验证通过即可。

---

### Task 9: SkillMessageController 支持 subagentSessionId

**Files:**
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/controller/SkillMessageController.java`

- [ ] **Step 1: PermissionReplyRequest 新增 subagentSessionId 字段**

找到 `PermissionReplyRequest`（可能是内部类或独立 DTO）。新增字段：

```java
private String subagentSessionId;
```

- [ ] **Step 2: replyPermission 方法中使用 subagentSessionId**

在 `replyPermission` 方法中，修改构建 payload 时的 `toolSessionId`：

```java
    // 使用子 session 真实 ID（如果是 subagent 的 permission）
    String targetToolSessionId = request.getSubagentSessionId() != null
            ? request.getSubagentSessionId()
            : session.getToolSessionId();

    String payload = PayloadBuilder.buildPayload(objectMapper, Map.of(
            "permissionId", permId,
            "response", request.getResponse(),
            "toolSessionId", targetToolSessionId));
```

- [ ] **Step 3: sendMessage 方法中处理 question reply 的 subagentSessionId**

在 `sendMessage` 方法中，检查 `SendMessageRequest` 是否有 `subagentSessionId` 字段。如果有，在 `routeToGateway` 构建 invoke payload 时使用它：

在 `SendMessageRequest` 中新增：

```java
private String subagentSessionId;
```

在 `routeToGateway` 或其调用处，如果 `request.getToolCallId() != null`（即 question reply），使用 subagentSessionId：

```java
    String targetToolSessionId = request.getSubagentSessionId() != null
            ? request.getSubagentSessionId()
            : session.getToolSessionId();
```

并将 `targetToolSessionId` 传入 payload 的 `toolSessionId` 字段。

- [ ] **Step 4: Commit**

```bash
git add skill-server/src/main/java/com/opencode/cui/skill/controller/SkillMessageController.java
git commit -m "feat(skill-server): support subagentSessionId in permission and question reply"
```

---

### Task 10: MessagePersistenceService 持久化 subagent 字段

**Files:**
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/service/MessagePersistenceService.java`
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/model/SkillMessagePart.java`（或对应的实体类）

- [ ] **Step 1: SkillMessagePart 实体新增 subagent 字段**

```java
private String subagentSessionId;
private String subagentName;
```

- [ ] **Step 2: 修改 persistPermissionPart 方法**

在 `SkillMessagePart.builder()` 链中新增：

```java
    .subagentSessionId(msg.getSubagentSessionId())
    .subagentName(msg.getSubagentName())
```

- [ ] **Step 3: 修改 persistToolPart / persistToolPartIfFinal 方法**

同样在 builder 链中新增 subagent 字段。

- [ ] **Step 4: 修改 persistTextPart、persistFilePart 方法**

同样新增 subagent 字段。所有 `SkillMessagePart.builder()` 调用都需要补充。

- [ ] **Step 5: 修改 MyBatis Mapper XML**

找到 `SkillMessagePartMapper.xml`（或 `SkillMessagePartRepository`），在 insert/upsert SQL 中新增 `subagent_session_id` 和 `subagent_name` 列。

在 select 的 resultMap 中新增对应映射。

- [ ] **Step 6: 验证编译**

```bash
cd skill-server && mvn compile -q
```

Expected: 编译成功。

- [ ] **Step 7: Commit**

```bash
git add skill-server/
git commit -m "feat(skill-server): persist subagent fields in message parts"
```

---

## Phase 3: skill-miniapp

### Task 11: 类型定义扩展

**Files:**
- Modify: `skill-miniapp/src/protocol/types.ts`

- [ ] **Step 1: StreamMessage 新增 subagent 字段**

在 `StreamMessage` 接口中（`fileMime` 之后、`messages` 之前）新增：

```typescript
  subagentSessionId?: string;
  subagentName?: string;
```

- [ ] **Step 2: MessagePart 新增 subtask 类型**

修改 `MessagePart` 的 `type` 字段，新增 `'subtask'`：

```typescript
  type: 'text' | 'thinking' | 'tool' | 'question' | 'permission' | 'file' | 'subtask';
```

新增 subtask 专用字段：

```typescript
  // Subtask (subagent) 专用字段
  subagentSessionId?: string;
  subagentName?: string;
  subagentPrompt?: string;
  subagentStatus?: 'running' | 'completed' | 'error';
  subParts?: MessagePart[];
```

- [ ] **Step 3: Commit**

```bash
git add skill-miniapp/src/protocol/types.ts
git commit -m "feat(skill-miniapp): add subagent fields to StreamMessage and MessagePart types"
```

---

### Task 12: SubtaskBlock 组件

**Files:**
- Create: `skill-miniapp/src/components/SubtaskBlock.tsx`

- [ ] **Step 1: 创建 SubtaskBlock 组件**

```tsx
// skill-miniapp/src/components/SubtaskBlock.tsx

import React, { useState, useMemo } from 'react';
import type { MessagePart } from '../protocol/types';
import { ToolCard } from './ToolCard';
import { ThinkingBlock } from './ThinkingBlock';

interface SubtaskBlockProps {
  part: MessagePart;
  onPermissionDecision?: (permissionId: string, response: string, subagentSessionId?: string) => void;
  onQuestionAnswer?: (answer: string, toolCallId?: string, subagentSessionId?: string) => void;
}

export const SubtaskBlock: React.FC<SubtaskBlockProps> = ({
  part,
  onPermissionDecision,
  onQuestionAnswer,
}) => {
  const [collapsed, setCollapsed] = useState(true);

  const status = part.subagentStatus ?? 'running';
  const subParts = part.subParts ?? [];

  const toolCount = useMemo(
    () => subParts.filter((p) => p.type === 'tool').length,
    [subParts],
  );

  const statusClass = `subtask-block--${status}`;
  const statusLabel =
    status === 'running' ? '运行中' : status === 'completed' ? '已完成' : '错误';

  const promptPreview =
    part.subagentPrompt && part.subagentPrompt.length > 50
      ? part.subagentPrompt.slice(0, 50) + '...'
      : part.subagentPrompt ?? '';

  return (
    <div className={`subtask-block ${statusClass}`}>
      <div
        className="subtask-block__header"
        onClick={() => setCollapsed(!collapsed)}
      >
        <div className="subtask-block__title">
          <span className="subtask-block__icon">🤖</span>
          <span className="subtask-block__agent-name">
            {part.subagentName ?? 'Subagent'}
          </span>
          <span className={`subtask-block__status-dot subtask-block__status-dot--${status}`} />
          <span className="subtask-block__status-label">{statusLabel}</span>
        </div>
        <div className="subtask-block__meta">
          <span className="subtask-block__prompt-preview">
            &quot;{promptPreview}&quot;
          </span>
          {toolCount > 0 && (
            <span className="subtask-block__tool-count">{toolCount} tools</span>
          )}
        </div>
        <span className="subtask-block__toggle">
          {collapsed ? '▶ 展开' : '▼ 收起'}
        </span>
      </div>

      {!collapsed && (
        <div className="subtask-block__content">
          {subParts.map((subPart, index) => {
            switch (subPart.type) {
              case 'text':
                return (
                  <div key={subPart.partId || index} className="subtask-block__text">
                    {subPart.content}
                  </div>
                );
              case 'thinking':
                return (
                  <ThinkingBlock
                    key={subPart.partId || index}
                    content={subPart.content}
                    isStreaming={subPart.isStreaming}
                  />
                );
              case 'tool':
                return (
                  <ToolCard
                    key={subPart.partId || index}
                    part={subPart}
                  />
                );
              default:
                return null;
            }
          })}
        </div>
      )}
    </div>
  );
};
```

注意：`ThinkingBlock` 和 `ToolCard` 的 props 需要匹配现有组件接口。实现时需检查这两个组件的实际 props 定义并适配。

- [ ] **Step 2: Commit**

```bash
git add skill-miniapp/src/components/SubtaskBlock.tsx
git commit -m "feat(skill-miniapp): add SubtaskBlock component for subagent display"
```

---

### Task 13: useSkillStream 集成 subagent 消息分发

**Files:**
- Modify: `skill-miniapp/src/hooks/useSkillStream.ts`

- [ ] **Step 1: 新增 handleSubagentMessage 函数**

在 `handleStreamMessage` 回调之前，新增 `handleSubagentMessage`：

```typescript
  const handleSubagentMessage = useCallback(
    (msg: StreamMessage) => {
      const { subagentSessionId, subagentName, type } = msg;
      if (!subagentSessionId) return;

      const messageId = msg.messageId ?? msg.sourceMessageId;
      if (!messageId) return;

      // 确保 subtask part 存在
      setMessages((prev) =>
        prev.map((message) => {
          if (message.id !== messageId) return message;
          const parts = message.parts ?? [];
          const hasSubtask = parts.some(
            (p) => p.type === 'subtask' && p.subagentSessionId === subagentSessionId,
          );
          if (!hasSubtask) {
            return {
              ...message,
              parts: [
                ...parts,
                {
                  partId: `subtask-${subagentSessionId}`,
                  type: 'subtask' as const,
                  content: '',
                  isStreaming: true,
                  subagentSessionId,
                  subagentName: subagentName ?? 'Subagent',
                  subagentPrompt: msg.content ?? '',
                  subagentStatus: 'running' as const,
                  subParts: [],
                },
              ],
            };
          }
          return message;
        }),
      );

      // 将消息内容追加到 subtask block 的 subParts 中
      const subPart = streamMessageToSubPart(msg);
      if (subPart) {
        setMessages((prev) =>
          prev.map((message) => {
            if (message.id !== messageId) return message;
            return {
              ...message,
              parts: (message.parts ?? []).map((p) => {
                if (p.type !== 'subtask' || p.subagentSessionId !== subagentSessionId) return p;
                return {
                  ...p,
                  subParts: [...(p.subParts ?? []), subPart],
                  subagentStatus: msg.status === 'completed' || msg.status === 'error'
                    ? msg.status
                    : p.subagentStatus,
                };
              }),
            };
          }),
        );
      }

      // permission/question 冒泡到主对话流
      if (type === 'permission.ask' || type === 'question') {
        applyStreamedMessage(msg);
      }
    },
    [applyStreamedMessage, setMessages],
  );
```

- [ ] **Step 2: 新增辅助函数 streamMessageToSubPart**

```typescript
function streamMessageToSubPart(msg: StreamMessage): MessagePart | null {
  switch (msg.type) {
    case 'text.delta':
    case 'text.done':
      return {
        partId: msg.partId ?? `text-${Date.now()}`,
        type: 'text',
        content: msg.content ?? '',
        isStreaming: msg.type === 'text.delta',
      };
    case 'thinking.delta':
    case 'thinking.done':
      return {
        partId: msg.partId ?? `thinking-${Date.now()}`,
        type: 'thinking',
        content: msg.content ?? '',
        isStreaming: msg.type === 'thinking.delta',
      };
    case 'tool.update':
      return {
        partId: msg.partId ?? msg.toolCallId ?? `tool-${Date.now()}`,
        type: 'tool',
        content: '',
        isStreaming: false,
        toolName: msg.toolName,
        toolCallId: msg.toolCallId,
        toolStatus: msg.status as MessagePart['toolStatus'],
        toolInput: msg.input,
        toolOutput: msg.output,
        toolTitle: msg.title,
      };
    default:
      return null;
  }
}
```

- [ ] **Step 3: 修改 handleStreamMessage，插入 subagent 分发**

在 `handleStreamMessage` 的 switch 语句之前插入：

```typescript
  const handleStreamMessage = useCallback(
    (msg: StreamMessage) => {
      // Subagent 消息分发
      if (msg.subagentSessionId) {
        handleSubagentMessage(msg);
        return;
      }

      // 现有逻辑不变
      switch (msg.type) {
        // ...
      }
    },
    [handleSubagentMessage, /* 现有依赖 */],
  );
```

- [ ] **Step 4: Commit**

```bash
git add skill-miniapp/src/hooks/useSkillStream.ts
git commit -m "feat(skill-miniapp): add subagent message handling in useSkillStream"
```

---

### Task 14: PermissionCard/QuestionCard 来源标注

**Files:**
- Modify: `skill-miniapp/src/components/PermissionCard.tsx`
- Modify: `skill-miniapp/src/components/QuestionCard.tsx`

- [ ] **Step 1: PermissionCard 新增 subagentName prop**

修改 PermissionCard 组件 props，新增 `subagentName`：

```tsx
interface PermissionCardProps {
  part: MessagePart;
  onDecision?: (permissionId: string, response: string, subagentSessionId?: string) => void;
  subagentName?: string;
  subagentSessionId?: string;
}
```

在卡片标题区域，渲染来源标注：

```tsx
{subagentName && (
  <span className="permission-card__source">[{subagentName}]</span>
)}
```

在 `handleDecision` 中传递 `subagentSessionId`：

```tsx
const handleDecision = (response: string) => {
  onDecision?.(part.permissionId!, response, subagentSessionId);
  // ...
};
```

- [ ] **Step 2: QuestionCard 新增 subagentName prop**

同理修改 QuestionCard：

```tsx
interface QuestionCardProps {
  part: MessagePart;
  onAnswer?: (answer: string, toolCallId?: string, subagentSessionId?: string) => void;
  subagentName?: string;
  subagentSessionId?: string;
}
```

标题区域添加来源标注，`handleSubmit` / `handleSelect` 中传递 `subagentSessionId`。

- [ ] **Step 3: 修改 MessageBubble 中的调用方**

找到 `MessageBubble.tsx`（渲染 PermissionCard/QuestionCard 的地方），在渲染时传入 `subagentName` 和 `subagentSessionId`：

```tsx
{part.type === 'permission' && (
  <PermissionCard
    part={part}
    onDecision={handlePermissionDecision}
    subagentName={msg.subagentName}
    subagentSessionId={msg.subagentSessionId}
  />
)}
```

注意：需要确认 `subagentName` 和 `subagentSessionId` 在 MessagePart 或 StreamMessage 上可用。如果 permission/question 冒泡时 StreamMessage 的 subagent 字段被保留，则可以从 msg 获取；否则需要从 MessagePart 上的字段获取（Task 11 中已在 MessagePart 中添加了 `subagentSessionId` 和 `subagentName`）。

- [ ] **Step 4: Commit**

```bash
git add skill-miniapp/src/components/PermissionCard.tsx
git add skill-miniapp/src/components/QuestionCard.tsx
git add skill-miniapp/src/components/MessageBubble.tsx
git commit -m "feat(skill-miniapp): add subagent source labels to PermissionCard and QuestionCard"
```

---

### Task 15: SubtaskBlock CSS 样式

**Files:**
- Modify: `skill-miniapp/src/index.css`

- [ ] **Step 1: 在 index.css 末尾添加 SubtaskBlock 样式**

```css
/* ===== SubtaskBlock ===== */

.subtask-block {
  border-left: 3px solid var(--border-color, #e0e0e0);
  margin: 8px 0;
  border-radius: 8px;
  background: var(--bg-subtle, #f8f9fa);
  overflow: hidden;
}

.subtask-block--running {
  border-left-color: var(--accent-color, #2196f3);
}

.subtask-block--completed {
  border-left-color: var(--success-color, #4caf50);
}

.subtask-block--error {
  border-left-color: var(--error-color, #f44336);
}

.subtask-block__header {
  padding: 10px 14px;
  cursor: pointer;
  display: flex;
  flex-direction: column;
  gap: 4px;
  user-select: none;
}

.subtask-block__header:hover {
  background: rgba(0, 0, 0, 0.03);
}

.subtask-block__title {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  font-weight: 500;
}

.subtask-block__icon {
  font-size: 14px;
}

.subtask-block__agent-name {
  color: var(--text-primary, #1a1a1a);
}

.subtask-block__status-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  display: inline-block;
}

.subtask-block__status-dot--running {
  background: var(--accent-color, #2196f3);
  animation: subtask-pulse 1.5s ease-in-out infinite;
}

.subtask-block__status-dot--completed {
  background: var(--success-color, #4caf50);
}

.subtask-block__status-dot--error {
  background: var(--error-color, #f44336);
}

@keyframes subtask-pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.4; }
}

.subtask-block__status-label {
  font-size: 12px;
  color: var(--text-secondary, #666);
}

.subtask-block__meta {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  color: var(--text-secondary, #666);
}

.subtask-block__prompt-preview {
  font-style: italic;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 300px;
}

.subtask-block__tool-count {
  color: var(--text-tertiary, #999);
}

.subtask-block__toggle {
  font-size: 11px;
  color: var(--text-secondary, #666);
  align-self: flex-end;
  margin-top: -20px;
}

.subtask-block__content {
  padding: 8px 14px 14px;
  border-top: 1px solid var(--border-color, #e0e0e0);
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.subtask-block__text {
  font-size: 13px;
  line-height: 1.5;
  color: var(--text-primary, #1a1a1a);
  white-space: pre-wrap;
}

/* Permission/Question 来源标注 */
.permission-card__source,
.question-card__source {
  font-size: 12px;
  color: var(--text-secondary, #666);
  margin-bottom: 4px;
  font-weight: 500;
}
```

- [ ] **Step 2: Commit**

```bash
git add skill-miniapp/src/index.css
git commit -m "feat(skill-miniapp): add SubtaskBlock CSS styles"
```

---

### Task 16: MessageBubble 集成 SubtaskBlock 渲染

**Files:**
- Modify: `skill-miniapp/src/components/MessageBubble.tsx`

- [ ] **Step 1: import SubtaskBlock**

```typescript
import { SubtaskBlock } from './SubtaskBlock';
```

- [ ] **Step 2: 在 parts 渲染中新增 subtask 分支**

找到 MessageBubble 中遍历 `message.parts` 渲染各类 part 的代码，新增：

```tsx
{part.type === 'subtask' && (
  <SubtaskBlock
    key={part.partId}
    part={part}
    onPermissionDecision={handlePermissionDecision}
    onQuestionAnswer={handleQuestionAnswer}
  />
)}
```

注意：`handlePermissionDecision` 和 `handleQuestionAnswer` 需要根据 MessageBubble 现有的 props/callback 机制适配。

- [ ] **Step 3: Commit**

```bash
git add skill-miniapp/src/components/MessageBubble.tsx
git commit -m "feat(skill-miniapp): render SubtaskBlock in MessageBubble"
```

---

## Phase 4: 集成验证

### Task 17: 端到端验证

- [ ] **Step 1: 启动所有服务**

```bash
# 启动 skill-server
cd skill-server && mvn spring-boot:run

# 启动 skill-miniapp
cd skill-miniapp && npm run dev

# 启动 plugin
cd plugins/agent-plugin && npm run dev
```

- [ ] **Step 2: 触发 subagent 场景**

在 OpenCode 中执行一个会调用 subagent 的操作（例如使用 Agent tool），观察：

1. Plugin 日志中出现 `subagent.mapper.cached` 日志
2. skill-server 收到带 `subagentSessionId` 的事件
3. miniapp 中出现 SubtaskBlock 折叠块
4. 子 agent 的 permission/question 冒泡到主对话流
5. 用户可以回复 permission/question，子 agent 继续执行

- [ ] **Step 3: 验证历史消息加载**

刷新 miniapp 页面，确认：
1. SubtaskBlock 从历史消息中正确还原
2. 已回复的 permission/question 显示正确状态

- [ ] **Step 4: 验证多并行 subagent**

触发多个并行 subagent，确认：
1. 每个 subagent 有独立的 SubtaskBlock
2. 各自的 permission/question 正确标注来源
3. 回复不会串到其他 subagent
