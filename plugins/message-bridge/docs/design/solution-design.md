# message-bridge 插件 — 方案设计文档

**Version:** V1.0  
**Date:** 2026-03-06  
**Status:** 评审版  
**Owner:** message-bridge maintainers  
**Related:** `../product/prd.md`, `../architecture/overview.md`, `./implementation-plan.md`  

---

## 1. 范围定义

### 1.1 In Scope

| 范围项 | 说明 |
|--------|------|
| 模块拆分与接口定义 | 核心模块的 TypeScript 接口与实现规范 |
| 错误处理策略 | Fast Fail 实现、错误码映射、脱敏日志规范 |
| 配置策略 | 多源配置发现、校验、热更新机制 |
| Action Registry 扩展机制 | 动态 action 注册与执行框架 |
| SDK 对齐策略 | 当前兼容层与目标态收敛计划 |
| 测试策略 | 单元/集成/E2E 测试框架与验收标准 |
| 实施计划 | 里程碑划分、交付物、验收标准 |

### 1.2 Out of Scope

| 范围项 | 说明 |
|--------|------|
| Gateway/Skill Server 改造 | 仅实现插件端，不涉及服务端业务代码修改 |
| 服务端幂等去重 | 由服务端承担，插件不实现 |
| 监控告警平台 | 后续迭代接入，首版仅输出日志指标 |
| 多平台 adapter | 首版仅支持 OpenCode 本地模式 |

### 1.3 外部依赖

| 依赖项 | 版本 | 说明 |
|--------|------|------|
| `@opencode-ai/sdk` | ^1.2.15 | OpenCode 本地 SDK，提供 session/chat/event 能力 |
| `ai-gateway` | 当前部署版 | WebSocket 服务端，负责 AK/SK 验证与消息中继 |
| `ws` | ^8.x | WebSocket 客户端库 |
| `jsonc-parser` | 最新 | JSONC 解析（支持注释与尾逗号） |

---

## 2. 模块拆分与接口定义

### 2.1 模块依赖关系

```
┌─────────────────────────────────────────────────────────────────┐
│                     MessageBridgePlugin                         │
│                   (插件生命周期管理)                              │
└──────────────┬────────────────────────────────┬─────────────────┘
               │                                │
        ┌──────▼──────┐                  ┌──────▼──────┐
        │ ConfigLayer │                  │  CoreLayer  │
        │   (配置层)   │                  │   (核心层)   │
        └──────┬──────┘                  └──────┬──────┘
               │                                │
               │         ┌──────────────────────┼──────────────┐
               │         │                      │              │
        ┌──────▼──────┐  │  ┌──────────────┐   │       ┌──────▼──────┐
        │ Config      │  │  │  Connection  │   │       │   Action    │
        │ Resolver    │  │  │    Layer     │   │       │   Layer     │
        └─────────────┘  │  └──────────────┘   │       └─────────────┘
                         │                     │
                  ┌──────▼──────┐       ┌──────▼──────┐
                  │   Event     │       │   Error     │
                  │   Layer     │       │   Layer     │
                  └─────────────┘       └─────────────┘
```

### 2.2 配置层接口

#### 2.2.1 ConfigResolver

```typescript
// src/config/types.ts

export interface BridgeConfig {
  config_version?: number;          // 默认: 1
  enabled?: boolean;                // 默认: true，false 时安全禁用插件
  gateway?: GatewayConfig;          // 默认见 GatewayConfig
  sdk?: SDKConfig;                  // 默认见 SDKConfig
  auth: AuthConfig;                 // 必填（仅 auth.ak 和 auth.sk 为必填字段）
  events?: EventConfig;             // 默认: PRD 默认白名单
  logging?: LoggingConfig;          // 可选
}

export interface GatewayConfig {
  url?: string;                     // 默认: ws://localhost:8081/ws/agent
  deviceName?: string;              // 默认: Local Machine
  toolType?: string;                // 默认: opencode
  toolVersion?: string;             // 默认: 1.0.0
  heartbeatIntervalMs?: number;     // 默认: 30000
  reconnect?: ReconnectConfig;      // 默认见 ReconnectConfig
  ping?: PingConfig;                // 默认见 PingConfig
}

export interface ReconnectConfig {
  baseMs?: number;                  // 默认: 1000
  maxMs?: number;                   // 默认: 30000
  exponential?: boolean;            // 默认: true
}

export interface PingConfig {
  intervalMs?: number;              // 默认: 30000
  pongTimeoutMs?: number;           // 默认: 10000
}

export interface SDKConfig {
  timeoutMs?: number;               // 默认: 10000
}

export interface AuthConfig {
  ak: string;                       // 必填（当 enabled !== false 时）
  sk: string;                       // 必填（当 enabled !== false 时）
}

export interface EventConfig {
  allowlist?: string[];             // 默认: ['message.*', 'permission.*', ...]
}

export interface LoggingConfig {
  level?: 'debug' | 'info' | 'warn' | 'error';  // 默认: info
  structured?: boolean;             // 默认: true
}
```

