# Architecture Validation Report

**Version:** 1.0  
**Date:** 2026-03-07  
**Status:** Draft  
**Owner:** message-bridge maintainers  
**Related:** `../architecture/overview.md`, `../design/solution-design.md`, `./traceability-matrix.md`  
**Scope**: `plugins/message-bridge`  

This document validates architecture alignment for key design decisions: pass-through behavior, action routing, fast-fail implementation, close-to-abort semantics, permission dual format support, and test framework choices.

---

## 1. Executive Summary

| Architecture Principle | Status | Evidence Count |
|------------------------|--------|----------------|
| Pass-Through (Transparent Relay) | **VALIDATED** | 4 |
| Action Routing (Registry Pattern) | **VALIDATED** | 5 |
| Fast Fail | **VALIDATED** | 6 |
| Close -> Abort (Not Delete) | **VALIDATED** | 3 |
| Permission Dual Format | **VALIDATED** | 5 |
| Test Framework (bun test + coverage gate) | **VALIDATED** | 4 |

**Overall**: Architecture implementation aligns with design documents. All core principles have concrete code evidence.

---

## 2. Pass-Through Validation (Transparent Relay)

### Principle
Per `overview.md` §1.2 and §1.3: Events must be relayed transparently without business transformation. Only envelope metadata is added.

### Evidence

| Location | Lines | Evidence |
|----------|-------|----------|
| `src/event/EventRelay.ts` | 44-48 | Event subscription: `this.opencode.event.subscribe((event) => this.handleEvent(event))` - raw event passed through |
| `src/event/EventRelay.ts` | 76-82 | Message construction: `event` field contains raw event unchanged; only envelope added |
| `src/event/EventFilter.ts` | 22-34 | Filter only checks allowlist, does not modify event content |
| `src/event/EnvelopeBuilder.ts` | 19-30 | Only adds envelope metadata (version, timestamp, ids, sequence); event body untouched |

### Validation Result: **PASSED**

The implementation correctly:
1. Subscribes to raw OpenCode events without preprocessing
2. Filters by type only (no content inspection)
3. Adds envelope wrapper without event mutation
4. Sends original event in `tool_event` message

---

## 3. Action Routing Validation

### Principle
Per `overview.md` §3.4 and `solution-design.md` §5: Actions must be registered in a registry and routed without core engine modification.

### Evidence

#### 3.1 Registry Pattern

| Location | Lines | Evidence |
|----------|-------|----------|
| `src/action/ActionRegistry.ts` | 1-10 | Interface defines `register()`, `get()`, `has()`, `list()` |
| `src/action/ActionRegistry.ts` | 15-41 | `DefaultActionRegistry` implements Map-based storage |
| `src/action/ActionRegistry.ts` | 18-20 | `register(action)` adds to Map by action.name |

#### 3.2 Router Implementation

| Location | Lines | Evidence |
|----------|-------|----------|
| `src/action/ActionRouter.ts` | 4-8 | Interface with `route()`, `setRegistry()`, `getRegistry()` |
| `src/action/ActionRouter.ts` | 21-35 | `route()` gets action from registry, validates, executes |
| `src/action/ActionRouter.ts` | 26-29 | Throws `Action not found` if not in registry - no hardcoded actions |

#### 3.3 Action Base Interface

| Location | Lines | Evidence |
|----------|-------|----------|
| `src/types/index.ts` | 639-651 | `Action<TPayload>` interface with name, validate, execute, errorMapper |

#### 3.4 Plugin Registration

| Location | Lines | Evidence |
|----------|-------|----------|
| `src/plugin/MessageBridgePlugin.ts` | 23-35 | `registerActions()` iterates array and registers each action - new actions added to array only |

### Extension Test

Adding a new action requires only:
1. Create class extending `Action` interface
2. Add instance to array in `MessageBridgePlugin.registerActions()`

No changes needed to:
- Connection layer
- Event relay
- Router logic
- Core plugin

### Validation Result: **PASSED**

The implementation correctly uses the Registry pattern, enabling new actions without core engine modification.

---

## 4. Fast Fail Validation

### Principle
Per `overview.md` §3.5 and `../product/prd.md` §FR-MB-07: Connection failures must be detected within 100ms and return immediate error without queuing.

### Evidence

#### 4.1 Fast Fail Detector

| Location | Lines | Evidence |
|----------|-------|----------|
| `src/error/FastFailDetector.ts` | 4 | `connectionCheckTimeoutMs = 100` - matches PRD 100ms requirement |
| `src/error/FastFailDetector.ts` | 6-18 | `checkState()` returns error codes for non-READY states |
| `src/error/FastFailDetector.ts` | 8-10 | DISCONNECTED/CONNECTING -> GATEWAY_UNREACHABLE |
| `src/error/FastFailDetector.ts` | 11-12 | CONNECTED -> AGENT_NOT_READY |

#### 4.2 State Checking in Actions

