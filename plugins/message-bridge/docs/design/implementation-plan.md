# message-bridge 插件实施计划

**Version:** 1.0  
**Date:** 2026-03-07  
**Status:** Draft  
**Owner:** message-bridge maintainers  
**Related:** `../product/prd.md`, `../architecture/overview.md`, `../quality/test-strategy.md`  

## TL;DR
> **目标**: 基于 PRD v1.4 和架构设计文档，实现 message-bridge OpenCode 原生插件
> 
> **交付物**: 
> - 完整的 TypeScript 插件实现
> - 配置管理、连接管理、事件过滤、Action 路由、错误处理
> - 单元测试、集成测试、E2E 测试
> - 符合 PRD 所有功能和非功能需求
> 
> **预计工作量**: Large (复杂度高，多组件集成)
> **并行执行**: YES - 5 波次并行开发
> **关键路径**: 配置层 → 连接层 → 事件层 → Action 层 → 错误层 → 测试

---

## Context

### 原始需求
基于 `plugins/message-bridge/docs/product/prd.md` 实现 message-bridge 插件，兼容现有 pc-agent <-> ai-gateway 外边界，仅实现插件本身。

### 架构确认
已完成架构设计文档确认，关键决策：
- **register 响应**: ai-gateway 无显式响应，连接保持即成功
- **agentId**: 插件本地生成 (如 `bridge-{uuid}`)
- **heartbeat**: Gateway 不响应，仅服务端超时检测
- **并发 invoke**: 不支持同一 session 并发，顺序处理
- **Fast Fail**: 连接异常立即返回错误，不缓冲
- **安全日志**: SK 绝不记录，签名只记录 presence

### 技术栈
- **语言**: TypeScript
- **依赖**: `@opencode-ai/sdk@^1.2.15`, `ws@^8.x`, `jsonc-parser`
- **构建**: TypeScript 编译, npm scripts
- **测试**: 单元测试 + 集成测试 + E2E 测试

---

## Work Objectives

### 核心目标
实现符合 PRD v1.4 所有要求的 message-bridge 插件，包括：
1. WS 鉴权连接、心跳、重连
2. 下行 invoke/status_query 与上行协议消息
3. 可配置白名单与 action 注册表
4. permission_reply response-only 协议对齐
5. close_session → session.abort 固化
6. 配置文件获取与校验
7. 插件级单测/集成/最小 E2E

### 具体交付物
- `plugins/message-bridge/src/` - 完整源码
- `plugins/message-bridge/tests/` - 完整测试套件
- `plugins/message-bridge/package.json` - 包配置
- `plugins/message-bridge/tsconfig.json` - TS 配置
- `plugins/message-bridge/README.md` - 使用文档

### 完成定义
- [ ] 所有 PRD 功能需求实现并通过测试
- [ ] 覆盖率: lines >= 80%, branches >= 70%
- [ ] typecheck 通过
- [ ] 集成测试通过 (Mock Gateway + Mock SDK)
- [ ] E2E Smoke 测试通过
- [ ] 符合架构设计文档所有约束

### 必须包含
- 透明透传优先：事件主体不做业务改写
- 可扩展优先：事件与 action 不写死在核心引擎
- SDK 对齐优先：以 @opencode-ai/sdk 定义为长期目标
- Fast Fail 优先：不可达立即返回 tool_error
- 无状态设计：不持久化业务数据

### 必须排除 (Guardrails)
- 不修改 ai-gateway 业务代码
- 不修改 skill-server 业务代码  
- 不实现服务端幂等去重
- 不接入监控平台
- 不实现多平台 adapter

---

## Verification Strategy

### 测试基础设施
- **存在**: 项目需要配置 node:test 测试框架
- **自动化测试**: YES - TDD 方式实现
- **框架**: node:test (Node.js built-in test framework)
- **TDD 流程**: RED (失败测试) → GREEN (最小实现) → REFACTOR

### QA 策略
每个任务必须包含 Agent-Executed QA Scenarios:
- **Frontend/UI**: N/A (纯插件)
- **TUI/CLI**: interactive_bash (tmux) - 运行插件命令
- **API/Backend**: Bash (curl) - 测试 WebSocket 连接
- **Library/Module**: Bash (bun/node REPL) - 导入模块测试

### 测试分层
1. **Unit**: 白名单、映射、路由、错误分支、envelope/sequence
2. **Integration**: Mock Gateway WS + Mock SDK Client  
3. **E2E Smoke**: 注册、心跳、create+chat+close、permission_reply、断连重连、不可达启动失败

---

## Execution Strategy

### 并行执行波次

> 最大化吞吐量，独立任务并行开发。
> 目标: 5-8 任务/波次，充分并行。

```
Wave 1 (基础层 - 立即可开始):
├── Task 1: 项目脚手架 + 配置管理 [quick]
├── Task 2: 类型定义 + 接口契约 [quick]  
├── Task 3: 工具函数 + 常量定义 [quick]
└── Task 4: node:test 基础设置 [quick]

Wave 2 (核心层 - 依赖 Wave 1):
├── Task 5: 配置层实现 (ConfigResolver/Validator/Parser) [ultrabrain]
├── Task 6: 连接层实现 (GatewayConnection/AkSkAuth/StateManager) [ultrabrain]
├── Task 7: 事件层实现 (EventFilter/EnvelopeBuilder/EventRelay) [ultrabrain]
├── Task 8: Action 层骨架 (ActionRegistry/Router/BaseAction) [ultrabrain]
└── Task 9: 错误层实现 (ErrorMapper/FastFailDetector) [ultrabrain]

Wave 3 (业务层 - 依赖 Wave 2):
├── Task 10: Chat Action 实现 [ultrabrain]
├── Task 11: Create Session Action 实现 [ultrabrain]
├── Task 12: Close Session Action 实现 [ultrabrain]
├── Task 13: Permission Reply Action 实现 [ultrabrain]
├── Task 14: Status Query Action 实现 [ultrabrain]
└── Task 15: 插件主入口 (MessageBridgePlugin) [ultrabrain]

Wave 4 (测试层 - 依赖 Wave 3):
├── Task 16: 单元测试实现 (node:test) [ultrabrain]
├── Task 17: 集成测试实现 (node:test) [ultrabrain]
├── Task 18: E2E Smoke 测试实现 (node:test) [ultrabrain]
└── Task 19: 覆盖率配置和验证 (node:test) [quick]

Wave 5 (文档和验证 - 依赖所有):
├── Task 20: README 文档编写 [writing]
├── Task 21: 最终集成验证 [deep]
├── Task 22: PRD 需求追踪矩阵 [writing]
└── Task 23: 架构一致性验证 [deep]

Critical Path: Task 1 → Task 5 → Task 6 → Task 10 → Task 16 → Task 21
Parallel Speedup: ~75% faster than sequential
Max Concurrent: 5 (Wave 2 & 3 have 5 parallel tasks each)
```