#### 2.2.2 ConfigResolver 实现

```typescript
// src/config/ConfigResolver.ts

export class ConfigResolver {
  private readonly workspaceRoot: string;
  
  constructor(workspaceRoot?: string) {
    this.workspaceRoot = workspaceRoot ?? process.cwd();
  }

  /**
   * 按优先级加载配置
   * 优先级: env > project > user > default
   */
  async resolve(): Promise<BridgeConfig>;
  
  /**
   * 获取配置源路径（用于调试）
   */
  getConfigSource(): ConfigSource;
}

export type ConfigSource = 
  | { type: 'env'; path: string }
  | { type: 'project'; path: string }
  | { type: 'user'; path: string }
  | { type: 'default' };
```

### 2.3 连接层接口

#### 2.3.1 GatewayConnection

```typescript
// src/connection/GatewayConnection.ts

export interface GatewayConnectionOptions {
  gatewayUrl: string;
  auth: AuthConfig;
  heartbeatIntervalMs: number;
  reconnectConfig: ReconnectConfig;
  pingConfig?: PingConfig;
}

export type ConnectionState = 
  | 'DISCONNECTED' 
  | 'CONNECTING' 
  | 'CONNECTED' 
  | 'READY';

export class GatewayConnection extends EventEmitter {
  readonly state: ConnectionState;
  readonly localAgentId: string;     // 本地生成的 agentId
  
  constructor(options: GatewayConnectionOptions);
  
  /**
   * 建立 WebSocket 连接并完成注册
   */
  connect(): Promise<void>;
  
  /**
   * 发送消息到 Gateway
   * 非 READY 状态时抛出错误
   */
  send(message: GatewayMessage): void;
  
  /**
   * 断开连接
   */
  disconnect(): Promise<void>;
  
  // 事件
  on(event: 'stateChange', listener: (state: ConnectionState) => void): this;
  on(event: 'message', listener: (message: GatewayMessage) => void): this;
  on(event: 'error', listener: (error: Error) => void): this;
}
```

#### 2.3.2 StateManager

```typescript
// src/connection/StateManager.ts

export class StateManager {
  private state: ConnectionState = 'DISCONNECTED';
  private localAgentId: string | null = null;
  
  /**
   * 生成新的本地 agentId
   * 格式: bridge-{uuid}
   */
  generateAgentId(): string;
  
  /**
   * 获取当前 agentId
   */
  getAgentId(): string | null;
  
  /**
   * 状态转换
   */
  transition(to: ConnectionState): void;
  
  /**
   * 当前状态检查
   */
  isReady(): boolean;
  
  /**
   * 重置（连接重建时调用）
   */
  reset(): void;
}
```

### 2.4 事件层接口

#### 2.4.1 EventFilter

```typescript
// src/event/EventFilter.ts

export interface EventFilterConfig {
  allowlist: string[];              // 如 ["message.*", "permission.*"]
}

export class EventFilter {
  constructor(config: EventFilterConfig);
  
  /**
   * 检查事件是否允许通过
   * 支持前缀通配符 (如 "message.*") 和精确匹配
   */
  isAllowed(eventType: string): boolean;
  
  /**
   * 获取匹配模式（用于调试）
   */
  getMatchPattern(eventType: string): string | null;
}
```

#### 2.4.2 EnvelopeBuilder

```typescript
// src/event/EnvelopeBuilder.ts

export interface Envelope {
  version: string;                  // "1.0"
  messageId: string;                // UUID v4
  timestamp: number;                // Unix timestamp (ms)
  source: string;                   // "message-bridge"
  agentId: string;                  // 本地 agentId
  sessionId?: string;               // 业务 sessionId（可选）
  sequenceNumber: number;           // 递增序号
  sequenceScope: 'session' | 'global';
}

export class EnvelopeBuilder {
  private sequenceCounters: Map<string, number> = new Map();
  
  constructor(private agentId: string);
  
  /**
   * 构建 envelope
   */
  build(sessionId?: string): Envelope;
  
  /**
   * 获取下一个 sequence number
   */
  private nextSequence(scope: string): number;
  
  /**
   * 重置指定 scope 的计数器
   */
  resetSequence(scope: string): void;
}
```

### 2.5 Action 层接口

#### 2.5.1 Action 基类与 Registry

```typescript
// src/action/BaseAction.ts

export interface ActionContext {
  sessionId?: string;
  toolSessionId?: string;
  opencode: OpenCodeSDK;
  envelopeBuilder: EnvelopeBuilder;
}

export interface ValidationResult {
  valid: boolean;
  errors?: string[];
}

export interface ActionResult {
  success: boolean;
  payload?: unknown;
  error?: ToolErrorPayload;
}

export abstract class BaseAction {
  abstract readonly name: string;
  
  /**
   * 验证 payload
   */
  abstract validate(payload: unknown): ValidationResult;
  
  /**
   * 执行 action
   */
  abstract execute(payload: unknown, context: ActionContext): Promise<ActionResult>;
  
  /**
   * 错误映射
   */
  abstract mapError(error: Error, context: ActionContext): ToolErrorPayload;
}
```

