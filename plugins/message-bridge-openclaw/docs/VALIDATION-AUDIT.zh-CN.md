# Message Bridge 验证与覆盖审计

更新时间：2026-03-14

本文档记录本轮基于 [VALIDATION.zh-CN.md](./VALIDATION.zh-CN.md) 执行的实际验证结果，以及“手册场景 -> 现有测试”的覆盖映射。

## 1. 执行基线

- 仓库：`/Users/zy/.codex/worktrees/3eda/opencode-CUI`
- OpenClaw profile：`~/.openclaw-dev`
- OpenClaw gateway：`openclaw --dev gateway run --allow-unconfigured --verbose`
- 当前 `message-bridge` dev 扩展已重新安装为当前 worktree bundle
- `gateway.mode` 已显式设为 `local`

本轮还做了两项环境校准：

- 停掉了占用 `127.0.0.1:19001` 的旧 `openclaw-gateway` 进程，并从当前 worktree 重启
- 通过 `openclaw --dev channels add --channel message-bridge ...` 把 `AK/SK` 修正为 `test-ak-openclaw-001 / test-sk-openclaw-001`

## 2. 能力验证矩阵

| 场景 | 当前状态 | 证据 | 备注 |
| --- | --- | --- | --- |
| 阶段一自动化：`downstream-normalization + bridge-chat` | 通过 | `node --test tests/downstream-normalization.test.mjs tests/bridge-chat.test.mjs`，`19/19` 通过 | 已补齐 `status_query/status_response`、`create_session`、`close_session`、`abort_session` |
| 阶段二自动化：`config-status + plugin-load + session-registry` | 通过 | `node --test tests/config-status.test.mjs tests/plugin-load.test.mjs tests/session-registry.test.mjs`，`11/11` 通过 | 覆盖 `configSchema/setup/onboarding/probe/status/issues` |
| `openclaw --dev channels add --channel message-bridge ...` | 通过 | CLI 输出 `Added Message Bridge account "default".` | 已写入 `channels.message-bridge` 顶层单账号配置 |
| `openclaw --dev channels remove --channel message-bridge` | 通过 | CLI 输出 `Disabled Message Bridge account "default".` | 配置中的 `enabled` 变为 `false` |
| `openclaw --dev channels remove --channel message-bridge --delete` | 通过 | CLI 输出 `Deleted Message Bridge account "default".` | 随后已重新 `add` 恢复环境 |
| `openclaw --dev channels status --probe --json` 可用性 | 通过 | 命令已返回 JSON，`connected=true`、`lastReadyAt`/`lastHeartbeatAt` 有值 | 说明 OpenClaw 本地网关和 live bridge 已正常运行 |
| `openclaw --dev channels status --probe --json` 的 probe 结果 | 阻塞 | `probe.state="rejected"`，`reason="duplicate_connection"` | 当前 runtime 已在线，额外 probe 被 ai-gateway 以重复连接拒绝，不会得到 `ready` |
| `openclaw --dev doctor` 可用性 | 通过 | `doctor` 已成功返回并输出 channel warnings | 不再卡在 `gateway.mode` 未配置或 19001 不可达 |
| `register / heartbeat` | 通过 | `channels status --probe --json` 显示 `connected=true`、`lastReadyAt`/`lastHeartbeatAt` 已更新；`ai-gateway.log` 有 heartbeat | 运行态已连通 |
| `status_query` | 通过 | `ai-gateway.log` 记录 `Received from Redis ... type=status_query` 和 `Recorded status_response ... opencodeOnline=true` | 手册 live 步骤可复现 |
| `create_session` | 部分通过 | `ai-gateway.log` 记录 `type=session_created`，并带 `welinkSessionId=welink-stage1-session-001`、`toolSessionId=session-stage1-001` | 本地 agent 已处理；上游 skill relay 被 `source_not_allowed` 拦截 |
| `chat` | 部分通过 | `ai-gateway.log` 记录 `tool_event`/`tool_done`；本地 session 文件产出精确回复 | 本地 OpenClaw 执行成功；上游 skill relay 被 `source_not_allowed` 拦截 |
| `close_session` | 部分通过 | `ai-gateway.log` 记录 `tool_done`；`sessions.json` 中 `message-bridge:default:session-stage1-001` 已消失 | 本地会话清理成功；上游 skill relay 被 `source_not_allowed` 拦截 |
| unsupported `permission_reply` fail-closed | 部分通过 | `ai-gateway.log` 记录 `type=tool_error` | 本地 fail-closed 生效；上游 skill relay 被 `source_not_allowed` 拦截 |
| 交互式 `onboarding` 重试/legacy skip | 仅自动化覆盖 | `tests/config-status.test.mjs` 通过 | 本轮未重复做完整手工 wizard 录屏式验证 |

### 2.1 本地 session 证据

`chat` 的本地执行产物已落到：

- `~/.openclaw-dev/agents/main/sessions/5f2470cf-a9c0-479c-ba8a-903361677155.jsonl`

该文件包含：

- 用户消息 `Reply with exactly: hello from openclaw bridge verification`
- assistant 消息 `hello from openclaw bridge verification`

