# 日志体系整改计划 — AI-Gateway & Skill-Server

## Context

两个 Java 服务（ai-gateway :8081, skill-server :8082）当前日志存在以下问题：
- 无 MDC，无法在服务内追踪一条请求的所有日志
- traceId 已存在于 GatewayMessage 中，但从未写入 MDC，也未从 skill-server 向 gateway 传播
- 日志格式缺少服务名、traceId、sessionId 等关键上下文
- 关键节点（认证拦截器、外部 API 调用）缺少日志或缺少计时
- 两个服务间的日志无法关联

**决策**：纯文本 pattern（不引入 JSON）；分批推进；MDC 包含 traceId + welinkSessionId + ak 三个字段。

---

## 第零批：项目规则 — 在 CLAUDE.md 中添加日志规范

在项目 `CLAUDE.md` 的 `## Key Conventions` 部分追加日志规范规则，确保后续所有代码改动都必须遵守：

```markdown
## Logging Standards

### 日志格式规范
- 所有日志必须通过 MDC 携带 traceId、sessionId(welinkSessionId)、ak 三个关联字段
- 使用 `[ENTRY]`/`[EXIT]`/`[ERROR]`/`[EXT_CALL]` 前缀标识日志场景
- 外部 API 调用必须记录 durationMs

### 日志完整性规则（强制）
- **每次代码改动都必须检查并补充相关日志**
- 消息流转的每一个环节（接收、校验、转换、路由、发送、响应）都必须有日志
- 每个请求的入口和出口必须有配对的 ENTRY/EXIT 日志
- 外部调用（HTTP、WebSocket 发送、Redis 操作）必须有调用前后的日志
- 异常分支和错误处理必须有 WARN/ERROR 日志，包含足够的上下文
- 禁止出现「日志黑洞」——任何可能失败的环节都要有日志覆盖

### MDC 使用规则
- WebSocket handler 方法入口设置 MDC，finally 块清理
- REST 请求通过 MdcRequestInterceptor 自动设置/清理
- 跨线程传播使用 MdcHelper.snapshot()/restore()
```

**修改文件**：`CLAUDE.md`（项目根目录）

---

## 第一批：基础设施 + 跨服务 traceId 传播

### 1.1 创建 MDC 常量类和工具类（两个服务各一份）

**新增文件**：
- `ai-gateway/.../logging/MdcConstants.java`
- `ai-gateway/.../logging/MdcHelper.java`
- `skill-server/.../logging/MdcConstants.java`
- `skill-server/.../logging/MdcHelper.java`

MdcConstants 定义 key：
- `TRACE_ID = "traceId"` — 跨服务请求追踪
- `SESSION_ID = "sessionId"` — welinkSessionId，会话级关联
- `AK = "ak"` — Agent 级关联
- `USER_ID = "userId"` — 用户标识
- `SCENARIO = "scenario"` — 场景标识（ws-agent, ws-skill, rest-im, etc.）

MdcHelper 核心方法：
- `fromGatewayMessage(msg)` — 批量设置 traceId/sessionId/ak/userId
- `clearAll()` — 清理所有自定义 key
- `ensureTraceId()` — 如果 MDC 中无 traceId 则生成 UUID
- `snapshot() / restore(Map)` — 跨线程传播用

### 1.2 创建 logback-spring.xml（两个服务各一份）

**新增文件**：
- `ai-gateway/src/main/resources/logback-spring.xml`
- `skill-server/src/main/resources/logback-spring.xml`

统一日志格式：
```
%d{HH:mm:ss.SSS} [%thread] %-5level [${SERVICE_NAME}] [%X{traceId:-}] [%X{sessionId:-}] [%X{ak:-}] %logger{36}.%method - %msg%n
```

效果示例：
```
14:23:45.123 [ws-pool-1] INFO  [ai-gateway] [a1b2c3d4] [sess-001] [ak-xyz] EventRelayService.relayToSkillServer - 转发消息到 SkillServer: type=tool_event, toolSessionId=ts-001
```

保留现有的 rolling policy 配置（20MB/30天/2GB），从 application.yml 迁移到 logback-spring.xml。

