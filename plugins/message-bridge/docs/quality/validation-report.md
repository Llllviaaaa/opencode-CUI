# Validation Report

## 1. Scope

This report reflects the integrated `message-bridge` implementation on top of the refactored boundary architecture and the `feat/message-bridge-dev` feature set.

Validated areas:

- contracts / protocol / runtime / action layering
- downstream action coverage
- exact upstream allowlist behavior
- config resolution and validation
- distribution build and load verification

## 2. Architecture Validation

### 2.1 Schema ownership

Validated conclusion:

- upstream schema owner: `protocol/upstream`
- downstream schema owner: `protocol/downstream`
- `runtime` no longer parses raw downstream payloads
- `action` mainline execution no longer normalizes payloads

### 2.2 Boundary contract visibility

Validated conclusion:

- upstream event contracts are centralized in `contracts/upstream-events.ts`
- downstream message contracts are centralized in `contracts/downstream-messages.ts`
- transport messages are centralized in `contracts/transport-messages.ts`

## 3. Functional Validation

Validated downstream actions:

- `chat`
- `create_session`
- `close_session`
- `permission_reply`
- `status_query`
- `abort_session`
- `question_reply`

Validated upstream default allowlist:

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

## 4. Verification Evidence

Validated successfully:

```bash
npm run typecheck
npm run test:unit
bun test tests/integration/plugin-distribution.test.mjs
./scripts/verify-opencode-load.sh
```

## 5. Compatibility Conclusions

Verified compatibility constraints:

- external transport message shapes remain stable
- `tool_event` remains envelope-free
- `tool_error`, `session_created`, and `status_response` remain available with existing semantics
- compatibility fields such as `welinkSessionId` remain supported on response messages
- allowlist defaults remain exact-event based and do not revert to wildcard behavior

## 6. Residual Notes

The implementation still keeps a small number of compatibility re-export entrypoints to avoid breaking older imports. These do not change the architectural baseline:

- new development should enter through `contracts/` and `protocol/`
- runtime and action layers should not reintroduce raw protocol parsing
