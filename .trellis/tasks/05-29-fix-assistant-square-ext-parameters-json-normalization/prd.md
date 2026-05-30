# fix assistant square extParameters JSON normalization

## Goal

Correct PR #82 so the default cloud protocol keeps its previous behavior, while the assistant-square protocol emits `extParameters.businessExtParam` and `extParameters.platformExtParam` as JSON values/objects even when upstream context provides them as JSON strings.

## What I already know

* The user clarified that default protocol should not change.
* The assistant-square protocol must adapt the two fields to JSON format.
* `businessExtParam` must be JSON, not a string, on the assistant-square wire body.
* The current branch changed `DefaultCloudRequestStrategy`; that should be reverted.
* `AssistantSquareCloudRequestStrategy` currently uses `objectMapper.valueToTree(ext)`, so any string value in the map remains a JSON string.

## Requirements

* Restore `DefaultCloudRequestStrategy` to the prior `objectMapper.valueToTree(ext)` behavior.
* In `AssistantSquareCloudRequestStrategy`, normalize only `businessExtParam` and `platformExtParam`:
  * JSON object/array values remain JSON.
  * String values containing JSON parse into JSON.
  * Missing/null/empty extParameters behavior stays unchanged.
* Add/update tests so default protocol is unchanged and assistant-square proves both fields are JSON objects when the input values are stringified JSON.

## Acceptance Criteria

* [ ] Default protocol test asserts the two fields remain JSON objects when provided as `ObjectNode`, matching previous behavior.
* [ ] Assistant-square test asserts `businessExtParam` and `platformExtParam` become JSON objects when provided as stringified JSON.
* [ ] Focused Maven test slice passes.
* [ ] PR branch is amended/pushed so PR #82 reflects the corrected behavior.

## Out of Scope

* Changing routing, SysConfig profiles, GW behavior, or default-assistant rule lookup.
* Publishing Trellis task files.
