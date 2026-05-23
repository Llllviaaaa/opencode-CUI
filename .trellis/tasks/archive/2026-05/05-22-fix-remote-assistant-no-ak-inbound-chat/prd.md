# Fix Remote Assistant No-AK Inbound Chat

## Problem

IM/external inbound chat can be blocked when the assistant instance lookup succeeds but the returned assistant has no AK. Production log example:

- `AssistantInstance.query success ... remote=false, hasAk=false`
- `AssistantResolve local_missing_ak: decision=unknown`
- `processChat: reason=assistant_check_unknown, decision=block-unknown`

This means an existing remote/business assistant without a local AK is treated as unknown before the business routing path has a chance to run.

## Goal

When assistant instance lookup confirms the assistant account exists, absence of AK must not make inbound chat fail as `UNKNOWN`. The flow should keep controller logic light and let resolver/session/message routing carry the assistant kind into the downstream route strategy.

## Acceptance Criteria

- Existing assistant instance with no AK resolves as `EXISTS`, not `UNKNOWN`.
- No-AK assistant instances use assistant account identity for session lookup/creation and business routing.
- Inbound chat no longer blocks with `assistant_check_unknown` for this case.
- Unit tests cover resolver and info/routing behavior for no-AK instance lookup.
