# diagnose externalws active push duplicate messages

## Goal

Fix intermittent duplicate active-push messages from local agent WebSocket events to external WebSocket clients in multi-pod deployments.

## What I Already Know

- Production symptom: with 16 skill-server pods and 16 ai-gateway pods, one active push can appear 31 times at externalws.
- The issue mainly happens when the local agent proactively emits messages through the gateway WebSocket path, not when an external invoke request initiates the turn.
- Gateway L3 broadcast sends unknown-route agent upstream messages to all gateway instances holding skill-server source connections.
- Skill-server session ownership should converge those duplicates to one owner, but the current SS relay path treats Redis `PUBLISH` subscriber count `0` as a hard relay failure and takeover signal.
- In Redis Cluster or cloud Redis proxy deployments, `RedisTemplate.convertAndSend` subscriber count may be only same-node diagnostic data, not an end-to-end delivery ACK.
- Existing external WS delivery already uses `publishToExternalRelayBestEffort`, where publish success is enough and only exceptions cause fallback.

## Requirements

- Do not treat SS relay `convertAndSend` subscriber count as delivery failure.
- Preserve dead-owner recovery through the existing owner heartbeat / loopback self-check path.
- Prevent the 16-pod broadcast plus CAS-forward pattern from multiplying one active-push event into 31 downstream externalws sends.
- Keep the change scoped to skill-server relay semantics and tests.

## Acceptance Criteria

- [ ] Remote SS owner relay returns after a successful publish even when same-node subscriber count is `0`.
- [ ] The router does not attempt takeover when SS relay publish was accepted with subscriber count `0`.
- [ ] Actual publish exceptions still fall through to dead-owner checks / takeover fallback.
- [ ] Tests cover Redis Cluster-style `convertAndSend(...)=0` without local processing.
- [ ] Existing external WS L1/L2 behavior remains unchanged.

## Definition of Done

- Focused skill-server tests pass.
- GitNexus impact was checked before editing modified symbols.
- `gitnexus_detect_changes()` confirms the affected scope before finishing.

## Technical Approach

- Add a best-effort SS relay publish helper in `RedisMessageBroker`, mirroring external relay semantics.
- Change `GatewayMessageRouter.route` to call the best-effort helper for remote owner relay. Successful publish returns immediately regardless of subscriber count.
- Use publish exception / helper failure as the only relay-publish failure signal, then keep the existing heartbeat/CAS takeover fallback.
- Update `SsRelayAndTakeoverTest` expectations so `publish=0` alone no longer causes takeover.

## Out of Scope

- Changing ai-gateway L3 broadcast topology.
- Changing externalws connection registry ownership.
- Adding protocol fields or frontend behavior.

## Technical Notes

- Key files inspected: `ai-gateway/.../SkillRelayService.java`, `skill-server/.../GatewayMessageRouter.java`, `skill-server/.../RedisMessageBroker.java`, `skill-server/.../ExternalWsDeliveryStrategy.java`.
- 31-message explanation: original SS owner can process 15 relayed copies plus its own broadcast copy; a CAS winner can process its own copy plus 14 forwarded conflict copies. That totals 31 in a 16-SS-pod cluster.
- Relevant spec: `.trellis/spec/skill-server/backend/conventions.md` states Redis pub/sub subscriber count is diagnostic-only in Redis Cluster / cloud Redis proxy scenarios.
