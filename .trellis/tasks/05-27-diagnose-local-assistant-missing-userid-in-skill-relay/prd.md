# diagnose local assistant missing userId in skill relay

## Goal

Diagnose and fix intermittent local-assistant delivery failures where `ai-gateway` logs `SkillRelayService.handleInvokeFromSkill: reason=missing_userId` even though the local agent is online.

## What I Already Know

* The failing guard is in `ai-gateway/src/main/java/com/opencode/cui/gateway/service/SkillRelayService.java::validateInvokeMessage`.
* Gateway validates top-level `GatewayMessage.userId` before checking local/remote/pending agent delivery, so an online local agent can still be bypassed if SS sends an invoke without top-level `userId`.
* SS personal/local invoke JSON writes top-level `userId` from `InvokeCommand.userId`.
* `SkillSession.userId` is intentionally nullable for IM group sessions; it must not be repopulated just to satisfy relay routing.
* Strong candidate root cause: group/local assistant first-chat toolSession creation uses `session.getUserId()` through `SessionRebuildService.rebuildToolSession`, which is null for group sessions. The correct route hint is the local assistant owner / gateway user id; the real sender remains in payload `sendUserAccount`.

## Requirements

* Keep group `SkillSession.userId = NULL` semantics intact.
* Preserve payload `sendUserAccount` as the real sender.
* Ensure create_session / rebuild invokes for local assistant routes can carry the local agent owner userId as top-level `InvokeCommand.userId`.
* Keep existing rebuild callers backward compatible.
* Add focused regression tests covering the missing-userId path.

## Acceptance Criteria

* [x] IM group first chat for a local personal assistant calls rebuild/create-session with a nonblank route userId.
* [x] `SessionRebuildService` uses an explicit route userId for create_session when provided, falling back to existing `session.userId` for old callers.
* [x] Existing direct/pending/rebuild behavior still passes focused tests.
* [x] GitNexus detect-changes confirms the affected scope is expected.

## Out of Scope

* Changing Gateway agent-online detection.
* Relaxing Gateway `missing_userId` validation.
* Storing assistant owner in `SkillSession.userId` for group sessions.
* Changing remote/business cloud routing semantics.

## Technical Notes

* Relevant specs: `.trellis/spec/skill-server/backend/conventions.md`, `.trellis/spec/skill-server/backend/type-safety.md`, `.trellis/spec/ai-gateway/backend/conventions.md`.
* Relevant code: `SessionRebuildService`, `GatewayRelayService`, `ImSessionManager`, `GatewayMessageRouter`, `SkillRelayService`.
* GitNexus impact: `SessionRebuildService.rebuildToolSession` is HIGH risk; use backward-compatible overloads and focused tests.
