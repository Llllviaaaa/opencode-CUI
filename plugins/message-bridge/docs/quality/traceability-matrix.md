# Traceability Matrix

## 1. Architecture Constraints

| Constraint | Current implementation |
|---|---|
| Boundary contracts are explicit | `src/contracts/*` |
| Schema owner is centralized | `src/protocol/*` |
| Runtime does orchestration only | `src/runtime/BridgeRuntime.ts` |
| Actions are execute-only | `src/action/*` |
| Upstream allowlist is exact | `DEFAULT_EVENT_ALLOWLIST` in `contracts/upstream-events.ts` |
| Default config is centralized | `src/config/default-config.ts` |

## 2. Downstream Coverage

| Message / action | Contract | Normalizer | Action |
|---|---|---|---|
| `status_query` | `contracts/downstream-messages.ts` | `protocol/downstream/DownstreamMessageNormalizer.ts` | runtime direct response |
| `invoke/chat` | same | same | `action/ChatAction.ts` |
| `invoke/create_session` | same | same | `action/CreateSessionAction.ts` |
| `invoke/close_session` | same | same | `action/CloseSessionAction.ts` |
| `invoke/permission_reply` | same | same | `action/PermissionReplyAction.ts` |
| `invoke/status_query` | same | same | runtime direct response |
| `invoke/abort_session` | same | same | `action/AbortSessionAction.ts` |
| `invoke/question_reply` | same | same | `action/QuestionReplyAction.ts` |

## 3. Upstream Coverage

| Event type | Contract | Extractor |
|---|---|---|
| `message.updated` | `contracts/upstream-events.ts` | `protocol/upstream/UpstreamEventExtractor.ts` |
| `message.part.updated` | same | same |
| `message.part.delta` | same | same |
| `message.part.removed` | same | same |
| `session.status` | same | same |
| `session.idle` | same | same |
| `session.updated` | same | same |
| `session.error` | same | same |
| `permission.updated` | same | same |
| `permission.asked` | same | same |
| `question.asked` | same | same |

## 4. Verification Mapping

| Verification goal | Evidence |
|---|---|
| Type safety for contracts and protocol | `bun run typecheck` |
| Unit coverage for protocol/runtime/action | `bun run test:unit` |
| Distribution artifact validation | `tests/integration/plugin-distribution.test.mjs` |
| Plugin load verification | `bun run verify:opencode-load` |

## 5. Current Conclusions

The current implementation satisfies the main refactor goal:

- raw protocol parsing is isolated to `protocol/*`
- external message shapes are isolated to `contracts/*`
- runtime no longer owns payload schema
- actions no longer own payload schema
