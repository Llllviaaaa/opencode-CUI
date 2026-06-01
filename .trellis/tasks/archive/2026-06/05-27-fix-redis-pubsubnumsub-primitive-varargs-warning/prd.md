# fix redis pubsubNumsub primitive varargs warning

## Goal

Fix the codecheck warning `VA_PRIMITIVE_ARRAY_PASSED_TO_OBJECT_VARARG` in `skill-server` without changing Redis pub/sub diagnostic behavior.

## What I Already Know

* The warning is in `RedisMessageBroker.physicalSubscriberCount`, at the Lettuce call `async.pubsubNumsub(channelBytes)`.
* `physicalSubscriberCount` is diagnostic-only; runtime relay health must continue to use `verifySubscriptionDelivery`.
* The existing implementation intentionally uses `connectionFactory.getConnection()` plus Lettuce native async commands to avoid the `RedisTemplate.execute` proxy/cast bug and raw RESP decode issues.
* GitNexus impact for `physicalSubscriberCount` is MEDIUM: 6 direct d=1 callers, all focused `RedisMessageBrokerTest` methods, no indexed business execution flows.

## Requirements

* Replace the primitive-array-to-varargs call shape with an explicit single-channel varargs array.
* Preserve one-channel `PUBSUB NUMSUB` semantics and existing fallback behavior.
* Keep the `connectionFactory.getConnection()` / `RedisConnectionUtils.releaseConnection` path unchanged.
* Verify with the focused `RedisMessageBrokerTest` suite.

## Acceptance Criteria

* [x] `RedisMessageBroker.physicalSubscriberCount` no longer passes raw `byte[]` directly into the varargs call.
* [x] Existing `physicalSubscriberCount_*` tests pass.
* [x] GitNexus detect-changes confirms the modified scope is expected.

## Out of Scope

* Changing Redis relay health checks.
* Reworking Redis listener lifecycle or reconnect behavior.
* Changing external relay fallback semantics.

## Technical Notes

* Relevant specs: `.trellis/spec/skill-server/backend/conventions.md`, `.trellis/spec/skill-server/backend/database-guidelines.md`.
* Relevant code: `skill-server/src/main/java/com/opencode/cui/skill/service/RedisMessageBroker.java`.
* Relevant tests: `skill-server/src/test/java/com/opencode/cui/skill/service/RedisMessageBrokerTest.java`.
