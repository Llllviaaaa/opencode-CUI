# Message Bridge 阶段一、阶段二验证手册

本文档用于验证 `message-bridge-openclaw` 在当前仓库中的阶段一、阶段二能力。

它解决两个问题：

- 需要一套可重复执行的验证步骤，而不是分散在测试、README 和聊天记录里
- 需要把“阶段一验证什么”和“阶段二验证什么”明确拆开，避免验收口径混乱

相关文档：

- 安装与联调：`docs/USAGE.zh-CN.md`
- 配置字段与优先级：`docs/CONFIGURATION.zh-CN.md`
- 实施计划：`docs/implementation-plan.md`
- 协议时序：`docs/protocol-sequence.md`
- 本轮验证与覆盖审计：`docs/VALIDATION-AUDIT.zh-CN.md`

## 1. 验证范围

### 阶段一

阶段一验证桥接核心链路是否可用，重点是：

- `register` / `heartbeat`
- `status_query`
- `create_session`
- `chat`
- `close_session`
- 不支持动作是否 fail-closed
- 新会话能否稳定走到回复完成

### 阶段二

阶段二验证插件产品化能力是否可用，重点是：

- 正式 `configSchema`
- 单账号配置收口
- `setup` / 轻量 `onboarding`
- `probe/status/issues`
- `setAccountEnabled` / `deleteAccount`

不在本手册范围内：

- `permission_reply`
- `question_reply`
- `pairing` / `security` / `directory` / `outbound`
- 更高层的流式体验优化

说明：

- `abort_session` 属于支持能力，但本手册把它定义为“自动化主验证，手工可选补充”
- 原因是它依赖运行中会话和更严格的时序，手工联调的稳定性不如自动化测试

## 2. 前置条件

执行本文步骤前，先确保：

- OpenClaw 可执行命令可用
- `ai-gateway`、Redis、MariaDB 已启动
- 插件已构建并安装到 OpenClaw profile
- `~/.openclaw-dev/openclaw.json` 或 `~/.openclaw/openclaw.json` 中已允许加载 `message-bridge`
- 已准备可用的 `gateway.url`、`auth.ak`、`auth.sk`
- 如果要做 live 验证，`gateway.mode` 必须显式配置成 `local`

如果你还没完成安装和基础配置，先按 `docs/USAGE.zh-CN.md` 执行：

- 构建
- 安装到 `extensions/message-bridge`
- 修改 `openclaw.json`
- 启动 `openclaw --dev gateway run --allow-unconfigured --verbose`

本文默认使用 dev profile：

- OpenClaw profile：`~/.openclaw-dev`
- 插件目录：`~/.openclaw-dev/extensions/message-bridge`
- 配置文件：`~/.openclaw-dev/openclaw.json`

除非特别说明，下面所有 `openclaw` 命令都默认带 `--dev`。
如果你验证的是默认 profile，把命令中的 `--dev` 去掉即可。

### 2.1 当前 live 阻塞前置项

如果下面两条命令还不能成功：

```bash
openclaw --dev channels status --probe --json
openclaw --dev doctor
```

那么依赖 OpenClaw dev gateway 的手工步骤只能判定为“阻塞”，不能判定为“失败”或“通过”。

最常见的阻塞原因有两个：

- `~/.openclaw-dev/openclaw.json` 里没有 `gateway.mode=local`
- 本地没有启动 `openclaw --dev gateway run --allow-unconfigured --verbose`

## 3. 快速入口

### 全量自动化回归

```bash
cd /Users/zy/.codex/worktrees/3eda/opencode-CUI/plugins/message-bridge-openclaw
npm test
```

### 只跑阶段一相关测试

```bash
cd /Users/zy/.codex/worktrees/3eda/opencode-CUI/plugins/message-bridge-openclaw
npm run build
node --test tests/downstream-normalization.test.mjs tests/bridge-chat.test.mjs
```

覆盖点：

