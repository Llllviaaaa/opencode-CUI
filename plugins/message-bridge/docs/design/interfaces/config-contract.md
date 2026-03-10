# Config Contract

## 1. Source Priority

Configuration is resolved in this order:

1. environment variables with `BRIDGE_*`
2. project config:
   - `.opencode/message-bridge.jsonc`
   - `.opencode/message-bridge.json`
3. user config:
   - `~/.config/opencode/message-bridge.jsonc`
   - `~/.config/opencode/message-bridge.json`
4. built-in defaults

Project config lookup walks upward from the workspace directory until filesystem root.

## 2. Default Source

Built-in defaults are defined in:

- `src/config/default-config.ts`

Key defaults:

| Key | Default |
|---|---|
| `enabled` | `true` |
| `config_version` | `1` |
| `gateway.url` | `ws://localhost:8081/ws/agent` |
| `gateway.deviceName` | `Local Machine` |
| `gateway.toolType` | `opencode` |
| `gateway.toolVersion` | `1.0.0` |
| `sdk.timeoutMs` | `10000` |
| `events.allowlist` | `DEFAULT_EVENT_ALLOWLIST` |

## 3. Minimal Config

```jsonc
{
  "auth": {
    "ak": "your-access-key",
    "sk": "your-secret-key"
  }
}
```

## 4. Validation Rules

Current validation rules include:

- `config_version` must be `1`
- `enabled` must be boolean
- `gateway.url` must start with `ws://` or `wss://`
- timing fields must be positive integers
- `auth.ak` and `auth.sk` are required when `enabled !== false`
- `events.allowlist` must be an exact supported event list
- `sdk.baseUrl` is deprecated and rejected

## 5. Event Allowlist Contract

Default allowlist values are:

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

Wildcard event patterns are not part of the current contract.

## 6. Environment Variables

Examples:

```bash
BRIDGE_AUTH_AK=your-access-key
BRIDGE_AUTH_SK=your-secret-key
BRIDGE_GATEWAY_URL=ws://gateway.example.com/ws/agent
BRIDGE_DEBUG=true
BRIDGE_ENABLED=true
BRIDGE_EVENTS_ALLOWLIST=message.updated,session.status
```

## 7. Logging

Config loading and validation produce structured logs, including:

- `config.resolve.started`
- `config.source.loaded`
- `config.resolve.completed`
- `config.validation.passed`
- `config.validation.failed`
