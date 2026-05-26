# brainstorm: diagnose missing sessionStatus in cloud assistant events

## Goal

Diagnose and fix cloud assistant stream events where outbound miniapp WebSocket payloads can have `type="session.status"` but omit the `sessionStatus` field. The expected protocol shape is that every `session.status` event delivered to miniapp carries a concrete status such as `busy`, `idle`, `retry`, or `completed`.

## What I already know

* User observed a normal outbound example from `SkillStreamHandler.outbound`:
  `{"type":"session.status","seq":68,"emittedAt":"2026-05-25T13:48:46.774843600Z","sessionStatus":"busy","welinkSessionId":"2954570845474787328"}`.
* `StreamMessage` uses `@JsonInclude(NON_NULL)`, so if `sessionStatus` is null the serialized field disappears.
* `CloudEventTranslator.handleSessionStatus()` currently reads only `event.path("sessionStatus")`.
* `CloudEventTranslator.translate()` already treats `status` and `sessionStatus` as aliases when clearing the per-session part sequence counter on idle.
* Cloud events are commonly wrapped under `event.properties`; `translate()` passes `properties` to the handler when present.
* `DefaultAssistantScopeStrategy`, `BusinessScopeStrategy`, and `PersonalScopeStrategy(protocol=cloud)` all delegate cloud event translation to `CloudEventTranslator`.
* GitNexus class impact for `CloudEventTranslator` is MEDIUM. Direct users are scope strategies and translator tests; indirect flow includes `GatewayMessageRouter` cloud `tool_event` routing.

## Assumptions

* The missing field occurs when a cloud `session.status` event uses `status` instead of `sessionStatus`, especially under `properties.status`.
* The correct fix is compatibility at the cloud event translation boundary, not a miniapp fallback.
* No protocol rename is intended; outbound miniapp payload should remain `sessionStatus`.

## Requirements

* Preserve existing `sessionStatus` input behavior.
* Accept cloud `session.status` input with `status` as a backward-compatible alias.
* Support both flat and `properties`-wrapped cloud events through the existing translator path.
* Do not change outbound WebSocket field names.
* Add focused regression coverage for alias handling.

## Acceptance Criteria

* [x] `CloudEventTranslator` translates `{"type":"session.status","sessionStatus":"busy"}` to `sessionStatus=busy`.
* [x] `CloudEventTranslator` translates `{"type":"session.status","status":"busy"}` to `sessionStatus=busy`.
* [x] `CloudEventTranslator` translates `{"type":"session.status","properties":{"status":"idle"}}` to `sessionStatus=idle`.
* [x] Existing cloud event translation tests pass.
* [x] GitNexus change detection reports only the expected translator/test scope.

## Definition of Done

* Focused unit tests added.
* Relevant Maven test(s) pass.
* GitNexus `detect_changes` run before handoff.
* No unrelated changes staged or modified.

## Out of Scope

* Changing miniapp stream consumption.
* Changing GW cloud decoder output shape beyond what is necessary for this symptom.
* Changing the public outbound protocol field from `sessionStatus` to `status`.

## Technical Notes

* Main candidate: `skill-server/src/main/java/com/opencode/cui/skill/service/CloudEventTranslator.java`.
* Regression tests: `skill-server/src/test/java/com/opencode/cui/skill/service/CloudEventTranslatorTest.java`.
* Delivery evidence path: `GatewayMessageRouter.handleToolEvent()` -> scope strategy `translateEvent()` -> `CloudEventTranslator.translate()` -> `StreamMessageEmitter` -> `SkillStreamHandler`.
* Spec sync: `.trellis/spec/skill-server/backend/type-safety.md` now records the cloud `session.status` inbound alias rule (`status` first, `sessionStatus` fallback, outbound remains `sessionStatus`).
* Verification:
  * `mvn -f skill-server/pom.xml -Dtest=CloudEventTranslatorTest test` passed, 28 tests.
  * `mvn -f skill-server/pom.xml -Dtest=PersonalScopeCloudProtocolIntegrationTest test` passed, 4 tests.
  * `mvn -f skill-server/pom.xml "-Dtest=CloudEventTranslatorTest,PersonalScopeCloudProtocolIntegrationTest" test` passed, 32 tests.
  * `mvn -f skill-server/pom.xml test` failed on pre-existing/out-of-scope `SessionRebuildServiceTest` assertions around fallback assistant account fields; the same test class fails when run alone.
  * `mvn -f skill-server/pom.xml -Dtest=SessionRebuildServiceTest test` failed with 7 assertions, all outside `CloudEventTranslator`.
  * `git diff --check` passed.
  * `gitnexus_detect_changes(scope=all)` reported low risk and no affected processes.