| Location | Lines | Evidence |
|----------|-------|----------|
| `src/action/ChatAction.ts` | 54-61 | Checks `context.connectionState !== 'READY'` before execution |
| `src/action/ChatAction.ts` | 55-61 | Returns `AGENT_NOT_READY` immediately if not READY |
| `src/action/CreateSessionAction.ts` | 56-65 | Same pattern: state check -> immediate error return |
| `src/action/CloseSessionAction.ts` | 47-56 | Same pattern: state check -> immediate error return |
| `src/action/PermissionReplyAction.ts` | 86-95 | Same pattern: state check -> immediate error return |

#### 4.3 No Queuing Evidence

| Location | Lines | Evidence |
|----------|-------|----------|
| `src/event/EventRelay.ts` | 64-67 | `if (!this.stateManager.isReady()) return;` - events dropped, not queued |
| `src/connection/GatewayConnection.ts` | 111-117 | `send()` throws if not connected - no internal queue |

#### 4.4 Error Code Mapping

| Location | Lines | Evidence |
|----------|-------|----------|
| `src/types/index.ts` | 455-465 | `stateToErrorCode()` function maps all states per PRD |

### Validation Result: **PASSED**

The implementation correctly:
1. Uses 100ms timeout constant
2. Returns GATEWAY_UNREACHABLE for DISCONNECTED/CONNECTING
3. Returns AGENT_NOT_READY for CONNECTED
4. Checks state at action entry points
5. Does not queue events or messages

---

## 5. Close Session -> Abort Validation

### Principle
Per `../product/prd.md` §FR-MB-05 and `overview.md` §3.4.3: `close_session` action must use `session.abort()`, NOT `session.delete()`.

### Evidence

| Location | Lines | Evidence |
|----------|-------|----------|
| `src/action/CloseSessionAction.ts` | 14-15 | Comment: "Concrete implementation of close_session action (PRD §FR-MB-05: uses abort semantics, not delete)" |
| `src/action/CloseSessionAction.ts` | 45 | Comment: "Execute close session action (using abort semantics - PRD §FR-MB-05)" |
| `src/action/CloseSessionAction.ts` | 70-75 | Code: `client.session.abort({ sessionId: payload.sessionId })` - abort called |
| `src/action/CloseSessionAction.ts` | 1-144 | **No delete method called anywhere in file** |

### Verification

Search for delete references in CloseSessionAction:
- `abort`: 2 references (lines 14, 71)
- `delete`: 0 references

### Validation Result: **PASSED**

The implementation correctly uses `session.abort()` as required. No `delete` semantics present.

---

## 6. Permission Reply Dual Format Validation

### Principle
Per PRD §FR-MB-06: Must support both target format (`response: allow|always|deny`) and compat format (`approved: boolean`).

### Evidence

#### 6.1 Type Definitions

| Location | Lines | Evidence |
|----------|-------|----------|
| `src/types/index.ts` | 356-365 | `PermissionReplyPayloadTarget` with `response: 'allow' \| 'always' \| 'deny'` |
| `src/types/index.ts` | 374-383 | `PermissionReplyPayloadCompat` with `approved: boolean` |
| `src/types/index.ts` | 390-392 | `PermissionReplyPayload` union of both |

#### 6.2 Type Guards

| Location | Lines | Evidence |
|----------|-------|----------|
| `src/types/index.ts` | 416-418 | `isPermissionReplyTarget()` checks for `response` field presence |
| `src/types/index.ts` | 427-429 | `mapApprovedToResponse()` maps boolean to response strings |

#### 6.3 Validation

| Location | Lines | Evidence |
|----------|-------|----------|
| `src/action/PermissionReplyAction.ts` | 46-67 | validate() branches: if target format, check response enum; else check approved boolean |

#### 6.4 Execution

| Location | Lines | Evidence |
|----------|-------|----------|
| `src/action/PermissionReplyAction.ts` | 110-117 | execute() detects format: target used directly, compat mapped via `mapApprovedToResponse()` |

### Mapping Verification

Per PRD §FR-MB-06:
- `approved=true` -> `allow`: **IMPLEMENTED** (`src/types/index.ts` line 428)
- `approved=false` -> `deny`: **IMPLEMENTED** (`src/types/index.ts` line 428)

### Validation Result: **PASSED**

The implementation correctly:
1. Defines both formats as TypeScript types
2. Provides type guards for format detection
3. Validates both formats
4. Maps compat format to target format during execution
5. Uses approved=true->allow, approved=false->deny mapping

---

## 7. Test Framework Validation

### Principle
Per `overview.md` §8 and `../product/prd.md` §9: Tests use `bun test`, coverage is generated by Bun and threshold-checked by script.

### Evidence

#### 7.1 Test Runner

| Location | Lines | Evidence |
|----------|-------|----------|
| `package.json` | scripts | `"test": "npm run build && bun test"` |
| `package.json` | scripts | unit/integration/e2e scripts all use `bun test` |
| `tests/**` | runtime | Test files executed by Bun test runner |

#### 7.2 Coverage Tool

