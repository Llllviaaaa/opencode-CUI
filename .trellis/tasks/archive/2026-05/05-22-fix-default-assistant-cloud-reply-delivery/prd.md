# fix default assistant cloud reply delivery

## Goal

Default assistant miniapp sessions can send messages to a cloud assistant, and cloud text replies (`text.delta` / `text.done`) must be translated by Skill Server and delivered to miniapp through the existing stream path. The current production symptom is that only `session.status` reaches miniapp while cloud text events are ignored.

## What I Already Know

- Gateway receives cloud events for the same request: `step.start`, `text.delta`, `text.done`, `step.done`, and `session.status`.
- Gateway relays those events to Skill Server as `tool_event` / `tool_done` with `sourceType=skill-server`.
- Skill Server logs `AssistantInfoService upstream returned null` and `parseApiResponse: missing data field` for the virtual default-assistant AK.
- Skill Server logs `Event ignored by strategy translator for session ..., scope=null` for cloud text events.
- `GatewayMessageRouter.handleToolEvent` resolves the `SkillSession`, but currently selects the translator strategy from `assistantInfoService.getAssistantInfo(resolvedAk)` and `scopeDispatcher.getStrategy(info)` only.
- Default assistant sessions have the required routing metadata on `SkillSession`: `businessSessionDomain`, `businessSessionType`, `assistantAccount`, and `ak`.
- Existing outbound invoke flow already uses `scopeDispatcher.getStrategy(domain, domainType, info)` so default-assistant rules can select `DefaultAssistantScopeStrategy`.

## Assumptions

- The default assistant session row is available when cloud events return, because the logs show routing by `welinkSessionId` and local owner takeover succeeds.
- For cloud event translation, the authoritative context should be the persisted session when present, not the virtual AK lookup alone.
- Existing miniapp stream contracts already support `text.delta` / `text.done`; no frontend protocol change is needed.

## Requirements

- Use session metadata when selecting the scope strategy for inbound gateway `tool_event` messages.
- Default assistant sessions must translate cloud text events through `DefaultAssistantScopeStrategy` / `CloudEventTranslator`.
- Existing personal-scope OpenCode events must keep using `PersonalScopeStrategy` / `OpenCodeEventTranslator`.
- Existing personal-scope cloud protocol events (`event.protocol=cloud`) must keep working.
- Existing business assistant IM filtering and miniapp delivery behavior must remain unchanged.

## Acceptance Criteria

- [ ] Given a default-assistant session whose AK lookup returns null, a `tool_event` with `event.type=text.delta` is translated and delivered instead of ignored.
- [ ] Given the same session, `scopeDispatcher.getStrategy(domain, domainType, info)` is used with the session's domain and type.
- [ ] Personal/OpenCode fallback behavior still uses the legacy single-argument strategy path when no session metadata is available.
- [ ] Regression tests cover the default-assistant cloud reply path.
- [ ] Targeted skill-server tests pass.

## Definition of Done

- Code follows the skill-server backend specs.
- GitNexus impact analysis is recorded; HIGH risk is handled with focused tests.
- GitNexus detect_changes runs before finishing.
- No unrelated user changes are reverted.

## Out of Scope

- Changing ai-gateway cloud event protocol shape.
- Adding new miniapp stream message types.
- Refactoring the broader assistant info lookup task.
- Changing Redis route/takeover behavior.

## Technical Notes

- GitNexus impact for `handleToolEvent`: HIGH. Direct caller: `dispatchLocally`; affected flows include `handleSsRelayMessage` and local dispatch.
- Code-spec update added to `.trellis/spec/skill-server/backend/conventions.md` under "Gateway tool_event scope selection".
- Likely implementation: in `GatewayMessageRouter.handleToolEvent`, derive strategy from the resolved `SkillSession` when present:
  - assistant info from `assistantInfoService.getAssistantInfo(session.getAk(), session.getAssistantAccount())` when assistantAccount exists, otherwise `getAssistantInfo(resolvedAk)`.
  - strategy from `scopeDispatcher.getStrategy(session.getBusinessSessionDomain(), session.getBusinessSessionType(), routerInfo)`.
  - keep current `scopeDispatcher.getStrategy(routerInfo)` fallback for missing session.
- Relevant specs read:
  - `.trellis/spec/skill-server/backend/index.md`
  - `.trellis/spec/skill-server/backend/directory-structure.md`
  - `.trellis/spec/skill-server/backend/conventions.md`
  - `.trellis/spec/skill-server/backend/logging-guidelines.md`
  - `.trellis/spec/skill-server/backend/type-safety.md`
  - `.trellis/spec/guides/code-reuse-thinking-guide.md`
  - `.trellis/spec/guides/cross-layer-thinking-guide.md`
