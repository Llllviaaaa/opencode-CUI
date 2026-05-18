# Hook Guidelines

The miniapp keeps stateful logic inside hooks and passes plain data plus callbacks into components. Every production hook in this subsystem returns a typed object, never a tuple.

## Typed Return Object Convention

The return contract is explicit and exported for the shared hooks.

From `skill-miniapp/src/hooks/useSkillSession.ts`:

```ts
export interface UseSkillSessionReturn {
  sessions: Session[];
  currentSession: Session | null;
  loading: boolean;
  error: string | null;
  createSession: (params: api.CreateSessionParams) => Promise<Session | null>;
  loadSessions: () => Promise<void>;
  switchSession: (sessionId: string) => void;
  closeSession: (sessionId: string) => Promise<void>;
  updateSessionStatus: (sessionId: string, status: Session['status']) => void;
  updateSessionTitle: (sessionId: string, title: string) => void;
}
```

From `skill-miniapp/src/hooks/useSkillStream.ts`:

```ts
export interface UseSkillStreamReturn {
  messages: Message[];
  isStreaming: boolean;
  agentStatus: AgentStatus;
  socketReady: boolean;
  sendMessage: (text: string, options?: { toolCallId?: string }) => Promise<void>;
  replyPermission: (
    permissionId: string,
    response: 'once' | 'always' | 'reject',
    subagentSessionId?: string,
  ) => Promise<void>;
  error: string | null;
}
```

`useAgentSelector()` keeps the same idea even though its return interface stays file-local.

## Pattern: Session CRUD With Local Cache

`useSkillSession()` owns the session list, the current selection, and local mutations after REST calls.

From `skill-miniapp/src/hooks/useSkillSession.ts`:

```ts
export function useSkillSession(): UseSkillSessionReturn {
  const [sessions, setSessions] = useState<Session[]>([]);
  const [currentSession, setCurrentSession] = useState<Session | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const creatingRef = useRef(false);

  const loadSessions = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const res = await api.getSessions(0, 100);
      setSessions(res.content);
    } catch (err) {
      const message =
        err instanceof Error ? err.message : 'Failed to load sessions';
      setError(message);
    } finally {
      setLoading(false);
    }
  }, []);
```

Key rules:
- Keep the canonical session list in hook state; pages consume it.
- Apply cheap local cache mutations after successful REST calls (`setSessions((prev) => ...)`) instead of reloading the whole list after every action.
- Guard duplicate creates with a ref (`creatingRef`) when an action must stay single-flight.

GitNexus process `SkillMain -> ApiError` confirms the error path is `SkillMain -> useSkillSession -> getSessions -> request -> ApiError`, so hooks should translate transport failures into UI-facing `error` state instead of swallowing them.

## Pattern: WebSocket Lifecycle Plus Protocol Assembly

`useSkillStream()` is the most important hook in the subsystem. It keeps browser state in React and transport state in refs.

From `skill-miniapp/src/hooks/useSkillStream.ts`:

```ts
export function useSkillStream(
  sessionId: string | null,
  options?: UseSkillStreamOptions,
): UseSkillStreamReturn {
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

The bridge from socket events into UI messages goes through `StreamAssembler`.

From `skill-miniapp/src/hooks/useSkillStream.ts`:

```ts
const applyStreamedMessage = useCallback((msg: StreamMessage) => {
  const messageId = msg.messageId ?? msg.sourceMessageId ?? genId('stream');
  const role = normalizeRole(msg.role);

  if (knownUserMessageIdsRef.current.has(messageId)) {
    return;
  }

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
}, []);
```

Reconnect and cleanup stay in the same hook because they share the socket refs.

From `skill-miniapp/src/hooks/useSkillStream.ts`:

```ts
const scheduleReconnect = useCallback(() => {
  if (reconnectTimerRef.current) {
    return;
  }

  const delay = getReconnectDelay(reconnectAttemptRef.current);
  reconnectAttemptRef.current += 1;
  reconnectTimerRef.current = setTimeout(() => {
    reconnectTimerRef.current = null;
    connect();
  }, delay);
}, [connect]);

