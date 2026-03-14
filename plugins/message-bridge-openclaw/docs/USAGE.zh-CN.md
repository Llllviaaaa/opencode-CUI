# Message Bridge OpenClaw 插件使用指南

本文档说明 `message-bridge-openclaw` 当前在 OpenClaw 中的已验证安装方式，以及构建、配置、联调步骤。

如果你要按阶段执行验收，而不是只做一次联调，请同时参考：

- `docs/VALIDATION.zh-CN.md`

当前插件目录：

- `/Users/zy/.codex/worktrees/3eda/opencode-CUI/plugins/message-bridge-openclaw`

当前插件能力边界：

- 已支持：`register`、`heartbeat`、`chat`、`create_session`、`close_session`、`abort_session`、`status_query`
- 未支持：`permission_reply`、`question_reply`
- 文本输出为 block 级 streaming，不是 token 级 streaming
- 当前环境里模型首块延迟和超时仍可能影响实际流式体验

运行时依赖约束：

- 插件运行时使用宿主 OpenClaw 提供的 `plugin-sdk`
- 安装目录中不要复制 `node_modules/`
- 尤其不要包含 `node_modules/openclaw`

## 1. 当前支持的安装方式

- 目录复制安装（推荐）
  - 把 `dist/`、`package.json`、`openclaw.plugin.json` 复制到 OpenClaw profile 的 `extensions/message-bridge/`
- 符号链接安装（开发联调）
  - 把插件根目录链接到 `extensions/message-bridge/`
  - 每次修改代码后重新执行 `npm run build`
- bundle 安装（推荐交付）
  - 生成完整的 `bundle/` 安装目录
  - 目标目录直接复制 `bundle/` 的内容
  - 不需要手动修改任何文件
- `openclaw plugins install`
  - 这是 OpenClaw 的通用安装入口
  - 但本仓库当前没有已验证的 npm 发布安装流，本文不把它作为主路径

推荐顺序：

1. 本地部署或交付验证：目录复制安装
2. 本地开发联调：符号链接安装
3. 需要最小手工安装步骤的交付：bundle 安装

## 2. 前置条件

必须满足：

- 已安装 OpenClaw
- `ai-gateway` 已启动
- Redis、MariaDB 已启动
- `ai-gateway` 中已存在可用 AK/SK
- OpenClaw profile 已具备可用模型认证

当前本地验证使用：

- OpenClaw `2026.3.11`
- 网关地址：`ws://127.0.0.1:8081/ws/agent`
- 测试凭据：
  - `ak`: `test-ak-openclaw-001`
  - `sk`: `test-sk-openclaw-001`

OpenClaw profile 常见路径：

- 默认 profile：`~/.openclaw`
- dev profile：`~/.openclaw-dev`

下文默认以 dev profile 为例；如果你使用默认 profile，把路径中的 `.openclaw-dev` 替换成 `.openclaw` 即可。

## 3. 构建

macOS / Linux：

```bash
cd /Users/zy/.codex/worktrees/3eda/opencode-CUI/plugins/message-bridge-openclaw
npm install
npm run build
npm test
```

Windows PowerShell：

```powershell
cd C:\path\to\opencode-CUI\plugins\message-bridge-openclaw
npm install
npm run build
npm test
```

如果你需要可直接复制安装的 bundle，再额外执行：

```bash
npm run build:bundle
```

## 4. 目录复制安装（推荐）

### 4.1 目标目录

- 默认 profile：`~/.openclaw/extensions/message-bridge`
- dev profile：`~/.openclaw-dev/extensions/message-bridge`

### 4.2 macOS / Linux

```bash
export OPENCLAW_EXT_DIR=~/.openclaw-dev/extensions/message-bridge
mkdir -p "$OPENCLAW_EXT_DIR"
rsync -a --delete ./dist/ "$OPENCLAW_EXT_DIR/dist/"
cp ./package.json "$OPENCLAW_EXT_DIR/package.json"
cp ./openclaw.plugin.json "$OPENCLAW_EXT_DIR/openclaw.plugin.json"
```

### 4.3 Windows PowerShell

```powershell
$target = "$env:USERPROFILE\.openclaw-dev\extensions\message-bridge"
New-Item -ItemType Directory -Force -Path $target | Out-Null
robocopy .\dist "$target\dist" /MIR
Copy-Item .\package.json "$target\package.json" -Force
Copy-Item .\openclaw.plugin.json "$target\openclaw.plugin.json" -Force
```

安装后的目标目录建议只保留：