### 依赖矩阵
- **1-4**: — — 5-9, 1
- **5**: 1,2,3 — 6,7,8,9, 2  
- **6**: 1,2,3,5 — 10-15, 2
- **7**: 1,2,3,5 — 10-15, 2
- **8**: 1,2,3,5 — 10-15, 2
- **9**: 1,2,3,5 — 10-15, 2
- **10-15**: 5,6,7,8,9 — 16-19, 3
- **16-19**: 10-15 — 20-23, 4
- **20-23**: 1-19 — F1-F4, 5

### Agent 分配
- **Wave 1**: `quick` category - 简单脚手架任务
- **Wave 2-4**: `ultrabrain` category - 复杂逻辑实现
- **Wave 5**: `writing` + `deep` - 文档和深度验证

---

## TODOs

> Implementation + Test = ONE Task. Never separate.
> EVERY task MUST have: Recommended Agent Profile + Parallelization info + QA Scenarios.
> **A task WITHOUT QA Scenarios is INCOMPLETE. No exceptions.**

- [x] 1. 项目脚手架 + 配置管理

  **What to do**:
  - 创建 `plugins/message-bridge/` 目录结构
  - 初始化 package.json (dependencies: @opencode-ai/sdk@^1.2.15, ws@^8.x, jsonc-parser)
  - 配置 tsconfig.json (target: ES2020, module: commonjs)
  - 设置 npm scripts (build, test, typecheck)
  - 创建基础目录结构:
    ```
    src/
    ├── index.ts
    ├── plugin/
    ├── config/
    ├── connection/
    ├── event/
    ├── action/
    └── error/
    tests/
    ├── unit/
    ├── integration/
    └── e2e/
    ```

  **Must NOT do**:
  - 不要实现具体业务逻辑
  - 不要修改其他项目文件

  **Recommended Agent Profile**:
  - **Category**: `quick`
    - Reason: Simple file structure creation and configuration
  - **Skills**: [`git-master`]
    - `git-master`: For proper directory structure and package.json setup
  - **Skills Evaluated but Omitted**:
    - `typescript-programmer`: Not needed for scaffolding

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 1 (with Tasks 2, 3, 4)
  - **Blocks**: Tasks 5-9
  - **Blocked By**: None (can start immediately)

  **References**:
  - `src/main/pc-agent/package.json` - Reference for dependencies and structure
  - `src/main/pc-agent/tsconfig.json` - Reference for TS configuration
  - PRD §2.2 - In Scope requirements

  **Acceptance Criteria**:
  - [ ] Directory structure matches specification
  - [ ] package.json has correct dependencies
  - [ ] npm run build succeeds
  - [ ] npm run typecheck succeeds

  **QA Scenarios**:
  ```
  Scenario: Verify project structure
    Tool: Bash
    Preconditions: None
    Steps:
      1. cd plugins/message-bridge
      2. ls -la
      3. cat package.json
      4. npm run build
    Expected Result: Build completes without errors, directory structure correct
    Evidence: .sisyphus/evidence/task-1-structure.txt

  Scenario: Verify dependencies
    Tool: Bash  
    Preconditions: Project scaffolded
    Steps:
      1. cd plugins/message-bridge
      2. npm list @opencode-ai/sdk ws jsonc-parser
    Expected Result: All required dependencies installed with correct versions
    Evidence: .sisyphus/evidence/task-1-deps.txt
  ```

  **Commit**: YES
  - Message: `feat(message-bridge): add project scaffolding`
  - Files: `plugins/message-bridge/**`
  - Pre-commit: `npm run build && npm run typecheck`

- [x] 2. 类型定义 + 接口契约

  **What to do**:
  - 创建 `src/types/index.ts` 包含所有核心类型
  - 定义 Config 接口 (PRD §2.9)
  - 定义 WebSocket 消息类型 (PRD §4.2, §4.3, §4.4)
  - 定义 Action Payload 类型 (PRD §7)
  - 定义 Error 类型 (PRD §7)
  - 定义 Envelope 类型 (PRD §4.4)

  **Must NOT do**:
  - 不要实现具体逻辑
  - 不要添加未在 PRD 中定义的字段

  **Recommended Agent Profile**:
  - **Category**: `quick`
    - Reason: Pure TypeScript interface definition
  - **Skills**: [`typescript-programmer`]
    - `typescript-programmer`: For precise type definitions matching PRD
  - **Skills Evaluated but Omitted**:
    - `git-master`: Not needed for type definitions

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 1 (with Tasks 1, 3, 4)
  - **Blocks**: Tasks 5-9
  - **Blocked By**: None

  **References**:
  - `plugins/message-bridge/docs/product/prd.md §7` - Type and error model
  - `plugins/message-bridge/docs/product/prd.md §4.4` - Envelope requirements
  - `src/main/pc-agent/types/` - Reference for type organization

  **Acceptance Criteria**:
  - [ ] All PRD-defined types are implemented
  - [ ] TypeScript compilation succeeds
  - [ ] Types match PRD field definitions exactly

  **QA Scenarios**:
  ```
  Scenario: Verify type definitions
    Tool: Bash
    Preconditions: Project scaffolded
    Steps:
      1. cd plugins/message-bridge
      2. npx tsc --noEmit
    Expected Result: TypeScript compilation succeeds without errors
    Evidence: .sisyphus/evidence/task-2-types.txt

  Scenario: Verify PRD compliance
    Tool: Bash
    Preconditions: Types defined
    Steps:
      1. cd plugins/message-bridge
      2. grep -r "InvokeAction" src/types/
      3. grep -r "PermissionReplyPayload" src/types/
    Expected Result: All PRD §7 types present with correct fields
    Evidence: .sisyphus/evidence/task-2-prd-compliance.txt
  ```

  **Commit**: YES (with Task 1)
  - Message: `feat(message-bridge): add type definitions`
  - Files: `plugins/message-bridge/src/types/**`

- [x] 3. 工具函数 + 常量定义

  **What to do**:
  - 创建 `src/utils/` 目录
  - 实现 AK/SK 签名工具函数
  - 实现 UUID 生成工具
  - 实现 JSONC 解析工具
  - 定义常量 (PRD §2.7, §NFR-MB-02)
  - 实现日志脱敏工具函数

  **Must NOT do**:
  - 不要实现业务逻辑
  - 不要硬编码敏感信息

  **Recommended Agent Profile**:
  - **Category**: `quick`
    - Reason: Utility functions and constants
  - **Skills**: [`typescript-programmer`]
    - `typescript-programmer`: For secure utility implementation
  - **Skills Evaluated but Omitted**:
    - `git-master`: Not needed

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 1 (with Tasks 1, 2, 4)
  - **Blocks**: Tasks 5-9
  - **Blocked By**: None

  **References**:
  - `src/main/pc-agent/AkSkAuth.ts` - Reference for AK/SK signing
  - `plugins/message-bridge/docs/product/prd.md §2.7` - Default parameters
  - `plugins/message-bridge/docs/product/prd.md §NFR-MB-03` - Security requirements

  **Acceptance Criteria**:
  - [ ] All utility functions implemented
  - [ ] Constants match PRD defaults
  - [ ] Security utilities properly sanitize sensitive data

  **QA Scenarios**:
  ```
  Scenario: Verify utility functions
    Tool: Bash
    Preconditions: Utilities implemented
    Steps:
      1. cd plugins/message-bridge
      2. node -e "const {generateUuid} = require('./dist/utils/uuid'); console.log(generateUuid().length)"
    Expected Result: UUID generation works, returns 36-character string
    Evidence: .sisyphus/evidence/task-3-uuid.txt

  Scenario: Verify security utilities
    Tool: Bash
    Preconditions: Security utilities implemented
    Steps:
      1. cd plugins/message-bridge
      2. node -e "const {sanitizeLog} = require('./dist/utils/security'); console.log(sanitizeLog('sk=secret123'))"
    Expected Result: Sensitive fields properly sanitized in logs
    Evidence: .sisyphus/evidence/task-3-security.txt
  ```

  **Commit**: YES (with Tasks 1-2)
  - Message: `feat(message-bridge): add utilities and constants`
  - Files: `plugins/message-bridge/src/utils/**`

