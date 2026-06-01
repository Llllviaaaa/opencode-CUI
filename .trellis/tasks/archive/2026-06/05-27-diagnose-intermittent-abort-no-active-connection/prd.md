# Diagnose Intermittent Abort No Active Connection

## Goal

Find and fix the intermittent miniapp/default-or-cloud assistant abort failure where GW logs `abort_session ignored: reason=no_active_connection` even though a cloud SSE/WS stream is still producing messages.

## What I Already Know

* `abort_session` reaches `CloudAgentService.cancelStreamingConnection`.
* GW cancels the upstream cloud stream through `CloudConnectionHandle.cancel()`.
* SSE cancellation closes the response `InputStream`; WebSocket cancellation calls `ws.abort()`.
* Active connections are currently indexed by `toolSessionId` and `welinkSessionId`, and cross-GW owner routing uses `gw:cloud-stream:{toolSessionId}`.
* The confirmed root cause is that business/default-assistant `chat` invokes were handled synchronously on the GW internal WS message thread. Because SS sticky-routes messages by `toolSessionId`, an `abort_session` for the same session could queue behind the still-running cloud stream and only run after the active handle/owner route had already been removed.
* A secondary observability/key robustness gap was that business/default-assistant outbound messages did not include top-level `welinkSessionId`, so GW could not build/log the session key for those cloud streams.

## Requirements

* Trace the abort path from skill-server `/abort` through GW cloud routing.
* Identify why active cloud connections can be missing for valid abort requests.
* Keep cancellation scoped to the upstream cloud stream; do not close miniapp or skill-server control links.
* Preserve cross-GW abort relay behavior.
* Add or update focused tests for the failing timing/key scenario.

## Acceptance Criteria

* [x] Business/default-assistant cloud invokes no longer block the GW internal WS read thread while a cloud SSE/WS stream is running.
* [x] `abort_session` remains inline, so it is not queued behind a pending chat route task.
* [x] Business/default-assistant chat and abort wire messages include top-level `welinkSessionId`, allowing GW to register and log both tool and session keys.
* [x] Cross-GW abort relay behavior remains covered by existing `CloudAgentServiceTest`.
* [x] Focused GW and SS tests pass with clean recompilation.

## Out of Scope

* Closing miniapp, skill-server, or user-facing WebSocket/SSE links.
* Redesigning the full session routing model.
* Changing cloud assistant business protocol payloads unless the bug requires it.

## Technical Notes

* Main suspect files:
  * `ai-gateway/src/main/java/com/opencode/cui/gateway/service/CloudAgentService.java`
  * `ai-gateway/src/main/java/com/opencode/cui/gateway/service/SkillRelayService.java`
  * `ai-gateway/src/main/java/com/opencode/cui/gateway/service/RedisMessageBroker.java`
  * `skill-server/src/main/java/com/opencode/cui/skill/service/SkillSessionFlowService.java`
  * `skill-server/src/main/java/com/opencode/cui/skill/service/scope/*ScopeStrategy.java`
* Existing close mechanism:
  * `CloudConnectionHandle.cancel()`
  * `SseProtocolStrategy` registers `InputStream.close()`
  * `WebSocketProtocolStrategy` registers `ws.abort()`
