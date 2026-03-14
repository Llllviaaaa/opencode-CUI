# Message Bridge OpenClaw 插件实施计划

**Version:** 0.4  
**Date:** 2026-03-14  
**Status:** In Progress  
**Owner:** message-bridge maintainers  
**Scope:** OpenClaw `--dev` 环境下的 `message-bridge` 插件

## TL;DR

当前已经完成一个可运行的 OpenClaw `message-bridge` V1 插件，能够在 OpenClaw `--dev` 环境下接入 `ai-gateway`，并完成基础上下行闭环。

已完成：

- OpenClaw 插件可被加载和启动
- `register` / `heartbeat` 正常
- `create_session` / `status_query` 正常
- `chat` 可以打通到 OpenClaw，并回传 `tool_event` / `tool_done`
- 已支持 block 级文本事件投影
- 已补充 macOS / Windows / 单文件 JS bundle 的使用说明

当前主要阻塞：

- 实际用户可感知的流式体验仍不稳定
- 新会话下模型请求存在超时，导致无回复
- `permission_reply` / `question_reply` 仍未实现
- 仍缺少对标成熟 channel 插件的配置、探活、账号管理与安全治理能力

本次刷新重点：

- 把“能力缺口”进一步整理为“可执行阶段”
- 明确阶段二不再只是抽象目标，而是下一轮最值得推进的交付包
- 把“应做的通用能力”和“暂不照搬的飞书平台特性”继续分开

## 当前进展

### 1. 插件基础能力

已完成：

- 独立插件包目录：`plugins/message-bridge-openclaw`
- 插件入口、channel 注册、运行时桥接逻辑
- 基于 OpenClaw channel runtime 与 `ai-gateway` WebSocket 协议联通
- OpenClaw dev 环境安装与启动验证

关键文件：

- `src/index.ts`
- `src/channel.ts`
- `src/OpenClawGatewayBridge.ts`

### 2. 已实现协议范围

已支持动作：

- `register`
- `heartbeat`
- `chat`
- `create_session`
- `close_session`
- `abort_session`
- `status_query`

当前不支持：

- `permission_reply`
- `question_reply`

不支持动作采用 fail-closed：

- 返回 `tool_error(unsupported_in_openclaw_v1)`

### 3. 联调状态

已完成验证：

- OpenClaw `--dev` 环境能加载插件
- `ai-gateway` 能识别 agent online
- `status_query -> status_response` 正常
- `create_session` 正常
- `chat -> tool_event -> tool_done` 闭环正常
- `skill-server` 能消费上行消息并落库最终文本

已确认的现实问题：

- 插件具备 block 级 streaming 协议投影能力
- 但实际流式体验依赖 OpenClaw 首块文本产出
- 当前环境中首块延迟大，且新会话存在 `LLM request timed out`

### 4. Streaming 相关结论

已确认：

- `ai-gateway` 和 `skill-server` 可兼容 `message.updated`、`message.part.updated`、`message.part.delta`
- 插件已具备把 OpenClaw 回复映射成上述事件的能力
- 对长回复，OpenClaw 可以在首块之后连续产出多个 block

当前限制：

- 不是 token 级 streaming
- 首块延迟很高
- 新会话在当前模型下可能在首块前超时

### 5. 文档与交付能力

已完成：

- README 更新
- 中文使用指南补齐
- macOS 安装步骤
- Windows PowerShell 安装步骤
- 单文件 JS bundle 支持

相关文件：

- `README.md`
- `docs/USAGE.zh-CN.md`
- `package.json`

### 6. 参考飞书插件后的能力缺口

对照基线：

- 主要参考当前 OpenClaw 内置 `feishu` channel 插件
- 次要参考仓库内 `openclaw-feishu` 的工程组织方式
- 目标不是照搬飞书平台特性，而是识别“成熟 channel 插件”具备、而当前 `message-bridge-openclaw` 尚未具备的通用能力

当前已经具备的桥接核心：

- `register` / `heartbeat` / `chat` / `create_session` / `close_session` / `abort_session` / `status_query`
- assistant 文本 block 级 streaming 事件投影
- tool lifecycle 到 `message.part.updated(type=tool)` 的映射
- 基于 `toolSessionId`、`welinkSessionId`、`runId` 的 session 关联

当前仍缺少的通用能力：

- 配置产品化能力
  - 仍缺少面向 host 的正式 `configSchema`
  - 仍缺少 `setup` / `onboarding` 以接入 `openclaw channels add`
  - 当前更多依赖手工写配置和文档说明
- 账号管理完整性
  - 虽支持 `accounts` 配置合并
  - 但缺少 `setAccountEnabled` / `deleteAccount`
  - `defaultAccountId` 仍固定为 `default`