- [x] 4. 测试基础设置

  **What to do**:
  - 配置 node:test 测试环境
  - 设置测试目录结构
  - 添加基础测试示例
  - 配置覆盖率阈值 (PRD §9.3)
  - 配置 npm scripts for testing

  **Must NOT do**:
  - 不要引入 Jest 或其他测试框架
  - 不要修改其他项目配置

  **Recommended Agent Profile**:
  - **Category**: `quick`
    - Reason: Test configuration setup for node:test
  - **Skills**: [`git-master`]
    - `git-master`: For proper test configuration
  - **Skills Evaluated but Omitted**:
    - `typescript-programmer`: Not needed

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 1 (with Tasks 1, 2, 3)
  - **Blocks**: Tasks 16-19
  - **Blocked By**: None

  **References**:
  - Node.js documentation for node:test
  - `plugins/message-bridge/docs/product/prd.md §9.3` - Coverage thresholds
  - `plugins/message-bridge/docs/quality/test-strategy.md §1.4` - Test framework baseline

  **Acceptance Criteria**:
  - [ ] node:test configuration complete
  - [ ] Coverage thresholds set to lines >= 80%, branches >= 70%
  - [ ] Basic test runs successfully with `node --test`
  - [ ] No Jest or other test frameworks introduced

  **QA Scenarios**:
  ```
  Scenario: Verify test setup
    Tool: Bash
    Preconditions: Test config created
    Steps:
      1. cd plugins/message-bridge
      2. node --test tests/unit/**/*.test.mjs
    Expected Result: Tests run successfully
    Evidence: .sisyphus/evidence/task-4-test-setup.txt

  Scenario: Verify coverage thresholds
    Tool: Bash
    Preconditions: Coverage configured
    Steps:
      1. cd plugins/message-bridge
      2. npm run test:coverage
      3. Check coverage report meets PRD requirements
    Expected Result: Coverage thresholds match PRD requirements
    Evidence: .sisyphus/evidence/task-4-coverage-thresholds.txt
  ```

  **Commit**: YES (with Tasks 1-3)
  - Message: `feat(message-bridge): add node:test infrastructure`
  - Files: `plugins/message-bridge/tests/**`, `plugins/message-bridge/package.json`

- [ ] 10. Chat Action 实现

  **What to do**:
  - 扩展 BaseAction 实现 ChatAction
  - 实现 validate 方法验证 payload
  - 实现 execute 方法调用 SDK session.prompt()
  - 实现 errorMapper 方法映射 SDK 错误
  - 注册到 ActionRegistry

  **Must NOT do**:
  - 不要修改其他 Action 实现
  - 不要硬编码 session ID

  **Recommended Agent Profile**:
  - **Category**: `ultrabrain`
    - Reason: SDK integration and action implementation
  - **Skills**: [`typescript-programmer`]
    - `typescript-programmer`: For SDK method calling and error handling
  - **Skills Evaluated but Omitted**:
    - `git-master`: Not needed

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 3 (with Tasks 11, 12, 13, 14, 15)
  - **Blocks**: Tasks 16-19
  - **Blocked By**: Tasks 5, 6, 7, 8, 9, 9.5

  **References**:
  - `plugins/message-bridge/docs/product/prd.md §FR-MB-04` - Basic action support
  - `@opencode-ai/sdk` documentation - session.prompt() method
  - Architecture doc §3.4.3 - Chat Action requirements

  **Acceptance Criteria**:
  - [ ] ChatAction extends BaseAction correctly
  - [ ] validate method validates required fields
  - [ ] execute method calls SDK session.prompt() with correct parameters
  - [ ] Action registered in ActionRegistry

  **QA Scenarios**:
  ```
  Scenario: Verify chat action validation
    Tool: Bash
    Preconditions: ChatAction implemented
    Steps:
      1. cd plugins/message-bridge
      2. node -e "const action = require('./dist/action/ChatAction').default; try { action.validate({}); } catch(e) { console.log('validation failed as expected'); }"
    Expected Result: Validation fails for empty payload
    Evidence: .sisyphus/evidence/task-10-validate.txt

  Scenario: Verify chat action execution
    Tool: Bash
    Preconditions: Mock SDK available
    Steps:
      1. cd plugins/message-bridge
      2. node -e "const action = require('./dist/action/ChatAction').default; // mock SDK call verification"
    Expected Result: SDK session.prompt() called with correct parameters
    Evidence: .sisyphus/evidence/task-10-execute.txt
  ```

  **Commit**: YES
  - Message: `feat(message-bridge): implement chat action`
  - Files: `plugins/message-bridge/src/action/ChatAction.ts`

- [ ] 11. Create Session Action 实现

  **What to do**:
  - 扩展 BaseAction 实现 CreateSessionAction
  - 实现 validate 方法验证 payload
  - 实现 execute 方法调用 SDK session.create()
  - 实现 errorMapper 方法映射 SDK 错误
  - 注册到 ActionRegistry

  **Must NOT do**:
  - 不要修改其他 Action 实现
  - 不要添加额外的 session 创建逻辑

  **Recommended Agent Profile**:
  - **Category**: `ultrabrain`
    - Reason: SDK integration and session management
  - **Skills**: [`typescript-programmer`]
    - `typescript-programmer`: For SDK session creation
  - **Skills Evaluated but Omitted**:
    - `git-master`: Not needed

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 3 (with Tasks 10, 12, 13, 14, 15)
  - **Blocks**: Tasks 16-19
  - **Blocked By**: Tasks 5, 6, 7, 8, 9, 9.5

  **References**:
  - `plugins/message-bridge/docs/product/prd.md §FR-MB-04` - Basic action support
  - `@opencode-ai/sdk` documentation - session.create() method
  - Architecture doc §3.4.3 - Create Session Action requirements

  **Acceptance Criteria**:
  - [ ] CreateSessionAction extends BaseAction correctly
  - [ ] validate method validates payload structure
  - [ ] execute method calls SDK session.create() with correct parameters
  - [ ] Action registered in ActionRegistry

  **QA Scenarios**:
  ```
  Scenario: Verify create session action
    Tool: Bash
    Preconditions: CreateSessionAction implemented
    Steps:
      1. cd plugins/message-bridge
      2. node -e "const action = require('./dist/action/CreateSessionAction').default; // test with valid payload"
    Expected Result: SDK session.create() called successfully
    Evidence: .sisyphus/evidence/task-11-create.txt
  ```

  **Commit**: YES
  - Message: `feat(message-bridge): implement create session action`
  - Files: `plugins/message-bridge/src/action/CreateSessionAction.ts`

