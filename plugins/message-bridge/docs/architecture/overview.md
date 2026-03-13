# message-bridge Architecture Overview

**Version:** 2.1  
**Date:** 2026-03-10  
**Status:** Active

## 1. Goals

The current implementation is organized around four constraints:

1. preserve external protocol behavior
2. keep boundary contracts explicit
3. confine raw protocol parsing to protocol boundary layers
4. keep runtime orchestration separate from business execution

The resulting flow is:

```text
raw event/message
  -> contracts
  -> protocol
  -> runtime
  -> action / transport
```

## 2. Layers

### 2.1 `contracts`

Defines external boundary shapes:

- `contracts/upstream-events.ts`
- `contracts/downstream-messages.ts`
- `contracts/transport-messages.ts`

This layer answers what the bridge exchanges with OpenCode and the gateway.

### 2.2 `protocol`

Owns schema normalization and extraction.

- `protocol/upstream`
  - validates supported upstream events
  - extracts `toolSessionId`
  - emits `event.extraction_failed` on failure
- `protocol/downstream`
  - normalizes gateway messages into typed commands
  - emits `downstream.normalization_failed` on failure

This layer is the only place allowed to read raw protocol fields.

### 2.3 `runtime`

Owns orchestration only:

- lifecycle
- config load
- connection management
- action routing
- gateway send

`runtime` must not parse raw upstream or downstream payloads.

### 2.4 `action`

Owns execute-only business logic:

- state gating
- SDK calls
- result mapping
- error mapping

Actions do not normalize payloads anymore.

## 3. Upstream Flow

```text
OpenCode event
  -> EventFilter
  -> extractUpstreamEvent()
  -> runtime.handleEvent()
  -> gateway.send({ type: 'tool_event', toolSessionId, event })
```

Current exact allowlist:

- `message.updated`
- `message.part.updated`
- `message.part.delta`
- `message.part.removed`
- `session.status`
- `session.idle`
- `session.updated`
- `session.error`
- `permission.updated`
- `permission.asked`
- `question.asked`

## 4. Downstream Flow

```text
gateway message
  -> normalizeDownstreamMessage()
  -> runtime.handleDownstreamMessage()
  -> actionRouter.route()
  -> action.execute()
  -> runtime sends transport response
```

Supported downstream message types:

- `invoke`
- `status_query`

Supported `invoke.action` values:

- `chat`
- `create_session`
- `close_session`
- `permission_reply`
- `abort_session`
- `question_reply`

## 5. Transport Behavior

Bridge-to-gateway transport message types:

- `register`
- `heartbeat`
- `tool_event`
- `tool_done`
- `tool_error`
- `session_created`
- `status_response`

Protocol notes:

- `tool_event` remains `{ type: 'tool_event', toolSessionId, event }`
- response messages no longer carry `sessionId` or `envelope`
- `session.idle` continues to be forwarded as `tool_event`
- `tool_done` is restored as a compat-layer completion projection for UI consumers
- no wildcard upstream allowlist defaults

## 6. Config and Logging

Config sources, high to low:

1. env: `BRIDGE_*`
2. project: `.opencode/message-bridge.jsonc` / `.json`
3. user: `~/.config/opencode/message-bridge.jsonc` / `.json`
4. built-in defaults

Defaults live in `src/config/default-config.ts`.

Structured logging is emitted through `client.app.log()` when available. Key protocol failure events:

- `event.extraction_failed`
- `downstream.normalization_failed`

## 7. Current Conclusions

Current code satisfies these architectural conclusions:

- boundary contracts are centralized in `contracts/`
- schema ownership is centralized in `protocol/`
- `runtime` has been reduced to orchestration
- `action` mainline execution no longer owns payload schema

Compatibility shims remain in a few legacy entrypoints to avoid breaking existing imports, but new development should follow the `contracts -> protocol -> runtime -> action` path.
