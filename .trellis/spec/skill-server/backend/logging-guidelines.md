# 日志规范

> `skill-server` 当前使用 SLF4J + Log4j2；本文件只记录已落地的 pattern、MDC key 和日志语义。

---

## 概览

- **日志实现**：SLF4J API + Log4j2 配置
- **服务前缀**：`[skill-server]`
- **MDC key**：`traceId`、`sessionId`、`ak`、`userId`、`scenario`
- **常见语义前缀**：`[ENTRY]`、`[EXIT]`、`[SKIP]`、`[EXT_CALL]`

注意：旧 seed 文档里的 `logback-spring.xml` 已过期；当前主配置文件是 `skill-server/src/main/resources/log4j2-spring.xml`。

---

## 日志布局

Log4j2 当前 pattern：

`%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level [${SERVICE_NAME}] [%X{traceId}] [%X{sessionId}] [%X{ak}] %logger{36}.%method - %msg%n`

资源证据：`skill-server/src/main/resources/log4j2-spring.xml:3-35`

要点：

- 固定服务前缀是 `[skill-server]`
- 默认布局打印 `traceId` / `sessionId` / `ak`
- `userId` 与 `scenario` 已进入 MDC，但当前默认 layout 没有直接打印它们

---

## MDC key 约定

MDC 常量统一定义在 `MdcConstants`，不要在业务代码里手写字符串：

```java
public final class MdcConstants {
    public static final String TRACE_ID = "traceId";
    public static final String SESSION_ID = "sessionId";
    public static final String AK = "ak";
    public static final String USER_ID = "userId";
    public static final String SCENARIO = "scenario";
}
```

来源：`skill-server/src/main/java/com/opencode/cui/skill/logging/MdcConstants.java:11-35`

规则：

- 允许使用的业务 MDC key 只有这五个。
- 新 key 必须先进 `MdcConstants`，再考虑是否加入 layout。

---

## REST 入口 MDC 生命周期

REST 请求统一由 `MdcRequestInterceptor` 初始化和清理 MDC：

```java
@Override
public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
    String traceId = request.getHeader(TRACE_ID_HEADER);
    if (traceId != null && !traceId.isBlank()) {
        MdcHelper.putTraceId(traceId);
    } else {
        MdcHelper.ensureTraceId();
    }

    String path = request.getRequestURI();
    Matcher matcher = SESSION_PATH_PATTERN.matcher(path);
    if (matcher.find()) {
        MdcHelper.putSessionId(matcher.group(1));
    }

    MdcHelper.putScenario("rest-" + request.getMethod().toLowerCase());
    return true;
}

@Override
public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
        Object handler, Exception ex) {
    MdcHelper.clearAll();
}
```

来源：`skill-server/src/main/java/com/opencode/cui/skill/config/MdcRequestInterceptor.java:25-50`

规则：

- Controller 不要自己生成 traceId。
- 请求结束必须清空 MDC，避免线程复用串值。

---

## 手工 MDC 操作

Gateway / WebSocket 场景不经过 MVC 拦截器时，通过 `MdcHelper` 设置、恢复、清理：

```java
public static void fromJsonNode(JsonNode node) {
    if (node == null) {
        return;
    }
    safePut(MdcConstants.TRACE_ID, textOrNull(node, "traceId"));
    safePut(MdcConstants.SESSION_ID, textOrNull(node, "welinkSessionId"));
    safePut(MdcConstants.AK, textOrNull(node, "ak"));
    safePut(MdcConstants.USER_ID, textOrNull(node, "userId"));
}

public static Map<String, String> snapshot() {
    Map<String, String> snap = new LinkedHashMap<>();
    for (String key : MdcConstants.ALL_KEYS) {
        snap.put(key, MDC.get(key));
    }
    return snap;
}
```

来源：`skill-server/src/main/java/com/opencode/cui/skill/logging/MdcHelper.java:66-149`

规则：

- 不要直接 `MDC.put(...)`。
- 异步线程要用 `snapshot()` / `restore()`，不要假设线程池能保留上下文。

---

## Controller 日志语义

Controller 入口普遍使用 `[ENTRY]` / `[EXIT]` + `durationMs`：

```java
long start = System.nanoTime();
log.info("[ENTRY] createSession: ak={}, userId={}", request.getAk(), userIdCookie);
...
long elapsedMs = (System.nanoTime() - start) / 1_000_000;
log.info("[EXIT] createSession: sessionId={}, durationMs={}", session.getId(), elapsedMs);
```