- 健康检查与状态汇总
  - 缺少 `probeAccount`
  - 缺少 `buildChannelSummary` / `buildAccountSnapshot`
  - 缺少 `collectStatusIssues`
  - 当前 `status_query` 更接近“进程在跑”，不是“链路健康”
- 安全与接入治理
  - 缺少 `pairing`
  - 缺少 `security` 配置和告警
  - 缺少类似 `allowFrom` / policy 的 sender 级控制
- 一等公民 channel 集成能力
  - 缺少 `messaging` 目标规范化
  - 缺少 `directory` 查询
  - 缺少 `outbound` 发送适配
  - 当前更像被动桥，不是完整 channel
- 协议补齐
  - `permission_reply` 未实现
  - `question_reply` 未实现
  - 仍停留在 V1 最小协议闭环
- 可观测性与体验稳定性
  - 新会话超时仍会导致无回复
  - 首块延迟偏大，block streaming 体验不稳定
  - 尚未把 reconnect、最近心跳、最近上下行等状态沉淀为统一诊断面

当前不建议直接照搬的飞书特性：

- WebSocket / Webhook 双接入模式
- mention 过滤、群聊 gating、事件去重
- 富媒体上传下载、卡片渲染、线程回复
- reactions / polls / edit 等平台语义

原因：

- 这些能力属于“直连 IM 平台”场景
- `message-bridge-openclaw` 当前职责是对接 `ai-gateway` 协议
- 只有当 `ai-gateway` 协议本身扩展出这些语义时，桥接层才应补对应映射

## 当前已知问题

### P0

- 新建会话下，`chat` 可能在首块前超时，无任何回复
- 当前默认模型 `openai-codex/gpt-5.3-codex` 在 OpenClaw 环境里的稳定性仍待确认

### P1

- 流式体验不稳定，首块耗时过长
- 当前日志虽能证明插件收到块后会持续上送，但用户侧未必感知为实时流式
- 缺少 `probeAccount` / `buildChannelSummary` / `collectStatusIssues`，诊断链路问题成本高
- 缺少正式 `configSchema`、`setup`、`onboarding`，仍以手工配置为主

### P2

- `permission_reply` 未实现
- `question_reply` 未实现
- 多账号管理仍不完整，缺少 `setAccountEnabled` / `deleteAccount`
- 尚未实现 `pairing` / `security` / `messaging` / `directory` / `outbound`
- 仍然以 OpenClaw `--dev` 环境为主，尚未整理正式安装形态

## 阶段状态总览

| 阶段 | 目标 | 当前状态 | 主要输出 |
| --- | --- | --- | --- |
| 阶段一 | 先让新会话稳定回复 | 进行中 | 超时原因收敛、reply timeout 调整、最小回复回归验证 |
| 阶段二 | 补齐插件产品化能力 | 未开始 | `configSchema`、`setup/onboarding`、状态探活、多账号管理 |
| 阶段三 | 优化 block 级流式体验 | 未开始 | 首块延迟分析、chunk 策略优化、用户侧可感知流式 |
| 阶段四 | 补齐 deferred actions | 未开始 | `permission_reply`、`question_reply` 协议实现或明确后置 |
| 阶段五 | 评估完整 channel 能力边界 | 未开始 | `pairing/security/messaging/directory/outbound` 的职责判断 |
| 阶段六 | 交付整理 | 部分完成 | `dist/`、`bundle/`、安装说明、正式分发路径 |

## 后续计划

### 阶段一：稳定性优先

目标：

- 先让新会话稳定有回复

任务：

- 核查 OpenClaw 当前默认模型在 dev 环境中的超时行为
- 调整 reply 链 timeout 配置
- 用干净新会话做最小回复验证
- 明确模型、超时、首块延迟之间的关系

验收标准：

- 新建 session 发送 `hi` 能稳定得到回复
- 无需依赖历史长会话才能出结果

### 阶段二：插件产品化能力补齐

目标：

- 让插件具备接近内置 channel 的配置、状态与账号管理能力

为什么先做这个阶段：

- 当前协议桥已经能跑起来，但 host 侧仍然把它视为“需要手工照顾的插件”
- 如果没有 `configSchema`、`setup`、状态探活和多账号管理，后续测试、运维、分发成本都会偏高
- 这部分能力不依赖 `ai-gateway` 扩协议，投入产出比高，适合尽快补齐

任务：

- 交付包 A：配置定义
  - 为 `channels.message-bridge` 补正式 `configSchema`
  - 明确哪些字段属于必填：`gateway.url`、`auth.ak`、`auth.sk`
  - 明确哪些字段属于可选：`runTimeoutMs`、`heartbeatIntervalMs`、`reconnect.*`、`agentIdPrefix`
  - 校验多账号配置结构与顶层默认值合并规则