```typescript
// src/action/ActionRegistry.ts

export class ActionRegistry {
  private actions: Map<string, BaseAction> = new Map();
  
  /**
   * 注册 action
   */
  register(action: BaseAction): void;
  
  /**
   * 获取 action
   */
  get(name: string): BaseAction | undefined;
  
  /**
   * 检查 action 是否存在
   */
  has(name: string): boolean;
  
  /**
   * 获取所有已注册 action 名称
   */
  list(): string[];
}
```

#### 2.5.2 内置 Actions

```typescript
// src/action/ChatAction.ts

export interface ChatPayload {
  text: string;
  toolSessionId?: string;
}

export class ChatAction extends BaseAction {
  readonly name = 'chat';
  
  validate(payload: unknown): ValidationResult;
  async execute(payload: ChatPayload, context: ActionContext): Promise<ActionResult>;
  mapError(error: Error, context: ActionContext): ToolErrorPayload;
}
```

```typescript
// src/action/CreateSessionAction.ts

export interface CreateSessionPayload {
  // 根据 SDK 定义
  [key: string]: unknown;
}

export class CreateSessionAction extends BaseAction {
  readonly name = 'create_session';
  
  validate(payload: unknown): ValidationResult;
  async execute(payload: CreateSessionPayload, context: ActionContext): Promise<ActionResult>;
  mapError(error: Error, context: ActionContext): ToolErrorPayload;
}
```

```typescript
// src/action/CloseSessionAction.ts

export interface CloseSessionPayload {
  toolSessionId: string;
}

export class CloseSessionAction extends BaseAction {
  readonly name = 'close_session';
  
  validate(payload: unknown): ValidationResult;
  async execute(payload: CloseSessionPayload, context: ActionContext): Promise<ActionResult>;
  mapError(error: Error, context: ActionContext): ToolErrorPayload;
  
  /**
   * 注意：close_session 固定映射到 session.abort，不执行 delete
   */
}
```

```typescript
// src/action/PermissionReplyAction.ts

export interface PermissionReplyTarget {
  permissionId: string;
  toolSessionId?: string;
  response: 'allow' | 'always' | 'deny';
}

export interface PermissionReplyCompat {
  permissionId: string;
  toolSessionId?: string;
  approved: boolean;
}

export type PermissionReplyPayload = PermissionReplyTarget | PermissionReplyCompat;

export class PermissionReplyAction extends BaseAction {
  readonly name = 'permission_reply';
  
  validate(payload: unknown): ValidationResult;
  async execute(payload: PermissionReplyPayload, context: ActionContext): Promise<ActionResult>;
  mapError(error: Error, context: ActionContext): ToolErrorPayload;
  
  /**
   * 兼容字段映射
   * approved=true -> allow
   * approved=false -> deny
   * 
   * SDK 映射
   * allow -> once
   * always -> always
   * deny -> reject
   */
  private normalizePayload(payload: PermissionReplyPayload): PermissionReplyTarget;
}
```

```typescript
// src/action/StatusQueryAction.ts

export interface StatusQueryPayload {
  sessionId?: string;               // 可选
}

export interface StatusResponsePayload {
  type: 'status_response';
  opencodeOnline: boolean;
  sessionId?: string;               // 按请求透传
  envelope: Envelope;
}

export class StatusQueryAction extends BaseAction {
  readonly name = 'status_query';
  
  validate(payload: unknown): ValidationResult;
  async execute(payload: StatusQueryPayload, context: ActionContext): Promise<ActionResult>;
  mapError(error: Error, context: ActionContext): ToolErrorPayload;
}
```

### 2.6 错误层接口

```typescript
// src/error/types.ts

export type ErrorCode =
  | 'GATEWAY_UNREACHABLE'
  | 'SDK_TIMEOUT'
  | 'SDK_UNREACHABLE'
  | 'AGENT_NOT_READY'
  | 'INVALID_PAYLOAD'
  | 'UNSUPPORTED_ACTION';

export interface ToolErrorPayload {
  type: 'tool_error';
  sessionId?: string;
  code: ErrorCode;
  error: string;
  envelope: Envelope;
}
```

```typescript
// src/error/FastFailDetector.ts

export interface FastFailConfig {
  sdkTimeoutMs: number;             // SDK 调用超时，默认 10000
  connectionCheckTimeoutMs: number; // 连接态判定时限，默认 100
}

export class FastFailDetector {
  constructor(private config: FastFailConfig);
  
  /**
   * 检查 Gateway 是否可达
   */
  isGatewayReachable(connectionState: ConnectionState): boolean;
  
  /**
   * 检查是否处于 READY 状态
   * 非 READY 时返回 AGENT_NOT_READY 错误
   */
  checkReady(connectionState: ConnectionState): ToolErrorPayload | null;
  
  /**
   * 构建 Fast Fail 错误
   */
  buildError(
    code: ErrorCode,
    message: string,
    sessionId: string | undefined,
    envelopeBuilder: EnvelopeBuilder
  ): ToolErrorPayload;
}
```

