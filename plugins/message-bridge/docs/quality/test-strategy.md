# message-bridge 插件 — 测试验证文档

**Version:** V1.0  
**Date:** 2026-03-06  
**Status:** 评审版  
**Owner:** message-bridge maintainers  
**Related:** `../product/prd.md`, `../architecture/overview.md`, `../design/solution-design.md`  

---

## 1. 范围定义

### 1.1 In Scope

| 范围项 | 说明 |
|--------|------|
| 需求追踪矩阵 | FR-ID 到测试用例的完整映射 |
| 单元测试用例 | 配置、白名单、扁平协议字段、错误处理等模块的独立测试 |
| 集成测试用例 | Mock Gateway + Mock SDK 的模块间交互测试 |
| E2E Smoke 测试 | 端到端完整链路验证 |
| 回归测试策略 | 版本迭代时的回归范围与自动化策略 |
| 质量门槛定义 | 覆盖率、通过率等验收指标 |

### 1.2 Out of Scope

| 范围项 | 说明 |
|--------|------|
| Gateway/Skill Server 测试 | 仅测试插件端，服务端测试由对应团队负责 |
| 性能压测 | 首版仅验证功能正确性，压测为后续任务 |
| 安全渗透测试 | 基础安全检查，深度渗透测试为后续任务 |
| 多平台兼容性测试 | 首版仅验证 OpenCode 本地模式 |

### 1.3 外部依赖

| 依赖项 | 用途 | 测试时处理 |
|--------|------|-----------|
| `@opencode-ai/sdk` | OpenCode 本地 SDK | Mock 或 Stub |
| `ai-gateway` | WebSocket 服务端 | Mock Server |
| `ws` | WebSocket 客户端库 | 直接使用 |
| `jsonc-parser` | JSONC 解析 | 直接使用 |

### 1.4 测试框架基线

| 测试层级 | 推荐框架 | 说明 |
|----------|----------|------|
| Unit / Integration | `node --import tsx/esm --test` (Node 24 内置测试器) | 与当前 `package.json` 脚本一致，避免 TS/ESM 解析偏差 |
| E2E Smoke（协议链路） | `node --import tsx/esm --test` + Mock Gateway/SDK | 当前版本优先验证插件协议链路 |
| E2E（Web UI 场景，后续） | Playwright Test（可选） | 若扩展到浏览器 UI 自动化再引入 |

**统一约束：**
- 本文档示例默认以 `node:test` 风格表达；若示例使用 `describe/it` 仅作为结构化伪代码。
- 新增测试不得引入与主仓库脚本不一致的测试框架依赖（例如 Jest），除非另行评审通过。

### 1.5 脚本矩阵（执行入口）

| 脚本 | 验证范围 | 使用场景 | 前置要求 | 是否门禁 |
|------|----------|----------|----------|----------|
| `pnpm test` | `unit + integration` | 日常开发默认回归；PR 基线自检 | 安装依赖；Node 24+；`pnpm install` 完成 | 是（默认门禁） |
| `pnpm run test:unit` | `tests/unit/*.test.mjs` | 快速验证协议解析、动作路由、错误映射等纯逻辑变更 | 同上 | 是 |
| `pnpm run test:integration` | `tests/integration/*.test.mjs` | 验证模块协作、构建产物与 setup CLI 行为 | 同上 | 是 |
| `pnpm run test:e2e` | `tests/e2e/*.test.mjs` 全量 | 发布前或环境回归，确认真实链路端到端行为 | 本机可用回环端口；允许子进程；已安装 `opencode` CLI | 否（环境敏感） |
| `pnpm run test:e2e:smoke` | 3 个关键场景：`connect-register/chat-stream/permission-roundtrip` | 协议主链路最小闭环验证（推荐在协议变更后执行） | 同 `test:e2e` | 否（环境敏感） |
| `pnpm run test:coverage` | `unit + integration` 覆盖率统计（默认不含 e2e） | 质量门槛检查（行覆盖率阻断） | 同 `pnpm test`；本地可写 `coverage/` 目录 | 是 |
| `pnpm run smoke:e2e` | 启动 mock gateway + `opencode serve`，执行单场景真实栈 smoke（由 `MB_SCENARIO` 控制） | 本地快速复现协议链路问题、产生日志证据 | 安装 `opencode`、`curl`；端口可用；允许子进程 | 否（调试/诊断） |
| `pnpm run debug:e2e` | 启动真实栈并产出调试汇总日志 | 专门用于排查 e2e 失败根因，不作为质量门禁 | 同 `smoke:e2e` | 否（调试工具） |
| `pnpm run logs:fetch -- ...` | 读取/过滤 OpenCode 日志（非测试执行） | 线下问题定位、按 trace/session 回溯 | 可访问日志目录（默认 `$HOME/.local/share/opencode/log`） | 否（辅助工具） |
| `pnpm run verify:core` | 串行执行 `typecheck + test + coverage + pack:check` | 日常开发/PR 的默认门禁 | 满足 `pnpm test` 前置要求 | 是（核心门禁） |
| `pnpm run verify:env` | 环境自检（命令、版本、端口、关键 env）并输出 JSON summary | 发布前快速确认环境能力 | `node/pnpm/opencode/curl` 可执行 | 是（发布前置） |
| `pnpm run verify:opencode-load` | 验证插件是否可被 OpenCode 从包根路径加载并完成初始化 | 发布前/升级 OpenCode 版本后，验证“可加载性” | 安装 `opencode`、`node`；端口可用；允许子进程 | 建议作为发布门禁 |
| `pnpm run verify:release` | 串行执行 `verify:core + verify:env + test:e2e:smoke + verify:opencode-load` | 发布前一次性验收 | 满足上述所有脚本前置要求 | 是（发布门禁） |
| `pnpm run verify:release:dry` | 与 `verify:release` 同链路，仅用于演练 | 发版前预演和环境巡检 | 同 `verify:release` | 否（演练） |

**说明：**
- 默认 CI/本地门禁建议：`pnpm run verify:core`。
- `test:e2e`、`test:e2e:smoke`、`smoke:e2e`、`debug:e2e`、`verify:opencode-load` 都依赖环境能力，不建议并入默认 `pnpm test`。
- `test:coverage` 当前会输出 `coverage_scope=unit+integration`，用于明确统计口径。
- `verify:env` 对端口采用“可回退即通过”策略：目标端口占用但存在可用回退端口时仅告警；仅在无可用回退端口时失败（`ENV_PORT_UNAVAILABLE`）。

### 1.6 发布前检查清单

- [ ] 执行 `pnpm run verify:env`（确认环境能力）
- [ ] 执行 `pnpm run verify:release`（完整发布门禁）
- [ ] 若本次改动涉及协议消息结构、路由字段或事件映射，至少执行并通过 `pnpm run test:e2e:smoke`
- [ ] 保留 `logs/` 目录中的 smoke/加载验证证据，便于回溯发布问题

### 1.7 执行分层

- 日常开发：`pnpm run verify:core`
- 发版验收：`pnpm run verify:release`
- 发版演练：`pnpm run verify:release:dry`

---

## 2. 需求追踪矩阵

### 2.1 FR-MB-01: 网关连接与鉴权

