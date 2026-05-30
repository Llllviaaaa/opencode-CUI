# diagnose assistant square streaming closes after sse events

## Goal

Diagnose and fix the assistant-square protocol path where the gateway receives streamed SSE events from the assistant plaza service, but downstream transmission stops immediately after the initial streamed events.

## What I Already Know

* The failing request is an IM group chat routed through `POST /api/external/invoke` in skill-server and then WS relay into ai-gateway.
* Skill-server resolves `assistantAccount=a_mockstand`, `ak=S008026`, `remote=true`, `businessTag=xxx`.
* Gateway routes the business-scope chat to `CloudAgentService` and connects with `cloudProfile=assistant_square`, `protocol=sse`, `decoder=assistant_square`.
* Gateway receives one `route` event and several `message` events from SSE, all with `protocolType=5` and the same `messageId=889434587260903425`.
* Immediately after the inbound message events, gateway logs `[CLOUD_AGENT] Connection closed by lifecycle`.
* The outbound request body includes assistant-square-shaped `extParameters` as an object containing `businessExtParam` and `platformExtParam`.

## Assumptions

* The close is likely caused inside ai-gateway lifecycle or assistant-square SSE decoding/terminal handling, not by initial routing from skill-server to gateway.
* The expected behavior is to keep streaming assistant-square `message` events downstream until the upstream SSE stream emits its real terminal condition or disconnects normally.

## Requirements

* Preserve assistant-square route selection and request payload format.
* Do not break default-assistant or other cloud protocol decoders.
* Make the downstream relay continue for assistant-square streaming message events.
* Keep logging useful enough to distinguish inbound SSE receipt from downstream delivery and lifecycle close.

## Acceptance Criteria

* [x] Root cause is traced from the actual gateway stream lifecycle path.
* [x] Any modified symbol has GitNexus impact analysis recorded before editing.
* [x] Focused tests cover assistant-square streamed `message` events and the close/terminal condition involved in this bug.
* [x] Existing relevant ai-gateway tests pass.
* [x] `gitnexus_detect_changes()` confirms the changed scope matches the expected files and flows.

## Out of Scope

* Changing assistant-square authentication or endpoint selection unless the root cause proves it necessary.
* Reworking skill-server session rebuild or IM inbound routing.
* Broad protocol refactors beyond the minimal fix for stream continuation.

## Technical Notes

* Logs show gateway receives SSE at `SseProtocolStrategy` before the lifecycle close, so the first inspection target is the assistant-square decoder and `CloudAgentService` stream-consumer lifecycle behavior.
* Prior project memory says `SseProtocolStrategy.handleDataLine(...)` is the correct raw SSE boundary for SSE observability, and `CloudAgentService` should avoid duplicate SSE decoded-event logging.
* Current assistant-square decoder dispatches by top-level `protocolType`; the real log uses `protocolType="5"`, while existing tests only cover missing protocol type and `"standard"`.
* Current standard protocol handler reads `messageId`, `messageType`, and `messageBody` from the top level; the real log nests those fields inside top-level `data`.
* GitNexus impact before edits: `AssistantSquareSseEventDecoder` LOW risk, direct impact limited to `AssistantSquareSseEventDecoderTest`; `StandardProtocolHandler` LOW risk, direct impact limited to `StandardProtocolHandlerTest`.
* Fix: normalize assistant-square SSE envelopes by routing `protocolType="5"` to the standard handler and passing nested `data` to the handler, while preserving top-level metadata fields for compatibility.
* Fix: extract text from object-shaped `messageBody.text` / `messageBody.content` before falling back to JSON string content.
* Validation: `mvn.cmd "-Dtest=AssistantSquareSseEventDecoderTest,StandardProtocolHandlerTest,SseProtocolStrategyTest" test` passed 38 tests; `mvn.cmd test` in `ai-gateway` passed 415 tests; `git diff --check` passed; GitNexus `detect_changes(scope=all)` reported low risk across 4 changed files and no affected processes.
