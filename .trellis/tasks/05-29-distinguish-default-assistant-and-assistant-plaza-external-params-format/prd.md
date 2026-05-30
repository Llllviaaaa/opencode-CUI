# distinguish default assistant and assistant plaza external params format

## Goal

SS must distinguish the outbound `extParameters` wire format used by the default cloud protocol from the assistant-square cloud protocol. The default protocol keeps `businessExtParam` and `platformExtParam` as JSON strings inside `extParameters`; the assistant-square protocol must send those two fields as JSON values/objects.

## What I already know

* The user observed that default-assistant `externalParams/extParameters` currently carries `businessExtParam` and `platformExtParam` as JSON strings.
* Assistant-square protocol expects these two fields in JSON format, not stringified JSON.
* The change should be made in `skill-server` only.
* `BusinessScopeStrategy` and `DefaultAssistantScopeStrategy` build `CloudRequestContext.extParameters` with `JsonNode` values.
* `DefaultCloudRequestStrategy` and `AssistantSquareCloudRequestStrategy` currently both serialize `context.extParameters` with `objectMapper.valueToTree(ext)`, so the protocol-specific distinction is not explicit at the strategy boundary.
* Existing memory notes say `platformExtParam` is SS-owned and `businessExtParam` moved into `payload.extParameters.businessExtParam`; replay paths previously missed payload-format updates, so tests should cover the strategy-level wire shape.

## Assumptions

* "Default assistant" refers to the default cloud request protocol shape (`DefaultCloudRequestStrategy`), not a request to change default-assistant routing semantics.
* Only `businessExtParam` and `platformExtParam` need special stringification in the default protocol; unrelated `extParameters` keys keep their normal value shape.
* Assistant-square should preserve nested JSON objects/arrays for these two fields.

## Requirements

* Keep the default protocol output compatible with JSON-string `extParameters.businessExtParam` and `extParameters.platformExtParam`.
* Ensure assistant-square protocol output emits those fields as JSON nodes/objects.
* Do not change inbound DTOs, routing choice, `DefaultAssistantScopeStrategy` rule lookup, or GW behavior.
* Add/adjust focused SS tests for both protocol strategies.

## Acceptance Criteria

* [ ] Default protocol test proves `extParameters.businessExtParam` and `extParameters.platformExtParam` are textual JSON strings and parse back to the expected objects.
* [ ] Assistant-square protocol test proves the same fields remain JSON objects in `extParameters`.
* [ ] Existing strategy routing behavior remains unchanged.
* [ ] Focused Maven tests pass for the changed SS test classes.

## Out of Scope

* Changing GW cloud invocation or response decoder behavior.
* Changing `PendingChatRequest`, replay reconstruction, or personal-scope relay payloads unless tests reveal the same protocol boundary bug there.
* Changing SysConfig profile names or default mappings.

## Technical Notes

* Main files inspected:
  * `skill-server/src/main/java/com/opencode/cui/skill/service/cloud/DefaultCloudRequestStrategy.java`
  * `skill-server/src/main/java/com/opencode/cui/skill/service/cloud/AssistantSquareCloudRequestStrategy.java`
  * `skill-server/src/main/java/com/opencode/cui/skill/service/scope/BusinessScopeStrategy.java`
  * `skill-server/src/main/java/com/opencode/cui/skill/service/scope/DefaultAssistantScopeStrategy.java`
  * `skill-server/src/main/java/com/opencode/cui/skill/service/PlatformExtParamBuilder.java`
* Focus tests:
  * `skill-server/src/test/java/com/opencode/cui/skill/service/cloud/CloudRequestBuilderTest.java`
  * `skill-server/src/test/java/com/opencode/cui/skill/service/cloud/AssistantSquareCloudRequestStrategyTest.java`
  * `skill-server/src/test/java/com/opencode/cui/skill/service/scope/ExtParametersIntegrationTest.java`
