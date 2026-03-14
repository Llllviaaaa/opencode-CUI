# Message Bridge OpenClaw 插件实施计划

**Version:** 0.5
**Date:** 2026-03-14  
**Status:** 阶段二最小交付已完成，当前重心回到阶段一稳定性与阶段三体验优化
**Owner:** message-bridge maintainers  
**Scope:** OpenClaw `--dev` 环境下的 `message-bridge` 插件

## TL;DR

当前插件已经完成一个可运行的 OpenClaw `message-bridge` V1 适配器，并且阶段二的最小产品化交付已经落地。

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

当前主要阻塞：

- 新会话首块延迟与 timeout 风险仍需继续收敛
- block streaming 仍然不是 token 级体验
- `permission_reply` / `question_reply` 仍未实现
- `pairing/security/messaging/directory/outbound` 仍未评估为本插件职责

本次刷新重点：

- 把阶段二从“待设计”更新为“最小交付已完成”
- 把后续优先级重新调整为阶段一稳定性与阶段三体验优化
- 明确单账号策略是当前有意收口，而不是阶段二临时过渡

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

相关文档：

- `README.md`
- `docs/USAGE.zh-CN.md`
- `docs/VALIDATION.zh-CN.md`
- `docs/protocol-sequence.md`

### 5. 交付与安装形态

已完成：

- `dist/` 目录安装方式
- bundle 目录安装方式
- bundle 构建脚本自动生成最小安装产物
- 运行时版本冲突排查说明

相关文件：

- `package.json`
- `scripts/build-bundle.mjs`
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
| 阶段一 | 先让新会话稳定回复 | 进行中 | timeout 原因收敛、reply timeout 调整、最小回复回归验证 |
| 阶段二 | 补齐插件产品化能力 | 已完成（最小交付） | `configSchema`、单账号 `setup/onboarding`、`probe/status/issues`、账号启停/删除 |
| 阶段三 | 优化 block 级流式体验 | 未开始 | 首块延迟分析、chunk 策略优化、用户侧可感知流式 |
| 阶段四 | 补齐 deferred actions | 未开始 | `permission_reply`、`question_reply` 协议实现或明确后置 |
| 阶段五 | 评估完整 channel 能力边界 | 未开始 | `pairing/security/messaging/directory/outbound` 的职责判断 |
| 阶段六 | 交付整理 | 进行中 | `dist/`、bundle 目录、安装说明、验证手册、正式分发路径评估 |

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