- [ ] 12. Close Session Action 实现

  **What to do**:
  - 扩展 BaseAction 实现 CloseSessionAction
  - 实现 validate 方法验证 payload
  - 实现 execute 方法调用 SDK session.abort()（不执行 delete）
  - 实现 errorMapper 方法映射 SDK 错误
  - 注册到 ActionRegistry

  **Must NOT do**:
  - **不要调用 session.delete()** - 必须使用 session.abort()
  - 不要修改其他 Action 实现

  **Recommended Agent Profile**:
  - **Category**: `ultrabrain`
    - Reason: Critical business logic with specific requirements
  - **Skills**: [`typescript-programmer`]
    - `typescript-programmer`: For precise SDK method calling
  - **Skills Evaluated but Omitted**:
    - `git-master`: Not needed

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 3 (with Tasks 10, 11, 13, 14, 15)
  - **Blocks**: Tasks 16-19
  - **Blocked By**: Tasks 5, 6, 7, 8, 9, 9.5

  **References**:
  - `plugins/message-bridge/docs/product/prd.md §FR-MB-05` - Close semantics requirement
  - `plugins/message-bridge/docs/product/prd.md §FR-MB-10` - Must say "close_session -> abort"
  - `@opencode-ai/sdk` documentation - session.abort() method
  - Architecture doc §3.4.3 - Close Session Action requirements

  **Acceptance Criteria**:
  - [ ] CloseSessionAction extends BaseAction correctly
  - [ ] execute method calls SDK session.abort() (NOT delete)
  - [ ] Action registered in ActionRegistry
  - [ ] PRD requirement FR-MB-05 fully satisfied

  **QA Scenarios**:
  ```
  Scenario: Verify close session abort only
    Tool: Bash
    Preconditions: CloseSessionAction implemented
    Steps:
      1. cd plugins/message-bridge
      2. node -e "const action = require('./dist/action/CloseSessionAction').default; // verify calls abort not delete"
    Expected Result: SDK session.abort() called, NOT session.delete()
    Evidence: .sisyphus/evidence/task-12-abort-only.txt
  ```

  **Commit**: YES
  - Message: `feat(message-bridge): implement close session action`
  - Files: `plugins/message-bridge/src/action/CloseSessionAction.ts`

- [ ] 13. Permission Reply Action 实现

  **What to do**:
  - 扩展 BaseAction 实现 PermissionReplyAction
  - 实现 validate 方法严格校验 `permissionId/toolSessionId/response`
  - 实现 execute 方法透传 `response` 到 SDK
  - 实现 errorMapper 方法映射 SDK 错误
  - 注册到 ActionRegistry

  **Must NOT do**:
  - 不要接收 legacy `approved` 字段
  - 不要引入 `allow/deny` 中间映射

  **Recommended Agent Profile**:
  - **Category**: `ultrabrain`
    - Reason: Strict protocol validation and SDK passthrough behavior
  - **Skills**: [`typescript-programmer`]
    - `typescript-programmer`: For compatibility layer implementation
  - **Skills Evaluated but Omitted**:
    - `git-master`: Not needed

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 3 (with Tasks 10, 11, 12, 14, 15)
  - **Blocks**: Tasks 16-19
  - **Blocked By**: Tasks 5, 6, 7, 8, 9, 9.5

  **References**:
  - `plugins/message-bridge/docs/product/prd.md §FR-MB-06` - response-only protocol contract
  - `plugins/message-bridge/docs/design/interfaces/protocol-contract.md` - canonical payload and values
  - Architecture doc §3.4.4 - Permission Reply protocol details

  **Acceptance Criteria**:
  - [ ] PermissionReplyAction requires `permissionId/toolSessionId/response`
  - [ ] `response` only accepts `once|always|reject`
  - [ ] Action registered in ActionRegistry
  - [ ] Legacy `approved` payload is rejected

  **QA Scenarios**:
  ```
  Scenario: Verify response-only validation
    Tool: Bash
    Preconditions: PermissionReplyAction implemented
    Steps:
      1. cd plugins/message-bridge
      2. node -e "const action = require('./dist/action/PermissionReplyAction').default; console.log(action.validate({ permissionId: 'p1', toolSessionId: 's1', response: 'once' }), action.validate({ permissionId: 'p1', approved: true }))"
    Expected Result: first valid=true, second valid=false
    Evidence: .sisyphus/evidence/task-13-response-only.txt

  Scenario: Verify response field direct use
    Tool: Bash
    Preconditions: PermissionReplyAction implemented
    Steps:
      1. cd plugins/message-bridge
      2. node -e "const action = require('./dist/action/PermissionReplyAction').default; console.log(action.validate({ permissionId: 'p1', toolSessionId: 's1', response: 'always' }))"
    Expected Result: valid=true
    Evidence: .sisyphus/evidence/task-13-response-direct.txt
  ```

  **Commit**: YES
  - Message: `feat(message-bridge): implement permission reply action`
  - Files: `plugins/message-bridge/src/action/PermissionReplyAction.ts`

- [ ] 9.5. TypeScript 配置修复

  **What to do**:
  - 更新 tsconfig.json 的 moduleResolution 设置为 "node16"
  - 确保 @opencode-ai/sdk 模块能正确解析
  - 验证所有 TypeScript 编译通过

  **Must NOT do**:
  - 不要修改其他 TypeScript 配置选项
  - 不要 break existing compilation

  **Recommended Agent Profile**:
  - **Category**: `quick`
    - Reason: Simple configuration fix
  - **Skills**: [`typescript-programmer`]
    - `typescript-programmer`: For proper module resolution configuration

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 2.5 (prerequisite for Wave 3)
  - **Blocks**: Tasks 10-15
  - **Blocked By**: None

  **References**:
  - TypeScript documentation for moduleResolution
  - @opencode-ai/sdk module structure

  **Acceptance Criteria**:
  - [ ] moduleResolution set to "node16"
  - [ ] npm run typecheck passes without errors
  - [ ] All imports resolve correctly

  **QA Scenarios**:
  ```
  Scenario: Verify module resolution
    Tool: Bash
    Preconditions: tsconfig.json updated
    Steps:
      1. cd plugins/message-bridge
      2. npm run typecheck
    Expected Result: TypeScript compilation succeeds without module resolution errors
    Evidence: .sisyphus/evidence/task-9.5-ts-config.txt
  ```

  **Commit**: YES
  - Message: `fix(message-bridge): update tsconfig for proper module resolution`
  - Files: `plugins/message-bridge/tsconfig.json`


