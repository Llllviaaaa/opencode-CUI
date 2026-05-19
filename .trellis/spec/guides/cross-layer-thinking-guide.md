# Cross-Layer Thinking Guide

> **Purpose**: Think through data flow across layers before implementing.

---

## The Problem

**Most bugs happen at layer boundaries**, not within layers.

Common cross-layer bugs:
- API returns format A, frontend expects format B
- Database stores X, service transforms to Y, but loses data
- Multiple layers implement the same logic differently

---

## Before Implementing Cross-Layer Features

### Step 1: Map the Data Flow

Draw out how data moves:

```
Source → Transform → Store → Retrieve → Transform → Display
```

For each arrow, ask:
- What format is the data in?
- What could go wrong?
- Who is responsible for validation?

### Step 2: Identify Boundaries

| Boundary | Common Issues |
|----------|---------------|
| API ↔ Service | Type mismatches, missing fields |
| Service ↔ Database | Format conversions, null handling |
| Backend ↔ Frontend | Serialization, date formats |
| Component ↔ Component | Props shape changes |

### Step 3: Define Contracts

For each boundary:
- What is the exact input format?
- What is the exact output format?
- What errors can occur?

---

## Common Cross-Layer Mistakes

### Mistake 1: Implicit Format Assumptions

**Bad**: Assuming date format without checking

**Good**: Explicit format conversion at boundaries

### Mistake 2: Scattered Validation

**Bad**: Validating the same thing in multiple layers

**Good**: Validate once at the entry point

### Mistake 3: Leaky Abstractions

**Bad**: Component knows about database schema

**Good**: Each layer only knows its neighbors

---

## Real Cross-Layer Flows In This Project

### Session creation: miniapp -> skill-server -> route cache -> gateway

`skill-miniapp/src/pages/SkillMain.tsx` creates sessions with `MINIAPP_SESSION_DOMAIN` and `MINIAPP_SESSION_TYPE`, then calls `createSession(...)` from `skill-miniapp/src/utils/api.ts`.
`skill-server/src/main/java/com/opencode/cui/skill/controller/SkillSessionController.java` accepts that payload and forwards it to `SkillSessionService.createSession(...)`.
That service persists the session and immediately claims Redis ownership through `SessionRouteService.createRoute(...)`, so the current skill-server instance can answer later stream traffic.
If the assistant still needs a tool session, the same controller builds an `InvokeCommand` and sends `GatewayActions.CREATE_SESSION` through `GatewayRelayService.sendInvokeToGateway(...)`, which depends on the gateway WebSocket bridge in `GatewayWSClient`.

### Assistant stream: ai-gateway -> skill-server -> miniapp websocket -> assembled UI

`ai-gateway/src/main/java/com/opencode/cui/gateway/ws/AgentWebSocketHandler.java` receives PCAgent messages and forwards tool events, permission requests, and session updates to skill-server via `handleRelayToSkillServer(...)`.
On the skill-server side, `GatewayRelayService.handleGatewayMessage(...)` restores MDC and dispatches by message type, while `OpenCodeEventTranslator.translate(...)` converts raw OpenCode events into frontend-facing `StreamMessage` DTOs.
`SkillStreamHandler` subscribes per user, reads Redis broadcasts in `handleUserBroadcast(...)`, stamps transport sequence numbers in `pushStreamMessageToUser(...)`, and sends snapshot or streaming state on reconnect.
The miniapp socket in `skill-miniapp/src/hooks/useSkillStream.ts` normalizes each incoming message, routes it through `handleStreamMessage(...)`, and feeds `StreamAssembler.handleMessage(...)` so components render stable parts instead of raw deltas.

### Permission flow: translated ask -> rendered card -> reverse invoke path

Permission asks can come from gateway events (`OpenCodeEventTranslator.translatePermissionFromGateway(...)`) or normal translator output, but both end up as `StreamMessage.Types.PERMISSION_ASK`.
`useSkillStream` treats that as normal streamed state, `StreamAssembler.handleMessage(...)` creates a permission part, and `skill-miniapp/src/components/PermissionCard.tsx` lets the user answer without knowing anything about Redis, gateway routing, or tool sessions.
The reverse path starts in `PermissionCard`, goes through `useSkillStream.replyPermissionFn(...)` and `api.replyPermission(...)`, lands in `SkillMessageController.replyPermission(...)`, and immediately fans out in two directions: `GatewayRelayService.sendInvokeToGateway(...)` sends the real `permission_reply` back to OpenCode, while `gatewayRelayService.publishProtocolMessage(...)` publishes a local `permission.reply` message so the UI resolves the card immediately.

