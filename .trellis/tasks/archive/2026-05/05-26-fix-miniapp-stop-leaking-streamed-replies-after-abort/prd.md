# fix miniapp stop leaking streamed replies after abort

## Goal

When a miniapp user aborts a running cloud/default-assistant response, stop the Gateway-side upstream stream so no more cloud SSE/WebSocket events are produced for that turn.

## Requirements

* Do not add a skill-server session-level TTL suppress marker, because it can incorrectly hide the next user message response in the same miniapp session.
* `abort_session` for cloud/default-assistant sessions must reach AI-Gateway with the current `toolSessionId`.
* AI-Gateway must treat `abort_session` as cancellation, not as an unknown cloud action and not as a `tool_error`.
* Gateway cancellation must close the active SSE input stream or WebSocket connection when possible.
* If `abort_session` lands on a different GW instance than the active cloud stream, Gateway must relay the control frame to the owner GW and cancel there.
* Starting a new chat should use a fresh active connection handle and should not be blocked by a previous abort marker.

## Acceptance Criteria

* [x] Default-assistant `abortSession` sends an `abort_session` invoke to Gateway when a tool session exists.
* [x] Cloud/default-assistant `abort_session` cancels the active Gateway streaming connection by `toolSessionId` or `welinkSessionId`.
* [x] Gateway suppresses cancellation-caused errors and does not relay `tool_error` for a user abort.
* [x] SSE cancellation closes the response stream and skips decoder tail flush after abort.
* [x] WebSocket cancellation aborts the active cloud WebSocket and releases the waiting thread.
* [x] Existing personal-assistant abort behavior remains unchanged.
* [x] Cross-GW abort relays to the GW instance recorded as the active cloud stream owner.

## Technical Approach

Use Gateway-side active stream cancellation. `CloudAgentService` registers each active streaming invoke with a cancellable connection handle keyed by `toolSessionId` and `welinkSessionId`. On `abort_session`, it cancels that handle and returns without relaying an error. `SseProtocolStrategy` and `WebSocketProtocolStrategy` register their concrete transport close action with the handle.

For multi-GW deployment, `CloudAgentService` also writes a short-lived `gw:cloud-stream:{toolSessionId} -> gatewayInstanceId` owner route. If the receiving GW has no local active stream, it relays a `RelayMessage` with `relayType=to-cloud-control` to the owner GW so the owning process closes its local SSE/WebSocket connection.

Skill Server only changes default-assistant abort routing so the abort invoke reaches Gateway. It does not add a miniapp delivery suppress marker.

## Decision (ADR-lite)

Context: A skill-server TTL suppress marker could block the next response if the user aborts and immediately sends another message in the same session.

Decision: Cancel the active upstream stream inside Gateway and avoid session-level output blocking in Skill Server.

Consequences: This directly stops cloud SSE/WebSocket sources. Any future need for exact turn-level abort ordering should pass a target request/trace id, but this task keeps the current protocol shape and uses existing `toolSessionId`/`welinkSessionId` routing keys.

## Out of Scope

* Frontend UI redesign for the stop button.
* Persisting durable abort suppress state in Redis or DB.
* Changing IM/external delivery behavior.

## Technical Notes

* Current SS default-assistant abort skips Gateway invoke via `SkillSessionFlowService.shouldSendLifecycleInvoke`.
* Current GW cloud routing treats actions outside `chat`, `question_reply`, and `permission_reply` as unknown cloud actions.
* Current SSE/WebSocket protocol strategy APIs are synchronous and need a cancellable handle so abort/timeout can close the real transport.