| 测试用例 ID | 用例名称 | 测试类型 | 优先级 | 关联 PRD 章节 |
|-------------|----------|----------|--------|---------------|
| UT-CONN-001 | WebSocket 连接建立 | 单元测试 | P0 | §4.1 |
| UT-CONN-002 | AK/SK 签名生成验证 | 单元测试 | P0 | §4.1 |
| UT-CONN-003 | 连接状态机转换 | 单元测试 | P0 | §4.5 |
| UT-CONN-004 | 指数退避重连算法 | 单元测试 | P0 | §4.5 |
| UT-CONN-005 | 心跳间隔偏差校验 | 单元测试 | P0 | §5 FR-MB-01 |
| INT-CONN-001 | 完整连接流程（含注册） | 集成测试 | P0 | §4.5 |
| E2E-CONN-001 | 网关连接建立与注册 | E2E Smoke | P0 | §4.5 |
| E2E-CONN-002 | 断线重连场景 | E2E Smoke | P0 | §4.5 |

### 2.2 FR-MB-02: 事件上行可扩展机制

| 测试用例 ID | 用例名称 | 测试类型 | 优先级 | 关联 PRD 章节 |
|-------------|----------|----------|--------|---------------|
| UT-EVNT-001 | 白名单精确匹配（message.updated） | 单元测试 | P0 | §5 FR-MB-02 |
| UT-EVNT-002 | 白名单精确匹配（file.edited） | 单元测试 | P0 | §5 FR-MB-02 |
| UT-EVNT-003 | 白名单拒绝路径 | 单元测试 | P0 | §5 FR-MB-02 |
| UT-EVNT-004 | 不支持事件记录 | 单元测试 | P1 | §5 FR-MB-02 |
| INT-EVNT-001 | 事件过滤与透传完整链路 | 集成测试 | P0 | §5 FR-MB-02 |
| E2E-EVNT-001 | 事件上行端到端 | E2E Smoke | P0 | §5 FR-MB-02 |

### 2.3 FR-MB-03: action 下行可扩展机制

| 测试用例 ID | 用例名称 | 测试类型 | 优先级 | 关联 PRD 章节 |
|-------------|----------|----------|--------|---------------|
| UT-ACTN-001 | Action Registry 注册与查找 | 单元测试 | P0 | §5 FR-MB-03 |
| UT-ACTN-002 | 新增 action 不修改核心引擎 | 单元测试 | P0 | §5 FR-MB-03 |
| UT-ACTN-003 | Downstream normalizer 执行 | 单元测试 | P0 | §5 FR-MB-03 |
| UT-ACTN-004 | Action executor 执行 | 单元测试 | P0 | §5 FR-MB-03 |
| UT-ACTN-005 | Action errorMapper 执行 | 单元测试 | P0 | §5 FR-MB-03 |
| INT-ACTN-001 | 自定义 action 扩展验证 | 集成测试 | P1 | §5 FR-MB-03 |

### 2.4 FR-MB-04: 基础 action 支持

| 测试用例 ID | 用例名称 | 测试类型 | 优先级 | 关联 PRD 章节 |
|-------------|----------|----------|--------|---------------|
| UT-CHAT-001 | chat action payload 验证 | 单元测试 | P0 | §5 FR-MB-04 |
| UT-CHAT-002 | chat action 执行 | 单元测试 | P0 | §5 FR-MB-04 |
| UT-SESN-001 | create_session action 验证 | 单元测试 | P0 | §5 FR-MB-04 |
| UT-SESN-002 | close_session action 验证 | 单元测试 | P0 | §5 FR-MB-04 |
| UT-SESN-003 | abort_session 调用 abort | 单元测试 | P0 | §5 FR-MB-05 |
| UT-PERM-001 | permission_reply payload 验证 | 单元测试 | P0 | §5 FR-MB-04 |
| UT-PERM-002 | permission_reply 执行 | 单元测试 | P0 | §5 FR-MB-04 |
| UT-STAT-001 | status_query payload 验证 | 单元测试 | P0 | §5 FR-MB-04 |
| UT-STAT-002 | status_query 执行 | 单元测试 | P0 | §5 FR-MB-04 |
| INT-ACTN-002 | chat 完整链路 | 集成测试 | P0 | §5 FR-MB-04 |
| INT-ACTN-003 | create_session 完整链路 | 集成测试 | P0 | §5 FR-MB-04 |
| INT-ACTN-004 | close_session 完整链路 | 集成测试 | P0 | §5 FR-MB-04 |
| E2E-ACTN-001 | chat action 端到端 | E2E Smoke | P0 | §5 FR-MB-04 |
| E2E-ACTN-002 | create_session 端到端 | E2E Smoke | P0 | §5 FR-MB-04 |
| E2E-ACTN-003 | close_session 端到端 | E2E Smoke | P0 | §5 FR-MB-04 |

### 2.5 FR-MB-05: 关闭语义

| 测试用例 ID | 用例名称 | 测试类型 | 优先级 | 关联 PRD 章节 |
|-------------|----------|----------|--------|---------------|
| UT-CLSE-001 | close_session 映射到 delete | 单元测试 | P0 | §5 FR-MB-05 |
| UT-CLSE-002 | abort_session 映射到 abort | 单元测试 | P0 | §5 FR-MB-05 |
| INT-CLSE-001 | close_session / abort_session 调用验证 | 集成测试 | P0 | §5 FR-MB-05 |
| E2E-CLSE-001 | session lifecycle 语义验证 | E2E Smoke | P0 | §5 FR-MB-05 |

### 2.6 FR-MB-06: 权限回复协议对齐

| 测试用例 ID | 用例名称 | 测试类型 | 优先级 | 关联 PRD 章节 |
|-------------|----------|----------|--------|---------------|
| UT-PERM-003 | response=once 透传到 SDK | 单元测试 | P0 | §5 FR-MB-06 |
| UT-PERM-004 | response=always 透传到 SDK | 单元测试 | P0 | §5 FR-MB-06 |
| UT-PERM-005 | response=reject 透传到 SDK | 单元测试 | P0 | §5 FR-MB-06 |
| UT-PERM-006 | 拒绝 legacy approved 字段 | 单元测试 | P0 | §5 FR-MB-06 |
| UT-PERM-007 | 拒绝 response=allow | 单元测试 | P0 | §5 FR-MB-06 |
| UT-PERM-008 | 拒绝 response=deny | 单元测试 | P0 | §5 FR-MB-06 |
| INT-PERM-001 | permission_reply 完整链路 | 集成测试 | P0 | §5 FR-MB-06 |
| E2E-PERM-001 | permission_reply response-only | E2E Smoke | P0 | §5 FR-MB-06 |

### 2.7 FR-MB-07: Fast Fail

