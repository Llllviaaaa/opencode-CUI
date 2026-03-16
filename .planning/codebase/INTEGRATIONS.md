# External Integrations

## Overview

The system integrates several internal and external boundaries to move messages between an OpenCode runtime, a gateway, a skill backend, and user-facing chat surfaces.

## Internal Service Boundaries

### AI Gateway

Relevant files:
- `ai-gateway/src/main/java/com/opencode/cui/gateway/ws/AgentWebSocketHandler.java`
- `ai-gateway/src/main/java/com/opencode/cui/gateway/ws/SkillWebSocketHandler.java`
- `ai-gateway/src/main/java/com/opencode/cui/gateway/service/SkillRelayService.java`
- `ai-gateway/src/main/java/com/opencode/cui/gateway/service/EventRelayService.java`
- `ai-gateway/src/main/java/com/opencode/cui/gateway/controller/AgentController.java`

Responsibilities:
- Accept WebSocket agent connections
- Accept WebSocket connections from `skill-server`
- Authenticate agents with AK/SK signatures
- Route invoke and event payloads across online components

### Skill Server

Relevant files:
- `skill-server/src/main/java/com/opencode/cui/skill/ws/GatewayWSClient.java`
- `skill-server/src/main/java/com/opencode/cui/skill/ws/SkillStreamHandler.java`
- `skill-server/src/main/java/com/opencode/cui/skill/service/GatewayRelayService.java`
- `skill-server/src/main/java/com/opencode/cui/skill/service/GatewayApiClient.java`
- `skill-server/src/main/java/com/opencode/cui/skill/controller/SkillSessionController.java`
- `skill-server/src/main/java/com/opencode/cui/skill/controller/SkillMessageController.java`

Responsibilities:
- Maintain outbound WebSocket connection to `ai-gateway`
- Serve session and message APIs to the frontend
- Fan out stream events to browser clients
- Persist and rebuild session/message history

## Frontend Integration

The miniapp consumes REST and WebSocket APIs.

Relevant files:
- `skill-miniapp/src/utils/api.ts`
- `skill-miniapp/src/hooks/useSkillSession.ts`
- `skill-miniapp/src/hooks/useSkillStream.ts`
- `skill-miniapp/src/hooks/useAgentSelector.ts`

Observed API shapes:
- list online agents
- create/list/get/close sessions
- send chat messages
- fetch message history
- reply to permissions
- send selected content to IM

## OpenCode Plugin Integration

The plugin package bridges local OpenCode and the remote gateway.

Relevant files:
- `plugins/message-bridge/src/index.ts`
- `plugins/message-bridge/src/runtime/BridgeRuntime.ts`
- `plugins/message-bridge/src/connection/GatewayConnection.ts`
- `plugins/message-bridge/src/action/ActionRouter.ts`
- `plugins/message-bridge/src/protocol/downstream/DownstreamMessageNormalizer.ts`
- `plugins/message-bridge/src/protocol/upstream/UpstreamEventExtractor.ts`

Integration contract highlights from `plugins/message-bridge/README.md`:
- downstream message types: `invoke`, `status_query`
- invoke actions: `chat`, `create_session`, `close_session`, `permission_reply`, `abort_session`, `question_reply`
- upstream events include session, message, permission, and question updates

## Persistence And Shared Infrastructure

### MySQL

Migration directories:
- `ai-gateway/src/main/resources/db/migration/`
- `skill-server/src/main/resources/db/migration/`

Mapper files:
- `ai-gateway/src/main/resources/mapper/*.xml`
- `skill-server/src/main/resources/mapper/*.xml`

### Redis

Config files:
- `ai-gateway/src/main/java/com/opencode/cui/gateway/config/RedisConfig.java`
- `skill-server/src/main/java/com/opencode/cui/skill/config/RedisConfig.java`

Used for:
- pub/sub style relay
- heartbeat and route ownership
- transient coordination between gateway instances and skill routing

## Authentication And Security Boundaries

- AK/SK signature validation in `ai-gateway/src/main/java/com/opencode/cui/gateway/service/AkSkAuthService.java`
- internal skill-to-gateway token configured via `skill.gateway.internal-token` in both service configs
- plugin auth payload construction in `plugins/message-bridge/src/connection/AkSkAuth.ts`

## IM Integration

The skill server can forward content to an IM backend.

Relevant files:
- `skill-server/src/main/java/com/opencode/cui/skill/service/ImMessageService.java`
- `skill-server/src/main/resources/application.yml`
- `skill-miniapp/src/hooks/useSendToIm.ts`

Configured endpoint:
- `skill.im.api-url` in `skill-server/src/main/resources/application.yml`

## Operations And Logging

- file logging configured in both backend `application.yml` files
- plugin runtime logs through `client.app.log()` or debug fallback per `plugins/message-bridge/README.md`
- plugin scripts support local stack start/stop, log fetching, and E2E debugging under `plugins/message-bridge/scripts/`

## Integration Risks To Remember

- default credentials and tokens are present in checked-in config files
- multiple protocol translations exist across plugin, gateway, skill server, and miniapp
- any transport shape change likely requires synchronized updates across at least three modules
