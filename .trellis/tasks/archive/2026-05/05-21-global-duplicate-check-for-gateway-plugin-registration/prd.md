# Global Duplicate Check For Gateway Plugin Registration

## Background

`ai-gateway` currently rejects duplicate plugin registrations only when the same AK already has an active WebSocket session inside the current gateway instance. In a multi-gateway deployment, a second plugin using the same AK can register on another gateway instance and overwrite global routing state while the old connection remains alive.

## Goal

When a plugin registers, the gateway must reject the new registration if Redis already shows the same AK is connected through a different gateway instance.

## Behavior

- Keep the existing per-AK registration lock.
- Keep device binding validation unchanged.
- Add a global duplicate check before `AgentRegistryService.register(...)`.
- If the global connection location points to another gateway instance, send `register_rejected` with reason `duplicate_connection` and close with code `4409`.
- Keep the existing local duplicate check for sessions already held by the current instance.
- Use existing Redis route lookup methods instead of introducing raw Redis key strings in the WebSocket handler.

## Notes

This uses the conservative policy: the old connection wins, and the new connection is rejected. If an old gateway dies without cleanup, the Redis route TTL may block a new connection briefly until the key expires.

## Acceptance

- A same-AK registration on a different gateway instance is rejected before DB registration and before local session registration.
- A same-AK registration with no remote global route continues through the existing local duplicate and normal registration flow.
- The analysis document explains the global duplicate behavior, channel validation, and MAC validation in plain language.