| 测试用例 ID | 用例名称 | 测试类型 | 优先级 | 关联 PRD 章节 |
|-------------|----------|----------|--------|---------------|
| UT-FAIL-001 | 连接态判定 <=100ms | 单元测试 | P0 | §5 FR-MB-07 |
| UT-FAIL-002 | Gateway 不可达返回 GATEWAY_UNREACHABLE | 单元测试 | P0 | §5 FR-MB-07 |
| UT-FAIL-003 | SDK 超时返回 SDK_TIMEOUT | 单元测试 | P0 | §5 FR-MB-07 |
| UT-FAIL-004 | SDK 不可达返回 SDK_UNREACHABLE | 单元测试 | P0 | §5 FR-MB-07 |
| UT-FAIL-005 | 状态到 Fast Fail 错误码映射 | 单元测试 | P0 | §5 FR-MB-07 |
| UT-FAIL-006 | tool_error best effort 发送 | 单元测试 | P0 | §5 FR-MB-07 |
| UT-FAIL-007 | 发送失败记录日志并计数 | 单元测试 | P0 | §5 FR-MB-07 |
| UT-FAIL-008 | 不排队不缓冲 invoke | 单元测试 | P0 | §5 FR-MB-07 |
| INT-FAIL-001 | Fast Fail 完整链路 | 集成测试 | P0 | §5 FR-MB-07 |
| E2E-FAIL-001 | Fast Fail 触发场景 | E2E Smoke | P0 | §5 FR-MB-07 |

### 2.8 FR-MB-08: 注册与状态查询

| 测试用例 ID | 用例名称 | 测试类型 | 优先级 | 关联 PRD 章节 |
|-------------|----------|----------|--------|---------------|
| UT-REGI-001 | register 消息格式 | 单元测试 | P1 | §5 FR-MB-08 |
| UT-REGI-002 | 心跳消息格式 | 单元测试 | P1 | §5 FR-MB-08 |
| UT-STAT-003 | status_response 格式 | 单元测试 | P1 | §5 FR-MB-08 |
| UT-STAT-004 | status_response 不携带 envelope | 单元测试 | P1 | §5 FR-MB-08 |
| UT-STAT-005 | status_response 不透传 sessionId | 单元测试 | P1 | §5 FR-MB-08 |
| INT-REGI-001 | 注册与心跳完整链路 | 集成测试 | P1 | §5 FR-MB-08 |
| INT-STAT-001 | status_query/response 完整链路 | 集成测试 | P1 | §5 FR-MB-08 |
| E2E-REGI-001 | 注册与心跳端到端 | E2E Smoke | P1 | §5 FR-MB-08 |
| E2E-STAT-001 | status_query/response 端到端 | E2E Smoke | P1 | §5 FR-MB-08 |

### 2.9 FR-MB-09: 配置文件获取

| 测试用例 ID | 用例名称 | 测试类型 | 优先级 | 关联 PRD 章节 |
|-------------|----------|----------|--------|---------------|
| UT-CFG-001 | 用户级配置发现 | 单元测试 | P0 | §5 FR-MB-09 |
| UT-CFG-002 | 项目级配置发现 | 单元测试 | P0 | §5 FR-MB-09 |
| UT-CFG-003 | 环境变量配置发现 | 单元测试 | P0 | §5 FR-MB-09 |
| UT-CFG-004 | 配置优先级 env > project > user > default | 单元测试 | P0 | §5 FR-MB-09 |
| UT-CFG-005 | JSONC 注释支持 | 单元测试 | P0 | §5 FR-MB-09 |
| UT-CFG-006 | JSONC 尾逗号支持 | 单元测试 | P0 | §5 FR-MB-09 |
| UT-CFG-007 | config_version=1 校验 | 单元测试 | P0 | §5 FR-MB-09 |
| UT-CFG-008 | 结构化错误输出 | 单元测试 | P0 | §5 FR-MB-09 |
| UT-CFG-009 | 敏感字段脱敏 | 单元测试 | P0 | §5 FR-MB-09 |
| UT-CFG-010 | enabled=false 安全禁用 | 单元测试 | P0 | §5 FR-MB-09 |
| INT-CFG-001 | 配置发现完整链路 | 集成测试 | P0 | §5 FR-MB-09 |
| E2E-CFG-001 | 配置发现端到端 | E2E Smoke | P0 | §5 FR-MB-09 |

### 2.10 Flat Protocol 上下行约束

| 测试用例 ID | 用例名称 | 测试类型 | 优先级 | 关联 PRD 章节 |
|-------------|----------|----------|--------|---------------|
| UT-ENVL-001 | 上行消息不携带 envelope | 单元测试 | P0 | §4.4 |
| UT-ENVL-002 | invoke 使用 welinkSessionId | 单元测试 | P0 | §4.4 |
| UT-ENVL-003 | tool_error 使用扁平路由字段 | 单元测试 | P0 | §4.4 |
| UT-ENVL-004 | status_response 仅含 opencodeOnline | 单元测试 | P0 | §4.4 |
| UT-ENVL-005 | tool_done 作为兼容完成消息恢复发送 | 单元测试 | P0 | §4.4 |
| UT-ENVL-006 | tool_event 不透传 sessionId | 单元测试 | P1 | §4.4 |
| INT-ENVL-001 | 扁平协议完整链路 | 集成测试 | P0 | §4.4 |
| E2E-ENVL-001 | 扁平协议端到端验证 | E2E Smoke | P0 | §4.4 |

---

## 3. 单元测试用例详情

### 3.1 连接层测试 (UT-CONN)

#### UT-CONN-001: WebSocket 连接建立

**前置条件:**
- Mock WebSocket Server 已启动
- 配置对象包含正确的 gateway URL

**测试步骤:**
1. 创建 GatewayConnection 实例
2. 调用 connect() 方法
3. 验证 WebSocket 连接建立
4. 验证连接 URL 包含 ak/ts/nonce/sign 参数

**预期结果:**
- WebSocket 连接成功建立
- 状态从 DISCONNECTED -> CONNECTING -> CONNECTED
- URL 参数格式正确

**测试代码:**
```typescript
describe('UT-CONN-001', () => {
  it('should establish WebSocket connection', async () => {
    const mockServer = new WS('ws://localhost:8081/ws/agent');
    const connection = new GatewayConnection({
      gatewayUrl: 'ws://localhost:8081/ws/agent',
      auth: { ak: 'test-ak', sk: 'test-sk' }
    });
    
    await connection.connect();
    
    expect(connection.state).toBe('CONNECTED');
    mockServer.close();
  });
});
```

---

#### UT-CONN-002: AK/SK 签名生成验证

**前置条件:**
- 已知 AK/SK 值
- 已知 timestamp 和 nonce

**测试步骤:**
1. 调用 AkSkAuth.generateSignature()
2. 使用相同参数手动计算签名
3. 比较两个签名结果

**预期结果:**
- 签名使用 HMAC-SHA256 算法
- 签名字符串格式: `{ak}:{ts}:{nonce}:{signature}`
- 签名结果可验证通过

**测试代码:**
```typescript
describe('UT-CONN-002', () => {
  it('should generate correct HMAC-SHA256 signature', () => {
    const auth = new AkSkAuth({ ak: 'test-ak', sk: 'test-sk' });
    const ts = 1709654400000;
    const nonce = 'random-nonce';
    
    const signature = auth.generateSignature(ts, nonce);
    
    // 手动计算验证
    const expected = crypto.createHmac('sha256', 'test-sk')
      .update(`test-ak:${ts}:${nonce}`)
      .digest('hex');
    
    expect(signature).toBe(expected);
  });
});
```

---

#### UT-CONN-003: 连接状态机转换

**前置条件:**
- GatewayConnection 实例已创建

**测试步骤:**
1. 初始状态检查
2. 调用 connect()，验证 CONNECTING 状态
3. 连接建立后验证 CONNECTED 状态
4. 发送 register 后验证 READY 状态
5. 模拟断开，验证 DISCONNECTED 状态