```typescript
// src/error/ErrorMapper.ts

export class ErrorMapper {
  /**
   * 将 SDK 错误映射到标准错误码
   */
  static fromSDKError(error: Error): ErrorCode;
  
  /**
   * 将验证错误映射到标准错误码
   */
  static fromValidationError(errors: string[]): ErrorCode;
  
  /**
   * 获取错误码对应的 HTTP 状态码
   * 仅用于 REST 调试/诊断接口；WebSocket 主链路不依赖 HTTP 状态码
   */
  static toHttpStatus(code: ErrorCode): number;
}
```

---

## 3. 错误处理策略

### 3.1 Fast Fail 机制实现

#### 3.1.1 不可达判定

```typescript
// Fast Fail 判定逻辑

class FastFailHandler {
  private readonly SDK_TIMEOUT_MS = 10000;  // 可配置
  private readonly CONNECTION_CHECK_TIMEOUT_MS = 100; // 可配置
  
  async handleInvoke(invoke: InvokeMessage): Promise<void> {
    const startTime = Date.now();
    const checkDeadline = startTime + this.CONNECTION_CHECK_TIMEOUT_MS;
    
    const isConnectionCheckTimedOut = () => Date.now() > checkDeadline;
    
    // 1. <=100ms 内完成连接态判定：连接不可达优先返回 GATEWAY_UNREACHABLE
    if (isConnectionCheckTimedOut()) {
      const error = this.buildToolError({
        code: 'AGENT_NOT_READY',
        error: `Connection state check timeout after ${this.CONNECTION_CHECK_TIMEOUT_MS}ms`,
        sessionId: invoke.sessionId
      });
      this.sendToolError(error);
      return;
    }
    
    if (this.connection.state === 'DISCONNECTED' || this.connection.state === 'CONNECTING') {
      const error = this.buildToolError({
        code: 'GATEWAY_UNREACHABLE',
        error: 'Gateway connection is not active',
        sessionId: invoke.sessionId
      });
      this.bestEffortSend(error);
      return;
    }
    
    // 2. 已连通但尚未完成注册
    if (this.connection.state === 'CONNECTED') {
      const error = this.buildToolError({
        code: 'AGENT_NOT_READY',
        error: 'Agent not ready, cannot process invoke',
        sessionId: invoke.sessionId
      });
      this.sendToolError(error);
      return;
    }
    
    this.metrics.observe('fast_fail.connection_check_ms', Date.now() - startTime);
    
    // 3. READY 状态下正常处理流程...
  }
  
  private bestEffortSend(error: ToolErrorPayload): void {
    try {
      this.connection.send(error);
    } catch (e) {
      // 发送失败，记录本地结构化日志并累计错误计数
      this.logger.error('Failed to send tool_error', {
        error: error.code,
        sessionId: error.sessionId,
        sendError: e.message
      });
      this.metrics.increment('tool_error_send_failed', { code: error.code });
    }
  }
}
```

#### 3.1.2 行为准则

| 场景 | 行为 | 说明 |
|------|------|------|
| Gateway 不可达 | 立即返回 `tool_error(GATEWAY_UNREACHABLE)` | best effort 发送，失败则本地日志+计数 |
| OpenCode 不可达 | 立即返回 `tool_error(SDK_UNREACHABLE)` | SDK 调用超时或连接异常 |
| 非 READY 状态 | 立即返回 `tool_error(AGENT_NOT_READY)` | 连接建立但未完成注册 |
| 验证失败 | 立即返回 `tool_error(INVALID_PAYLOAD)` | payload 不符合 schema |
| 不支持的 action | 立即返回 `tool_error(UNSUPPORTED_ACTION)` | action 未在 registry 中注册 |
| SDK 超时 | 立即返回 `tool_error(SDK_TIMEOUT)` | 超过 `sdkTimeoutMs` 未响应 |

#### 3.1.3 错误响应格式

所有错误响应必须符合以下结构：

```json
{
  "type": "tool_error",
  "sessionId": "sess_123",
  "code": "SDK_TIMEOUT",
  "error": "SDK call timeout after 10000ms",
  "envelope": {
    "version": "1.0",
    "messageId": "msg_456",
    "timestamp": 1709654400000,
    "source": "message-bridge",
    "agentId": "bridge-uuid-123",
    "sessionId": "sess_123",
    "sequenceNumber": 42,
    "sequenceScope": "session"
  }
}
```

### 3.2 脱敏日志规范

#### 3.2.1 敏感字段处理

