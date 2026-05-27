# fix snapshot resume ordering with tool messages

## Goal

Fix the snapshot resume flow so sessions that include tool-call/tool-update parts restore as one coherent assistant message instead of splitting restored snapshot parts away from the generated historical message.

## What I already know

* The user observes the problem only when tool-type messages are present.
* Sessions with only thinking and text restore normally.
* The visible failure is that restored snapshot content and generated/history content appear as separate assistant message blocks.
* The likely boundary is the interaction between message history loading and WebSocket snapshot resume.
* Current staged changes touch both `skill-miniapp/src/hooks/useSkillStream.ts` and server-side snapshot/persistence services.
* Existing memory from prior work says `tool_done` is the durable finalize signal; `session.status=idle` alone is not enough.

## Assumptions (temporary)

* The same assistant turn can be represented once by MySQL history and once by Redis snapshot/replay if identity fields differ.
* Tool messages expose the issue because tool parts are persisted earlier than text/thinking buffer finalization, so history can contain a partial or completed assistant message before snapshot resume is processed.
* The fix should preserve a single message identity across history, Redis buffered parts, snapshot payloads, and miniapp StreamAssembler state.

## Open Questions

* None blocking yet. First derive the exact root cause from code and tests.

## Requirements (evolving)

* Snapshot resume must merge with the corresponding history assistant message by canonical `messageId`.
* `messageId` is the assistant-turn identity; `partId` is the per-part identity used for idempotent updates and de-duplication.
* Tool-call parts must remain ordered with text/thinking parts in the same assistant message; a tool call/update is not a message boundary.
* Server-side persistence and Redis buffering must agree on the same canonical assistant `messageId` for all parts in the active assistant turn.
* Miniapp should not process non-agent stream/snapshot messages for a session before that session's history has been loaded or failed.
* Server-side snapshot identity must prefer a real upstream message id over generated fallback ids.
* Miniapp snapshot restoration should treat snapshot state as an overlay for the active turn, not as a historical message to append.

## Acceptance Criteria (evolving)

* [x] A tool-call session can load history and then resume snapshot without duplicate/split assistant blocks.
* [x] When DB history has split later tool/text parts into separate assistant rows, snapshot resume either receives corrected server history or the miniapp reconciles those parts back under the canonical snapshot message without duplicate visible assistant blocks.
* [x] Text/thinking-only sessions continue to restore normally.
* [x] Unit tests cover multi-tool assistant turn persistence/snapshot identity and miniapp snapshot/history merge behavior.
* [x] `skill-server` compile/tests and `skill-miniapp` build or typecheck pass for the touched scope.

## Definition of Done

* Tests added/updated where appropriate.
* Compile/typecheck/build run for touched modules.
* GitNexus impact analysis is run before editing modified symbols.
* GitNexus change detection confirms expected affected scope before finishing.

## Out of Scope

* Broad redesign of the stream protocol.
* Redis delivery ordering changes unrelated to this history/snapshot merge problem.
* UI restyling.

## Technical Notes

* Task created from user report on branch `codex/streaming-persistence-resume`.
* Suspect client file: `skill-miniapp/src/hooks/useSkillStream.ts`.
* Suspect server files: `SnapshotService`, `StreamBufferService`, `ActiveMessageTracker`, `MessagePersistenceService`, `SkillMessageService`, and mapper/test files.
* Current staged diff removed client pending-stream gating and server generated-id adoption/context merge helpers; these are high-probability causes to verify.
* User-provided repro data shows Redis snapshot grouping four parts under `msg_e5a985f0b001aIUhtWDg1yaEpa`, while DB history splits those same parts across `msg_e5a985f0b001aIUhtWDg1yaEpa`, `msg_e5a988e9d001cYIW53d6wRNCz5`, and `msg_e5a98a6cc001G6W92kkJE4FZwW`.
* Design decision: history is the durable baseline, snapshot is an active-turn overlay. Restore must reconcile by canonical `messageId` and de-duplicate by `partId`; repeated resume must be idempotent.