来源：`skill-server/src/main/java/com/opencode/cui/skill/controller/SkillSessionController.java:72-110`

IM 入站失败时也会打 `[EXIT]`，但带失败原因：

```java
if (!result.success()) {
    log.warn("[EXIT] ImInboundController.receiveMessage: reason=processing_failed, code={}, message={}, durationMs={}",
            result.code(), result.message(), elapsedMs);
    return ResponseEntity.ok(ApiResponse.error(result.code(), result.message()));
}
```

来源：`skill-server/src/main/java/com/opencode/cui/skill/controller/ImInboundController.java:73-82`

规则：

- Controller 的成功 / 失败出口都要带 `durationMs`。
- 参数多时只记关键关联字段，不要打印整包 payload。

---

## [SKIP] 与降级日志

需要解释“为什么没继续处理”时，统一用 `[SKIP]`。

```java
if (resolveResult == null) {
    log.warn("[SKIP] processChat: reason=resolve_failed, assistantAccount={}", assistantAccount);
    return InboundResult.error(404, "Invalid assistant account");
}
...
log.warn("[SKIP] checkAgentOnline: reason=agent_offline, ak={}, domain={}, sessionType={}, sessionId={}",
        ak, businessDomain, sessionType, sessionId);
```

来源：`skill-server/src/main/java/com/opencode/cui/skill/service/InboundProcessingService.java:105-110,350-358`

规则：

- `[SKIP]` 必须带 `reason=...`
- 用 `WARN` 记录业务降级，用 `ERROR` 记录不可恢复异常

---

## 降级路径的量化可观测字段

`[SKIP] reason=...` 解释了"为什么降级"，但 SRE 还需要知道"降级到什么程度、命中频率多高"。fallback / 兼容 / 重试路径必须额外带**量化字段**，让 ELK / metric 能聚合出降级率，而不是只看到分散的 WARN 文本。

约定字段命名：

| 字段 | 含义 | 取值示例 |
|------|------|---------|
| `<entity>_format` | 反序列化时识别到的格式分支 | `json` / `plain` / `fallback_invalid_json` |
| `fields_degraded` | 本条记录的关键字段是否走了 fallback | `true` / `false`（bool） |
| `<entity>_count` | 本次批处理 / 重试涉及的条数 | 整数 |
| `retry_count` | 第几次重试 | 整数 |

```java
// retry / 反序列化入口结构化字段
log.info("[ENTRY] retryPendingMessages: sessionId={}, pending_count={}, retry_count={}",
        sessionId, messages.size(), retryCount);

for (PendingChatRequest req : messages) {
    log.info("retryPendingMessages.consume: pending_format={}, fields_degraded={}",
            req.pendingFormat(), req.isFieldsDegraded());
    ...
}
```

参考实现：`skill-server/src/main/java/com/opencode/cui/skill/service/GatewayMessageRouter.java::retryPendingMessages`、`SessionRebuildService.java::consumePendingMessages`。

规则：

- 凡有 fallback / 老格式兼容 / 重试的代码路径，**必须**至少带 `<entity>_format` + `fields_degraded` 两个字段。
- 字段值用稳定 enum 字符串（`json` / `plain` / ...），不要拼可变上下文（如 `"json_for_session_xxx"`），否则 ELK 聚合不出来。
- 同一 fallback 概念在不同模块（pending / cache / config）复用同一组字段名前缀，不要每个模块发明一套。

---

## 外部调用计时

`LogTimer` 是当前推荐的外部调用计时工具：

```java
public static <T> T timed(Logger log, String operation, Supplier<T> action) {
    long start = System.nanoTime();
    try {
        T result = action.get();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        log.info("[EXT_CALL] {} completed: durationMs={}", operation, elapsedMs);
        return result;
    } catch (Exception e) {
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        log.error("[EXT_CALL] {} failed: durationMs={}, error={}", operation, elapsedMs, e.getMessage());
        throw e;
    }
}
```

来源：`skill-server/src/main/java/com/opencode/cui/skill/logging/LogTimer.java:22-52`

真实调用例子：

```java
ResponseEntity<String> response = LogTimer.timed(
        log, "ImMessage.send(chatId=" + chatId + ")",
        () -> restTemplate.postForEntity(sendUrl, request, String.class));
```

来源：`skill-server/src/main/java/com/opencode/cui/skill/service/ImMessageService.java:63-76`

补充说明：

