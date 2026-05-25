# State Management

The miniapp does not use Redux, Zustand, Jotai, React Query, or any other external state library. State is split across React state, mutable refs, and protocol objects, with page components composing the hooks.

## State Boundary

| Category | Lives in | Examples |
| --- | --- | --- |
| Render-driving state | React `useState` inside hooks or pages | `messages`, `isStreaming`, `sessions`, `selectedAgent`, `error`, `sidebarVisible` |
| Mutable runtime state that should not re-render | `useRef` inside hooks/pages | `wsRef`, reconnect timers, `assemblersRef`, `pendingInitialMessageRef`, send-to-IM clear timer |
| Protocol assembly state | `StreamAssembler` instances in refs | in-flight `MessagePart` ordering, permission resolution, streaming completion |
| Normalized transport data | `protocol/history.ts` and `utils/api.ts` | `Message`, `MessagePart`, `Session`, `AgentInfo` |

## The Most Important Boundary: React State vs Protocol State

`useSkillStream()` keeps the browser-facing state in React and the assembly engine in refs.

From `skill-miniapp/src/hooks/useSkillStream.ts`:

```ts
const [messages, setMessages] = useState<Message[]>([]);
const [isStreaming, setIsStreaming] = useState(false);
const [agentStatus, setAgentStatus] = useState<AgentStatus>('unknown');
const [socketReady, setSocketReady] = useState(false);
const [error, setError] = useState<string | null>(null);

const wsRef = useRef<WebSocket | null>(null);
const reconnectAttemptRef = useRef(0);
const reconnectTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
const heartbeatTimerRef = useRef<ReturnType<typeof setInterval> | null>(null);
const historyRequestRef = useRef(0);
const assemblersRef = useRef(new Map<string, StreamAssembler>());
const activeMessageIdsRef = useRef(new Set<string>());
const knownUserMessageIdsRef = useRef(new Set<string>());
```

That split is deliberate:
- `messages` is what components render.
- `StreamAssembler` owns the in-flight part graph for each assistant message.
- Socket objects and timers stay out of React state because they are runtime machinery, not UI.

## Bridge Pattern: StreamAssembler To Message[]

The handoff from protocol state to UI state happens inside `applyStreamedMessage()`.

From `skill-miniapp/src/hooks/useSkillStream.ts`:

```ts
let assembler = assemblersRef.current.get(messageId);
if (!assembler) {
  assembler = new StreamAssembler();
  assemblersRef.current.set(messageId, assembler);
}

assembler.handleMessage({ ...msg, messageId, role });
const hasActiveStreaming = assembler.hasActiveStreaming();
const content = assembler.getText();
const parts = assembler.getParts();

setMessages((prev) => {
  const existing = prev.find((message) => message.id === messageId);
  const nextMessage: Message = {
    id: messageId,
    role,
    content,
    contentType: existing?.contentType ?? contentTypeForRole(role),
    timestamp: existing?.timestamp ?? normalizeTimestamp(msg.emittedAt),
    messageSeq: msg.messageSeq ?? existing?.messageSeq,
    meta: existing?.meta,
    isStreaming: hasActiveStreaming,
    parts: parts.length > 0 ? [...parts] : existing?.parts,
  };
  return upsertMessage(prev, nextMessage);
});
```

State flow:
1. Socket event arrives in `useSkillStream()`.
2. `StreamAssembler.handleMessage()` updates transport-level part state.
3. `getText()` and `getParts()` snapshot that protocol state into a renderable `Message`.
4. `setMessages()` publishes the new UI state.
5. `MessageBubble.tsx` renders the parts.

## Page-Level Composition

There is still no global app store. The top-level pages compose the hook outputs and pass them down as props.

From `skill-miniapp/src/app.tsx`:

```tsx
const {
  sessions,
  currentSession,
  loading: sessionsLoading,
  error: sessionError,
  createSession,
  switchSession,
  updateSessionStatus,
  updateSessionTitle,
} = useSkillSession();

const activeSessionId = currentSession?.id ?? null;

const {
  messages,
  isStreaming,
  agentStatus,
  socketReady,
  sendMessage,
  replyPermission,
  error: streamError,
} = useSkillStream(activeSessionId, {
  onSessionTitleUpdate: updateSessionTitle,
});
```

From `skill-miniapp/src/pages/SkillMain.tsx`:

```tsx
const {
  sendToIm,
  sending: imSending,
  success: imSuccess,
  error: imError,
} = useSendToIm(activeSessionId);

const handleSendToIm = useCallback(
  (selectedText: string) => {
    void sendToIm(selectedText, imChatId);
  },
  [sendToIm, imChatId],
);
```

This is the intended split:
- Pages orchestrate hook composition and page-local UI state.
- Hooks own domain and transport state.
- Components remain render-only.

## Subagent State Uses Virtual Message IDs, Not A Parallel Store

Subagent output does not get its own store or context. Instead, `useSkillStream` synthesizes a virtual assistant message whose id is `subtask-${subagentSessionId}` and whose single `subtask`-type part carries the nested `subParts` array. This keeps every subagent event inside the same `messages` array.

From `skill-miniapp/src/hooks/useSkillStream.ts`:

```ts
const virtualMessageId = `subtask-${subagentSessionId}`;

// 确保虚拟 message 存在（含单个 subtask part）
setMessages((prev) => {
  const exists = prev.some((m) => m.id === virtualMessageId);
  if (!exists) {
    return [
      ...prev,
      {
        id: virtualMessageId,
        role: 'assistant' as const,
        content: '',
        contentType: 'plain' as const,
        timestamp: Date.now(),
        isStreaming: true,
        parts: [
          {
            partId: virtualMessageId,
            type: 'subtask' as const,
            content: '',
            isStreaming: true,
            subagentSessionId,
            subagentName: subagentName ?? 'Subagent',
            subagentPrompt: msg.content ?? '',
            subagentStatus: 'running' as const,
            subParts: [],
          },
        ],
      },
    ];
  }
  return prev;
});
```

