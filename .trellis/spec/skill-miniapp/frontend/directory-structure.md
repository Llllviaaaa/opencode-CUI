# Directory Structure

This guide documents the live source layout under `skill-miniapp/src/`. The generated `dist/` bundle exists in the repo, but frontend implementation and spec work should reason from `src/`.

## Live Source Tree

```text
skill-miniapp/src/
|-- main.tsx
|-- app.tsx
|-- index.css
|-- components/
|   |-- AgentSelector.tsx
|   |-- CodeBlock.tsx
|   |-- ConversationView.tsx
|   |-- MessageBubble.tsx
|   |-- MessageInput.tsx
|   |-- PermissionCard.tsx
|   |-- QuestionCard.tsx
|   |-- SendToImButton.tsx
|   |-- SessionSidebar.tsx
|   |-- SubtaskBlock.tsx
|   |-- ThinkingBlock.tsx
|   `-- ToolCard.tsx
|-- hooks/
|   |-- useAgentSelector.ts
|   |-- useSendToIm.ts
|   |-- useSkillSession.ts
|   `-- useSkillStream.ts
|-- protocol/
|   |-- OpenCodeEventParser.ts
|   |-- StreamAssembler.ts
|   |-- ToolUseRenderer.ts
|   |-- history.ts
|   `-- types.ts
|-- pages/
|   |-- SkillMain.tsx
|   `-- SkillMiniBar.tsx
|-- constants/
|   `-- session.ts
`-- utils/
    |-- api.ts
    `-- devAuth.ts
```

## Layer Intent

| Layer | Live files | Role |
| --- | --- | --- |
| Entry and shell | `main.tsx`, `app.tsx`, `index.css` | Mount the app and compose the desktop-style shell |
| Pages | `pages/SkillMain.tsx`, `pages/SkillMiniBar.tsx` | Embedded overlay and compact page-level entry surfaces |
| Components | `components/*.tsx` | Focused renderers and controls; the folder stays flat |
| Hooks | `hooks/*.ts` | Stateful logic, effects, polling, optimistic updates, and socket lifecycle |
| Protocol | `protocol/*.ts` | Framework-agnostic event parsing, message assembly, history normalization, and shared types |
| Constants | `constants/session.ts` | Miniapp-specific session domain/type constants |
| Utils | `utils/api.ts`, `utils/devAuth.ts` | REST client, normalization helpers, and dev cookie bootstrap |

## What Each Area Owns

### Entry and Shell
- `skill-miniapp/src/main.tsx` is the real browser entry.
- `skill-miniapp/src/app.tsx` is the standard shell that imports `index.css`, wires `useSkillSession`, `useSkillStream`, and `useAgentSelector`, and feeds the presentational components.
- `skill-miniapp/src/pages/SkillMain.tsx` is not dead code. It is the embedded overlay variant and adds `useSendToIm()` plus IM-oriented props such as `initialSessionId` and `imChatId`.

### Components
- `MessageBubble.tsx` is the central delegator for assistant output.
- `ThinkingBlock.tsx`, `ToolCard.tsx`, `QuestionCard.tsx`, `PermissionCard.tsx`, and `SubtaskBlock.tsx` are specialized part renderers.
- `CodeBlock.tsx` is the fenced-code renderer used by `MessageBubble.tsx`.
- `SendToImButton.tsx` is a floating selection action that belongs with components, not hooks, because it owns DOM selection UI.

### Hooks
- `useSkillStream.ts` is the transport-heavy hook. It loads history, opens the socket, tracks reconnection, and bridges `StreamAssembler` back into `Message[]`.
- `useSkillSession.ts` owns session CRUD and local list caching.
- `useAgentSelector.ts` polls `/api/skill/agents` every 30 seconds and preserves the selected agent when possible.
- `useSendToIm.ts` is the small action hook for the send-to-IM REST call and transient success state.

### Protocol
- `types.ts` is the shared domain type file used by hooks, components, and utilities.
- `StreamAssembler.ts` is the authoritative multi-part assembler for streaming events.
- `history.ts` converts backend history payloads into frontend `Message` and `MessagePart` objects and groups subagent parts into `subtask` blocks.
- `OpenCodeEventParser.ts` and `ToolUseRenderer.ts` are protocol helpers that intentionally avoid React imports.

## Layout Rules That Match The Current Codebase

- Keep the folder structure flat. New assistant part renderers should land directly in `components/`, not in nested feature directories.
- Put new long-lived state into hooks, not pages or leaf components.
- Put message-shape normalization in `protocol/` or `utils/api.ts`, not in `ConversationView.tsx` or `MessageBubble.tsx`.
- The style system is split today:
  - `app.tsx` relies on `index.css` for the main shell.
  - `SkillMain.tsx`, `CodeBlock.tsx`, and `SendToImButton.tsx` still use local `React.CSSProperties` maps for embedded surfaces and utility UI.
  Keep that split in mind when extending those files.

## Concrete Files To Follow

- Part delegation example: `skill-miniapp/src/components/MessageBubble.tsx`
- Overlay page composition: `skill-miniapp/src/pages/SkillMain.tsx`
- Typed CRUD hook: `skill-miniapp/src/hooks/useSkillSession.ts`
- Stream assembly boundary: `skill-miniapp/src/hooks/useSkillStream.ts` and `skill-miniapp/src/protocol/StreamAssembler.ts`
- Backend normalization boundary: `skill-miniapp/src/utils/api.ts` and `skill-miniapp/src/protocol/history.ts`
