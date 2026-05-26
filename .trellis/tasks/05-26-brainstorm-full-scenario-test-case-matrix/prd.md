# brainstorm: full scenario test case matrix

## Goal

Analyze the current `opencode-CUI` codebase end to end and produce a complete, source-backed test case checklist for CUI/miniapp and IM single/group chat flows across local, remote, and default assistants, including remote default protocol and assistant-market/cloud protocols.

## What I Already Know

* User wants a detailed test case list, not an implementation patch.
* Explicit scenario axes from user: CUI/miniapp, IM direct/group chat, local assistant, remote assistant, default assistant, remote default protocol, assistant-square protocol.
* User clarified on 2026-05-26 that `ImInboundController` / `/api/inbound/messages` is no longer the active IM path; IM uses the external protocol (`/api/external/invoke` + `/ws/external/stream`).
* User clarified that the test document should be readable in local/business language, not primarily in technical class/API vocabulary.
* User then clarified that the simplified business-language version lost too much coverage; final document should combine business-language readability with a complete coverage matrix and detailed per-scenario cases.
* This repo has three Trellis spec layers: `skill-miniapp`, `skill-server`, and `ai-gateway`.
* Memory from prior work says AK/session semantics differ across local routing key, cloud callback lookup key, and virtual AK for default assistants; IM/external resolves `assistantAccount -> ak` before invoke, while default-assistant injects a virtual AK.

## Assumptions

* "Complete coverage" means a functional/system test inventory covering protocol and routing behavior, not exhaustive unit tests for every helper.
* IM direct/group test cases must be written as External protocol cases with `businessDomain=im` and `sessionType=direct|group`.
* Test cases should include happy paths, replay/rebuild/resume paths, stream/persistence behavior, failure/offline paths, and compatibility aliases where code supports them.
* The output can be delivered in this conversation as a checklist, with this PRD recording analysis scope.

## Requirements

* Identify all relevant user-entry and callback-entry flows from current source/docs.
* Build a scenario matrix across channel, chat shape, assistant type, remote protocol family, session state, delivery topology, stream event type, persistence/replay, and failure mode.
* Call out existing coverage versus recommended missing tests where the codebase already contains tests.
* Keep technical source evidence available in task notes, while the user-facing test document should describe scenarios in business language.

## Acceptance Criteria

* [x] Covers miniapp/CUI and IM direct/group chat.
* [x] Covers local assistant, remote assistant, default assistant, business/cloud/assistant-square protocol, and default protocol variants.
* [x] Covers first message, existing session, rebuild/retry, resume/history, tool events, permission/question flows, and finalization.
* [x] Covers single-instance and multi-instance/Redis delivery paths.
* [x] Includes negative/failure scenarios and backward-compatible payload aliases.
* [x] Lists practical test cases with expected results and likely test layer.

## Definition of Done

* Source-backed scenario inventory delivered to user.
* PRD records scope and key findings.
* No production code changed.

## Out of Scope

* Implementing the tests in this task unless user asks later.
* Changing routing/protocol behavior.

## Technical Notes

* Task created from user request on 2026-05-26.
* Relevant docs discovered so far include `docs/ak-usage-ss-gw.md`, `docs/assistant-three-route-sequences.md`, and protocol docs under `documents/protocol/`.
* Test case document created at `test-cases.md`; updated to IM-over-External mainline after user correction, rewritten as a business-language验收用例文档, and expanded again into a complete business coverage matrix plus 87 detailed cases.
* Existing test-document conventions inspected:
  * `documents/im-rest-link/test_cases.md` uses per-case sections with 前置条件 / 操作步骤 / 预期结果 / 验证 SQL or 验证点.
  * `docs/superpowers/specs/2026-04-10-cloud-agent-test-plan.md` uses module matrices plus E2E step lists and coverage gaps.
* Primary entrypoints inspected:
  * `skill-miniapp/src/app.tsx`, `skill-miniapp/src/hooks/useSkillStream.ts`, `skill-miniapp/src/utils/api.ts`
  * `skill-server/.../SkillSessionController.java`, `SkillMessageController.java`, `ExternalInboundController.java`; `ImInboundController.java` is legacy/compat-only for this test plan.
  * `skill-server/.../SkillSessionFlowService.java`, `SkillMessageFlowService.java`, `InboundProcessingService.java`, `ImSessionManager.java`, `GatewayMessageRouter.java`
  * `ai-gateway/.../SkillWebSocketHandler.java`, `AgentWebSocketHandler.java`, `SkillRelayService.java`, `CloudAgentService.java`
* Scenario axes to cover:
  * Channel: miniapp/CUI, IM direct over External, IM group over External, generic External, cloud push/im_push.
  * Assistant identity: personal/local AK, business/remote assistantAccount, default-assistant rule with virtual AK.
  * Protocol: personal opencode/cloud translator, remote default cloud request/decoder, assistant-square request/decoder.
  * Session state: first message, existing ready session, missing toolSessionId, close/abort, rebuild, retry pending, resume/history.
  * Event/delivery: text/thinking/question/permission/tool_done/tool_error/session status, miniapp WS, IM REST, External WS L1/L2 Redis relay, multi-instance owner routing.
  * Failure: validation, deleted/unknown assistant, offline personal assistant, missing callback config, channel/action mismatch, malformed cloud events, nonnumeric assistant-square topicId, callback push mismatch.
* Existing tests already cover many unit/service slices. The highest-value gaps are cross-product integration tests across channel x assistant type x cloud protocol, especially default-assistant + assistant-square, IM group suppress/retry behavior through `/api/external/invoke`, External WS L1/L2 delivery, no IM REST fallback for external-source sessions, and end-to-end cloud callback/push into SS persistence.