| 字段 | 日志处理方式 | 说明 |
|------|-------------|------|
| `sk` (Secret Key) | **绝不记录** | 任何日志级别均不包含 |
| `ak` (Access Key) | 作为标识符记录 | 非敏感，可用于追踪 |
| 签名原文 | 仅记录是否 present | 不记录具体内容 |
| payload | 最小化记录 | 仅记录用于路由的字段 |

#### 3.2.2 结构化日志格式

```typescript
interface LogEntry {
  timestamp: string;                // ISO 8601
  level: 'debug' | 'info' | 'warn' | 'error';
  component: string;                // 模块名称
  message: string;
  context: {
    sessionId?: string;
    messageId?: string;
    agentId?: string;
    action?: string;
    eventType?: string;
    // 不包含敏感信息
  };
  error?: {
    code: string;
    message: string;
    stack?: string;
  };
}
```

#### 3.2.3 日志级别定义

| 级别 | 使用场景 | 示例 |
|------|----------|------|
| `debug` | 详细调试信息 | WebSocket 帧收发、心跳详情 |
| `info` | 关键生命周期节点 | 连接建立、注册成功、action 完成 |
| `warn` | 可恢复错误 | 短暂连接断开、重连中 |
| `error` | 需关注的问题 | 认证失败、配置错误、无法恢复的错误 |

---

## 4. 配置策略

### 4.1 配置发现机制

#### 4.1.1 配置源优先级

```
优先级（高 -> 低）:

1. 环境变量 (BRIDGE_*)
   如: BRIDGE_GATEWAY_URL, BRIDGE_AUTH_AK, BRIDGE_AUTH_SK

2. 项目级配置
   路径: <workspace>/.opencode/message-bridge.jsonc

3. 用户级配置
   路径: ~/.config/opencode/message-bridge.jsonc

4. 默认配置
   内置在代码中
```

#### 4.1.2 Workspace 确定规则

```typescript
function resolveWorkspace(): string {
  // 1. 优先使用 ctx.projectRoot（OpenCode 插件上下文）
  if (ctx.projectRoot) {
    return ctx.projectRoot;
  }
  
  // 2. 回退到 process.cwd()
  return process.cwd();
  
  // 3. 不向上递归搜索 git root
}
```

### 4.2 配置校验

#### 4.2.1 校验规则

```typescript
interface ConfigValidation {
  valid: boolean;
  errors: ConfigError[];
}

interface ConfigError {
  path: string;                     // 错误路径，如 "gateway.url"
  code: string;                     // 错误码
  message: string;                  // 错误描述
}

// 校验项
const validationRules = [
  // 必填字段（仅 auth.ak 和 auth.sk 在 enabled !== false 时为必填）
  { path: 'auth.ak', required: true, type: 'string' },
  { path: 'auth.sk', required: true, type: 'string' },
  
  // 可选字段（使用默认值）
  { path: 'config_version', required: false, type: 'number', enum: [1], default: 1 },
  { path: 'enabled', required: false, type: 'boolean', default: true },
  { path: 'gateway.url', required: false, type: 'string', format: 'websocket-url', default: 'ws://localhost:8081/ws/agent' },
  { path: 'gateway.deviceName', required: false, type: 'string', default: 'Local Machine' },
  { path: 'gateway.toolType', required: false, type: 'string', default: 'opencode' },
  { path: 'gateway.toolVersion', required: false, type: 'string', default: '1.0.0' },
  { path: 'gateway.heartbeatIntervalMs', required: false, type: 'number', min: 1, default: 30000 },
  { path: 'gateway.reconnect.baseMs', required: false, type: 'number', min: 1, default: 1000 },
  { path: 'gateway.reconnect.maxMs', required: false, type: 'number', min: 1, default: 30000 },
  { path: 'gateway.reconnect.exponential', required: false, type: 'boolean', default: true },
  { path: 'gateway.ping.intervalMs', required: false, type: 'number', min: 1, default: 30000 },
  { path: 'gateway.ping.pongTimeoutMs', required: false, type: 'number', min: 1, default: 10000 },
  { path: 'sdk.timeoutMs', required: false, type: 'number', min: 1, default: 10000 },
  { path: 'events.allowlist', required: false, type: 'array', default: ['message.*', 'permission.*', 'session.*', 'file.edited', 'todo.updated', 'command.executed'] },
  
  // 废弃字段
  { path: 'sdk.baseUrl', deprecated: true },
];
```

#### 4.2.2 JSONC 支持

```typescript
// 使用 jsonc-parser 库解析
import { parse } from 'jsonc-parser';

function parseConfig(content: string): unknown {
  const errors: ParseError[] = [];
  const result = parse(content, errors, {
    allowTrailingComma: true,       // 允许尾逗号
    disallowComments: false,        // 允许注释
  });
  
  if (errors.length > 0) {
    throw new ConfigParseError(errors);
  }
  
  return result;
}
```

### 4.3 环境变量占位符