**预期结果:**
- 状态转换顺序: DISCONNECTED -> CONNECTING -> CONNECTED -> READY -> DISCONNECTED
- 每个状态转换触发相应事件

**测试代码:**
```typescript
describe('UT-CONN-003', () => {
  it('should follow correct state transitions', async () => {
    const states: string[] = [];
    const connection = new GatewayConnection(mockOptions);
    
    connection.on('stateChange', (state) => states.push(state));
    
    await connection.connect();
    expect(states).toContain('CONNECTED');
    
    // 发送 register 后
    await simulateRegister();
    expect(connection.state).toBe('READY');
    
    connection.disconnect();
    expect(connection.state).toBe('DISCONNECTED');
  });
});
```

---

#### UT-CONN-004: 指数退避重连算法

**前置条件:**
- 配置 reconnectBaseMs=1000, reconnectMaxMs=30000

**测试步骤:**
1. 模拟连接失败
2. 记录每次重连的延迟时间
3. 验证延迟符合指数退避公式

**预期结果:**
- 延迟序列: 1s, 2s, 4s, 8s, 16s, 30s, 30s...
- 公式: delay = min(baseMs * 2^attempt, maxMs)

**测试代码:**
```typescript
describe('UT-CONN-004', () => {
  it('should use exponential backoff for reconnection', async () => {
    const delays: number[] = [];
    const connection = new GatewayConnection({
      ...mockOptions,
      reconnectConfig: { baseMs: 1000, maxMs: 30000 }
    });
    
    // 模拟 6 次重连失败
    for (let i = 0; i < 6; i++) {
      const start = Date.now();
      await connection.attemptReconnect();
      delays.push(Date.now() - start);
    }
    
    expect(delays[0]).toBeGreaterThanOrEqual(1000);
    expect(delays[1]).toBeGreaterThanOrEqual(2000);
    expect(delays[2]).toBeGreaterThanOrEqual(4000);
    expect(delays[3]).toBeGreaterThanOrEqual(8000);
    expect(delays[4]).toBeGreaterThanOrEqual(16000);
    expect(delays[5]).toBeGreaterThanOrEqual(30000);
  });
});
```

---

#### UT-CONN-005: 心跳间隔偏差校验

**前置条件:**
- 配置 `heartbeatIntervalMs=30000`
- 使用可控时钟或计时器 mock

**测试步骤:**
1. 启动心跳调度
2. 连续记录 3 次心跳发送时间戳
3. 计算相邻间隔偏差

**预期结果:**
- 心跳发送间隔接近 30000ms
- 每次偏差不超过 ±1000ms

**测试代码:**
```typescript
describe('UT-CONN-005', () => {
  it('should keep heartbeat interval within +/-1s drift', async () => {
    const samples = await collectHeartbeatIntervals({ intervalMs: 30000, count: 3 });
    for (const delta of samples) {
      expect(Math.abs(delta - 30000)).toBeLessThanOrEqual(1000);
    }
  });
});
```

---

### 3.2 事件层测试 (UT-EVNT)

#### UT-EVNT-001: 白名单精确匹配（message.updated）

**前置条件:**
- 白名单配置: `["message.updated", "permission.asked"]`

**测试步骤:**
1. 创建 EventFilter 实例
2. 测试各种事件类型的匹配结果

**预期结果:**
- `message.created` -> 匹配
- `message.updated` -> 匹配
- `message.deleted` -> 匹配
- `permission.request` -> 匹配
- `file.edited` -> 不匹配

**测试代码:**
```typescript
describe('UT-EVNT-001', () => {
  it('should match exact patterns correctly', () => {
    const filter = new EventFilter([
      'message.updated',
      'permission.asked'
    ]);
    
    expect(filter.isAllowed('message.updated')).toBe(true);
    expect(filter.isAllowed('message.created')).toBe(false);
    expect(filter.isAllowed('permission.asked')).toBe(true);
    expect(filter.isAllowed('permission.updated')).toBe(false);
    expect(filter.isAllowed('file.edited')).toBe(false);
  });
});
```

---

#### UT-EVNT-002: 白名单精确匹配（file.edited）

**前置条件:**
- 白名单配置: `["file.edited", "todo.updated"]`

**测试步骤:**
1. 测试精确匹配的事件类型
2. 测试类似但不匹配的事件类型

**预期结果:**
- `file.edited` -> 匹配
- `todo.updated` -> 匹配
- `file.created` -> 不匹配
- `todo.created` -> 不匹配
- `file.edited.extra` -> 不匹配

**测试代码:**
```typescript
describe('UT-EVNT-002', () => {
  it('should match exact patterns correctly', () => {
    const filter = new EventFilter({
      allowlist: ['file.edited', 'todo.updated']
    });
    
    expect(filter.isAllowed('file.edited')).toBe(true);
    expect(filter.isAllowed('todo.updated')).toBe(true);
    expect(filter.isAllowed('file.created')).toBe(false);
    expect(filter.isAllowed('todo.created')).toBe(false);
    expect(filter.isAllowed('file.edited.extra')).toBe(false);
  });
});
```

---

### 3.3 Flat Protocol 测试 (UT-ENVL)

#### UT-ENVL-008: `tool_event` 不携带 `sessionId` 或 `envelope`

**前置条件:**
- `BridgeRuntime` 实例已创建
- `gatewayConnection.send()` 已 mock

**测试步骤:**
1. 将 runtime 状态置为 `READY`
2. 设置允许 `message.*` 的事件白名单
3. 发送一个 `message.delta` 事件
4. 检查上行消息字段

**预期结果:**
- 发送一条 `tool_event`
- 消息仅包含当前扁平协议字段
- `sessionId` 与 `envelope` 均不存在

**测试代码:**
```typescript
describe('UT-ENVL-008', () => {
  it('should forward flat tool_event fields only', async () => {
    const runtime = new BridgeRuntime({ client: {} });
    const sent: unknown[] = [];

    runtime.gatewayConnection = { send: (msg: unknown) => sent.push(msg) };
    runtime.eventFilter = new EventFilter(['message.*']);
    runtime.stateManager.setState('READY');

    await runtime.handleEvent({
      type: 'message.delta',
      properties: { sessionId: 'tool-1' },
      text: 'hello',
    });

    expect(sent).toHaveLength(1);
    expect(sent[0]).toMatchObject({ type: 'tool_event' });
    expect((sent[0] as Record<string, unknown>).sessionId).toBeUndefined();
    expect((sent[0] as Record<string, unknown>).envelope).toBeUndefined();
  });
});
```

---

#### UT-ENVL-010: allowlist 拒绝仅记录日志，不发送上行消息

**前置条件:**
- `BridgeRuntime` 实例已创建
- 日志 sink 与 `gatewayConnection.send()` 已 mock

**测试步骤:**
1. 将 runtime 状态置为 `READY`
2. allowlist 仅允许 `session.idle`
3. 发送一个 `session.created` 事件
4. 检查日志与发送记录

**预期结果:**
- 不发送 `tool_event`
- 记录 `event.rejected_allowlist`
- 不发送 `tool_error`

