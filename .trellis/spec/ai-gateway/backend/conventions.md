# 编码约定

> `ai-gateway` 的通用编码规范。重点不是“模板式 Spring 代码”，而是围绕 Agent 握手、Redis 路由、WebSocket 中继建立统一写法。
---

## 依赖注入

一律使用**构造器注入**，不要使用字段注入。`AgentRegistryService` 直接把 Repository、Relay 服务和雪花 ID 生成器作为构造参数收口，便于测试与替换实现。

```java
// Source: ai-gateway/src/main/java/com/opencode/cui/gateway/service/AgentRegistryService.java:30-43
@Service
public class AgentRegistryService {
    private final AgentConnectionRepository repository;
    private final EventRelayService eventRelayService;
    private final SnowflakeIdGenerator snowflakeIdGenerator;

    public AgentRegistryService(AgentConnectionRepository repository,
            EventRelayService eventRelayService,
            SnowflakeIdGenerator snowflakeIdGenerator) {
        this.repository = repository;
        this.eventRelayService = eventRelayService;
        this.snowflakeIdGenerator = snowflakeIdGenerator;
    }
}
```

## 配置管理

- 统一从 `application.yml` 的 `gateway.*`、`skill.gateway.*`、`opencode.logging.*` 命名空间读取配置。
- 简单标量用 `@Value` 注入，例如 `gateway.instance-id`、`gateway.auth.mode`、`gateway.agent.heartbeat-timeout-seconds`。
- 结构化配置用 `*Properties` 承载，例如 `SnowflakeProperties`、`CloudTimeoutProperties`。
- `GatewayApplication` 已开启 `@MapperScan`，不要在每个 Mapper 上重复做手工注册。来源：`ai-gateway/src/main/java/com/opencode/cui/gateway/GatewayApplication.java:10-17`。

### 陷阱：`@Value(":<default>")` 的默认值是 fallback，不是"真实"默认值

`@Value("${gateway.foo:false}")` 的 `:false` 只在 `application.yml` **没写** `gateway.foo` 时才生效。一旦 yml 写了 `gateway.foo: true`，Java 源码里的 `:false` 就是假象。

**症状**：开发同学读 Java 代码以为某 feature flag 默认关，实际线上一直开着。debug 时按"代码里写的默认值"推理就会走偏。

**规则**：

- **声明真实默认值唯一权威是 `application.yml`**。Java 端 `@Value(":<x>")` 的 `<x>` 仅作为"yml 整段缺失"的兜底，不要把它当作 feature flag 的真实默认值。
- 新增 flag 时，**yml 必须显式写一行**（即使值等于 Java fallback），让 grep `application.yml` 就能看到全部默认值。
- review 写有 `@Value(":<default>")` 的代码时，第一件事是 `grep "<config-key>" application*.yml` 确认两边一致；不一致时以 yml 为准。

**真实事故**：`SkillRelayService.legacyRelayEnabled` 历史上 Java 写 `@Value(":false")`，但 `application.yml` 写的是 `GATEWAY_LEGACY_RELAY_ENABLED:true`——线上 legacy 兜底实际一直开着，直到 PR1 清理时才暴露。

## WebSocket 注册模式

WebSocket 端点统一由 `GatewayConfig` 注册，Handler 同时承担 `HandshakeInterceptor`。这样握手鉴权和消息处理能共享一份依赖图，不需要额外的 adapter 层。

```java
// Source: ai-gateway/src/main/java/com/opencode/cui/gateway/config/GatewayConfig.java:35-74
registry.addHandler(agentWebSocketHandler, "/ws/agent")
        .addInterceptors(agentWebSocketHandler)
        .setAllowedOrigins(allowedOrigins);

registry.addHandler(skillWebSocketHandler, "/ws/skill")
        .addInterceptors(skillWebSocketHandler)
        .setAllowedOrigins(allowedOrigins);
```

配套约束：

| 场景 | 约定 | 真实实现 |
|------|------|----------|
| Agent 握手 | 从 `Sec-WebSocket-Protocol` 里解析 `auth.{base64url-json}`，验签成功后把 `userId` / `ak` 写入 `session.getAttributes()` | `ws/AgentWebSocketHandler.java:127-194` |
| Skill Server 握手 | 用 `token + source + instanceId` 子协议做内部鉴权 | `ws/SkillWebSocketHandler.java:47-175` |
| REST MDC | 只拦截 `/api/**`，通过 `MdcRequestInterceptor` 自动注入 `traceId` 与 `scenario` | `config/GatewayConfig.java:47-53`, `config/MdcRequestInterceptor.java:16-32` |

## 服务编排模式

`ai-gateway` 的 Controller 不直接做 Redis 或 WebSocket 操作，而是把编排责任下沉到 Service：