- [ ] 11. Create Session Action 实现

  **What to do**:
  - 扩展 BaseAction 实现 CreateSessionAction
  - 实现 validate 方法验证 payload
  - 实现 execute 方法调用 SDK session.create()
  - 实现 errorMapper 方法映射 SDK 错误
  - 注册到 ActionRegistry

  **Must NOT do**:
  - 不要修改其他 Action 实现
  - 不要添加额外的 session 创建逻辑

  **Recommended Agent Profile**:
  - **Category**: `ultrabrain`
    - Reason: SDK integration and session management
  - **Skills**: [`typescript-programmer`]
    - `typescript-programmer`: For SDK session creation
  - **Skills Evaluated but Omitted**:
    - `git-master`: Not needed

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 3 (with Tasks 10, 12, 13, 14, 15)
  - **Blocks**: Tasks 16-19
  - **Blocked By**: Tasks 5, 6, 7, 8, 9

  **References**:
  - `plugins/message-bridge/docs/product/prd.md §FR-MB-04` - Basic action support
  - `@opencode-ai/sdk` documentation - session.create() method
  - Architecture doc §3.4.3 - Create Session Action requirements

  **Acceptance Criteria**:
  - [ ] CreateSessionAction extends BaseAction correctly
  - [ ] validate method validates payload structure
  - [ ] execute method calls SDK session.create() with correct parameters
  - [ ] Action registered in ActionRegistry

  **QA Scenarios**:
  ```
  Scenario: Verify create session action
    Tool: Bash
    Preconditions: CreateSessionAction implemented
    Steps:
      1. cd plugins/message-bridge
      2. node -e "const action = require('./dist/action/CreateSessionAction').default; // test with valid payload"
    Expected Result: SDK session.create() called successfully
    Evidence: .sisyphus/evidence/task-11-create.txt
  ```

  **Commit**: YES
  - Message: `feat(message-bridge): implement create session action`
  - Files: `plugins/message-bridge/src/action/CreateSessionAction.ts`

- [ ] 12. Close Session Action 实现

  **What to do**:
  - 扩展 BaseAction 实现 CloseSessionAction
  - 实现 validate 方法验证 payload
  - 实现 execute 方法调用 SDK session.abort()（不执行 delete）
  - 实现 errorMapper 方法映射 SDK 错误
  - 注册到 ActionRegistry

  **Must NOT do**:
  - **不要调用 session.delete()** - 必须使用 session.abort()
  - 不要修改其他 Action 实现

  **Recommended Agent Profile**:
  - **Category**: `ultrabrain`
    - Reason: Critical business logic with specific requirements
  - **Skills**: [`typescript-programmer`]
    - `typescript-programmer`: For precise SDK method calling
  - **Skills Evaluated but Omitted**:
    - `git-master`: Not needed

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 3 (with Tasks 10, 11, 13, 14, 15)
  - **Blocks**: Tasks 16-19
  - **Blocked By**: Tasks 5, 6, 7, 8, 9

  **References**:
  - `plugins/message-bridge/docs/product/prd.md §FR-MB-05` - Close semantics requirement
  - `plugins/message-bridge/docs/product/prd.md §FR-MB-10` - Must say "close_session -> abort"
  - `@opencode-ai/sdk` documentation - session.abort() method
  - Architecture doc §3.4.3 - Close Session Action requirements

  **Acceptance Criteria**:
  - [ ] CloseSessionAction extends BaseAction correctly
  - [ ] execute method calls SDK session.abort() (NOT delete)
  - [ ] Action registered in ActionRegistry
  - [ ] PRD requirement FR-MB-05 fully satisfied

  **QA Scenarios**:
  ```
  Scenario: Verify close session abort only
    Tool: Bash
    Preconditions: CloseSessionAction implemented
    Steps:
      1. cd plugins/message-bridge
      2. node -e "const action = require('./dist/action/CloseSessionAction').default; // verify calls abort not delete"
    Expected Result: SDK session.abort() called, NOT session.delete()
    Evidence: .sisyphus/evidence/task-12-abort-only.txt
  ```

  **Commit**: YES
  - Message: `feat(message-bridge): implement close session action`
  - Files: `plugins/message-bridge/src/action/CloseSessionAction.ts`

- [ ] 13. Permission Reply Action 实现

  **What to do**:
  - 扩展 BaseAction 实现 PermissionReplyAction
  - 实现 validate 方法严格校验 `permissionId/toolSessionId/response`
  - 实现 execute 方法透传 `response` 到 SDK
  - 实现 errorMapper 方法映射 SDK 错误
  - 注册到 ActionRegistry

  **Must NOT do**:
  - 不要接收 legacy `approved` 字段
  - 不要引入 `allow/deny` 中间映射

  **Recommended Agent Profile**:
  - **Category**: `ultrabrain`
    - Reason: Strict protocol validation and SDK passthrough behavior
  - **Skills**: [`typescript-programmer`]
    - `typescript-programmer`: For compatibility layer implementation
  - **Skills Evaluated but Omitted**:
    - `git-master`: Not needed

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 3 (with Tasks 10, 11, 12, 14, 15)
  - **Blocks**: Tasks 16-19
  - **Blocked By**: Tasks 5, 6, 7, 8, 9

  **References**:
  - `plugins/message-bridge/docs/product/prd.md §FR-MB-06` - response-only protocol contract
  - `plugins/message-bridge/docs/design/interfaces/protocol-contract.md` - canonical payload and values
  - Architecture doc §3.4.4 - Permission Reply protocol details

  **Acceptance Criteria**:
  - [ ] PermissionReplyAction requires `permissionId/toolSessionId/response`
  - [ ] `response` only accepts `once|always|reject`
  - [ ] Action registered in ActionRegistry
  - [ ] Legacy `approved` payload is rejected

  **QA Scenarios**:
  ```
  Scenario: Verify response-only validation
    Tool: Bash
    Preconditions: PermissionReplyAction implemented
    Steps:
      1. cd plugins/message-bridge
      2. node -e "const action = require('./dist/action/PermissionReplyAction').default; console.log(action.validate({ permissionId: 'p1', toolSessionId: 's1', response: 'once' }), action.validate({ permissionId: 'p1', approved: true }))"
    Expected Result: first valid=true, second valid=false
    Evidence: .sisyphus/evidence/task-13-response-only.txt

  Scenario: Verify response field direct use
    Tool: Bash
    Preconditions: PermissionReplyAction implemented
    Steps:
      1. cd plugins/message-bridge
      2. node -e "const action = require('./dist/action/PermissionReplyAction').default; console.log(action.validate({ permissionId: 'p1', toolSessionId: 's1', response: 'always' }))"
    Expected Result: valid=true
    Evidence: .sisyphus/evidence/task-13-response-direct.txt
  ```

  **Commit**: YES
  - Message: `feat(message-bridge): implement permission reply action`
  - Files: `plugins/message-bridge/src/action/PermissionReplyAction.ts`

