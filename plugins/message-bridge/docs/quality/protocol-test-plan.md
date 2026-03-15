# 协议驱动测试任务清单

**Version:** 1.0  
**Date:** 2026-03-13  
**Status:** Active  
**Owner:** message-bridge maintainers  
**Related:** `../design/interfaces/protocol-contract.md`, `./test-strategy.md`, `./traceability-matrix.md`

## 1. 目标

将 `message-bridge` 的高频回归验证收敛为一套以协议契约为中心的自动化测试，重点证明两件事：

- `message-bridge` 自身主流程正常
- `message-bridge` 与 `ai-gateway` 协议一致

非目标：

- 不验证 miniapp 页面渲染或弹窗展示
- 不以日志文案作为主断言
- 不把内部实现细节固化为对外契约

## 2. 编写规则

所有新增测试用例必须遵循以下规则：

1. 以协议定义为真源
- 用例名称、输入报文、期望报文、错误分支必须能映射到 `protocol-contract.md`

2. 以报文为输入输出
- 下行输入使用 gateway 报文
- 上行输出断言 bridge 发回的 transport 报文
- 上游事件使用 opencode 事件载荷

3. 严格断言契约字段
- 必须断言 `type`、必填字段、字段语义、非法值处理、时序约束
- 不依赖协议未承诺的额外字段

4. 日志只作辅证
- 日志仅用于排障，不作为主断言

5. 按协议能力组织测试文件
- 优先使用 `protocol-*.test.mjs` 命名，而不是按实现类命名

## 3. 分层策略

### 3.1 Unit

目标：

- 字段归一化
- 合法性校验
- 错误映射
- allowlist 过滤

要求：

- 不依赖真实连接
- 一次只验证一个协议规则

### 3.2 Integration

目标：

- `mock gateway + mock sdk + BridgeRuntime` 的协议回路
- 上下行消息、状态变化、补偿消息语义

要求：

- 这是主力回归层
- 优先覆盖主流程和协议边界

### 3.3 E2E Smoke

目标：

- 真实 `opencode serve` 加载插件后，能够与 mock gateway 跑通关键链路

要求：

- 只保留少量高价值场景
- 不承载 UI 自动化职责

## 4. 任务清单

### 4.1 第一阶段：建立协议测试基线

- [x] 明确测试边界为“message-bridge 功能正常 + ai-gateway 协议一致”
- [x] 明确测试用例必须遵从协议层定义
- [x] 建立本任务清单与编写规则
- [x] 新增 `status-query` 协议集成测试
- [x] 新增 `chat-stream` 协议集成测试

### 4.2 第二阶段：补齐主流程闭环

- [x] 新增 `permission-roundtrip` 协议集成测试
- [ ] 覆盖 `permission_reply.response=once|always|reject`
- [x] 覆盖非法 `permission_reply` 枚举值返回 `tool_error`
- [x] 新增 `question-roundtrip` 协议集成测试
- [x] 覆盖 `question_reply` 在唯一 pending request 下的成功回路
- [ ] 覆盖 `question_reply` 缺字段时报 `tool_error`
- [x] 覆盖 `question_reply` 无法匹配时报 `tool_error`
- [x] 覆盖 `question_reply` 歧义匹配时报 `tool_error`

### 4.3 第三阶段：补少量真实栈 smoke

- [x] 保持 `register` 主路径由 `unit/e2e` 双层覆盖，避免在并发 integration 中引入全局 `WebSocket` 脆弱性
- [x] 将 `connect-register` 场景提升为真实栈 E2E smoke
- [x] 将 `chat-stream` 场景提升为真实栈 E2E smoke
- [x] 将 `permission-roundtrip` 场景提升为真实栈 E2E smoke
- [x] 提供统一回归入口 `pnpm run test:integration && pnpm run test:e2e:smoke`

## 5. 用例拆分

### 5.1 `connect-register`

前置条件：

- bridge 读取到 enabled 配置
- gateway 连接可建立
- gateway 返回 `register_ok`

输入：

- runtime 启动
- gateway 下发 `status_query`

期望：

- 首包为 `register`
- `register` 包含协议要求的元数据字段
- bridge 返回 `status_response`
- `status_response` 仅承诺 `type` 和 `opencodeOnline`

### 5.2 `chat-stream`

前置条件：

- runtime 处于 `READY`
- 事件在 allowlist 中

输入：

- `invoke/chat`
- `message.part.delta`
- `message.part.updated`
- `session.idle`

期望：

- 流式事件统一上送为 `tool_event`
- `toolSessionId` 从事件中正确提取
- `session.idle` 继续作为 `tool_event` 上送
- `tool_done` 仅在协议约定场景发送，且不得重复

### 5.3 `permission-roundtrip`

输入：

- `permission.asked`
- `invoke/permission_reply`

期望：

- `permission.asked` 上送为 `tool_event`
- `permission_reply` 正确映射到 SDK
- `once|always|reject` 语义正确

### 5.4 `question-roundtrip`

输入：

- `question.asked`
- `invoke/question_reply`

期望：

- 题干、选项、`toolCallId`、`toolSessionId` 上送正确
- reply 能正确匹配 pending request
- 缺字段或歧义时报 `tool_error`

## 6. 执行顺序

1. 先跑 `tests/integration/protocol-connect.test.mjs`
2. 再跑 `tests/integration/protocol-chat-stream.test.mjs`
3. 再补 `permission-roundtrip`
4. 最后补 `question-roundtrip`

## 7. 验收标准

- 新增测试主断言全部来自协议层定义
- 改需求后可优先通过集成测试完成高频回归
- 发版前通过少量真实栈 smoke 证明插件可加载、可建连、可转发关键协议消息