| 层 | 典型职责 | 真实类 |
|----|----------|--------|
| Controller | 鉴权 header、参数校验、HTTP 状态码与 `ApiResponse` 组装 | `AgentController`, `CloudPushController` |
| WS Handler | 握手、消息解包、MDC 注入、把领域动作转交 Service | `AgentWebSocketHandler`, `SkillWebSocketHandler` |
| 核心 Service | Agent 生命周期、中继、路由学习、离线缓冲 | `AgentRegistryService`, `EventRelayService`, `SkillRelayService` |
| 基础设施 Service | Redis key/channel 操作、外部身份 API 调用 | `RedisMessageBroker`, `IdentityApiClient` |

## AK/SK 验签辅助模式

AK/SK 校验不是“抛异常 + 统一处理”的风格，而是显式返回 `userId` 或 `null`。所有公共校验都先过时间窗和 nonce，再按模式分支到本地 HMAC 或远端 identity API。

```java
// Source: ai-gateway/src/main/java/com/opencode/cui/gateway/service/AkSkAuthService.java:97-150
public String verify(String ak, String timestamp, String nonce, String signature) {
    if (ak == null || timestamp == null || nonce == null || signature == null) {
        return null;
    }
    // 1. 时间窗校验
    // 2. Redis SET NX 反重放
    // 3. gateway 模式走 verifyLocally；remote 模式走 resolveIdentity
}
```

与之配套的本地 HMAC 计算集中在一个私有辅助方法中，避免在握手层、Controller 层重复拼签名逻辑。来源：`ai-gateway/src/main/java/com/opencode/cui/gateway/service/AkSkAuthService.java:161-194`。

## REST 响应约定

- 正常 REST 接口统一返回 `ResponseEntity<ApiResponse<T>>`。
- `ApiResponse.ok(data)` 表示 `code=0` 成功；`ApiResponse.error(code, message)` 表示业务失败。
- 兼容接口可以暂时返回 `Map<String, Object>`，但新接口优先使用 `record` 或专用 DTO。例：`AgentController` 的 `/agents/{id}/status` 和 `/agents/{id}/invoke` 仍保留 legacy Map 返回；见 `controller/AgentController.java:147-197`。

## 事务与调度

- 涉及 MySQL 写入的方法显式加 `@Transactional`，例如 `AgentRegistryService.register/heartbeat/markOffline/checkTimeouts`；来源：`service/AgentRegistryService.java:48-49`, `93-101`, `133-148`。
- 定时任务只做状态收敛，不做外部阻塞 I/O；`checkTimeouts()` 负责找出超时 Agent、标记离线、移除本地会话。来源：`service/AgentRegistryService.java:131-148`。

## 运维可配置的"套餐" = SysConfig 数据 + Registry 运行时拼装

需要让运维零代码自由组合策略（如 request/response 协议套餐）时，**不要**把每个组合做成一个 `@Component` 类。改用：

- 策略本体（如 `*CloudRequestStrategy`、`SseEventDecoder` 实现）保持 `@Component` 自动注册；
- "套餐"用 record / POJO 表达（不是接口、不是 Bean），由 `@Service` Registry 在运行时按 SysConfig 两段查找拼装：①`<feature>_profile:<dimensionKey>` → profile name；②`<feature>_profile_def:<profileName>` → JSON 选择哪些策略。
- 约定 fallback：`profile_def` 缺失时按 "profile name == strategy bean name" 对称查找，避免运维必填两段。
- Registry 必须带 in-memory TTL cache（参考 5min / `gateway.cloud-protocol-profile.cache-ttl-ms`），SysConfig 查询不要进热路径每次都打。

参考：`CloudResponseProfileRegistry`、`CloudRequestProfileRegistry`（SS 侧）。跨服务时 profile name 字符串是 SS↔GW 的唯一契约。

## 废弃 SysConfig / 类的渐进迁移

迁移到新机制（如 profile-based）后，旧 SysConfig 数据**保留不删**作为回滚兜底，但代码侧不再读取；对应旧 Builder / Strategy 类加 `@Deprecated` + javadoc 注明替代品。例：`CloudRequestBuilder` + `cloud_request_strategy:<businessTag>`。这样 rollback 只需切回旧入口，不需要回填数据。

## 禁止事项

| 禁止 | 原因 | 正确做法 |
|------|------|----------|
| 在 Controller / WS Handler 中直接拼 Redis key | key 演进快，容易出现兼容遗漏 | 统一走 `RedisMessageBroker` |
| 在业务代码里直接 `MDC.put("traceId", ...)` | key 分散、难清理 | 使用 `MdcConstants` + `MdcHelper` |
| 在握手层抛未捕获异常 | WebSocket 握手失败语义不清 | 返回 `false` 或发送拒绝消息后关闭连接 |
| 为 Agent 状态接口返回裸 `Map` | 丢失类型约束 | 新接口使用 `AgentSummaryResponse`、`AgentStatusResponse`、`InvokeResult` |
| 在多个地方复制 HMAC / nonce 逻辑 | 易出现协议偏差 | 只在 `AkSkAuthService` 实现验签 |
## Assistant instance remote routing

