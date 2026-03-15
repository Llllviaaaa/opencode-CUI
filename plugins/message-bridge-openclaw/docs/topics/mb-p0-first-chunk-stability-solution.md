# P0 首块超时/首块延迟方案专题

**ID:** FR-MB-OPENCLAW-P0-FIRST-CHUNK-SOLUTION  
**Version:** v1.0  
**Date:** 2026-03-15  
**Status:** 方案冻结  
**Owner:** message-bridge maintainers  
**关联需求:** `./mb-p0-first-chunk-stability.md`  
**关联实施计划:** `../implementation-plan.md`

## 1. 设计目标

1. 关闭“首块前 timeout 导致无回复”的 P0 稳定性风险。
2. 收敛首块延迟抖动并提升可观测性。
3. 在不扩展协议边界的前提下，实现可回滚、可验证的最小方案。

## 2. 方案路径

### 2.1 timeout 口径统一

1. `runtime_reply` 与 `subagent_fallback` 统一使用同一 `runTimeoutMs`。
2. 两路径 timeout 判断语义一致，错误映射一致。
3. 不改 transport message 结构，仅统一执行口径与诊断口径。

### 2.2 失败分型统一

固定失败阶段：

1. `before_first_chunk`
2. `after_first_chunk`

固定错误分类最小集：

1. `timeout`
2. `runtime_error`

### 2.3 最小重试边界

1. 仅 `before_first_chunk + timeout` 允许重试。
2. 单次请求最多重试 1 次。
3. `after_first_chunk` 禁止重试，避免重复输出或语义污染。
4. 重试必须复用同一业务请求标识（idempotency key），不得改变会话键或路由键。
5. 重试必须记录 `retryAttempt` 观测字段（首发为 `0`，重试为 `1`）。
6. 统计时首发与重试都计入总样本，但必须可区分。

## 3. 观测字段

两路径必须统一输出以下字段：

1. `executionPath`
2. `configuredTimeoutMs`
3. `failureStage`
4. `errorCategory`
5. `firstChunkLatencyMs`
6. `totalLatencyMs`
7. `chunkCount`
8. `retryAttempt`

要求：

1. 字段语义一致。
2. 任意失败样本可直接定位为首块前或首块后失败。
3. 任意样本可识别首发或重试请求，支持重试归因分析。

## 4. 回滚与降级策略

1. 若重试策略导致整体延迟显著上升，先关闭重试，保留分型与观测字段。
2. 若统一口径引入误判，回滚到上一稳定版本并保留日志用于归因。
3. 回滚不改变协议边界，仅回退本地 timeout/诊断策略。

## 5. 实施映射

实现任务：

1. 统一两路径 timeout 参数和判断逻辑。
2. 固化 `before_first_chunk` / `after_first_chunk` 分型。
3. 固化 `timeout` / `runtime_error` 分类。
4. 增加首块前 timeout 单次重试控制。

测试与门禁：

1. 新会话首块成功率统计达标。
2. 首块前 timeout 分类正确。
3. 首块后失败分类正确。
4. 两路径关键字段一致率达标。
