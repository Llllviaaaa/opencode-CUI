# 异常处理

> `skill-server` 的错误处理不是单一路径，而是 REST、入站编排、WebSocket 传输、协议消息四条语义并存。

---

## 概览

当前代码中的错误承载方式分四层：

1. **请求结构错误**：Controller 直接返回 `400 + ApiResponse.error(...)`
2. **业务处理失败**：service 返回 `InboundResult.error(...)`，Controller 再包装成 `HTTP 200 + ApiResponse.code != 0`
3. **标准协议异常**：抛 `ProtocolException`，由 `GlobalExceptionHandler` 映射成状态码 + `ApiResponse`
4. **流式业务错误**：作为 `StreamMessage.Types.ERROR` / `session.error` 发给前端，而不是抛 MVC 异常

---

## 异常类型与返回语义

| 载体 | 触发位置 | HTTP 行为 | 典型场景 |
|------|----------|-----------|----------|
| `ApiResponse.error(400, ...)` | Controller 手工校验 | `400` 或 `200`，取决于入口 | 缺字段、非法 payload |
| `InboundResult.error(code, msg, ...)` | `InboundProcessingService` | Controller 包装为 `200 + body.code=code` | agent 离线、session 未就绪 |
| `ProtocolException` | MVC 调用链内部 | `GlobalExceptionHandler` 映射状态码 | 403/404/409 语义错误 |
| `StreamMessage.error(...)` | 流式编排 | WebSocket / Redis user-stream | 离线提示、协议层错误回传 |

代码证据：

- `GlobalExceptionHandler`：`skill-server/src/main/java/com/opencode/cui/skill/config/GlobalExceptionHandler.java:17-65`
- `InboundResult`：`skill-server/src/main/java/com/opencode/cui/skill/service/InboundProcessingService.java:402-410`
- `StreamMessage.error(...)`：`skill-server/src/main/java/com/opencode/cui/skill/model/StreamMessage.java:227-234`

---

## GlobalExceptionHandler

`ProtocolException` 是 MVC 标准异常通道，统一由 `GlobalExceptionHandler` 处理：

```java
@ExceptionHandler(ProtocolException.class)
public ResponseEntity<ApiResponse<Void>> handleProtocolException(ProtocolException ex) {
    log.warn("Protocol error: code={}, message={}", ex.getCode(), ex.getMessage());
    HttpStatus status = mapToHttpStatus(ex.getCode());
    return ResponseEntity.status(status).body(ApiResponse.error(ex.getCode(), ex.getMessage()));
}

@ExceptionHandler(IllegalArgumentException.class)
public ResponseEntity<ApiResponse<?>> handleIllegalArgument(IllegalArgumentException e) {
    log.warn("Illegal argument: {}", e.getMessage());
    return ResponseEntity.ok(ApiResponse.error(400, e.getMessage()));
}

@ExceptionHandler(Exception.class)
public ResponseEntity<ApiResponse<?>> handleGeneral(Exception e) {
    log.error("Unexpected error: {}", e.getMessage(), e);
    return ResponseEntity.status(500).body(ApiResponse.error(500, "Internal server error"));
}
```

来源：`skill-server/src/main/java/com/opencode/cui/skill/config/GlobalExceptionHandler.java:23-64`

对应异常类非常薄，只携带状态码：

```java
public class ProtocolException extends RuntimeException {
    private final int code;

    public ProtocolException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
```

来源：`skill-server/src/main/java/com/opencode/cui/skill/service/ProtocolException.java:8-27`

规则：

- 需要明确 HTTP 语义的错误，才抛 `ProtocolException`。
- 参数错误且无需中断整个 MVC 调用链时，优先直接返回 `ApiResponse.error(...)`。

---

## ApiResponse 约定

所有 REST 入口都回到同一个 envelope：`code=0` 成功，非 `0` 失败。

```java
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {
    private int code;
    private String errormsg;
    private T data;

    public static <T> ApiResponse<T> ok(T data) { ... }
    public static <T> ApiResponse<T> error(int code, String message) { ... }
}
```

