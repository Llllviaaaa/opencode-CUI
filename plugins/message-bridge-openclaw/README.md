# Message Bridge OpenClaw Plugin

`message-bridge` is an OpenClaw channel plugin that connects an OpenClaw runtime
to the existing `ai-gateway` WebSocket protocol used by the OpenCode
`message-bridge` plugin.

This package is the OpenClaw-side adapter. It keeps the gateway protocol
unchanged and translates OpenClaw channel runtime events into the gateway
message contract.

Chinese usage guide:

- `docs/USAGE.zh-CN.md`

Implementation plan:

- `docs/implementation-plan.md`

## V1 scope

Supported:

- `register`
- `heartbeat`
- `chat`
- `create_session`
- `close_session`
- `abort_session`
- `status_query`

Deferred in V1:

- `permission_reply`
- `question_reply`

Deferred actions fail closed with `tool_error(unsupported_in_openclaw_v1)`.

## Environment

Current validated environment:

- OpenClaw `2026.3.2`
- local `ai-gateway`
- Redis on `127.0.0.1:6379`
- MariaDB on `127.0.0.1:3306`
- gateway endpoint `ws://127.0.0.1:8081/ws/agent`

The plugin assumes the active OpenClaw profile already has:

- a usable auth profile in `agents/main/agent/auth-profiles.json`
- a valid default model in `agents.defaults.model`

## Build

```bash
cd /Users/zy/.codex/worktrees/3eda/opencode-CUI/plugins/message-bridge-openclaw
npm install
npm run build
npm run build:bundle
npm test
```

Successful build should produce `dist/` and a green `npm test`.
`npm run build:bundle` produces a single-file plugin bundle at `bundle/index.js`.

## Install into OpenClaw dev environment

This guide uses the OpenClaw `--dev` environment.

The current dev plugin location is:

`~/.openclaw-dev/extensions/message-bridge`

Sync the plugin contents into that directory:

```bash
rm -rf /Users/zy/.openclaw-dev/extensions/message-bridge
mkdir -p /Users/zy/.openclaw-dev/extensions/message-bridge
cp -R /Users/zy/.codex/worktrees/3eda/opencode-CUI/plugins/message-bridge-openclaw/dist /Users/zy/.openclaw-dev/extensions/message-bridge/
cp /Users/zy/.codex/worktrees/3eda/opencode-CUI/plugins/message-bridge-openclaw/package.json /Users/zy/.openclaw-dev/extensions/message-bridge/
cp /Users/zy/.codex/worktrees/3eda/opencode-CUI/plugins/message-bridge-openclaw/openclaw.plugin.json /Users/zy/.openclaw-dev/extensions/message-bridge/
```

## OpenClaw dev config

Update the dev config file `~/.openclaw-dev/openclaw.json` with:

```json
{
  "agents": {
    "defaults": {
      "model": "openai-codex/gpt-5.3-codex",
      "blockStreamingDefault": "on",
      "blockStreamingBreak": "text_end"
    }
  },
  "plugins": {
    "allow": ["message-bridge"],
    "entries": {
      "message-bridge": {
        "enabled": true
      }
    }
  },
  "channels": {
    "message-bridge": {
      "enabled": true,
      "blockStreaming": true,
      "gateway": {
        "url": "ws://127.0.0.1:8081/ws/agent",
        "toolType": "OPENCLAW",
        "toolVersion": "0.1.0",
        "deviceName": "OpenClaw Gateway"
      },
      "auth": {
        "ak": "test-ak-openclaw-001",
        "sk": "test-sk-openclaw-001"
      }
    }
  }
}
```

Minimum required fields are:

- `channels.message-bridge.gateway.url`
- `channels.message-bridge.auth.ak`
- `channels.message-bridge.auth.sk`

To enable progressive text delivery, also set:

- `agents.defaults.blockStreamingDefault = "on"`
- `agents.defaults.blockStreamingBreak = "text_end"`
- `channels.message-bridge.blockStreaming = true`

## Start OpenClaw dev gateway

```bash
openclaw --dev gateway run --allow-unconfigured --verbose
```

Healthy startup should show:

- the plugin is loaded
- the `message-bridge` channel account starts
- the gateway connection becomes ready

## Verify registration and heartbeat

Check the gateway log:

`/Users/zy/Code/opencode/opencode-CUI/logs/local-stack/ai-gateway.log`

Expected result:

- registration for `test-ak-openclaw-001`
- `toolType=OPENCLAW`
- periodic heartbeat logs

## Verify control path

Publish a status query:

```bash
redis-cli publish agent:test-ak-openclaw-001 '{"type":"status_query"}'
```

Expected result in `ai-gateway.log`:

- `status_response`
- `opencodeOnline=true`

## Verify chat path

Publish a chat invoke:

```bash
redis-cli publish agent:test-ak-openclaw-001 '{"type":"invoke","action":"chat","welinkSessionId":"welink-openclaw-verify-001","payload":{"toolSessionId":"tool-openclaw-verify-001","text":"Reply with exactly: hello from openclaw bridge verification"}}'
```

Expected result in `ai-gateway.log`:

- downstream `invoke`
- upstream `tool_event`
- upstream `tool_done`

Confirm the actual assistant output in the latest session file under:

`/Users/zy/.openclaw-dev/agents/main/sessions`

## Known limitations

- `permission_reply` is not implemented
- `question_reply` is not implemented
- streaming is block-level, not token-level
- install flow is still dev-profile oriented, not a published distribution flow
