# Message Bridge OpenClaw 插件实施计划

**Version:** 0.2  
**Date:** 2026-03-13  
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

## 当前已知问题

### P0

- 新建会话下，`chat` 可能在首块前超时，无任何回复
- 当前默认模型 `openai-codex/gpt-5.3-codex` 在 OpenClaw 环境里的稳定性仍待确认

### P1

- 流式体验不稳定，首块耗时过长
- 当前日志虽能证明插件收到块后会持续上送，但用户侧未必感知为实时流式

### P2

- `permission_reply` 未实现
- `question_reply` 未实现
- 仍然以 OpenClaw `--dev` 环境为主，尚未整理正式安装形态

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

### 阶段二：流式体验优化

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

### 阶段三：协议能力补齐

目标：

- 从 V1 走向更完整协议支持

任务：

- 评估 `permission_reply`
- 评估 `question_reply`
- 明确是否需要 OpenClaw core 能力配合

验收标准：

- unsupported 动作的边界被清晰替换成正式实现，或正式确认后置

### 阶段四：交付整理

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
2. 再优化真实流式体验
3. 最后补齐 deferred actions 和正式安装形态