**测试代码:**
```typescript
describe('UT-ENVL-010', () => {
  it('should log and drop rejected events', async () => {
    const logs: unknown[] = [];
    const sent: unknown[] = [];
    const runtime = new BridgeRuntime({
      client: {
        app: {
          log: async (entry: unknown) => {
            logs.push(entry);
            return true;
          },
        },
      },
    });

    runtime.gatewayConnection = { send: (msg: unknown) => sent.push(msg) };
    runtime.eventFilter = new EventFilter(['session.idle']);
    runtime.stateManager.setState('READY');

    await runtime.handleEvent({ type: 'session.created' });

    expect(sent).toHaveLength(0);
    expect(
      logs.some((entry) => (entry as { body?: { message?: string } }).body?.message === 'event.rejected_allowlist'),
    ).toBe(true);
  });
});
```

---

### 3.4 Fast Fail 测试 (UT-FAIL)

#### UT-FAIL-001: 连接态判定 <=100ms

**前置条件:**
- FastFailDetector 配置 connectionCheckTimeoutMs=100
- Mock 连接状态

**测试步骤:**
1. 模拟 READY 状态，测量判定时间
2. 模拟非 READY 状态，测量判定时间
3. 验证所有判定在 100ms 内完成

**预期结果:**
- 所有状态判定耗时 < 100ms
- READY 状态返回 null（无错误）
- DISCONNECTED/CONNECTING 状态返回 GATEWAY_UNREACHABLE
- CONNECTED（已连通未注册）返回 AGENT_NOT_READY

**测试代码:**
```typescript
describe('UT-FAIL-001', () => {
  it('should complete connection check within 100ms', async () => {
    const detector = new FastFailDetector({
      sdkTimeoutMs: 10000,
      connectionCheckTimeoutMs: 100
    });
    
    const start = Date.now();
    const gatewayReachable = detector.isGatewayReachable('DISCONNECTED' as ConnectionState);
    const duration = Date.now() - start;
    
    expect(duration).toBeLessThan(100);
    expect(gatewayReachable).toBe(false);
    expect(detector.checkReady('READY' as ConnectionState)).toBeNull();
  });
});
```

---

#### UT-FAIL-005: 状态到 Fast Fail 错误码映射

**前置条件:**
- FastFailDetector 实例已创建

**测试步骤:**
1. 测试 DISCONNECTED 状态
2. 测试 CONNECTING 状态
3. 测试 CONNECTED 状态（未注册）
4. 测试 READY 状态

**预期结果:**
- DISCONNECTED -> GATEWAY_UNREACHABLE
- CONNECTING -> GATEWAY_UNREACHABLE
- CONNECTED -> AGENT_NOT_READY
- READY -> null（正常）

**测试代码:**
```typescript
describe('UT-FAIL-005', () => {
  it('should map connection states to expected Fast Fail codes', () => {
    const detector = new FastFailDetector(mockConfig);
    const mapCode = (state: ConnectionState): ErrorCode | null => {
      if (!detector.isGatewayReachable(state)) return 'GATEWAY_UNREACHABLE';
      return detector.checkReady(state)?.code ?? null;
    };
    
    expect(mapCode('DISCONNECTED')).toBe('GATEWAY_UNREACHABLE');
    expect(mapCode('CONNECTING')).toBe('GATEWAY_UNREACHABLE');
    expect(mapCode('CONNECTED')).toBe('AGENT_NOT_READY');
    expect(mapCode('READY')).toBeNull();
  });
});
```

---

### 3.5 配置层测试 (UT-CFG)

#### UT-CFG-004: 配置优先级 env > project > user > default

**前置条件:**
- 设置环境变量 BRIDGE_GATEWAY_URL
- 创建项目级配置文件
- 创建用户级配置文件

**测试步骤:**
1. 调用 ConfigResolver.resolve()
2. 验证使用的配置值来源
3. 依次移除高优先级配置，验证降级

**预期结果:**
- 优先使用环境变量
- 环境变量不存在时使用项目级配置
- 项目级不存在时使用用户级配置
- 都不存在时使用默认值

**测试代码:**
```typescript
describe('UT-CFG-004', () => {
  it('should follow correct config priority', async () => {
    // Setup: env > project > user > default
    process.env.BRIDGE_GATEWAY_URL = 'env-url';
    mockFs.setProjectConfig({ gateway: { url: 'project-url' } });
    mockFs.setUserConfig({ gateway: { url: 'user-url' } });
    
    const resolver = new ConfigResolver();
    
    // Should use env
    let config = await resolver.resolve();
    expect(config.gateway.url).toBe('env-url');
    
    // Remove env, should use project
    delete process.env.BRIDGE_GATEWAY_URL;
    config = await resolver.resolve();
    expect(config.gateway.url).toBe('project-url');
    
    // Remove project, should use user
    mockFs.removeProjectConfig();
    config = await resolver.resolve();
    expect(config.gateway.url).toBe('user-url');
  });
});
```

---

#### UT-CFG-005: JSONC 注释支持

**前置条件:**
- 包含注释的 JSONC 配置文件

**测试步骤:**
1. 读取包含注释的配置文件
2. 调用 ConfigResolver.resolve()
3. 验证解析成功

**预期结果:**
- 单行注释 (`//`) 被正确处理
- 多行注释 (`/* */`) 被正确处理
- 配置值正确解析

**测试代码:**
```typescript
describe('UT-CFG-005', () => {
  it('should parse JSONC with comments', async () => {
    const jsoncContent = `
      {
        // This is a comment
        "config_version": 1,
        /* Multi-line
           comment */
        "enabled": true,
        "gateway": {
          "url": "ws://test.com" // inline comment
        }
      }
    `;
    
    mockFs.setProjectConfigRaw(jsoncContent);
    
    const resolver = new ConfigResolver();
    const config = await resolver.resolve();
    
    expect(config.config_version).toBe(1);
    expect(config.enabled).toBe(true);
    expect(config.gateway.url).toBe('ws://test.com');
  });
});
```

---

### 3.6 Action 测试 (UT-ACTN)

#### UT-ACTN-002: 新增 action 不修改核心引擎

**前置条件:**
- ActionRegistry 实例已创建
- 自定义 Action 类已定义

**测试步骤:**
1. 创建自定义 Action
2. 注册到 ActionRegistry
3. 通过 ActionRouter 路由到自定义 Action
4. 验证核心引擎代码未被修改

**预期结果:**
- 自定义 Action 正常注册和执行
- 核心引擎（ActionRouter）无需修改
- 其他 Action 不受影响

**测试代码:**
```typescript
describe('UT-ACTN-002', () => {
  it('should allow new action without modifying core engine', () => {
    // 自定义 Action
    class CustomAction extends BaseAction {
      readonly name = 'custom_action';
      
      validate(payload: unknown) {
        return { valid: true };
      }
      
      async execute(payload: unknown, context: ActionContext) {
        return { success: true, payload: { custom: true } };
      }
      
      mapError(error: Error, context: ActionContext) {
        return { type: 'tool_error', code: 'SDK_UNREACHABLE', error: error.message, welinkSessionId: context.welinkSessionId };
      }
    }
    
    const registry = new ActionRegistry();
    const customAction = new CustomAction();
    
    // 注册自定义 action
    registry.register(customAction);
    
    // 验证可以通过 registry 获取
    expect(registry.get('custom_action')).toBe(customAction);
    
    // 验证其他 action 不受影响
    expect(registry.get('chat')).toBeDefined();
  });
});
```

---

#### UT-PERM-003: response=once 透传到 SDK

