# Codebase Stack

## Overview

This repository is a multi-module system centered on OpenCode-driven chat and tool orchestration.
The top-level package at `package.json` links a local plugin package and acts mainly as a workspace shell.

Primary subsystems:
- `ai-gateway/` - Spring Boot gateway for agent registration, AK/SK auth, routing, and relay
- `skill-server/` - Spring Boot backend for session management, persistence, and WebSocket streaming
- `skill-miniapp/` - React + Vite frontend for chat, session browsing, and streaming UI
- `plugins/message-bridge/` - TypeScript plugin that connects a local OpenCode instance to `ai-gateway`
- `tests/` - Python integration, security, HA, and end-to-end tests

## Languages And Runtimes

- Java 21 in `ai-gateway/pom.xml` and `skill-server/pom.xml`
- TypeScript in `skill-miniapp/src/` and `plugins/message-bridge/src/`
- React 18 in `skill-miniapp/package.json`
- Node.js tooling via `npm` and `vite` in `skill-miniapp/package.json`
- Bun as the declared package manager for the plugin in `plugins/message-bridge/package.json`
- Python test tooling in `tests/requirements.txt`

## Backend Frameworks

`ai-gateway/` and `skill-server/` both use:
- `spring-boot-starter-web`
- `spring-boot-starter-websocket`
- `mybatis-spring-boot-starter`
- `spring-boot-starter-data-redis`
- MySQL runtime driver `mysql-connector-j`
- Lombok for boilerplate reduction
- `spring-boot-starter-test` for unit and slice testing

Additional backend dependencies:
- `org.java-websocket:Java-WebSocket` in `skill-server/pom.xml` for the outbound gateway client
- `com.github.ben-manes.caffeine:caffeine` in `skill-server/pom.xml` for bounded in-memory caching

## Frontend And Plugin Stack

`skill-miniapp/` uses:
- `react`
- `react-dom`
- `react-markdown`
- `remark-gfm`
- `shiki`
- `vite`
- `typescript`

`plugins/message-bridge/` uses:
- `@opencode-ai/plugin`
- `@opencode-ai/sdk`
- `jsonc-parser`
- Node-compatible TypeScript build scripts in `plugins/message-bridge/scripts/`

## Entry Points

- `ai-gateway/src/main/java/com/opencode/cui/gateway/GatewayApplication.java`
- `skill-server/src/main/java/com/opencode/cui/skill/SkillServerApplication.java`
- `skill-miniapp/src/main.tsx`
- `plugins/message-bridge/src/index.ts`

## Runtime Configuration Sources

- `ai-gateway/src/main/resources/application.yml`
- `skill-server/src/main/resources/application.yml`
- `plugins/message-bridge/src/config/default-config.ts`
- `.opencode/message-bridge.jsonc` and related locations documented in `plugins/message-bridge/README.md`

## Data And Messaging Infrastructure

- MySQL for persistent entities and session/message storage
- Redis for routing, pub/sub, heartbeat state, and transient coordination
- WebSocket between agent, gateway, skill server, and frontend stream clients
- HTTP REST for session CRUD, message retrieval, agent listing, and IM relay

## Build And Verification Tooling

- Maven for Java services
- Vite + TypeScript compiler for the miniapp
- Bun-based test execution for `plugins/message-bridge/`
- Pytest + pytest-asyncio for top-level system tests

## Current Stack Notes

- The repository mixes `npm`, `bun`, `mvn`, and `pytest`; local setup is multi-runtime rather than monolithic.
- Both backend services share similar Spring/MyBatis/Redis/MySQL patterns, which reduces conceptual drift.
- Some UI strings in `skill-miniapp/src/app.tsx` appear garbled, suggesting an encoding issue worth tracking separately.
