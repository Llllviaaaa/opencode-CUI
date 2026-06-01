# Adapt assistantAccount remoteType routing

## Goal

The assistant instance API no longer returns `isRemote`; it now returns `remoteType`, where `0` means local, `1` means assistant-square protocol, and `2` means default cloud protocol. Update GW/SS routing so protocol selection comes from the instance API instead of requiring a SysConfig businessTag-to-profile package mapping.

## What I Already Know

* The user confirmed the upstream contract change: `isRemote` is gone and replaced by `remoteType`.
* Existing SS routing treats a remote assistant as `isRemote == true || remoteProperty non-empty`.
* Existing SS request profile selection uses `cloud_protocol_profile:<businessTag>` to map a business tag to `default` or `assistant_square`.
* Existing GW remote route selection uses `remoteProperty` for endpoint/protocol and uses `bizRobotTag` or fallback business tag as `cloudProfile`.
* `bizRobotTag` is still business metadata and should not be collapsed into the protocol/profile concept when `remoteType` provides the profile directly.

## Assumptions

* `remoteType=0` is local even if `isRemote` is absent.
* `remoteType=1` should select `assistant_square` request strategy / response decoder.
* `remoteType=2` should select `default` request strategy / response decoder.
* Legacy `isRemote` and non-empty `remoteProperty` remain tolerated for cached/older payload compatibility, but new behavior should prefer `remoteType`.

## Requirements

* Add `remoteType` to both GW and SS assistant instance models.
* Use `remoteType` to determine remote/business routability in SS.
* Preserve `bizRobotTag` as business metadata while exposing a protocol profile derived from `remoteType`.
* Make SS cloud request profile selection honor a direct profile name (`assistant_square` / `default`) without a `cloud_protocol_profile:<businessTag>` SysConfig mapping.
* Make GW `CloudConnectionContext.cloudProfile` prefer the profile derived from `remoteType`, falling back to existing `bizRobotTag` / SS-provided hint for compatibility.
* Update focused tests for `remoteType=0/1/2` and legacy compatibility.

## Acceptance Criteria

* [x] `remoteType=1` remote assistant with blank `appKey` resolves as remote/business and sends assistant-square protocol without SysConfig mapping.
* [x] `remoteType=2` remote assistant with blank `appKey` resolves as remote/business and sends default protocol without SysConfig mapping.
* [x] `remoteType=0` with missing `appKey` stays `UNKNOWN` for local assistant integrity.
* [x] GW remote route sets `cloudProfile` from `remoteType` when available.
* [x] Existing legacy `isRemote` / `remoteProperty` behavior remains compatible.

## Verification

* `git diff --check`
* `mvn.cmd clean "-Dtest=AssistantAccountResolverServiceTest,AssistantInstanceInfoServiceTest,AssistantInfoServiceTest,CloudRequestProfileRegistryTest,BusinessScopeStrategyTest" test` in `skill-server`
* `mvn.cmd clean "-Dtest=AssistantInstanceInfoServiceTest,CloudAgentServiceTest" test` in `ai-gateway`
* `gitnexus_detect_changes(scope="all")`

## Definition of Done

* Unit tests updated for SS model/resolver/profile selection and GW remote route profile selection.
* Relevant Maven tests pass for touched modules.
* GitNexus impact analysis is run before symbol edits and detect_changes is run before finishing.

## Out of Scope

* Removing existing SysConfig profile definitions or migrations.
* Changing `default_assistant_rule` behavior unless tests prove it is directly affected.
* Reworking the full cloud request profile registry beyond the direct-profile bypass needed here.

## Technical Notes

* SS choke points: `AssistantInstanceInfo`, `AssistantInfoService`, `AssistantAccountResolverService`, `BusinessScopeStrategy`, `CloudRequestProfileRegistry`.
* GW choke points: `AssistantInstanceInfo`, `CloudAgentService.resolveRemoteRoute`, `CloudResponseProfileRegistry`.
* Existing specs to honor: assistant instance no-AK remote routing, GW remoteProperty route selection, cloud protocol profile behavior, and Redis status cache shape.
