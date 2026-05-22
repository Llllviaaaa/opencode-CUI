# Fix Default Assistant Virtual Account Send Message Routing

## Goal

Default assistant sessions use virtual `ak` and virtual `assistantAccount` injected from `default_assistant_rule`. Sending messages must not require that virtual account to exist in the upstream assistant-account resolver, and must not treat the virtual AK as a local personal Agent for online checks.

## Requirements

* Miniapp `sendMessage` and `replyPermission` must continue skipping assistant deletion checks when `(businessSessionDomain, businessSessionType)` matches a default assistant rule.
* Miniapp message routing must select scope with `AssistantScopeDispatcher.getStrategy(domain, type, info)` so default assistant sessions skip local Agent availability checks.
* IM/external inbound `chat`, `question_reply`, `permission_reply`, and `rebuild` must check default assistant rules before resolving `assistantAccount`.
* For inbound default assistant hits, use rule-injected virtual `ak` and virtual `assistantAccount`, skip upstream assistant-account resolution/deletion checks, and continue to build business cloud invokes through the existing default assistant strategy.
* Preserve existing personal and business assistant behavior when no default assistant rule matches.
* Do not restore `senderUserAccount` fallback to owner; sender remains envelope-required and directly propagated.

## Acceptance Criteria

* [x] Miniapp default assistant `sendMessage` does not call assistant account resolver or availability service.
* [x] Miniapp default assistant `replyPermission` does not call assistant account resolver or availability service.
* [x] IM/external default assistant `chat` succeeds when upstream resolver would return `NOT_EXISTS` or `UNKNOWN` for the virtual account.
* [x] IM/external default assistant `question_reply`, `permission_reply`, and `rebuild` do not fail before rule-based virtual AK injection.
* [x] Non-default inbound paths keep existing `NOT_EXISTS -> 410` and `UNKNOWN -> 404` behavior.
* [x] Relevant unit/integration tests cover the regression paths.

## Definition of Done

* Tests added or updated for `SkillMessageController` and `InboundProcessingService` default assistant paths.
* Targeted Maven tests pass for the changed modules.
* GitNexus impact analysis is run before code edits and detect-changes is run before finishing.
* Behavior is documented in task notes if any intentional edge-case trade-off remains.

## Technical Approach

* Reuse `DefaultAssistantRuleService.lookup(domain, type)` as the single source of truth for default assistant detection.
* In miniapp routing, pass domain/type into `AssistantScopeDispatcher` before checking `requiresOnlineCheck()`.
* In inbound processing, add an early default-assistant branch that resolves the rule and injects `rule.ak()` / `rule.assistantAccount()` before legacy resolver logic.
* Keep strategy selection centralized; avoid scattering business/default scope checks outside the dispatcher beyond early default-rule detection needed to avoid resolver calls.

## Decision (ADR-lite)

**Context**: The existing default assistant session creation path injects virtual identity, but some send paths still assume `assistantAccount -> AK` or `AK -> local Agent availability` works. Virtual identities intentionally may not exist upstream.

**Decision**: Treat `(domain, type)` default assistant rule match as higher priority than upstream account resolution and local online checks across send paths.

**Consequences**: Runtime behavior follows the original default assistant contract. Rules must remain present for existing default sessions; if a rule is removed after session creation, the session falls back to legacy validation and may fail, which is acceptable for this task.

## Out of Scope

* Replacing the broader assistant instance info lookup architecture.
* Removing legacy AK-based callback configuration fallback in ai-gateway.
* Changing miniapp UI behavior for selecting or creating assistants.

## Technical Notes

* Related task context: `.trellis/tasks/05-22-unify-assistant-instance-info-lookup/prd.md`.
* Existing behavior doc: `docs/ak-usage-ss-gw.md`.
* Implementation completed 2026-05-22:
  * miniapp `sendMessage` / `replyPermission` now select scope with `(domain, type, info)` and pass `null` info for default assistant hits to avoid virtual AK upstream lookup.
  * inbound `chat`, `question_reply`, `permission_reply`, and `rebuild` now resolve default assistant rule identity before legacy assistant-account resolution.
  * `ImSessionManager#createSessionAsync` now uses domain/type-aware scope dispatch so first inbound default-assistant sessions get the default/business local toolSession path.
* Verification:
  * `mvn "-Dtest=SkillMessageControllerTest,InboundProcessingServiceTest,ImSessionManagerTest" test` passed in `skill-server`.
  * `git diff --check` passed.
  * GitNexus impact was LOW for edited symbols; `detect_changes(scope=all)` reports CRITICAL due changed message entry flows and unrelated dirty files in the worktree, with affected processes centered on route/reply/inbound flows.
* Key files inspected:
  * `skill-server/src/main/java/com/opencode/cui/skill/controller/SkillMessageController.java`
  * `skill-server/src/main/java/com/opencode/cui/skill/service/InboundProcessingService.java`
  * `skill-server/src/main/java/com/opencode/cui/skill/service/scope/AssistantScopeDispatcher.java`
  * `skill-server/src/main/java/com/opencode/cui/skill/service/scope/DefaultAssistantScopeStrategy.java`
  * `skill-server/src/main/java/com/opencode/cui/skill/service/AssistantAccountResolverService.java`
* Spec guidance read:
  * `.trellis/spec/skill-server/backend/index.md`
  * `.trellis/spec/skill-server/backend/directory-structure.md`
  * `.trellis/spec/skill-server/backend/conventions.md`
  * `.trellis/spec/skill-server/backend/error-handling.md`
  * `.trellis/spec/skill-server/backend/type-safety.md`
  * `.trellis/spec/skill-server/backend/logging-guidelines.md`
  * `.trellis/spec/guides/code-reuse-thinking-guide.md`
  * `.trellis/spec/guides/cross-layer-thinking-guide.md`
