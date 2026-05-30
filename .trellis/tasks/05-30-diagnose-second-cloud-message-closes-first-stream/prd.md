# diagnose second cloud message closes first stream

## Goal

Find and fix why sending a second message to a cloud assistant while the first assistant-square SSE response is still streaming closes the first message's long-lived connection.

## What I already know

* The user can reproduce by sending one cloud-assistant message, waiting while it is streaming, then sending another message before the first stream finishes.
* The observed result is that the first message's long connection is closed.
* Recent assistant-square work changed `protocolType="5"` standard SSE decoding and emits `session.status=busy/idle`.
* Memory notes say prior abort/no-active-connection work found cloud stream cancellation lives in `CloudAgentService.cancelStreamingConnection(...)`; this bug may be in the same lifecycle/routing area but must be rechecked from current code.

## Assumptions (temporary)

* Overlapping cloud turns should be allowed when they have distinct `toolSessionId` / message lifecycle identity.
* A second `chat` should not cancel an existing stream unless it is explicitly an `abort_session` for that stream or the product intentionally enforces one active stream per session.
* The current symptom likely comes from active lifecycle ownership keyed too broadly, route overwrites, or new-chat cleanup using `ak` / session identity instead of stream identity.

## Open Questions

* Is overlapping streaming intended to be allowed within the same WeLink session/topic, or should the newer message replace the older one? The user's report suggests the former, but code must confirm expected behavior.

## Requirements (evolving)

* Trace the gateway path from skill-server chat invoke into cloud route selection and SSE lifecycle registration.
* Identify exactly which key/path closes the first connection when a second cloud chat starts.
* Preserve explicit abort behavior: `abort_session` must still cancel the targeted active stream.
* Avoid changing assistant-square payload/event decoding unless the lifecycle closure is caused by decoded terminal/status events.

## Acceptance Criteria

* [ ] Root cause is proven from current code, not inferred only from logs.
* [ ] A regression test reproduces two overlapping cloud chat invocations and verifies the first lifecycle is not closed by the second.
* [ ] Existing abort-session tests still pass.
* [ ] Focused ai-gateway cloud lifecycle tests pass.
* [ ] ai-gateway full test suite passes if implementation changes are made.
* [ ] GitNexus impact and detect_changes are run for changed symbols.

## Definition of Done

* Tests added/updated for the overlap scenario.
* No regression to explicit abort routing.
* Whitespace check passes.
* Risk and rollback notes captured in final handoff.

## Out of Scope

* Changing cloud assistant request payload fields unless required by the root cause.
* Changing skill-server session creation/rebuild unless gateway lifecycle scope is proven innocent.
* Supporting cancellation of multiple historical streams in this task.

## Technical Notes

* Suspect files from initial search: `CloudAgentService`, `CloudConnectionLifecycle`, `SseProtocolStrategy`, and `CloudAgentServiceTest`.
* GitNexus context for `CloudAgentService.handleInvoke` shows outgoing calls to `cancelStreamingConnection`, `invokeStreaming`, and `invokeRemoteRoute`.