### Selected text -> IM forward: UI affordance -> session API -> IM platform

`skill-miniapp/src/components/SendToImButton.tsx` is a pure UI affordance that only knows how to capture selected text and call `onSend(...)`.
`skill-miniapp/src/hooks/useSendToIm.ts` turns that into `api.sendToIm(...)`, and `SkillMain` wires the selected text from the assistant message area into that hook.
On the server side, `SkillMessageController.sendToIm(...)` resolves the IM chat ID from the request or from the session's business session ID, then hands off to `imMessageService.sendMessage(...)`.
This is a useful reminder that UI-level actions may still depend on backend session metadata that was created much earlier in the session-creation flow.

---

## Worked Example: Adding A Field That Crosses Layers

Use two templates, because not every "new field" is the same kind of change.

### Transport-only field: `senderUserAccount`

In the current codebase, `senderUserAccount` is a real cross-service envelope field, but it is not a miniapp field.
It is defined on inbound DTOs such as `skill-server/src/main/java/com/opencode/cui/skill/model/ExternalInvokeRequest.java` and `ImMessageRequest.java`, validated in `ExternalInboundController.java` and `ImInboundController.java`, carried through `InboundProcessingService.java`, and serialized into outbound invoke payloads as `sendUserAccount`.
For business-scope assistants, `BusinessScopeStrategy.java` maps that payload into the cloud request body, and the gateway side expects the equivalent concept as `userAccount` in `ai-gateway/src/main/java/com/opencode/cui/gateway/model/ImPushRequest.java` and `CloudPushController.java`.
The lesson is structural: decide first whether the field is transport-only or user-visible.
If a field never leaves the service or gateway boundary, adding it to `skill-miniapp/src/protocol/types.ts` would create drift, not completeness.

### User-visible field: `permission.reply.response`

When a field must reach the UI, the path is longer.
`SkillMessageController.replyPermission(...)` emits `StreamMessage.Types.PERMISSION_REPLY` with `PermissionInfo.response`, the frontend mirror for that value lives in `skill-miniapp/src/protocol/types.ts` as `StreamMessage.response`, and the live consumer path runs through `useSkillStream.replyPermissionFn(...)` plus `StreamAssembler.handleMessage(...)`, which stores it as `permissionResponse`.
`PermissionCard.tsx` then renders that resolved state.
That is the real checklist for any UI-visible stream field: backend DTO, producer or translator, websocket consumer and assembler, then the rendering component.

---

## If You Change `StreamMessage`, What Else Must Change?

- [ ] Update the server DTO and type constants in `skill-server/src/main/java/com/opencode/cui/skill/model/StreamMessage.java`.
- [ ] Search every producer, not just one translator. Common sources are `OpenCodeEventTranslator.java`, `CloudEventTranslator.java`, `SkillMessageController.java`, `InboundProcessingService.java`, and `GatewayRelayService.java`.
- [ ] Check delivery and replay paths in `SkillStreamHandler.java`, `SnapshotService.java`, and `MessagePersistenceService.java` so reconnects and history do not drift from live stream behavior.
- [ ] Update the frontend mirror in `skill-miniapp/src/protocol/types.ts`.
- [ ] Update message normalization and switch handling in `skill-miniapp/src/hooks/useSkillStream.ts`.
- [ ] Update both live assembly and history assembly in `skill-miniapp/src/protocol/StreamAssembler.ts` and `skill-miniapp/src/protocol/history.ts`.
- [ ] Update the renderer only if the field is user-visible. Typical examples are `PermissionCard.tsx`, `SubtaskBlock.tsx`, `ToolCard.tsx`, and message bubble helpers.
- [ ] Re-read the transport-only vs user-visible decision before you touch the miniapp. Not every backend field belongs in `types.ts`.
- [ ] If the field crosses into the agent-plugin submodule (e.g. `personal` scope `question_reply` / `permission_reply` / `chat` payloads), also update `plugins/agent-plugin/plugins/message-bridge/src/protocol/downstream/DownstreamMessageNormalizer.ts` — see "Hidden Reshape Steps" below.

---

## Hidden Reshape Steps (The Normalizer Trap)

Adding a field at every "obvious" layer (DTO, controller, contract, action, adapter, types, hook, component) is **not enough** if any intermediate layer reshapes payloads through a whitelist / schema / normalizer / projection. That intermediate layer silently drops unknown fields. You see no compile error and no runtime error — the new field just disappears.

