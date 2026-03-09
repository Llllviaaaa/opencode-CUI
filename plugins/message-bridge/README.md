# Message Bridge Plugin

A WebSocket-based bridge that connects local OpenCode instances to a remote gateway for bidirectional messaging. This plugin runs in local mode only, relaying events between OpenCode and external IM platforms.

## What It Does

The message-bridge plugin acts as a protocol adapter between OpenCode and a gateway service:

- **Local Mode Only**: Connects to OpenCode running on localhost (port 54321 by default)
- **WebSocket Gateway**: Maintains a persistent connection to the gateway (port 8081 by default)
- **Event Relay**: Streams OpenCode events upstream and executes gateway commands downstream
- **Session Management**: Creates, manages, and closes chat sessions with OpenCode
- **Permission Handling**: Responds to permission requests from OpenCode during execution

## Implemented Actions

The plugin supports 6 gateway-invokable actions plus the standalone `status_query` message:

### 1. chat
Send a message to an existing OpenCode session.

```typescript
payload: {
  toolSessionId: string;  // Required: target OpenCode session
  text: string;           // Required: message content
}
```

### 2. create_session
Create a new OpenCode session.

```typescript
payload: Record<string, unknown>;  // Forwarded to session.create({ body: payload })
```

### 3. abort_session
Abort an active OpenCode session.

```typescript
payload: {
  toolSessionId: string;  // Required: target OpenCode session ID
}
```

### 4. close_session
Close an OpenCode session using **delete semantics**.

```typescript
payload: {
  toolSessionId: string;  // Required: target OpenCode session ID
}
```

### 5. permission_reply
Respond to a permission request from OpenCode. Uses **response-only** protocol:
```typescript
payload: {
  permissionId: string;
  toolSessionId: string;
  response: 'once' | 'always' | 'reject';
}
```

Legacy `approved` payloads are no longer accepted.

### 6. question_reply
Reply to a pending OpenCode question using the raw question APIs.

```typescript
payload: {
  toolSessionId: string;
  toolCallId?: string;  // Optional: used when multiple pending questions exist
  answer: string;
}
```

The plugin resolves the pending request via `GET /question`, then replies with
`POST /question/{requestID}/reply`.

### Standalone status_query
Query the connection health status.

```typescript
{ type: 'status_query' }
```

## Configuration

Configuration loads from multiple sources with this priority (highest first):

1. **Environment variables** (`BRIDGE_*`)
2. **Project config** (`.opencode/message-bridge.jsonc` or `.opencode/message-bridge.json`, with `jsonc` preferred) - supports upward lookup from subdirectories
3. **User config** (`~/.config/opencode/message-bridge.jsonc` or `~/.config/opencode/message-bridge.json`, with `jsonc` preferred)
4. **Built-in defaults**

### Project Config Lookup

The plugin searches for project config candidates in this order at each directory level:

1. `.opencode/message-bridge.jsonc`
2. `.opencode/message-bridge.json`

It starts from the current working directory (or specified workspace) and walks up to the filesystem root. This allows running from any subdirectory within your project:

```
/workspace/project/
  ├── .opencode/
  │   ├── message-bridge.jsonc  ← preferred when both exist
  │   └── message-bridge.json
  ├── src/
  │   └── components/
  │       └── Button.tsx        ← running from here works!
  └── .git/
```

### Minimal Configuration

Only `auth.ak` and `auth.sk` are required fields (when `enabled` is not `false`):

```jsonc
{
  "auth": {
    "ak": "your-access-key",
    "sk": "your-secret-key"
  }
}
```

### Key Configuration Options

| Option | Required | Default | Description |
|--------|----------|---------|-------------|
| `auth.ak` | yes* | none | Access Key |
| `auth.sk` | yes* | none | Secret Key |
| `debug` | no | `false` | Enable detailed debug logging |
| `gateway.url` | no | `ws://localhost:8081/ws/agent` | Gateway WebSocket endpoint |
| `gateway.deviceName` | no | `Local Machine` | Device identifier |
| `gateway.toolType` | no | `opencode` | Tool type identifier |
| `gateway.toolVersion` | no | `1.0.0` | Tool version |
| `gateway.heartbeatIntervalMs` | no | `30000` | Heartbeat frequency (ms) |
| `gateway.reconnect.baseMs` | no | `1000` | Initial reconnect delay (ms) |
| `gateway.reconnect.maxMs` | no | `30000` | Max reconnect delay (ms) |
| `gateway.reconnect.exponential` | no | `true` | Use exponential backoff |
| `gateway.ping.intervalMs` | no | `30000` | Ping interval (ms) |
| `gateway.ping.pongTimeoutMs` | no | `10000` | Pong timeout (ms) |
| `sdk.timeoutMs` | no | `10000` | SDK call timeout (ms) |
| `events.allowlist` | no | `['message.*', 'permission.*', 'question.*', ...]` | Allowed event patterns |
| `enabled` | no | `true` | Enable/disable plugin |
| `config_version` | no | `1` | Config version |

\* Required when `enabled !== false`

### Environment Variables

All config values can be set via environment variables using the `BRIDGE_` prefix:

```bash
BRIDGE_GATEWAY_URL=ws://gateway.example.com/ws/agent
BRIDGE_SDK_TIMEOUT_MS=10000
BRIDGE_AUTH_AK=your-access-key
BRIDGE_AUTH_SK=your-secret-key
BRIDGE_ENABLED=true
BRIDGE_DEBUG=true
```

