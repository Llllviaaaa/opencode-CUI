# 移除会话访问控制中的 Agent 在线判断

## 背景

云端助手永远在线，不需要判断 agent 是否在线。即使是个人助手，获取会话、关闭会话、查询历史消息等纯数据操作也不应依赖 agent 在线状态。

当前 `SessionAccessControlService.requireSessionAccess()` 通过 `isAkOwnedByUser()` → `getOnlineAgentsByUserId()` 校验 AK 归属，该实现将"AK 归属校验"和"Agent 在线判断"耦合在一起，导致 agent 离线时所有会话操作返回 403。

## 变更范围

### 变更 1：简化 `requireSessionAccess()`

**文件**: `SessionAccessControlService.java`

移除 `isAkOwnedByUser()` 调用，只保留 userId 匹配校验。

理由：会话创建时 AK 已经过验证，session 表中的 userId 字段本身就是归属证明，无需每次请求再通过在线列表间接验证。

**变更前**:
```java
public SkillSession requireSessionAccess(Long sessionId, String userIdCookie) {
    String userId = requireUserId(userIdCookie);
    SkillSession session = sessionService.getSession(sessionId);

    if (!userId.equals(session.getUserId())) {
        throw new ProtocolException(403, "Session access denied");
    }

    if (session.getAk() != null && !session.getAk().isBlank()
            && !gatewayApiClient.isAkOwnedByUser(session.getAk(), userId)) {
        throw new ProtocolException(403, "Session access denied");
    }

    return session;
}
```

**变更后**:
```java
public SkillSession requireSessionAccess(Long sessionId, String userIdCookie) {
    String userId = requireUserId(userIdCookie);
    SkillSession session = sessionService.getSession(sessionId);

    if (!userId.equals(session.getUserId())) {
        throw new ProtocolException(403, "Session access denied");
    }

    return session;
}
```

### 变更 2：移除 `GatewayApiClient` 依赖

**文件**: `SessionAccessControlService.java`

`SessionAccessControlService` 不再需要 `GatewayApiClient` 依赖，移除构造函数注入。

### 变更 3：清理 `isAkOwnedByUser()` 方法

**文件**: `GatewayApiClient.java`

`isAkOwnedByUser()` 方法在移除 `requireSessionAccess()` 的调用后，检查是否还有其他调用方。如果没有则删除该方法。

### 不变更的部分

以下在线检查逻辑**保持不变**：

1. **`SkillMessageController.routeToGateway()`** — 发送消息时的 `requiresOnlineCheck()` + `getAgentByAk()` 检查
2. **`InboundProcessingService.processChat()`** — IM 入站消息处理时的在线检查
3. **`AgentQueryController.getOnlineAgents()`** — 查询在线 agent 列表接口
4. **`AssistantScopeStrategy.requiresOnlineCheck()`** — scope 策略接口及其 Personal/Business 实现

这些检查是发送消息路由到 gateway 时的合理前置校验，与会话数据访问无关。

## 受影响接口

| 接口 | 变更前 | 变更后 |
|------|--------|--------|
| `GET /sessions/{id}` | agent 离线 → 403 | 仅校验 userId |
| `DELETE /sessions/{id}` | agent 离线 → 403 | 仅校验 userId |
| `POST /sessions/{id}/abort` | agent 离线 → 403 | 仅校验 userId |
| `POST /sessions/{id}/messages` | agent 离线 → 403（访问控制层） | 仅校验 userId（发送时仍有在线检查） |
| `GET /sessions/{id}/messages` | agent 离线 → 403 | 仅校验 userId |
| `GET /sessions/{id}/messages/history` | agent 离线 → 403 | 仅校验 userId |
| `POST /sessions/{id}/send-to-im` | agent 离线 → 403 | 仅校验 userId |
| `POST /sessions/{id}/permissions/{permId}` | agent 离线 → 403 | 仅校验 userId |

## 安全性分析

- **userId 匹配**（保留）：确保用户只能访问自己创建的会话 — 这是核心权限校验
- **AK 在线列表归属**（移除）：属于冗余校验，因为会话创建时已绑定 userId 和 AK 的关系

移除后安全性不降低。

## 测试要点

- 验证 agent 离线时，获取会话/历史消息等接口仍能正常返回
- 验证 userId 不匹配时仍然返回 403
- 验证发送消息时个人助手的在线检查仍然生效
- 更新 `SessionAccessControlService` 相关单元测试
