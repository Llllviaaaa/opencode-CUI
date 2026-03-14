# Message Bridge OpenClaw Plugin

`message-bridge` is an OpenClaw channel plugin that connects an OpenClaw runtime
to the existing `ai-gateway` WebSocket protocol used by the OpenCode
`message-bridge` plugin.

This package is the OpenClaw-side adapter. It keeps the gateway protocol
unchanged and translates OpenClaw channel runtime events into the gateway
message contract.

Chinese usage guide:

- `docs/USAGE.zh-CN.md`

Validation manual:

- `docs/VALIDATION.zh-CN.md`

Implementation plan:

- `docs/implementation-plan.md`

Protocol conversion sequences:

- `docs/protocol-sequence.md`

## 安装方式

当前仓库已验证的安装方式都属于本地扩展安装：

- 目录复制安装（推荐）：复制 `dist/`、`package.json`、`openclaw.plugin.json` 到 `~/.openclaw/extensions/message-bridge` 或 `~/.openclaw-dev/extensions/message-bridge`
- 符号链接安装（开发联调）：把插件根目录链接到 profile 的 `extensions/message-bridge`
- bundle 安装（推荐交付）：执行 `npm run build:bundle`，直接复制 `bundle/` 目录内容到 profile 的 `extensions/message-bridge`

`openclaw plugins install` 是 OpenClaw 的通用安装入口，但本仓库当前没有已验证的已发布分发流程；安装命令、配置示例和 bundle 入口修改方式见 `docs/USAGE.zh-CN.md`。

关键约束：

- 不要把 `node_modules/` 复制到 `extensions/message-bridge`
- 插件运行时必须使用宿主 OpenClaw 提供的 `plugin-sdk`
- 如果插件目录里出现 `node_modules/openclaw`，可能会和宿主 `openclaw --version` 解析到的版本冲突

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

- OpenClaw `2026.3.11`
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
`npm run build:bundle` produces a ready-to-install bundle directory at `bundle/`.

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

安装目录中不要包含：

- `node_modules/`
- 特别是 `node_modules/openclaw`

推荐安装后的目录只包含：

- `dist/`
- `package.json`
- `openclaw.plugin.json`
- 文档文件可选

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

## Bundle Install

Run:

```bash
cd /Users/zy/.codex/worktrees/3eda/opencode-CUI/plugins/message-bridge-openclaw
npm run build:bundle
```

Then copy the generated bundle directory contents into:

- `~/.openclaw-dev/extensions/message-bridge`

The generated bundle directory already contains:

- `index.js`
- `package.json`
- `openclaw.plugin.json`
- `README.md`

No manual `package.json` edits are required after copying.

## Runtime Version Conflicts

如果启动时报类似下面的错误：

- `Cannot access 'ANTHROPIC_MODEL_ALIASES' before initialization`

优先检查插件目录里是否存在私有 OpenClaw runtime：

```bash
find ~/.openclaw-dev/extensions/message-bridge -maxdepth 2 -type d | grep node_modules
```

如果存在 `~/.openclaw-dev/extensions/message-bridge/node_modules/openclaw`，先删除整个 `node_modules/`，再按本文档重新安装最小文件集。

这类错误通常不是 OpenClaw 配置字段错误，而是：

- 宿主 `openclaw --version`
- 插件目录里的私有 `node_modules/openclaw`

两者版本混用导致的模块初始化冲突。

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
- published `openclaw plugins install` distribution flow is not yet validated for this package
- plugin install must not include `node_modules/openclaw`
