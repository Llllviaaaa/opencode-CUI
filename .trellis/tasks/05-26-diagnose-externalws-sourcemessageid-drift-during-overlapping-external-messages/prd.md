# diagnose externalws sourceMessageId drift during overlapping external messages

## Goal

Fix the external invocation flow when two chat messages are sent into the same business session while the first assistant response is still streaming. Events delivered to `/ws/external/stream` must keep message identity and text content bound to the correct user request.

## What I already know

* The user reports that message 2's external WS events sometimes carry `messageId` for message 2 but `sourceMessageId` from message 1.
* The user also reports that message 2's `text.done` may become a truncated fragment from message 1.
* Cloud-side `step.start` / `step.done` events include `messageId` but not `partId`; intermediate part events may rely on fallback identity.
* The affected path is the external API plus external WS delivery, not the miniapp-only UI path.
* Related prior fixes touched `ExternalWsDeliveryStrategy`, `ExternalStreamHandler`, and relay delivery; those were about duplicate/fallback delivery, not overlapping stream identity.

## Requirements

* Keep `messageId` and `sourceMessageId` consistent for every emitted external WS event.
* For cloud events without `partId` and any events without `messageId`, bind fallback identity to the current external invoke request/stream, not to mutable session-global state.
* Keep streamed text accumulation isolated per overlapping chat request.
* Avoid broad delivery fallback changes; this task is about identity/content isolation in the external streaming path.
* Add a focused regression test for overlapping external messages or the lowest-level component that can reproduce the state collision.

## Acceptance Criteria

* [ ] When message 1 is streaming and message 2 starts in the same session, message 2 events never contain message 1's `sourceMessageId`.
* [ ] Message 2 `text.done` is built from message 2 content only, never a truncated or accumulated fragment from message 1.
* [ ] Existing external WS routing semantics remain unchanged.
* [ ] Targeted tests pass.

## Definition of Done

* Tests added or updated around the bug trigger.
* GitNexus impact analysis run before symbol edits.
* GitNexus change detection run before final handoff.
* Focused test command run and reported.

## Out of Scope

* Redesigning external WS registry or Redis relay fallback.
* Changing miniapp snapshot restore behavior unless the investigation proves shared code is responsible.
* Adding new external API fields unless required to preserve existing message identity.

## Technical Notes

* `GatewayMessageRouter.routeAssistantMessage` only calls `prepareStableMessageContext` for miniapp or IM direct sessions; external domain currently relies on the event's own identity plus outbound enrich.
* `StreamMessageEmitter.enrich` calls `MessagePersistenceService.applyMessageContextIfPresent` for non-user messages when the session id is numeric.
* `ActiveMessageTracker.applyMessageContextIfPresent` writes `messageId/messageSeq/role` but not `sourceMessageId`, so any overwrite here can produce split identity if the incoming event already has a stale `sourceMessageId`.
* `CloudEventTranslator` currently warns when cloud part-level events lack `partId`; the real cloud contract has at least `step.start` / `step.done` with `messageId` but no `partId`, so fallback part identity must be stable.
* `OpenClawGatewayBridge` keeps `activeToolSessions` by `sessionKey`; overlapping `handleChat` calls in the same session create new `AssistantStreamState` values and overwrite the previous active entry.
* `handleRuntimeAgentEvent` routes runtime deltas through `activeToolSessions`, so direct runtime events without a per-run disambiguator are a likely source of content mixing.