### 1.3 修改 application.yml（两个服务）

- 删除 `logging.pattern.file` 和 `logging.pattern.console`（由 logback-spring.xml 接管）
- 保留 `logging.level` 配置
- 保留 `opencode.logging.*` 自定义属性

**修改文件**：
- `ai-gateway/src/main/resources/application.yml`（行 97-98）
- `skill-server/src/main/resources/application.yml`（行 93-95）

### 1.4 跨服务 traceId 传播

**问题**：Skill-Server 发 invoke 到 Gateway 时不带 traceId → Gateway 生成新 UUID → 回传时 traceId 对不上。

**修改**：
- `skill-server/.../service/GatewayRelayService.java` 的 `buildInvokeMessage()` — 从 MDC 取 traceId 写入消息，无则生成
- `skill-server/.../service/GatewayRelayService.java` 的 `handleGatewayMessage()` — 从 Gateway 消息提取 traceId 写入 MDC

**验证**：Gateway 侧 `ensureTraceId()` 会保留已有 traceId，无需修改。

### 1.5 核心入口点 MDC 织入

**WebSocket Handler（ai-gateway）**：
- `AgentWebSocketHandler` — 每个 handler 方法入口 `MdcHelper.fromGatewayMessage()`，finally 块 `MdcHelper.clearAll()`
- `SkillWebSocketHandler` — 同上

**WebSocket Handler（skill-server）**：
- `GatewayWSClient.InternalWebSocketClient.onMessage()` — 从消息提取 traceId 写入 MDC
- `SkillStreamHandler` — 每个 handler 方法入口设置 MDC

**REST 拦截器（两个服务各一个）**：
- 新增 `config/MdcRequestInterceptor.java` — 实现 HandlerInterceptor
  - `preHandle()`：从 `X-Trace-Id` header 或自动生成 traceId，写入 MDC
  - `afterCompletion()`：清理 MDC
- 注册到 WebMvcConfigurer

**消息路由核心（两个服务）**：
- `ai-gateway/.../service/EventRelayService.java` — 每个 relay 方法入口设置 MDC
- `ai-gateway/.../service/SkillRelayService.java` — 同上
- `skill-server/.../service/GatewayMessageRouter.java` — route() 入口设置 MDC

### 1.6 认证拦截器日志补全

**严重缺陷**：`skill-server/.../config/ImTokenAuthInterceptor.java` 完全无日志。

修改：
- 添加 `@Slf4j`
- 认证失败：`log.warn("[AUTH_FAIL] IM token auth: reason={}, path={}, remoteAddr={}", ...)`
- 认证成功：`log.debug("[AUTH_OK] IM token auth: userId={}, path={}", ...)`

---

## 第二批：入口/出口/外部调用日志补全 + 计时

### 2.1 创建计时工具类

**新增文件**（两个服务各一份）：
- `logging/LogTimer.java`

提供 `timed(Logger, String, Supplier<T>)` 方法，自动记录操作耗时和成功/失败。

### 2.2 外部 API 调用添加计时日志

| 服务 | 文件 | 外部调用 |
|------|------|----------|
| ai-gateway | `IdentityApiClient.java` | POST /identity/check |
| ai-gateway | `DeviceBindingService.java` | 设备绑定验证 |
| skill-server | `ImOutboundService.java` | IM 发消息 API |
| skill-server | `ImMessageService.java` | IM 消息发送 |
| skill-server | `AssistantAccountResolverService.java` | 助手账号解析 |
| skill-server | `GatewayApiClient.java` | Gateway REST 查询 |

每个调用点添加格式：
```
log.info("[EXT_CALL] {}: url={}, durationMs={}, status={}", operation, url, elapsed, status)
```

### 2.3 REST Controller 入口/出口日志

**标准格式**：
```
[ENTRY] scenario: param1={}, param2={}
[EXIT]  scenario: result={}, durationMs={}
[ERROR] scenario: error={}, durationMs={}
```