**前置条件:**
- PermissionReplyAction 实例已创建
- Mock SDK `postSessionIdPermissionsPermissionId` 方法

**测试步骤:**
1. 调用 execute() 传入 `response=once`
2. 验证 SDK 被调用时 body.response 仍为 `once`

**预期结果:**
- `response=once` 直接透传，不做中间映射

**测试代码:**
```typescript
describe('UT-PERM-003', () => {
  it('should pass through response=once', async () => {
    const action = new PermissionReplyAction();
    const calls: unknown[] = [];
    const mockSDK = {
      postSessionIdPermissionsPermissionId: async (payload: unknown) => { calls.push(payload); }
    };
    
    await action.execute(
      { permissionId: 'perm-123', toolSessionId: 's-1', response: 'once' },
      { client: mockSDK as any, connectionState: 'READY' } as any
    );
    
    expect(calls[0]).toMatchObject({
      path: { id: 's-1', permissionID: 'perm-123' },
      body: { response: 'once' }
    });
  });
});
```

---

#### UT-SESN-003: abort_session 映射到 abort

**前置条件:**
- AbortSessionAction 实例已创建
- Mock SDK session.abort 方法

**测试步骤:**
1. 调用 execute() 传入 toolSessionId
2. 验证 SDK session.abort 被调用
3. 验证 `close_session` 语义不在此 action 中出现

**预期结果:**
- session.abort 被调用一次
- 不触发 delete 路径

**测试代码:**
```typescript
describe('UT-SESN-003', () => {
  it('should call session.abort for abort_session', async () => {
    const action = new AbortSessionAction();
    let abortCalledWith: unknown = null;
    let abortCalls = 0;
    const mockSDK = {
      session: {
        abort: async (id: string) => { abortCalledWith = id; abortCalls++; }
      }
    };
    
    await action.execute(
      { toolSessionId: 'sess-123' },
      { opencode: mockSDK as any }
    );
    
    expect(abortCalledWith).toBe('sess-123');
    expect(abortCalls).toBe(1);
  });
});
```

---

## 4. 集成测试用例详情

### 4.1 连接集成测试 (INT-CONN)

#### INT-CONN-001: 完整连接流程（含注册）

**前置条件:**
- Mock WebSocket Server 已启动
- 配置包含正确的 AK/SK

**测试步骤:**
1. 启动 Mock Gateway Server
2. 创建 GatewayConnection 并连接
3. 验证 WebSocket 握手
4. 验证 register 消息发送
5. 模拟 Gateway 保持连接（无 register_success）
6. 验证状态变为 READY

**预期结果:**
- WebSocket 连接建立成功
- register 消息格式正确
- 状态最终变为 READY

**测试代码:**
```typescript
describe('INT-CONN-001', () => {
  it('should complete full connection flow with registration', async () => {
    const mockGateway = new MockGatewayServer();
    await mockGateway.start();
    
    const connection = new GatewayConnection({
      gatewayUrl: 'ws://localhost:8888/ws/agent',
      auth: { ak: 'test-ak', sk: 'test-sk' },
      heartbeatIntervalMs: 30000,
      reconnectConfig: { baseMs: 1000, maxMs: 30000 }
    });
    
    await connection.connect();
    
    // 验证 register 消息
    expect(mockGateway.receivedMessages).toContainEqual({
      type: 'register',
      deviceName: expect.any(String),
      os: expect.any(String),
      toolType: 'opencode',
      toolVersion: expect.any(String)
    });
    
    // 验证状态为 READY
    expect(connection.state).toBe('READY');
    
    await mockGateway.stop();
  });
});
```

---

### 4.2 Action 集成测试 (INT-ACTN)

#### INT-ACTN-002: chat 完整链路

**前置条件:**
- Mock Gateway Server 已启动
- Mock SDK Client 已配置
- GatewayConnection 处于 READY 状态

**测试步骤:**
1. Gateway 发送 invoke(chat) 消息
2. 插件接收并路由到 ChatAction
3. ChatAction 调用 SDK session.prompt()
4. 模拟 SDK 返回成功
5. 验证插件会在兼容层发送 tool_done，并保留完成态事件透传

**预期结果:**
- SDK session.prompt 被正确调用
- 发送单个 `tool_done`
- `session.idle` 或其他完成事件仍以 `tool_event` 发送

**测试代码:**
```typescript
describe('INT-ACTN-002', () => {
  it('should complete chat action flow end-to-end', async () => {
    const { gateway, sdk, connection } = await setupIntegrationTest();
    
    // Gateway 发送 invoke
    gateway.send({
      type: 'invoke',
      welinkSessionId: 'sess-123',
      action: 'chat',
      payload: { text: 'Hello', toolSessionId: 'oc-456' }
    });
    
    // 等待处理
    await waitFor(() => sdk.session.prompt.mock.calls.length > 0);
    
    // 验证 SDK 被调用
    expect(sdk.session.prompt).toHaveBeenCalled();
    
    // 模拟 SDK 成功回调
    await simulateSDKSuccess();
    
    // 验证发送了 tool_done
    expect(gateway.receivedMessages.some((msg) => msg.type === 'tool_done')).toBe(true);
  });
});
```

---

### 4.3 Fast Fail 集成测试 (INT-FAIL)

#### INT-FAIL-001: Fast Fail 完整链路

**前置条件:**
- Mock Gateway Server 已启动
- GatewayConnection 处于 DISCONNECTED 状态

**测试步骤:**
1. Gateway 发送 invoke 消息
2. 验证连接态检查在 100ms 内完成
3. 验证 tool_error(GATEWAY_UNREACHABLE) 返回
4. 验证错误包含扁平路由字段

**预期结果:**
- 响应时间 < 100ms
- 错误码为 GATEWAY_UNREACHABLE
- 错误消息使用 `welinkSessionId/toolSessionId` 等扁平字段

**测试代码:**
```typescript
describe('INT-FAIL-001', () => {
  it('should return Fast Fail error within 100ms', async () => {
    const { gateway, connection } = await setupIntegrationTest();
    
    // 断开连接
    await connection.disconnect();
    expect(connection.state).toBe('DISCONNECTED');
    
    const start = Date.now();
    
    // Gateway 发送 invoke
    gateway.send({
      type: 'invoke',
      welinkSessionId: 'sess-123',
      action: 'chat',
      payload: { text: 'Hello' }
    });
    
    // 等待 tool_error
    await waitFor(() => gateway.receivedToolErrors.length > 0);
    const duration = Date.now() - start;
    
    // 验证时间
    expect(duration).toBeLessThan(100);
    
    // 验证错误
    expect(gateway.receivedToolErrors[0]).toMatchObject({
      type: 'tool_error',
      code: 'GATEWAY_UNREACHABLE',
      welinkSessionId: 'sess-123'
    });
  });
});
```

---

## 5. E2E Smoke 测试用例详情

### 5.1 基础链路测试 (E2E-CONN)

#### E2E-CONN-001: 网关连接建立与注册

**前置条件:**
- OpenCode 本地实例运行中 (localhost:54321)
- AI-Gateway 可连接
- 配置文件包含正确的 AK/SK

**测试步骤:**
1. 启动 message-bridge 插件
2. 验证 WebSocket 连接到 Gateway
3. 验证 AK/SK 鉴权成功
4. 验证 register 消息发送
5. 验证心跳消息周期性发送

