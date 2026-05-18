# Quality Guidelines

The miniapp does not currently ship an ESLint or Prettier workflow. The hard quality gates are TypeScript strictness, the production build, and keeping the protocol/UI boundary explicit.

## Required Commands

Run these from the repo root:

```bash
cd skill-miniapp && npm run typecheck
cd skill-miniapp && npm run build
```

The scripts are defined in `skill-miniapp/package.json`:

```json
{
  "scripts": {
    "dev": "vite",
    "build": "tsc && vite build",
    "preview": "vite preview",
    "typecheck": "tsc --noEmit"
  }
}
```

## Runtime Test Infrastructure — Current Status

> **Fact**: `skill-miniapp` ships **no runtime test infrastructure**. `package.json` exposes only `dev / build / preview / typecheck`. Under `skill-miniapp/src/` there are zero `*.test.*` / `*.spec.*` files and no `jest` / `vitest` / `@testing-library` dependency declared.

Tasks whose acceptance criteria call for "miniapp unit tests" must pick one of two paths:

**Path A — Static-guarantee substitute (default for additive, contract-driven tasks)**

When the new code is a thin pass-through across the protocol → hook → component → api layers, the following combination is usually sufficient and does **not** require introducing a test runner:

1. Tighten the TypeScript signature at every hop so that omitting the field is a compile error. Push the new property into the shared types in `skill-miniapp/src/protocol/types.ts`, then thread it through the consuming hook return type and every component callback in the call chain.
2. Use conditional pass-through at the wire boundary (`if (value && value.trim()) body.field = value`) so that an undefined upstream state stays undefined downstream — this preserves backward-compatible request shapes without needing a negative-case unit test.
3. Grep the codebase for the **old** field name (e.g. `toolCallId`, `permissionId`) and verify each consumer either keeps using it or has been migrated; no callsite should silently lose the old field.
4. Confirm `npm run typecheck` and `npm run build` are green.

This was the path used by `personal-question-requestid` (the 13-anchor `requestId` propagation chain) — every hop became compile-required, and the historical-replay fallback was guaranteed structurally by **not** updating `history.ts`.

**Path B — Introduce vitest (treat as infrastructure subtask)**

Path B is justified only when the task adds **≥3 distinct pieces of runtime logic that TypeScript cannot statically constrain**. Typical examples:

- Stateful assembler / cache logic where the bug surface is "wrong field combined into the right type"
- Time-sensitive UI flows (debouncing, optimistic-then-confirm)
- Error-recovery / retry behavior that branches on response payload shape
- Localization, formatting, or parsing pipelines with multi-input fan-out

When triggered, scope the introduction as **its own task** in `.trellis/tasks/`. Do not bury "stand up vitest" inside an unrelated feature PRD. The cost (dependencies, tsconfig split, CI wiring) deserves its own review surface.

### Acceptance Criteria Wording

When writing PRDs for miniapp tasks, prefer phrasing acceptance criteria as "this hop's signature must require the new field at compile time" or "this callsite must conditionally include the field in the body", rather than "add a unit test for X". The former is enforced by `tsc --noEmit`; the latter is currently unenforceable until Path B fires.

## Core Review Checklist

- `npm run typecheck` passes before review.
- `npm run build` passes before review.
- No `any` is introduced into `protocol/`, `hooks/`, or `utils/api.ts`.
- New `MessagePart.type` or `StreamMessage.type` branches are handled in every required switch.
- Hooks convert transport failures into `error` state instead of silently swallowing them.
- Optimistic user messages and permission replies preserve message identity rather than replacing the whole conversation blindly.

## Typed Error Contract

REST failures are supposed to become `ApiError` and then surface in hook `error` state. Both transport-level failures (`!res.ok`) and business-level failures (`body.code !== 0` on a 200) use the same `ApiError` class; the `body` property carries the envelope for later narrowing.

From `skill-miniapp/src/utils/api.ts`:

```ts
if (!res.ok) {
  let body: unknown;
  try {
    body = await res.json();
  } catch {
    /* ignore parse errors */
  }

  const errorText =
    body !== null &&
    typeof body === 'object' &&
    'errormsg' in body &&
    typeof body.errormsg === 'string'
      ? body.errormsg
      : res.statusText;

  throw new ApiError(res.status, errorText, body);
}
```