- [ ] 14. Status Query Action 实现

  **What to do**:
  - 扩展 BaseAction 实现 StatusQueryAction
  - 实现 validate 方法（无 payload 验证）
  - 实现 execute 方法调用 SDK health() 并返回 status_response
  - 实现 errorMapper 方法处理健康检查错误
  - 注册到 ActionRegistry

  **Must NOT do**:
  - 不要实现定期上报（仅响应查询）
  - 不要修改 status_response 格式

  **Recommended Agent Profile**:
  - **Category**: `ultrabrain`
    - Reason: Health check integration and response formatting
  - **Skills**: [`typescript-programmer`]
    - `typescript-programmer`: For health check and response building
  - **Skills Evaluated but Omitted**:
    - `git-master`: Not needed

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 3 (with Tasks 10, 11, 12, 13, 15)
  - **Blocks**: Tasks 16-19
  - **Blocked By**: Tasks 5, 6, 7, 8, 9

  **References**:
  - `plugins/message-bridge/docs/product/prd.md §FR-MB-08` - Status query requirements
  - `plugins/message-bridge/docs/product/prd.md §7` - StatusResponse definition
  - Architecture investigation results - Only on-demand, no periodic

  **Acceptance Criteria**:
  - [ ] StatusQueryAction extends BaseAction correctly
  - [ ] execute method calls SDK health() and returns status_response
  - [ ] status_response contains opencodeOnline boolean and envelope
  - [ ] Only responds to queries, no periodic reporting

  **QA Scenarios**:
  ```
  Scenario: Verify status query response
    Tool: Bash
    Preconditions: StatusQueryAction implemented
    Steps:
      1. cd plugins/message-bridge
      2. node -e "const action = require('./dist/action/StatusQueryAction').default; // mock health check"
    Expected Result: Returns status_response with opencodeOnline and envelope
    Evidence: .sisyphus/evidence/task-14-status-response.txt
  ```

  **Commit**: YES
  - Message: `feat(message-bridge): implement status query action`
  - Files: `plugins/message-bridge/src/action/StatusQueryAction.ts`

- [ ] 15. 插件主入口 (MessageBridgePlugin)

  **What to do**:
  - 实现 `MessageBridgePlugin.ts` - 插件生命周期管理
  - 集成所有层（配置、连接、事件、Action、错误）
  - 实现 start/stop 方法
  - 实现 SDK 事件订阅和 Gateway 路由
  - 添加初始化和清理逻辑

  **Must NOT do**:
  - 不要实现具体的业务逻辑（委托给各层）
  - 不要硬编码配置

  **Recommended Agent Profile**:
  - **Category**: `ultrabrain`
    - Reason: Orchestrator pattern integrating all components
  - **Skills**: [`typescript-programmer`]
    - `typescript-programmer`: For component orchestration
  - **Skills Evaluated but Omitted**:
    - `git-master`: Not needed

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 3 (with Tasks 10, 11, 12, 13, 14)
  - **Blocks**: Tasks 16-19
  - **Blocked By**: Tasks 5, 6, 7, 8, 9, 10, 11, 12, 13, 14

  **References**:
  - `src/main/pc-agent/PcAgentPlugin.ts` - Reference for plugin lifecycle
  - Architecture doc §3.1.3 - Plugin lifecycle pseudo-code
  - PRD §2.2 - In Scope requirements

  **Acceptance Criteria**:
  - [ ] MessageBridgePlugin orchestrates all components correctly
  - [ ] start method initializes all layers in correct order
  - [ ] stop method cleans up resources properly
  - [ ] SDK events routed to EventRelay
  - [ ] Gateway messages routed to ActionRouter

  **QA Scenarios**:
  ```
  Scenario: Verify plugin lifecycle
    Tool: interactive_bash
    Preconditions: Plugin implemented
    Steps:
      1. tmux new-session -d -s plugin-test
      2. tmux send-keys -t plugin-test "cd plugins/message-bridge && npm run build" Enter
      3. tmux send-keys -t plugin-test "node -e 'require(\"./dist/plugin/MessageBridgePlugin\").testLifecycle()'" Enter
    Expected Result: Plugin starts and stops without errors
    Evidence: .sisyphus/evidence/task-15-lifecycle.txt
  ```

  **Commit**: YES
  - Message: `feat(message-bridge): implement plugin main entry`
  - Files: `plugins/message-bridge/src/plugin/MessageBridgePlugin.ts`

- [ ] 16. 单元测试实现

  **What to do**:
  - 为所有核心模块实现单元测试使用 node:test
  - ConfigResolver/Validator/Parser 测试
  - GatewayConnection/AkSkAuth/StateManager 测试
  - EventFilter/EnvelopeBuilder/EventRelay 测试
  - ActionRegistry/Router/BaseAction 测试
  - ErrorMapper/FastFailDetector 测试
  - 所有 Action 实现测试
  - 覆盖率 >= 80% lines, >= 70% branches

  **Must NOT do**:
  - 不要使用 Jest 或其他测试框架
  - 不要使用真实 WebSocket 连接
  - 不要依赖外部服务

  **Recommended Agent Profile**:
  - **Category**: `ultrabrain`
    - Reason: Comprehensive test coverage for complex logic with node:test
  - **Skills**: [`typescript-programmer`]
    - `typescript-programmer`: For thorough unit testing with node:test
  - **Skills Evaluated but Omitted**:
    - `git-master`: Not needed

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 4 (with Tasks 17, 18, 19)
  - **Blocks**: Tasks 20-23
  - **Blocked By**: Tasks 10-15

  **References**:
  - `plugins/message-bridge/docs/product/prd.md §9.1` - Test layering requirements
  - `plugins/message-bridge/docs/product/prd.md §9.3` - Coverage thresholds
  - `plugins/message-bridge/docs/quality/test-strategy.md` - Test framework baseline
  - Node.js node:test documentation

  **Acceptance Criteria**:
  - [ ] All core modules have unit tests using node:test
  - [ ] Coverage meets PRD requirements (lines >= 80%, branches >= 70%)
  - [ ] All edge cases and error paths tested
  - [ ] Tests pass with `node --test`

  **QA Scenarios**:
  ```
  Scenario: Verify unit test coverage
    Tool: Bash
    Preconditions: Unit tests implemented
    Steps:
      1. cd plugins/message-bridge
      2. npm run test:coverage
    Expected Result: Coverage meets PRD thresholds
    Evidence: .sisyphus/evidence/task-16-coverage.txt
  ```

  **Commit**: YES
  - Message: `test(message-bridge): add comprehensive unit tests with node:test`
  - Files: `plugins/message-bridge/tests/unit/**`