History normalization uses the same virtual-id convention so reloading a session produces the same shape as a live stream.

From `skill-miniapp/src/protocol/history.ts` (`mergeSubagentPartsAcrossMessages`):

```ts
result.push({
  id: `subtask-${sid}`,
  role: 'assistant',
  content: '',
  contentType: 'plain',
  timestamp: msg.timestamp,
  isStreaming: false,
  parts: [{
    partId: `subtask-${sid}`,
    type: 'subtask',
    content: '',
    isStreaming: false,
    subagentSessionId: sid,
    subagentName: entry.name,
    subagentPrompt: '',
    subagentStatus: 'completed',
    subParts: entry.parts,
  }],
});
```

Implications:
- There is no separate "subagent store". React state is still a flat `Message[]`; subagent identity rides in `message.id`.
- Do not give subagents their own `useSkillStream` instance — the main hook already dispatches subagent events (`handleSubagentMessage`) and owns the virtual message ids.
- When extending history normalization, preserve the `subtask-${sid}` id convention so live-stream merges continue to hit the same row.

## History And Streaming Normalization Stay Out Of Components

History payload normalization belongs in `protocol/history.ts`, not in `ConversationView.tsx`.

From `skill-miniapp/src/protocol/history.ts`:

```ts
export function normalizeHistoryMessage(raw: BackendMessage): Message {
  const parts = Array.isArray(raw.parts)
    ? raw.parts
        .map((part, index) => normalizePart(part, index))
        .filter((part): part is MessagePart => part !== null)
    : [];

  const derivedContent = parts
    .filter((part) => part.type === 'text')
    .map((part) => part.content)
    .join('');

  return {
    id: String(raw.messageId ?? raw.id ?? `history_${Math.random().toString(36).slice(2)}`),
    role: normalizeRole(raw.role),
    content: raw.content ?? derivedContent,
    contentType: normalizeContentType(raw.contentType),
    timestamp: normalizeTimestamp(raw.createdAt),
    isStreaming: false,
    parts: parts.length > 0 ? groupPartsIntoSubtasks(parts) : undefined,
  };
}
```

That is where message shaping belongs.

## No External Store Means No Hidden Cache

- Session lists are cached in `useSkillSession()` state.
- Agent availability is cached in `useAgentSelector()` state.
- Message history and streaming output live in `useSkillStream()` state.
- Send-to-IM success/error state lives only in `useSendToIm()`.

If a feature only needs to cross one or two component levels, keep prop-drilling. The current tree is still small enough that context or a global store would add complexity without solving a real problem.

## Anti-Regression Note

The release-0401 line removed the old "merge consecutive assistant messages" behavior (`4e3962d` / `47d6652`). Do not reintroduce UI-side message coalescing in `ConversationView.tsx` or `MessageBubble.tsx`.

If message grouping needs to change:
- adjust `protocol/history.ts` for historical payloads,
- adjust `StreamAssembler.ts` for live streams,
- keep message identity stable in React state.

## Snapshot Restore Must Reconcile Duplicate Parts

Streaming snapshot restore is an overlay on top of history, not a second history source. When a
snapshot carries the canonical assistant message and history also contains split assistant shells
from earlier tool events, `useSkillStream()` must preserve the canonical snapshot message and
remove duplicate parts from other assistant messages.

### 1. Scope / Trigger

- Trigger: `restoreStreamingMessage` receives a `streaming` snapshot while history is already
  loaded or is being merged into React state.
- Scope: assistant text, thinking, tool, and tool-error parts.
- Goal: avoid rendering the same assistant turn as "snapshot bubble + history fragments".

### 2. Signatures

- Restore entry: `restoreStreamingMessage` in `useSkillStream()`.
- Duplicate pruning helper: `pruneSnapshotDuplicateParts(...)`.
- Text derivation helper: `textContentFromParts(...)`.

### 3. Contracts

- Snapshot message id wins for the active assistant turn.
- Duplicate detection uses stable part identity: `partId` first; for tool parts, `toolCallId`
  is an additional key because failed tool calls may be replayed with the same call id.
- After duplicate parts are removed, empty assistant shells must be dropped.
- Do not reintroduce broad "merge consecutive assistant messages" behavior in components.
  The fix is scoped to snapshot reconciliation in `useSkillStream()`.

### 4. Validation & Error Matrix

| Case | Required behavior |
| --- | --- |
| snapshot has canonical text/tool/text and history has split tool shell | Render only the canonical snapshot message for those parts. |
| history has a distinct assistant message with unique parts | Keep it unchanged. |
| duplicate pruning empties an assistant shell | Remove the shell from React state. |
| snapshot has no parts | Keep existing messages and do not derive content from empty parts. |

### 5. Good / Base / Bad Cases

Good:

```ts
const reconciled = pruneSnapshotDuplicateParts(prev, snapshotMessage);
return upsertMessage(reconciled, snapshotMessage);
```

Base:

```ts
const content = textContentFromParts(snapshotMessage.parts);
```

Bad:

```ts
return [...prev, snapshotMessage];
```

### 6. Tests Required

- Add a hook or reducer-level regression for snapshot restore with tool parts whenever this
  path is refactored.
- Keep `npm run typecheck` and `npm run build` green after message shape changes.

### 7. Wrong vs Correct

Wrong: appending snapshot content beside already loaded history fragments and relying on visual
ordering to hide the duplicate.

Correct: reconcile by part identity first, then upsert the canonical snapshot message.
