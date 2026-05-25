# Approach A Stream Logging Guidelines

This project keeps SLF4J plus Log4j2 with MDC. Production `INFO` logs should reconstruct the important end-to-end message movement without printing every internal method hop.

## Required Log Context

Every backend log line should visibly include:

| Field | Source |
| --- | --- |
| `service` | Log4j2 service property |
| `instance` | Runtime instance id, normally pod `HOSTNAME` or configured instance id |
| `traceId` | MDC, propagated through request/message payloads |
| `sessionId` | MDC `welinkSessionId` / business session id |
| `ak` | MDC agent key |
| `userId` | MDC user id when known |
| `scenario` | MDC operation scenario |

## Level Matrix

| Level | Use for |
| --- | --- |
| `DEBUG` | Internal implementation detail: cache decisions, routine Redis publish/receive, route branch decisions, per-method flow diagnostics, and any high-frequency detail that has a boundary-level `INFO` event. |
| `INFO` | External or boundary milestones needed in production: accepted request, lifecycle state change, boundary WS/SSE event, delivery success, closed, and final summaries. |
| `WARN` | Recoverable degradation: invalid input that is skipped, route fallback, relay candidate failure, retry, timeout, missing optional dependency, or no active connection. |
| `ERROR` | Failed operation that loses user-visible work, cannot be recovered locally, or needs intervention. Include stack traces when useful and safe. |

## WS/SSE Event Logs

Use `event=ws_event` only at transport boundaries, and print the raw event payload directly:

| Endpoint | Direction | Where to log |
| --- | --- | --- |
| `gw.local_agent` | inbound | ai-gateway receives a raw local-agent WebSocket event. |
| `gw.cloud_agent` | inbound | ai-gateway receives/decodes a cloud-agent stream event. |
| `ss.miniapp` | outbound | skill-server successfully sends a raw WebSocket message to miniapp subscribers. |
| `ss.external_ws` | outbound | skill-server successfully sends a raw WebSocket message to an external source. |

Format:

```text
event=ws_event direction=<inbound|outbound> endpoint=<endpoint> result=<received|sent> payload=<raw json>
```

Do not use `event=ws_event` inside router, translator, delivery strategy, Redis publish, or relay-envelope handling methods. Those internal steps may keep normal `DEBUG`/`WARN`/`ERROR` operational logs, but the event body itself belongs at the actual ingress/egress boundary.

Trace, session, user, ak, scenario, service, and instance context must come from MDC/log pattern rather than being flattened into the event body.

## Propagation Rules

- Miniapp REST calls should send `X-Trace-Id`; backend interceptors keep or generate a trace id if it is missing.
- Process-crossing messages must carry trace context in the payload, then restore MDC at the receiver before logging.
- Executor work should use an MDC task decorator so submitted async work sees the caller's trace context.
- Redis/pubsub logs should identify relay lifecycle and failures, but they should not duplicate full stream event payloads unless Redis is the actual external boundary being audited.
