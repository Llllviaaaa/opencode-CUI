# Reduce CloudAgentService invokeStreaming complexity

## Goal

Reduce the cyclomatic complexity of `CloudAgentService.invokeStreaming` from 21 to below 20 without changing cloud stream behavior.

## What I Already Know

* The reported complexity issue is in `ai-gateway` `CloudAgentService.invokeStreaming`.
* The method currently handles connection registration, lifecycle timeout/error handling, event route-field enrichment, fallback `messageId` / `partId` injection, MDC stream logging, and relay in one body.
* The existing branch already has uncommitted remoteType/cloudProfile routing changes; this task must preserve them.

## Requirements

* Keep the public and private call surface behavior of `invokeStreaming` unchanged.
* Lower `invokeStreaming` complexity below 20 by extracting cohesive helper methods.
* Preserve timeout behavior, cancellation checks, `errorSent` CAS behavior, fallback `messageId`, fallback `partId`, MDC restore, and SSE duplicate-log suppression.
* Avoid broad refactors outside `CloudAgentService`.

## Acceptance Criteria

* [x] `invokeStreaming` complexity drops below 20.
* [x] Existing `CloudAgentServiceTest` targeted suite passes.
* [x] No whitespace errors from `git diff --check`.
* [x] GitNexus impact analysis and detect_changes are run.

## Definition of Done

* Focused ai-gateway tests pass.
* Trellis context validates.
* Changes are limited to the refactor and task metadata unless tests prove otherwise.

## Technical Approach

Extract the event-handling lambda body into helper methods:

* one helper to enrich incoming cloud events with routing/session fields;
* one helper to apply fallback IDs to `event.properties`;
* one helper to relay with MDC/logging.

Keep helper methods package-private/private and local to `CloudAgentService` so no downstream contract changes.

## Out of Scope

* Changing cloud protocol semantics.
* Changing timeout values or lifecycle behavior.
* Changing remoteType/cloudProfile routing behavior already present on this branch.

## Technical Notes

* Main file: `ai-gateway/src/main/java/com/opencode/cui/gateway/service/CloudAgentService.java`.
* Relevant tests: `ai-gateway/src/test/java/com/opencode/cui/gateway/service/CloudAgentServiceTest.java`.
* Verification:
  * `mvn.cmd clean "-Dtest=CloudAgentServiceTest" test` in `ai-gateway`
  * `git diff --check`
  * `gitnexus_impact(target="invokeStreaming", direction="upstream")`
  * `gitnexus_detect_changes(scope="all")`