```typescript
// 配置文件支持 ${ENV_VAR} 占位符
const config = {
  "auth": {
    "ak": "${BRIDGE_AUTH_AK}",
    "sk": "${BRIDGE_AUTH_SK}"
  }
};

// 解析时替换
function resolvePlaceholders(value: string): string {
  return value.replace(/\$\{([^}]+)\}/g, (match, envVar) => {
    const envValue = process.env[envVar];
    if (envValue === undefined) {
      throw new Error(`Environment variable ${envVar} is not set`);
    }
    return envValue;
  });
}
```

---

## 5. Action Registry 扩展机制

### 5.1 扩展点设计

```typescript
// src/action/ActionRegistry.ts

export interface ActionRegistryOptions {
  // 是否允许覆盖内置 action
  allowOverride?: boolean;
}

export class ActionRegistry {
  private actions: Map<string, BaseAction> = new Map();
  private readonly allowOverride: boolean;
  
  constructor(options: ActionRegistryOptions = {}) {
    this.allowOverride = options.allowOverride ?? false;
    this.registerBuiltinActions();
  }
  
  /**
   * 注册自定义 action
   * 新 action 接入不得修改连接与核心转发引擎
   */
  register(action: BaseAction): void {
    if (this.actions.has(action.name) && !this.allowOverride) {
      throw new Error(`Action ${action.name} is already registered`);
    }
    this.actions.set(action.name, action);
  }
  
  /**
   * 获取 action
   */
  get(name: string): BaseAction | undefined {
    return this.actions.get(name);
  }
  
  private registerBuiltinActions(): void {
    this.register(new ChatAction());
    this.register(new CreateSessionAction());
    this.register(new CloseSessionAction());
    this.register(new PermissionReplyAction());
    this.register(new StatusQueryAction());
  }
}
```

### 5.2 自定义 Action 示例

```typescript
// 自定义 action 示例

import { BaseAction, ActionContext, ValidationResult, ActionResult } from './BaseAction';
import { ToolErrorPayload } from '../error/types';

export class CustomAction extends BaseAction {
  readonly name = 'custom_action';
  
  validate(payload: unknown): ValidationResult {
    if (typeof payload !== 'object' || payload === null) {
      return { valid: false, errors: ['Payload must be an object'] };
    }
    
    const p = payload as Record<string, unknown>;
    const errors: string[] = [];
    
    if (typeof p.requiredField !== 'string') {
      errors.push('requiredField must be a string');
    }
    
    return { valid: errors.length === 0, errors };
  }
  
  async execute(payload: unknown, context: ActionContext): Promise<ActionResult> {
    try {
      // 业务逻辑
      const result = await this.doSomething(payload, context);
      
      return {
        success: true,
        payload: result
      };
    } catch (error) {
      return {
        success: false,
        error: this.mapError(error as Error, context)
      };
    }
  }
  
  mapError(error: Error, context: ActionContext): ToolErrorPayload {
    return {
      type: 'tool_error',
      code: 'SDK_UNREACHABLE',
      sessionId: context.sessionId,
      error: error.message,
      envelope: context.envelopeBuilder.build(context.sessionId)
    };
  }
}

// 注册自定义 action
const registry = new ActionRegistry({ allowOverride: true });
registry.register(new CustomAction());
```

### 5.3 Action Router 实现

```typescript
// src/action/ActionRouter.ts

export class ActionRouter {
  constructor(
    private registry: ActionRegistry,
    private errorHandler: ErrorHandler,
    private envelopeBuilder: EnvelopeBuilder
  ) {}
  
  async route(invoke: InvokeMessage, context: ActionContext): Promise<void> {
    // 1. 查找 action
    const action = this.registry.get(invoke.action);
    if (!action) {
      const error = this.buildError('UNSUPPORTED_ACTION', `Action ${invoke.action} is not supported`);
      this.sendError(error, invoke.sessionId);
      return;
    }
    
    // 2. 验证 payload
    const validation = action.validate(invoke.payload);
    if (!validation.valid) {
      const error = this.buildError('INVALID_PAYLOAD', validation.errors!.join(', '));
      this.sendError(error, invoke.sessionId);
      return;
    }
    
    // 3. 执行 action
    try {
      const result = await action.execute(invoke.payload, context);
      
      if (result.success) {
        // 发送成功响应
        this.sendSuccess(result.payload, invoke.sessionId);
      } else {
        // 发送错误响应
        this.sendError(result.error!, invoke.sessionId);
      }
    } catch (error) {
      // 未捕获异常
      const toolError = action.mapError(error as Error, context);
      this.sendError(toolError, invoke.sessionId);
    }
  }
  
  private buildError(code: ErrorCode, message: string): ToolErrorPayload {
    return {
      type: 'tool_error',
      code,
      error: message,
      envelope: this.envelopeBuilder.build()
    };
  }
  
  private sendError(error: ToolErrorPayload, sessionId?: string): void {
    // 发送 tool_error 到 Gateway
  }
  
  private sendSuccess(payload: unknown, sessionId?: string): void {
    // 发送 tool_done/session_created/status_response 到 Gateway
  }
}
```

