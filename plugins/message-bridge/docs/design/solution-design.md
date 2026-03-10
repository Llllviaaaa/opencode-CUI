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