- `dist/`
- `package.json`
- `openclaw.plugin.json`

不要保留：

- `node_modules/`
- `node_modules/openclaw`

## 5. 符号链接安装（开发联调）

这一方式适合频繁改代码、频繁重启网关的场景。插件目录直接指向仓库工作区，不用每次手动拷贝文件。

### 5.1 macOS / Linux

```bash
ln -sfn /Users/zy/.codex/worktrees/3eda/opencode-CUI/plugins/message-bridge-openclaw \
  ~/.openclaw-dev/extensions/message-bridge

npm run build
```

### 5.2 Windows PowerShell

```powershell
New-Item `
  -ItemType SymbolicLink `
  -Path "$env:USERPROFILE\.openclaw-dev\extensions\message-bridge" `
  -Target "C:\path\to\opencode-CUI\plugins\message-bridge-openclaw"

npm run build
```

说明：

- Windows 创建符号链接通常需要管理员权限或启用 Developer Mode
- 如果不方便创建符号链接，继续使用“目录复制安装”即可
- 如果使用符号链接，确保插件根目录下没有会干扰宿主版本解析的 `node_modules/openclaw`

## 6. bundle 安装

这一方式适合“减少手工配置并直接复制安装”。`npm run build:bundle` 会直接生成一个可安装目录。

### 6.1 执行 bundle

```bash
cd /Users/zy/.codex/worktrees/3eda/opencode-CUI/plugins/message-bridge-openclaw
npm run build:bundle
```

输出文件：

- `bundle/index.js`
- `bundle/package.json`
- `bundle/openclaw.plugin.json`
- `bundle/README.md`

说明：

- `openclaw` / `openclaw/*` 被保留为 external
- 目标环境里仍然需要安装 OpenClaw runtime
- bundle 目录内容可以直接复制到插件安装目录

### 6.2 macOS / Linux

```bash
export OPENCLAW_EXT_DIR=~/.openclaw-dev/extensions/message-bridge
mkdir -p "$OPENCLAW_EXT_DIR"
cp ./bundle/index.js "$OPENCLAW_EXT_DIR/index.js"
cp ./bundle/package.json "$OPENCLAW_EXT_DIR/package.json"
cp ./bundle/openclaw.plugin.json "$OPENCLAW_EXT_DIR/openclaw.plugin.json"
cp ./bundle/README.md "$OPENCLAW_EXT_DIR/README.md"
```

### 6.3 Windows PowerShell

```powershell
$target = "$env:USERPROFILE\.openclaw-dev\extensions\message-bridge"
New-Item -ItemType Directory -Force -Path $target | Out-Null
Copy-Item .\bundle\index.js "$target\index.js" -Force
Copy-Item .\bundle\package.json "$target\package.json" -Force
Copy-Item .\bundle\openclaw.plugin.json "$target\openclaw.plugin.json" -Force
Copy-Item .\bundle\README.md "$target\README.md" -Force
```

Bundle 安装目录不需要再修改 `package.json`。生成的 `bundle/package.json` 已经固定为 `index.js` 入口。

## 6.5 运行时版本冲突排查

如果启动 `openclaw gateway run` 时出现类似：

- `Cannot access 'ANTHROPIC_MODEL_ALIASES' before initialization`

先排查插件目录里是否混入了私有 OpenClaw runtime。

macOS / Linux：

```bash
find ~/.openclaw-dev/extensions/message-bridge -maxdepth 2 -type d | grep node_modules
```

Windows PowerShell：

```powershell
Get-ChildItem "$env:USERPROFILE\.openclaw-dev\extensions\message-bridge" -Depth 2 -Directory |
  Where-Object { $_.FullName -match 'node_modules' }
```

如果发现 `node_modules/openclaw`：

1. 删除插件目录中的 `node_modules`
2. 重新按“目录复制安装”或“bundle 单文件安装”部署
3. 再执行 `openclaw --dev gateway run --allow-unconfigured --verbose`

这类错误通常不是 `openclaw.json` 配置字段错误，而是插件目录中的私有 `openclaw` 和宿主 OpenClaw 版本混用导致的模块初始化问题。

## 7. 配置 OpenClaw

配置文件路径：

- 默认 profile：`~/.openclaw/openclaw.json`
- dev profile：`~/.openclaw-dev/openclaw.json`

示例配置：

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
      "runTimeoutMs": 300000,
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

最少需要确认这些字段是正确的：

- `channels.message-bridge.gateway.url`
- `channels.message-bridge.auth.ak`
- `channels.message-bridge.auth.sk`

当前阶段默认 `runTimeoutMs` 已提高到 `300000`，它当前同时作用于两条执行链：

- `dispatchReplyWithBufferedBlockDispatcher(... replyOptions.timeoutOverrideSeconds)`
- `subagent.waitForRun(... timeoutMs)`

如果你没有显式配置 `runTimeoutMs`，插件会使用这个更保守的默认值；如果你已经在配置里写了该字段，则继续以你的显式值为准。

如果你想进一步调细 block 级 streaming，再按需补充：

```json
{
  "agents": {
    "defaults": {
      "blockStreamingChunk": {
        "minChars": 20,
        "maxChars": 80,
        "breakPreference": "sentence"
      },
      "blockStreamingCoalesce": {
        "minChars": 20,
        "maxChars": 80,
        "idleMs": 150
      }
    }
  }
}
```

## 8. 启动

dev profile：

```bash
openclaw --dev gateway run --allow-unconfigured --verbose
```

默认 profile：

```bash
openclaw gateway run --allow-unconfigured --verbose
```

健康启动后应能看到：

- 插件被加载
- `message-bridge` channel account 启动
- 网关连接进入 ready 状态

## 9. 联调验证

### 9.1 校验注册和心跳

查看 `ai-gateway` 日志，确认：

- agent 注册成功
- `toolType=OPENCLAW`
- 心跳持续正常

当前本机日志路径：

- `/Users/zy/Code/opencode/opencode-CUI/logs/local-stack/ai-gateway.log`

### 9.2 校验状态查询

```bash
redis-cli publish agent:test-ak-openclaw-001 '{"type":"status_query"}'
```

预期：

- `ai-gateway` 收到 `status_response`

### 9.3 校验聊天链路

```bash
redis-cli publish agent:test-ak-openclaw-001 '{"type":"invoke","action":"chat","welinkSessionId":"welink-openclaw-verify-001","payload":{"toolSessionId":"tool-openclaw-verify-001","text":"Reply with exactly: hello from openclaw bridge verification"}}'
```

预期：

- 下行 `invoke`
- 上行 `tool_event`
- 最终 `tool_done`

如果模型正常返回，还可以在 OpenClaw session 文件中看到 assistant 落盘内容。

## 10. 常见问题

### 10.1 新建会话没有回复

阶段一修复后，插件默认 `runTimeoutMs` 已提高到 `300000`。如果新建会话仍然没有回复，先按这个顺序看 OpenClaw 日志：

- `bridge.chat.started`
- `bridge.chat.model_selected`
- `bridge.chat.first_chunk`
- `bridge.chat.failed`
- `embedded run agent end: ... LLM request timed out.`

如果有，说明：

- 只有 `bridge.chat.started`：插件已收到请求，但还没进入可见回复阶段
- 有 `bridge.chat.model_selected`，没有 `bridge.chat.first_chunk`：通常是首块过慢或首块前 timeout
- 出现 `bridge.chat.failed`：看其中的 `failureStage`、`errorCategory`、`configuredTimeoutMs`
- 同时出现 `embedded run agent end: ... LLM request timed out.`：问题在模型请求超时，不在插件安装

当前阶段不调整模型路由策略，只修桥接层 timeout 与诊断链路。

### 10.2 有 `tool_event`，但没有可见流式文本

当前插件只支持 block 级 streaming。

另外，是否真正看到流式，还取决于：

- OpenClaw 首块文本是否及时产出
- 当前模型是否超时
- 当前 session 上下文是否过大

### 10.3 出现 `loaded without install/load-path provenance`

这是本地开发扩展的 provenance warning。

影响：

- 不影响功能

原因：

- 插件是从本地扩展目录直接加载的，不是正式安装记录

### 10.4 启动时报 `ANTHROPIC_MODEL_ALIASES before initialization`

优先怀疑安装产物中混入了私有 `node_modules/openclaw`，而不是配置字段本身错误。

先检查：

- `openclaw --version`
- 插件目录下是否存在 `node_modules/openclaw`

如果同时存在宿主 OpenClaw 和插件私有 OpenClaw 两套版本，Node 可能优先解析插件目录中的旧版本，导致启动期模块初始化冲突。

## 11. 建议的交付方式

开发环境建议：

- 优先用“目录复制安装”
- 如果需要高频改动和调试，用“符号链接安装”

需要嵌入式交付时建议：

- 使用 `npm run build:bundle`
- 交付单个 `index.js` + `package.json` + `openclaw.plugin.json`

这样目标环境不需要复制整套源码目录，只需要最小插件文件集。