说明 bridge 已把 `ai-gateway` 下行 `chat` 成功转成 OpenClaw 本地会话执行。

## 3. 覆盖映射矩阵

| 手册场景 | 现有覆盖 | 测试文件 |
| --- | --- | --- |
| 下行协议归一化 | 自动化已覆盖 | `tests/downstream-normalization.test.mjs` |
| `status_query -> status_response` | 自动化已覆盖，本轮补齐 | `tests/bridge-chat.test.mjs` |
| `create_session -> session_created` | 自动化已覆盖，本轮补齐 | `tests/bridge-chat.test.mjs` |
| `chat -> tool_event -> tool_done` | 自动化已覆盖 | `tests/bridge-chat.test.mjs` |
| runtime reply / fallback / timeout / tool lifecycle / runTimeoutMs | 自动化已覆盖 | `tests/bridge-chat.test.mjs` |
| `close_session` | 自动化已覆盖，本轮补齐 | `tests/bridge-chat.test.mjs` |
| `abort_session` 成功 / `unknown_tool_session` | 自动化已覆盖，本轮补齐 | `tests/bridge-chat.test.mjs` |
| ready / inbound / outbound / heartbeat 运行态时间戳 | 自动化已覆盖 | `tests/bridge-chat.test.mjs` |
| unsupported `permission_reply` fail-closed | 自动化已覆盖 | `tests/bridge-chat.test.mjs` |
| `configSchema` / 单账号 / `setup` / `onboarding` / `probe/status/issues` / 启停删除 | 自动化已覆盖 | `tests/config-status.test.mjs` |
| 插件注册与 runtime store | 自动化已覆盖 | `tests/plugin-load.test.mjs` |
| session registry | 自动化已覆盖 | `tests/session-registry.test.mjs` |
| `channels add` 成功写入单账号配置 | 已新增测试，当前环境未执行 | `plugins/openclaw/src/commands/channels.message-bridge.test.ts` |
| `channels add` 拒绝非 `default` 账号 | 已新增测试，当前环境未执行 | `plugins/openclaw/src/commands/channels.message-bridge.test.ts` |
| `channels remove` disable / delete | 已新增测试，当前环境未执行 | `plugins/openclaw/src/commands/channels.message-bridge.test.ts` |
| `channels status` 输出 summary / issue | 已新增测试，当前环境未执行 | `plugins/openclaw/src/commands/channels.message-bridge.test.ts` |

## 4. 当前缺口

### 4.1 仍缺真正跨进程 E2E

当前仓库仍然没有“真实 `ai-gateway + OpenClaw + skill relay` 全链路”的自动化 E2E。

现有自动化主要是：

- 插件级组件测试
- 宿主命令级单测

这意味着像 `source_not_allowed` 这类上游 relay 策略问题，只能在 live 联调时暴露。

### 4.2 宿主命令测试已补，但本轮未在本机跑通

本轮新增了 `plugins/openclaw/src/commands/channels.message-bridge.test.ts`，但当前 worktree 缺少 `plugins/openclaw` 的本地 `vitest` 依赖环境，无法直接在本机完成执行。

结论：

- 测试文件已补齐
- 插件侧 Node 测试已实跑通过
- 宿主命令级测试仍需在已安装 `plugins/openclaw` 依赖的环境里补跑一次

### 4.3 live `probe` 与“已在线 runtime”冲突

当前 `message-bridge` 运行态已经在线时，`probeAccount` 会再次发起短连接，被 ai-gateway 以 `duplicate_connection` 拒绝。

这导致：

- `channels status --probe` 不会得到 `probe.state=ready`
- `doctor` 会输出“网关拒绝注册：duplicate_connection”

但与此同时：

- `connected=true`
- `lastReadyAt` 有值
- 心跳和收发时间戳在更新

因此这更像“探活策略和上游单连接约束的碰撞”，不是基础链路不可用。

### 4.4 live 上游回写被 `source_not_allowed` 拦截

本轮 `create_session`、`chat`、`close_session`、unsupported fail-closed 都能在本地 agent 侧执行，但 ai-gateway 向上游 skill relay 回写时被拒绝：

- `errorCode=source_not_allowed`

这意味着：

- 本地 bridge 功能大体正常
- 但“从 ai-gateway 回到上游 skill”这条链路的环境授权未打通

## 5. 结论

可以确认的结论：

- 阶段一/阶段二的插件级自动化能力目前是通过的
- `message-bridge` 在当前 worktree 的 dev profile 中已经能真实连上 ai-gateway，并处理 `status_query`、`create_session`、`chat`、`close_session` 和 unsupported fail-closed
- `setup`、`disable`、`delete` 的 CLI 手工动作可以执行

当前不能直接宣称“全手册全部通过”的原因有两个：

- live `probe` 会被 `duplicate_connection` 干扰
- ai-gateway 的上游 skill relay 当前被 `source_not_allowed` 拦截

如果下一步要把这份审计收口成“全通过”，建议优先处理：

1. `probe` 在 runtime 已在线时的探活策略
2. ai-gateway 侧 `source_not_allowed` 的上游授权配置
3. 在完整依赖环境里补跑 `plugins/openclaw/src/commands/channels.message-bridge.test.ts`
