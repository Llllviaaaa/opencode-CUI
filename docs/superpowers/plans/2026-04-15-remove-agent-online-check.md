# 移除会话访问控制中的 Agent 在线判断 — 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 `requireSessionAccess()` 中的 agent 在线判断移除，改由业务层按 scope 策略显式检查，解决云端助手被错误拦截的问题。

**Architecture:** 从 `SessionAccessControlService` 移除 `GatewayApiClient` 依赖和 `isAkOwnedByUser()` 调用，仅保留 userId 匹配。在 `replyPermission` 中补充与 `routeToGateway()` 相同的 scope 感知在线检查。清理不再使用的 `isAkOwnedByUser()` 方法。

**Tech Stack:** Java 17, Spring Boot, Mockito, Maven

**Spec:** `docs/superpowers/specs/2026-04-15-remove-agent-online-check-from-session-access-design.md`

---

### Task 1: 简化 `SessionAccessControlService`

**Files:**
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/service/SessionAccessControlService.java`

- [ ] **Step 1: 移除 `isAkOwnedByUser()` 调用和 `GatewayApiClient` 依赖**

将 `SessionAccessControlService.java` 修改为：

```java
package com.opencode.cui.skill.service;

import com.opencode.cui.skill.model.SkillSession;
import org.springframework.stereotype.Service;

/**
 * 会话访问控制服务。
 * 提供统一的身份校验和会话权限检查逻辑，
 * 确保用户只能访问自己拥有的会话。
 */
@Service
public class SessionAccessControlService {

    private final SkillSessionService sessionService;

    public SessionAccessControlService(SkillSessionService sessionService) {
        this.sessionService = sessionService;
    }

    /**
     * 校验并提取 userId。
     *
     * @param userIdCookie 从 Cookie 中读取的 userId 值
     * @return 去除空格后的 userId
     * @throws ProtocolException 400 如果 userId 为空
     */
    public String requireUserId(String userIdCookie) {
        if (userIdCookie == null || userIdCookie.isBlank()) {
            throw new ProtocolException(400, "userId is required");
        }
        return userIdCookie.trim();
    }

    /**
     * 校验用户对指定会话的访问权限。
     * 仅校验 userId 归属，不检查 Agent 在线状态。
     *
     * @param sessionId    会话 ID
     * @param userIdCookie 从 Cookie 中读取的 userId 值
     * @return 校验通过的 SkillSession 对象
     * @throws ProtocolException 403 如果访问被拒绝
     */
    public SkillSession requireSessionAccess(Long sessionId, String userIdCookie) {
        String userId = requireUserId(userIdCookie);
        SkillSession session = sessionService.getSession(sessionId);

        if (!userId.equals(session.getUserId())) {
            throw new ProtocolException(403, "Session access denied");
        }

        return session;
    }
}
```

- [ ] **Step 2: 运行编译验证**

Run: `cd skill-server && mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: 提交**

```bash
git add skill-server/src/main/java/com/opencode/cui/skill/service/SessionAccessControlService.java
git commit -m "refactor: remove agent online check from SessionAccessControlService"
```

---

### Task 2: 更新 `SessionAccessControlService` 相关测试

**Files:**
- Modify: `skill-server/src/test/java/com/opencode/cui/skill/controller/SkillSessionControllerTest.java`
- Modify: `skill-server/src/test/java/com/opencode/cui/skill/controller/SkillMessageControllerTest.java`

- [ ] **Step 1: 修改 `SkillSessionControllerTest.setUp()`**

`SessionAccessControlService` 不再依赖 `GatewayApiClient`，相关 mock 设置中如果有 `isAkOwnedByUser` 的 stub 需要移除。当前测试文件中 `accessControlService` 是 `@Mock`，`requireSessionAccess` 直接返回 mock session，所以**无需修改** — 但需要确认编译通过。

