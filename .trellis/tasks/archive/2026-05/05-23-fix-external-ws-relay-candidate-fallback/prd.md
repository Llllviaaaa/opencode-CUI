# fix: external ws relay candidate fallback

## Goal

Make SS -> external WS cross-instance delivery follow the same reliability principle as
GW/SS relay: a Redis publish with zero subscribers is not success. Delivery should try all
known remote SS candidates for the target external domain before falling back.

## Requirements

* Preserve owner-only external WS registry: each SS writes only `external-ws:held-by:{selfId}`.
* Keep candidate discovery based on alive SS roster plus pipelined held-by HGET.
* Return all remote SS candidates with `connectionCount > 0`, not only the first one.
* Publish external relay through a named RedisMessageBroker API that returns subscriber count.
* Treat `publishToExternalRelay(...)=0` as a failed candidate and try the next one.
* Fall back to the existing L3 behavior only after all candidates fail or no candidate exists.

## Acceptance Criteria

* [x] `ExternalWsRegistry` exposes ordered candidate-list lookup while keeping old single-target API compatible.
* [x] `ExternalWsDeliveryStrategy` tries the next candidate when Redis publish returns 0 subscribers.
* [x] Existing L1 local delivery and L3 fallback behavior remain intact.
* [x] External relay channel subscription no longer hand-writes `ss:external-relay:` outside `RedisMessageBroker`.
* [x] Targeted tests pass.

## Out of Scope

* Changing external WS handshake/auth.
* Changing the owner-only held-by hash model.
* Adding new Redis scans or shared registry hashes.