**预期结果:**
- 连接状态: DISCONNECTED -> CONNECTED -> READY
- register 消息包含 deviceName/os/toolType/toolVersion
- 心跳每 30s 发送一次

**验证方式:**
```bash
# 查看插件日志
$ tail -f ~/.opencode/logs/message-bridge.log

# 预期输出
[INFO] Connecting to gateway: ws://gateway.example.com/ws/agent
[INFO] WebSocket connected
[INFO] Sending register message
[INFO] State changed: CONNECTED -> READY
[INFO] Heartbeat sent (interval: 30000ms)
```

---

#### E2E-CONN-002: 断线重连场景

**前置条件:**
- 插件已连接到 Gateway 并处于 READY 状态

**测试步骤:**
1. 模拟网络断开（如关闭 Gateway 或断开网络）
2. 等待插件检测到断开
3. 观察重连过程
4. 恢复 Gateway/网络
5. 验证重连成功并重新注册

**预期结果:**
- 断开检测时间 < 10s
- 重连使用指数退避
- 重连后生成新的 agentId
- 重新发送 register 消息

**验证方式:**
```bash
# 1. 启动插件并连接
$ npm run dev

# 2. 模拟断开（推荐：停止 Gateway 进程/容器）
$ docker stop ai-gateway   # 或 kill gateway 进程

# 3. 观察日志
[WARN] Connection lost, attempting reconnect (attempt: 1, delay: 1000ms)
[WARN] Connection lost, attempting reconnect (attempt: 2, delay: 2000ms)
...
[INFO] Reconnected successfully
[INFO] New agentId generated: bridge-xxx-xxx
[INFO] Register message sent

# 4. 恢复 Gateway
$ docker start ai-gateway
```

---

### 5.2 Action E2E 测试 (E2E-ACTN)

#### E2E-ACTN-001: chat action 端到端

**前置条件:**
- 插件已注册并处于 READY 状态
- Skill 已创建会话

**测试步骤:**
1. 从 Skill 发送 chat 消息
2. Gateway 转发 invoke(chat) 到插件
3. 插件调用 OpenCode SDK
4. OpenCode 生成响应
5. 插件继续以 tool_event 向 Gateway 透传完成事件
6. Gateway 转发到 Skill

**预期结果:**
- 完整链路时延 < 5s
- compat 层发送单个 tool_done
- 完成态事件仍可被 Gateway/Skill 消费

**验证方式:**
```bash
# Skill 侧发送消息
$ curl -X POST http://skill-server/api/sessions/sess-123/chat \
  -d '{"text": "Hello OpenCode"}'

# 插件日志
[INFO] Received invoke: chat
[INFO] Calling SDK session.prompt
[INFO] SDK response received
[INFO] Forwarding tool_event

# Gateway 日志
[INFO] Forwarded tool_event to Skill-Server
```

---

#### E2E-ACTN-003: close_session 端到端

**前置条件:**
- 存在活跃的 OpenCode 会话
- 插件处于 READY 状态

**测试步骤:**
1. Skill 发送 close_session 请求
2. Gateway 转发 invoke(close_session)
3. 插件调用 OpenCode session.delete()
4. 单独验证 abort_session 走 session.abort()
5. 插件不为 close_session / abort_session 发送 tool_done

**预期结果:**
- close_session 触发 delete
- abort_session 触发 abort
- 两条路径都不发送额外 tool_done

**验证方式:**
```bash
# 插件日志（开启 debug）
[DEBUG] Received invoke: close_session
[DEBUG] Calling session.delete with toolSessionId: oc-xxx
[DEBUG] session.delete completed successfully
[DEBUG] No compat.tool_done.* log expected for close_session
```

---

### 5.3 Permission Reply E2E 测试 (E2E-PERM)

#### E2E-PERM-001: permission_reply response-only

**前置条件:**
- OpenCode 发起权限请求
- 插件处于 READY 状态

**测试步骤:**
1. 测试 canonical response 路径
   - Skill 发送 permission_reply with `response=once`
   - 验证 SDK 收到 `response=once`

2. 测试 canonical response 路径
   - Skill 发送 permission_reply with `response=always`
   - 验证 SDK 收到 `response=always`

3. 测试 canonical response 路径
   - Skill 发送 permission_reply with `response=reject`
   - 验证 SDK 收到 `response=reject`

4. 测试 legacy 字段拒绝路径
   - Skill 发送 permission_reply with `approved=true`
   - 验证插件返回 tool_error（INVALID_PAYLOAD）

**预期结果:**
- `response=once|always|reject` 均可成功执行
- legacy `approved` 字段被拒绝

**验证方式:**
```bash
# 测试 response=once
curl -X POST http://gateway/api/permission-reply \
  -d '{"permissionId": "perm-1", "toolSessionId": "ses-1", "response": "once"}'
# 插件日志: [INFO] action.permission_reply.started ... response=once

# 测试 legacy approved（应失败）
curl -X POST http://gateway/api/permission-reply \
  -d '{"permissionId": "perm-2", "approved": true}'
# 预期: 返回 tool_error / INVALID_PAYLOAD
```

---

### 5.4 Fast Fail E2E 测试 (E2E-FAIL)

#### E2E-FAIL-001: Fast Fail 触发场景

**前置条件:**
- 插件未连接或处于非 READY 状态

**测试步骤:**
1. 插件启动但 Gateway 不可达
2. Skill 发送 invoke 请求
3. 验证 100ms 内返回 tool_error
4. 验证错误码为 GATEWAY_UNREACHABLE

**预期结果:**
- 响应时间 <= 100ms
- 错误码: GATEWAY_UNREACHABLE
- 错误消息使用扁平字段

**验证方式:**
```bash
# 1. 插件启动但 Gateway 关闭
$ npm run dev
[WARN] Failed to connect to gateway, will retry...

# 2. Skill 发送请求并计时
$ time curl -X POST http://skill-server/api/invoke \
  -d '{"action": "chat", "payload": {"text": "test"}}'
# 响应时间应 < 100ms

# 3. 查看错误响应
{
  "type": "tool_error",
  "code": "GATEWAY_UNREACHABLE",
  "welinkSessionId": "sess-123",
  "error": "Gateway connection is not active"
}
```

---

## 6. 回归测试策略

### 6.1 回归范围定义

| 变更类型 | 回归测试范围 | 自动化级别 |
|----------|-------------|-----------|
| 配置层修改 | UT-CFG-*, INT-CFG-* | 全自动 |
| 连接层修改 | UT-CONN-*, INT-CONN-*, E2E-CONN-* | 全自动 |
| 事件层修改 | UT-EVNT-*, INT-EVNT-*, E2E-EVNT-* | 全自动 |
| Action 层修改 | UT-ACTN-*, UT-CHAT-*, UT-SESN-*, UT-PERM-*, UT-STAT-* | 全自动 |
| 错误层修改 | UT-FAIL-*, INT-FAIL-*, E2E-FAIL-* | 全自动 |
| Flat Protocol 修改 | UT-ENVL-*, INT-ENVL-*, E2E-ENVL-* | 全自动 |
| SDK 版本升级 | 全部 E2E 测试 | 全自动 + 手动验证 |
| Gateway 协议变更 | 全部测试 | 全自动 + 手动验证 |

### 6.2 自动化测试矩阵

