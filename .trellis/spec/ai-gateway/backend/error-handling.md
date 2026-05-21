# 错误处理

> `ai-gateway` 的错误语义分散在 Controller、WebSocket 握手层和少量基础设施异常中。
---

## 概览

当前实现的错误处理分三层：

1. **REST Controller**：直接返回 `ResponseEntity<ApiResponse<?>>`，显式决定 HTTP 状态与业务码。
2. **WebSocket 握手 / 消息处理**：通过返回 `false`、发送 `register_rejected`、或关闭连接来表达失败，不依赖全局异常处理器。
3. **基础设施 Service**：对外部依赖失败抛出局部运行时异常（例如 `IdentityApiClient.IdentityApiException`），由上层决定降级或记录日志。

## 当前真实错误面

| 位置 | 失败表达方式 | 真实示例 |
|------|--------------|----------|
| `AgentController` | `401/404/400 + ApiResponse.error(...)` | `controller/AgentController.java:62-145` |
| `CloudPushController` | `400/403 + ApiResponse.error(...)` | `controller/CloudPushController.java:45-87` |
| `AgentWebSocketHandler.beforeHandshake` | 返回 `false` 拒绝握手 | `ws/AgentWebSocketHandler.java:127-194` |
| `AgentWebSocketHandler.handleRegister` | 发送 `register_rejected` 后用自定义 close code 关闭 | `ws/AgentWebSocketHandler.java:313-397` |
| `AkSkAuthService.verify` | 返回 `userId` 或 `null`，失败时清理 nonce 并记录日志 | `service/AkSkAuthService.java:97-150` |
| `IdentityApiClient.check` | 抛 `IdentityApiException` 表示网络/超时/解析失败 | `service/IdentityApiClient.java:56-121` |

## REST 接口错误模式

Controller 里是“就地返回”风格，而不是把校验失败抛给全局 advice。

```java
// Source: ai-gateway/src/main/java/com/opencode/cui/gateway/controller/AgentController.java:67-84,121-144
if (!isAuthorized(authorization)) {
    return ResponseEntity.status(401)
            .body(ApiResponse.error(401, "Invalid or missing internal token"));
}

if (ak == null || ak.isBlank()) {
    return ResponseEntity.badRequest().body(ApiResponse.error(400, "ak is required"));
}
```

`CloudPushController` 也采用同一模式，只是针对云推送场景返回 `400`/`403`：

```java
// Source: ai-gateway/src/main/java/com/opencode/cui/gateway/controller/CloudPushController.java:47-78
if (isBlank(request.getAssistantAccount())) {
    return ResponseEntity.badRequest().body(ApiResponse.error(400, "assistantAccount is required"));
}
if (!request.getUserAccount().equals(resolved.getCreateBy())) {
    return ResponseEntity.status(403)
            .body(ApiResponse.error(403, "userAccount does not match assistant creator"));
}
```

## WebSocket 握手与注册错误模式

Agent 握手失败不会抛异常，而是直接拒绝升级；注册阶段失败则返回协议消息并关闭连接。

```java
// Source: ai-gateway/src/main/java/com/opencode/cui/gateway/ws/AgentWebSocketHandler.java
if (authPayload == null) {
    log.warn("[AUTH] AgentWSHandler.beforeHandshake: reason=no_auth_subprotocol");
    return false;
}

String userId = akSkAuthService.verify(ak, ts, nonce, sign);
if (userId == null) {
    log.warn("[AUTH] AgentWSHandler.beforeHandshake: reason=auth_failed, ak={}", ak);
    return false;
}

String remoteOwnerGateway = findRemoteOwnerGateway(akId);
if (remoteOwnerGateway != null) {
    sendAndClose(session, GatewayMessage.registerRejected("duplicate_connection"), CLOSE_DUPLICATE);
    return;
}

if (eventRelayService.hasAgentSession(akId)) {
    sendAndClose(session, GatewayMessage.registerRejected("duplicate_connection"), CLOSE_DUPLICATE);
    return;
}
```

重复连接现在是两层检查：先看 Redis 中的全局连接位置是否指向其他 Gateway 实例，再看当前实例内是否已有同 AK 的 open session。两种情况都使用 `duplicate_connection` 与 `4409`，保持协议返回稳定。

`SkillWebSocketHandler` 对内部来源也沿用相同风格：子协议校验失败时直接 `return false`，见 `ws/SkillWebSocketHandler.java:47-61,135-175`。

## 服务级异常与降级

`ai-gateway` 里只有少数明确的领域异常类型，最重要的是 `IdentityApiClient.IdentityApiException`。它只表达“外部 identity API 不可用/超时/HTTP 非 200/解析失败”，调用方需要自己决定是否降级。

```java
// Source: ai-gateway/src/main/java/com/opencode/cui/gateway/service/IdentityApiClient.java:56-121
if (!enabled) {
    throw new IdentityApiException("Identity API not configured");
}
if (response.statusCode() != 200) {
    throw new IdentityApiException("HTTP " + response.statusCode());
}
throw new IdentityApiException("API call failed: " + e.getMessage(), e);
```

真实调用链里，`AkSkAuthService.resolveIdentity()` 会捕获这个异常并返回 `null`，再由握手层拒绝连接；来源：`service/AkSkAuthService.java:218-239`。

## 错误码与状态码约定

| HTTP 状态 | `ApiResponse.code` | 使用场景 |
|-----------|--------------------|----------|
| `200` | `0` | 成功 |
| `400` | `400` | 缺参数、参数不合法、Agent 离线、缺少活跃 WS 会话 |
| `401` | `401` | 内部 Bearer token 无效 |
| `403` | `403` | 云推送的 `userAccount` 与助手创建者不匹配 |
| `404` | `404` | AK 对应 Agent 不存在 |
| WebSocket close `4403/4408/4409` | 协议层，不走 `ApiResponse` | 设备绑定失败、注册超时、重复连接 |

## 写新代码时的判断规则

| 场景 | 建议做法 |
|------|----------|
| REST 参数校验失败 | 直接 `return ResponseEntity.status(...).body(ApiResponse.error(...))` |
| WebSocket 握手失败 | 记录带原因的日志并 `return false` |
| 已完成握手但注册失败 | 发送 `GatewayMessage.registerRejected(reason)`，随后关闭连接 |
| 外部依赖临时不可用 | 抛局部异常或返回 `null`，由调用方降级，不要假装成功 |
| Redis / JSON 处理失败 | 记录 `log.error`，必要时丢弃该消息，避免把坏状态写回路由表 |

## 禁止事项

1. 不要在 `ai-gateway` 文档中虚构全局异常处理器；当前服务没有这层。
2. 不要把握手拒绝写成 HTTP 风格异常；WebSocket 协议层应直接拒绝或关闭。
3. 不要把 AK/SK 校验失败当成 500；这是认证失败或外部鉴权不可用，需分日志和返回语义。
4. 不要吞掉基础设施异常且不记日志，尤其是 Identity API、Redis 中继、JSON 反序列化错误。
