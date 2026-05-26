# Diagnose Redis relay self-check and multi-instance streaming gaps

## Goal

Locate and fix the startup Redis relay self-check reconnect error and the multi-instance path where agent replies are persisted to history but streaming events are not delivered back to the miniapp websocket.

## What I Already Know

* On startup, `SkillInstanceRegistry` logs `Self-check failed: own relay channel has 0 subscribers, attempting reconnect`.
* The reconnect path calls `RedisMessageBroker.startListenerContainer`, after which `RedisMessageListenerContainer` aborts subscription with `RedisSystemException: Unknown redis exception`.
* When reconnect fails, `SkillInstanceRegistry` skips heartbeat to trigger takeover.
* In single-instance deployment, miniapp send and receive works normally.
* In multi-instance deployment, the agent reply exists in history, but miniapp does not receive streaming websocket events for that reply.
* Websocket connections themselves are reported as healthy.

## Assumptions

* The two symptoms are likely related to skill-server Redis relay listener registration or ownership checks across instances.
* The history write path is succeeding, so the loss is probably after upstream reply handling and before websocket delivery on the correct instance.
* The first useful proof will be code-level evidence for where relay subscription count is checked, where reconnect is attempted, and how streaming events route to local or remote websocket connections.

## Requirements

* Identify the root cause of the startup self-check error.
* Identify why multi-instance streaming events are not delivered to miniapp websocket while history persistence succeeds.
* Keep the diagnosis scoped to skill-server relay / websocket delivery unless evidence shows the gateway or miniapp protocol is involved.
* If code changes are needed, preserve single-instance behavior.

## Acceptance Criteria

* [x] Startup self-check recovery no longer calls shared `RedisMessageListenerContainer.stop()` / `start()` after a single relay-channel recovery miss.
* [x] Multi-instance miniapp streaming path remains protected because `user-stream:*` subscriptions are no longer torn down by relay self-check fallback.
* [x] Persisted history behavior remains unchanged; no persistence code was modified.
* [x] Verification includes focused relay / heartbeat / miniapp stream tests and full `skill-server` test suite.

## Definition of Done

* Tests added or updated for the affected relay / websocket behavior.
* Lint or package-level tests run for the changed module.
* `gitnexus_detect_changes()` confirms only expected symbols and flows are affected before committing.
* Any high-risk impact findings are surfaced before implementation.

## Out of Scope

* Changing miniapp UI rendering unless backend evidence shows the websocket event payload is malformed.
* Reworking gateway assistant routing or AK behavior unless the relay diagnosis points there.
* Broad Redis infrastructure or deployment changes outside app-owned listener/reconnect behavior.

## Technical Notes

* Branch: `codex/redis-relay-self-check-streaming-diagnosis`
* Task created from user report on 2026-05-23.
* Initial log anchors: `SkillInstanceRegistry`, `RedisMessageBroker.startListenerContainer`, `RedisMessageListenerContainer`.
* GitNexus impact before editing:
  * `forceReconnectListenerContainer`: MEDIUM. Direct production caller is `SkillInstanceRegistry.refreshHeartbeat`; direct tests cover reconnect behavior.
  * `refreshHeartbeat`: LOW. Direct production caller is `startScheduling`.
  * `publishMessage`: HIGH. Avoided as an edit point because it affects multiple delivery flows.
* Diagnosis:
  * Miniapp outbound stream delivery goes through Redis `user-stream:{userId}`. `MiniappDeliveryStrategy` and `StreamMessageEmitter` publish to the user channel; `SkillStreamHandler` only pushes to the browser after the ws-holding instance receives that Redis broadcast.
  * `forceReconnectListenerContainer` fallback currently calls shared `RedisMessageListenerContainer.stop()` / `start()`. That shared container owns `ss:relay:*`, `ss:external-relay:*`, `agent:*`, and `user-stream:*`, so a relay self-check recovery can interrupt miniapp user-stream subscriptions.
  * The user's timestamps show a roughly two-second gap between self-check and `start()` failure, matching the current re-subscribe timeout before fallback to whole-container restart.
  * Redis 6.0 supports `PUBSUB NUMSUB`, but Redis Cluster / cloud Redis proxy deployments only report Pub/Sub counts for the node handling the command. Using `NUMSUB` as heartbeat hard truth can falsely report `0` when the subscription connection and probe connection land on different nodes.
* Implemented change:
  * `SkillInstanceRegistry` self-check now calls `RedisMessageBroker.verifySubscriptionDelivery`, which publishes a loopback probe to `ss:relay:{instanceId}` and waits for the local listener to receive it. This validates the real publish -> Redis -> listener path instead of relying on node-local subscriber counts.
  * `resubscribeActiveListener` now performs channel-scoped `removeMessageListener` + `addMessageListener` before validating subscriber restoration.
  * `forceReconnectListenerContainer` no longer falls back to whole-container stop/start after channel recovery fails. It returns `false`, preserving `SkillInstanceRegistry`'s heartbeat-skip takeover signal without disrupting unrelated `user-stream:*` subscriptions.
  * `physicalSubscriberCount` remains available as a diagnostic helper, but no longer drives heartbeat self-check.
* Verification:
  * `mvn clean test "-Dtest=RedisMessageBrokerTest,SkillInstanceRegistryTest"` passed.
  * `mvn test "-Dtest=RedisMessageBrokerTest,SkillInstanceRegistryTest,SkillStreamHandlerTest,MiniappDeliveryStrategyTest"` passed.
  * `mvn test` in `skill-server` passed.
  * `gitnexus_detect_changes(scope=all)` reported low risk and only the expected Redis broker/test symbols.

## Technical Approach

Keep the fix scoped to Redis listener recovery. Do not change miniapp websocket payloads, session ownership, or generic publish behavior. The recovery path should avoid destructive shared-container restart for a single relay self-check miss; it should re-register the affected channel and rely on Spring Data Redis container recovery/backoff for broader connection failures, so `user-stream:*` subscriptions are not torn down by relay self-check.