- [ ] 17. 集成测试实现

  **What to do**:
  - 实现 Mock Gateway WS 服务器
  - 实现 Mock SDK Client
  - 测试完整集成流程使用 node:test
  - 测试配置层 → 连接层 → 事件层 → Action 层 → 错误层
  - 验证 PRD 所有功能需求

  **Must NOT do**:
  - 不要连接真实 Gateway
  - 不要使用真实 OpenCode 实例
  - 不要引入 Jest 或其他 test frameworks

  **Recommended Agent Profile**:
  - **Category**: `ultrabrain`
    - Reason: Complex integration testing with mocks using node:test
  - **Skills**: [`typescript-programmer`]
    - `typescript-programmer`: For mock server and client implementation with node:test
  - **Skills Evaluated but Omitted**:
    - `git-master`: Not needed

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 4 (with Tasks 16, 18, 19)
  - **Blocks**: Tasks 20-23
  - **Blocked By**: Tasks 10-15

  **References**:
  - `plugins/message-bridge/docs/product/prd.md §9.1` - Integration test requirements
  - `plugins/message-bridge/docs/product/prd.md §9.2` - Required test scenarios
  - Existing project integration test patterns
  - Node.js node:test documentation

  **Acceptance Criteria**:
  - [ ] Mock Gateway WS server simulates real Gateway behavior
  - [ ] Mock SDK Client simulates real OpenCode SDK
  - [ ] All PRD required scenarios tested with node:test
  - [ ] Integration tests pass with `node --test`

  **QA Scenarios**:
  ```
  Scenario: Verify integration test flow
    Tool: interactive_bash
    Preconditions: Integration tests implemented
    Steps:
      1. tmux new-session -d -s integration-test
      2. tmux send-keys -t integration-test "cd plugins/message-bridge && node --test tests/integration/**/*.test.mjs" Enter
    Expected Result: Integration tests pass, simulating full message flow
    Evidence: .sisyphus/evidence/task-17-integration.txt
  ```

  **Commit**: YES
  - Message: `test(message-bridge): add integration tests with node:test`
  - Files: `plugins/message-bridge/tests/integration/**`

- [ ] 18. E2E Smoke 测试实现

  **What to do**:
  - 实现 E2E Smoke 测试使用 node:test
  - 测试注册、心跳、create+chat+close、permission_reply
  - 测试断连重连、不可达启动失败
  - 测试 Fast Fail 行为
  - 测试 envelope 完整性和 sequence 递增

  **Must NOT do**:
  - 不要实现完整的 E2E 套件（仅 Smoke）
  - 不要依赖外部网络
  - 不要引入 Jest 或其他 test frameworks

  **Recommended Agent Profile**:
  - **Category**: `ultrabrain`
    - Reason: End-to-end testing with realistic scenarios using node:test
  - **Skills**: [`typescript-programmer`]
    - `typescript-programmer`: For E2E test orchestration with node:test
  - **Skills Evaluated but Omitted**:
    - `git-master`: Not needed

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 4 (with Tasks 16, 17, 19)
  - **Blocks**: Tasks 20-23
  - **Blocked By**: Tasks 10-15

  **References**:
  - `plugins/message-bridge/docs/product/prd.md §9.1` - E2E smoke requirements
  - `plugins/message-bridge/docs/product/prd.md §9.2` - Required E2E scenarios
  - Architecture doc §3.4 - Business flow requirements
  - Node.js node:test documentation

  **Acceptance Criteria**:
  - [ ] All PRD E2E smoke scenarios implemented with node:test
  - [ ] Tests cover critical user flows
  - [ ] E2E tests pass consistently with `node --test`
  - [ ] Fast Fail behavior verified

  **QA Scenarios**:
  ```
  Scenario: Verify E2E smoke test
    Tool: interactive_bash
    Preconditions: E2E tests implemented
    Steps:
      1. tmux new-session -d -s e2e-test
      2. tmux send-keys -t e2e-test "cd plugins/message-bridge && node --test tests/e2e/**/*.test.mjs" Enter
    Expected Result: E2E smoke tests pass, covering all critical flows
    Evidence: .sisyphus/evidence/task-18-e2e.txt
  ```

  **Commit**: YES
  - Message: `test(message-bridge): add E2E smoke tests with node:test`
  - Files: `plugins/message-bridge/tests/e2e/**`

- [ ] 19. 覆盖率配置和验证

  **What to do**:
  - 配置 node:test 覆盖率报告
  - 设置 PRD 要求的阈值 (lines >= 80%, branches >= 70%)
  - 添加覆盖率验证脚本 for node:test
  - 验证所有测试运行后覆盖率达标

  **Must NOT do**:
  - 不要降低覆盖率要求
  - 不要忽略覆盖率失败
  - 不要 use Jest coverage configuration

  **Recommended Agent Profile**:
  - **Category**: `quick`
    - Reason: Configuration and validation setup for node:test
  - **Skills**: [`git-master`]
    - `git-master`: For proper test configuration
  - **Skills Evaluated but Omitted**:
    - `typescript-programmer`: Not needed for config

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 4 (with Tasks 16, 17, 18)
  - **Blocks**: Tasks 20-23
  - **Blocked By**: Tasks 16, 17, 18

  **References**:
  - `plugins/message-bridge/docs/product/prd.md §9.3` - Quality thresholds
  - Task 4 - Test infrastructure setup with node:test
  - Node.js node:test coverage documentation

  **Acceptance Criteria**:
  - [ ] Coverage thresholds configured correctly for node:test
  - [ ] Coverage validation script works with node:test
  - [ ] All tests pass with coverage meeting thresholds using `npm run test:coverage`

  **QA Scenarios**:
  ```
  Scenario: Verify coverage thresholds
    Tool: Bash
    Preconditions: Coverage configured
    Steps:
      1. cd plugins/message-bridge
      2. npm run test:coverage
    Expected Result: Tests pass and coverage meets thresholds
    Evidence: .sisyphus/evidence/task-19-coverage-thresholds.txt
  ```

  **Commit**: YES
  - Message: `test(message-bridge): configure node:test coverage thresholds`
  - Files: `plugins/message-bridge/package.json`

- [ ] 20. README 文档编写

  **What to do**:
  - 编写插件 README.md
  - 包含安装、配置、使用说明
  - 包含架构概述和组件说明
  - 包含测试和开发指南
  - 符合项目文档规范

  **Must NOT do**:
  - 不要包含未实现的功能
  - 不要复制其他项目的文档

  **Recommended Agent Profile**:
  - **Category**: `writing`
    - Reason: Technical documentation writing
  - **Skills**: []
    - No specific skills needed for documentation
  - **Skills Evaluated but Omitted**:
    - `typescript-programmer`: Not needed for docs

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 5 (with Tasks 21, 22, 23)
  - **Blocks**: F1-F4
  - **Blocked By**: Tasks 1-19

  **References**:
  - Architecture design document
  - PRD requirements
  - Existing project README patterns

  **Acceptance Criteria**:
  - [ ] README contains complete installation guide
  - [ ] Configuration options documented
  - [ ] Usage examples provided
  - [ ] Architecture overview included
  - [ ] Testing instructions documented

  **QA Scenarios**:
  ```
  Scenario: Verify README completeness
    Tool: Bash
    Preconditions: README written
    Steps:
      1. cd plugins/message-bridge
      2. cat README.md
    Expected Result: README contains all required sections and is comprehensive
    Evidence: .sisyphus/evidence/task-20-readme.txt
  ```

  **Commit**: YES
  - Message: `docs(message-bridge): add README documentation`
  - Files: `plugins/message-bridge/README.md`

