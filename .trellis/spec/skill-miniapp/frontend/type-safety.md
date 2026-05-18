# Type Safety

The miniapp relies on TypeScript strict mode plus explicit normalization at the transport boundaries. Shared runtime models live in `protocol/types.ts`; backend-specific raw shapes live next to the normalizers in `utils/api.ts` and `protocol/history.ts`.

## Shared Domain Types

The shared transport and UI model starts in `skill-miniapp/src/protocol/types.ts`.

From `skill-miniapp/src/protocol/types.ts`:

```ts
export type StreamMessageType =
  | 'text.delta'
  | 'text.done'
  | 'thinking.delta'
  | 'thinking.done'
  | 'tool.update'
  | 'question'
  | 'file'
  | 'step.start'
  | 'step.done'
  | 'session.status'
  | 'session.title'
  | 'session.error'
  | 'permission.ask'
  | 'permission.reply'
  | 'agent.online'
  | 'agent.offline'
  | 'message.user'
  | 'planning.delta'
  | 'planning.done'
  | 'searching'
  | 'search_result'
  | 'reference'
  | 'ask_more'
  | 'error'
  | 'snapshot'
  | 'streaming';
```

From `skill-miniapp/src/protocol/types.ts`:

```ts
export interface StreamMessage {
  type: StreamMessageType;
  seq?: number;
  welinkSessionId?: string | number;
  emittedAt?: string;
  raw?: unknown;

  messageId?: string;
  messageSeq?: number;
  role?: MessageRole;
  sourceMessageId?: string;

  partId?: string;
  partSeq?: number;
  content?: string;

  toolName?: string;
  toolCallId?: string;
  status?: 'pending' | 'running' | 'completed' | 'error';
```

And the UI-facing message part model:

From `skill-miniapp/src/protocol/types.ts`:

```ts
export interface MessagePart {
  partId: string;
  partSeq?: number;
  type:
    | 'text'
    | 'thinking'
    | 'tool'
    | 'question'
    | 'permission'
    | 'file'
    | 'subtask'
    | 'planning'
    | 'searching'
    | 'search_result'
    | 'reference'
    | 'ask_more';
  content: string;
  isStreaming: boolean;

  toolName?: string;
  toolCallId?: string;
  toolStatus?: 'pending' | 'running' | 'completed' | 'error';
  toolInput?: Record<string, unknown>;
  toolOutput?: string;
```

Note the current codebase uses a wide interface with a `type` discriminator, not a per-variant union of interfaces. That means every renderer must switch on `type` before reading the optional fields.

## Session And History Types

From `skill-miniapp/src/protocol/types.ts`:

```ts
export interface Message {
  id: string;
  role: MessageRole;
  content: string;
  contentType: 'markdown' | 'code' | 'plain';
  timestamp: number;
  messageSeq?: number;
  meta?: Record<string, unknown>;
  isStreaming?: boolean;
  parts?: MessagePart[];
}

export interface MessageHistoryPage<T> {
  content: T[];
  size: number;
  hasMore: boolean;
  nextBeforeSeq?: number;
}

export interface Session {
  id: string;
  userId?: string;
  ak?: string;
  title: string;
  businessSessionDomain?: string;
  businessSessionType?: string;
  businessSessionId?: string;
  assistantAccount?: string;
  status: 'active' | 'idle' | 'closed';
  toolSessionId?: string;
  createdAt: string;
  updatedAt: string;
}
```

Important current-reality note:
- There is no separate `SessionSummary` type in this miniapp.
- Session lists, detail fetches, and current-session state all use the same `Session` interface.

## API And Error Types

The REST client keeps its raw backend shapes private and exports normalized frontend types.

From `skill-miniapp/src/utils/api.ts`:

```ts
export class ApiError extends Error {
  constructor(
    public status: number,
    public statusText: string,
    public body?: unknown,
  ) {
    super(`API Error ${status}: ${statusText}`);
    this.name = 'ApiError';
  }
}

export interface SkillDefinition {
  id: number;
  skillCode: string;
  skillName: string;
  toolType: string;
  description?: string;
  iconUrl?: string;
  status: string;
}
```

`request()` auto-unwraps the backend envelope `{ code, errormsg, data }`. When `code !== 0`, the JSON body is preserved on `ApiError.body` for downstream business-code narrowing.

From `skill-miniapp/src/utils/api.ts`:

```ts
if (
  json !== null &&
  typeof json === 'object' &&
  'code' in json &&
  'data' in json
) {
  if (json.code !== 0) {
    throw new ApiError(res.status, json.errormsg ?? 'Unknown error', json);
  }
  return json.data as T;
}
```

## Business Code Narrowing (HTTP 200 + body.code)

The skill-server returns HTTP 200 with `body.code=410` to signal "assistant has been deleted". The HTTP status alone is not enough; hooks must reach into `ApiError.body.code`.