- 下行协议归一化
- `create_session` 参数约束
- `status_query -> status_response`
- `create_session -> session_created`
- `chat -> tool_event -> tool_done`
- `close_session`
- `abort_session`
- dispatcher / subagent fallback
- 超时与错误路径
- ready / inbound / outbound / heartbeat 运行时状态
- unsupported action fail-closed

### 只跑阶段二相关测试

```bash
cd /Users/zy/.codex/worktrees/3eda/opencode-CUI/plugins/message-bridge-openclaw
npm run build
node --test tests/config-status.test.mjs
```

覆盖点：

- `configSchema`
- 单账号 `default` 语义
- `setup` / `onboarding`
- `probe` 四条分支
- `status/issues`
- `setAccountEnabled` / `deleteAccount`
- legacy `accounts` 与错误输入的回退行为

## 4. 阶段一验证手册

### 4.1 自动化验证

先执行：

```bash
cd /Users/zy/.codex/worktrees/3eda/opencode-CUI/plugins/message-bridge-openclaw
npm run build
node --test tests/downstream-normalization.test.mjs tests/bridge-chat.test.mjs
```

通过标准：

- 所有测试通过
- 没有 `tool_done`/`tool_error` 相关断言失败
- 没有 `bridge status` 时间戳相关断言失败

### 4.2 手工验证

#### 步骤 1：启动 OpenClaw

```bash
openclaw --dev gateway run --allow-unconfigured --verbose
```

预期结果：

- 插件被加载
- `message-bridge` account 启动
- 连接最终进入 ready

#### 步骤 2：验证注册和心跳

查看 `ai-gateway` 日志：

- `/Users/zy/Code/opencode/opencode-CUI/logs/local-stack/ai-gateway.log`

预期结果：

- 出现 agent 注册成功日志
- 日志中能看到 `toolType=openclaw`
- 能持续看到 heartbeat

#### 步骤 3：验证 `status_query`

```bash
redis-cli publish agent:test-ak-openclaw-001 '{"type":"status_query"}'
```

预期结果：

- `ai-gateway.log` 中出现 `status_response`
- 结果里能看到 `opencodeOnline=true`

#### 步骤 4：验证 `create_session`

```bash
redis-cli publish agent:test-ak-openclaw-001 '{"type":"invoke","action":"create_session","welinkSessionId":"welink-stage1-session-001","payload":{"sessionId":"session-stage1-001"}}'
```

预期结果：

- `ai-gateway.log` 中出现 `session_created`
- 响应里包含 `welinkSessionId=welink-stage1-session-001`
- 响应里包含生成后的 `toolSessionId`

#### 步骤 5：验证 `chat`

```bash
redis-cli publish agent:test-ak-openclaw-001 '{"type":"invoke","action":"chat","welinkSessionId":"welink-stage1-chat-001","payload":{"toolSessionId":"tool-stage1-chat-001","text":"Reply with exactly: hello from openclaw bridge verification"}}'
```

预期结果：

- `ai-gateway.log` 中能看到下行 `invoke`
- 上行依次出现一个或多个 `tool_event`
- 最终出现 `tool_done`
- OpenClaw 最新 session 文件中能看到回复文本

默认可检查：

- `~/.openclaw-dev/agents/main/sessions`

#### 步骤 6：验证 `close_session`

```bash
redis-cli publish agent:test-ak-openclaw-001 '{"type":"invoke","action":"close_session","welinkSessionId":"welink-stage1-session-001","payload":{"toolSessionId":"session-stage1-001"}}'
```

预期结果：

- 插件侧会删除 `toolSessionId=session-stage1-001` 对应的本地 session
- `close_session` 成功路径不应再上报 `tool_done`
- 不应出现 `tool_error`
- 后续同一 `toolSessionId` 不应再继续复用旧 session 状态

#### 步骤 7：验证 unsupported action fail-closed

```bash
redis-cli publish agent:test-ak-openclaw-001 '{"type":"invoke","action":"permission_reply","welinkSessionId":"welink-stage1-unsupported-001","payload":{"toolSessionId":"tool-stage1-unsupported-001","permissionId":"perm-001","response":"once"}}'
```

预期结果：

