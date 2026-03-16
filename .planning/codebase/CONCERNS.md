# Concerns And Risks

## High-Priority Concerns

### Checked-In Default Secrets

Sensitive-looking defaults appear in config files:
- database password defaults in `ai-gateway/src/main/resources/application.yml`
- database password defaults in `skill-server/src/main/resources/application.yml`
- internal skill-to-gateway token defaults in both backend config files

Even if these are only local-development values, storing realistic secrets in versioned config increases accidental misuse risk.

### Encoding / Mojibake

There are visible garbled strings in scanned files:
- UI text in `skill-miniapp/src/app.tsx`
- comments and docstrings in `tests/test_e2e.py`
- some dependency comments in `skill-server/pom.xml`

This can lead to broken UX, confusing maintenance, and inconsistent file encoding practices.

### Contract Coupling Across Modules

Protocol and transport concepts are implemented in:
- `plugins/message-bridge/src/contracts/`
- `plugins/message-bridge/src/protocol/`
- `skill-server/src/main/java/com/opencode/cui/skill/service/OpenCodeEventTranslator.java`
- `ai-gateway/src/main/java/com/opencode/cui/gateway/model/GatewayMessage.java`
- frontend stream parsing in `skill-miniapp/src/hooks/useSkillStream.ts`

This creates a high coordination burden when message shapes evolve.

## Medium-Priority Concerns

### Operational Complexity

The project spans:
- Java/Maven
- TypeScript/npm
- TypeScript/Bun
- Python/pytest
- MySQL
- Redis
- WebSocket-based live dependencies

This improves modularity, but onboarding and CI/CD become more complex.

### Frontend State Complexity

`skill-miniapp/src/hooks/useSkillStream.ts` is a large, stateful hook with reconnect, resume, normalization, buffering, and merge behavior.
It is a sensible place for complexity, but it is also a likely hotspot for regressions.

### Duplicate Or Overlapping Frontend Entrypoints

Both `skill-miniapp/src/app.tsx` and `skill-miniapp/src/pages/SkillMain.tsx` appear to assemble the chat experience.
This may be intentional, but it is worth confirming to avoid parallel UI paths drifting apart.

### Incomplete Centralized Tooling

No obvious monorepo task runner or unified root CI config was surfaced in the scanned files.
Developers may need manual knowledge to build and test all modules reliably.

## Lower-Priority Concerns

### Documentation Distribution

The plugin module has rich docs under `plugins/message-bridge/docs/`, but the Java services appear lighter on embedded architecture docs.
Knowledge may be unevenly distributed across subprojects.

### Cross-Service Schema Drift

Separate migration sets in `ai-gateway/` and `skill-server/` are appropriate, but they also require deployment discipline when contracts or identifiers evolve.

## Fragile Areas To Watch

- WebSocket handshake and reconnect behavior
- Redis-backed ownership and route TTL handling
- event ordering and message part sequencing
- session resume / snapshot rebuild flows
- auth tolerance windows and nonce replay prevention

## Suggested Next Investigation Areas

- replace checked-in secret defaults with safer placeholders or environment-only values
- standardize UTF-8 handling and repair garbled files
- document end-to-end protocol ownership across gateway, skill server, plugin, and miniapp
- decide whether frontend entry duplication is intentional and reduce overlap if not
