# Message Bridge OpenClaw 插件实施计划

**Version:** 0.7
**Date:** 2026-03-15  
**Status:** 阶段二最小交付已完成；阶段一稳定性 P0 实现与测试已落地，当前重心是阶段一门禁验证与阶段三体验优化
**Owner:** message-bridge maintainers  
**Scope:** OpenClaw `--dev` 环境下的 `message-bridge` 插件

## P0 首块稳定性专题索引（2026-03-15）

需求与方案专题：

1. `./topics/mb-p0-first-chunk-stability.md`
2. `./topics/mb-p0-first-chunk-stability-solution.md`

需求追溯标识：

- `FR-MB-OPENCLAW-P0-FIRST-CHUNK`

本节只挂载执行任务与验收门禁，不重复专题正文。

### P0-FC-01 实现任务包
- [x] 统一 `runtime_reply` 与 `subagent_fallback` 的 timeout 口径（同一 `runTimeoutMs`）。
- [x] 固化失败阶段分型：`before_first_chunk` / `after_first_chunk`。
- [x] 固化错误分类字段：至少包含 `timeout` / `runtime_error`。
- [x] 增加最小重试边界：仅首块前 timeout 允许单次重试。
- [x] 重试必须复用同一业务请求标识（idempotency key），不得改变会话键与路由键。
- [x] 增加 `retryAttempt` 观测字段输出（首发 `0`、重试 `1`），并纳入门禁统计口径。

### P0-FC-02 测试任务包
- [x] 新会话首块成功率统计场景。
- [x] 首块前 timeout 注入与分类验证。
- [x] 首块后失败注入与分类验证。
- [x] `runtime_reply` / `subagent_fallback` 诊断字段一致性验证。

### P0-FC-03 验收门禁
- [ ] 首块成功率达标（阈值以需求专题定义为准）。
- [ ] 首块前 timeout 占比达标（阈值以需求专题定义为准）。
- [ ] 失败样本阶段分型覆盖率 100%。
- [ ] 两路径关键诊断字段一致率 100%。
- [ ] 发布前在目标环境执行同模型、同网关配置的连续 30 个新会话样本门禁检查。

### P0-FC-04 上线阻塞条件
出现任一条件即阻塞上线：

1. 首块成功率未达标。
2. 首块前 timeout 占比超阈值。
3. 无法稳定区分 `before_first_chunk` 与 `after_first_chunk`。
4. `runtime_reply` / `subagent_fallback` 输出口径不一致。

## P0 阶段四 permission_reply 专题索引（2026-03-15）

需求与方案专题：

1. `./topics/mb-p0-permission-bridge-requirements.md`
2. `./topics/mb-p0-permission-bridge-solution.md`

需求追溯标识：

- `FR-MB-OPENCLAW-P0-PERMISSION-BRIDGE`

本节仅挂载阶段四 `permission_reply` 的需求口径与任务入口，不展开实现细节。

### P0-PR-01 目标能力
- `invoke.permission_reply` 保持 ai-gateway 现有入参形态：`toolSessionId` / `permissionId` / `response`。
- `response` 与 OpenClaw `exec approvals` 决策语义一致映射：
  - `once -> allow-once`
  - `always -> allow-always`
  - `reject -> deny`
- 权限状态上行通过 `tool_event` 投影（`permission.asked` / `permission.updated`），不新增传输消息类型。
- `question_reply` 本阶段继续 fail-closed，不纳入本任务包交付。

### P0-PR-02 约束与错误口径
- `permissionId` 采用 opaque passthrough，直接作为 OpenClaw `approvalId` 使用；不引入任何 ID 映射缓存。
- 插件只维护最小状态：`toolSessionId`、`permissionId`、`status(pending/resolved/expired)`、`resolvedAt?`、`expiresAt?`，用于幂等/防重放/会话隔离。
- 不存在/过期/已决议/解析失败均返回稳定 `tool_error`；端到端最小契约遵循 `error + welinkSessionId?/toolSessionId?`。
- `errorCode/action` 不再作为公共 wire 字段；诊断信息统一进入 `error` 文本与插件日志。
- 会话错配场景统一返回 `permission_session_mismatch`，不得产生跨会话副作用。
- 重复提交同一 `permissionId` 必须幂等，不产生二次副作用，不污染 session 状态。