- `GatewayApiClient` 仍保留手写 `start / elapsedMs` 计时模式，这是当前代码事实，不是推荐新样式。
- 新增阻塞型外部调用时，优先用 `LogTimer`，除非需要更细粒度的 success / non-success 区分。

手写计时证据：`skill-server/src/main/java/com/opencode/cui/skill/service/GatewayApiClient.java:50-139`

---

## 敏感信息与消息体

敏感值必须脱敏；大消息体默认不要打到 INFO。

脱敏工具：

```java
log.info("Agent connected: mac={}, token={}",
    SensitiveDataMasker.maskMac(macAddress),
    SensitiveDataMasker.maskToken(token));
```

来源：`skill-server/src/main/java/com/opencode/cui/skill/logging/SensitiveDataMasker.java`

规则：

- token / 密钥 / 完整用户输入不要直接落 INFO。
- 必须打印消息正文时，优先降到 DEBUG，并限制长度。

---

## Scenario: SS Stream Event Boundary Logging

### 1. Scope / Trigger

- Trigger: logging stream messages sent from SS to miniapp WebSocket clients and external WebSocket clients.
- Boundary: log only after the actual WebSocket `sendMessage(...)` succeeds. Do not log the same stream event from delivery strategies, emitters, Redis relay helpers, or internal route methods.
- Source files: `skill-server/src/main/java/com/opencode/cui/skill/ws/SkillStreamHandler.java`, `skill-server/src/main/java/com/opencode/cui/skill/ws/ExternalStreamHandler.java`, `skill-server/src/main/java/com/opencode/cui/skill/logging/StreamEventLogHelper.java`.

### 2. Signatures

```java
StreamEventLogHelper.outbound(Logger log, String endpoint, String result, String payload);
StreamEventLogHelper.outbound(Logger log, String endpoint, String result, Object payload);
```

### 3. Contracts

- Log format must keep service/instance/MDC before the level:
  `%d [%thread] [${SERVICE_NAME}] [${INSTANCE_ID}] [%X{traceId}] [%X{sessionId}] [%X{ak}] [%X{userId}] [%X{scenario}] %-5level ...`
- Stream event payload must be the exact payload string sent to the WebSocket. Do not flatten, split, or re-shape `content` fields.
- Use `endpoint=ss.miniapp` for miniapp WebSocket egress and `endpoint=ss.external_ws` for external WebSocket egress.
- The helper log line shape is fixed:
  `event=ws_event direction=outbound endpoint={} result={} payload={}`
- Internal high-frequency `tool_event` route logs must be `DEBUG`; production `INFO` should keep only actual outbound events plus lifecycle/error records.

### 4. Validation & Error Matrix

| Case | Required behavior |
|------|-------------------|
| Local miniapp WebSocket send succeeds | Log one `direction=outbound endpoint=ss.miniapp` record with the raw sent payload. |
| External WebSocket send succeeds | Log one `direction=outbound endpoint=ss.external_ws` record with the raw sent payload. |
| WebSocket send fails | Log the existing warn/error with failure reason; do not log a successful outbound event. |
| Redis relay delivers to local SS handler | Log only when the final local WebSocket send succeeds. |
| Internal route or delivery strategy sees `tool_event` | Use `DEBUG` or no log; do not duplicate the boundary event log. |

### 5. Tests Required

- `StreamEventLogHelperTest` must assert the fixed `event=ws_event` outbound log shape.
- Handler tests should assert outbound logging happens after send success when the handler is touched.
- Routing tests should verify high-frequency `tool_event` internal logs do not require `INFO` assertions.

### 6. Wrong vs Correct

#### Wrong

```java
log.info("[DELIVERY] Miniapp: sessionId={}, type={}, userId={}", sessionId, type, userId);
log.info("[RELAY-RX] External relay: domain={}, sent={}", domain, sent);
```

#### Correct

```java
session.sendMessage(new TextMessage(messageText));
StreamEventLogHelper.outbound(log, "ss.miniapp", "sent", messageText);
```

## 常见错误

1. 不要再引用 `logback-spring.xml`；当前配置是 `log4j2-spring.xml`。
2. 不要直接 `MDC.put()`；统一走 `MdcHelper`。
3. 不要忘记 `clearAll()`；线程池复用会污染后续请求。
4. 不要给 `[SKIP]` 留空原因。
5. 不要把外部调用的耗时日志写成无上下文的 `done` / `failed`；至少要带 operation 和 `durationMs`。