### 1. Scope / Trigger

This applies when `CloudAgentService` receives an invoke payload with `assistantAccount` or `partnerAccount`.
Remote cloud routing must prefer the assistant instance API over legacy callback/profile fallback because remote assistants may not have an AK.

### 2. Signatures

- Instance lookup: `AssistantInstanceInfoService.getInstanceInfo(String partnerAccount)`.
- Route resolution: `CloudAgentService.resolveRemoteRoute(String assistantAccount, String action, String fallbackBusinessTag)`.
- HTTP auth application: `CloudAuthService.applyAuth(HttpRequest.Builder builder, String appId, String authType)`.
- WebSocket auth application: `CloudAuthService.applyAuth(WebSocket.Builder builder, String appId, String authType)`.
- Connection context: `CloudConnectionContext.authType()` and `CloudConnectionContext.cloudProfile()`.

### 3. Contracts

`assistantAccount` / `partnerAccount` is the primary identity for remote assistant routing.
`AssistantInstanceInfo.remoteProperty[]` is selected by action (`chat`, `question_reply`, `permission_reply`, etc.) and supplies protocol, endpoint, and auth type.
`remoteProperty.headers` is an array, but GW uses only the first `header.type` to derive `CloudConnectionContext.authType`; protocol executors must then call `CloudAuthService.applyAuth(..., authType)`.
`remoteProperty.headers[].customKey` and `customValue` are not raw outbound cloud request headers in this flow and must not be replayed by GW.
`CloudConnectionContext.cloudProfile` comes from `AssistantInstanceInfo.bizRobotTag` when present, otherwise the SS-provided business tag / legacy `cloudProfile`; never use `remoteProperty.dataProtocol` as the profile override.

### 4. Validation & Error Matrix

| Case | Required behavior |
| --- | --- |
| Instance API returns a matching `remoteProperty` for action | Use endpoint/protocol from instance data, derive `authType` from `remoteProperty.headers[0].type`, and derive `cloudProfile` from `bizRobotTag` or SS fallback. |
| Instance API succeeds but action property is missing | Emit remote-property-missing failure; do not invent a legacy endpoint. |
| Instance API fails or no partner account is present | Preserve legacy fallback path when configured. |
| `remoteProperty.headers` is empty | Use `authType="none"` so no auth headers are written. |
| First `header.type` is unsupported | Pass the normalized value as `authType`; `CloudAuthService` must fail fast rather than silently dropping auth material. |
| `customKey/customValue` are present | Ignore them for cloud outbound auth; never write them as raw request headers. |

### 5. Good / Base / Bad Cases

Good:

```java
AssistantInstanceInfo info = assistantInstanceInfoService.getInstanceInfo(partnerAccount);
RemoteRoute route = resolveRemoteRouteFromInstance(info, action);
CloudConnectionContext ctx = CloudConnectionContext.builder()
        .authType(route.authType())       // from remoteProperty.headers[0].type
        .cloudProfile(route.cloudProfile()) // from bizRobotTag or SS fallback
        .build();
cloudAuthService.applyAuth(builder, ctx.getAppId(), ctx.getAuthType());
```

Base:

```java
RemoteRoute route = resolveRemoteRouteFromLegacyConfig(businessTag, action);
```

Bad:

```java
String ak = payload.path("ak").asText();
RemoteRoute route = resolveOnlyByAk(ak);
```

The bad case cannot route no-AK remote assistants and causes skill-server to block or degrade before the gateway can call the cloud endpoint.

### 6. Tests Required

- `AssistantInstanceInfoServiceTest`: success, not-exists/empty data, upstream failure, and cache behavior.
- `CloudAgentServiceTest`: action-specific `remoteProperty` selection, no-AK remote invocation, first `headers[0].type` auth mapping, `bizRobotTag` cloudProfile mapping, and legacy fallback.
- `CloudAuthServiceTest`: HTTP and WebSocket authType strategy dispatch, including unknown authType failure.

### 7. Wrong vs Correct

Wrong: use AK as the only route key for cloud assistants, map `remoteProperty.dataProtocol` into `cloudProfile`, or replay `remoteProperty.headers[].customKey/customValue` as raw outbound headers.

Correct: use `assistantAccount` / `partnerAccount` to load instance metadata, select `remoteProperty` by action, derive `authType` from the first `header.type`, keep `cloudProfile` from `bizRobotTag` or SS fallback, and treat AK as optional context.
