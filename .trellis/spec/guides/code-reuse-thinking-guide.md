# Code Reuse Thinking Guide

> **Purpose**: Stop and think before creating new code - does it already exist?

---

## The Problem

**Duplicated code is the #1 source of inconsistency bugs.**

When you copy-paste or rewrite existing logic:
- Bug fixes don't propagate
- Behavior diverges over time
- Codebase becomes harder to understand

---

## Before Writing New Code

### Step 1: Search First

```bash
# Search for similar function names
rg -n "functionName" .

# Search for similar logic
rg -n "keyword" .
```

### Step 2: Ask These Questions

| Question | If Yes... |
|----------|-----------|
| Does a similar function exist? | Use or extend it |
| Is this pattern used elsewhere? | Follow the existing pattern |
| Could this be a shared utility? | Create it in the right place |
| Am I copying code from another file? | **STOP** - extract to shared |

---

## Common Duplication Patterns

### Pattern 1: Copy-Paste Functions

**Bad**: Copying a validation function to another file

**Good**: Extract to shared utilities, import where needed

### Pattern 2: Similar Components

**Bad**: Creating a new component that's 80% similar to existing

**Good**: Extend existing component with props/variants

### Pattern 3: Repeated Constants

**Bad**: Defining the same constant in multiple files

**Good**: Single source of truth, import everywhere

---

## Shared Concepts In This Project

### Session domain and type constants

The backend owns the canonical session-domain and session-type values in `skill-server/src/main/java/com/opencode/cui/skill/model/SkillSession.java` (`DOMAIN_MINIAPP`, `DOMAIN_IM`, `SESSION_TYPE_GROUP`, `SESSION_TYPE_DIRECT`).
The miniapp only mirrors the values it actively sends during session creation in `skill-miniapp/src/constants/session.ts`.
If you add a new session domain or type, extend the backend constants first and only add a frontend mirror if the UI must actually send or branch on it.

### MDC keys and trace propagation

Shared MDC key names live in `ai-gateway/src/main/java/com/opencode/cui/gateway/logging/MdcConstants.java` and `skill-server/src/main/java/com/opencode/cui/skill/logging/MdcConstants.java`, and both services print the same keys in `log4j2-spring.xml`.
If you need a new cross-service logging dimension, put it in the constants/helper/pattern trio instead of sprinkling raw `MDC.put(...)` calls through handlers and controllers.

### Error response envelopes

Both backends already standardize the REST wrapper as `ApiResponse<T>` with `code`, `errormsg`, and `data` in `ai-gateway/.../ApiResponse.java` and `skill-server/.../ApiResponse.java`.
The miniapp request helper in `skill-miniapp/src/utils/api.ts` automatically unwraps exactly that shape.
If you need a new error envelope, change the shared wrapper and the frontend unwrapping logic together; do not invent a controller-local format.

---

## When to Abstract

**Abstract when**:
- Same code appears 3+ times
- Logic is complex enough to have bugs
- Multiple people might need this

**Don't abstract when**:
- Only used once
- Trivial one-liner
- Abstraction would be more complex than duplication

---

## After Batch Modifications

When you've made similar changes to multiple files:

1. **Review**: Did you catch all instances?
2. **Search**: Run `rg` to find any missed
3. **Consider**: Should this be abstracted?

---

## Search-First Examples From This Repo

Before adding a new stream or message-part type, run:

```bash
rg -n "StreamMessage.Types|StreamMessageType|MessagePart|handleMessage\\(|normalizeIncomingStreamMessage|normalizePart\\(" skill-server skill-miniapp
```

Why: type registration is split across backend DTOs, live websocket handling, assembler logic, and history normalization.

Before adding a new invoke payload field or relay envelope field, run:

```bash
rg -n "InvokeCommand|sendInvokeToGateway|buildInvokeMessage|payloadFields.put|extractField\\(" skill-server ai-gateway
```

Why: fields such as `assistantAccount`, `toolSessionId`, and `sendUserAccount` are written in one subsystem and unpacked in another.

Before changing session domain or type values, run:

```bash
rg -n "DOMAIN_|SESSION_TYPE_|MINIAPP_SESSION_" skill-server skill-miniapp
```

Why: the backend owns the canonical values, but the miniapp mirrors the subset it sends during session creation.

Before inventing another REST envelope or error parser, run:

```bash
rg -n "ApiResponse|errormsg|code, errormsg, data" ai-gateway skill-server skill-miniapp
```

Why: the miniapp already assumes the shared `ApiResponse` contract exists.

---

## Gotcha: Asymmetric Mechanisms Producing Same Output

**Problem**: When two different mechanisms must produce the same file set (e.g., recursive directory copy for init vs. manual `files.set()` for update), structural changes (renaming, moving, adding subdirectories) only propagate through the automatic mechanism. The manual one silently drifts.

**Symptom**: Init works perfectly, but update creates files at wrong paths or misses files entirely.

**Prevention checklist**:
- [ ] When migrating directory structures, search for ALL code paths that reference the old structure
- [ ] If one path is auto-derived (glob/copy) and another is manually listed, the manual one needs updating
- [ ] Add a regression test that compares outputs from both mechanisms

---

## Checklist Before Commit

- [ ] Searched for existing similar code
- [ ] No copy-pasted logic that should be shared
- [ ] Constants defined in one place
- [ ] Similar patterns follow same structure
