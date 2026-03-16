# Testing Map

## Overview

Testing is distributed across Java unit tests, TypeScript/Bun plugin tests, and Python cross-service/system tests.
This gives the repository good breadth, especially around protocol and integration paths.

## Java Service Tests

`ai-gateway/` includes tests for:
- controllers in `ai-gateway/src/test/java/com/opencode/cui/gateway/controller/`
- services in `ai-gateway/src/test/java/com/opencode/cui/gateway/service/`
- WebSocket handlers in `ai-gateway/src/test/java/com/opencode/cui/gateway/ws/`
- models in `ai-gateway/src/test/java/com/opencode/cui/gateway/model/`

`skill-server/` includes tests for:
- controllers in `skill-server/src/test/java/com/opencode/cui/skill/controller/`
- services in `skill-server/src/test/java/com/opencode/cui/skill/service/`
- WebSocket classes in `skill-server/src/test/java/com/opencode/cui/skill/ws/`

Representative files:
- `ai-gateway/src/test/java/com/opencode/cui/gateway/service/AkSkAuthServiceTest.java`
- `ai-gateway/src/test/java/com/opencode/cui/gateway/service/EventRelayServiceTest.java`
- `skill-server/src/test/java/com/opencode/cui/skill/service/GatewayRelayServiceTest.java`
- `skill-server/src/test/java/com/opencode/cui/skill/ws/SkillStreamHandlerTest.java`

## Plugin Tests

The plugin has dedicated unit, integration, and E2E-style tests:
- `plugins/message-bridge/tests/unit/`
- `plugins/message-bridge/tests/integration/`
- `plugins/message-bridge/tests/e2e/`

Notable coverage areas based on file names:
- config validation
- connection lifecycle
- action routing
- protocol normalization
- upstream event extraction
- plugin distribution and load verification

Useful commands from `plugins/message-bridge/package.json`:
- `bun run test`
- `bun run test:unit`
- `bun run test:integration`
- `bun run test:e2e`
- `bun run test:coverage`

## Top-Level Python Tests

The root `tests/` directory validates cross-module behavior.

Covered themes include:
- gateway auth
- agent registration and relay
- skill session CRUD
- message CRUD
- stream behavior
- full integration flows
- end-to-end chat flows
- security
- performance
- high availability

Representative files:
- `tests/test_gateway_auth.py`
- `tests/test_gateway_agent.py`
- `tests/test_gateway_ws_skill.py`
- `tests/test_integration.py`
- `tests/test_e2e.py`
- `tests/test_security.py`
- `tests/test_performance.py`
- `tests/test_ha.py`

## Test Infrastructure

- pytest fixtures and environment setup live in `tests/conftest.py`
- shared HTTP/WebSocket helpers live in `tests/utils/`
- async scenarios use `pytest.mark.asyncio`
- some tests are marked `requires_agent`, `security`, `performance`, `ha`, or `slow`

## Strengths

- test suite spans unit to distributed integration levels
- protocol-heavy code paths appear well represented
- scenario IDs in test names improve traceability
- security and resilience are treated as first-class concerns

## Likely Gaps

- no obvious root-level CI orchestration file was seen in the scanned file list
- frontend component tests are not visible in `skill-miniapp/`
- end-to-end tests appear environment-dependent and may require running services plus live agent availability
- the repository likely relies on manual multi-runtime setup before full verification succeeds
