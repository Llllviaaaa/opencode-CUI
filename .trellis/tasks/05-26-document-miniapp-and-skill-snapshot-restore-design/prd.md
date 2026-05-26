# document miniapp and skill snapshot restore design

## Goal

Generate a source-backed technical design document for the current MiniApp + Skill Server snapshot restore scheme, using the user's US design document template.

## What I Already Know

* Scope is documentation only: describe the current implementation, not redesign it.
* The relevant layers are `skill-miniapp` and `skill-server`.
* Current MiniApp behavior: durable history is loaded from `GET /api/skill/sessions/{sessionId}/messages/history`; WebSocket `streaming.parts` is treated as an active-turn overlay.
* Current Skill Server behavior: `SkillStreamHandler` handles `/ws/skill/stream` resume; `SnapshotService` builds `streaming` messages from `StreamBufferService`; `ProtocolMessageMapper` maps internal stream messages to protocol parts.
* Durable history uses MySQL `skill_message` and `skill_message_part`; live replay uses Redis stream buffer keys; latest history has a short Redis cache.

## Assumptions

* The output should be a Markdown technical design document in the repo `docs/` directory.
* "miniapp 和 skill" means `skill-miniapp` frontend and `skill-server` backend; `ai-gateway` is included only as an upstream event source/dependency when needed.
* No code behavior should be changed in this task.

## Open Questions

* None blocking. The document can be derived from current code, existing task notes, and specs.

## Requirements

* Follow the US requirements/design template structure provided by the user.
* Mark non-applicable sections as "不涉及" instead of deleting them.
* Explain the business flow, technical architecture, REST/WebSocket contracts, data model, cache keys, integration points, impact surface, and DFX considerations.
* Make the design source-backed with references to concrete files/classes/modules.

## Acceptance Criteria

* [x] A Markdown technical design document exists under `docs/`.
* [x] The document covers both MiniApp and Skill Server snapshot restore behavior.
* [x] The document explains history baseline vs active streaming overlay and the canonical `messageId`/`partId` merge model.
* [x] The document includes interface, data, cache, exception, compatibility, and impact sections.
* [x] The document is checked against current source files.

## Definition of Done

* Docs created/updated.
* No runtime code changes.
* `git status` reviewed for unrelated changes.
* `gitnexus_detect_changes(scope="all")` run before finishing to confirm scope.

## Out of Scope

* Changing snapshot restore behavior.
* Adding tests or running full builds.
* Creating diagrams outside Markdown/Mermaid.
* Updating external WIKI/cloud-doc links.

## Technical Notes

* Primary MiniApp files:
  * `skill-miniapp/src/hooks/useSkillStream.ts`
  * `skill-miniapp/src/protocol/StreamAssembler.ts`
  * `skill-miniapp/src/protocol/history.ts`
  * `skill-miniapp/src/protocol/types.ts`
  * `skill-miniapp/src/utils/api.ts`
* Primary Skill Server files:
  * `skill-server/src/main/java/com/opencode/cui/skill/ws/SkillStreamHandler.java`
  * `skill-server/src/main/java/com/opencode/cui/skill/service/SnapshotService.java`
  * `skill-server/src/main/java/com/opencode/cui/skill/service/StreamBufferService.java`
  * `skill-server/src/main/java/com/opencode/cui/skill/service/SkillMessageService.java`
  * `skill-server/src/main/java/com/opencode/cui/skill/service/MessagePersistenceService.java`
  * `skill-server/src/main/java/com/opencode/cui/skill/service/ActiveMessageTracker.java`
  * `skill-server/src/main/java/com/opencode/cui/skill/service/ProtocolMessageMapper.java`