The same helper is duplicated in both hooks because the pattern is load-bearing for delete-safety (see commit `519648f`).

From `skill-miniapp/src/hooks/useSkillStream.ts` and `skill-miniapp/src/hooks/useSkillSession.ts`:

```ts
function extractBusinessCode(err: unknown): number | undefined {
  if (err instanceof ApiError && err.body && typeof err.body === 'object' && 'code' in err.body) {
    const code = (err.body as { code?: unknown }).code;
    return typeof code === 'number' ? code : undefined;
  }
  return undefined;
}
```

Typical usage site in `useSkillStream.sendMessageFn`:

```ts
} catch (err) {
  if (extractBusinessCode(err) === 410) {
    setError('该助理已被删除');
  } else {
    const message = err instanceof Error ? err.message : 'Failed to send message';
    setError(message);
  }
  // 保留 optimistic 用户消息，不 rollback（PRD 决策 Q-arch-4）
}
```

Rules:
- Check `extractBusinessCode(err) === 410` before the generic `err instanceof Error` branch.
- Do not rollback the optimistic user message or permission state on 410; the user should see their input plus the deletion notice.
- Never rely on `err.status` (HTTP code) to detect business failures — the transport tier already returned 200.

From `skill-miniapp/src/utils/api.ts`:

```ts
async function request<T>(
  path: string,
  options: RequestInit = {},
): Promise<T> {
  ensureDevUserIdCookie();
  const url = `${baseURL}${path}`;

  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    ...(options.headers as Record<string, string> | undefined),
  };

  const res = await fetch(url, {
    credentials: options.credentials ?? 'include',
    ...options,
    headers,
  });
```

And session payloads are normalized before they escape the API client.

From `skill-miniapp/src/utils/api.ts`:

```ts
function normalizeSession(raw: BackendSession): Session {
  const createdAt = raw.createdAt ?? new Date().toISOString();
  return {
    id: raw.welinkSessionId != null ? String(raw.welinkSessionId) : '0',
    userId: raw.userId ?? undefined,
    ak: raw.ak ?? undefined,
    title: raw.title ?? '',
    businessSessionDomain: raw.businessSessionDomain ?? undefined,
    businessSessionType: raw.businessSessionType ?? undefined,
    businessSessionId: raw.businessSessionId ?? undefined,
    assistantAccount: raw.assistantAccount ?? undefined,
    status: normalizeSessionStatus(raw.status),
    toolSessionId: raw.toolSessionId ?? undefined,
    createdAt,
    updatedAt: raw.updatedAt ?? createdAt,
  };
}
```

## Narrowing Patterns Used In The Live Code

From `skill-miniapp/src/utils/api.ts`:

```ts
export function getOnlineAgents(): Promise<AgentInfo[]> {
  return request<RawAgentInfo[]>('/api/skill/agents').then((agents) =>
    agents
      .map((agent) => normalizeAgent(agent))
      .filter((agent): agent is AgentInfo => agent !== null),
  );
}
```

From `skill-miniapp/src/protocol/history.ts`:

```ts
const parts = Array.isArray(raw.parts)
  ? raw.parts
      .map((part, index) => normalizePart(part, index))
      .filter((part): part is MessagePart => part !== null)
  : [];
```

Use the same shape when converting `unknown` or nullable transport data into frontend state:
- parse or validate first,
- narrow with a type predicate,
- only then publish typed values to hooks or components.

## Discriminants And Exhaustiveness

The two practical discriminants in this subsystem are:
- `StreamMessage.type`
- `MessagePart.type`

The current code already relies on those discriminants in:
- `skill-miniapp/src/protocol/StreamAssembler.ts`
- `skill-miniapp/src/hooks/useSkillStream.ts`
- `skill-miniapp/src/components/MessageBubble.tsx`
- `skill-miniapp/src/protocol/history.ts`

When extending those switches:
- add the new case everywhere the protocol is normalized and rendered,
- prefer a `never` exhaustiveness guard such as `const _exhaustive: never = part.type;` during refactors instead of silently letting the default branch hide an unhandled variant,
- keep the fallback branches only when they are intentionally defensive against server drift.

## Other Shared Types Worth Knowing

From `skill-miniapp/src/protocol/types.ts`:

```ts
export type MiniBarStatus = 'processing' | 'completed' | 'error' | 'offline';

export interface ToolUseInfo {
  toolName: string;
  args?: Record<string, unknown>;
  result?: string;
  error?: string;
  status: 'running' | 'completed' | 'error';
}

export type OpenCodeEventType = string;
export type OpenCodeEvent = Record<string, unknown>;
export type ParsedEvent = Record<string, unknown>;
```

`OpenCodeEvent` and `ParsedEvent` are intentionally loose today. If the parser hardens later, tighten those aliases at the parser boundary instead of leaking raw event objects deep into components.
