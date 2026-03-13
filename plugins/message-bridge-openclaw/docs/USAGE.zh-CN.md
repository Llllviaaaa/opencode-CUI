# Message Bridge OpenClaw 插件使用指南

本文档面向两类使用方式：

- 作为独立插件目录安装到 OpenClaw
- 打包成单个 JS 文件后，作为源码直接集成到 OpenClaw 插件目录

当前插件目录：

- `/Users/zy/.codex/worktrees/3eda/opencode-CUI/plugins/message-bridge-openclaw`

当前插件能力边界：

- 已支持：`register`、`heartbeat`、`chat`、`create_session`、`close_session`、`abort_session`、`status_query`
- 未支持：`permission_reply`、`question_reply`
- 文本输出为 block 级 streaming，不是 token 级 streaming
- 当前环境里模型首块延迟和超时仍可能影响实际流式体验

## 1. 前置条件

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

## 2. macOS 安装步骤

### 2.1 构建

```bash
cd /Users/zy/.codex/worktrees/3eda/opencode-CUI/plugins/message-bridge-openclaw
npm install
npm run build
npm test
```

### 2.2 安装为目录插件

OpenClaw dev profile 目录通常是：

- `~/.openclaw-dev/extensions/message-bridge`

同步插件产物：

```bash
mkdir -p ~/.openclaw-dev/extensions/message-bridge
rsync -a --delete ./dist/ ~/.openclaw-dev/extensions/message-bridge/dist/
cp ./package.json ~/.openclaw-dev/extensions/message-bridge/package.json
cp ./openclaw.plugin.json ~/.openclaw-dev/extensions/message-bridge/openclaw.plugin.json
```

### 2.3 配置 OpenClaw

编辑 `~/.openclaw-dev/openclaw.json`：

```json
{
  "agents": {
    "defaults": {
      "model": "openai-codex/gpt-5.3-codex",
      "blockStreamingDefault": "on",
      "blockStreamingBreak": "text_end",
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

### 2.4 启动

```bash
openclaw --dev gateway run --allow-unconfigured --verbose
```

## 3. Windows 安装步骤

以下示例使用 PowerShell。

### 3.1 构建

```powershell
cd C:\path\to\opencode-CUI\plugins\message-bridge-openclaw
npm install
npm run build
npm test
```

### 3.2 安装为目录插件

假设 OpenClaw dev profile 在：

- `%USERPROFILE%\.openclaw-dev\extensions\message-bridge`

同步插件产物：

```powershell
$target = "$env:USERPROFILE\.openclaw-dev\extensions\message-bridge"
New-Item -ItemType Directory -Force -Path $target | Out-Null
robocopy .\dist "$target\dist" /MIR
Copy-Item .\package.json "$target\package.json" -Force
Copy-Item .\openclaw.plugin.json "$target\openclaw.plugin.json" -Force
```

### 3.3 配置

编辑：

- `%USERPROFILE%\.openclaw-dev\openclaw.json`

配置内容与 macOS 相同，只需要把 `gateway.url`、`ak`、`sk` 替换成你的实际值。

### 3.4 启动

```powershell
openclaw --dev gateway run --allow-unconfigured --verbose
```

## 4. 打包为单个 JS 文件

这一方式适合“把插件源码以一个 JS 文件形式直接集成到 OpenClaw 扩展目录”。

### 4.1 执行 bundle

```bash
cd /Users/zy/.codex/worktrees/3eda/opencode-CUI/plugins/message-bridge-openclaw
npm run build:bundle
```

输出文件：

- `bundle/index.js`

这个 bundle 会把插件内部 TS/JS 源码合并为一个 ESM 文件。

说明：

- `openclaw` / `openclaw/*` 被保留为 external
- 这意味着目标环境里仍然需要安装 OpenClaw runtime
- 但插件自己的本地模块不再需要整目录拷贝

### 4.2 以单文件方式集成到 OpenClaw

目标目录最少保留三个文件：

- `index.js`
- `package.json`
- `openclaw.plugin.json`

macOS 示例：

```bash
mkdir -p ~/.openclaw-dev/extensions/message-bridge
cp ./bundle/index.js ~/.openclaw-dev/extensions/message-bridge/index.js
cp ./package.json ~/.openclaw-dev/extensions/message-bridge/package.json
cp ./openclaw.plugin.json ~/.openclaw-dev/extensions/message-bridge/openclaw.plugin.json
```

然后把 `package.json` 里的入口改成 bundle 文件：

```json
{
  "main": "index.js",
  "openclaw": {
    "extensions": ["./index.js"]
  }
}
```

Windows 示例：

```powershell
$target = "$env:USERPROFILE\.openclaw-dev\extensions\message-bridge"
New-Item -ItemType Directory -Force -Path $target | Out-Null
Copy-Item .\bundle\index.js "$target\index.js" -Force
Copy-Item .\package.json "$target\package.json" -Force
Copy-Item .\openclaw.plugin.json "$target\openclaw.plugin.json" -Force
```

同样需要把目标目录里的 `package.json` 入口改成：

```json
{
  "main": "index.js",
  "openclaw": {
    "extensions": ["./index.js"]
  }
}
```

## 5. 联调验证

### 5.1 校验注册和心跳

查看 `ai-gateway` 日志，确认：

- agent 注册成功
- `toolType=OPENCLAW`
- 心跳持续正常

当前本机日志路径：

- `/Users/zy/Code/opencode/opencode-CUI/logs/local-stack/ai-gateway.log`

### 5.2 校验状态查询

```bash
redis-cli publish agent:test-ak-openclaw-001 '{"type":"status_query"}'
```

预期：

- `ai-gateway` 收到 `status_response`

### 5.3 校验聊天链路

```bash
redis-cli publish agent:test-ak-openclaw-001 '{"type":"invoke","action":"chat","welinkSessionId":"welink-openclaw-verify-001","payload":{"toolSessionId":"tool-openclaw-verify-001","text":"Reply with exactly: hello from openclaw bridge verification"}}'
```

预期：

- 下行 `invoke`
- 上行 `tool_event`
- 最终 `tool_done`

如果模型正常返回，还可以在 OpenClaw session 文件中看到 assistant 落盘内容。

## 6. 常见问题

### 6.1 新建会话没有回复

先看 OpenClaw 日志里是否出现：

- `bridge.chat.started`
- `embedded run agent end: ... LLM request timed out.`

如果有，说明：

- 会话创建和消息路由是正常的
- 问题在模型请求超时，不在插件安装

### 6.2 有 `tool_event`，但没有可见流式文本

当前插件只支持 block 级 streaming。

另外，是否真正看到流式，还取决于：

- OpenClaw 首块文本是否及时产出
- 当前模型是否超时
- 当前 session 上下文是否过大

### 6.3 出现 `loaded without install/load-path provenance`

这是本地开发扩展的 provenance warning。

影响：

- 不影响功能

原因：

- 插件是从本地扩展目录直接加载的，不是正式安装记录

## 7. 建议的交付方式

开发环境建议：

- 使用 `dist/` 目录安装

需要嵌入式交付时建议：

- 使用 `npm run build:bundle`
- 交付单个 `index.js` + `package.json` + `openclaw.plugin.json`

这样目标环境不需要复制整套源码目录，只需要最小插件文件集。