- 返回 `tool_error`
- `error` 中包含 `unsupported_in_openclaw_v1:permission_reply`
- 不应返回 `tool_done`

继续验证 `question_reply`：

```bash
redis-cli publish agent:test-ak-openclaw-001 '{"type":"invoke","action":"question_reply","welinkSessionId":"welink-stage1-unsupported-002","payload":{"toolSessionId":"tool-stage1-unsupported-001","answer":"ok"}}'
```

预期结果：

- 返回 `tool_error`
- `error` 中包含 `unsupported_in_openclaw_v1:question_reply`
- 不应返回 `tool_done`

### 4.3 阶段一通过标准

阶段一可视为通过，需要同时满足：

- 注册、心跳、`status_query`、`create_session`、`chat`、`close_session` 均可联通
- 新会话下 `chat` 能稳定产生回复，不依赖旧会话残留状态
- unsupported action 走 fail-closed，而不是静默成功
- 自动化回归全部通过

## 5. 阶段二验证手册

### 5.1 自动化验证

先执行：

```bash
cd /Users/zy/.codex/worktrees/3eda/opencode-CUI/plugins/message-bridge-openclaw
npm run build
node --test tests/config-status.test.mjs
```

重点观察这些断言是否通过：

- `message bridge config schema accepts single-account config`
- `message bridge listAccountIds is fixed to default and rejects non-default account ids`
- `probeMessageBridgeAccount covers ready, rejected, connect_error, disconnect-before-ready and timeout`
- `collectMessageBridgeStatusIssues reports config, auth and runtime problems`
- `message bridge onboarding retries invalid input until the default account is configured`
- `message bridge onboarding skips legacy accounts config instead of reporting success`

### 5.2 手工验证

阶段二默认建立在“阶段一核心链路已经健康”的前提上。

#### 步骤 1：验证非交互 `setup`

当前 `setup` / `onboarding` 只支持配置：

- `name`
- `gateway.url`
- `auth.ak`
- `auth.sk`

不支持配置 `gateway.toolType`、`gateway.toolVersion`、`gateway.deviceName`、`gateway.macAddress`。

先备份当前配置，再执行：

```bash
openclaw --dev channels add --channel message-bridge --url ws://127.0.0.1:8081/ws/agent --token test-ak-openclaw-001 --password test-sk-openclaw-001 --name "Primary bridge"
```

预期结果：

- 配置写入 `channels.message-bridge` 顶层
- 顶层存在 `gateway.url`、`auth.ak`、`auth.sk`
- 顶层存在 `name`
- 没有写入 `channels.message-bridge.accounts`

#### 步骤 2：验证单账号限制

```bash
openclaw --dev channels add --channel message-bridge --account secondary --url ws://127.0.0.1:8081/ws/agent --token test-ak-openclaw-001 --password test-sk-openclaw-001
```

预期结果：

- CLI 直接报错
- 错误信息包含 `single_account_only`
- 配置文件不应新增 `secondary`

#### 步骤 3：验证交互式 `onboarding` 会重试错误输入

说明：

- `name` 仅作为账号展示名，不参与注册协议
- 注册时的 `toolType/deviceName/toolVersion/macAddress` 统一由运行时派生

执行：

```bash
openclaw --dev channels add
```

在 wizard 中选择 `message-bridge`，第一次故意输入：

- URL：`http://127.0.0.1:8081/ws/agent`
- AK：留空
- SK：留空

预期结果：

- wizard 提示输入不合法
- 不会直接把 channel 记成配置成功
- 会继续要求重新输入

然后第二次输入合法值：

- URL：`ws://127.0.0.1:8081/ws/agent`
- AK：真实 AK
- SK：真实 SK

预期结果：

- wizard 完成配置
- `openclaw.json` 中出现合法顶层配置

#### 步骤 4：验证 legacy `accounts` 会跳过而不是假成功

先把配置手工改成 legacy 形态：

```json
{
  "channels": {
    "message-bridge": {
      "accounts": {
        "legacy": {
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
  }
}
```

然后再次执行：

