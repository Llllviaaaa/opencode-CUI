# brainstorm: compare GW cloud config sysconfig and API logic

## Goal

Compare the ai-gateway logic that reads cloud route/config values from SysConfig with the logic that fetches the same configuration through an API, then identify why the API-populated values are wrong.

## What I already know

* The reported issue is in GW when calling an interface to obtain cloud configuration.
* The comparison target is the difference between SysConfig-derived configuration and interface-derived configuration.
* Prior project context includes cloud-route v2 SysConfig fallback, `CallbackConfigService`, `SysConfigController`, config JSON shape, and cache TTL handling.

## Assumptions (temporary)

* The issue is likely a field mapping, default value, cache, or JSON shape mismatch rather than a transport failure.
* The first useful output is a source-backed comparison and suspected root cause; code changes may follow after confirmation.

## Open Questions

* None yet; derive details from code first.

## Requirements (evolving)

* Trace both configuration paths in `ai-gateway`.
* Compare accepted field names, nested JSON shape, defaulting, cache behavior, and value assignment.
* Identify any inconsistent values set by the API path.
* Candidate mismatch found: remote API path sets `CloudConnectionContext.cloudProfile` from `remoteProperty.dataProtocol`, while SysConfig path preserves the request/business tag semantics.
* User confirmed `cloudProfile` should be kept from SS or overwritten from API `bizRobotTag`, not from `remoteProperty.dataProtocol`.
* Deeper auth mismatch: remote API path sets `.authType("none")`, while the instance API supplies `remoteProperty.headers` as an array; only the first `header.type` should be parsed into GW `authType` and then dispatched through `CloudAuthStrategy`.
* User corrected the intended contract: `remoteProperty.headers` is not a list of raw outbound headers for GW to replay. GW must not forward `customKey/customValue` directly to the cloud endpoint for this flow.
* Current `CloudAuthService.applyAuth(..., remoteHeaders)` is the wrong direction: it treats non-empty `remoteHeaders` as a complete replacement for auth strategy dispatch. The expected behavior is `remoteProperty.headers[0].type -> authType -> applyAuth(authType)`.

## Acceptance Criteria (evolving)

* [x] Source-backed comparison of SysConfig path vs API path.
* [x] Clear explanation of the likely wrong value and why it diverges.
* [x] Candidate fix location identified if a code change is needed.
* [x] API remote route resolves `authType` from `remoteProperty.headers[0].type` only.
* [x] API remote route does not forward `customKey/customValue` as raw cloud request headers.
* [x] Remote route keeps SS-provided cloud profile or uses API `bizRobotTag`, never `dataProtocol`.

## Definition of Done

* Tests added/updated if behavior changes.
* Lint/typecheck or focused tests run if code changes.
* `gitnexus_detect_changes()` run before commit if code changes are committed.

## Out of Scope (explicit)

* Broad redesign of cloud routing/configuration.
* Changes outside GW unless the comparison proves a cross-layer contract mismatch.

## Technical Notes

* Task created from user report on 2026-05-26.
* Relevant memory keywords: `CallbackConfigService`, `cloud_route_fallback`, `SysConfigController`, `gw config curl`, `config JSON shape`.
* SysConfig route: `CloudAgentService.handleInvoke` derives `businessTag`, calls `SysConfigFallbackProviderV2.load(ak, scope, businessTag)`, and builds context with `.cloudProfile(businessTag)`.
* API route: `AssistantInstanceInfoService.getInstanceInfo(assistantAccount)` parses `data.remoteProperty`; `CloudAgentService.resolveRemoteRoute` maps `url -> channelAddress`, `commProtocol -> channelType`, `dataProtocol -> RemoteRoute.cloudProfile`, and `invokeRemoteRoute` builds context with `.authType("none")` and `.cloudProfile(remoteRoute.cloudProfile())`.
* `CloudConnectionContext.cloudProfile` is documented and consumed as the cloud protocol/profile name for SSE decoder selection, originally passed from SS invoke payload `cloudProfile`.
* Current wrong behavior: `CloudAuthService.applyAuth(HttpRequest.Builder, appId, authType, remoteHeaders)` returns immediately after applying remote headers, skipping `authType`.
* Current wrong behavior: `SseProtocolStrategy` and `WebHookExecutor` both branch on remote headers non-empty -> `applyAuth(..., remoteHeaders)`, otherwise `applyAuth(..., authType)`.
* Current wrong behavior: `WebSocketProtocolStrategy.applyHeaders` only applies remote headers plus optional `X-App-Id`; it does not dispatch `authType`.
* Historical task `.trellis/tasks/archive/2026-05/05-12-gateway/prd.md` explicitly revoked "profile overrides authType"; auth belongs to callback/API endpoint metadata, including `authType=3 -> integration_token`.
* Additional risk: `WebSocketProtocolStrategy` ignores `authType` even for SysConfig paths, so a websocket route with `authType=soa/apig/integration_token` would not go through the auth strategy unless equivalent `remoteHeaders` are supplied.
* Implementation direction: remove remote raw header forwarding from cloud protocol calls; make remote API path produce a normal `authType` value and let each protocol strategy call `CloudAuthService.applyAuth(..., authType)`.
