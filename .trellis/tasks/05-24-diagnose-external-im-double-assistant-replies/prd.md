# Diagnose external IM double assistant replies

## Goal

Find and fix why messages sent through the IM external interface can produce two assistant replies. The immediate production symptom is that an assistant response is streamed through the external path and then also sent to IM as a fallback message.

## What I Already Know

* User-reported traceId: `e9eb91e2-84a6-4157-b7d5-f3f9ba45de70`.
* Domain is `im`; welink session id is `183906780303982592`.
* Gateway routes cloud events back to skill-server via `toolSessionId=183906780471754752`.
* Skill-server logs show `ExternalWsDeliveryStrategy` sending external events (`planning.*`, `text.delta`, `text.done`, `step.done`, `session.status`).
* Relay candidate publish attempts show `receivers=0` for target `deployment-skill-api-79d9d94887-8vp5v`.
* After `text.done`, `ExternalWsDeliveryStrategy` logs `ExternalWs-L3: no WS connections, fell back to ImRest`, then `ImOutboundService` sends the completed assistant text to IM.
* User confirmed the target pod did receive the external relay message even while the publisher reported `receivers=0`.
* A prior memory note says external relay candidate delivery should treat `publishToExternalRelay(...) == 0` as failed and continue to next candidate, with `RedisMessageBroker` owning relay channel helpers.

## Assumptions

* The duplicate visible IM reply is likely caused by the external delivery fallback path sending `text.done` to IM even though the original request came from an external/IM integration path.
* `receivers=0` on Redis `PUBLISH` is not a reliable end-to-end delivery ACK in the deployed Redis topology; it should be diagnostic only.

## Requirements

* Root-cause the duplicate reply from code, not only from log inference.
* Preserve external websocket delivery behavior for clients that are actually connected.
* Keep the external path to L1 local websocket and L2 cross-instance Redis relay only.
* Remove L3 IM REST fallback from external delivery.
* Keep relay/subscriber diagnostics as diagnostics, without using same-node subscriber count as a business delivery result.
* Propagate trace context through the Pub/Sub relay envelope so target-pod relay receive logs remain traceable.

## Acceptance Criteria

* [x] Code-level cause explains why the provided log sequence sends a second IM message.
* [x] A regression test or focused verification covers `domain=im` external delivery when relay candidate publish returns zero same-node subscribers.
* [x] External delivery no longer has an IM REST fallback path.
* [x] Pub/Sub relay restores MDC fields on the receiving instance.
* [x] GitNexus impact analysis is run before editing modified symbols.
* [x] GitNexus change detection is run before finishing.

## Definition of Done

* Tests added or updated where practical.
* Relevant backend test command run, or inability documented.
* Scope reviewed against expected affected symbols and execution flows.
* PRD updated with final technical notes.

## Out of Scope

* Reworking Redis relay architecture broadly.
* Changing cloud assistant generation behavior.
* Changing miniapp frontend behavior unless code evidence shows it participates in the duplicate reply.

## Technical Notes

* Root cause: `ExternalWsDeliveryStrategy` treated Redis external relay `receivers=0` as relay failure, then invoked L3 IM REST fallback for `domain=im`. In Redis Cluster, that returned count is not a cluster-wide delivery acknowledgement, so the target instance may still receive the Pub/Sub message.
* Fix: `ExternalWsDeliveryStrategy` now delivers by L1 local external WS, then L2 best-effort Redis relay. It stops after a successful `PUBLISH` command and never calls IM REST fallback.
* `RedisMessageBroker.publishToExternalRelayBestEffort(...)` returns `false` only when `convertAndSend` throws; it still logs `sameNodeReceivers` for diagnostics.
* Relay envelope now includes `traceId`, `welinkSessionId`, `ak`, and `userId`. `ExternalStreamHandler` restores those fields to MDC before pushing to local WS and logging `[RELAY-RX]`.
* `RedisMessageBroker` clears MDC after each Pub/Sub callback to avoid leaking relay trace context between messages.
* Updated `skill-server` backend conventions so future external WS work treats Redis subscriber count as diagnostic and keeps the path to L1/L2 only.