- 交付包 B：配置入口
  - 设计 `setup`，接入 `openclaw channels add`
  - 评估是否需要轻量 `onboarding`
  - 让用户可以通过 CLI 完成最小可用配置，而不是手改 JSON
- 交付包 C：账号管理
  - 补 `setAccountEnabled`
  - 补 `deleteAccount`
  - 重新评估 `defaultAccountId` 是否应固定为 `default`
  - 明确默认账号与命名账号的合并、启停、删除行为
- 交付包 D：状态与探活
  - 增加 `probeAccount`
  - 增加 `buildChannelSummary`
  - 增加 `buildAccountSnapshot`
  - 明确 `status_query` 与 host 侧 `probe` 的职责边界
- 交付包 E：诊断问题聚合
  - 设计 `collectStatusIssues`
  - 首批覆盖配置缺失、鉴权失败、网关连接失败、心跳异常、最近上/下行超时
  - 让 `openclaw channels status` / `doctor` 输出可操作诊断

验收标准：

- 不依赖手写 JSON，也能完成基础配置
- `openclaw channels status` / `doctor` 能给出可操作的错误信息
- 多账号启停与默认账号语义清晰可用

建议拆分顺序：

1. 先做 `configSchema`
2. 再做 `setup`
3. 再补 `probe/status/issues`
4. 最后补账号启停与删除语义

阶段依赖：

- 不依赖 `ai-gateway` 扩展协议
- 依赖确认 OpenClaw 当前 `ChannelPlugin` 接口中各能力的接入点
- 依赖确认 `message-bridge-openclaw` 是否继续以“多账号可选”而不是“单账号固定”方向推进

### 阶段三：流式体验优化

目标：

- 让 block 级 streaming 具备可感知的用户体验

任务：

- 继续记录 `bridge.chat.started` / `first_chunk` / `completed`
- 比较不同模型的首块延迟
- 评估 OpenClaw reply dispatcher 是否存在更低延迟接入点
- 优化 `blockStreamingChunk` / `blockStreamingCoalesce`

验收标准：

- 长回复场景下，用户能在合理时间看到第一段文本
- 后续块持续上行，而不是集中在末尾一次 flush

### 阶段四：协议能力补齐

目标：

- 从 V1 走向更完整协议支持

前置判断：

- 先确认 OpenClaw reply runtime 是否能稳定承接来自桥接层的 `permission_reply` / `question_reply`
- 若 host/core 暂无稳定入口，应把该阶段降级为“协议预留 + 明确后置”，避免在插件层硬做兼容 hack

任务：

- 评估 `permission_reply`
- 评估 `question_reply`
- 明确是否需要 OpenClaw core 能力配合

验收标准：

- unsupported 动作的边界被清晰替换成正式实现，或正式确认后置

### 阶段五：一等公民 channel 能力评估

目标：

- 判断插件是否需要从“桥接适配器”进一步演进为“完整 channel 插件”

任务：

- 评估是否需要 `pairing` / `security`
- 评估是否需要 `messaging` 目标规范化
- 评估是否需要 `directory` 查询
- 评估是否需要 `outbound` 主动发送能力
- 明确这些能力应落在桥接层，还是继续由 `ai-gateway` 上游负责

验收标准：

- 明确哪些能力属于插件职责，哪些能力明确不做
- 若决定实现，形成清晰接口与协议边界

当前建议默认结论：

- `pairing` / `security` 值得评估，但不应抢在阶段二之前
- `messaging` / `directory` / `outbound` 只有在插件要参与 OpenClaw 通用发送链路时才需要实现
- 飞书类平台特性仍保持 out of scope，除非 `ai-gateway` 协议同步升级

### 阶段六：交付整理

目标：

- 形成更容易分发的插件交付方式

任务：

- 继续维护 `dist/` 目录安装方式
- 继续维护单文件 `bundle/index.js` 方式
- 视需要补充正式安装路径说明

验收标准：

- 至少支持目录安装和单文件集成两种交付方式

## 当前建议

如果下一步继续推进，优先级建议如下：

1. 先解决新会话超时无回复
2. 再补齐配置、探活、状态汇总和账号管理能力
3. 再优化真实流式体验
4. 然后补齐 deferred actions
5. 最后评估是否要做完整 channel 的安全、目录和 outbound 能力

## 下一轮执行包建议

如果只做一轮中等规模迭代，建议范围控制在阶段二，具体为：

1. 提交一：`configSchema + describeAccount/buildAccountSnapshot`
2. 提交二：`probeAccount + collectStatusIssues`
3. 提交三：`setup` 接入 `openclaw channels add`
4. 提交四：`setAccountEnabled/deleteAccount/defaultAccountId` 语义收口

这样做的好处：

- 不需要等待协议升级
- 能明显降低手工配置和排障成本
- 能把插件从“能跑”推进到“可维护、可诊断、可接入”
