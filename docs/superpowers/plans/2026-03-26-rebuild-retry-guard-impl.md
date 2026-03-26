# SessionRebuildService 重建重试保护 - 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 给 `SessionRebuildService.rebuildToolSession()` 加入 per-session 重建计数器，超过上限后停止重建并通知用户，冷却期过后自动恢复。

**Architecture:** 在 `rebuildToolSession()` 入口增加 Caffeine 计数器（`expireAfterAccess`），拦截超限请求。计数器检查放在两条重建路径（tool_error 驱动 / IM 消息驱动）的汇合点，统一保护。

**Tech Stack:** Java 21, Spring Boot, Caffeine Cache, JUnit 5 + Mockito

**Spec:** `docs/superpowers/specs/2026-03-26-rebuild-retry-guard-design.md`

---

### Task 1: 添加配置项

**Files:**
- Modify: `skill-server/src/main/resources/application.yml:89-94`

- [ ] **Step 1: 在 `skill.session` 节点下新增两个配置项**

在 `auto-create-timeout-seconds` 之后添加：

```yaml
    rebuild-max-attempts: ${SKILL_SESSION_REBUILD_MAX_ATTEMPTS:3}
    rebuild-cooldown-seconds: ${SKILL_SESSION_REBUILD_COOLDOWN_SECONDS:30}
```

- [ ] **Step 2: 验证配置格式**

Run: `cd skill-server && ./mvnw compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add skill-server/src/main/resources/application.yml
git commit -m "feat(ss): 新增 rebuild-max-attempts 和 rebuild-cooldown-seconds 配置项"
```

---

### Task 2: 修改 SessionRebuildService 构造函数和成员

**Files:**
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/service/SessionRebuildService.java`

- [ ] **Step 1: 新增 import 和成员变量**

在现有 import 之后添加：

```java
import java.util.concurrent.atomic.AtomicInteger;
```

在类中新增成员变量（`pendingRebuildMessages` 声明之后）：

```java
    /** 重建计数器：sessionId → 已重建次数，冷却期后自动过期 */
    private final Cache<String, AtomicInteger> rebuildAttemptCounters;
    private final int maxRebuildAttempts;
    private final int rebuildCooldownSeconds;
```

- [ ] **Step 2: 修改构造函数，注入配置并初始化计数器缓存**

将构造函数改为：

```java
    public SessionRebuildService(ObjectMapper objectMapper,
            SkillSessionService sessionService,
            SkillMessageRepository messageRepository,
            @org.springframework.beans.factory.annotation.Value("${skill.session.rebuild-max-attempts:3}") int maxRebuildAttempts,
            @org.springframework.beans.factory.annotation.Value("${skill.session.rebuild-cooldown-seconds:30}") int rebuildCooldownSeconds) {
        this.objectMapper = objectMapper;
        this.sessionService = sessionService;
        this.messageRepository = messageRepository;
        this.maxRebuildAttempts = maxRebuildAttempts;
        this.rebuildCooldownSeconds = rebuildCooldownSeconds;
        this.rebuildAttemptCounters = Caffeine.newBuilder()
                .expireAfterAccess(Duration.ofSeconds(rebuildCooldownSeconds))
                .maximumSize(1_000)
                .build();
    }
```

- [ ] **Step 3: 验证编译**

Run: `cd skill-server && ./mvnw compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add skill-server/src/main/java/com/opencode/cui/skill/service/SessionRebuildService.java
git commit -m "feat(ss): SessionRebuildService 添加重建计数器成员和配置注入"
```

---

### Task 3: 在 rebuildToolSession() 中实现计数器检查

**Files:**
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/service/SessionRebuildService.java:68-105`

- [ ] **Step 1: 在 `rebuildToolSession()` 开头插入计数器检查逻辑**

替换 `rebuildToolSession()` 方法为：

