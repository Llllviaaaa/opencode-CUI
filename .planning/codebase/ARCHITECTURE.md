# Architecture

## System Shape

This is a distributed, message-oriented application rather than a single deployable unit.
The architecture centers on routing chat and tool events across four layers:

1. OpenCode host plugin in `plugins/message-bridge/`
2. gateway service in `ai-gateway/`
3. session/persistence service in `skill-server/`
4. chat UI in `skill-miniapp/`

## High-Level Flow

Representative path:
- user selects an agent and starts a session in `skill-miniapp/src/app.tsx`
- frontend calls REST helpers in `skill-miniapp/src/utils/api.ts`
- `skill-server` creates or loads session state through controller/service/repository layers
- `skill-server` relays invoke commands to `ai-gateway` using `skill-server/src/main/java/com/opencode/cui/skill/ws/GatewayWSClient.java`
- `ai-gateway` routes messages to a connected agent through `ai-gateway/src/main/java/com/opencode/cui/gateway/ws/AgentWebSocketHandler.java`
- local OpenCode plugin executes actions and emits upstream events through `plugins/message-bridge/src/runtime/BridgeRuntime.ts`
- events return through gateway and skill server, then stream to the frontend via `skill-server/src/main/java/com/opencode/cui/skill/ws/SkillStreamHandler.java`

## Backend Architectural Pattern

Both Java services follow a layered Spring Boot style:
- controller layer for HTTP APIs
- WebSocket handler/client layer for streaming transport
- service layer for business logic
- repository layer backed by MyBatis mappers
- model/config classes for typed payloads and properties

Evidence:
- controllers under `ai-gateway/src/main/java/com/opencode/cui/gateway/controller/` and `skill-server/src/main/java/com/opencode/cui/skill/controller/`
- services under each module's `service/`
- repositories under each module's `repository/`
- XML mappers under `src/main/resources/mapper/`

## Gateway Role

`ai-gateway/` acts as the central traffic hub.

Key responsibilities:
- authenticate agents with AK/SK
- track online agents and device bindings
- maintain relay state and ownership
- route messages between skill server and agents
- expose agent query APIs

Key files:
- `ai-gateway/src/main/java/com/opencode/cui/gateway/service/AgentRegistryService.java`
- `ai-gateway/src/main/java/com/opencode/cui/gateway/service/SkillRelayService.java`
- `ai-gateway/src/main/java/com/opencode/cui/gateway/service/EventRelayService.java`
- `ai-gateway/src/main/java/com/opencode/cui/gateway/service/RedisMessageBroker.java`

## Skill Server Role

`skill-server/` is the conversation-domain backend.

Key responsibilities:
- manage skill sessions and messages
- persist message parts and rebuild snapshots
- translate OpenCode events to stream-friendly payloads
- maintain browser WebSocket subscriptions
- optionally forward content to IM systems

Key files:
- `skill-server/src/main/java/com/opencode/cui/skill/service/SkillSessionService.java`
- `skill-server/src/main/java/com/opencode/cui/skill/service/SkillMessageService.java`
- `skill-server/src/main/java/com/opencode/cui/skill/service/MessagePersistenceService.java`
- `skill-server/src/main/java/com/opencode/cui/skill/service/OpenCodeEventTranslator.java`
- `skill-server/src/main/java/com/opencode/cui/skill/service/SnapshotService.java`

## Frontend Pattern

`skill-miniapp/` follows a hook-driven React architecture:
- API wrappers in `src/utils/api.ts`
- stateful domain hooks in `src/hooks/`
- stream parsing and normalization in `src/protocol/`
- presentational components in `src/components/`
- page assemblies in `src/pages/`

The root app composes hooks rather than introducing a global state library.

## Plugin Pattern

`plugins/message-bridge/` is organized by boundary and runtime responsibilities:
- `contracts/` for public message contracts
- `protocol/` for normalization and extraction
- `connection/` for WebSocket/auth transport
- `action/` for downstream action execution
- `runtime/` for orchestration lifecycle
- `error/` and `utils/` for shared support

This is a layered boundary architecture explicitly described in `plugins/message-bridge/README.md`.

## Scheduling, Caching, And State

- scheduling is enabled in `ai-gateway/src/main/java/com/opencode/cui/gateway/GatewayApplication.java`
- scheduling is enabled via `skill-server/src/main/java/com/opencode/cui/skill/config/SkillConfig.java`
- `skill-server` uses Caffeine-backed cache patterns and trackers such as `TranslatorSessionCache.java` and `ActiveMessageTracker.java`
- Redis supplements in-memory state for cross-process coordination

## Architectural Strengths

- clear subsystem boundaries
- strong transport-centric decomposition
- comprehensive protocol-focused test coverage
- explicit separation between persistence, relay, and UI concerns

## Architectural Fragility

- transport contracts are duplicated across modules
- no single monorepo build orchestrator coordinates all runtimes
- cross-service changes likely require careful synchronized updates
