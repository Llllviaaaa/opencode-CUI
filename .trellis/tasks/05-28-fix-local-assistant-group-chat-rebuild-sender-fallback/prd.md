# Fix local assistant group chat rebuild sender fallback

## Goal

Fix the local-assistant group chat rebuild path so a first or pending IM message can rebuild the tool session and retry without losing the real sender identity.

## What I Already Know

- The reported production log is for `domain=im`, `sessionType=group`, `action=chat`, assistant `dig_00807476_mpb167cd`, ak `20260518175745094739374`.
- `InboundProcessingService` found an existing `SkillSession` whose `toolSessionId` was still null and requested a rebuild.
- `SessionRebuildService` logged `fallback_account_missing_on_legacy_overload` from `rebuildToolSession(legacy String overload)`.
- The pending retry message was appended with `assistantAccount=null`, `sender=null`, and `imGroupId=null`.
- `PendingChatRequest.fromSessionFallback` intentionally refuses to infer group-chat sender from `session.userId`, so a group pending request must carry sender context before fallback.

## Assumptions

- The correct fix is to keep using null `SkillSession.userId` for group sessions and persist/pass the sender through rebuild context.
- The live chat path likely has the sender available before the call degrades into the legacy rebuild overload.
- This task should stay scoped to local assistant group-chat rebuild and pending retry behavior unless inspection proves a shared helper is the source.

## Requirements

- Preserve sender identity for group IM pending messages when tool session creation or rebuild is needed.
- Avoid adding a group-session fallback from `SkillSession.userId` back into `sendUserAccount`.
- Keep backward compatibility for existing legacy pending entries where possible, while failing clearly when sender is truly unavailable.
- Add focused tests around group rebuild/retry sender propagation.

## Acceptance Criteria

- [x] The reported log sequence no longer reaches `fallback_account_missing_on_legacy_overload` when the inbound group chat contains a sender.
- [x] Pending retry payloads for group IM messages include assistant account, sender account, and group/session context needed for replay.
- [x] Existing direct-chat rebuild behavior remains unchanged.
- [x] Focused backend tests pass for the touched `skill-server` path.

## Definition of Done

- Tests added or updated for the affected rebuild/retry path.
- Relevant Trellis specs consulted before code changes.
- GitNexus impact analysis run before symbol edits and change detection run before finishing.
- Rollback risk is limited to the group-chat rebuild context propagation path.

## Out of Scope

- Changing cloud assistant routing.
- Reintroducing sender fallback from group session ownership.
- Broad refactors of IM routing or assistant availability.

## Technical Notes

- Initial suspect chain: `ExternalInboundController.invoke` -> `InboundProcessingService.processChat` -> `GatewayRelayService.rebuildToolSession` -> `SessionRebuildService.rebuildToolSession` -> `PendingChatRequest.fromSessionFallback`.
- Prior memory notes identify `SkillSession.userId` -> `PendingChatRequest.fromSessionFallback` as a dangerous identity-pollution chain for group sessions.
- Root cause confirmed: `InboundProcessingService.processChat` case B still used the deprecated `requestToolSession(session, prompt)` String overload, so group session rebuild lost `senderUserAccount` and route user context.
- Fix: build a structured `PendingChatRequest` from the current inbound envelope before requesting tool session rebuild, and pass `ownerWelinkId` as route user for local assistants.
- Validation: `mvn.cmd -f skill-server/pom.xml "-Dtest=InboundProcessingServiceTest,SessionRebuildServiceTest,ImSessionManagerTest" clean test` passed with 83 tests.