Run: `cd skill-server && mvn test-compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 2: 运行现有测试确认不受影响**

Run: `cd skill-server && mvn test -pl . -Dtest="SkillSessionControllerTest,SkillMessageControllerTest" -q`
Expected: Tests run: all pass

- [ ] **Step 3: 提交（如有修改）**

```bash
git add skill-server/src/test/
git commit -m "test: update tests after SessionAccessControlService simplification"
```

---

### Task 3: 在 `replyPermission` 中补充 scope 感知在线检查

**Files:**
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/controller/SkillMessageController.java:336-412`

- [ ] **Step 1: 在 `replyPermission` 方法中添加在线检查**

在 `session.getAk() == null` 检查之后、`session.getToolSessionId()` 检查之前，插入 scope 感知在线检查逻辑。修改 `replyPermission` 方法，在第 369 行（`if (session.getAk() == null)` 块）之后添加：

```java
        // Agent 在线检查：云端助手永远在线（跳过），个人助手需要检查
        AssistantScopeStrategy scopeStrategy = scopeDispatcher.getStrategy(
                assistantInfoService.getCachedScope(session.getAk()));
        if (assistantIdProperties.isEnabled() && scopeStrategy.requiresOnlineCheck()) {
            AgentSummary agent = gatewayApiClient.getAgentByAk(session.getAk());
            if (agent == null) {
                log.warn("[SKIP] replyPermission: reason=agent_offline, sessionId={}, ak={}",
                        sessionId, session.getAk());
                return ResponseEntity.ok(ApiResponse.error(503, AGENT_OFFLINE_MESSAGE));
            }
        }
```

需要在方法顶部确保已 import `AgentSummary` 和 `AssistantScopeStrategy`（已在文件级 import 中存在）。

完整的 `replyPermission` 方法中，从 `session.getAk() == null` 到 `payload` 构建之间的代码应为：

```java
        if (session.getAk() == null) {
            return ResponseEntity.ok(ApiResponse.error(400, "No agent associated with this session"));
        }

        // Agent 在线检查：云端助手永远在线（跳过），个人助手需要检查
        AssistantScopeStrategy scopeStrategy = scopeDispatcher.getStrategy(
                assistantInfoService.getCachedScope(session.getAk()));
        if (assistantIdProperties.isEnabled() && scopeStrategy.requiresOnlineCheck()) {
            AgentSummary agent = gatewayApiClient.getAgentByAk(session.getAk());
            if (agent == null) {
                log.warn("[SKIP] replyPermission: reason=agent_offline, sessionId={}, ak={}",
                        sessionId, session.getAk());
                return ResponseEntity.ok(ApiResponse.error(503, AGENT_OFFLINE_MESSAGE));
            }
        }

        if (session.getToolSessionId() == null || session.getToolSessionId().isBlank()) {
            return ResponseEntity.ok(ApiResponse.error(500, "No toolSessionId available"));
        }
```

- [ ] **Step 2: 编译验证**

Run: `cd skill-server && mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: 提交**

```bash
git add skill-server/src/main/java/com/opencode/cui/skill/controller/SkillMessageController.java
git commit -m "feat: add scope-aware online check to replyPermission"
```

---

### Task 4: 为 `replyPermission` 在线检查添加测试

**Files:**
- Modify: `skill-server/src/test/java/com/opencode/cui/skill/controller/SkillMessageControllerTest.java`

- [ ] **Step 1: 添加个人助手离线时权限回复返回错误的测试**

在 `SkillMessageControllerTest` 类中 `permissionReplyClosedSession409` 测试之后添加：

```java
    @Test
    @DisplayName("replyPermission returns 503 when personal agent is offline")
    void permissionReplyAgentOffline503() {
        SkillSession session = new SkillSession();
        session.setId(1L);
        session.setAk("99");
        session.setUserId("1");
        session.setToolSessionId("tool-session-1");
        session.setStatus(SkillSession.Status.ACTIVE);
        when(accessControlService.requireSessionAccess(1L, "1")).thenReturn(session);
        when(gatewayApiClient.getAgentByAk("99")).thenReturn(null); // Agent 离线

        var request = new SkillMessageController.PermissionReplyRequest();
        request.setResponse("once");

        var response = controller.replyPermission("1", "1", "p-abc", request);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(503, response.getBody().getCode());
        verify(gatewayRelayService, never()).sendInvokeToGateway(any());
    }
