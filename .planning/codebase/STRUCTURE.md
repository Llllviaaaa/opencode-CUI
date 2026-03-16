# Repository Structure

## Top-Level Layout

- `ai-gateway/` - Java gateway service
- `skill-server/` - Java skill/session service
- `skill-miniapp/` - React frontend
- `plugins/message-bridge/` - OpenCode message bridge plugin
- `tests/` - Python system-level tests
- `.agent/` - local agent-related workspace data

## AI Gateway Layout

Main source root:
- `ai-gateway/src/main/java/com/opencode/cui/gateway/`

Subareas:
- `config/` - Spring config and typed properties
- `controller/` - REST API endpoints
- `model/` - DTOs and persisted entities
- `repository/` - MyBatis mapper interfaces
- `service/` - auth, registry, relay, broker, and ID generation logic
- `ws/` - WebSocket handlers for agent and skill connections

Resources:
- `ai-gateway/src/main/resources/application.yml`
- `ai-gateway/src/main/resources/db/migration/`
- `ai-gateway/src/main/resources/mapper/`

Tests:
- `ai-gateway/src/test/java/com/opencode/cui/gateway/`

## Skill Server Layout

Main source root:
- `skill-server/src/main/java/com/opencode/cui/skill/`

Subareas:
- `config/` - CORS, Redis, RestTemplate, scheduling, exception handling
- `controller/` - session, message, and agent query endpoints
- `model/` - sessions, messages, parts, views, commands
- `repository/` - MyBatis mapper interfaces
- `service/` - session/message logic, relay, translation, buffering, snapshots
- `ws/` - inbound stream handler and outbound gateway client

Resources:
- `skill-server/src/main/resources/application.yml`
- `skill-server/src/main/resources/db/migration/`
- `skill-server/src/main/resources/mapper/`

Tests:
- `skill-server/src/test/java/com/opencode/cui/skill/`

## Miniapp Layout

Source root:
- `skill-miniapp/src/`

Subareas:
- `components/` - reusable UI components such as `ConversationView.tsx` and `MessageBubble.tsx`
- `hooks/` - domain hooks such as `useSkillStream.ts` and `useSkillSession.ts`
- `pages/` - assembled screens like `SkillMain.tsx` and `SkillMiniBar.tsx`
- `protocol/` - stream parsing, history normalization, tool rendering
- `utils/` - API client and dev auth utilities

Supporting files:
- `skill-miniapp/src/app.tsx`
- `skill-miniapp/src/main.tsx`
- `skill-miniapp/src/index.css`
- `skill-miniapp/vite.config.ts`
- `skill-miniapp/tsconfig.json`

## Plugin Layout

Source root:
- `plugins/message-bridge/src/`

Subareas:
- `action/`
- `config/`
- `connection/`
- `contracts/`
- `error/`
- `event/`
- `protocol/downstream/`
- `protocol/upstream/`
- `runtime/`
- `types/`
- `utils/`

Supporting directories:
- `plugins/message-bridge/tests/`
- `plugins/message-bridge/scripts/`
- `plugins/message-bridge/docs/`

## Test Layout

Top-level Python test suite:
- `tests/test_gateway_auth.py`
- `tests/test_gateway_agent.py`
- `tests/test_gateway_ws_skill.py`
- `tests/test_skill_session.py`
- `tests/test_skill_message.py`
- `tests/test_skill_stream.py`
- `tests/test_integration.py`
- `tests/test_e2e.py`
- `tests/test_security.py`
- `tests/test_performance.py`
- `tests/test_ha.py`

Shared helpers:
- `tests/conftest.py`
- `tests/utils/api_client.py`
- `tests/utils/ws_client.py`
- `tests/utils/auth.py`

## Structural Observations

- Naming is mostly domain-oriented and consistent across modules.
- The two Java services mirror each other structurally, which helps onboarding.
- The plugin package is the most documentation-rich area of the repository.
- There are both `skill-miniapp/src/app.tsx` and page components such as `skill-miniapp/src/pages/SkillMain.tsx`, so frontend entry composition may have some overlap.