### P0-PR-03 最小验收口径
- 固定环境门禁窗口：同一目标环境、固定模型与固定网关配置下，连续至少 30 个有效 `permission_reply` 样本。
- 有效样本下 `permission_reply` 决策提交成功率 `100%`（上述门禁窗口样本集）。
- 三种 `response` 到目标决策映射一致率 `100%`。
- 非法/过期/重复输入均结构化 `tool_error` 收敛，且状态机无回归。
- 强制覆盖 4 个用例：同会话授权成功、重复提交幂等、过期提交、跨会话错配（结构化 `tool_error`）。
- 30 样本门禁中至少包含 1 个错误场景样本（重复/过期/错配任一）。
- 观测字段至少包含：`toolSessionId`、`permissionId`、`decision`、`resolveResult`、`reason`、`latencyMs`。

## P0 参考专题索引：feishu-openclaw 能力需求清单（2026-03-16）

参考专题：

1. `./topics/mb-p0-feishu-openclaw-reference-requirements.md`

需求追溯标识：

- `FR-MB-OPENCLAW-P0-FEISHU-REFERENCE`

本节作为 `message-bridge-openclaw` 的能力参考基线，明确：

- 需求描述
- 优先级（`P0/P1/P2`）
- OpenClaw 插件接口一对一主依赖映射

## TL;DR

当前插件已经完成一个可运行的 OpenClaw `message-bridge` V1 适配器，并且阶段二的最小产品化交付已经落地。

**最新更新（v0.6）：**

- 新增 duplicate_connection 抑制逻辑，避免健康运行时误报重复连接问题
- 新增诊断辅助函数和 skill-relay 实时检查脚本
- 验证审计文档已补充

已完成：

- OpenClaw 插件可被加载、启动并接入 `ai-gateway`
- `register` / `heartbeat` / `status_query` / `create_session` / `chat` 闭环已打通
- block 级文本事件投影已实现
- 阶段二最小交付已完成
  - 正式 `configSchema`
  - 单账号配置收口
  - `setup` / 轻量 `onboarding`
  - `probe/status/issues`
  - `setAccountEnabled` / `deleteAccount`
  - bundle 交付目录
  - 阶段一、阶段二验证手册
- **阶段一诊断增强**
  - duplicate_connection 抑制逻辑（避免健康运行时误报）
  - `validate:skill-relay` 脚本命令
  - `VALIDATION-AUDIT.zh-CN.md` 验证审计文档

当前主要阻塞：

- 新会话首块延迟与 timeout 风险仍需继续收敛
- block streaming 仍然不是 token 级体验
- `permission_reply` / `question_reply` 业务能力仍未实现（但 fail-closed 结构已规范化）
- `pairing/security/messaging/directory/outbound` 仍未评估为本插件职责

本次刷新重点（v0.6）：

- 增强阶段一诊断能力，新增 duplicate_connection 抑制逻辑
- 新增 `validate:skill-relay` 脚本命令和实时检查脚本
- 新增 `docs/VALIDATION-AUDIT.zh-CN.md` 验证审计文档
- 补充 duplicate_connection 场景测试用例（healthy/unhealthy 状态）

## 当前进展

### 1. 插件基础能力

已完成：

- 独立插件包目录：`plugins/message-bridge-openclaw`
- 插件入口、channel 注册、运行时桥接逻辑
- 基于 OpenClaw channel runtime 与 `ai-gateway` WebSocket 协议联通
- OpenClaw dev 环境安装、启动与联调验证

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

### 3. 阶段二最小交付状态

已完成：

- 配置定义
  - `channels.message-bridge` 已提供正式 `configSchema`
  - `gateway.url`、`auth.ak`、`auth.sk` 已作为必填配置收口