```java
    /**
     * 执行工具会话重建核心流程。
     * <ol>
     * <li>检查重建计数器是否超限</li>
     * <li>缓存待重试的用户消息</li>
     * <li>广播 retry 状态到前端</li>
     * <li>向 Gateway 发送 create_session 命令</li>
     * </ol>
     */
    public void rebuildToolSession(String sessionId, SkillSession session,
            String pendingMessage, RebuildCallback callback) {
        // --- 重建计数器检查 ---
        AtomicInteger counter = rebuildAttemptCounters.get(sessionId, k -> new AtomicInteger(0));
        int attempts = counter.incrementAndGet();

        if (attempts > maxRebuildAttempts) {
            log.warn("Rebuild exhausted: session={}, attempts={}, cooldownSeconds={}",
                    sessionId, attempts, rebuildCooldownSeconds);
            pendingRebuildMessages.invalidate(sessionId);
            Long numericId = ProtocolUtils.parseSessionId(sessionId);
            if (numericId != null) {
                sessionService.clearToolSessionId(numericId);
            }
            callback.broadcast(sessionId, session.getUserId(),
                    StreamMessage.error("会话连接异常（重建已达上限），请等待 "
                            + rebuildCooldownSeconds + " 秒后重试"));
            return;
        }

        log.info("Rebuild attempt {}/{} for session={}", attempts, maxRebuildAttempts, sessionId);

        // --- 原有逻辑 ---
        if (pendingMessage != null && !pendingMessage.isBlank()) {
            pendingRebuildMessages.put(sessionId, pendingMessage);
            log.info("Stored pending retry message for session {}: '{}'",
                    sessionId,
                    pendingMessage.substring(0, Math.min(50, pendingMessage.length())));
        }

        callback.broadcast(sessionId, session.getUserId(), StreamMessage.sessionStatus("retry"));

        if (session.getAk() == null || session.getAk().isBlank()) {
            log.error("Cannot rebuild session {}: no ak associated", sessionId);
            pendingRebuildMessages.invalidate(sessionId);
            callback.broadcast(sessionId, session.getUserId(),
                    StreamMessage.error("AI session expired and cannot be rebuilt"));
            return;
        }

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("title", session.getTitle() != null ? session.getTitle() : "");
        String payloadStr;
        try {
            payloadStr = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            payloadStr = "{}";
        }

        callback.sendInvoke(new InvokeCommand(
                session.getAk(),
                session.getUserId(),
                sessionId,
                GatewayActions.CREATE_SESSION,
                payloadStr));
        log.info("Rebuild create_session sent for welinkSession={}, ak={}", sessionId, session.getAk());
    }
```

- [ ] **Step 2: 验证编译**

Run: `cd skill-server && ./mvnw compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add skill-server/src/main/java/com/opencode/cui/skill/service/SessionRebuildService.java
git commit -m "feat(ss): rebuildToolSession 添加重建计数器检查，超限后停止重建并通知用户"
```

---

### Task 4: 编写单元测试

**Files:**
- Create: `skill-server/src/test/java/com/opencode/cui/skill/service/SessionRebuildServiceTest.java`

注意：需要先确认测试目录结构存在。

- [ ] **Step 1: 创建测试文件**