---

## 6. SDK 对齐策略

### 6.1 当前兼容层

#### 6.1.1 permission_reply 双字段兼容

```typescript
// src/action/PermissionReplyAction.ts

export class PermissionReplyAction extends BaseAction {
  readonly name = 'permission_reply';
  
  async execute(payload: PermissionReplyPayload, context: ActionContext): Promise<ActionResult> {
    // 1. 规范化 payload（兼容 approved 字段）
    const normalized = this.normalizePayload(payload);
    
    // 2. 映射到 SDK 语义
    const sdkResponse = this.mapToSDK(normalized.response);
    
    // 3. 调用 SDK
    await context.opencode.permission.reply({
      permissionId: normalized.permissionId,
      toolSessionId: normalized.toolSessionId,
      response: sdkResponse
    });
    
    return { success: true };
  }
  
  private normalizePayload(payload: PermissionReplyPayload): PermissionReplyTarget {
    // 如果包含 approved 字段，转换为 response
    if ('approved' in payload) {
      return {
        permissionId: payload.permissionId,
        toolSessionId: payload.toolSessionId,
        response: payload.approved ? 'allow' : 'deny'
      };
    }
    return payload as PermissionReplyTarget;
  }
  
  private mapToSDK(response: 'allow' | 'always' | 'deny'): string {
    const mapping: Record<string, string> = {
      'allow': 'once',
      'always': 'always',
      'deny': 'reject'
    };
    return mapping[response];
  }
}
```

#### 6.1.2 close_session 语义固化

```typescript
// src/action/CloseSessionAction.ts

export class CloseSessionAction extends BaseAction {
  readonly name = 'close_session';
  
  async execute(payload: CloseSessionPayload, context: ActionContext): Promise<ActionResult> {
    // 固定映射到 session.abort，不执行 delete
    await context.opencode.session.abort(payload.toolSessionId);
    
    return { success: true };
  }
}
```

### 6.2 收敛计划

| 差异项 | 当前行为 | 目标行为 | 收敛版本 | 退场条件 |
|--------|----------|----------|----------|----------|
| permission_reply | 支持 approved + response 双字段 | 仅 response 字段 | v1.5 | Skill-Server 完成 approved -> response 统一迁移 |
| close_session | 映射到 abort | SDK 原生支持 | 待定 | SDK 提供原生 close_session 语义 |
| agentId 绑定 | 插件本地生成 agentId | Gateway 分配 gatewayAgentId | 待定 | Gateway 新增 register_success 响应 |

### 6.3 兼容性矩阵

| SDK 版本 | 状态 | 说明 |
|----------|------|------|
| 1.2.15 | ✅ 已验证 | 首版基线版本 |
| 1.2.x | ⚠️ 兼容范围 | 其他小版本需回归验证 |
| 1.3.x | ❓ 待评估 | 需重新评估兼容性 |

---

## 7. 测试策略

### 7.1 测试分层

```
┌─────────────────────────────────────────────────────────────────┐
│                        E2E Smoke                                │
│  • 注册、心跳、create+chat+close 完整链路                        │
│  • permission_reply 双字段兼容验证                               │
│  • 断连重连场景                                                  │
│  • 不可达启动失败场景                                            │
└────────────────────────────┬────────────────────────────────────┘
                             │
┌────────────────────────────▼────────────────────────────────────┐
│                      Integration                                │
│  • Mock Gateway WS + Mock SDK Client                            │
│  • 模块间交互验证                                                │
│  • 配置发现与加载                                                │
└────────────────────────────┬────────────────────────────────────┘
                             │
┌────────────────────────────▼────────────────────────────────────┐
│                         Unit                                    │
│  • 白名单匹配逻辑                                                │
│  • Envelope 构建与 Sequence 递增                                │
│  • Action 验证与执行                                             │
│  • 错误映射                                                      │
│  • 配置校验                                                      │
└─────────────────────────────────────────────────────────────────┘
```

### 7.2 必测场景

| 序号 | 场景 | 测试类型 | 验证点 |
|------|------|----------|--------|
| 1 | chat action 正常链路 | E2E | invoke -> SDK.chat -> tool_done |
| 2 | create_session action | E2E | invoke -> SDK.create -> session_created |
| 3 | close_session -> abort | E2E | 验证调用 abort 而非 delete |
| 4 | permission_reply 双字段 | E2E | approved=true/false 正确映射到 allow/deny |
| 5 | status_query/response | E2E | 可选 sessionId 透传 |
| 6 | 白名单允许路径 | Unit | 匹配白名单的事件正常上行 |
| 7 | 白名单拒绝路径 | Unit | 不匹配白名单的事件被丢弃并记录 |
| 8 | Fast Fail 触发 | Unit | 连接异常时立即返回 tool_error |
| 9 | envelope 完整性 | Unit | 所有字段正确填充 |
| 10 | sequence 递增 | Unit | 同 session 内 sequenceNumber 递增 |
| 11 | READY 前 invoke | Unit | 返回 AGENT_NOT_READY |
| 12 | 配置发现优先级 | Integration | env > project > user > default |
| 13 | JSONC 解析 | Unit | 支持注释与尾逗号 |
| 14 | 扩展性验证 | Unit | 新增 action 不改核心引擎 |

