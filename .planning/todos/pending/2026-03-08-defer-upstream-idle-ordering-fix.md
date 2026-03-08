---
created: 2026-03-08T22:09:52+08:00
title: Defer upstream idle ordering fix
area: general
files:
  - src/main/pc-agent/EventRelay.ts
  - skill-server/src/main/java/com/opencode/cui/skill/service/OpenCodeEventTranslator.java
  - skill-server/src/main/java/com/opencode/cui/skill/service/StreamBufferService.java
  - skill-miniapp/src/hooks/useSkillStream.ts
---

## Problem

OpenCode live reply events can still arrive in the wrong semantic order: `session.status=idle` is observed before the final `thinking.done` and `text.done` parts. This does not block persistence, but it creates a race for the live UI state machine.

Evidence captured on 2026-03-08 for skill session `21`:
- Browser stream frames showed `seq=20 session.status=idle` before `seq=21 thinking.done` and `seq=22 text.done`, followed by another `seq=23 session.status=idle`.
- `skill-server.log` around `21:36:51` showed session buffer cleanup happening before the final assistant parts were persisted and published.

Current workaround is to harden the miniapp against this ordering. The upstream source of the early `idle` still needs a dedicated fix.

## Solution

Trace the upstream event path and make `idle` terminal only after final assistant parts have been forwarded:
- inspect `pc-agent` forwarding for `session.status` / `session.idle`
- confirm whether OpenCode emits multiple idle-like events for a single prompt
- avoid clearing translator/buffer state before final content-bearing events are emitted
- keep the miniapp-side tolerance even after the upstream fix lands