```
CI Pipeline (Pull Request):
├── Unit Tests (所有 UT-*)
│   └── 通过门槛: 100% 通过
├── Integration Tests (所有 INT-*)
│   └── 通过门槛: 100% 通过
└── Coverage Check
    ├── Lines >= 80%
    └── Branches >= 70%

Nightly Build:
├── 全部单元测试
├── 全部集成测试
└── E2E Smoke 测试 (所有 E2E-*)
    └── 通过门槛: 100% 通过

Release Build:
├── 全部自动化测试
└── 手动验证清单
    ├── E2E-CONN-002 (断线重连)
    ├── E2E-PERM-001 (response-only)
    └── E2E-FAIL-001 (Fast Fail 时延)
```

### 6.3 关键路径回归

**关键路径定义:**
1. 连接建立 -> 注册 -> READY
2. invoke(chat) -> SDK.chat -> tool_done + tool_event/session.idle
3. 连接断开 -> 重连 -> 重新注册

**回归频率:**
- 每次代码提交: 关键路径单元测试
- 每次 PR: 全部单元 + 集成测试
- 每日: 全部自动化测试
- 每次发布: 全部测试 + 手动验证

---

## 7. 质量门槛

### 7.1 覆盖率要求

> 统一口径：本节与 `solution-design.md` 质量门槛保持一致，作为 PR 阻塞标准。
> 覆盖率由 `c8` 生成（`node --test` + lcov），`lines >= 80%` 为硬门禁，`branches >= 70%` 暂作为观测项（非阻塞）。

| 层级 | Lines Coverage | Branches Coverage | Functions Coverage |
|------|----------------|-------------------|-------------------|
| 单元测试 | ≥ 80% | ≥ 70% | ≥ 80% |
| 集成测试 | ≥ 60% | ≥ 50% | ≥ 60% |
| E2E Smoke | ≥ 40% | ≥ 30% | ≥ 40% |
| **总计（PR 阻塞口径）** | **≥ 80%** | **观测项（目标 ≥ 70%）** | **≥ 80%** |

### 7.2 测试通过率

| 测试类型 | 通过门槛 | 失败处理 |
|----------|----------|----------|
| 单元测试 | 100% | 阻塞 PR 合并 |
| 集成测试 | 100% | 阻塞 PR 合并 |
| E2E Smoke | 100% | 阻塞发布 |

### 7.3 性能门槛

| 指标 | 目标值 | 测试方法 |
|------|--------|----------|
| Fast Fail 响应时延 | ≤ 100ms | E2E-FAIL-001 |
| 连接建立时间 | ≤ 1s | E2E-CONN-001 |
| 心跳间隔偏差 | ≤ ±1s | UT-CONN-005 |
| 重连退避偏差 | ≤ ±100ms | UT-CONN-004 |

### 7.4 代码质量门槛

| 检查项 | 工具 | 通过标准 |
|--------|------|----------|
| TypeScript 类型检查 | tsc | 0 错误 |
| ESLint（可选，后续接入） | eslint | 接入后 0 错误，警告需评审 |
| 依赖安全扫描 | npm audit | 0 高危漏洞 |
| 代码重复率（可选，后续接入） | jscpd | 接入后 < 3% |

---

## 8. 测试数据管理

### 8.1 Mock 数据规范

#### Mock Gateway Server

```typescript
// tests/mocks/MockGatewayServer.ts

export class MockGatewayServer {
  receivedMessages: any[] = [];
  receivedToolDone: any[] = [];
  receivedToolErrors: any[] = [];
  
  async start(port: number = 8888): Promise<void>;
  async stop(): Promise<void>;
  
  // 发送 invoke 到插件
  sendInvoke(payload: InvokePayload): void;
  
  // 发送 status_query 到插件
  sendStatusQuery(): void;
  
  // 等待特定消息
  waitForMessage(type: string, timeout?: number): Promise<any>;
}
```

#### Mock SDK Client

```typescript
// tests/mocks/MockOpenCodeSDK.ts

export class MockOpenCodeSDK {
  session = {
    create: async (...args: unknown[]) => undefined,
    chat: async (...args: unknown[]) => undefined,
    abort: async (...args: unknown[]) => undefined,
    delete: async (...args: unknown[]) => undefined
  };
  
  permission = {
    reply: async (...args: unknown[]) => undefined
  };
  
  // 模拟成功响应
  mockSuccess(method: string, result: any): void;
  
  // 模拟超时
  mockTimeout(method: string): void;
  
  // 模拟错误
  mockError(method: string, error: Error): void;
}
```

### 8.2 测试配置文件

```jsonc
// tests/fixtures/configs/valid-config.jsonc
{
  "config_version": 1,
  "enabled": true,
  "gateway": {
    "url": "ws://localhost:8888/ws/agent",
    "heartbeatIntervalMs": 30000,
    "reconnect": {
      "baseMs": 1000,
      "maxMs": 30000
    }
  },
  "sdk": {
    "timeoutMs": 10000
  },
  "auth": {
    "ak": "test-ak",
    "sk": "test-sk"
  },
  "events": {
    "allowlist": [
      "message.updated",
      "message.part.updated",
      "message.part.delta",
      "message.part.removed",
      "session.status",
      "session.idle",
      "session.updated",
      "session.error",
      "permission.updated",
      "permission.asked",
      "question.asked"
    ]
  }
}
```

---

## 9. 附录

### 9.1 测试用例汇总表

| 测试类型 | 用例数量 | P0 数量 | P1 数量 |
|----------|----------|---------|---------|
| 单元测试 | 43 | 36 | 7 |
| 集成测试 | 15 | 12 | 3 |
| E2E Smoke | 14 | 11 | 3 |
| **总计** | **72** | **59** | **13** |

### 9.2 PRD 追踪矩阵汇总

| PRD 章节 | 功能需求 | 单元测试 | 集成测试 | E2E Smoke |
|----------|----------|----------|----------|-----------|
| §5 FR-MB-01 | 网关连接与鉴权 | 5 | 1 | 2 |
| §5 FR-MB-02 | 事件上行可扩展 | 4 | 1 | 1 |
| §5 FR-MB-03 | action 下行可扩展 | 5 | 1 | 0 |
| §5 FR-MB-04 | 基础 action 支持 | 8 | 3 | 3 |
| §5 FR-MB-05 | 关闭语义 | 2 | 1 | 1 |
| §5 FR-MB-06 | 权限回复协议对齐 | 6 | 1 | 1 |
| §5 FR-MB-07 | Fast Fail | 8 | 1 | 1 |
| §5 FR-MB-08 | 注册与状态查询 | 5 | 2 | 2 |
| §5 FR-MB-09 | 配置文件获取 | 10 | 1 | 1 |
| §4.4 | Flat Protocol 规范 | 11 | 1 | 1 |

### 9.3 参考文档

- [prd.md](../product/prd.md) — 需求基线
- [overview.md](../architecture/overview.md) — 架构设计
- [solution-design.md](../design/solution-design.md) — 方案设计
- [AGENTS.md](../AGENTS.md) — 文档约束

### 9.4 文档变更记录

| 版本 | 日期 | 变更内容 |
|------|------|----------|
| v1.0 | 2026-03-06 | 初始版本，基于 PRD v1.4 建立完整测试追踪矩阵 |

---

**文档结束**
