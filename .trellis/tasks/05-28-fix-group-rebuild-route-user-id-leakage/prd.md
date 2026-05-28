# Fix group rebuild route user id leakage

## Goal

Correct the prior local-assistant group-chat rebuild fix so it preserves the real message sender in pending retry payloads without adding a `userId` to group session rebuild/create-session routing.

## Requirements

- Group IM sessions must continue to have no session `userId`.
- Group rebuild must not pass owner or sender as the `routeUserId`/`InvokeCommand.userId` fallback.
- Pending retry messages must still carry `sendUserAccount` from the inbound envelope, because replayed `chat` needs the true sender.
- Direct-chat behavior may keep the existing route user behavior.

## Acceptance Criteria

- [x] Group case-B rebuild calls the structured `PendingChatRequest` API with null route user.
- [x] Group pending request keeps `sendUserAccount`, `assistantAccount`, `imGroupId`, and business context.
- [x] Direct personal case-B rebuild still carries a route user.
- [x] Focused `skill-server` tests pass.

## Technical Notes

- User correction: local-assistant group sessions intentionally have no userId; the previous patch confused pending sender identity with create-session route user identity.
- Keep sender in `PendingChatRequest.sendUserAccount`; do not use it to populate session/create-session userId for groups.