来源：`skill-server/src/main/java/com/opencode/cui/skill/model/ApiResponse.java:19-62`

注意：

- `errormsg` 是当前稳定字段名，不要新造 `message` / `errorMessage`。
- offline 等业务失败不会丢失 session 线索；external 入口会把 `businessSessionId` / `welinkSessionId` 放进 `data`。

测试证据：`skill-server/src/test/java/com/opencode/cui/skill/controller/ExternalInboundControllerTest.java:161-179`

---

## Controller 层手工校验

当前项目没有使用 Bean Validation；入口 Controller 都是“手工校验 + 早返回”。

external 信封层校验：

```java
private String validateEnvelope(ExternalInvokeRequest request) {
    if (request == null) return "Request body is required";
    if (request.getAction() == null || request.getAction().isBlank()) return "action is required";
    if (request.getBusinessDomain() == null || request.getBusinessDomain().isBlank()) return "businessDomain is required";
    if (request.getSessionId() == null || request.getSessionId().isBlank()) return "sessionId is required";
    if (request.getAssistantAccount() == null || request.getAssistantAccount().isBlank()) return "assistantAccount is required";
    if (request.getSenderUserAccount() == null || request.getSenderUserAccount().isBlank()) return "senderUserAccount is required";
    return null;
}
```

来源：`skill-server/src/main/java/com/opencode/cui/skill/controller/ExternalInboundController.java:106-116`

IM 回调校验：

```java
private String validate(ImMessageRequest request) {
    if (request == null) {
        return "Request body is required";
    }
    if (!SkillSession.DOMAIN_IM.equalsIgnoreCase(request.businessDomain())) {
        return "Only IM inbound is supported";
    }
    if (!request.isTextMessage()) {
        return "Only text messages are supported";
    }
    return null;
}
```

来源：`skill-server/src/main/java/com/opencode/cui/skill/controller/ImInboundController.java:92-121`

规则：

- 缺字段 / 非法枚举 / 不支持的消息类型，直接返回 `400`。
- 不要把“结构错了”的请求下沉到 service 再报业务错。

---

## senderUserAccount 信封层迁移

`senderUserAccount` 已经是**信封层必填字段**，不再接受 `payload.senderUserAccount`。

Controller 校验已经强制要求信封字段：

```java
if (request.getSenderUserAccount() == null || request.getSenderUserAccount().isBlank()) {
    return "senderUserAccount is required";
}
```

来源：`skill-server/src/main/java/com/opencode/cui/skill/controller/ExternalInboundController.java:113-115`

测试明确覆盖了旧字段被忽略：

```java
@Test
@DisplayName("D1 hard cut: payload.senderUserAccount is ignored, envelope required")
void legacyPayloadSenderUserAccountIsIgnored() throws Exception {
    String json = "{\"action\":\"chat\","
            + "\"businessDomain\":\"im\",\"sessionType\":\"direct\","
            + "\"sessionId\":\"dm-001\",\"assistantAccount\":\"assist-01\","
            + "\"payload\":{\"content\":\"hello\",\"senderUserAccount\":\"legacy-user\"}}";
    ...
    assertEquals("senderUserAccount is required", response.getBody().getErrormsg());
}
```

来源：`skill-server/src/test/java/com/opencode/cui/skill/controller/ExternalInboundControllerTest.java:137-150`

规则：

- 新协议字段只能加在 `ExternalInvokeRequest` / `ImMessageRequest` 的信封层。
- 不要再兼容 `payload.senderUserAccount`，测试已经把这个兼容口关闭了。

---

## InboundResult 与业务失败

IM / external 共用的 `InboundProcessingService` 不直接返回 MVC 异常，而是返回 `InboundResult`。

agent 离线的典型路径：

