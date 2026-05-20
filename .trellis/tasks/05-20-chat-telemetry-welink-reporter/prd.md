# Chat Telemetry — WeLink Reporter (skill-server)

## 1. 背景与目标

skill-server 需要把 chat 链路的两类事件上报到 WeLink 埋码平台（`messagecloud/producer`），用于产品分析：

- **用户发起对话**（`skill_chat_request`）
- **助手回复对话**（`skill_chat_response`）

两类事件都要带 `businessSessionDomain` + `businessSessionType` + `businessSessionId` + `senderUserAccount` + `assistantAccount` + `businessTag`。

接口协议详见外部文档（RSA-OAEP-SHA256 包 AES-128-GCM 信封 + Bearer Token + `x-wlk-hwa:1` 头）。

**业务约束**：
- 上报失败**绝不**能影响 chat 业务链路
- 本地/测试环境默认不上报
- 仅生产环境通过配置开启

## 2. 设计总览

### 2.1 分层

| 层 | 职责 | 主类 |
|---|---|---|
| 加解密 | RSA-OAEP-SHA256 包 AES-128-GCM，产 `{key, content}` 信封 | `WelinkCipherUtil` |
| HTTP 客户端 | POST `messagecloud/producer`，加 `Authorization` + `x-wlk-hwa:1` | `WelinkTelemetryClient` |
| 上报核心 | 组装 payload，提交到异步执行器 | `WelinkTelemetryReporter` |
| 异步执行 | 独立线程池 + 有界队列（不复用业务线程池） | `TelemetryExecutor` |
| 事件订阅 | 监听 `ChatRequestTelemetryEvent` / `ChatReplyTelemetryEvent` | `ChatTelemetryEventListener` |
| AOP 切面 | 切 `finalizeActiveAssistantTurn`，publish reply 事件 | `ChatReplyAspect` |
| 配置 | enabled / url / token / publicKey / tenantId / serviceName / executor 配置 | `WelinkTelemetryProperties` |

### 2.2 包结构

```
skill-server/src/main/java/com/opencode/cui/skill/telemetry/
├── config/
│   ├── WelinkTelemetryProperties.java        # @ConfigurationProperties("telemetry.welink")
│   └── WelinkTelemetryAutoConfiguration.java # @ConditionalOnProperty("telemetry.welink.enabled")
├── crypto/
│   └── WelinkCipherUtil.java
├── client/
│   ├── WelinkTelemetryClient.java
│   └── dto/
│       ├── EncryptedEnvelope.java            # {key, content}
│       └── TelemetryPayload.java             # {data, policyName}
├── core/
│   ├── WelinkTelemetryReporter.java
│   ├── TelemetryEvent.java
│   └── TelemetryExecutor.java
└── chat/
    ├── ChatRequestTelemetryEvent.java
    ├── ChatReplyTelemetryEvent.java
    ├── ChatReplyAspect.java
    └── ChatTelemetryEventListener.java
```

### 2.3 改动既有文件

| 文件 | 改动 |
|---|---|
| `controller/SkillMessageController.java` | `routeToGateway()` 末尾增加 `eventPublisher.publishEvent(new ChatRequestTelemetryEvent(...))`（约 3 行） |
| `application.yml` | 新增 `telemetry.welink.*` 配置块（默认 `enabled=false`） |

`MessagePersistenceService.java` / `ActiveMessageTracker.java` **无需修改**，AOP pointcut 切入。

## 3. chat 事件字段映射

### 3.1 共同字段（两个事件相同）

| 字段 | 值来源 |
|---|---|
| `policyName` | 配置 `telemetry.welink.policy-name`，默认 `POLICY_WELINK_SERVER` |
| `serviceName` | 配置 `telemetry.welink.service-name`，默认 `skill-server` |
| `appName` / `appPackageName` | 配置静态读 |
| `tenantId` | 配置 `telemetry.welink.tenant-id`（静态） |
| `sessionId` | `businessSessionId`（与会话表 PK 一致） |
| `eventType` | `event` |
| `eventTime` | `System.currentTimeMillis()`（事件触发时刻） |
| `traceId` | 当前 MDC 中的 traceId，缺失则空串 |
| `extendData` | `{businessSessionDomain, businessSessionType, businessSessionId, senderUserAccount, assistantAccount, businessTag}` |

### 3.2 差异字段

| 字段 | request 事件 | reply 事件 |
|---|---|---|
| `eventId` | `skill_chat_request` | `skill_chat_response` |
| `eventLabel` | `用户发起 chat 对话` | `助手回复 chat 对话` |
| `userId` | `senderUserAccount`（cookie 优先，session.userId 兜底） | `assistantAccount` |

> 两事件 `sessionId` 一致，便于下游按会话关联 request/response pair。

## 4. 切入点（精确到方法）

### 4.1 用户发起事件 — 显式 publishEvent

