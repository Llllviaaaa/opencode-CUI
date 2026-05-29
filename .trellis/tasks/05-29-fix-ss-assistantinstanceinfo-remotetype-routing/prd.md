# fix SS assistantInstanceInfo remoteType routing

## Goal

Update skill-server routing so `assistantInstanceInfo.remoteType` is the authoritative source for local / assistant-square / default-protocol routing. The current SS implementation still keeps legacy `isRemote` fallback behavior and can still derive cloud protocol profiles through SysConfig businessTag mappings; this task removes that drift for assistant-instance-driven routing.

## What I Already Know

* Upstream `assistantInstanceInfo` no longer returns `isRemote`; it now uses only `remoteType`.
* `remoteType=0` means local assistant, `remoteType=1` means assistant-square protocol, and `remoteType=2` means default protocol.
* Current SS `AssistantInstanceInfo` still has `isRemote` and falls back to `isRemote == true` or non-empty `remoteProperty` when `remoteType` is absent.
* Current SS has `AssistantInstanceInfo.protocolProfile()` mapping `1 -> assistant_square`, `2 -> default`.
* Current `BusinessScopeStrategy.resolveProfile(...)` honors `AssistantInfo.cloudProfile`, but falls back to `CloudRequestProfileRegistry.resolve(businessTag)`, which reads `cloud_protocol_profile` from SysConfig.
* Project specs currently document a legacy fallback for missing `remoteType`; this task intentionally supersedes that with the new upstream contract from the user.

## Requirements

* Treat `remoteType` as the only source of assistant local/remote classification in SS assistant-instance routing.
* Route `remoteType=1` business invocations with cloud profile `assistant_square`.
* Route `remoteType=2` business invocations with cloud profile `default`.
* Treat `remoteType=0` as local even if legacy `remoteProperty` is present.
* Do not infer remote routing from `isRemote` or `remoteProperty`.
* Do not use SysConfig `cloud_protocol_profile` businessTag mapping to choose the package/profile for assistant-instance-driven remote assistants.
* Preserve existing default-assistant rule lookup behavior unless it is directly required by the `assistantInstanceInfo.remoteType` contract.

## Acceptance Criteria

* [x] SS no longer relies on `AssistantInstanceInfo.isRemote` for remote/local classification.
* [x] No-AK `remoteType=1` assistants resolve as business with `cloudProfile=assistant_square`.
* [x] No-AK `remoteType=2` assistants resolve as business with `cloudProfile=default`.
* [x] `remoteType=0` with legacy `remoteProperty` remains local / incomplete when AK is missing.
* [x] Business invoke uses the profile supplied by `AssistantInfo.cloudProfile` for remoteType routes and does not consult SysConfig businessTag mapping for those routes.
* [x] Focused unit tests cover the changed classification and profile-resolution behavior.

## Definition of Done

* Tests added or updated for the changed SS routing contract.
* Focused Maven test run passes for touched skill-server tests.
* GitNexus impact analysis is run before editing touched symbols.
* GitNexus detect changes is run before finishing.

## Technical Approach

Tighten `AssistantInstanceInfo` classification around `remoteType`, then make the business-scope cloud profile path explicit: instance-driven business routes should carry `AssistantInfo.cloudProfile`, and `BusinessScopeStrategy` should resolve that profile directly instead of falling back to businessTag-to-profile SysConfig mapping for those routes.

## Out of Scope

* Reworking ai-gateway remote route resolution.
* Replacing `default_assistant_rule` SysConfig lookup for miniapp default-assistant scenarios.
* Removing SysConfig profile definition support if profile names still need strategy definitions.

## Technical Notes

* Likely touched files: `skill-server/src/main/java/com/opencode/cui/skill/model/AssistantInstanceInfo.java`, `skill-server/src/main/java/com/opencode/cui/skill/service/AssistantInfoService.java`, `skill-server/src/main/java/com/opencode/cui/skill/service/scope/BusinessScopeStrategy.java`, and related tests.
* Existing tests already cover no-AK `remoteType=1/2`, local `remoteType=0`, and business-scope profile hints; they need to be updated to assert the tightened no-legacy-fallback contract.
* Memory notes from previous work point to `AssistantAccountResolverService` / `AssistantInfoService` as the current assistantAccount-to-routing choke points and to a previous SysConfig-vs-instance API mismatch around cloud profile semantics.
