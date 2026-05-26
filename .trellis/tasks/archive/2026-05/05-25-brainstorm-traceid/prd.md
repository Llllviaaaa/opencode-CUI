# brainstorm: full-chain logging and traceId governance

## Goal

Bring ai-gateway, skill-server, and the miniapp entry path to a logging model where one traceId can follow a request across REST, WebSocket, asynchronous tasks, Redis pub/sub, and external callbacks, while every log line identifies the service instance that emitted it and carries enough structured context to diagnose issues without noisy, repetitive logs.

## What I Already Know

- The current code already has MDC helpers in both Java services: `traceId`, `sessionId`, `ak`, `userId`, and `scenario`.
- Current log4j2 patterns include service name, thread, level, `traceId`, `sessionId`, and `ak`, but not `service.instance.id` / `instanceId`, `userId`, or `scenario`.
- REST interceptors read `X-Trace-Id` or generate a traceId, then clear MDC after completion.
- Gateway and skill-server protocol messages often carry `traceId`; `GatewayMessage.ensureTraceId()` preserves an existing traceId or generates a UUID.
- Some Pub/Sub relay paths restore MDC manually, such as external WS relay envelopes, but propagation is not systematic across all Redis listeners or async executors.
- The miniapp API client does not currently attach `X-Trace-Id`; browser-side logging is minimal.
- The user explicitly wants streaming-event logs for local-agent inbound events, cloud-agent inbound events, and outbound stream events sent to miniapp and external clients.
- A previous document exists at `documents/logging-overhaul/logging-overhaul-plan.md`; it is useful context but partly stale because the repo now uses log4j2 config rather than the earlier logback framing in that document.

## Repo Findings

- Current Java backend logging volume is high: roughly 925 `log.debug/info/warn/error` calls across `ai-gateway` and `skill-server`.
- Level distribution from static scan: `warn` 341, `info` 336, `error` 132, `debug` 116.
- Highest-volume files include `GatewayMessageRouter` (76), `RedisMessageBroker` in skill-server (44), `SkillRelayService` (42), `AgentWebSocketHandler` (35), `SessionRebuildService` (35), and `EventRelayService` (31).
- About 250 log lines use ad-hoc bracket tags. The most common are `[ENTRY]` (60), `[EXT_CALL]` (52), and `[EXIT]` (46), plus scattered tags like `[CLOUD_AGENT]`, `[SSE]`, `[AUTH]`, `[RELAY-RX]`, and `[SKIP]`.
- Several logs encode error semantics in message text such as `[ERROR] ...` while still using `warn` or `info`, which makes severity filtering less reliable.
- Some high-frequency transport logs are `info`, including publish/receive events in Redis brokers and many entry/exit statements; these are likely contributors to noisy production logs.
- Async boundaries found so far: Redis listener containers, `ThreadPoolTaskExecutor` for message history refresh, scheduler executors, raw `ScheduledExecutorService`, raw `new Thread` WebSocket senders, and `CompletableFuture` waiting paths.
- `ThreadPoolTaskExecutor` instances in the repo do not currently set a task decorator for context propagation.

## External Research Notes

### Common industry conventions

- OpenTelemetry log records model trace context as top-level fields and defines severity as normalized fields rather than only free-text levels.
- OpenTelemetry compatibility guidance recommends `trace_id`, `span_id`, and `trace_flags` as top-level fields in structured or legacy log formats.
- OpenTelemetry semantic conventions define `service.name` and `service.instance.id`; `service.instance.id` is intended to uniquely identify horizontally scaled service instances.
- Log4j2 Thread Context / MDC is the standard mechanism for associating contextual fields with the executing thread and making them available to log output.
- Spring Boot observability docs call out that async executors need context propagation via a task decorator, otherwise context is lost when switching threads.
- Cloud Logging structured logging recognizes fields such as `severity`, `spanId`, labels, operation, and trace fields; JSON logs are more precise for metadata and multiline messages.