From `skill-miniapp/src/hooks/useSkillSession.ts`:

```ts
try {
  const res = await api.getSessions(0, 100);
  setSessions(res.content);
} catch (err) {
  const message =
    err instanceof Error ? err.message : 'Failed to load sessions';
  setError(message);
} finally {
  setLoading(false);
}
```

Do not downgrade these errors to untyped strings inside `api.ts`; the hook layer is the right place to map them for UI.

## Business Code 410 Contract (Assistant Deleted)

The skill-server returns HTTP 200 with `body.code === 410` when the target assistant has been deleted (see `skill-server` commit `519648f`). Every write-path hook (`createSession`, `sendMessage`, `replyPermission`) must branch on this code before showing a generic error. The duplicated helper is intentional so each hook is self-contained.

From `skill-miniapp/src/hooks/useSkillStream.ts`:

```ts
function extractBusinessCode(err: unknown): number | undefined {
  if (err instanceof ApiError && err.body && typeof err.body === 'object' && 'code' in err.body) {
    const code = (err.body as { code?: unknown }).code;
    return typeof code === 'number' ? code : undefined;
  }
  return undefined;
}
```

From `skill-miniapp/src/hooks/useSkillSession.ts` (`createSession`):

```ts
} catch (err) {
  if (extractBusinessCode(err) === 410) {
    setError('该助理已被删除');
  } else {
    const message =
      err instanceof Error ? err.message : 'Failed to create session';
    setError(message);
  }
  return null;
}
```

Review checklist additions:
- Any new REST-calling hook must handle `extractBusinessCode(err) === 410` before the generic branch.
- PRD decision `Q-arch-4`: on 410 the optimistic user message and optimistic permission state must not be rolled back. The user sees their input plus the "该助理已被删除" banner.

## Message Part Extension Checklist

Whenever you add or change an assistant part shape, review these files together:
- `skill-miniapp/src/protocol/types.ts`
- `skill-miniapp/src/protocol/StreamAssembler.ts`
- `skill-miniapp/src/protocol/history.ts`
- `skill-miniapp/src/components/MessageBubble.tsx`
- `skill-miniapp/src/components/SubtaskBlock.tsx` if the part can appear inside subagent output

This is the live renderer switch in `MessageBubble.tsx`:

```tsx
switch (part.type) {
  case 'thinking':
    return <ThinkingBlock key={part.partId} part={part} />;
  case 'tool':
    return <ToolCard key={part.partId} part={part} />;
  case 'question':
    return <QuestionCard key={part.partId} part={part} onAnswer={onQuestionAnswer} />;
  case 'permission':
    return <PermissionCard key={part.partId} part={part} onDecision={onPermissionDecision} />;
  case 'subtask':
    return (
      <SubtaskBlock
        key={part.partId}
        part={part}
        onPermissionDecision={onPermissionDecision}
        onQuestionAnswer={onQuestionAnswer}
      />
    );
```

If that switch or the protocol switches are missing a case, the UI is incomplete even if TypeScript still compiles.

## Anti-Patterns

- Do not use `any` in the protocol layer. Use `unknown` plus narrowing.
- Do not re-fetch whole conversations after every stream event; let `StreamAssembler` and `upsertMessage()` update incrementally.
- Do not add UI-only merge heuristics for assistant messages.
- Do not move normalization logic into presentational components.
- Do not introduce a state library just to avoid passing props through one page-level component.

## Cautionary Tale: Do Not Reintroduce Smart Message Merging

The release-0401 line removed consecutive assistant-message merge logic (`4e3962d`, with the follow-up commit title recorded as removing consecutive assistant merge behavior). Treat that as a standing rule:

- message grouping belongs in `protocol/history.ts` or `protocol/StreamAssembler.ts`,
- UI components should render the message list they are given,
- preserving `message.id` and `messageSeq` is more important than trying to make the chat look "cleaner" by collapsing records in the view layer.

## Manual Scenarios Worth Rechecking

- Start a new session from `app.tsx` and verify the first pending message is sent after the socket becomes ready.
- Switch sessions and verify history loads without leaving stale streaming state behind.
- Answer a question card and verify the tool call ID stays attached.
- Reply to a permission card and verify both the main message and subtask permission states update.
- Highlight assistant text in `SkillMain.tsx` and verify send-to-IM still works.