```bash
openclaw --dev channels add
```

预期结果：

- wizard 显示 `channels.message-bridge.accounts` 已废弃
- channel 不应被当成“配置成功”
- 原配置保持不变，等待你先迁移配置

#### 步骤 5：验证 `status/probe`

```bash
openclaw --dev channels status --probe --json
```

预期结果：

- `message-bridge` 账号存在
- `accountId` 固定为 `default`
- `configured` 为 `true`
- `running` 为 `true`
- `connected` 为 `true`
- `probe.state` 为 `ready`
- JSON 中包含：
  - `lastReadyAt`
  - `lastHeartbeatAt`
  - `lastProbeAt`
  - `probe`

#### 步骤 6：验证 `doctor`

```bash
openclaw --dev doctor
```

预期结果：

- 如果配置和连接正常，不应出现 `message-bridge` 的阻塞性问题
- 如果有问题，输出应带有可执行 fix，而不是只有原始错误

#### 步骤 7：验证 issue 分类

建议按下面几组场景分别修改配置，再重复执行：

```bash
openclaw --dev channels status --probe
openclaw --dev doctor
```

| 场景 | 操作 | 预期 issue |
| --- | --- | --- |
| 缺少配置 | 删除 `channels.message-bridge.auth.sk` | `config`，提示缺少必填字段 |
| legacy 配置 | 写入 `channels.message-bridge.accounts` | `config`，提示迁移 |
| 鉴权失败 | 保持 `gateway.url` 正确，故意写错 `ak/sk` | `auth`，提示检查凭证 |
| 连接失败 | 把 `gateway.url` 改到错误端口或错误地址 | `runtime`，提示无法连接网关 |
| 探活超时 | 指向会吞连接但不返回 ready 的环境 | `runtime`，提示 probe timeout |

可选高级场景：

- 如果你能控制 `ai-gateway` 注册策略，让它因为协议版本或策略拒绝注册，而不是凭证错误
- 预期 issue 应为 `runtime`
- 不应误报成 `auth`

#### 步骤 8：验证禁用和删除

禁用：

```bash
openclaw --dev channels remove --channel message-bridge
```

预期结果：

- CLI 输出 `Disabled Message Bridge account "default"`
- `channels.message-bridge.enabled` 变为 `false`

删除：

```bash
openclaw --dev channels remove --channel message-bridge --delete
```

预期结果：

- CLI 输出 `Deleted Message Bridge account "default"`
- `channels.message-bridge` 被移除

关于“仅改显示名时不应重新启用已禁用账号”：

- 这个场景已由自动化测试覆盖
- 推荐以 `tests/config-status.test.mjs` 为准
- 如果要手工复现，需先构造 disabled 配置，再走交互式 `channels add` 的 `Add display names for these accounts?` 分支

关于 `abort_session`：

- 这个场景以自动化测试为主验证
- 当前已覆盖成功中止和 `unknown_tool_session` 错误分支
- 如果要做 live 补充验证，建议只在可稳定复现“运行中会话”的环境里执行

### 5.3 阶段二通过标准

阶段二可视为通过，需要同时满足：

- 不依赖手改 JSON，也能完成基础配置
- `message-bridge` 只接受 `default` 单账号
- `onboarding` 对错误输入会阻断并重试
- legacy `accounts` 不会被当成成功结果
- `channels status --probe` 与 `doctor` 能输出可操作诊断
- 禁用、删除账号命令可用
- 自动化回归全部通过

## 6. 常用排查入口

### 查看 `ai-gateway` 日志

```bash
tail -f /Users/zy/Code/opencode/opencode-CUI/logs/local-stack/ai-gateway.log
```

### 查看当前 channel 状态

```bash
openclaw --dev channels status --probe --json
```

### 查看全局诊断

```bash
openclaw --dev doctor
```

### 查看当前配置中的 `message-bridge`

```bash
cat ~/.openclaw-dev/openclaw.json
```

如果你在默认 profile 下验证，把 `~/.openclaw-dev` 替换成 `~/.openclaw`。