需要补全的 Controller：
- `skill-server/.../controller/ImInboundController.java` — 已有入口日志，补 EXIT + 计时
- `skill-server/.../controller/SkillSessionController.java` — 补 ENTRY/EXIT
- `skill-server/.../controller/SkillMessageController.java` — 补 ENTRY/EXIT
- `ai-gateway/.../controller/AgentController.java` — 已有部分日志，补 EXIT + 计时

### 2.4 全链路每个环节的日志补全

**核心原则**：消息从进入系统到离开系统，每经过一个方法/类/服务边界，都要有日志。确保排查时能通过 traceId 看到完整链路，精确定位断在哪一步。

#### 链路 A：IM 消息 → Agent（下行）

每一步都需要日志：
```
1. [skill-server] ImTokenAuthInterceptor.preHandle()      — [AUTH] 认证结果
2. [skill-server] ImInboundController.handleImMessage()    — [ENTRY] 收到 IM 消息
3. [skill-server] AssistantAccountResolverService.resolve() — [EXT_CALL] 解析助手账号
4. [skill-server] ContextInjectionService.resolve()        — 上下文注入结果
5. [skill-server] ImSessionManager.getOrCreateSession()    — 会话查找/创建结果
6. [skill-server] SkillSessionService.createSession()      — 新建会话（如需要）
7. [skill-server] GatewayRelayService.sendInvokeToGateway() — [EXIT→GW] 发送 invoke
8. [skill-server] GatewayWSClient.send()                   — WS 发送结果
9. [ai-gateway]   SkillWebSocketHandler.handleTextMessage() — [ENTRY] 收到 skill invoke
10. [ai-gateway]  SkillRelayService.handleInvokeFromSkill() — 校验 + 路由决策
11. [ai-gateway]  EventRelayService.relayToAgent()          — 转发给 Agent
12. [ai-gateway]  AgentWebSocketHandler (send)              — [EXIT→AGENT] WS 发送结果
```

#### 链路 B：Agent 响应 → Skill-Server → 前端（上行）

```
1. [ai-gateway]   AgentWebSocketHandler.handleTextMessage() — [ENTRY] 收到 Agent 消息
2. [ai-gateway]   EventRelayService.relayToSkillServer()    — 路由决策 + traceId 保证
3. [ai-gateway]   SkillRelayService.relayToSkill()          — 路由缓存查找/广播
4. [ai-gateway]   SkillRelayService (send to SS WS)         — [EXIT→SS] WS 发送结果
5. [skill-server] GatewayWSClient.onMessage()               — [ENTRY] 收到 GW 消息
6. [skill-server] GatewayRelayService.handleGatewayMessage() — 消息解析
7. [skill-server] GatewayMessageRouter.route()              — 路由分发
8. [skill-server] GatewayMessageRouter.handleToolEvent()    — 事件处理
9. [skill-server] MessagePersistenceService.persist()       — 消息持久化
10. [skill-server] SkillStreamHandler.broadcast()           — [EXIT→FE] 推送到前端
```

#### 链路 C：Agent 响应 → IM（外发）

```
1-8. 同链路 B 的 1-8
9. [skill-server] ImOutboundService.sendTextToIm()         — [EXT_CALL] IM API 调用 + 计时
10. [skill-server] ImMessageService.sendMessage()           — [EXT_CALL] IM 消息发送 + 计时
```

#### 链路 D：Miniapp → Agent（下行）

```
1. [skill-server] MdcRequestInterceptor.preHandle()        — [AUTH] traceId 设置
2. [skill-server] SkillMessageController.sendMessage()     — [ENTRY] 收到 Miniapp 消息
3. [skill-server] SkillMessageService.saveMessage()        — 消息持久化
4-8. 同链路 A 的 7-12
```

#### 链路 E：Agent 注册

