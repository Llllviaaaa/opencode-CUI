# Message Bridge Plugin

`message-bridge` is an OpenCode local plugin that bridges a local OpenCode instance and a remote gateway over WebSocket.

The current implementation uses a layered boundary architecture:

- `contracts/`: external boundary contracts
- `protocol/`: raw message normalization and extraction
- `runtime/`: orchestration and transport
- `action/`: execute-only business actions

Related documentation:

- [Architecture Overview](./docs/architecture/overview.md)
- [Source Layout](./docs/architecture/source-layout.md)
- [Protocol Contract](./docs/design/interfaces/protocol-contract.md)
- [Config Contract](./docs/design/interfaces/config-contract.md)
- [Validation Report](./docs/quality/validation-report.md)

## Supported Downstream Messages

The gateway can send these downstream message types:

- `invoke`
- `status_query`

Supported `invoke.action` values:

- `chat`
- `create_session`
- `close_session`
- `permission_reply`
- `abort_session`
- `question_reply`

### `chat`

```ts
payload: {
  toolSessionId: string;
  text: string;
}
```

### `create_session`

```ts
payload: {
  sessionId?: string;
  metadata?: Record<string, unknown>;
}
```

### `close_session`

```ts
payload: {
  toolSessionId: string;
}
```

Current implementation uses `session.delete()` for `close_session`.

### `permission_reply`

```ts
payload: {
  permissionId: string;
  toolSessionId: string;
  response: 'once' | 'always' | 'reject';
}
```

### `status_query`

Standalone:

```ts
{ type: 'status_query' }
```

### `abort_session`

```ts
payload: {
  toolSessionId: string;
}
```

### `question_reply`

```ts
payload: {
  toolSessionId: string;
  answer: string;
  toolCallId?: string;
}
```

The action resolves a pending question with `GET /question` and replies with `POST /question/{requestID}/reply`.

## Supported Upstream Events

The default allowlist is an exact event list:

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

Wildcard defaults such as `message.*` and `session.*` are no longer used.

Upstream transport shape remains:

```ts
{
  type: 'tool_event',
  toolSessionId: string,
  event: SupportedUpstreamEvent
}
```

## Transport Messages

Bridge-to-gateway transport messages currently include:

- `register`
- `heartbeat`
- `tool_event`
- `tool_error`
- `session_created`
- `status_response`

Transport response shapes:

- `tool_error`: `{ type, welinkSessionId?, toolSessionId?, error }`
- `session_created`: `{ type, welinkSessionId?, toolSessionId?, session }`
- `status_response`: `{ type, opencodeOnline }`

## Configuration

Configuration priority, high to low:

1. `BRIDGE_*` environment variables
2. project config: `.opencode/message-bridge.jsonc` then `.opencode/message-bridge.json`
3. user config: `~/.config/opencode/message-bridge.jsonc` then `.json`
4. built-in defaults

Defaults are defined in:

- [default-config.ts](./src/config/default-config.ts)

### Minimal Config

```jsonc
{
  "auth": {
    "ak": "your-access-key",
    "sk": "your-secret-key"
  }
}
```

### Key Defaults

| Key | Default |
|---|---|
| `enabled` | `true` |
| `config_version` | `1` |
| `gateway.url` | `ws://localhost:8081/ws/agent` |
| `gateway.toolType` | `OPENCODE` |
| `gateway.heartbeatIntervalMs` | `30000` |
| `gateway.reconnect.baseMs` | `1000` |
| `gateway.reconnect.maxMs` | `30000` |
| `gateway.reconnect.exponential` | `true` |
| `gateway.ping.intervalMs` | `30000` |
| `gateway.ping.pongTimeoutMs` | `10000` |
| `sdk.timeoutMs` | `10000` |
| `events.allowlist` | `DEFAULT_EVENT_ALLOWLIST` |

## Logging

The bridge emits structured logs through `client.app.log()` when available.

Register metadata is auto-collected at runtime:

- `deviceName` comes from `os.hostname()`
- `toolVersion` comes only from `client.global.health().version`
- `runtime.start()` fails before connect/register when `global.health()` is unavailable, throws, or returns without a non-empty `version`
- `macAddress` comes from the first usable local network interface, or `""` when unavailable
- `macAddress` is currently a pre-provisioned field for Gateway compatibility; the server must treat `""` as missing

Important normalization and extraction failures are logged as:

- `event.extraction_failed`
- `downstream.normalization_failed`

`BRIDGE_DEBUG=true` enables richer local debug output when log delivery is unavailable.

## Build and Test

```bash
bun install
bun run typecheck
bun run build
bun run test
bun run test:unit
bun run test:integration
bun run test:e2e
bun run test:coverage
```

Distribution and load verification:

```bash
bun test tests/integration/plugin-distribution.test.mjs
bun run verify:opencode-load
```

Package installation is the primary path for OpenCode:

```json
{
  "plugin": ["@opencode-cui/message-bridge"]
}
```

Single-file copy into `.opencode/plugins/` remains available as a compatibility path after `bun run build`.
