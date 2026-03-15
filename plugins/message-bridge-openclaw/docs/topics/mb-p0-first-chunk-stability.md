# P0 首块超时/首块延迟需求专题

**ID:** FR-MB-OPENCLAW-P0-FIRST-CHUNK  
**Version:** v1.0  
**Date:** 2026-03-15  
**Status:** 冻结  
**Owner:** message-bridge maintainers  
**关联方案:** `./mb-p0-first-chunk-stability-solution.md`  
**关联实施计划:** `../implementation-plan.md`

## 1. 背景与问题定义

当前 `message-bridge-openclaw` 在新会话场景中仍存在首块稳定性风险：

1. 首块前超时：尚未收到首块文本即 timeout，用户感知为“无回复”。
2. 首块延迟波动：能回复但首块延迟不稳定，体验抖动明显。
3. 路径口径不一致：`runtime_reply` 与 `subagent_fallback` 的 timeout 与诊断口径未完全统一。

## 2. 范围与非范围

### 2.1 范围内

1. 仅收敛首块超时/延迟稳定性问题。
2. 统一两条执行路径的 timeout 与诊断口径。
3. 固化上线门禁：成功率、超时占比、失败分型、路径一致性。

### 2.2 范围外

1. 不引入 token 级 streaming。
2. 不扩展 deferred actions（`permission_reply` / `question_reply`）实现。
3. 不改造 `ai-gateway` 或 skill relay 协议字段。

## 3. 指标与阈值

统计建议：每批次至少 30 个新会话样本。

统计口径（固定）：

1. 以“请求（request）”为统计单位，不以会话去重。
2. 重试请求必须单独标记，但纳入总样本。
3. 统计时 `runtime_reply` / `subagent_fallback` 必须分路径出报表并给出总体汇总。

核心指标：

1. 首块成功率（First Chunk Success Rate）
2. 首块延迟（First Chunk Latency，关注 P50/P95）
3. 首块前 timeout 占比（Before-First-Chunk Timeout Ratio）
4. 路径一致性（`runtime_reply` / `subagent_fallback` 诊断字段一致率）

指标公式（固定）：

1. 首块成功率 = `收到首块文本的请求数 / 总请求数 * 100%`
2. 首块前 timeout 占比 = `failureStage=before_first_chunk 且 errorCategory=timeout 的请求数 / 总请求数 * 100%`
3. 路径一致率 = `满足统一诊断字段集合（executionPath/failureStage/errorCategory/firstChunkLatencyMs/retryAttempt）的请求数 / 总请求数 * 100%`
4. 总请求数固定定义为 `bridge.chat.started` 事件计数。

P0 通过阈值：

1. 首块成功率 >= 99%
2. 首块前 timeout 占比 <= 1%
3. 失败样本分型覆盖率 100%（必须能区分 `before_first_chunk` 与 `after_first_chunk`）
4. 两路径关键诊断字段一致率 100%

发布门禁窗口（固定）：

1. 发布前在目标环境执行同模型、同网关配置的连续 30 个新会话样本门禁检查。

## 4. 验收口径

最小场景：

1. 新会话首块成功率统计
2. 首块前 timeout 分类
3. 首块后失败分类
4. `runtime_reply` / `subagent_fallback` 一致性验证

每个场景必须明确：

1. 指标定义
2. 数据来源
3. 通过阈值
4. 失败处理动作

最小场景 -> 数据来源（固定）：

| 场景 | 指标定义 | 数据来源（日志事件与字段） | 通过阈值 | 失败处理动作 |
| --- | --- | --- | --- | --- |
| 新会话首块成功率统计 | 收到首块文本请求数 / 总请求数（分母固定为 `bridge.chat.started` 计数） | `bridge.chat.started`、`bridge.chat.first_chunk`、`bridge.chat.completed`；字段：`executionPath`、`firstChunkLatencyMs` | 首块成功率 >= 99% | 阻塞上线，定位失败样本并执行阶段一稳定性回归 |
| 首块前 timeout 分类 | `before_first_chunk + timeout` 请求占比（分母固定为 `bridge.chat.started` 计数） | `bridge.chat.failed`；字段：`failureStage`、`errorCategory`、`executionPath` | 占比 <= 1% | 阻塞上线，优先排查 timeout 口径与路由链路 |
| 首块后失败分类 | `after_first_chunk` 失败请求分类正确率 | `bridge.chat.failed`；字段：`failureStage`、`errorCategory`、`executionPath` | 分类覆盖率 100% | 阻塞上线，排查重复输出/中断链路问题 |
| 两路径一致性验证 | 统一字段集合完整率（`executionPath/failureStage/errorCategory/firstChunkLatencyMs/retryAttempt`） | `bridge.chat.started`、`bridge.chat.first_chunk`、`bridge.chat.failed`、`bridge.chat.completed`；字段：`executionPath`、`failureStage`、`errorCategory`、`firstChunkLatencyMs`、`retryAttempt` | 路径一致率 = 100% | 阻塞上线，补齐缺失字段或统一语义 |

## 5. 风险与依赖

风险：

1. 模型首块抖动可能导致 timeout 误判。
2. reply dispatcher 与模型慢的现象可能混淆，增加排障成本。
3. 样本量不足会导致指标结论不稳定。

依赖：

1. 统一日志字段与错误分类口径。
2. 固定回归脚本/手册执行顺序，保证统计可复现。

## 6. 追溯信息

1. 主入口：`../implementation-plan.md`
2. 方案专题：`./mb-p0-first-chunk-stability-solution.md`
3. 文档入口：`../../README.md`