- 单账号策略
  - 当前只支持 `default` 账号
  - 非 `default` 的 `accountId` 会直接报错
  - legacy `channels.message-bridge.accounts` 会被识别为废弃配置
- 配置入口
  - 已接入 `openclaw channels add`
  - 已提供轻量 `onboarding`
  - 无效输入会阻断并重试
  - legacy `accounts` 不会再被视为配置成功
  - `setup/onboarding` 仅写入 `name`、`gateway.url`、`auth.ak`、`auth.sk`
  - `name` 只作为账号展示名，不参与注册协议
  - `toolType=openclaw`、`deviceName`、`toolVersion`、`macAddress` 已收敛为运行时派生元数据
- 账号生命周期
  - 已支持 `setAccountEnabled`
  - 已支持 `deleteAccount`
  - 已接入 `openclaw channels remove`
- 状态、探活与诊断
  - 已支持 `probeAccount`
  - 已支持 `buildChannelSummary`
  - 已支持 `buildAccountSnapshot`
  - 已支持 `collectStatusIssues`
  - `openclaw channels status --probe` 与 `openclaw doctor` 可以消费这些结果
  - **v0.6 新增诊断增强**：
    - duplicate_connection 抑制逻辑（避免健康运行时误报）
    - `getHeartbeatThresholdMs` 辅助函数
    - `isRuntimeHealthyForDuplicateConnection` 辅助函数
    - `getProbeReason` 函数统一提取探活原因
    - duplicate_connection 场景测试用例（healthy/unhealthy）

关键文件：

- `src/config.ts`
- `src/channel.ts`
- `src/status.ts`
- `src/onboarding.ts`
- `tests/config-status.test.mjs`

### 4. 联调与验证状态

已完成验证：

- OpenClaw `--dev` 环境能加载插件
- `ai-gateway` 能识别 agent online
- `status_query -> status_response` 正常
- `create_session -> session_created` 正常
- `chat -> tool_event -> tool_done` 闭环正常
- `channels add` / `channels status --probe` / `doctor` 已能覆盖阶段二能力
- 阶段一、阶段二验证手册已补齐
- **v0.6 新增**：duplicate_connection 场景测试用例（healthy/unhealthy 状态）

相关文档：

- `README.md`
- `docs/USAGE.zh-CN.md`
- `docs/VALIDATION.zh-CN.md`
- `docs/VALIDATION-AUDIT.zh-CN.md`（v0.6 新增验证审计文档）
- `docs/protocol-sequence.md`

### 5. 交付与安装形态

已完成：

- `dist/` 目录安装方式
- bundle 目录安装方式
- bundle 构建脚本自动生成最小安装产物
- 运行时版本冲突排查说明
- **v0.6 新增**：
  - `validate:skill-relay` 脚本命令
  - `scripts/skill-relay-live-check.mjs` skill relay 实时检查脚本

相关文件：

- `package.json`
- `scripts/build-bundle.mjs`
- `scripts/skill-relay-live-check.mjs`（v0.6 新增）
- `bundle/index.js`
- `bundle/package.json`
- `bundle/openclaw.plugin.json`

### 6. 仍然缺少的通用能力

当前仍缺少或未确认的能力：

- 稳定性与体验
  - 新会话首块超时问题尚未彻底关闭
  - block streaming 首块延迟仍偏大
  - 缺少面向不同模型/超时组合的稳定性基线
- 协议补齐
  - `permission_reply`
  - `question_reply`
- 安全与接入治理
  - `pairing`
  - `security` 配置与告警
  - sender 级访问控制
- 一等公民 channel 集成能力
  - `messaging` 目标规范化
  - `directory` 查询
  - `outbound` 主动发送

### 7. 当前不建议直接照搬的飞书特性

继续保持 out of scope：

- WebSocket / Webhook 双接入模式
- mention 过滤、群聊 gating、事件去重
- 富媒体上传下载、卡片渲染、线程回复
- reactions / polls / edit 等平台语义

原因：

