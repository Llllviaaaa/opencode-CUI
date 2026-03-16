# Message Bridge Configuration Reference

This document defines the supported configuration fields for the
`message-bridge-openclaw` plugin, where to configure them, and how effective
values are resolved.

## Supported fields

The plugin reads channel config under `channels.message-bridge`:

- `enabled` (`boolean`, optional, default `true`)
- `name` (`string`, optional)
- `gateway.url` (`string`, required, must start with `ws://` or `wss://`)
- `gateway.heartbeatIntervalMs` (`integer`, optional, default `30000`)
- `gateway.reconnect.baseMs` (`integer`, optional, default `1000`)
- `gateway.reconnect.maxMs` (`integer`, optional, default `30000`)
- `gateway.reconnect.exponential` (`boolean`, optional, default `true`)
- `auth.ak` (`string`, required)
- `auth.sk` (`string`, required)
- `agentIdPrefix` (`string`, optional, default `"message-bridge"`)
- `runTimeoutMs` (`integer`, optional, default `300000`)

Not supported as user config:

- `GatewayUrl` / `gatewayUrl` (non-standard aliases, ignored)
- register metadata fields like `toolType`, `toolVersion`, `deviceName`, `macAddress`

## Where to configure

You can configure these fields in three ways:

1. `openclaw channels add --channel message-bridge --url <gateway-url> --token <ak> --password <sk> [--name <name>]`
2. Channel onboarding flow (`openclaw onboard` / `openclaw channels add` wizard path)
3. Manual edit in OpenClaw config file:
   - default profile: `~/.openclaw/openclaw.json`
   - dev profile (`--dev`): `~/.openclaw-dev/openclaw.json`
   - custom path when `OPENCLAW_CONFIG_PATH` is set

## Resolution and precedence

For plugin-specific fields, effective values are resolved in this order:

1. Explicit values in `channels.message-bridge` in active `openclaw.json`
2. Plugin defaults for missing optional fields

Notes:

- `channels add` / onboarding do not create `GatewayUrl` aliases; they write canonical fields only.
- This plugin does not define dedicated env vars such as `MESSAGE_BRIDGE_GATEWAY_URL`.
- If you need environment-driven values, use `${VAR_NAME}` substitution in `openclaw.json`.
  See OpenClaw env docs:
  - `plugins/openclaw/docs/help/environment.md`
  - `plugins/openclaw/docs/zh-CN/help/environment.md`