```java
package com.opencode.cui.skill.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.model.InvokeCommand;
import com.opencode.cui.skill.model.SkillSession;
import com.opencode.cui.skill.model.StreamMessage;
import com.opencode.cui.skill.repository.SkillMessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SessionRebuildServiceTest {

    private SessionRebuildService service;
    private SkillSessionService sessionService;
    private SkillMessageRepository messageRepository;
    private ObjectMapper objectMapper;

    // 捕获回调调用
    private final List<StreamMessage> broadcastedMessages = new ArrayList<>();
    private final List<InvokeCommand> sentInvokes = new ArrayList<>();

    private SessionRebuildService.RebuildCallback callback;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        sessionService = mock(SkillSessionService.class);
        messageRepository = mock(SkillMessageRepository.class);

        // maxAttempts=3, cooldown=30s
        service = new SessionRebuildService(
                objectMapper, sessionService, messageRepository, 3, 30);

        broadcastedMessages.clear();
        sentInvokes.clear();

        callback = new SessionRebuildService.RebuildCallback() {
            @Override
            public void broadcast(String sessionId, String userId, StreamMessage msg) {
                broadcastedMessages.add(msg);
            }

            @Override
            public void sendInvoke(InvokeCommand command) {
                sentInvokes.add(command);
            }
        };
    }

    private SkillSession buildSession(String id, String ak) {
        SkillSession session = new SkillSession();
        session.setId(Long.parseLong(id));
        session.setUserId("user-1");
        session.setAk(ak);
        session.setTitle("test");
        return session;
    }

    @Test
    void rebuildToolSession_shouldAllowAttemptsWithinLimit() {
        SkillSession session = buildSession("100", "ak-1");

        service.rebuildToolSession("100", session, "hello", callback);
        service.rebuildToolSession("100", session, "hello", callback);
        service.rebuildToolSession("100", session, "hello", callback);

        // 3 次都应该发送 create_session invoke
        assertEquals(3, sentInvokes.size());
        // 每次都应该广播 retry 状态
        assertTrue(broadcastedMessages.stream()
                .anyMatch(m -> "retry".equals(m.getSessionStatus())));
    }

    @Test
    void rebuildToolSession_shouldBlockAfterMaxAttempts() {
        SkillSession session = buildSession("200", "ak-2");

        // 前 3 次：正常
        service.rebuildToolSession("200", session, "hello", callback);
        service.rebuildToolSession("200", session, "hello", callback);
        service.rebuildToolSession("200", session, "hello", callback);

        sentInvokes.clear();
        broadcastedMessages.clear();

        // 第 4 次：应该被拦截
        service.rebuildToolSession("200", session, "hello", callback);

        // 不应发送 invoke
        assertEquals(0, sentInvokes.size());
        // 应广播 error 消息
        assertEquals(1, broadcastedMessages.size());
        assertEquals("error", broadcastedMessages.get(0).getType());
        assertTrue(broadcastedMessages.get(0).getError().contains("重建已达上限"));
        // 应清空 toolSessionId
        verify(sessionService).clearToolSessionId(200L);
    }

    @Test
    void rebuildToolSession_differentSessionsShouldHaveSeparateCounters() {
        SkillSession session1 = buildSession("300", "ak-3");
        SkillSession session2 = buildSession("400", "ak-4");

        // session 300: 耗尽 3 次
        service.rebuildToolSession("300", session1, "msg", callback);
        service.rebuildToolSession("300", session1, "msg", callback);
        service.rebuildToolSession("300", session1, "msg", callback);

        sentInvokes.clear();

        // session 300 第 4 次应该被拦截
        service.rebuildToolSession("300", session1, "msg", callback);
        assertEquals(0, sentInvokes.size());

        // session 400 应该正常
        service.rebuildToolSession("400", session2, "msg", callback);
        assertEquals(1, sentInvokes.size());
    }

    @Test
    void rebuildToolSession_blockedAttemptShouldClearPendingMessages() {
        SkillSession session = buildSession("500", "ak-5");

        // 耗尽 3 次
        service.rebuildToolSession("500", session, "msg1", callback);
        service.rebuildToolSession("500", session, "msg2", callback);
        service.rebuildToolSession("500", session, "msg3", callback);

        // 第 4 次被拦截
        service.rebuildToolSession("500", session, "msg4", callback);

        // pending message 应该已被清除
        assertNull(service.consumePendingMessage("500"));
    }
}
```

- [ ] **Step 2: 运行测试**

Run: `cd skill-server && ./mvnw test -pl . -Dtest=SessionRebuildServiceTest -q`
Expected: 4 tests PASS

- [ ] **Step 3: Commit**

```bash
git add skill-server/src/test/java/com/opencode/cui/skill/service/SessionRebuildServiceTest.java
git commit -m "test(ss): SessionRebuildService 重建计数器单元测试"
```

---

### Task 5: 验证完整构建

**Files:** 无新改动，仅验证

- [ ] **Step 1: 运行完整编译**

Run: `cd skill-server && ./mvnw compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 2: 运行全部测试**

Run: `cd skill-server && ./mvnw test -q`
Expected: BUILD SUCCESS, 所有测试通过

- [ ] **Step 3: 最终 Commit（如有格式调整）**

如果前面步骤有遗漏的格式修正，在此统一提交。