useEffect(() => {
  connect();

  return () => {
    setSocketReady(false);
    clearHeartbeatTimer();
    if (reconnectTimerRef.current) {
      clearTimeout(reconnectTimerRef.current);
      reconnectTimerRef.current = null;
    }
    reconnectAttemptRef.current = 0;
    if (wsRef.current) {
      wsRef.current.onclose = null;
      wsRef.current.close();
      wsRef.current = null;
    }
  };
}, [clearHeartbeatTimer, connect]);
```

Keep JSX out of this hook. If a new stream feature needs rendering, expose it as `MessagePart` data and let components render it.

## Pattern: Optimistic Send And Permission Reply

Outgoing actions also stay inside the hook so components never talk to `api.ts` directly. On business code 410 (assistant deleted) the optimistic state is kept, not rolled back — this is a deliberate product decision recorded in PRD `Q-arch-4`.

From `skill-miniapp/src/hooks/useSkillStream.ts`:

```ts
const sendMessageFn = useCallback(
  async (text: string, options?: { toolCallId?: string }) => {
    if (!sessionId) {
      return;
    }

    setError(null);

    const tempId = genId('user');
    const optimisticMessage: Message = {
      id: tempId,
      role: 'user',
      content: text,
      contentType: 'plain',
      timestamp: Date.now(),
    };
    setMessages((prev) => upsertMessage(prev, optimisticMessage));

    try {
      const saved = await api.sendMessage(sessionId, text, options?.toolCallId);
      const normalized = normalizeHistoryMessage(saved as unknown as Record<string, unknown>);
      setMessages((prev) => {
        const nextMessages = prev.filter((message) => message.id !== tempId && message.id !== normalized.id);
        return upsertMessage(nextMessages, normalized);
      });
    } catch (err) {
      if (extractBusinessCode(err) === 410) {
        setError('该助理已被删除');
      } else {
        const message = err instanceof Error ? err.message : 'Failed to send message';
        setError(message);
      }
      // 保留 optimistic 用户消息，不 rollback（PRD 决策 Q-arch-4）
    }
  },
  [sessionId],
);
```

And permission replies update both the protocol cache and React state before the REST call finishes, including SubtaskBlock-owned permission parts nested inside `subParts`.

From `skill-miniapp/src/hooks/useSkillStream.ts`:

```ts
// 立即更新 messages state 中 permission part 的 permResolved
// 防止后续 re-render 时 PermissionCard 的 useEffect 重置 resolved 状态
setMessages((prev) =>
  prev.map((message) => ({
    ...message,
    parts: (message.parts ?? []).map((p) => {
      // 直接在 message parts 中匹配
      if (p.type === 'permission' && p.permissionId === permissionId) {
        return { ...p, permResolved: true, permissionResponse: response };
      }
      // SubtaskBlock 内的 subParts 中匹配
      if (p.type === 'subtask' && p.subParts?.length) {
        return {
          ...p,
          subParts: p.subParts.map((sp) =>
            sp.type === 'permission' && sp.permissionId === permissionId
              ? { ...sp, permResolved: true, permissionResponse: response }
              : sp,
          ),
        };
      }
      return p;
    }),
  })),
);
```

## Pattern: Subagent Dispatch Inside The Same Hook

`useSkillStream()` dispatches subagent-scoped events into a virtual `subtask-${subagentSessionId}` message so subagent output stays grouped even when interleaved with main-session events.

From `skill-miniapp/src/hooks/useSkillStream.ts`:

```ts
const handleStreamMessage = useCallback((msg: StreamMessage) => {
  const currentSessionId = sessionIdRef.current;
  if (msg.welinkSessionId && (!currentSessionId || String(msg.welinkSessionId) !== String(currentSessionId))) {
    return;
  }

  // Subagent 消息分发
  if (msg.subagentSessionId) {
    handleSubagentMessage(msg);
    return;
  }
  switch (msg.type) {
```

`handleSubagentMessage` owns its own per-subagent upsert logic: text/thinking deltas merge by `partId`, `tool.update` upserts by `toolCallId`, and `permission.reply` flips the `permResolved` flag in the nested `subParts` array.

From `skill-miniapp/src/hooks/useSkillStream.ts`:

```ts
// text/thinking delta: 按 partId 合并到同一个 part
if ((msg.type === 'text.delta' || msg.type === 'thinking.delta') && msg.partId) {
  const idx = existing.findIndex((sp) => sp.partId === subPart.partId);
  if (idx >= 0) {
    const updated = [...existing];
    updated[idx] = { ...updated[idx], content: updated[idx].content + (msg.content ?? '') };
    return { ...p, subParts: updated };
  }
}
```

Rules:
- Subagent events have their own assembly code path; they do **not** reuse `StreamAssembler`.
- The main assembly map `assemblersRef` only holds main-session messages.
- If you add a new subagent part type, extend both `streamMessageToSubPart` and the upsert branches in `handleSubagentMessage`.

## Pattern: Polling Hook

`useAgentSelector()` is the reference for periodic fetch with local selection reconciliation.

From `skill-miniapp/src/hooks/useAgentSelector.ts`:

```ts
const POLL_INTERVAL_MS = 30_000;

export function useAgentSelector(): UseAgentSelectorReturn {
  const [agents, setAgents] = useState<AgentInfo[]>([]);
  const [selectedAgent, setSelectedAgent] = useState<AgentInfo | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const timerRef = useRef<ReturnType<typeof setInterval> | null>(null);

  const fetchAgents = useCallback(async () => {
    try {
      const result = await getOnlineAgents();
      setAgents(result);
      setError(null);

      if (result.length === 1 && !selectedAgent) {
        setSelectedAgent(result[0]);
      }

      if (selectedAgent && !result.find((a) => a.id === selectedAgent.id)) {
        setSelectedAgent(result.length > 0 ? result[0] : null);
      }
    } catch (e) {
      setError('Failed to load agents');
      console.error('Agent query error:', e);
    } finally {
      setLoading(false);
    }
  }, [selectedAgent]);
```

Use the same pattern when a list needs periodic refresh but also owns a local user choice.

## Pattern: One-Shot Action Hook

`useSendToIm()` is the small action-only hook. It is the right model when the UI only needs a transient async status.

From `skill-miniapp/src/hooks/useSendToIm.ts`:

```ts
export function useSendToIm(sessionId: string | null): UseSendToImReturn {
  const [sending, setSending] = useState(false);
  const [success, setSuccess] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const clearTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const sendToIm = useCallback(
    async (content: string, chatId: string) => {
      if (!sessionId) return;

      if (clearTimerRef.current) {
        clearTimeout(clearTimerRef.current);
        clearTimerRef.current = null;
      }

      setSending(true);
      setSuccess(false);
      setError(null);
```

## Practical Rules

- Return a typed object with stable field names. Do not hide data inside positional tuple slots.
- Keep network calls in hooks or `utils/api.ts`, not in components.
- Store non-render transport machinery in refs.
- If a hook starts to mix transport, normalization, and rendering concerns, extract a pure helper into `protocol/` or `utils/` before the hook grows further.