- 这些能力属于“直连 IM 平台”的 channel 形态
- `message-bridge-openclaw` 当前职责仍是桥接 `ai-gateway` 协议
- 只有当 `ai-gateway` 协议本身扩展出这些语义时，桥接层才应补映射

## 当前已知问题

### P1

- 新会话在部分模型/环境组合下仍可能在首块前超时
- 当前流式体验仍依赖 OpenClaw 首块产出速度
- 即使协议链路已具备 block 级流式能力，用户侧也未必稳定感知为“实时”

### P2

- `permission_reply` 未实现
- `question_reply` 未实现
- `pairing` / `security` / `messaging` / `directory` / `outbound` 尚未评估为当前阶段目标
- 仍然主要以 OpenClaw `--dev` 环境为主，尚无正式发布安装流

## 阶段状态总览

| 阶段 | 目标 | 当前状态 | 主要输出 |
| --- | --- | --- | --- |
| 阶段一 | 先让新会话稳定回复 | 进行中（v0.6 诊断增强已落地） | timeout 原因收敛、reply timeout 调整、duplicate_connection 抑制、skill-relay 检查脚本、最小回复回归验证 |
| 阶段二 | 补齐插件产品化能力 | 已完成（最小交付） | `configSchema`、单账号 `setup/onboarding`、`probe/status/issues`、账号启停/删除 |
| 阶段三 | 优化 block 级流式体验 | 未开始 | 首块延迟分析、chunk 策略优化、用户侧可感知流式 |
| 阶段四 | 补齐 deferred actions | 进行中（fail-closed 规范化已完成） | `permission_reply`、`question_reply` 协议实现或明确后置 |
| 阶段五 | 评估完整 channel 能力边界 | 未开始 | `pairing/security/messaging/directory/outbound` 的职责判断 |
| 阶段六 | 交付整理 | 进行中 | `dist/`、bundle 目录、验证审计文档、skill-relay 检查脚本、安装说明、验证手册 |

## 后续计划

### 阶段一：稳定性优先

目标：

- 先让新会话稳定有回复

任务：

- 核查 OpenClaw 当前默认模型在 dev 环境中的超时行为
- 继续收敛 reply dispatcher 与 fallback 两条路径的 timeout 配置
- 用干净新会话做最小回复验证
- 明确模型、超时、首块延迟之间的关系
- 把 `docs/VALIDATION.zh-CN.md` 纳入固定回归流程
- **v0.6 已完成**：
  - 实现 duplicate_connection 抑制逻辑，避免健康运行时误报
  - 新增 `getHeartbeatThresholdMs` / `isRuntimeHealthyForDuplicateConnection` / `getProbeReason` 辅助函数
  - 新增 `validate:skill-relay` 脚本命令
  - 新增 `scripts/skill-relay-live-check.mjs` skill relay 实时检查脚本
  - 新增 `docs/VALIDATION-AUDIT.zh-CN.md` 验证审计文档
  - 补充 duplicate_connection 场景测试用例

验收标准：

- 新建 session 发送 `hi` 能稳定得到回复
- 无需依赖历史长会话才能出结果
- timeout 发生时能明确分辨是模型慢、reply dispatcher 卡住，还是路由/链路问题

### 阶段二：插件产品化能力补齐

状态：

- 最小交付已完成

本阶段已交付：

- 正式 `configSchema`
- 单账号配置收口
- `setup` / 轻量 `onboarding`
- `probe/status/issues`
- `setAccountEnabled`
- `deleteAccount`
- 面向阶段一、阶段二的验证手册

本阶段结论：

- 当前插件已经从“需要手工照顾的桥接实验件”推进到“可配置、可诊断、可接入”的最小可维护形态
- 后续若继续扩阶段二，不应再回到多账号扩展，而应围绕单账号形态做体验打磨和宿主侧集成细化

### 阶段三：流式体验优化

目标：

- 让 block 级 streaming 具备可感知的用户体验

任务：

