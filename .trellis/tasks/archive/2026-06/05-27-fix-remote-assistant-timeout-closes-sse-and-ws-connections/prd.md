# Fix Remote Assistant Timeout Closing SSE and WS Connections

## Goal

When a remote assistant streaming chat call times out, the gateway must close the underlying cloud streaming connection instead of only returning a timeout error downstream. This prevents SSE input streams and cloud WebSocket sessions from staying open after the conversation has already failed.

## What I Already Know

* User-reported symptom: after the remote assistant conversation API times out, the SSE and WS long-lived connections are not actually disconnected.
* The relevant gateway path is `CloudAgentService.invokeStreaming(...)`, which wires `CloudConnectionLifecycle`, `CloudProtocolClient`, and protocol strategies.
* `SseProtocolStrategy` closes the HTTP response stream only through the `CloudConnectionHandle.onCancel(...)` close action or normal stream completion.
* `WebSocketProtocolStrategy` aborts the Java WebSocket and releases its close latch only through the same `CloudConnectionHandle.onCancel(...)` close action.
* Current timeout handling emits a cloud error but does not cancel the `CloudConnectionHandle`, so protocol strategies can keep blocking on `readLine()` or `closeLatch.await(...)`.

## Requirements

* On first-event timeout, idle timeout, or max-duration timeout, cancel the active cloud connection handle.
* Preserve existing one-shot `tool_error` behavior: timeout and transport error must not both emit duplicate errors.
* Preserve explicit `abort_session` behavior and its late-error suppression.
* Cover both SSE and WebSocket protocol strategy cleanup in tests.

## Acceptance Criteria

* [ ] A lifecycle timeout closes the active SSE input stream through the registered cancel action.
* [ ] A lifecycle timeout aborts the active cloud WebSocket through the registered cancel action and releases the blocking wait.
* [ ] Timeout still relays exactly one `tool_error` downstream.
* [ ] Explicit `abort_session` remains idempotent and suppresses late connection errors.
* [ ] Focused `ai-gateway` tests pass.

## Definition of Done

* GitNexus impact analysis is run before modifying symbols.
* Tests are added or updated for timeout-driven cleanup.
* `gitnexus_detect_changes()` confirms the affected scope before finishing.
* Risk and rollback notes are captured in the final handoff.

## Technical Approach

Use the existing cancellation path as the source of truth for physical connection cleanup. The timeout callback in `CloudAgentService` should cancel the same `CloudConnectionHandle` used by `abort_session`; this reuses the already-registered SSE stream close action and WebSocket abort/latch action instead of adding protocol-specific timeout branches.

## Out of Scope

* Changing timeout durations or configuration keys.
* Changing remote assistant routing, `assistantAccount`, `remoteType`, or cloud profile mapping.
* Changing downstream skill-server browser/external WebSocket lifecycle unless code inspection proves it is part of this bug.

## Technical Notes

* GitNexus query found the likely symbols: `CloudAgentService.invokeStreaming`, `CloudConnectionLifecycle`, `SseProtocolStrategy`, `WebSocketProtocolStrategy`, and `CloudConnectionHandle`.
* Existing memory confirms prior cloud streaming work kept SSE boundary semantics in `SseProtocolStrategy` and remote assistant routing work was centered on assistant-instance lookup; this task should stay focused on timeout cleanup.
