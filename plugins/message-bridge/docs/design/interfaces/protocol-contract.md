# Protocol Contract

## 1. Boundary Layers

The current protocol contract is split into:

- `contracts/upstream-events.ts`
- `contracts/downstream-messages.ts`
- `contracts/transport-messages.ts`
- `contracts/envelope.ts`

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
  sessionId?: string;
  action: InvokeAction;
  payload: InvokePayloadByAction[InvokeAction];
  envelope?: Envelope;
}
```

Supported `action` values:

- `chat`
- `create_session`
- `close_session`
- `permission_reply`
- `status_query`
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
  response: 'allow' | 'always' | 'deny';
};

type StatusQueryPayload = {
  sessionId?: string;
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

Notes:

- `close_session` currently preserves bridge behavior by calling `session.abort()`
- `abort_session` also calls `session.abort()`
- `question_reply` resolves a pending question through the raw question API chain

### 2.2 `status_query`

Standalone shape:

```ts
{
  type: 'status_query';
  sessionId?: string;
  envelope?: Envelope;
}
```

The bridge also accepts `invoke.action = 'status_query'` for compatibility.

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
  | ToolDoneMessage
  | ToolErrorMessage
  | SessionCreatedMessage
  | StatusResponseMessage;
```

Key shapes:

```ts
type ToolErrorMessage = {
  type: 'tool_error';
  sessionId?: string;
  welinkSessionId?: string;
  error: string;
  envelope: Envelope;
};

type SessionCreatedMessage = {
  type: 'session_created';
  sessionId: string;
  welinkSessionId?: string;
  toolSessionId?: string;
  session?: CreateSessionResultData;
  envelope: Envelope;
};

type StatusResponseMessage = {
  type: 'status_response';
  opencodeOnline: boolean;
  sessionId?: string;
  welinkSessionId?: string;
  envelope: Envelope;
};
```

Compatibility behavior:

- `welinkSessionId` remains optional on response messages
- `tool_event` remains envelope-free

## 5. Failure Semantics

Protocol parsing is fail-closed.

Upstream normalization failure:

- log event: `event.extraction_failed`
- event is dropped

Downstream normalization failure:

- log event: `downstream.normalization_failed`
- bridge returns existing `tool_error` semantics