- 继续记录 `bridge.chat.started` / `first_chunk` / `completed`
- 比较不同模型的首块延迟
- 评估 OpenClaw reply dispatcher 是否存在更低延迟接入点
- 优化 `blockStreamingChunk` / `blockStreamingCoalesce`
- 明确哪些体验问题应在插件层处理，哪些必须依赖宿主 reply runtime

验收标准：

- 长回复场景下，用户能在合理时间看到第一段文本
- 后续块持续上行，而不是集中在末尾一次 flush
- 不同模型的首块延迟与 timeout 风险有明确基线记录

### 阶段四：协议能力补齐

目标：

- 从 V1 走向更完整协议支持

前置判断：

- 先确认 OpenClaw reply runtime 是否能稳定承接来自桥接层的 `permission_reply` / `question_reply`
- 若 host/core 暂无稳定入口，应把该阶段降级为“协议预留 + 明确后置”，避免在插件层硬做兼容 hack

任务：

- 按 `FR-MB-OPENCLAW-P0-PERMISSION-BRIDGE` 落地 `permission_reply`（OpenClaw `exec approvals` 映射）
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

- `pairing` / `security` 值得评估，但不应抢在阶段一、阶段三之前
- `messaging` / `directory` / `outbound` 只有在插件要参与 OpenClaw 通用发送链路时才需要实现
- 飞书类平台特性继续保持 out of scope，除非 `ai-gateway` 协议同步升级

### 阶段六：交付整理

目标：

- 形成更容易分发和回归的插件交付方式

任务：

- 继续维护 `dist/` 目录安装方式
- 继续维护 bundle 目录安装方式
- 维护 `README.md`、`docs/USAGE.zh-CN.md`、`docs/VALIDATION.zh-CN.md`
- 评估是否需要正式发布或安装渠道
- **v0.6 已完成**：
  - 新增 `validate:skill-relay` 脚本命令
  - 新增 `scripts/skill-relay-live-check.mjs` skill relay 实时检查脚本
  - 新增 `docs/VALIDATION-AUDIT.zh-CN.md` 验证审计文档

验收标准：

- 至少支持目录安装和 bundle 目录安装两种交付方式
- 新环境接入时，不需要依赖口头说明即可完成构建、安装、验证

## 当前建议

如果下一步继续推进，优先级建议如下：

1. 先关闭阶段一的新会话 timeout 与首块稳定性问题
2. 再进入阶段三，优化真实 block streaming 体验
3. 然后评估阶段四的宿主接口前置条件
4. 最后再判断阶段五的完整 channel 能力是否值得做

## 下一轮执行包建议

如果只做一轮中等规模迭代，建议范围控制在“阶段一 + 阶段三起步”，具体为：

1. 提交一：补稳定性基线
   - 记录不同模型的首块延迟、reply timeout、fallback timeout
2. 提交二：补链路诊断细化
   - 把 timeout 分类从“现象”收敛到“原因”
3. 提交三：补流式体验优化
   - 调整 chunk/coalesce 策略，验证用户侧首块可感知时间
4. 提交四：形成固定回归矩阵
   - 把 `docs/VALIDATION.zh-CN.md` 里的阶段一、阶段二步骤沉淀为持续执行清单

这样做的好处：

- 不需要等待协议升级
- 能先把“能配置、能诊断”继续推进到“能稳定使用”
- 能为后续是否补 `permission_reply` / `question_reply` 提供更可靠的基础链路

---

## 修改记录

### v0.6 (2026-03-14)

**阶段一诊断增强：**

- 新增 duplicate_connection 抑制逻辑，避免健康运行时误报重复连接问题
- 新增 `getHeartbeatThresholdMs` 辅助函数
- 新增 `isRuntimeHealthyForDuplicateConnection` 辅助函数
- 新增 `getProbeReason` 函数统一提取探活原因
- 新增 `validate:skill-relay` 脚本命令
- 新增 `scripts/skill-relay-live-check.mjs` skill relay 实时检查脚本
- 新增 `docs/VALIDATION-AUDIT.zh-CN.md` 验证审计文档
- 补充 duplicate_connection 场景测试用例（healthy/unhealthy 两种情况）

**关联提交：** `85b4920`
