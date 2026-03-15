# P0 参考专题：feishu-openclaw 能力需求清单（面向 message-bridge-openclaw）

**需求标识**: `FR-MB-OPENCLAW-P0-FEISHU-REFERENCE`  
**状态**: draft（用于阶段四实现参考）  
**更新时间**: 2026-03-16

## 1. 目标

沉淀 `feishu-openclaw` 的能力与 OpenClaw 插件接口依赖关系，形成 `message-bridge-openclaw` 的实现参考基线。  
本文要求每条需求都具备：

1. 需求描述
2. 优先级（`P0/P1/P2`）
3. 一对一主依赖接口（唯一主接口）

## 2. 需求清单（需求 + 优先级 + 一对一接口映射）

| 需求ID | 需求描述 | 优先级 | 一对一主依赖接口 | 主依赖说明 |
| --- | --- | --- | --- | --- |
| `FR-01` | 注册并暴露 `message-bridge-openclaw` 渠道能力，完成插件接入。 | `P0` | `ClawdbotPluginApi.registerChannel(...)` | 所有后续能力均依赖 channel 注册。 |
| `FR-02` | 支持账号维度配置解析（默认账号、多账号、启停、删除）。 | `P0` | `ChannelPlugin.config` | 账号生命周期由 `config` 子接口统一承载。 |
| `FR-03` | 支持会话入口安全策略（`pairing/allowlist/open/disabled`）并可读取 `allowFrom`。 | `P0` | `ChannelPlugin.security.resolveDmPolicy` | DM 准入规则在插件模型中由 `security` 统一定义。 |
| `FR-04` | 支持配对通过后的用户通知（可选提示文本）。 | `P1` | `ChannelPlugin.pairing.notifyApproval` | 配对回执语义在插件层唯一入口是 `pairing.notifyApproval`。 |
| `FR-05` | 支持文本出站发送（含长文本分块）。 | `P0` | `ChannelPlugin.outbound.sendText` | 文本消息主执行接口。 |
| `FR-06` | 支持媒体出站（图片/文件）及失败降级策略。 | `P1` | `ChannelPlugin.outbound.sendMedia` | 媒体发送与文本发送解耦。 |
| `FR-07` | 支持运行态健康探测与账号状态快照。 | `P0` | `ChannelPlugin.status.probeAccount` | 可用性、告警、排障依赖统一探测入口。 |
| `FR-08` | 支持网关启动账号实例与消息接收 provider 启动。 | `P0` | `ChannelPlugin.gateway.startAccount` | 渠道 runtime 启动主入口。 |
| `FR-09` | 支持入站消息投递到 OpenClaw 回复分发主链路。 | `P0` | `runtime.channel.reply.dispatchReplyWithBufferedBlockDispatcher(...)` | 当前闭环回包路径依赖该运行时分发接口。 |
| `FR-10` | 支持配置热重载（`channels.xxx` 改动后生效）。 | `P1` | `ChannelPlugin.reload.configPrefixes` | 热重载行为由 `reload` 唯一声明。 |
| `FR-11` | 对外声明能力边界（如无 `threads/polls/reactions`）。 | `P1` | `ChannelPlugin.capabilities` | 避免上层误调用未实现能力。 |
| `FR-12` | 支持 onboarding 引导（首次配置、凭据、`allowFrom`）。 | `P2` | `ChannelPlugin.onboarding` | 非主链路能力，但影响可接入性。 |

## 3. 分批优先级建议（用于 message-bridge-openclaw）

1. 第一批 `P0`: `FR-01/02/03/05/07/08/09`（先打通可用闭环 + 可观测 + 安全准入）。
2. 第二批 `P1`: `FR-04/06/10/11`（补齐配对体验、媒体能力、重载与边界声明）。
3. 第三批 `P2`: `FR-12`（完善 onboarding 交付体验）。

## 4. 最小验收口径

1. 功能验收：`P0` 需求逐条具备自动化或联调样例。
2. 接口验收：每条需求只绑定一个主依赖接口，不在实现阶段二次选型。
3. 运行验收：具备启动、收发、探测、错误可见四类日志/状态字段。
4. 安全验收：`dmPolicy` 与 `allowFrom` 生效，跨会话/跨账号无越权。

## 5. 假设与边界

1. “一对一接口”指主依赖唯一，允许少量辅助函数存在。
2. `dispatchReplyWithBufferedBlockDispatcher(...)` 视为当前 OpenClaw 运行时稳定入口。
3. 本文仅覆盖 OpenClaw 插件接口参考，不覆盖飞书官方 OAuth 插件能力面。