- [ ] 21. 最终集成验证

  **What to do**:
  - 执行完整的端到端集成验证
  - 验证所有 PRD 功能需求
  - 验证架构设计文档合规性
  - 验证性能和稳定性要求
  - 生成验证报告

  **Must NOT do**:
  - 不要跳过任何 PRD 要求
  - 不要忽略边缘情况

  **Recommended Agent Profile**:
  - **Category**: `deep`
    - Reason: Comprehensive validation and verification
  - **Skills**: [`typescript-programmer`]
    - `typescript-programmer`: For thorough validation
  - **Skills Evaluated but Omitted**:
    - `git-master`: Not needed

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 5 (with Tasks 20, 22, 23)
  - **Blocks**: F1-F4
  - **Blocked By**: Tasks 1-19

  **References**:
  - PRD v1.4 complete requirements
  - Architecture design document
  - All implemented features

  **Acceptance Criteria**:
  - [ ] All PRD functional requirements verified
  - [ ] All PRD non-functional requirements verified
  - [ ] Architecture compliance confirmed
  - [ ] Validation report generated

  **QA Scenarios**:
  ```
  Scenario: Verify PRD compliance
    Tool: Bash
    Preconditions: All features implemented
    Steps:
      1. cd plugins/message-bridge
      2. npm run validate:prd
    Expected Result: All PRD requirements satisfied
    Evidence: .sisyphus/evidence/task-21-prd-compliance.txt
  ```

  **Commit**: YES
  - Message: `feat(message-bridge): final integration validation`
  - Files: `plugins/message-bridge/validation/**`

- [ ] 22. PRD 需求追踪矩阵

  **What to do**:
  - 创建 PRD 需求追踪矩阵
  - 映射每个 PRD 需求到具体实现和测试
  - 确保 100% 需求覆盖
  - 生成追踪报告

  **Must NOT do**:
  - 不要遗漏任何 PRD 需求
  - 不要映射到不存在的实现

  **Recommended Agent Profile**:
  - **Category**: `writing`
    - Reason: Requirements traceability documentation
  - **Skills**: []
    - No specific skills needed
  - **Skills Evaluated but Omitted**:
    - `typescript-programmer`: Not needed

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 5 (with Tasks 20, 21, 23)
  - **Blocks**: F1-F4
  - **Blocked By**: Tasks 1-19

  **References**:
  - `plugins/message-bridge/docs/product/prd.md` - Complete PRD
  - All implemented features and tests

  **Acceptance Criteria**:
  - [ ] Every PRD requirement mapped to implementation
  - [ ] Every PRD requirement mapped to test
  - [ ] 100% coverage achieved
  - [ ] Traceability matrix complete

  **QA Scenarios**:
  ```
  Scenario: Verify requirements traceability
    Tool: Bash
    Preconditions: Traceability matrix created
    Steps:
      1. cd plugins/message-bridge
      2. cat docs/requirements-traceability.md
    Expected Result: Every PRD requirement has corresponding implementation and test
    Evidence: .sisyphus/evidence/task-22-traceability.txt
  ```

  **Commit**: YES
  - Message: `docs(message-bridge): add PRD requirements traceability`
  - Files: `plugins/message-bridge/docs/requirements-traceability.md`

- [ ] 23. 架构一致性验证

  **What to do**:
  - 验证实现与架构设计文档的一致性
  - 检查组件边界和接口契约
  - 验证数据流和状态机
  - 生成架构一致性报告

  **Must NOT do**:
  - 不要偏离架构设计文档
  - 不要引入未规划的组件

  **Recommended Agent Profile**:
  - **Category**: `deep`
    - Reason: Architecture compliance verification
  - **Skills**: [`typescript-programmer`]
    - `typescript-programmer`: For architecture validation
  - **Skills Evaluated but Omitted**:
    - `git-master`: Not needed

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 5 (with Tasks 20, 21, 22)
  - **Blocks**: F1-F4
  - **Blocked By**: Tasks 1-19

  **References**:
  - Architecture design document
  - All implemented components

  **Acceptance Criteria**:
  - [ ] All architectural components implemented as designed
  - [ ] Component boundaries respected
  - [ ] Data flows match architecture
  - [ ] State machines implemented correctly
  - [ ] Architecture compliance report generated

  **QA Scenarios**:
  ```
  Scenario: Verify architecture compliance
    Tool: Bash
    Preconditions: Implementation complete
    Steps:
      1. cd plugins/message-bridge
      2. npm run validate:architecture
    Expected Result: Implementation matches architecture design document
    Evidence: .sisyphus/evidence/task-23-architecture-compliance.txt
  ```

  **Commit**: YES
  - Message: `feat(message-bridge): architecture compliance validation`
  - Files: `plugins/message-bridge/validation/architecture/**`

---
## Final Verification Wave

> 4 review agents run in PARALLEL. ALL must APPROVE. Rejection → fix → re-run.

- [ ] F1. **Plan Compliance Audit** — `oracle`
  Read the plan end-to-end. For each "Must Have": verify implementation exists (read file, curl endpoint, run command). For each "Must NOT Have": search codebase for forbidden patterns — reject with file:line if found. Check evidence files exist in .sisyphus/evidence/. Compare deliverables against plan.
  Output: `Must Have [N/N] | Must NOT Have [N/N] | Tasks [N/N] | VERDICT: APPROVE/REJECT`

- [ ] F2. **Code Quality Review** — `unspecified-high`
  Run `tsc --noEmit` + linter + `npm test`. Review all changed files for: `as any`/`@ts-ignore`, empty catches, console.log in prod, commented-out code, unused imports. Check AI slop: excessive comments, over-abstraction, generic names (data/result/item/temp).
  Output: `Build [PASS/FAIL] | Lint [PASS/FAIL] | Tests [N pass/N fail] | Files [N clean/N issues] | VERDICT`

- [ ] F3. **Real Manual QA** — `unspecified-high` (+ `playwright` skill if UI)
  Start from clean state. Execute EVERY QA scenario from EVERY task — follow exact steps, capture evidence. Test cross-task integration (features working together, not isolation). Test edge cases: empty state, invalid input, rapid actions. Save to `.sisyphus/evidence/final-qa/`.
  Output: `Scenarios [N/N pass] | Integration [N/N] | Edge Cases [N tested] | VERDICT`

- [ ] F4. **Scope Fidelity Check** — `deep`
  For each task: read "What to do", read actual diff (git log/diff). Verify 1:1 — everything in spec was built (no missing), nothing beyond spec was built (no creep). Check "Must NOT do" compliance. Detect cross-task contamination: Task N touching Task M's files. Flag unaccounted changes.
  Output: `Tasks [N/N compliant] | Contamination [CLEAN/N issues] | Unaccounted [CLEAN/N files] | VERDICT`

---

## Commit Strategy

- **Wave 1**: `feat(message-bridge): add project scaffolding and config`
- **Wave 2**: `feat(message-bridge): implement core layers`
- **Wave 3**: `feat(message-bridge): implement business actions`
- **Wave 4**: `test(message-bridge): add comprehensive test suite`
- **Wave 5**: `docs(message-bridge): add documentation and final validation`

---

## Success Criteria

### Verification Commands
```bash
cd plugins/message-bridge
npm install
npm run build
npm run typecheck
npm run test:coverage
```

### Final Checklist
- [ ] All PRD requirements implemented and tested
- [ ] Coverage: lines >= 80%, branches >= 70%
- [ ] All "Must Have" present, all "Must NOT Have" absent
- [ ] Architecture design document compliance verified
- [ ] Agent-Executed QA Scenarios all pass
- [ ] Final verification wave all APPROVE

---
