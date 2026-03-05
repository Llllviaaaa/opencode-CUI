# DECISIONS.md — Architecture Decision Records

> **Last Updated:** 2026-03-05

## ADR-001: Transparent Pass-Through Architecture
**Date:** Pre-existing
**Status:** Accepted
**Context:** Need to relay AI coding tool events through multiple backend layers.
**Decision:** Backend services (Gateway, Skill Server) do NOT parse or transform OpenCode event payloads. Protocol adaptation happens only in the Client.
**Consequence:** Future tool support (Cursor, Windsurf) requires only new Client protocol adapters, not backend changes.

## ADR-002: Redis Pub/Sub for Multi-Instance Routing
**Date:** Pre-existing
**Status:** Accepted
**Context:** Gateway and Skill Server may run as multiple instances for scalability.
**Decision:** Use Redis Pub/Sub with `agent:{agentId}` (downlink) and `session:{sessionId}` (uplink) channels for cross-instance routing.
**Consequence:** Requires Redis dependency but enables horizontal scaling without sticky sessions.

## ADR-003: Sequence Number Tracking
**Date:** Pre-existing
**Status:** Accepted
**Context:** WebSocket messages may arrive out-of-order or be lost in transit.
**Decision:** Implement per-session AtomicLong sequence numbers with 3-tier gap detection (warn/recover/reconnect).
**Consequence:** Added complexity but ensures message reliability and ordering.

---

## Phase 1 Decisions

**Date:** 2026-03-05

### ADR-004: Refactor to OpenCode Plugin Format
**Status:** Accepted
**Context:** Current PC Agent is a standalone class (`new PcAgentPlugin()`), but the protocol spec (Layer 0) requires an OpenCode Plugin Hook format that registers via `opencode.json` and receives events through the plugin event hook.
**Decision:** Refactor PC Agent to use the OpenCode Plugin format: `export const PlatformAgent: Plugin = async (ctx) => { ... }`. Use `ctx.client` for all SDK operations instead of creating a standalone client.
**Consequence:** Major refactor of current code; entry point, event reception, and SDK calls all change. But aligns exactly with how OpenCode expects plugins to work.

### ADR-005: Envelope as Platform Protocol, Event as Raw Pass-Through
**Status:** Accepted
**Context:** Need to clarify the relationship between `envelope` and `event` fields in upstream messages.
**Decision:** `envelope` is the platform's own protocol metadata (version, messageId, agentId, sequenceNumber, etc.) used for server-side validation and routing. `event` contains the raw, unmodified OpenCode event — complete transparent pass-through.
**Consequence:** Backend services can use envelope for routing/validation without parsing the event payload.

### ADR-006: MVP SDK Operations — abort + permissions
**Status:** Accepted
**Context:** Protocol spec lists 8 SDK operations but not all are needed for MVP.
**Decision:** Add `session.abort()` and `session.permissions()` (permission reply) to OpenCodeBridge. Skip fork and revert for MVP.
**Consequence:** Enables permission confirmation flow and session abort — critical for UX.

### ADR-007: Bun Runtime Compatibility
**Status:** Accepted
**Context:** Protocol spec states "Plugin runs in OpenCode process via Bun". Current code uses `node:crypto`, `node:os`.
**Decision:** Ensure all code is Bun-compatible. Use `node:` prefix for built-in modules (Bun supports most `node:` APIs). Test under Bun runtime.
**Consequence:** Must verify `node:crypto` (HMAC-SHA256) and `node:os` work under Bun.

### ADR-008: Dual Testing Strategy
**Status:** Accepted
**Context:** No tests exist currently. Need verification strategy for Phase 1.
**Decision:** Both unit tests (mock WebSocket, mock SDK) for PC Agent AND end-to-end verification via Test Simulator.
**Consequence:** More thorough coverage but Test Simulator e2e depends on Phase 2-3 being at least partially ready. Unit tests can be done in Phase 1; e2e in Phase 5.