```
1. [ai-gateway] AgentWebSocketHandler.beforeHandshake()    — [ENTRY] WS 握手
2. [ai-gateway] AkSkAuthService.verify()                   — [AUTH] AK/SK 认证 + 计时
3. [ai-gateway] IdentityApiClient.check()                  — [EXT_CALL] 身份校验 + 计时
4. [ai-gateway] DeviceBindingService.validate()            — [EXT_CALL] 设备绑定 + 计时
5. [ai-gateway] AgentWebSocketHandler.afterConnectionEstablished() — 连接建立
6. [ai-gateway] AgentWebSocketHandler.handleRegister()     — [ENTRY] 注册消息
7. [ai-gateway] AgentRegistryService.register()            — 注册结果
8. [ai-gateway] EventRelayService.registerSession()        — 会话注册
9. [ai-gateway] (send register_ok)                         — [EXIT] 注册完成
```

**实施方式**：逐个链路审查每一步，缺失日志的补上，已有日志的确认格式统一且包含 MDC 字段。每一步至少有一条日志，错误分支额外加 WARN/ERROR。

### 2.5 敏感数据脱敏

**新增**：`logging/SensitiveDataMasker.java`（两个服务各一份）
- `maskMac(String)` — 仅显示后 4 位
- `maskToken(String)` — 显示前 4 后 4

修改 `AgentWebSocketHandler.handleRegister()` 中 macAddress 日志输出使用脱敏。

---

## 关键文件清单

### 修改项目规则（1 个）

| 文件 | 说明 |
|------|------|
| `CLAUDE.md` | 追加 Logging Standards 规范，确保后续改动遵守 |

### 新增文件（共 10 个）

| 文件 | 服务 |
|------|------|
| `logging/MdcConstants.java` | 两个服务各一份 |
| `logging/MdcHelper.java` | 两个服务各一份 |
| `logging/LogTimer.java` | 两个服务各一份 |
| `logging/SensitiveDataMasker.java` | 两个服务各一份 |
| `config/MdcRequestInterceptor.java` | 两个服务各一份 |
| `logback-spring.xml` | 两个服务各一份 |

### 主要修改文件

**ai-gateway**（~8 个文件）：
- `ws/AgentWebSocketHandler.java` — MDC 织入
- `ws/SkillWebSocketHandler.java` — MDC 织入
- `service/EventRelayService.java` — MDC 织入
- `service/SkillRelayService.java` — MDC 织入
- `service/AkSkAuthService.java` — 计时
- `service/IdentityApiClient.java` — 计时
- `service/DeviceBindingService.java` — 计时
- `application.yml` — 删除 pattern 配置

**skill-server**（~12 个文件）：
- `config/ImTokenAuthInterceptor.java` — 添加日志（当前完全无日志）
- `controller/ImInboundController.java` — MDC + EXIT 日志
- `controller/SkillSessionController.java` — ENTRY/EXIT 日志
- `controller/SkillMessageController.java` — ENTRY/EXIT 日志
- `ws/GatewayWSClient.java` — MDC 织入
- `ws/SkillStreamHandler.java` — MDC 织入
- `service/GatewayRelayService.java` — traceId 注入 + MDC 织入
- `service/GatewayMessageRouter.java` — MDC 织入
- `service/ImOutboundService.java` — 计时
- `service/ImMessageService.java` — 计时
- `service/AssistantAccountResolverService.java` — 计时
- `application.yml` — 删除 pattern 配置

---

## 风险与注意事项

1. **WebSocket 线程模型**：MDC 是 ThreadLocal，每个 handler 方法必须在入口设置、finally 清理，不可依赖上游
2. **Redis 回调线程**：Lettuce event loop 线程中 MDC 为空，需在回调入口从消息体提取 traceId
3. **不可变 DTO**：GatewayMessage 使用 toBuilder() 模式，所有修改返回新实例
4. **向后兼容**：Message Bridge Plugin 可能不传 traceId，Gateway 的 ensureTraceId() 会兜底生成 UUID

---

## 验证方法

1. 启动两个服务，通过 Miniapp 发送消息
2. 在 ai-gateway 和 skill-server 日志中搜索同一个 traceId，验证能关联上
3. 在单个服务日志中搜索 traceId，验证同一请求的所有日志行都包含该 traceId
4. 验证外部 API 调用日志包含 durationMs
5. 验证 ImTokenAuthInterceptor 认证失败时有 WARN 日志
6. 验证 MDC 在请求结束后被清理（不会泄漏到下一个请求）
