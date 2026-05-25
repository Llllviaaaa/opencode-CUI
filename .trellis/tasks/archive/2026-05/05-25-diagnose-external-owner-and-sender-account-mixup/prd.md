# diagnose external owner and sender account mixup

## Goal

Locate why external conversations can populate `ownerWelinkId` and/or `senderUserAccount` with `assistantAccount`, and identify the exact code path and expected fix scope before changing behavior.

## What I already know

* User observed an external conversation bug: `ownerWelinkId` / `senderUserAccount` are wrong and become `assistantAccount`.
* Previous repo guidance says external/IM inbound `senderUserAccount` must be an envelope-level required field and must not fall back to `ownerWelinkId`.
* Previous repo guidance says `sendUserAccount` should pass through the real sender and ai-gateway treats it as opaque.

## Assumptions

* The bug is in `skill-server` external inbound/session rebuild/pending replay flow unless code inspection proves otherwise.
* The immediate request is diagnosis and localization; implementation should come after the cause and blast radius are clear.

## Open Questions

* None yet; derive the failing path from code first.

## Requirements

* Trace external inbound request handling from controller through session resolution/rebuild and gateway dispatch.
* Identify every place in the external flow where `assistantAccount` can be copied into `ownerWelinkId`, session `userId`, `senderUserAccount`, or `sendUserAccount`.
* Report the precise suspected root cause with file and line references.

## Acceptance Criteria

* [ ] Root cause is backed by current source code.
* [ ] Affected scenario is stated in plain language.
* [ ] Proposed fix scope is narrow and aligned with existing sender/assistant identity contracts.

## Definition of Done

* No source behavior is changed during diagnosis unless explicitly requested next.
* If implementation follows, run GitNexus impact before editing affected symbols.
* If implementation follows, run focused tests and `gitnexus_detect_changes()` before commit.

## Technical Notes

* Relevant memory: prior fallback cleanup around `senderUserAccount`, `ownerWelinkId`, `InboundProcessingService`, `ImSessionManager`, `ImInboundController`, and ai-gateway passthrough.
* Root-cause candidate: `AssistantAccountResolverService#judge` currently sets `ownerWelinkId = firstNonBlank(info.getOwnerWelinkId(), resolvedAccount)`. When upstream omits `ownerWelinkId`, `resolvedAccount` is `partnerAccount` or the requested `assistantAccount`, so the resolver can return `ownerWelinkId == assistantAccount`.
* Propagation path: `AssistantSessionIdentity.fromResolveOutcome` copies `outcome.ownerWelinkId()` into `gatewayUserId`; `ImSessionManager#createSessionAsync` writes that as `SkillSession.userId`; `PendingChatRequest#fromSessionFallback` later maps `session.getUserId()` to `sendUserAccount`, so rebuild/plain-text fallback can turn sender into assistant account.
* Live request path still passes envelope `senderUserAccount` through `dispatchChatToGateway`; the bad sender appears on degraded rebuild/fallback paths that reconstruct sender from `SkillSession.userId`.
* Existing test `AssistantAccountResolverServiceTest#remoteOwnerMissingFallsBackToPartnerAccountExists` explicitly enshrines the bad fallback by asserting missing owner becomes `assist-001`.
* GitNexus impact: changing `AssistantAccountResolverService` class is MEDIUM; changing `PendingChatRequest#fromSessionFallback` is HIGH and affects tool-error/context-overflow/session-created replay flows.

## User Clarification

* Direct conversations must use the current user's identity for `userId` (cookie userId in miniapp paths; `senderUserAccount` in IM/external inbound envelopes), not assistant owner identity and never `assistantAccount` fallback.
* The fix should remove the assistantAccount fallback logic rather than preserving it through another layer.
