# 日志规范

> `ai-gateway` 使用 SLF4J + Log4j2，统一日志标签是 `[ai-gateway]`。
---

## 概览

| 项目 | 真实实现 |
|------|----------|
| 日志框架 | SLF4J + Log4j2 |
| 服务标签 | `[ai-gateway]` |
| 固定 MDC 槽位 | `traceId`, `sessionId`, `ak` |
| 扩展 MDC key | `userId`, `scenario`（用于业务日志，不在默认 pattern 中单独占位） |
| 配置文件 | `ai-gateway/src/main/resources/log4j2-spring.xml` |

## 日志格式

```xml
<!-- Source: ai-gateway/src/main/resources/log4j2-spring.xml:3-28 -->
<Property name="SERVICE_NAME">ai-gateway</Property>
<Property name="LOG_PATTERN">
  %d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level [${SERVICE_NAME}]
  [%X{traceId}] [%X{sessionId}] [%X{ak}] %logger{36}.%method - %msg%n
</Property>
```

因此默认格式至少包含：

```text
[ai-gateway] [traceId] [sessionId] [ak]
```

注意：`userId` 和 `scenario` 是 MDC 中的真实 key，但默认 pattern 没把它们单独展开；如果需要，直接写进消息体即可。

## MDC Key 约定

`MdcConstants` 只定义 5 个自定义 key，任何业务代码都不能直接手写字符串。

| 常量 | Key | 用途 |
|------|-----|------|
| `TRACE_ID` | `traceId` | 跨服务追踪 ID |
| `SESSION_ID` | `sessionId` | `welinkSessionId` 级别关联 |
| `AK` | `ak` | Agent 路由主键 |
| `USER_ID` | `userId` | 业务侧用户标识 |
| `SCENARIO` | `scenario` | `rest-get`、`ws-agent-register` 等场景标签 |

来源：`ai-gateway/src/main/java/com/opencode/cui/gateway/logging/MdcConstants.java:14-32`。

## MDC 使用方式

```java
// Source: ai-gateway/src/main/java/com/opencode/cui/gateway/logging/MdcHelper.java:60-123
MdcHelper.fromGatewayMessage(message);
MdcHelper.putScenario("ws-agent-" + type);

Map<String, String> previous = MdcHelper.snapshot();
try {
    // ... 业务逻辑
} finally {
    MdcHelper.restore(previous);
}
```

约束：

- 进入 WebSocket / Service 边界时先 `fromGatewayMessage(...)`。
- 退出边界时必须 `clearAll()` 或 `restore(snapshot)`。
- 需要跨线程传递上下文时，先 `snapshot()`，再在目标线程 `restore()`。

## REST 请求日志模式

REST 接口统一通过 `MdcRequestInterceptor` 处理：

```java
// Source: ai-gateway/src/main/java/com/opencode/cui/gateway/config/MdcRequestInterceptor.java:16-32
String traceId = request.getHeader("X-Trace-Id");
if (traceId != null && !traceId.isBlank()) {
    MdcHelper.putTraceId(traceId);
} else {
    MdcHelper.ensureTraceId();
}
MdcHelper.putScenario("rest-" + request.getMethod().toLowerCase());
```

这意味着：

- 如果上游已经带了 `X-Trace-Id`，必须沿用，不要重生一个新的。
- REST 完成后立刻清理 MDC，防止 Tomcat 线程复用造成串号。

## WebSocket / 中继日志模式

| 位置 | 约定 | 真实示例 |
|------|------|----------|
| `AgentWebSocketHandler` | 握手使用 `[AUTH]`，业务入口/出口使用 `[ENTRY]` / `[EXIT]` | `ws/AgentWebSocketHandler.java:131-193,322-392,428-438` |
| `SkillWebSocketHandler` | `invoke/route_confirm/route_reject` 分支都打 `[ENTRY]` / `[EXIT]` | `ws/SkillWebSocketHandler.java:88-117` |
| `EventRelayService` | 投递给 Agent 使用 `[EXIT->AGENT]`，投递给 Source 使用 `[EXIT->SOURCE]` | `service/EventRelayService.java:148-160,249-306` |
| `RedisMessageBroker` | key 写入/删除与 pub/sub 订阅操作打印结构化上下文 | `service/RedisMessageBroker.java:121-150,230-267,490-549,703-777` |

## 外部调用计时

`LogTimer` 专门用于包裹外部依赖调用，成功时写 `INFO`，失败时写 `ERROR`。

```java
// Source: ai-gateway/src/main/java/com/opencode/cui/gateway/logging/LogTimer.java:22-48
String result = LogTimer.timed(log, "IdentityAPI.check", () -> identityClient.check(...));
LogTimer.timedRun(log, "CloudRoute.refresh", () -> refreshRoute(...));
```

输出固定以 `[EXT_CALL]` 开头，便于检索。

## 敏感信息脱敏

对 MAC 地址、token 等敏感值统一走 `SensitiveDataMasker`：

```java
// Source: ai-gateway/src/main/java/com/opencode/cui/gateway/logging/SensitiveDataMasker.java:13-28
SensitiveDataMasker.maskMac(macAddress);   // -> ****:EE:FF
SensitiveDataMasker.maskToken(token);      // -> abcd****mnop
```

`AgentWebSocketHandler.handleRegister()` 已经用它输出 MAC 地址。来源：`ws/AgentWebSocketHandler.java:387-392`。

## 禁止事项

1. 不要在文档或代码示例中混用其他服务标签；本服务统一是 `[ai-gateway]`。
2. 不要直接 `MDC.put("traceId", ...)`，统一走 `MdcHelper`。
3. 不要遗漏 `clearAll()` / `restore()`，尤其是在 WebSocket handler 和异步发送链路里。
4. 不要在 `INFO` 级别打印原始 token、完整 MAC、完整 payload 大对象。
