# Relax gateway invoke userId validation

## Background

Production group IM rebuild can send `create_session` from skill-server to ai-gateway with a blank top-level `userId`.
That top-level field is not the IM sender and is not the group session id; it is at most an ownership/context hint for the assistant AK.

Current ai-gateway behavior rejects the invoke in `SkillRelayService.validateInvokeMessage` before delivery:

- `source` and `ak` are valid.
- The target local agent is selected by `ak`.
- `userId` is blank, so GW logs `reason=missing_userId` and returns without calling the local agent.

This blocks group session rebuild and loses the pending user instruction.

## Business Decision

For SS -> GW -> local agent invoke delivery:

- `ak` is the routing identity and chooses the connected local/remote agent.
- `source` remains a hard boundary check so one source session cannot spoof another.
- Top-level `userId` is optional attribution/diagnostic context.
- The real IM sender remains in the payload (`sendUserAccount` / related business payload fields), not in top-level `userId`.
- Group sessions may legitimately have no persisted `SkillSession.userId`; GW must not require SS to invent one.

## Scope

In scope:

- Relax ai-gateway `SkillRelayService` invoke validation so missing `userId` no longer blocks delivery.
- Keep source and `ak` validation strict.
- If `userId` is present but does not match the currently registered AK owner, log it as an observation and continue.
- Add tests for missing/mismatched `userId` still dispatching to the agent path.

Out of scope:

- Changing agent registration authentication.
- Changing IM payload sender fields.
- Reworking Redis agent registration keys.
- Broad refactors to route learning or cloud/business invoke strategies.

## Acceptance Criteria

- A `create_session`/invoke with valid `source` and `ak`, but blank `userId`, reaches local delivery, remote relay, or pending queue according to existing `ak` routing.
- A mismatched non-blank `userId` is observable in logs but does not block delivery.
- Existing source mismatch and missing `ak` rejection behavior remains unchanged.
- Tests cover missing `userId`, mismatched `userId`, and existing happy-path delivery.
- No protocol field is added or removed.

## Validation Plan

- Run focused ai-gateway tests for `SkillRelayService`.
- Run focused skill-server tests touched by the previous rebuild fallback to ensure the combined scenario still passes.
- Run `git diff --check`.
- Run GitNexus change detection before finishing.