In this project the canonical example is `plugins/agent-plugin/plugins/message-bridge/src/protocol/downstream/DownstreamMessageNormalizer.ts`. It rebuilds inbound payloads field by field (e.g. `normalizeQuestionReplyPayload` returns a hand-written `{ toolSessionId, answer, toolCallId? }` shape). Unknown fields like `requestId` are not rejected — they are just **not copied into the output**. Downstream `QuestionReplyAction` / `OpencodeSessionGatewayAdapter` never see them. Three layers changed, zero behavior delta.

### Pre-modification grep (before adding a field across layers)

Run these searches and answer each hit before declaring "I have the full file list":

| Grep | Intent |
|---|---|
| `normalize`, `Normalizer` | hand-written payload reshaping |
| `whitelist`, `allow.?list`, `pick`, `pickFields` | explicit field allowlists |
| `schema.parse`, `z.object`, `assertValid`, `validate` | schema-driven payload rebuild |
| `projection`, `project`, `toDto`, `to[A-Z]\w+Dto` | DTO projection layers |
| `JsonInclude`, `@JsonIgnore`, `FAIL_ON_UNKNOWN_PROPERTIES` | Jackson allow/deny shaping |
| `Pick<`, `Omit<` (TS) over the payload type | type-level reshape that hints at runtime reshape |

For every hit, ask: **does this code copy fields explicitly, or spread the whole object?** If explicit, the new field must be added here too.

### Per-layer in/out field table

For each hop in the data flow, write a two-column "in" / "out" field list — even one line per layer is enough. If the "out" column drops a field that appears in "in", that layer is a reshape layer and must be updated.

### Required test on the reshape layer

Add a normalizer-level unit test for the new field with three cases: (1) field present and non-empty → output contains it; (2) field blank → output omits it (or whatever the project's blank policy is); (3) field absent → output omits it (locks the legacy contract). This test is the line of defense that prevents the "three layers changed, zero behavior delta" failure mode from recurring.

---

## Multi-Producer Envelope Consistency

A single wire-layer envelope (e.g. a `chat` invoke payload, a `permission.reply` stream message) is often built by **multiple independent producers** in the codebase — fast-path vs slow-path, sync vs retry, online vs offline. Each producer hand-assembles fields. When a new field gets added to one producer but not the others, the envelope **shape drifts**: downstream observers see "same event type, different field set, depending on which code path emitted it." The bug is invisible until someone debugs a log diff between two production sessions.

In this project the canonical example is the `chat` invoke payload, built independently by `InboundProcessingService.dispatchChatToGateway` (sync path) and `GatewayMessageRouter.retryPendingMessages` (async retry path after `session_created` callback). Both must emit the same 6 fields (`text`, `toolSessionId`, `assistantAccount`, `sendUserAccount`, `imGroupId`, `messageId`, `businessExtParam`) — but they don't share construction code.

### Pre-modification check: list all producers of the envelope

Before adding / removing a field on a wire-layer envelope, grep for **all** call sites that construct it:

| Pattern to grep | What you're looking for |
|---|---|
| Action / type constant (e.g. `"chat"`, `Types.PERMISSION_REPLY`) | Every place that mentions this envelope type |
| Builder method (e.g. `objectMapper.createObjectNode()` near the type constant) | Every hand-assembly site |
| `sendInvokeToGateway` / `emitToClient` / `publishProtocolMessage` calls | Every wire-out call |

If you find **2+ producers**, you must update **all** of them, not just the one that triggered your task.

### Field-parity table

For each envelope with multiple producers, maintain a small table in the PRD or commit message:

| Field | Producer A (`dispatchChatToGateway`) | Producer B (`retryPendingMessages`) |
|---|---|---|
| `text` | known from request | from Redis pending |
| `assistantAccount` | known | from Redis pending (or session fallback) |
| `sendUserAccount` | known (group: real sender, direct: owner) | from Redis pending (or session.userId fallback) |
| ... | ... | ... |

A column that has "missing" or "?" is a drift risk and must be resolved (either fill it, or add explicit null with a documented reason).

### Required test: snapshot the envelope from each producer

For each producer, write a unit test that asserts the **exact field set** of the emitted envelope (use `assertEquals` on field names sorted alphabetically). When a new field is added but not propagated to all producers, exactly the producers missing the field will fail their snapshot test.

---

## Checklist for Cross-Layer Features

Before implementation:
- [ ] Mapped the complete data flow
- [ ] Identified all layer boundaries
- [ ] Defined format at each boundary
- [ ] Decided where validation happens

After implementation:
- [ ] Tested with edge cases (null, empty, invalid)
- [ ] Verified error handling at each boundary
- [ ] Checked data survives round-trip

---

## When to Create Flow Documentation

Create detailed flow docs when:
- Feature spans 3+ layers
- Multiple teams are involved
- Data format is complex
- Feature has caused bugs before
