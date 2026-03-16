# Message Bridge 配置说明

本文档定义 `message-bridge-openclaw` 插件当前支持的配置字段、可配置位置与生效优先级。

## 1. 支持的配置字段

插件读取 `channels.message-bridge` 下的配置：

- `enabled`（`boolean`，可选，默认 `true`）
- `debug`（`boolean`，可选，默认 `false`；开启后打印 WebSocket 原始报文日志）
- `name`（`string`，可选）
- `gateway.url`（`string`，必填，必须以 `ws://` 或 `wss://` 开头）
- `gateway.heartbeatIntervalMs`（`integer`，可选，默认 `30000`）
- `gateway.reconnect.baseMs`（`integer`，可选，默认 `1000`）
- `gateway.reconnect.maxMs`（`integer`，可选，默认 `30000`）
- `gateway.reconnect.exponential`（`boolean`，可选，默认 `true`）
- `auth.ak`（`string`，必填）
- `auth.sk`（`string`，必填）
- `agentIdPrefix`（`string`，可选，默认 `"message-bridge"`）
- `runTimeoutMs`（`integer`，可选，默认 `300000`）

不支持作为用户配置的字段：

- `GatewayUrl` / `gatewayUrl`（非标准别名，忽略）
- 注册元数据字段：`toolType`、`toolVersion`、`deviceName`、`macAddress`

## 2. 可以在什么地方配置

你可以通过以下方式配置这些字段：

1. 命令行非交互配置：
   `openclaw channels add --channel message-bridge --url <gateway-url> --token <ak> --password <sk> [--name <name>]`
2. onboarding 交互流程（`openclaw onboard` 或 `openclaw channels add` 的向导路径）
3. 手工编辑 OpenClaw 配置文件：
   - 默认 profile：`~/.openclaw/openclaw.json`
   - dev profile（`--dev`）：`~/.openclaw-dev/openclaw.json`
   - 设置了 `OPENCLAW_CONFIG_PATH` 时，使用该路径

## 3. 生效优先级

对本插件字段，生效值按以下顺序解析：

1. 当前生效 `openclaw.json` 中 `channels.message-bridge` 的显式值
2. 插件默认值（只用于缺失的可选字段）

补充说明：

- `channels add` / onboarding 只会写规范字段，不会写 `GatewayUrl` 别名。
- 本插件没有单独的 `MESSAGE_BRIDGE_*` 环境变量优先级层。
- 如果需要环境变量驱动，可在 `openclaw.json` 里使用 `${VAR_NAME}` 替换。
  详见 OpenClaw 环境变量文档：
  - `plugins/openclaw/docs/help/environment.md`
  - `plugins/openclaw/docs/zh-CN/help/environment.md`