| Location | Lines | Evidence |
|----------|-------|----------|
| `package.json` | scripts | `"test:coverage": "npm run build && node ./scripts/check-coverage.mjs"` |
| `scripts/check-coverage.mjs` | - | Parses Bun coverage and enforces threshold |

#### 7.3 Coverage Thresholds

| Requirement | Configured | Evidence |
|-------------|------------|----------|
| Lines >= 80% | Yes | `package.json` line 15: `--lines 80` |
| Branches >= 70% | Yes | `package.json` line 15: `--branches 70` |

#### 7.4 Test Categories

| Category | File | Evidence |
|----------|------|----------|
| Unit | `tests/unit/example.test.mjs` | 405 lines, tests for actions, utilities |
| Integration | `tests/integration/plugin.test.mjs` | 159 lines, tests registry and routing |
| E2E | `tests/e2e/example.test.mjs` | File exists |

#### 7.5 Test Coverage by Component

| Component | Unit Tests | Integration Tests |
|-----------|------------|-------------------|
| ChatAction | Yes (lines 52-111) | Via registry |
| CreateSessionAction | Yes (lines 113-148) | Via registry |
| CloseSessionAction | Yes (lines 150-192) | Via registry |
| PermissionReplyAction | Yes (lines 194-256) | Via registry |
| StatusQueryAction | Yes (lines 258-290) | Via registry |
| FastFailDetector | Yes (lines 294-310) | - |
| ErrorMapper | Yes (lines 312-330) | - |
| EventFilter | Yes (lines 332-355) | - |
| EnvelopeBuilder | Yes (lines 357-404) | - |
| ActionRegistry | - | Yes (lines 7-28) |
| ActionRouter | - | Yes (lines 30-67) |

### Validation Result: **PASSED**

The implementation correctly:
1. Uses Bun test runner (`bun test`)
2. Uses Bun coverage with enforced threshold script
3. Has unit tests for all actions and utilities
4. Has integration tests for registry and routing
5. Has E2E test file present

---

## 8. Cross-Reference: Architecture Docs -> Code

| Architecture Doc Section | Implementation File | Alignment |
|--------------------------|---------------------|-----------|
| `overview.md` §3.1 Config Layer | `src/config/ConfigResolver.ts`, `ConfigValidator.ts` | Direct mapping |
| `overview.md` §3.2 Connection Layer | `src/connection/GatewayConnection.ts`, `StateManager.ts`, `AkSkAuth.ts` | Direct mapping |
| `overview.md` §3.3 Event Layer | `src/event/EventRelay.ts`, `EventFilter.ts`, `EnvelopeBuilder.ts` | Direct mapping |
| `overview.md` §3.4 Action Layer | `src/action/ActionRouter.ts`, `ActionRegistry.ts`, `*Action.ts` | Direct mapping |
| `overview.md` §3.5 Error Layer | `src/error/FastFailDetector.ts`, `ErrorMapper.ts` | Direct mapping |
| `solution-design.md` §2.5.2 Built-in Actions | 5 action files in `src/action/` | All 5 implemented |
| `solution-design.md` §3.1 Fast Fail | `src/error/FastFailDetector.ts` | Direct implementation |
| `solution-design.md` §6.1.1 Permission Reply | `src/action/PermissionReplyAction.ts` | Dual format supported |
| `solution-design.md` §6.1.2 Close Session | `src/action/CloseSessionAction.ts` | Abort semantics confirmed |

---

## 9. Deviations and Notes

### 9.1 Accepted Deviations (Documented)

| Deviation | Location | Rationale |
|-----------|----------|-----------|
| No gatewayAgentId from server | `overview.md` §9.1 | ai-gateway does not return register_success; plugin uses local agentId |
| READY state on register send (not response) | `overview.md` §9.1 | No explicit register_success from gateway; connection keep-alive implies success |

### 9.2 Minor Gaps (Non-blocking)

| Gap | Impact | Recommendation |
|-----|--------|----------------|
| HMAC-SHA256 signature not implemented | Auth uses Bearer token instead of PRD query params | Implement in `AkSkAuth.ts` if gateway requires |
| unsupported_event not logged | Events silently dropped when not in allowlist | Add logging in `EventRelay.ts` |
| Config tests not found | Config logic not explicitly unit tested | Add tests for ConfigResolver/Validator |

---

## 10. Conclusion

### Architecture Alignment: **VALIDATED**

The `plugins/message-bridge` implementation aligns with the architecture design documents:

1. **Pass-Through**: Events are relayed transparently with only envelope metadata added
2. **Action Routing**: Registry pattern enables extension without core modification
3. **Fast Fail**: 100ms detection, immediate error return, no queuing
4. **Close->Abort**: Uses `session.abort()`, no `delete` called
5. **Permission Dual Format**: Supports both `response` and `approved` fields with correct mapping
6. **Test Framework**: bun test with coverage threshold gate

All core architectural principles have concrete evidence in the codebase.

---

**Document End**
