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
- [NPM Publish Guide](./docs/operations/npm-publish-guide.md)

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

`welinkSessionId` remains optional for `create_session`; when present it is passed through to `session_created` / `tool_error`.

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

Interactive setup CLI:

- `node ./scripts/setup-message-bridge.mjs`

The CLI will:

- prompt for `ak` and `sk`
- write `message-bridge.jsonc` in user scope by default
- enable `@opencode-cui/message-bridge` in OpenCode `plugin` config
- create a default `.npmrc` scope entry for `@opencode-cui`

The CLI does not prompt for `gateway.url`; existing values are preserved and missing values fall back to the bridge default.

User-scope `.npmrc` path resolution follows this order:

- `NPM_CONFIG_USERCONFIG`, if explicitly set
- Windows: `%USERPROFILE%\\.npmrc` (falls back to `%HOMEDRIVE%%HOMEPATH%\\.npmrc`)
- macOS / Linux: `~/.npmrc`

On Windows, the user-scope OpenCode config directory follows the same path convention as OpenCode itself: `%USERPROFILE%\\.config\\opencode`. The generated npm scope placeholder is written to the resolved `.npmrc` path above and currently keeps the registry value empty for later internal registry completion.

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
| `gateway.channel` | `opencode` |
| `gateway.heartbeatIntervalMs` | `30000` |
| `gateway.reconnect.baseMs` | `1000` |
| `gateway.reconnect.maxMs` | `30000` |
| `gateway.reconnect.exponential` | `true` |
| `gateway.ping.intervalMs` | `30000` |
| `gateway.ping.pongTimeoutMs` | `10000` |
| `sdk.timeoutMs` | `10000` |
| `events.allowlist` | `DEFAULT_EVENT_ALLOWLIST` |

`gateway.channel` is mapped to register payload field `toolType` when the bridge connects to ai-gateway.

## Logging

The bridge emits structured logs through `client.app.log()` when available.

Register metadata is auto-collected at runtime:

- `deviceName` comes from `os.hostname()`
- `toolVersion` comes from `client.global.health().version`, or from a raw `GET /global/health` fallback when the injected SDK surface does not expose `global.health()`
- `runtime.start()` fails before connect/register when the `global.health` probe fails or returns without a non-empty `version`
- `macAddress` comes from the first usable local network interface, or `""` when unavailable
- `macAddress` is currently a pre-provisioned field for Gateway compatibility; the server must treat `""` as missing

Important normalization and extraction failures are logged as:

- `event.extraction_failed`
- `downstream.normalization_failed`

`BRIDGE_DEBUG=true` enables richer local debug output when log delivery is unavailable.

## Build and Test

```bash
pnpm install
pnpm run typecheck
pnpm run build
pnpm test
pnpm run test:unit
pnpm run test:integration
pnpm run test:e2e
pnpm run test:coverage
```

最低版本前置要求：

- `node >= 24.0.0`
- `pnpm >= 9.15.0`
- `opencode` 命令可用（`verify:env` / `test:e2e:smoke` / `verify:opencode-load` 需要）

一键命令对照：

- 日常开发门禁：`pnpm run verify:core`
- 发布验收：`pnpm run verify:release`
- 发布演练（dry-run）：`pnpm run verify:release:dry`
- 环境自检：`pnpm run verify:env`

测试脚本的验证范围、适用场景与前置要求见：

- [测试策略（脚本矩阵）](./docs/quality/test-strategy.md)

协议回归推荐入口：

```bash
pnpm run test:integration && pnpm run test:e2e:smoke
```

该组合命令会顺序执行：

- `tests/integration`
- `tests/e2e/connect-register.test.mjs`
- `tests/e2e/chat-stream.test.mjs`
- `tests/e2e/permission-roundtrip.test.mjs`

适合作为修改需求代码后的主回归入口，用于验证 `message-bridge` 与 `ai-gateway` 的协议主链路仍然正确。

Distribution and load verification:

```bash
node --import tsx/esm --test tests/integration/plugin-distribution.test.mjs
pnpm run verify:opencode-load
pnpm run verify:release
```

失败排查顺序建议：

1. 先看 `logs/verify-env-*.json`（环境缺失、版本不匹配、端口冲突）
2. 再看 `logs/e2e-smoke-*/summary.json`（协议场景失败分类）
3. 最后看 `logs/opencode-load-verify-*/summary.json`（OpenCode 加载失败分类）

Package installation is the primary path for OpenCode:

```json
{
  "plugin": ["@opencode-cui/message-bridge"]
}
```

Single-file copy into `.opencode/plugins/` remains available as a compatibility path after `pnpm run build`.

## Publishing

维护者发布流程、beta 包约定以及私仓切换方式见：

- [NPM Publish Guide](./docs/operations/npm-publish-guide.md)
