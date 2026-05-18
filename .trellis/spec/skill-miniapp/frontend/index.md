# skill-miniapp Frontend Spec

## File Set Decision
- Kept the existing seven files. No extra guide was added for this refresh.
- `SubtaskBlock.tsx` is now covered in the live directory map, component guidance, and state guide (virtual message id convention).
- Markdown rendering, code highlighting, and send-to-IM behavior are documented inside the existing component, hook, and state guides instead of being split into new files.
- Business-code 410 ("助理已被删除") error handling is documented in both `quality-guidelines.md` and `type-safety.md` because it spans the type contract, the hook error layer, and the review checklist.

## Subsystem At A Glance
- Path: `skill-miniapp/`
- Stack: React 18 + Vite 5 + TypeScript 5
- Role: IM-embedded OpenCode chat client using REST for CRUD and WebSocket for streaming output

## Architecture Snapshot
- Entry flow: `skill-miniapp/src/main.tsx` mounts `skill-miniapp/src/app.tsx`; `skill-miniapp/src/pages/SkillMain.tsx` is the overlay-style embedded page; `skill-miniapp/src/pages/SkillMiniBar.tsx` is the compact surface.
- Render model: `skill-miniapp/src/components/MessageBubble.tsx` switches on `MessagePart.type` and delegates to focused renderers such as `ThinkingBlock`, `ToolCard`, `QuestionCard`, `PermissionCard`, and `SubtaskBlock`.
- State model: hooks own React state, `skill-miniapp/src/protocol/StreamAssembler.ts` owns multi-part protocol assembly, and `skill-miniapp/src/utils/api.ts` normalizes REST responses and throws `ApiError`.
- Quality gates: `cd skill-miniapp && npm run typecheck` and `cd skill-miniapp && npm run build`.

## Available Guides

| File | Focus |
| --- | --- |
| [directory-structure.md](./directory-structure.md) | Live `skill-miniapp/src/` file map and layer boundaries |
| [component-guidelines.md](./component-guidelines.md) | Part-renderer delegation, collapsible blocks, and card-state UI patterns |
| [hook-guidelines.md](./hook-guidelines.md) | Typed-return-object hooks for streaming, sessions, agents, and send-to-IM |
| [state-management.md](./state-management.md) | React state vs refs vs protocol objects, plus page-level orchestration |
| [type-safety.md](./type-safety.md) | Real domain types from `protocol/types.ts` and error contracts from `api.ts` |
| [quality-guidelines.md](./quality-guidelines.md) | Build gates, extension checklist, and anti-regression rules |

## Current Facts To Preserve
- The app is layer-based, not feature-folder based: `components/`, `hooks/`, `protocol/`, `pages/`, `utils/`, and `constants/`.
- `useSkillStream()` is the bridge between the socket and React. It stores transport machinery in refs and only commits assembled `Message[]` into state.
- `useSkillSession()` and `useAgentSelector()` both follow the same "typed object return + local loading/error state" pattern.
- The protocol layer is intentionally React-free. `StreamAssembler`, `history.ts`, `OpenCodeEventParser.ts`, and `ToolUseRenderer.ts` should stay usable outside React components.
- There is no separate `SessionSummary` type in the current miniapp. Session lists reuse `Session` from `protocol/types.ts`.
- Subagent output is stored as a virtual `subtask-${subagentSessionId}` message inside the same `messages` array — there is no parallel subagent store.
- HTTP 200 + `body.code === 410` is the canonical "assistant deleted" signal. Write-path hooks (`createSession`, `sendMessage`, `replyPermission`) branch on it and keep the optimistic UI (PRD `Q-arch-4`).

## Evidence Base
- Refreshed against the live files under `skill-miniapp/src/`.
- Verified with ABCoder repo `skill-miniapp`.
- Cross-checked with GitNexus process traces `SkillMain -> ApiError` and `SkillMain -> GetParts`.