### Sources

- OpenTelemetry Logs Data Model: https://opentelemetry.io/docs/specs/otel/logs/data-model/
- OpenTelemetry Trace Context in non-OTLP Log Formats: https://opentelemetry.io/docs/specs/otel/compatibility/logging_trace_context/
- OpenTelemetry service semantic attributes: https://opentelemetry.io/docs/specs/semconv/registry/attributes/service/
- Log4j2 Thread Context: https://logging.apache.org/log4j/2.x/manual/thread-context.html
- Spring Boot Observability / Context Propagation: https://docs.spring.io/spring-boot/reference/actuator/observability.html
- Google Cloud Structured Logging: https://docs.cloud.google.com/logging/docs/structured-logging

## Requirements

- Every backend log line should include at least `service.name`, `service.instance.id`, `traceId`, `sessionId`, `ak`, `userId`, and `scenario` when available.
- One user/business flow should keep the same traceId across miniapp REST, skill-server, GatewayMessage over WS, ai-gateway, Redis pub/sub relays, async work, and external callbacks.
- Redis pub/sub payloads that cross process boundaries must carry trace context explicitly, not rely only on thread-local MDC.
- Async executor work must capture and restore MDC automatically or through a shared wrapper.
- Logs should have explicit event names / operation names instead of many free-form bracket prefixes.
- The implementation will follow Approach A: keep the current SLF4J/log4j2 stack and add disciplined MDC/logging conventions plus explicit propagation. OpenTelemetry tracing/export is not part of the first implementation.
- Production troubleshooting must be possible from INFO logs. DEBUG may contain extra internal detail, but DEBUG must not be required to reconstruct the important end-to-end message path.
- Stream event logging must cover only the transport boundaries:
  - local agent -> ai-gateway inbound raw WebSocket events.
  - cloud agent -> ai-gateway inbound raw stream events.
  - skill-server -> miniapp outbound raw WebSocket messages.
  - skill-server -> external WebSocket outbound raw WebSocket messages.
- Stream event logs must print the original/raw payload directly in `payload=...`; do not flatten stream fields into separate log arguments.
- Trace, session, user, ak, scenario, service, and instance must come from MDC/log pattern, not from expanding the event payload.
- Internal router, translator, Redis publish, delivery strategy, and relay-envelope handling methods must not emit `event=ws_event`; they can keep normal operational logs at appropriate levels.
- Log levels should be standardized:
  - `debug`: high-frequency internals, cache hit/miss, routine publish/receive, local routing decisions, and per-chunk stream deltas.
  - `info`: lifecycle milestones, externally meaningful state transitions, accepted/delivered/closed summaries, stream done/error/permission/question/session events, and checkpoints needed to reconstruct message movement end to end in production.
  - `warn`: recoverable degradation, retries, fallback paths, invalid external input, route misses that the system can handle.
  - `error`: failed operation needing intervention or losing user-visible work; include exception stack when useful.
- Sensitive values such as tokens, signatures, raw authorization headers, large payloads, and user-provided message bodies should not be logged raw.

## Acceptance Criteria

- [ ] A generated traceId from miniapp or REST entry can be found in logs across skill-server and ai-gateway for a normal sync flow.
- [ ] The same traceId survives at least one async executor boundary.
- [ ] The same traceId survives Redis pub/sub relay boundaries, including SS external relay and GW relay paths.
- [ ] Every backend log line visibly identifies the emitting instance.
- [ ] Local-agent inbound raw WebSocket events are logged at ai-gateway ingress as `event=ws_event direction=inbound endpoint=gw.local_agent payload=<raw json>`.
- [ ] Cloud-agent inbound raw stream events are logged at ai-gateway ingress as `event=ws_event direction=inbound endpoint=gw.cloud_agent payload=<raw json>`.
- [ ] Miniapp outbound raw WebSocket messages are logged only after skill-server successfully sends to miniapp subscribers.
- [ ] External WebSocket outbound raw messages are logged only after skill-server successfully sends to an external WebSocket connection.
- [ ] Router, translator, Redis publish, delivery strategy, and relay-envelope handling methods do not duplicate event payload logs.
- [ ] INFO logs alone are enough to reconstruct the main path of a message from inbound source through routing/relay to outbound delivery.
- [ ] A documented level matrix explains when to use debug/info/warn/error.
- [ ] A static or test-level check covers required MDC keys and propagation utilities.
- [ ] High-frequency transport logs are downgraded or sampled so production INFO is not flooded.

