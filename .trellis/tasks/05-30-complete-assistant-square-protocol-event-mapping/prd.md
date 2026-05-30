# complete assistant square protocol event mapping

## Goal

Complete ai-gateway assistant-square standard protocol decoding for the documented `protocolType="5"` SSE stream so skill-server receives displayable stream events, structured standard events, and `session.status` busy/idle markers.

## What I Already Know

* The previous fix already normalizes `protocolType="5"` to the standard assistant-square handler and unwraps top-level `data`.
* The complete protocol places display payloads under `data.messageBody` and process-step content under `data.processStep`.
* Skill-server `CloudEventTranslator` already handles `session.status` with `properties.status` or `properties.sessionStatus`; `idle` clears part sequence / replay state, and `busy` marks live streaming.
* The user confirmed `eventType=question + messageType=TEXT_LIST` is out of scope for this change.

## Requirements

* Keep `protocolType="5"` routed through the standard assistant-square protocol handler.
* Emit `session.status=busy` once at the beginning of a handled assistant-square turn.
* Emit `session.status=idle` after flushing final part/step events on `FINISH`.
* Map standard display events:
  * `message` + `TEXT` -> `text.delta` from `messageBody.text`.
  * `planning` + `PLANNING` -> `planning.delta` from `messageBody.planning`.
  * `think` / `processStep` -> `thinking.delta` from `processStep.message` or `messageEn`.
* Map standard structured events:
  * `searching` -> `searching.properties.keywords` from `messageBody.searching`.
  * `searchResult` -> `search_result.properties.results` from `messageBody.searchResult`.
  * `reference` -> `reference.properties.references` from `messageBody.references`.
  * `askMore` -> `ask_more.properties.askMoreQuestions` from `messageBody.askMore`.
* Map `error` to `tool_error` using `message`, `error`, `messageEn`, or `errorEn`.
* Do not implement athena/uniknow/agentmaker card, image, file, or HTML display protocols in this task.
* Do not map `eventType=question + messageType=TEXT_LIST`.

## Acceptance Criteria

* [x] Assistant-square protocol unit tests cover the documented nested `messageBody` and `processStep` shapes.
* [x] Tests verify exactly one `session.status=busy` and a final `session.status=idle` after flush.
* [x] Tests verify `question + TEXT_LIST` remains ignored except existing boundary behavior.
* [x] Focused ai-gateway decoder tests pass.
* [x] ai-gateway full test suite passes.
* [x] GitNexus impact and detect_changes are run for the changed symbols.

## Out of Scope

* Request payload changes.
* Skill-server translator changes unless gateway output proves incompatible.
* Non-standard assistant-square families: athena, uniknow, agentmaker, cards, images, files, HTML.

## Technical Notes

* Downstream session status shape should be `GatewayMessage.Type.TOOL_EVENT` with `event.type="session.status"` and `event.properties.status`.
* `session.status=idle` should be emitted after part `.done` and `step.done` so persistence/replay cleanup happens after final content reaches skill-server.
* Validation on 2026-05-30: `mvn.cmd clean "-Dtest=AssistantSquareSseEventDecoderTest,StandardProtocolHandlerTest,SseProtocolStrategyTest" test`, `mvn.cmd test`, `git diff --check`, and GitNexus `detect_changes(scope=all)` all passed.
