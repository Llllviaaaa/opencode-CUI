# Message Bridge OpenClaw Plugin

`message-bridge` is an OpenClaw channel plugin that connects an OpenClaw runtime
to the existing `ai-gateway` WebSocket protocol used by the OpenCode
`message-bridge` plugin.

This package is the OpenClaw-side adapter. It keeps the gateway protocol
unchanged and translates OpenClaw channel runtime events into the gateway
message contract.

Chinese usage guide:

- `docs/USAGE.zh-CN.md`
- `docs/LOGGING-MATRIX.zh-CN.md`（日志事件矩阵）
- `docs/CONFIGURATION.zh-CN.md`（配置字段、配置位置、优先级）

Configuration reference:

- `docs/CONFIGURATION.md`

Validation manual:

- `docs/VALIDATION.zh-CN.md`

Implementation plan:

- `docs/implementation-plan.md`

Protocol conversion sequences:

- `docs/protocol-sequence.md`

P0 首块稳定性专题（需求 + 方案）:

- `docs/topics/mb-p0-first-chunk-stability.md`
- `docs/topics/mb-p0-first-chunk-stability-solution.md`

P0 阶段四 permission_reply 专题（需求 + 方案）:

- `docs/topics/mb-p0-permission-bridge-requirements.md`
- `docs/topics/mb-p0-permission-bridge-solution.md`
  - 阶段四文档已冻结：`permissionId` 透传、插件不承担唯一性保障、实现进行中
  - FR: `FR-MB-OPENCLAW-P0-PERMISSION-BRIDGE`
  - 目标：`permission_reply` 映射 OpenClaw `exec approvals`
  - 范围外：`question_reply` 继续 fail-closed

P0 参考专题（feishu-openclaw 能力需求清单）:

- `docs/topics/mb-p0-feishu-openclaw-reference-requirements.md`
  - FR: `FR-MB-OPENCLAW-P0-FEISHU-REFERENCE`
  - 内容：需求描述 + 优先级 + OpenClaw 插件接口一对一主依赖映射

## 安装方式

当前仓库已验证的安装方式都属于本地扩展安装：

- 目录复制安装（推荐）：复制 `dist/`、`package.json`、`openclaw.plugin.json` 到 `~/.openclaw/extensions/message-bridge` 或 `~/.openclaw-dev/extensions/message-bridge`
- 符号链接安装（开发联调）：把插件根目录链接到 profile 的 `extensions/message-bridge`
- bundle 安装（推荐交付）：执行 `npm run install:bundle:dev`，自动把 `bundle/` 安装到 `~/.openclaw-dev/extensions/message-bridge`

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

Deferred actions fail closed with stable shape:

- `type=tool_error`
- `error=unsupported_in_openclaw_v1:<action>`
- no `errorCode` / `action` wire extension fields
- no `tool_done` fallback receipt for deferred actions

Upgrade path:

- current phase keeps both actions unsupported by design
- implementation target and rollout gate are tracked in `docs/implementation-plan.md` (phase four)

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
npm run install:bundle:dev
npm test
```

Successful build should produce `dist/` and a green `npm test`.
`npm run build:bundle` produces a ready-to-install bundle directory at `bundle/`.
`npm run install:bundle:dev` builds the bundle and installs it into the OpenClaw `--dev` profile.

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
        "url": "ws://127.0.0.1:8081/ws/agent"
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

Interactive `setup` / `onboarding` only writes:

- `channels.message-bridge.name`
- `channels.message-bridge.gateway.url`
- `channels.message-bridge.auth.ak`
- `channels.message-bridge.auth.sk`

The following register metadata fields are runtime-derived and not user-configurable:

- `toolType` is fixed to `openclaw`
- `deviceName` comes from `os.hostname()`
- `toolVersion` comes from the plugin package version at runtime
- `macAddress` comes from the first usable local network interface, or `""` when unavailable

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
npm run install:bundle:dev
```

This command will:

- run `npm run build:bundle`
- install the generated bundle into `~/.openclaw-dev/extensions/message-bridge`
- print the installed files
- print the next gateway start command

If you need manual install instead, copy the generated `bundle/` directory contents into:

- `~/.openclaw-dev/extensions/message-bridge`

The generated bundle directory already contains:

- `index.js`
- `package.json`
- `openclaw.plugin.json`
- `README.md`

No manual `package.json` edits are required.

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
- `toolType=openclaw`
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
- both actions return fail-closed standard `tool_error` with `error=unsupported_in_openclaw_v1:<action>`
- `close_session` success only clears local session state and does not emit `tool_done`
- streaming is block-level, not token-level
- published `openclaw plugins install` distribution flow is not yet validated for this package
- plugin install must not include `node_modules/openclaw`