```

- [ ] **Step 2: 添加云端助手跳过在线检查的测试**

```java
    @Test
    @DisplayName("replyPermission skips online check for business assistant (always online)")
    void permissionReplyBusinessAssistantSkipsOnlineCheck() {
        SkillSession session = new SkillSession();
        session.setId(1L);
        session.setAk("biz-ak");
        session.setUserId("1");
        session.setToolSessionId("tool-session-1");
        session.setStatus(SkillSession.Status.ACTIVE);
        when(accessControlService.requireSessionAccess(1L, "1")).thenReturn(session);

        // 设置 business scope 策略（requiresOnlineCheck=false）
        com.opencode.cui.skill.service.scope.AssistantScopeStrategy businessStrategy =
                org.mockito.Mockito.mock(com.opencode.cui.skill.service.scope.AssistantScopeStrategy.class);
        when(businessStrategy.requiresOnlineCheck()).thenReturn(false);
        when(scopeDispatcher.getStrategy("business")).thenReturn(businessStrategy);
        when(assistantInfoService.getCachedScope("biz-ak")).thenReturn("business");

        var request = new SkillMessageController.PermissionReplyRequest();
        request.setResponse("once");

        var response = controller.replyPermission("1", "1", "p-abc", request);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(0, response.getBody().getCode());
        // 不应调用 getAgentByAk — 云端助手跳过在线检查
        verify(gatewayApiClient, never()).getAgentByAk("biz-ak");
        verify(gatewayRelayService).sendInvokeToGateway(any());
    }
```

- [ ] **Step 3: 运行测试**

Run: `cd skill-server && mvn test -pl . -Dtest="SkillMessageControllerTest" -q`
Expected: Tests run: all pass

- [ ] **Step 4: 提交**

```bash
git add skill-server/src/test/java/com/opencode/cui/skill/controller/SkillMessageControllerTest.java
git commit -m "test: add replyPermission online check tests for personal and business scope"
```

---

### Task 5: 清理 `isAkOwnedByUser()` 方法

**Files:**
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/service/GatewayApiClient.java:142-150`

- [ ] **Step 1: 确认 `isAkOwnedByUser` 无其他调用方**

Run: 在 skill-server 源码中搜索 `isAkOwnedByUser`，确认仅在 `SessionAccessControlService`（已移除）中使用。

搜索命令（在项目根目录）:
```bash
grep -r "isAkOwnedByUser" skill-server/src/main/java/
```
Expected: 仅 `GatewayApiClient.java` 中的方法定义本身，无其他调用方。

- [ ] **Step 2: 删除 `isAkOwnedByUser` 方法**

从 `GatewayApiClient.java` 中删除第 142-150 行的 `isAkOwnedByUser` 方法：

```java
    // 删除以下方法
    public boolean isAkOwnedByUser(String ak, String userId) {
        if (ak == null || ak.isBlank() || userId == null || userId.isBlank()) {
            return false;
        }

        return getOnlineAgentsByUserId(userId).stream()
                .map(AgentSummary::getAk)
                .anyMatch(ak::equals);
    }
```

- [ ] **Step 3: 编译验证**

Run: `cd skill-server && mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: 运行全量测试**

Run: `cd skill-server && mvn test -q`
Expected: All tests pass

- [ ] **Step 5: 提交**

```bash
git add skill-server/src/main/java/com/opencode/cui/skill/service/GatewayApiClient.java
git commit -m "refactor: remove unused isAkOwnedByUser method from GatewayApiClient"
```
