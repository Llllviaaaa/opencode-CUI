# fix group rebuild create_session missing userId

## Goal

Fix the IM group-session rebuild path where an existing `SkillSession` has a blank `toolSessionId` and the SS `create_session` invoke reaches GW with top-level `userId=null`, causing GW to skip local-agent delivery with `missing_userId`.

## What I Already Know

- The production log shows `InboundProcessingService` detected an existing session with no ready `toolSessionId`, appended a structured pending request with `sender=y00807476`, then sent `GatewayRelayService.sendInvokeToGateway` with `userId=null`.
- GW `SkillRelayService.validateInvokeMessage` rejects local-agent invokes before delivery when top-level `GatewayMessage.userId` is blank.
- Group `SkillSession.userId` intentionally remains `NULL`; this task must not repopulate it or infer group sender from it.
- `SessionRebuildService.rebuildToolSession(..., routeUserId, ...)` already supports a separate route hint and uses `firstNonBlank(routeUserId, session.getUserId())`.

## Requirements

- Existing group-chat sessions with blank `toolSessionId` must rebuild by passing the resolved local-agent owner/gateway user as `routeUserId`.
- Explicit `rebuild` action on an existing local/personal session must also pass the resolved route user into the tool-session request.
- Pending chat payload must continue to preserve the real message sender in `PendingChatRequest.sendUserAccount`.
- Group `SkillSession.userId` must remain `null`.

## Acceptance Criteria

- [x] For `chat` on an existing group session with missing `toolSessionId`, `sessionManager.requestToolSession(session, pendingRequest, ownerWelinkId)` is called.
- [x] The pending request still carries `sendUserAccount=senderUserAccount` and `imGroupId=business session id`.
- [x] For explicit `rebuild` on an existing personal/local session, the rebuild request carries the resolved owner route user.
- [x] Existing direct/personal behavior remains unchanged.

## Definition of Done

- Unit tests updated for the affected rebuild paths.
- Focused Maven tests pass for `InboundProcessingServiceTest`.
- GitNexus `detect_changes` confirms only expected symbols/flows changed.

## Technical Approach

Use the existing route-hint contract rather than adding a new field or changing GW validation. The SS entrypoint already resolves `ownerWelinkId` from `AssistantSessionIdentity`; pass that value to the existing `ImSessionManager.requestToolSession(..., routeUserId)` overload for both missing-tool-session chat rebuild and explicit rebuild.

## Out of Scope

- No ai-gateway validation change.
- No database migration.
- No change to `SkillSession.userId` group-session semantics.
- No change to retry payload sender semantics.

## Technical Notes

- Specs read: `.trellis/spec/skill-server/backend/index.md`, `directory-structure.md`, `conventions.md`, `error-handling.md`, `logging-guidelines.md`, `type-safety.md`, and `.trellis/spec/guides/cross-layer-thinking-guide.md`.
- GitNexus impact:
  - `processChat`: LOW, direct callers are `ImInboundController.receiveMessage` and `ExternalInboundController.invoke`.
  - `processRebuild`: LOW, direct caller is `ExternalInboundController.invoke`.
  - `rebuildRouteUserId`: LOW, direct caller is `processChat`.
- Memory: previous local-assistant fix established that top-level `GatewayMessage.userId` is a route hint and should not be confused with payload `sendUserAccount`.