```java
private InboundResult checkAgentOnline(String businessDomain, String sessionType,
                                       String sessionId, String ak,
                                       String assistantAccount) {
    if (!assistantIdProperties.isEnabled()) return null;
    AssistantScopeStrategy scopeStrategy = scopeDispatcher.getStrategy(
            assistantInfoService.getCachedScope(ak));
    if (!scopeStrategy.requiresOnlineCheck()) return null;
    if (gatewayApiClient.getAgentByAk(ak) != null) return null;

    handleAgentOffline(businessDomain, sessionType, sessionId, ak, assistantAccount);
    return InboundResult.error(503, offlineMessageProvider.get(), sessionId, ...);
}
```

来源：`skill-server/src/main/java/com/opencode/cui/skill/service/InboundProcessingService.java:341-359`

IM Controller 收到业务失败后返回 `HTTP 200 + code`：

```java
if (!result.success()) {
    log.warn("[EXIT] ImInboundController.receiveMessage: reason=processing_failed, code={}, message={}, durationMs={}",
            result.code(), result.message(), elapsedMs);
    return ResponseEntity.ok(ApiResponse.error(result.code(), result.message()));
}
```

来源：`skill-server/src/main/java/com/opencode/cui/skill/controller/ImInboundController.java:75-82`

规则：

- 需要保留会话上下文、但不应被上游重试器当成 HTTP 失败的场景，用 `InboundResult`。
- 只有真正的请求结构错误才返回 `400`。

---

## WebSocket / 流式错误语义

### 1. 传输层错误：记录并清理，不发异常帧

客户端控制消息解析失败时，`SkillStreamHandler` 只记录 warn，不会构造特殊 frame：

```java
try {
    JsonNode node = objectMapper.readTree(message.getPayload());
    ...
} catch (Exception e) {
    log.warn("Failed to parse client message on stream endpoint: wsId={}, error={}",
            session.getId(), e.getMessage());
}
```

来源：`skill-server/src/main/java/com/opencode/cui/skill/ws/SkillStreamHandler.java:103-126`

transport error 只做清理：

```java
public void handleTransportError(WebSocketSession session, Throwable exception) {
    ...
    unregisterSubscriber(session);
}
```

来源：`skill-server/src/main/java/com/opencode/cui/skill/ws/SkillStreamHandler.java:135-146`

### 2. 业务层错误：作为 `StreamMessage` 推送

agent 离线时，不抛 WebSocket 异常，而是发协议消息：

```java
StreamMessage offlineMsg = StreamMessage.builder()
        .type(StreamMessage.Types.ERROR)
        .error(offlineMessage)
        .build();
emitter.emitToSession(session, String.valueOf(session.getId()), null, offlineMsg);
```

来源：`skill-server/src/main/java/com/opencode/cui/skill/service/InboundProcessingService.java:365-381`

规则：

- 不要期待 `GlobalExceptionHandler` 能处理 WebSocket handler 里的错误。
- 要通知前端业务失败，用 `StreamMessage.Types.ERROR` 或 `session.error`，不要抛 Java 异常给 transport 层。

---

## try/catch 模式

错误处理要么**早返回**，要么**记日志后保留可恢复路径**，不要吞异常。

一个典型的可恢复 try/catch 在 `handleAgentOffline`：系统消息持久化失败不会阻断前端离线提示。

```java
if (session.isImDirectSession()) {
    try {
        messageService.saveSystemMessage(session.getId(), offlineMessage);
    } catch (Exception e) {
        log.error("Failed to persist agent_offline message: {}", e.getMessage());
    }
}
```

来源：`skill-server/src/main/java/com/opencode/cui/skill/service/InboundProcessingService.java:376-381`

规则：

- catch 之后如果选择继续执行，必须说明“为什么这个失败可以降级”。
- 只记录 `e.getMessage()` 仍然不够时，再带上完整堆栈。

---

## 常见错误

1. 不要把信封字段缺失当成业务失败返回 `200`；这类错误必须直接 `400`。
2. 不要在 WebSocket handler 里抛 MVC 风格异常；没有 `GlobalExceptionHandler` 会接住它。
3. 不要吞掉持久化 / Redis / 序列化异常；至少要 `log.warn` 或 `log.error`。
4. 不要复活旧的 `payload.senderUserAccount` 兼容口；当前 external 合约已经固定。
