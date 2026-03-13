# Solution Design

## 1. Design Decision

The current design uses a boundary-normalization architecture:

```text
contracts -> protocol -> runtime -> action
```

This replaces the older model where runtime and actions both interpreted raw protocol payloads.

## 2. Why This Design

This structure solves four concrete problems:

1. duplicated schema ownership across runtime and actions
2. weak boundary visibility for upstream and downstream contracts
3. inconsistent raw field parsing across modules
4. higher regression risk when protocol fields evolve

## 3. Layer Responsibilities

### 3.1 `contracts`

Defines external interaction contracts only:

- upstream OpenCode events
- downstream gateway messages
- transport messages
- envelope

### 3.2 `protocol`

Owns schema normalization:

- `protocol/upstream` extracts and validates upstream events
- `protocol/downstream` normalizes downstream commands

This is the only layer allowed to read raw protocol fields.

### 3.3 `runtime`

Owns:

- config resolution
- connection lifecycle
- protocol invocation
- action routing
- transport message sending

It does not own schema.

### 3.4 `action`

Owns:

- business execution
- SDK calls
- state checks
- error mapping

It does not normalize payloads.

## 4. Upstream Design

The upstream path is:

```text
raw OpenCode event
  -> EventFilter
  -> extractUpstreamEvent()
  -> runtime
  -> tool_event
```

Supported event types are explicit. There is no wildcard default allowlist.

## 5. Downstream Design

The downstream path is:

```text
raw gateway message
  -> normalizeDownstreamMessage()
  -> runtime
  -> action.execute()
  -> transport response
```

Supported actions:

- `chat`
- `create_session`
- `close_session`
- `permission_reply`
- `status_query`
- `abort_session`
- `question_reply`

## 6. Compatibility Rules

The refactor preserves external behavior:

- transport message shapes remain stable
- `tool_event` remains `{ type, toolSessionId, event }`
- `tool_error`, `session_created`, `status_response` retain existing semantics
- compatibility fields such as `welinkSessionId` remain supported where already used

## 6.1 Tool Done Compatibility Design

This section defines the target-state design for the planned `tool_done`
compatibility recovery. The current implementation still does not emit
`tool_done` until that recovery is implemented.

### Problem Statement

- current `message-bridge` does not emit `tool_done`
- legacy `pc-agent` emits `tool_done` as a completion signal
- the UI currently consumes `tool_done`
- forwarding `session.idle` as `tool_event` remains required, but it does not replace the compatibility contract expected by older consumers

### Architectural Position

Restoring `tool_done` is a compatibility decision, not a new core domain model.

The core facts remain:

- downstream invoke actions complete successfully
- upstream session lifecycle events such as `session.idle` and `session.status` continue to flow through the normalized event path

`tool_done` is therefore treated as a compatibility projection derived from those facts. It must not become a first-class runtime truth and must not push compatibility rules back into the `protocol` layer.

Acceptable approach:

- a dedicated compat module decides when to synthesize `tool_done`

Non-acceptable approaches:

- hardcoding `tool_done` decisions directly inside `handleEvent()`
- letting each action success branch compose its own independent `tool_done` behavior
- treating `tool_done` as the canonical completion event of the bridge

### Layer Ownership

- `contracts`
  - retain the existing `tool_done` transport shape
  - do not introduce a new completion message type
- `protocol`
  - does not decide whether `tool_done` should be emitted
  - does not encode compatibility inference rules
- `runtime`
  - invokes the compat layer at explicit lifecycle boundaries only
  - does not own the compatibility decision logic
- `runtime/compat`
  - is the single owner of `tool_done` trigger, deduplication, fallback, and compat logging
- `action`
  - continues to own SDK execution only
  - does not own compatibility event synthesis

### Trigger Model

- proactive completion trigger:
  - `chat`
  - `question_reply`
  - `permission_reply`
- non-completion actions do not trigger `tool_done`:
  - `create_session`
  - `close_session`
  - `abort_session`
- fallback trigger:
  - when upstream `session.idle` arrives and no `tool_done` has been emitted for the same execution, the compat layer emits one

### Deduplication Model

- one execution emits at most one `tool_done`
- if an action success path already emitted `tool_done`, the later `session.idle` still forwards as `tool_event` but does not emit a second `tool_done`
- if no proactive emission happened, `session.idle` becomes the fallback emission point
- deduplication state is owned only by the compat layer

### Compatibility Output Semantics

- `session.idle -> tool_event` remains unchanged
- `tool_done` is restored as a UI-facing compatibility completion signal in the target design
- `tool_done` does not replace `tool_event(session.idle)`; both may coexist
- `tool_done` does not require `usage` in this recovery

### Logging

Compatibility logs must be explicit so they are not confused with primary protocol logs.

Recommended log events:

- `compat.tool_done.sent`
- `compat.tool_done.skipped_duplicate`
- `compat.tool_done.fallback_from_idle`

Recommended fields:

- `toolSessionId`
- `source` (`invoke_complete` or `session_idle`)
- `action`
- `traceId`

### Required Corrections

The documentation set must no longer describe the bridge as never emitting `tool_done`.

Required updates:

- `solution-design.md`
- `implementation-plan.md`
- `architecture/overview.md`
- `operations/logging-reference.md`
- `quality/test-strategy.md`

## 7. Config and Distribution

Current design also keeps:

- multi-source config resolution with json/jsonc project and user config
- structured logging through `client.app.log()`
- single-file plugin distribution build
- plugin load verification script

## 8. Architectural Conclusion

The current design target is not just cleaner structure. It is a constraint:

- schema owner must stay inside `protocol`
- boundary contracts must stay visible inside `contracts`
- runtime must stay orchestration-only
- actions must stay execute-only

Any new upstream event or downstream action should be added by updating:

1. `contracts`
2. `protocol`
3. runtime registration
4. tests

The change must not bypass protocol normalization.
