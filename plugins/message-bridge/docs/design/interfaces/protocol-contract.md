# Protocol Contract

## 1. Boundary Layers

The current protocol contract is split into:

- `contracts/upstream-events.ts`
- `contracts/downstream-messages.ts`
- `contracts/transport-messages.ts`

The `protocol/` layer normalizes raw messages against these contracts.

## 2. Downstream Contract

Supported downstream message types:

- `invoke`
- `status_query`

### 2.1 `invoke`

Shape:

```ts
{
  type: 'invoke';
  welinkSessionId?: string;
  action: InvokeAction;
  payload: InvokePayloadByAction[InvokeAction];
}
```

Supported `action` values:

- `chat`
- `create_session`
- `close_session`
- `permission_reply`
- `abort_session`
- `question_reply`

Payloads:

```ts
type ChatPayload = {
  toolSessionId: string;
  text: string;
};

type CreateSessionPayload = {
  sessionId?: string;
  metadata?: Record<string, unknown>;
};

type CloseSessionPayload = {
  toolSessionId: string;
};

type PermissionReplyPayload = {
  permissionId: string;
  toolSessionId: string;
  response: 'once' | 'always' | 'reject';
};

type AbortSessionPayload = {
  toolSessionId: string;
};

type QuestionReplyPayload = {
  toolSessionId: string;
  answer: string;
  toolCallId?: string;
};
```

`create_session` also requires a non-empty top-level `welinkSessionId`; if it is missing, runtime returns `tool_error` and does not call the SDK create path.

Notes:

- `close_session` calls `session.delete()`
- `abort_session` also calls `session.abort()`
- `question_reply` resolves a pending question through the raw question API chain

### 2.2 `status_query`

Standalone shape:

```ts
{
  type: 'status_query';
}
```

## 3. Upstream Event Contract

Supported upstream event types:

- `message.updated`
- `message.part.updated`
- `message.part.delta`
- `message.part.removed`
- `session.status`
- `session.idle`
- `session.updated`
- `session.error`
- `permission.updated`
- `permission.asked`
- `question.asked`

Default allowlist is the same exact list.

The bridge extracts `toolSessionId` from the normalized event and then emits:

```ts
{
  type: 'tool_event';
  toolSessionId: string;
  event: SupportedUpstreamEvent;
}
```

## 4. Transport Contract

Bridge-to-gateway transport messages:

```ts
type UpstreamMessage =
  | RegisterMessage
  | HeartbeatMessage
  | ToolEventMessage
  | ToolErrorMessage
  | SessionCreatedMessage
  | StatusResponseMessage;
```

Key shapes:

```ts
type ToolErrorMessage = {
  type: 'tool_error';
  welinkSessionId?: string;
  toolSessionId?: string;
  error: string;
};

type SessionCreatedMessage = {
  type: 'session_created';
  welinkSessionId?: string;
  toolSessionId?: string;
  session?: CreateSessionResultData;
};

type StatusResponseMessage = {
  type: 'status_response';
  opencodeOnline: boolean;
};
```

Completion behavior:

- `session.idle` continues to be forwarded upstream as `tool_event`
- the bridge may synthesize a compat `tool_done` for legacy/UI completion consumers

## 5. Failure Semantics

Protocol parsing is fail-closed.

Upstream normalization failure:

- log event: `event.extraction_failed`
- event is dropped

Downstream normalization failure:

- log event: `downstream.normalization_failed`
- bridge returns existing `tool_error` semantics