The `BRIDGE_DEBUG` environment variable is also supported for backward compatibility (equivalent to setting `debug: true` in config).

The plugin uses the injected OpenCode `client` from the plugin runtime and does not accept `sdk.baseUrl`.

### Configuration Loading Logs

The plugin outputs configuration loading information to help diagnose issues:

```
[message-bridge] config.resolve.completed { sources: ['default', 'project:/path', 'env'], ... }
[message-bridge] config.validation.failed { errorCount: 1, errors: [{ path: 'auth.ak', code: 'MISSING_REQUIRED', ... }] }
```

### Structured Logging

This plugin now emits key-chain logs via OpenCode client API `client.app.log()` (`POST /log`).

- Service name is fixed as `message-bridge`
- Levels: `debug`, `info`, `warn`, `error`
- Logs always include the full redacted `extra` payload
- Error logs include normalized detail fields such as `errorDetail`, `errorName`, `sourceErrorCode`, and `errorType` when available
- `BRIDGE_DEBUG=true` only enables local debug hints when log delivery is unavailable or fails
- Log delivery failures never block bridge traffic
- Detailed reference (levels/fields/triggers/sequence diagrams): `docs/operations/logging-reference.md`

## Build and Test Commands

```bash
# Install dependencies
npm install

# Type check without emitting
npm run typecheck

# Build the plugin
npm run build

# Build single-file distribution artifact for direct copy into .opencode/plugins
npm run build:plugin

# Run all tests
npm run test

# Run unit tests only
npm run test:unit

# Run integration tests only
npm run test:integration

# Run e2e tests only
npm run test:e2e

# Run tests with coverage (enforces thresholds)
npm run test:coverage

# One-click local debug flow (mock gateway + opencode + plugin)
npm run debug:e2e

# Fetch logs from OpenCode log files for troubleshooting
# Note: use -- before script arguments
npm run logs:fetch -- --since "1 hour ago" --level error

# Verify OpenCode can load the single-file distribution artifact
npm run verify:opencode-load
```

## Plugin Distribution (Direct Copy)

Use this flow when sharing the plugin with other users who should install it by copying one file.

```bash
# Generate direct-copy artifact
npm run build:plugin

# Copy into target project plugin directory
cp ./release/message-bridge.plugin.js /path/to/target-project/.opencode/plugins/message-bridge.plugin.js
```

Then restart OpenCode in the target project.

- `npm run build` produces development artifacts under `dist/`
- `npm run build:plugin` produces distribution artifact `release/message-bridge.plugin.js` for direct copy

## Developer Debug Flow

Use the one-click script to validate the full chain: plugin loading, gateway handshake, action routing, and log reporting.

```bash
cd plugins/message-bridge
npm run debug:e2e
```

The script will:

1. Build plugin (`npm run build`)
2. Start a local mock gateway (`ws://127.0.0.1:8081/ws/agent`)
3. Start `opencode serve` with isolated HOME and only this plugin enabled
4. Trigger `session.create` + `prompt_async`
5. Assert key checkpoints (`gateway.ready`, `router.route.completed`, `runtime.invoke.completed`)
6. Write logs under `plugins/message-bridge/logs/e2e-debug-<timestamp>/`

Useful environment variables:

- `MB_SKIP_BUILD=true`: skip build step
- `MB_OPENCODE_PORT=4096`: override OpenCode port
- `MB_GATEWAY_PORT=8081`: override mock gateway port
- `MB_LOG_LEVEL=DEBUG`: OpenCode log level
- `BRIDGE_DEBUG=true`: enable local debug hints for log fallback / send-failed cases

### Coverage Requirements

The coverage gate enforces minimum thresholds:
- **Lines**: >= 80%
- **Branches**: >= 70%

Tests use Node.js built-in test runner (`node:test`).

## Quick Integration Usage

```typescript
import { MessageBridgePluginClass, DefaultActionRegistry } from '@opencode-cui/message-bridge';
import { loadConfig } from '@opencode-cui/message-bridge/config';

// Load configuration
const config = await loadConfig('/path/to/workspace');

// Create registry and plugin
const registry = new DefaultActionRegistry();
const plugin = new MessageBridgePluginClass(registry);

// Start the plugin
await plugin.start();

// Actions are now registered and ready to receive gateway invocations
// The plugin handles WebSocket connection, event streaming, and action execution automatically

// When shutting down
await plugin.stop();
```

## Current Status (MVP)

This is a **Minimum Viable Product** with the following characteristics:

- **Local mode only**: Connects to OpenCode on localhost; no remote AI tool support yet
- **OpenCode SDK only**: Uses `@opencode-ai/sdk` for communication
- **Single gateway connection**: One WebSocket connection per plugin instance
- **No persistence**: Sessions live only in memory; no database integration
- **Limited error recovery**: Basic reconnection with exponential backoff

## Architecture Overview

```
┌─────────────────┐     WebSocket      ┌─────────────┐
│  Gateway        │◄──────────────────►│  message-   │
│  (port 8081)    │                    │  bridge     │
└─────────────────┘                    └──────┬──────┘
                                              │
                                              │ SDK calls
                                              ▼
                                       ┌─────────────┐
                                       │  OpenCode   │
                                       │ (port 54321)│
                                       └─────────────┘
```

The plugin maintains a stateful WebSocket connection to the gateway and translates gateway commands into OpenCode SDK calls.

## License

MIT