## Feasible Approaches

### Approach A: Structured logging standard with explicit propagation (recommended)

- Keep current SLF4J/log4j2 stack, add MDC keys for instance and scenario, switch log output to structured JSON or a parseable key-value pattern, and introduce small shared propagation helpers for REST, WS, Redis envelopes, and executor tasks.
- Pros: fits current code, low infrastructure risk, directly addresses traceId and instance requirement.
- Cons: still a custom convention; without OpenTelemetry traces, span relationships remain implicit.

### Approach B: Full OpenTelemetry tracing plus log correlation

- Add OpenTelemetry/Micrometer tracing, propagate W3C traceparent, emit trace/span ids into logs, and optionally export traces/logs to an observability backend.
- In practice, this means each request/stream flow becomes a trace, each service operation or transport boundary can become a span, and logs can be correlated to those spans through `trace_id` / `span_id`.
- Pros: best long-term observability model, works with industry tooling.
- Cons: larger dependency/configuration change; may be more than needed for the immediate logging cleanup.

### Approach C: Documentation-only convention plus opportunistic cleanup

- Write a logging guide and fix only the worst noisy logs as touched.
- Pros: fastest and lowest risk.
- Cons: will not guarantee traceId across async/pubsub; likely leaves the current inconsistency mostly intact.

## Proposed MVP

- Use Approach A now, with field names close to OpenTelemetry conventions where practical.
- Add `serviceInstanceId` or `service.instance.id` to MDC/log pattern using `gateway.instance-id` and skill-server `HOSTNAME`/configured instance source.
- Add miniapp `X-Trace-Id` generation/persistence per request or per user action, then propagate through REST.
- Add MDC task decorator/wrapper for existing `ThreadPoolTaskExecutor` and a helper for raw executor/new-thread/scheduler call sites.
- Add Redis relay envelope/context extraction for all process-crossing pub/sub paths, not only external WS relay.
- Add a stream-event audit log helper and use it only at local-agent receive, cloud-agent receive, miniapp WS send, and external WS send boundaries. The helper logs raw payloads directly and relies on MDC/log pattern for trace/session/instance context.
- Normalize event names and levels in the highest-volume files first: `GatewayMessageRouter`, `RedisMessageBroker`, `SkillRelayService`, `EventRelayService`, `GatewayRelayService`, and external delivery paths.

## Out Of Scope (Initial)

- Deploying a full observability backend.
- Replacing all existing logs in one massive pass.
- Logging unrelated raw payload bodies, auth headers, tokens, signatures, or whole envelopes beyond the explicitly requested stream `content` field.
- Enabling full OpenTelemetry export unless explicitly chosen later.
- Changing business behavior or routing semantics.

## Decisions

- Approach A is selected for the first implementation.
- Boundary stream event payloads must be visible at production INFO level.
- Stream event logs must print the raw payload directly instead of flattening fields.
- `event=ws_event` belongs only at GW ingress and SS WebSocket egress; internal flow logs must not duplicate event payloads.
- INFO logs must carry the important end-to-end message transfer checkpoints; DEBUG-only logs are not sufficient for production troubleshooting.

## Definition Of Done

- Repo findings and external research are captured.
- User agrees on scope and approach.
- Implementation context is curated into Trellis JSONL before coding.
- Tests are added/updated for trace propagation across the chosen boundaries.
- Lint/type-check/test commands relevant to changed packages pass.
