# brainstorm: diagnose missing ak during remote assistant session rebuild

## Goal

Diagnose and fix the skill-server flow where an IM group message resolves a remote assistant instance successfully, but the downstream session lookup and rebuild path continues with `ak=null`, causing `SessionRebuildService` to fail with `Cannot rebuild session ...: no ak associated`.

## What I already know

* The user pulled this from production-like logs on 2026-05-23 around 19:45.
* `ExternalInboundController.invoke` receives `action=chat`, `domain=im`, `sessionType=group`, `sessionId=967176031196676128#dig_myagent_30067369`, `assistant=dig_myagent_30067369`.
* `AssistantInstanceInfoService` reports `remote=true` and `hasAk=false`.
* `AssistantAccountResolverService` reports `decision=allow`, `source=instance`, `assistantAccount=dig_myagent_30067369`, `ak=****`, `remote=true`, `businessTag=myAgent`.
* Immediately after that, `InboundProcessingService` logs `resolved assistant=dig_myagent_30067369, ak=null`.
* `ImSessionManager` looks up and creates the IM session with `ak=null`.
* `GatewayRelayService` initiates tool session rebuild with `ak=null`.
* `SessionRebuildService` uses the legacy String overload and fails with `Cannot rebuild session 183906780303982592: no ak associated`.
* Main was fast-forwarded from `882932f` to `7ce298f` before diagnosis.

## Design Direction

Use an explicit assistant session identity instead of ad hoc `ak == null` checks:

* `LOCAL`: local assistant with a real AK. Session lookup should carry both AK and assistantAccount where available.
* `REMOTE`: remote assistant with optional/no AK. Session lookup should use assistantAccount, and missing toolSessionId should be handled by business/default local toolSession generation rather than AK-based rebuild.
* `DEFAULT`: default-assistant rule identity. The configured AK is virtual for downstream invoke, but session lookup/creation locking should use assistantAccount so it does not behave like a real local Agent AK.

Implementation should centralize:

* identity construction from `ResolveOutcome` / default-assistant rule,
* session lookup key selection,
* async session creation lookup/lock key selection,
* existing-session initialization inside `ImSessionManager.createSessionAsync` so business/default existing sessions do not fall back to legacy String rebuild.

## Assumptions

* `AssistantResolve success ... ak=****` is not proof of a real AK because `SensitiveDataMasker.maskToken(null)` renders `****`; `AssistantInstanceInfoService hasAk=false` is the decisive log signal.
* No-AK remote assistants are valid business-routable assistants and must not be forced through AK-based rebuild.
* Scope starts in `skill-server`; `ai-gateway` changes are out of scope unless the skill-server-to-gateway contract proves incomplete.

## Requirements

* Trace why no-AK remote/default assistants are entering AK-based session lookup/rebuild paths.
* Ensure session lookup, async creation, and rebuild use the assistant identity strategy instead of scattered AK null checks.
* Avoid regressing existing default assistant, business scope, and no-AK remote assistant flows.
* Add or update focused tests around the failing path.

## Acceptance Criteria

* [ ] The logged no-AK remote assistant flow no longer reaches `SessionRebuildService: Cannot rebuild session ...: no ak associated`.
* [ ] Existing no-AK remote assistant behavior remains intentional and covered.
* [ ] Default assistant session lookup/creation uses assistantAccount routing rather than treating virtual AK as a real local route key.
* [ ] Relevant `skill-server` tests pass.
* [ ] GitNexus impact analysis is run before edits, and detect-changes is run before finishing.

## Definition of Done

* Tests added or updated for the repaired path.
* Targeted module test command run.
* Trellis task can be marked ready for implementation or completed with notes.

## Out of Scope

* Reworking the full assistant instance routing architecture.
* Changing production SysConfig or gateway routing unless code proves it is required.
* Broad miniapp UI changes.

## Technical Notes

* Pull result: `git pull --ff-only origin main` updated to `7ce298f`.
* Initial symptom chain: `ExternalInboundController -> AssistantAccountResolverService -> InboundProcessingService -> ImSessionManager -> GatewayRelayService -> SessionRebuildService`.
* GitNexus impact: `processChat` CRITICAL, `ImSessionManager.createSessionAsync` HIGH, reply/rebuild methods MEDIUM, `SkillSessionService.findByBusinessSession` LOW.