### 7.3 质量门槛

| 指标 | 目标 | 说明 |
|------|------|------|
| Lines Coverage | ≥ 80% | 插件目录内代码行覆盖率 |
| Branches Coverage | ≥ 70% | 分支覆盖率 |
| Type Check | ✅ 通过 | TypeScript 严格模式 |
| Unit Tests | ✅ 全通过 | 所有单元测试通过 |
| Integration Tests | ✅ 全通过 | 所有集成测试通过 |
| E2E Smoke | ✅ 全通过 | 所有 E2E 冒烟测试通过 |

---

## 8. 实施计划

### 8.1 里程碑划分

| 阶段 | 周期 | 交付物 | 验收标准 |
|------|------|--------|----------|
| **M1: 基础设施** | Week 1 | • 项目脚手架<br>• 配置层实现<br>• 错误类型定义 | • typecheck 通过<br>• 配置发现单元测试通过 |
| **M2: 连接层** | Week 2 | • WebSocket 连接<br>• AK/SK 鉴权<br>• 状态机实现<br>• 指数退避重连 | • 连接建立/断开测试通过<br>• 重连 5 次无异常 |
| **M3: 事件层** | Week 3 | • 事件订阅<br>• 白名单过滤<br>• Envelope 构建<br>• 事件透传 | • 白名单单元测试通过<br>• Sequence 递增验证通过 |
| **M4: Action 层** | Week 4 | • Action Registry<br>• 5 个基础 Action<br>• Permission Reply 兼容 | • 所有 action 单元测试通过<br>• 双字段兼容测试通过 |
| **M5: 集成与 E2E** | Week 5 | • 模块集成<br>• E2E 测试<br>• 性能基准 | • E2E 冒烟全通过<br>• 覆盖率达标 |
| **M6: 文档与交付** | Week 6 | • API 文档<br>• 使用指南<br>• 运维手册 | • 文档评审通过 |

### 8.2 验收标准

#### 8.2.1 功能验收

- [ ] 网关连接：成功建立 WS 连接并完成注册
- [ ] 心跳机制：30s 间隔发送，连接保持稳定
- [ ] 重连机制：断线后指数退避重连成功
- [ ] 事件上行：白名单事件正确封装并透传
- [ ] Action 下行：5 个基础 action 正常执行
- [ ] Fast Fail：异常场景 100ms 内返回错误
- [ ] 配置管理：多源配置发现与优先级正确

#### 8.2.2 质量验收

- [ ] TypeScript 严格模式无错误
- [ ] 单元测试覆盖率 ≥ 80%
- [ ] 分支覆盖率 ≥ 70%
- [ ] 无高危安全漏洞（依赖扫描）
- [ ] 脱敏日志规范合规

#### 8.2.3 性能验收

- [ ] 连接建立时间 < 1s（本地网络）
- [ ] invoke 处理延迟 P99 < 100ms（Fast Fail 场景）
- [ ] 内存占用稳定，无泄漏

### 8.3 风险与回滚

| 风险 | 影响 | 缓解措施 |
|------|------|----------|
| Gateway 协议变更 | 高 | 保留协议适配层，监控 Gateway 变更 |
| SDK 版本不兼容 | 中 | 锁定 SDK 版本，明确兼容范围 |
| 兼容层保留过长 | 中 | 制定收敛计划，跟踪差异项退场 |

**回滚策略：**
1. 插件回退至上一稳定版本
2. 保持后端协议不变，仅插件回滚
3. 保留失败日志用于根因分析

---

## 9. 附录

### 9.1 参考文档

- [prd.md](../product/prd.md) — 需求基线
- [overview.md](../architecture/overview.md) — 架构设计
- [AGENTS.md](../AGENTS.md) — 文档约束

### 9.2 术语表

| 术语 | 说明 |
|------|------|
| Gateway | AI-Gateway 服务端 |
| SDK | `@opencode-ai/sdk` |
| READY | 插件状态：已注册成功，可收发业务消息 |
| Fast Fail | 连接异常立即返回错误，不排队缓冲 |
| Envelope | 消息信封，包含元数据 |
| Action | Gateway 下发的指令类型 |

### 9.3 文档变更记录

| 版本 | 日期 | 变更内容 |
|------|------|----------|
| v1.0 | 2026-03-06 | 初始版本，基于 PRD v1.4 和架构文档 V1 |

---

**文档结束**
