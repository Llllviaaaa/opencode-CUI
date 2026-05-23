# fix: skill instance redis relay reconnect failure

## Goal

Stop the repeated Redis relay self-check failure loop where `SkillInstanceRegistry` detects
`ss:relay:{instanceId}` has zero Redis subscribers, attempts a forced
`RedisMessageListenerContainer` reconnect, then logs `Reconnect failed` and Spring logs
`SubscriptionTask aborted with exception`.

## Requirements

* Work from the latest `origin/main` and keep the fix on a dedicated branch.
* Keep the scope inside `skill-server` Redis pub/sub self-healing.
* Preserve takeover safety: if the local relay subscription cannot be restored, this instance
  must still stop refreshing its heartbeat so other instances can take over.
* Re-subscribe the failed local relay channel first, without restarting the shared listener
  container when `PUBSUB NUMSUB` confirms recovery.
* Make forced reconnect robust when Spring Data Redis throws from `start()` during initial
  subscription registration.
* Prevent overlapping forced reconnect attempts against the shared
  `RedisMessageListenerContainer`.
* Add regression tests covering single-channel recovery, restart fallback, failed-start path,
  and existing success/failure behavior.

## Acceptance Criteria

* [x] `NUMSUB=0` recovery first re-submits the local relay channel subscription and avoids
      shared container `stop/start` when `NUMSUB` becomes positive.
* [x] A failed or timed-out channel re-subscribe falls back to `listenerContainer.stop/start`.
* [x] A failed `listenerContainer.start()` leaves the listener container reset for the next
      reconnect attempt.
* [x] Concurrent reconnect calls do not perform overlapping `stop()` / `start()` cycles.
* [x] Existing `NUMSUB>0`, reconnect-success, and reconnect-failure heartbeat behavior remains
      covered by tests.
* [x] Targeted `skill-server` tests pass.
* [x] GitNexus change detection reports only the expected Redis relay self-heal symbols.

## Definition of Done

* Tests added or updated for the changed behavior.
* Targeted Maven tests pass.
* GitNexus impact was checked before modifying changed symbols.
* Existing unrelated `.gitignore` local change remains out of the fix commit.

## Technical Approach

`RedisMessageBroker.forceReconnectListenerContainer` is the narrow fix point. It owns the
`RedisMessageListenerContainer` and already centralizes Redis pub/sub recovery. The method will
guard recovery with a single-flight lock, first re-issue the local relay channel subscription via
the existing active listener, and confirm recovery with `PUBSUB NUMSUB`. Only if that channel-level
re-subscribe fails or times out will it restart the shared listener container. The fallback still
explicitly resets the container after `start()` throws and keeps the boolean contract used by
`SkillInstanceRegistry.refreshHeartbeat`.

## Decision (ADR-lite)

Context: Spring Data Redis 3.4.6 treats initial subscription failure differently from recovery
after an active subscription. A `RedisSystemException` from `LettuceConnection.subscribe` can
complete startup exceptionally and leave callers responsible for cleanup.

Decision: Handle relay recovery in `RedisMessageBroker` instead of changing registry ownership or
adding new cross-layer behavior. Prefer channel-level re-subscribe so the common self-heal path
does not drive Spring Data Redis through the failing whole-container `start()` path.

Consequences: The fix remains local and low-risk, reduces blast radius for a single lost relay
subscription, and avoids interrupting unrelated `agent:*` / `user-stream:*` subscriptions when
channel-level recovery works. If Redis remains unavailable, heartbeat skipping continues by design.

## Out of Scope

* Changing Redis topology or Lettuce/Spring dependency versions.
* Changing session ownership, takeover semantics, or gateway/miniapp behavior.
* Adding a new Redis listener container abstraction.

## Technical Notes

* Branch created from latest `origin/main` after fast-forwarding local `main`.
* Existing local `.gitignore` change for `.cursor/` is unrelated and should not be staged.
* Relevant specs read: `skill-server/backend/index.md`, `conventions.md`,
  `database-guidelines.md`, `logging-guidelines.md`, `directory-structure.md`,
  and shared guides index.
* GitNexus impact:
  * `forceReconnectListenerContainer`: MEDIUM, direct callers are `refreshHeartbeat` and tests.
  * `refreshHeartbeat`: LOW, direct production caller is `startScheduling`.
* Spring Data Redis source inspected locally from `spring-data-redis-3.4.6-sources.jar`.