- **位置**：`SkillMessageController.routeToGateway()` 方法末尾（`SkillMessageController.java`，已构造完 payload、即将调用 gateway 前）
- **方式**：直接 `eventPublisher.publishEvent(new ChatRequestTelemetryEvent(session, effectiveUserId, businessTag))`
- **理由**：所需字段（`session`、`effectiveUserId`）都在局部变量中，AOP 提参反而更绕；显式 1-3 行更清晰
- **失败请求是否上报**：否。`routeToGateway` 内部 early-return 路径（agent_offline / no_toolSessionId / no_agent）**不**触发上报；只有真正发往 gateway 的请求才上报

### 4.2 助手回复事件 — AOP 切面

- **切点**：`MessagePersistenceService.finalizeActiveAssistantTurn(Long sessionId)`
- **方式**：`@AfterReturning` 切面 → publish `ChatReplyTelemetryEvent(sessionId)`
- **字段提取**：listener 内通过 `SkillSessionRepository.findById(sessionId)` 拉 5 个业务字段
- **理由**：该方法签名稳定、调用点统一（2 处都在 `GatewayMessageRouter`），AOP 零侵入；listener 内的一次额外 DB 查询是为了换业务代码零修改

## 5. 配置

```yaml
telemetry:
  welink:
    enabled: false            # 总开关；本地/测试默认关
    url: ""                   # 例：http://api-intranet.clink.local/hwa-c/v3/messagecloud/producer
    token: ""                 # Bearer token
    public-key: ""            # RSA 公钥 base64
    policy-name: POLICY_WELINK_SERVER
    tenant-id: ""             # 必填，静态配置
    service-name: skill-server
    app-name: skill-server
    app-package-name: com.opencode.cui.skill
    executor:
      core-pool-size: 2
      max-pool-size: 4
      queue-capacity: 1000
      reject-policy: discard  # 队列满直接丢 + WARN 日志
```

**自动 disabled 条件**：`enabled=true` 但 `url`/`token`/`public-key`/`tenant-id` 任一为空 → 启动日志 WARN 并视同关闭。由 `WelinkTelemetryAutoConfiguration` 在 Bean 初始化时校验。

## 6. 错误处理矩阵

| 失败点 | 处理 | 影响业务 |
|---|---|---|
| `enabled=false` 或必填配置缺失 | Listener 直接 return | 否 |
| 业务必传字段缺失（如 `senderUserAccount` 空） | WARN 日志 + 跳过该条 | 否 |
| RSA/AES 加密异常 | catch，WARN 日志（**不**打印明文/密钥） | 否 |
| HTTP 4xx / 5xx / 超时 | catch，WARN 日志（含 `eventId` + `sessionId` + `httpCode`） | 否 |
| 线程池队列满 | `DiscardPolicy` + WARN 日志 + 内部丢弃计数 +1 | 否 |
| Listener / 切面任何未捕获异常 | 顶层 try-catch 兜底 | 否 |

**核心不变量**：上报链路任何异常都不得抛回业务线程。

## 7. 测试

| 测试文件 | 覆盖 |
|---|---|
| `WelinkCipherUtilTest` | RSA + AES 往返；`security:` 前缀；IV 唯一 |
| `WelinkTelemetryReporterTest` | 字段映射（policyName / userId / extendData）；disabled 短路 |
| `WelinkTelemetryClientTest` | `MockRestServiceServer` 验请求头 + body `{key, content}` 结构 |
| `ChatTelemetryEventListenerTest` | request / reply 两类事件 → 两种 eventId 调用 reporter |
| `ChatReplyAspectIntegrationTest` | `@SpringBootTest` 验切面接到 `finalizeActiveAssistantTurn` |

**不测**：真实 RSA 公钥 + 真实 HTTP 端点（CI 无法访问内网）。

## 8. 上线 & 回滚

- **上线**：prod profile 配齐 `url` / `token` / `public-key` / `tenant-id` + `enabled=true`
- **灰度**：先开 1 pod 观察日志 24h（WARN / 上报量），再全量
- **回滚**：`enabled=false` 滚动重启；零数据迁移、零 DB 变更
- **监控**：日志关键字 `WelinkTelemetry`，WARN 突增视为异常

## 9. 显式不做（YAGNI）

- 不做本地缓存 / 文件落盘 / 失败重试队列
- 不做批量上报（producer 接口按单条交付即可）
- 不做加密密钥轮换（公钥配置项管理即可）
- 不抽 `TelemetryReporter` 通用接口（目前只 chat 一个业务，过早抽象）

## 10. PR 范围

单 PR 一次性交付：
1. `telemetry/*` 全套基础设施（crypto / client / core / config）
2. `chat/*` 事件与切面
3. `SkillMessageController.routeToGateway` 改动（3 行）
4. `application.yml` 默认配置块（disabled）
5. 全套单测 + 一个切面集成测
