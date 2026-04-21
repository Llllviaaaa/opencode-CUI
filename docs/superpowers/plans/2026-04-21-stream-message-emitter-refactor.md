# StreamMessageEmitter 重构实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 skill-server 中分散在各处的"手动 enrich + 出站"约定收口到统一的 `StreamMessageEmitter`，顺带修复 external ws `type=error` 事件缺失 `welinkSessionId` 的 bug。

**Architecture:** 新建 `StreamMessageEmitter` 作为所有 `StreamMessage` 出站的唯一权威入口；提供 3 个方法对应三种既存出站语义（业务投递 / 前端强推 / 前端强推+buffer）。迁移 18 处调用点。外部 API 签名保留（thin wrapper 向内委托），避免破坏测试与下游引用。

**Tech Stack:** Java 21, Spring Boot 3.4, Maven, JUnit 5, Mockito, Jackson, Redis (Lettuce), Lombok.

**关联 spec:** `docs/superpowers/specs/2026-04-21-stream-message-emitter-refactor-design.md` (v4)

**分支建议:** 在当前 `release-0417` 基础上建 feature 分支；本次改动只触及 `skill-server` 模块。

---

## Shell 环境说明

本 plan 的命令默认使用 bash / Unix 风格（Claude Code 默认 harness 可直接执行）。Windows PowerShell / Codex CLI 下需要做如下替换：

**注意**：本仓库**没有** Maven Wrapper（`./mvnw` 不存在）。所有命令使用全局 `mvn`（Windows / Linux / macOS 均通用）。

| bash | PowerShell 等价 |
|------|----------------|
| `mvn -pl skill-server test -q` | 同（全局 `mvn`，路径分隔符无影响） |
| `mvn -pl skill-server compile -q` | 同 |
| `grep -rn "pattern" path/` | `Select-String -Pattern "pattern" -Path path\ -Recurse` |
| `git commit -m "$(cat <<'EOF'...EOF)"` | `git commit -m @'...'@`（PowerShell here-string，`'@` 必须顶格） |
| 反斜杠换行 `\` | 反引号换行 `` ` `` |

Git Bash 下 `grep` 和 bash 语法也可直接使用。执行者按本地 shell 调整即可；plan 描述保持 bash 以免重复。

---

## Phase 1 — 引入 Emitter（零迁移，独立可验证）

### Task 1: 创建 `StreamMessageEmitter` 骨架

**Files:**
- Create: `skill-server/src/main/java/com/opencode/cui/skill/service/delivery/StreamMessageEmitter.java`

- [ ] **Step 1: 新建 `StreamMessageEmitter.java` 骨架（仅字段 + 构造器 + 空方法签名，暂不实现）**

```java
package com.opencode.cui.skill.service.delivery;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.opencode.cui.skill.model.SkillSession;
import com.opencode.cui.skill.model.StreamMessage;
import com.opencode.cui.skill.service.MessagePersistenceService;
import com.opencode.cui.skill.service.ProtocolUtils;
import com.opencode.cui.skill.service.RedisMessageBroker;
import com.opencode.cui.skill.service.SkillSessionService;
import com.opencode.cui.skill.service.StreamBufferService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Set;

/**
 * StreamMessage 出站的唯一权威入口。
 *
 * <p>封装三种既存出站语义：
 * <ul>
 *   <li>{@link #emitToSession}：按 session domain 路由到 miniapp/IM/ExternalWs 策略</li>
 *   <li>{@link #emitToClient}：强制推给 miniapp 前端（绕过 domain 路由）</li>
 *   <li>{@link #emitToClientWithBuffer}：前端强推 + buffer（断线重连回放）</li>
 * </ul>
 *
 * <p>所有方法内部统一完成 enrich（填充 sessionId/welinkSessionId/emittedAt + 分配 messageContext），
 * 调用方不再需要手动 enrich。
 */
@Slf4j
@Component
public class StreamMessageEmitter {

    /** 不需要 emittedAt 时间戳的消息类型集合（从 GatewayMessageRouter 迁移而来） */
    private static final Set<String> EMITTED_AT_EXCLUDED_TYPES = Set.of(
            StreamMessage.Types.PERMISSION_REPLY,
            StreamMessage.Types.AGENT_ONLINE,
            StreamMessage.Types.AGENT_OFFLINE,
            StreamMessage.Types.ERROR);

    private final OutboundDeliveryDispatcher dispatcher;
    private final RedisMessageBroker redisBroker;
    private final StreamBufferService bufferService;
    private final MessagePersistenceService persistenceService;
    private final SkillSessionService sessionService;
    private final ObjectMapper objectMapper;

    public StreamMessageEmitter(OutboundDeliveryDispatcher dispatcher,
                                 RedisMessageBroker redisBroker,
                                 StreamBufferService bufferService,
                                 MessagePersistenceService persistenceService,
                                 SkillSessionService sessionService,
                                 ObjectMapper objectMapper) {
        this.dispatcher = dispatcher;
        this.redisBroker = redisBroker;
        this.bufferService = bufferService;
        this.persistenceService = persistenceService;
        this.sessionService = sessionService;
        this.objectMapper = objectMapper;
    }

    public void emitToSession(SkillSession session, String sessionId,
                              String userId, StreamMessage msg) {
        throw new UnsupportedOperationException("not yet implemented");
    }

    public void emitToClient(String sessionId, String userIdHint, StreamMessage msg) {
        throw new UnsupportedOperationException("not yet implemented");
    }

    public void emitToClientWithBuffer(String sessionId, StreamMessage msg) {
        throw new UnsupportedOperationException("not yet implemented");
    }
}
```

- [ ] **Step 2: 编译验证骨架**

Run: `mvn -pl skill-server compile -q`
Expected: `BUILD SUCCESS`（无编译错误；Spring 启动时会注册该 bean，但本 task 不启动服务）

- [ ] **Step 3: Commit**

```bash
git add skill-server/src/main/java/com/opencode/cui/skill/service/delivery/StreamMessageEmitter.java
git commit -m "feat(emitter): scaffold StreamMessageEmitter with three outbound methods"
```

---

### Task 2: 创建测试骨架 + enrich `welinkSessionId` 用例

**Files:**
- Create: `skill-server/src/test/java/com/opencode/cui/skill/service/delivery/StreamMessageEmitterTest.java`
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/service/delivery/StreamMessageEmitter.java`

- [ ] **Step 1: 新建测试类骨架 + 写 enrich-1/2 两条失败测试**

```java
package com.opencode.cui.skill.service.delivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.model.SkillSession;
import com.opencode.cui.skill.model.StreamMessage;
import com.opencode.cui.skill.service.MessagePersistenceService;
import com.opencode.cui.skill.service.RedisMessageBroker;
import com.opencode.cui.skill.service.SkillSessionService;
import com.opencode.cui.skill.service.StreamBufferService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StreamMessageEmitterTest {

    @Mock OutboundDeliveryDispatcher dispatcher;
    @Mock RedisMessageBroker redisBroker;
    @Mock StreamBufferService bufferService;
    @Mock MessagePersistenceService persistenceService;
    @Mock SkillSessionService sessionService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private StreamMessageEmitter emitter;

    @BeforeEach
    void setUp() {
        emitter = new StreamMessageEmitter(
                dispatcher, redisBroker, bufferService,
                persistenceService, sessionService, objectMapper);
    }

    // --- enrich semantics ---

    @Test
    void enrich1_welinkSessionId_isOverwrittenByCanonicalSessionId() {
        SkillSession session = mock(SkillSession.class);
        StreamMessage msg = StreamMessage.builder()
                .type(StreamMessage.Types.TEXT_DELTA)
                .welinkSessionId("business-123")
                .role("assistant")
                .build();

        emitter.emitToSession(session, "101", "user-a", msg);

        // canonical overwrite: "business-123" 被替换为 "101"
        assertEquals("101", msg.getWelinkSessionId());
    }

    @Test
    void enrich2_welinkSessionId_isFilledWhenBlank() {
        SkillSession session = mock(SkillSession.class);
        StreamMessage msg = StreamMessage.builder()
                .type(StreamMessage.Types.TEXT_DELTA)
                .role("assistant")
                .build();

        emitter.emitToSession(session, "101", "user-a", msg);

        assertEquals("101", msg.getWelinkSessionId());
    }
}
```

- [ ] **Step 2: 运行失败测试验证**

Run: `mvn -pl skill-server test -Dtest=StreamMessageEmitterTest -q`
Expected: `enrich1_...` / `enrich2_...` 两条 FAIL，错误为 `UnsupportedOperationException: not yet implemented`

- [ ] **Step 3: 在 `StreamMessageEmitter` 实现 `enrich` 私有方法 + `emitToSession`**

修改 `StreamMessageEmitter.java`，先添加 `enrich` 私有方法：

```java
    private void enrich(String sessionId, StreamMessage msg) {
        if (msg == null || sessionId == null) return;

        msg.setSessionId(sessionId);               // 内部字段，始终覆盖
        msg.setWelinkSessionId(sessionId);          // 协议字段，canonical overwrite

        if (!EMITTED_AT_EXCLUDED_TYPES.contains(msg.getType())
                && (msg.getEmittedAt() == null || msg.getEmittedAt().isBlank())) {
            msg.setEmittedAt(Instant.now().toString());
        }

        if (!"user".equals(ProtocolUtils.normalizeRole(msg.getRole()))) {
            Long numericId = ProtocolUtils.parseSessionId(sessionId);
            if (numericId != null) {
                persistenceService.prepareMessageContext(numericId, msg);
            }
        }
    }
```

替换 `emitToSession` 方法体（移除 `UnsupportedOperationException`）：

```java
    public void emitToSession(SkillSession session, String sessionId,
                              String userId, StreamMessage msg) {
        enrich(sessionId, msg);
        dispatcher.deliver(session, sessionId, userId, msg);
    }
```

- [ ] **Step 4: 运行测试验证通过**

Run: `mvn -pl skill-server test -Dtest=StreamMessageEmitterTest -q`
Expected: `enrich1_...` / `enrich2_...` PASS

- [ ] **Step 5: Commit**

```bash
git add skill-server/src/main/java/com/opencode/cui/skill/service/delivery/StreamMessageEmitter.java \
        skill-server/src/test/java/com/opencode/cui/skill/service/delivery/StreamMessageEmitterTest.java
git commit -m "feat(emitter): implement enrich welinkSessionId + emitToSession"
```

---

### Task 3: enrich `emittedAt` 语义（3 条用例）

**Files:**
- Modify: `skill-server/src/test/java/com/opencode/cui/skill/service/delivery/StreamMessageEmitterTest.java`

- [ ] **Step 1: 追加 enrich-3/4/5 测试**

在 `StreamMessageEmitterTest` 追加：

```java
    @Test
    void enrich3_emittedAt_excludedTypesKeepNull() {
        SkillSession session = mock(SkillSession.class);
        StreamMessage msg = StreamMessage.builder()
                .type(StreamMessage.Types.ERROR)      // 在白名单内
                .error("oops")
                .build();

        emitter.emitToSession(session, "101", null, msg);

        assertNull(msg.getEmittedAt());
    }

    @Test
    void enrich4_emittedAt_nonExcludedAndBlank_filled() {
        SkillSession session = mock(SkillSession.class);
        StreamMessage msg = StreamMessage.builder()
                .type(StreamMessage.Types.TEXT_DELTA)  // 非白名单
                .role("assistant")
                .build();

        emitter.emitToSession(session, "101", null, msg);

        assertNotNull(msg.getEmittedAt());
        assertFalse(msg.getEmittedAt().isBlank());
    }

    @Test
    void enrich5_emittedAt_alreadySet_preserved() {
        SkillSession session = mock(SkillSession.class);
        StreamMessage msg = StreamMessage.builder()
                .type(StreamMessage.Types.TEXT_DELTA)
                .emittedAt("2026-01-01T00:00:00Z")
                .role("assistant")
                .build();

        emitter.emitToSession(session, "101", null, msg);

        assertEquals("2026-01-01T00:00:00Z", msg.getEmittedAt());
    }
```

- [ ] **Step 2: 运行测试**

Run: `mvn -pl skill-server test -Dtest=StreamMessageEmitterTest -q`
Expected: 全部 5 条 PASS（enrich-3/4/5 基于已实现的 enrich 逻辑直接通过）

- [ ] **Step 3: Commit**

```bash
git add skill-server/src/test/java/com/opencode/cui/skill/service/delivery/StreamMessageEmitterTest.java
git commit -m "test(emitter): add enrich emittedAt semantics cases"
```

---

### Task 4: enrich `messageContext` 触发条件（3 条用例）

**Files:**
- Modify: `skill-server/src/test/java/com/opencode/cui/skill/service/delivery/StreamMessageEmitterTest.java`

- [ ] **Step 1: 追加 enrich-6/7/8 测试**

```java
    @Test
    void enrich6_userRole_noMessageContextCall() {
        SkillSession session = mock(SkillSession.class);
        StreamMessage msg = StreamMessage.builder()
                .type(StreamMessage.Types.MESSAGE_USER)
                .role("user")
                .build();

        emitter.emitToSession(session, "101", null, msg);

        verifyNoInteractions(persistenceService);
    }

    @Test
    void enrich7_assistantRoleNumericSessionId_prepareMessageContextCalled() {
        SkillSession session = mock(SkillSession.class);
        StreamMessage msg = StreamMessage.builder()
                .type(StreamMessage.Types.TEXT_DELTA)
                .role("assistant")
                .build();

        emitter.emitToSession(session, "101", null, msg);

        verify(persistenceService).prepareMessageContext(eq(101L), eq(msg));
    }

    @Test
    void enrich8_assistantRoleNonNumericSessionId_noMessageContextCall() {
        SkillSession session = mock(SkillSession.class);
        StreamMessage msg = StreamMessage.builder()
                .type(StreamMessage.Types.TEXT_DELTA)
                .role("assistant")
                .build();

        emitter.emitToSession(session, "not-numeric", null, msg);

        verifyNoInteractions(persistenceService);
    }
```

- [ ] **Step 2: 运行测试**

Run: `mvn -pl skill-server test -Dtest=StreamMessageEmitterTest -q`
Expected: 全部 8 条 PASS

- [ ] **Step 3: Commit**

```bash
git add skill-server/src/test/java/com/opencode/cui/skill/service/delivery/StreamMessageEmitterTest.java
git commit -m "test(emitter): add enrich messageContext branch cases"
```

---

### Task 5: enrich 幂等（1 条用例）

**Files:**
- Modify: `skill-server/src/test/java/com/opencode/cui/skill/service/delivery/StreamMessageEmitterTest.java`

> **注意**：`boundary-1`（null 输入）依赖 `emitToClient` / `emitToClientWithBuffer` 完整实现后才能全面断言，故挪到 Task 8 一起加，避免本 task 留失败测试进 commit 历史。

- [ ] **Step 1: 追加 enrich-9**

```java
    @Test
    void enrich9_repeatedEmit_stableFields() {
        SkillSession session = mock(SkillSession.class);
        StreamMessage msg = StreamMessage.builder()
                .type(StreamMessage.Types.TEXT_DELTA)
                .role("assistant")
                .build();

        emitter.emitToSession(session, "101", null, msg);
        String firstEmittedAt = msg.getEmittedAt();

        emitter.emitToSession(session, "101", null, msg);

        assertEquals(firstEmittedAt, msg.getEmittedAt(), "emittedAt should not be rewritten");
        assertEquals("101", msg.getWelinkSessionId());
        assertEquals("101", msg.getSessionId());
        // prepareMessageContext 可调 2 次（tracker 内部幂等，不影响可观察状态）
        verify(persistenceService, times(2)).prepareMessageContext(eq(101L), eq(msg));
    }
```

- [ ] **Step 2: 运行 enrich-9 验证通过**

Run: `mvn -pl skill-server test -Dtest=StreamMessageEmitterTest#enrich9_repeatedEmit_stableFields -q`
Expected: PASS

- [ ] **Step 3: 跑全部已添加用例，确保未引入回归**

Run: `mvn -pl skill-server test -Dtest=StreamMessageEmitterTest -q`
Expected: enrich-1..9（9 条）全部 PASS

- [ ] **Step 4: Commit**

```bash
git add skill-server/src/test/java/com/opencode/cui/skill/service/delivery/StreamMessageEmitterTest.java
git commit -m "test(emitter): add enrich idempotency case"
```

---

### Task 6: `emitToSession` 其余断言（2 条用例）

**Files:**
- Modify: `skill-server/src/test/java/com/opencode/cui/skill/service/delivery/StreamMessageEmitterTest.java`

- [ ] **Step 1: 追加 session-1/2/3 测试**

```java
    // --- emitToSession ---

    @Test
    void session1_dispatcherDeliverCalled() {
        SkillSession session = mock(SkillSession.class);
        StreamMessage msg = StreamMessage.builder()
                .type(StreamMessage.Types.TEXT_DELTA).role("assistant").build();

        emitter.emitToSession(session, "101", "user-a", msg);

        verify(dispatcher).deliver(eq(session), eq("101"), eq("user-a"), eq(msg));
    }

    @Test
    void session2_emitToSession_noRedisInteraction() {
        SkillSession session = mock(SkillSession.class);
        StreamMessage msg = StreamMessage.builder()
                .type(StreamMessage.Types.TEXT_DELTA).role("assistant").build();

        emitter.emitToSession(session, "101", "user-a", msg);

        verifyNoInteractions(redisBroker);
    }

    @Test
    void session3_dispatcherThrows_emitterDoesNotSwallow() {
        SkillSession session = mock(SkillSession.class);
        StreamMessage msg = StreamMessage.builder()
                .type(StreamMessage.Types.TEXT_DELTA).role("assistant").build();
        doThrow(new RuntimeException("dispatcher boom"))
                .when(dispatcher).deliver(any(), any(), any(), any());

        // emitter 层不新增 try-catch，异常应冒出（仅行为断言，非合约承诺）
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> emitter.emitToSession(session, "101", "user-a", msg));
        assertEquals("dispatcher boom", ex.getMessage());
    }
```

- [ ] **Step 2: 运行测试**

Run: `mvn -pl skill-server test -Dtest=StreamMessageEmitterTest -q`
Expected: 除 boundary-1 外的 enrich + session-1/2/3 共 11 条 PASS

- [ ] **Step 3: Commit**

```bash
git add skill-server/src/test/java/com/opencode/cui/skill/service/delivery/StreamMessageEmitterTest.java
git commit -m "test(emitter): cover emitToSession delivery and exception behavior"
```

---

### Task 7: 实现 `emitToClient` + 对应 4 条测试

**Files:**
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/service/delivery/StreamMessageEmitter.java`
- Modify: `skill-server/src/test/java/com/opencode/cui/skill/service/delivery/StreamMessageEmitterTest.java`

- [ ] **Step 1: 在 Emitter 添加 `resolveUserId` + `sendToUserChannel` + `emitToClient` 实现**

在 `StreamMessageEmitter` 类内追加（替换原有 `emitToClient` 的 `UnsupportedOperationException`）：

```java
    public void emitToClient(String sessionId, String userIdHint, StreamMessage msg) {
        try {
            enrich(sessionId, msg);
            String userId = resolveUserId(sessionId, userIdHint);
            if (userId == null || userId.isBlank()) {
                log.warn("emitToClient skipped: no userId resolvable, sessionId={}, type={}",
                        sessionId, msg != null ? msg.getType() : null);
                return;
            }
            sendToUserChannel(sessionId, userId, msg);
            log.info("[EMIT->CLIENT] sessionId={}, type={}, userId={}",
                    sessionId, msg.getType(), userId);
        } catch (Exception e) {
            log.error("emitToClient failed: sessionId={}, type={}, error={}",
                    sessionId, msg != null ? msg.getType() : null, e.getMessage());
        }
    }

    private void sendToUserChannel(String sessionId, String userId, StreamMessage msg)
            throws JsonProcessingException {
        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("sessionId", sessionId);
        envelope.put("userId", userId);
        envelope.set("message", objectMapper.valueToTree(msg));
        redisBroker.publishToUser(userId, objectMapper.writeValueAsString(envelope));
    }

    private String resolveUserId(String sessionId, String hint) {
        if (hint != null && !hint.isBlank()) return hint;
        try {
            Long numericId = ProtocolUtils.parseSessionId(sessionId);
            if (numericId == null) return null;
            SkillSession s = sessionService.findByIdSafe(numericId);
            return s != null ? s.getUserId() : null;
        } catch (Exception e) {
            log.warn("resolveUserId failed for sessionId={}: {}", sessionId, e.getMessage());
            return null;
        }
    }
```

- [ ] **Step 2: 追加 client-1/2/3/4 测试**

```java
    // --- emitToClient ---

    @Test
    void client1_userIdHintPresent_publishToUserCalledOnce() throws Exception {
        StreamMessage msg = StreamMessage.builder()
                .type(StreamMessage.Types.SESSION_STATUS).sessionStatus("busy").build();

        emitter.emitToClient("101", "user-a", msg);

        org.mockito.ArgumentCaptor<String> payloadCap = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(redisBroker).publishToUser(eq("user-a"), payloadCap.capture());
        var envelope = objectMapper.readTree(payloadCap.getValue());
        assertEquals("101", envelope.path("sessionId").asText());
        assertEquals("user-a", envelope.path("userId").asText());
        assertEquals("session.status", envelope.path("message").path("type").asText());
    }

    @Test
    void client2_hintNullAndSessionFound_usesSessionUserId() {
        StreamMessage msg = StreamMessage.builder()
                .type(StreamMessage.Types.SESSION_STATUS).sessionStatus("busy").build();
        SkillSession s = mock(SkillSession.class);
        when(s.getUserId()).thenReturn("user-from-session");
        when(sessionService.findByIdSafe(101L)).thenReturn(s);

        emitter.emitToClient("101", null, msg);

        verify(redisBroker).publishToUser(eq("user-from-session"), anyString());
    }

    @Test
    void client3_hintNullAndSessionNull_publishToUserNotCalled() {
        StreamMessage msg = StreamMessage.builder()
                .type(StreamMessage.Types.SESSION_STATUS).sessionStatus("busy").build();
        when(sessionService.findByIdSafe(any())).thenReturn(null);

        emitter.emitToClient("101", null, msg);

        verifyNoInteractions(redisBroker);
    }

    @Test
    void client4_publishThrows_emitterSwallowsException() {
        StreamMessage msg = StreamMessage.builder()
                .type(StreamMessage.Types.SESSION_STATUS).sessionStatus("busy").build();
        doThrow(new RuntimeException("redis down"))
                .when(redisBroker).publishToUser(anyString(), anyString());

        // 不应抛
        assertDoesNotThrow(() -> emitter.emitToClient("101", "user-a", msg));
    }
```

- [ ] **Step 3: 运行测试**

Run: `mvn -pl skill-server test -Dtest=StreamMessageEmitterTest -q`
Expected: 15 条（除 boundary-1 部分断言）PASS

- [ ] **Step 4: Commit**

```bash
git add skill-server/src/main/java/com/opencode/cui/skill/service/delivery/StreamMessageEmitter.java \
        skill-server/src/test/java/com/opencode/cui/skill/service/delivery/StreamMessageEmitterTest.java
git commit -m "feat(emitter): implement emitToClient with userId fallback"
```

---

### Task 8: 实现 `emitToClientWithBuffer` + 2 条测试 + 回补 boundary-1

**Files:**
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/service/delivery/StreamMessageEmitter.java`
- Modify: `skill-server/src/test/java/com/opencode/cui/skill/service/delivery/StreamMessageEmitterTest.java`

- [ ] **Step 1: 实现 `emitToClientWithBuffer`**

替换 `emitToClientWithBuffer` 的 `UnsupportedOperationException`：

```java
    public void emitToClientWithBuffer(String sessionId, StreamMessage msg) {
        emitToClient(sessionId, null, msg);
        if (msg == null || sessionId == null) return;
        bufferService.accumulate(sessionId, msg);
    }
```

- [ ] **Step 2: 追加 buffer-1/2 测试**

```java
    // --- emitToClientWithBuffer ---

    @Test
    void buffer1_normalCase_publishAndAccumulate() {
        StreamMessage msg = StreamMessage.builder()
                .type(StreamMessage.Types.PERMISSION_REPLY).build();
        SkillSession s = mock(SkillSession.class);
        when(s.getUserId()).thenReturn("user-a");
        when(sessionService.findByIdSafe(101L)).thenReturn(s);

        emitter.emitToClientWithBuffer("101", msg);

        verify(redisBroker).publishToUser(eq("user-a"), anyString());
        verify(bufferService).accumulate(eq("101"), eq(msg));
    }

    @Test
    void buffer2_publishThrows_bufferStillAccumulates() {
        StreamMessage msg = StreamMessage.builder()
                .type(StreamMessage.Types.PERMISSION_REPLY).build();
        SkillSession s = mock(SkillSession.class);
        when(s.getUserId()).thenReturn("user-a");
        when(sessionService.findByIdSafe(101L)).thenReturn(s);
        doThrow(new RuntimeException("redis down"))
                .when(redisBroker).publishToUser(anyString(), anyString());

        emitter.emitToClientWithBuffer("101", msg);

        // 沿袭原 publishProtocolMessage 语义：broadcast 吞异常 → buffer 仍执行
        verify(bufferService).accumulate(eq("101"), eq(msg));
    }
```

- [ ] **Step 3: 追加 boundary-1（首次加入，全方法已实现）**

```java
    // --- null-input boundary ---

    @Test
    void boundary1_nullInputs_noOpAllMethods() {
        SkillSession session = mock(SkillSession.class);

        assertDoesNotThrow(() -> {
            emitter.emitToSession(session, null, null, null);
            emitter.emitToClient(null, null, null);
            emitter.emitToClientWithBuffer(null, null);
        });

        verifyNoInteractions(dispatcher);
        verifyNoInteractions(redisBroker);
        verifyNoInteractions(bufferService);
        verifyNoInteractions(persistenceService);
    }
```

注意：`emitToSession(session, null, null, null)` 会调 `enrich`（null msg/sessionId early return）后 **仍然** 调 `dispatcher.deliver(session, null, null, null)`——这会命中 `verifyNoInteractions(dispatcher)` 的检查失败。修正 `emitToSession` 加入 early return：

在 `StreamMessageEmitter.emitToSession` 方法体开头追加：

```java
        if (sessionId == null || msg == null) return;
```

同时 `emitToClient` 开头也加（避免 null sessionId 触发 log 且仍会 call redis）：

```java
        if (sessionId == null || msg == null) return;
```

完整修改后 `emitToSession` 方法：

```java
    public void emitToSession(SkillSession session, String sessionId,
                              String userId, StreamMessage msg) {
        if (sessionId == null || msg == null) return;
        enrich(sessionId, msg);
        dispatcher.deliver(session, sessionId, userId, msg);
    }
```

`emitToClient` 方法：

```java
    public void emitToClient(String sessionId, String userIdHint, StreamMessage msg) {
        if (sessionId == null || msg == null) return;
        try {
            enrich(sessionId, msg);
            String userId = resolveUserId(sessionId, userIdHint);
            if (userId == null || userId.isBlank()) {
                log.warn("emitToClient skipped: no userId resolvable, sessionId={}, type={}",
                        sessionId, msg.getType());
                return;
            }
            sendToUserChannel(sessionId, userId, msg);
            log.info("[EMIT->CLIENT] sessionId={}, type={}, userId={}",
                    sessionId, msg.getType(), userId);
        } catch (Exception e) {
            log.error("emitToClient failed: sessionId={}, type={}, error={}",
                    sessionId, msg.getType(), e.getMessage());
        }
    }
```

- [ ] **Step 4: 运行全部 19 条测试**

Run: `mvn -pl skill-server test -Dtest=StreamMessageEmitterTest -q`
Expected: 全部 19 条 PASS

- [ ] **Step 5: Commit**

```bash
git add skill-server/src/main/java/com/opencode/cui/skill/service/delivery/StreamMessageEmitter.java \
        skill-server/src/test/java/com/opencode/cui/skill/service/delivery/StreamMessageEmitterTest.java
git commit -m "feat(emitter): implement emitToClientWithBuffer and null-boundary guards"
```

---

### Task 9: Phase 1 验证 — 全量测试 + 冒烟编译

**Files:** 无

- [ ] **Step 1: 跑 skill-server 全量测试**

Run: `mvn -pl skill-server test -q`
Expected: 所有既存测试 + 新 `StreamMessageEmitterTest` 19 条 PASS（因 Phase 1 未改业务代码，既存测试不受影响）

- [ ] **Step 2: 确认 Phase 1 结束前 Emitter 尚未被任何业务代码引用**

Run grep 验证（预期结果仅本身测试文件 + 新类自身）：

```bash
grep -rn "StreamMessageEmitter" skill-server/src
```

Expected: 仅 `StreamMessageEmitter.java` 与 `StreamMessageEmitterTest.java` 两文件出现

- [ ] **Step 3: Phase 1 无额外 commit**（前 8 个 task 已提交）

---

## Phase 2 — 全量迁移（18 处调用点，单 commit 推荐 / 按批次测试）

Phase 2 的迁移步骤繁多但耦合紧（Router 内部 enrichStreamMessage 删除会影响所有 Router handler），建议在 feature 分支上分批修改，**所有改动最终以单个 commit 合并**（见 spec §5.1 Step 2 约束）。

**实施约定**：Phase 2 过程中允许中间状态测试不全绿；每个 task 结尾检查编译通过即可，最终在 Task 17 跑全量回归。

**⚠️ 先决任务**：Task 10/13 会给 `GatewayMessageRouter` / `GatewayRelayService` 追加构造器参数 `StreamMessageEmitter emitter`，这将破坏仓库里 5 个手动 `new` 受影响类的测试文件的编译，以及 20+ 处 `verify(outboundDeliveryDispatcher).deliver(...)` 断言。**必须先完成 Task 9.5** 统一适配，否则 Phase 2 结束时测试无法通过。

**注意边界**：Router 内部除了"enrich + dispatcher.deliver"组合外，`dispatchStreamMessage` 和 `handleToolDone` 还**独立**调 `bufferService.accumulate(...)`（line 551/596）用于 miniapp / null session 的 buffer 累积，以及 `persistIfFinal(...)` 用于持久化。这两处是**业务副作用路径**，**本次重构不迁移**，保留在 Router 内部。对应的测试断言 `verify(bufferService).accumulate(...)` 和 `verify(persistenceService).persistIfFinal(...)` 都**保持不变**。

---

### Task 9.5: 现有测试 Fixture 批量适配（构造器 + 断言重定位）

**Files:**
- Modify: `skill-server/src/test/java/com/opencode/cui/skill/service/GatewayRelayServiceTest.java`
- Modify: `skill-server/src/test/java/com/opencode/cui/skill/service/GatewayRelayServiceScopeTest.java`
- Modify: `skill-server/src/test/java/com/opencode/cui/skill/service/GatewayMessageRouterImPushTest.java`
- Modify: `skill-server/src/test/java/com/opencode/cui/skill/service/ImOutboundFilterTest.java`
- Modify: `skill-server/src/test/java/com/opencode/cui/skill/service/SsRelayAndTakeoverTest.java`

**背景**：受影响的 5 个测试文件都手动 `new` Router/RelayService 实例（而非 `@InjectMocks`），并对 `outboundDeliveryDispatcher` / `bufferService` / `persistenceService` 做断言。Phase 2 的 Router 改造只会改变 **dispatcher.deliver** 的调用路径；`bufferService.accumulate` 和 `persistIfFinal` 仍由 Router 内部在 deliver 之后独立调用，它们的副作用路径不变。

因此迁移规则收窄：
- 只改：`verify(outboundDeliveryDispatcher).deliver(any(), eq("xxx"), ..., capture)` → `verify(emitter).emitToSession(any(), eq("xxx"), ..., capture)`
- **保持不变**：`verify(bufferService).accumulate(...)` —— 守护的是 Router 的 miniapp buffer 业务副作用（`dispatchStreamMessage` / `handleToolDone` 内 deliver 之后独立调 accumulate），不是 `publishProtocolMessage` 那条已迁到 emitter 的路径
- **保持不变**：`verify(persistenceService).persistIfFinal(...)` —— 不在 enrich 路径内，Router 独立调用，迁移不影响

---

- [ ] **Step 1: 在每个受影响测试文件顶部添加 emitter mock**

在以下 5 个文件的 `@Mock` 区追加：

```java
    @Mock
    com.opencode.cui.skill.service.delivery.StreamMessageEmitter emitter;
```

文件列表：
- `GatewayRelayServiceTest.java`
- `GatewayRelayServiceScopeTest.java`
- `GatewayMessageRouterImPushTest.java`
- `ImOutboundFilterTest.java`
- `SsRelayAndTakeoverTest.java`

- [ ] **Step 2: 在每个 `new GatewayMessageRouter(...)` 参数列表末尾追加 `emitter`**

- `GatewayRelayServiceTest.java:92` — 在最后一个现有参数后加 `, emitter`（注意原构造末尾参数 `120` 可能是个 int 字段，emitter 应**插在 dispatcher 之后、120 之前**；按 Task 10 Step 1 实际构造签名顺序调整）
- `GatewayMessageRouterImPushTest.java:84` — 同上
- `ImOutboundFilterTest.java:71` — 同上
- `SsRelayAndTakeoverTest.java:81` — 同上

> **约定**：Task 10 在给 `GatewayMessageRouter` 加构造参数时，**把 `StreamMessageEmitter emitter` 插入在现有 `OutboundDeliveryDispatcher outboundDeliveryDispatcher` 参数的**下一个位置**（保留 `outboundDeliveryDispatcher` 字段——后续可能仍有其他调用点暂未迁，且外部 wrapper 保留完成后可以统一清理）。执行 Task 10 时以此顺序落地；本 task 的测试调用按此顺序追加 `emitter` 参数。

- [ ] **Step 3: 在每个 `new GatewayRelayService(...)` 参数列表末尾追加 `emitter`**

- `GatewayRelayServiceTest.java:109` — 末尾加 `, emitter`
- `GatewayRelayServiceScopeTest.java:55` — 末尾加 `, emitter`

同样约定：Task 13 给 `GatewayRelayService` 构造器**末尾**追加 `StreamMessageEmitter emitter` 参数。

- [ ] **Step 4: 重定位 `GatewayRelayServiceTest.java` 的断言**

打开 `GatewayRelayServiceTest.java`，对以下行执行替换（行号基于当前版本，若偏移需重新定位）：

| 原 | 改为 |
|---|------|
| `verify(outboundDeliveryDispatcher).deliver(any(), eq("..."), any(), msgCaptor.capture())` | `verify(emitter).emitToSession(any(), eq("..."), any(), msgCaptor.capture())` |
| `verify(outboundDeliveryDispatcher, times(N)).deliver(...)` | `verify(emitter, times(N)).emitToSession(...)` |
| `verify(outboundDeliveryDispatcher, atLeast(N)).deliver(...)` | `verify(emitter, atLeast(N)).emitToSession(...)` |

**行号提示**（如仓库结构未变）：
- dispatcher 断言（**迁移**）：line 134, 160, 184, 218, 230, 253, 274, 295, 350, 375, 398, 477, 492, 528, 541, 553, 581, 602, 637, 673, 699（共 **21 处**）
- bufferService 断言（**不改**）：line 137, 233 — 守 Router 内部 miniapp buffer 业务副作用，与 emitter 路径无关
- persistIfFinal 断言（**不改**）：line 138, 162, 234, 641 — 不在 enrich 路径内

> **批量替换技巧**（bash）：
> ```bash
> # 仅改 dispatcher 断言；bufferService / persistenceService 断言保持不变
> sed -i 's/verify(outboundDeliveryDispatcher)\.deliver/verify(emitter).emitToSession/g' \
>     skill-server/src/test/java/com/opencode/cui/skill/service/GatewayRelayServiceTest.java
> sed -i 's/verify(outboundDeliveryDispatcher, \(times\|atLeast\|atMost\)/verify(emitter, \1/g' \
>     skill-server/src/test/java/com/opencode/cui/skill/service/GatewayRelayServiceTest.java
> # 上述 sed 只改了前缀 "verify(outboundDeliveryDispatcher, times(N))"；其后紧跟的 ".deliver(" 还没改，再补一刀
> sed -i 's/verify(emitter, \(times\|atLeast\|atMost\)\(([^)]*)\))\.deliver/verify(emitter, \1\2).emitToSession/g' \
>     skill-server/src/test/java/com/opencode/cui/skill/service/GatewayRelayServiceTest.java
> ```
> 执行后人工 review diff，确认：
> - `verify(bufferService).accumulate(...)` **未被改动**（line 137, 233）
> - `verify(persistenceService).persistIfFinal(...)` **未被改动**
> - captor 变量名（msgCaptor 等）在 assertion 中仍指向正确对象

- [ ] **Step 5: 重定位 `ImOutboundFilterTest.java` 的断言**

`ImOutboundFilterTest.java:158, 174, 190` 三处 `verify(outboundDeliveryDispatcher)` 断言全部改为 `verify(emitter).emitToSession(...)`（line 174, 190 是 `never()` 变体，语义保留）：

```java
// 原
verify(outboundDeliveryDispatcher).deliver(any(SkillSession.class), eq(SESSION_ID), eq(USER_ID), any(StreamMessage.class));
// 改为
verify(emitter).emitToSession(any(SkillSession.class), eq(SESSION_ID), eq(USER_ID), any(StreamMessage.class));

// 原（never）
verify(outboundDeliveryDispatcher, never()).deliver(any(), any(), any(), any());
// 改为
verify(emitter, never()).emitToSession(any(), any(), any(), any());
```

- [ ] **Step 6: 检查其他文件是否也有需迁的 dispatcher 断言**

```bash
grep -rn "verify(outboundDeliveryDispatcher" skill-server/src/test
```

如果 Step 4/5 之后还有命中，按 Step 4 的替换规则处理。

**不要** grep `verify(bufferService).accumulate` —— 这类断言**保持原样**；误改会破坏 miniapp buffer 副作用的回归保护。

- [ ] **Step 7: 编译（不跑测试；此时 Task 10 尚未执行，构造器参数还少一个，测试编译仍会失败——这是预期的）**

本 task **不要求**编译通过。它是"提前把测试对齐到 Task 10/13 完成后的目标形态"。真正的编译验证在 Task 10/13 完成后（每个 task 的 Step 8）。

- [ ] **Step 8: 不 commit**

Task 9.5 的所有改动聚合进 Phase 2 的最终单 commit（Task 17）。本 task 只是工作流中的一步，不独立 commit。

---

### Task 10: 迁移 `GatewayMessageRouter` 的 6 处业务投递调用

**Files:**
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/service/GatewayMessageRouter.java`

> **边界提醒**：本 task 只替换 `enrichStreamMessage(...) + outboundDeliveryDispatcher.deliver(...)` 这两行组合为单行 `emitter.emitToSession(...)`。**不要**删除或改动紧邻的 `bufferService.accumulate(...)`（line 551、596）和 `persistenceService.persistIfFinal(...)`（line 557 等）——这些是 Router 的业务副作用，独立于本次出口重构，必须保留。

- [ ] **Step 1: 在 `GatewayMessageRouter` 注入 `StreamMessageEmitter`**

位置：`GatewayMessageRouter.java:69` 字段区域后追加：

```java
    private final com.opencode.cui.skill.service.delivery.StreamMessageEmitter emitter;
```

修改构造函数签名（找到现有构造函数，在参数列表末尾追加 `StreamMessageEmitter emitter`，并在方法体末尾赋值 `this.emitter = emitter;`）。

> 提示：如果 Router 使用 Lombok `@RequiredArgsConstructor`，则仅添加 final 字段即可；如果是手写构造，须显式添加参数与赋值。本文件是手写构造，按后者处理。

- [ ] **Step 2: 替换第 1 处（line 544-547）**

原代码：

```java
        // 统一填充 welinkSessionId、emittedAt 等公共字段
        enrichStreamMessage(sessionId, msg);

        // 统一投递
        outboundDeliveryDispatcher.deliver(session, sessionId, userId, msg);
```

替换为：

```java
        // 统一出站（enrich 由 emitter 内部完成）
        emitter.emitToSession(session, sessionId, userId, msg);
```

- [ ] **Step 3: 替换第 2 处（line 583-584）**

原代码：

```java
            enrichStreamMessage(sessionId, textDoneMsg);
            outboundDeliveryDispatcher.deliver(session, sessionId, userId, textDoneMsg);
```

替换为：

```java
            emitter.emitToSession(session, sessionId, userId, textDoneMsg);
```

- [ ] **Step 4: 替换第 3 处（line 592-593）**

原代码：

```java
        enrichStreamMessage(sessionId, msg);
        outboundDeliveryDispatcher.deliver(session, sessionId, userId, msg);
```

替换为：

```java
        emitter.emitToSession(session, sessionId, userId, msg);
```

- [ ] **Step 5: 替换第 4 处（line 643-644）**

原代码：

```java
        enrichStreamMessage(sessionId, errorMsg);
        outboundDeliveryDispatcher.deliver(session, sessionId, userId, errorMsg);
```

替换为：

```java
        emitter.emitToSession(session, sessionId, userId, errorMsg);
```

- [ ] **Step 6: 替换第 5 处（line 787-788）**

原代码：

```java
        enrichStreamMessage(sessionId, msg);
        outboundDeliveryDispatcher.deliver(session, sessionId, userId, msg);
```

替换为：

```java
        emitter.emitToSession(session, sessionId, userId, msg);
```

- [ ] **Step 7: 替换第 6 处（line 1056-1057）**

原代码：

```java
        enrichStreamMessage(sessionId, resetMsg);
        outboundDeliveryDispatcher.deliver(session, sessionId, userId, resetMsg);
```

替换为：

```java
        emitter.emitToSession(session, sessionId, userId, resetMsg);
```

- [ ] **Step 8: 编译检查**

Run: `mvn -pl skill-server compile -q`
Expected: BUILD SUCCESS（此时 `enrichStreamMessage` 私有方法仍在但已无调用方；编译器可能告警但不阻塞）

---

### Task 11: 迁移 `GatewayMessageRouter` 的前端强推 4 处 + wrapper 改造

**Files:**
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/service/GatewayMessageRouter.java`

- [ ] **Step 1: 替换第 7 处 `broadcastStreamMessage`（line 471）**

原代码：

```java
            broadcastStreamMessage(sessionId, userId, StreamMessage.sessionStatus("busy"));
```

替换为：

```java
            emitter.emitToClient(sessionId, userId, StreamMessage.sessionStatus("busy"));
```

- [ ] **Step 2: 替换第 8 处（line 672-673）**

原代码：

```java
        activeSessions.forEach(session -> broadcastStreamMessage(
                session.getId().toString(),
                session.getUserId(), StreamMessage.agentOnline()));
```

替换为：

```java
        activeSessions.forEach(session -> emitter.emitToClient(
                session.getId().toString(),
                session.getUserId(), StreamMessage.agentOnline()));
```

（根据原多行布局调整缩进）

- [ ] **Step 3: 替换第 9 处（line 686-688）**

原代码：

```java
        sessionService.findActiveByAk(ak).forEach(session -> broadcastStreamMessage(
                session.getId().toString(),
                session.getUserId(), msg));
```

替换为：

```java
        sessionService.findActiveByAk(ak).forEach(session -> emitter.emitToClient(
                session.getId().toString(),
                session.getUserId(), msg));
```

- [ ] **Step 4: 替换第 10 处（line 759）**

原代码：

```java
        broadcastStreamMessage(sessionId, userId, StreamMessage.sessionStatus("busy"));
```

替换为：

```java
        emitter.emitToClient(sessionId, userId, StreamMessage.sessionStatus("busy"));
```

- [ ] **Step 5: 改造 `broadcastStreamMessage` 为 thin wrapper**

原方法（约 line 870-891）整体替换为：

```java
    /**
     * 广播 StreamMessage 到前端。
     * @deprecated 保留以维持外部测试兼容；内部请直接使用 {@link StreamMessageEmitter#emitToClient}。
     */
    @Deprecated
    public void broadcastStreamMessage(String sessionId, String userIdHint, StreamMessage msg) {
        emitter.emitToClient(sessionId, userIdHint, msg);
    }
```

- [ ] **Step 6: 改造 `publishProtocolMessage` 为 thin wrapper**

原方法（约 line 893-897）整体替换为：

```java
    /**
     * 发布协议消息（广播 + 缓冲）。
     * @deprecated 保留以维持外部测试兼容；内部请直接使用 {@link StreamMessageEmitter#emitToClientWithBuffer}。
     */
    @Deprecated
    public void publishProtocolMessage(String sessionId, StreamMessage msg) {
        emitter.emitToClientWithBuffer(sessionId, msg);
    }
```

- [ ] **Step 7: 改造 Router 内部 `RebuildCallback` 匿名类**

原代码（line 1124-1128）：

```java
    private final SessionRebuildService.RebuildCallback rebuildCallback = new SessionRebuildService.RebuildCallback() {
        @Override
        public void broadcast(String sessionId, String userId, StreamMessage msg) {
            broadcastStreamMessage(sessionId, userId, msg);
        }
```

将方法体改为直接调 emitter：

```java
    private final SessionRebuildService.RebuildCallback rebuildCallback = new SessionRebuildService.RebuildCallback() {
        @Override
        public void broadcast(String sessionId, String userId, StreamMessage msg) {
            emitter.emitToClient(sessionId, userId, msg);
        }
```

- [ ] **Step 8: 编译检查**

Run: `mvn -pl skill-server compile -q`
Expected: BUILD SUCCESS

---

### Task 12: 删除 `GatewayMessageRouter.enrichStreamMessage` 私有方法

**Files:**
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/service/GatewayMessageRouter.java`

- [ ] **Step 1: 删除 `enrichStreamMessage` 方法**

找到 `enrichStreamMessage` 方法（约 line 991-1005），**整体删除**。同时删除其辅助方法 `isEmittedAtExcluded`（约 line 1019-1022）——已由 emitter 内部常量取代。

也删除类顶部的常量（约 line 52-57）：

```java
    private static final Set<String> EMITTED_AT_EXCLUDED_TYPES = Set.of(
            StreamMessage.Types.PERMISSION_REPLY,
            StreamMessage.Types.AGENT_ONLINE,
            StreamMessage.Types.AGENT_OFFLINE,
            StreamMessage.Types.ERROR);
```

以及 `import java.util.Set;`（若无其他引用）—— IDE 或 `mvn compile` 会提示。

- [ ] **Step 2: 编译检查**

Run: `mvn -pl skill-server compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: grep 验证旧方法已绝迹**

```bash
grep -rn "enrichStreamMessage" skill-server/src
```

Expected: 零匹配

---

### Task 13: 迁移 `GatewayRelayService` 的 `RebuildCallback`

**Files:**
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/service/GatewayRelayService.java`

- [ ] **Step 1: 注入 emitter**

在 `GatewayRelayService` 添加字段：

```java
    private final com.opencode.cui.skill.service.delivery.StreamMessageEmitter emitter;
```

同步修改构造函数：参数列表末尾添加 `StreamMessageEmitter emitter`，赋值 `this.emitter = emitter;`。

- [ ] **Step 2: 修改 `RebuildCallback` 匿名类实现**

找到 line 336-340 附近：

```java
                new SessionRebuildService.RebuildCallback() {
                    @Override
                    public void broadcast(String sid, String uid, StreamMessage msg) {
                        messageRouter.broadcastStreamMessage(sid, uid, msg);
                    }
```

替换为：

```java
                new SessionRebuildService.RebuildCallback() {
                    @Override
                    public void broadcast(String sid, String uid, StreamMessage msg) {
                        emitter.emitToClient(sid, uid, msg);
                    }
```

- [ ] **Step 3: 编译检查**

Run: `mvn -pl skill-server compile -q`
Expected: BUILD SUCCESS

---

### Task 14: 迁移 `InboundProcessingService`（含 Bug 修复）

**Files:**
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/service/InboundProcessingService.java`

- [ ] **Step 1: 注入 emitter、移除 dispatcher 依赖**

顶部字段区域，**删除**：

```java
    private final OutboundDeliveryDispatcher outboundDeliveryDispatcher;
```

（line 50）

**新增**：

```java
    private final com.opencode.cui.skill.service.delivery.StreamMessageEmitter emitter;
```

同步修改构造函数：**移除** `OutboundDeliveryDispatcher outboundDeliveryDispatcher` 参数 + 赋值语句；**新增** `StreamMessageEmitter emitter` 参数 + 赋值。同时删除不再使用的 import `com.opencode.cui.skill.service.delivery.OutboundDeliveryDispatcher`。

- [ ] **Step 2: 确认 `InboundProcessingService:281`（IM 入站 reply）不直接迁移**

该处调用 `gatewayRelayService.publishProtocolMessage(...)` — 是外部 public API，签名保留（Task 11 已把它改为内部 delegate 到 emitter 的 thin wrapper）。`InboundProcessingService` 本文件**无需修改这一行**。本 task 仅需迁移 line 339（Step 3）。

- [ ] **Step 3: 迁移第 7 处 — agent offline bug 点（line 339）**

原代码（line 334-347）：

```java
        SkillSession session = sessionManager.findSession(businessDomain, sessionType, sessionId, ak);
        if (session != null) {
            StreamMessage offlineMsg = StreamMessage.builder()
                    .type(StreamMessage.Types.ERROR)
                    .error(AGENT_OFFLINE_MESSAGE)
                    .build();
            outboundDeliveryDispatcher.deliver(session,
                    String.valueOf(session.getId()), null, offlineMsg);
            if (session.isImDirectSession()) {
                try {
                    messageService.saveSystemMessage(session.getId(), AGENT_OFFLINE_MESSAGE);
                } catch (Exception e) {
                    log.error("Failed to persist agent_offline message: {}", e.getMessage());
                }
            }
        }
```

替换 `outboundDeliveryDispatcher.deliver(...)` 为 `emitter.emitToSession(...)`：

```java
        SkillSession session = sessionManager.findSession(businessDomain, sessionType, sessionId, ak);
        if (session != null) {
            StreamMessage offlineMsg = StreamMessage.builder()
                    .type(StreamMessage.Types.ERROR)
                    .error(AGENT_OFFLINE_MESSAGE)
                    .build();
            emitter.emitToSession(session,
                    String.valueOf(session.getId()), null, offlineMsg);
            if (session.isImDirectSession()) {
                try {
                    messageService.saveSystemMessage(session.getId(), AGENT_OFFLINE_MESSAGE);
                } catch (Exception e) {
                    log.error("Failed to persist agent_offline message: {}", e.getMessage());
                }
            }
        }
```

- [ ] **Step 4: 编译检查**

Run: `mvn -pl skill-server compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 5: 更新 `InboundProcessingServiceTest` 的构造参数**

**Files:** Modify `skill-server/src/test/java/com/opencode/cui/skill/service/InboundProcessingServiceTest.java`

在测试 setUp 中找到创建 `InboundProcessingService` 实例的地方，把原来的 `outboundDeliveryDispatcher` mock 参数替换为 `emitter` mock。新增字段：

```java
    @Mock
    com.opencode.cui.skill.service.delivery.StreamMessageEmitter emitter;
```

并从测试类中移除不再用的 `@Mock OutboundDeliveryDispatcher ...` 字段（若存在）。

- [ ] **Step 6: 将 `handleAgentOffline` 改为 package-private**

在 `InboundProcessingService.java` 把方法声明：

```java
    private void handleAgentOffline(String businessDomain, String sessionType,
                                     String sessionId, String ak, String assistantAccount) {
```

改为（去掉 `private`）：

```java
    void handleAgentOffline(String businessDomain, String sessionType,
                             String sessionId, String ak, String assistantAccount) {
```

这是本次回归测试的唯一可见性调整；将方法置于同包可测范围，避免通过 `process()` 构造复杂 fixture 才能触发 offline 分支。

- [ ] **Step 7: 追加 agent-offline 回归测试**

在 `InboundProcessingServiceTest` 类顶部 import 区确认含：

```java
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import org.mockito.ArgumentCaptor;
import com.opencode.cui.skill.model.SkillSession;
import com.opencode.cui.skill.model.StreamMessage;
```

在类内追加测试方法：

```java
    @Test
    void handleAgentOffline_ExternalWs_shouldRouteViaEmitter() {
        // given: 非 miniapp/IM domain 的 external ws session
        SkillSession session = mock(SkillSession.class);
        when(session.getId()).thenReturn(101L);
        when(session.isImDirectSession()).thenReturn(false);
        when(sessionManager.findSession(
                eq("ext"), eq("single"), eq("101"), eq("ak-x")))
                .thenReturn(session);

        // when: 直接触发 handleAgentOffline（已在 Step 6 改为 package-private）
        inboundProcessingService.handleAgentOffline(
                "ext", "single", "101", "ak-x", "assistant-x");

        // then: 走 emitter.emitToSession 路径，msg 携带 ERROR 类型 + offline 文案
        ArgumentCaptor<StreamMessage> cap = ArgumentCaptor.forClass(StreamMessage.class);
        verify(emitter).emitToSession(eq(session), eq("101"), isNull(), cap.capture());
        assertEquals(StreamMessage.Types.ERROR, cap.getValue().getType());
        assertNotNull(cap.getValue().getError());
    }
```

> **关于 offline 文案断言**：spec §4.2 原示例包含 `assertEquals(AGENT_OFFLINE_MESSAGE, ...)`。该常量在 `InboundProcessingService` 是 private 的——不调整可见性则测试无法引用。本 plan 改为 `assertNotNull(...getError())` 作为弱化断言，原因：文案本身不是回归保护重点（文案变化不应破坏本测试）；"走 emitter + type=ERROR + error 字段非空"三者已守住 bug 闭环的必要条件。

- [ ] **Step 8: 编译 + 跑 InboundProcessingServiceTest**

Run: `mvn -pl skill-server test -Dtest=InboundProcessingServiceTest -q`
Expected: PASS（既存用例 + 新 agent-offline 用例）

---

### Task 15: 迁移 `SkillMessageController` 的 3 处

**Files:**
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/controller/SkillMessageController.java`

- [ ] **Step 1: 控制器保留外部 API 调用形式，无需改**

根据 spec §3.3 和迁移策略，`SkillMessageController:173/423` 走 `gatewayRelayService.publishProtocolMessage(...)` 的调用**不动** — 该 public API 签名保留，内部已 delegate 到 emitter（Task 11 完成）。

同样，`SkillMessageController:131` 走 `messageRouter.broadcastStreamMessage(...)` 的调用也**不动** — 该 public API 同样保留 wrapper。

- [ ] **Step 2: 验证编译 + 原有测试**

Run: `mvn -pl skill-server test -Dtest=SkillMessageControllerTest -q`
Expected: 原断言 `verify(gatewayRelayService).publishProtocolMessage(...)` 仍 PASS（Mock 目标未变）

> **说明**：Task 15 实际是"确认 controller 层无迁移动作"的占位 task。spec 的迁移表 #12/15/16 标"迁移后 emitter"，但在 wrapper 策略下保留外部 API 意味着 controller 代码不直接引用 emitter。此 task 仅作状态确认。

---

### Task 16: 新增 `ExternalWsDeliveryStrategyTest` 序列化守护

**Files:**
- Modify: `skill-server/src/test/java/com/opencode/cui/skill/service/delivery/ExternalWsDeliveryStrategyTest.java`

- [ ] **Step 1: 追加序列化守护用例**

在 `ExternalWsDeliveryStrategyTest` 类内追加：

```java
    @Test
    void deliver_errorEvent_serializedJsonContainsWelinkSessionId() throws Exception {
        // given: 一条 enrich 过的 error StreamMessage（模拟 emitter 输出状态）
        SkillSession session = mock(SkillSession.class);
        when(session.getBusinessSessionDomain()).thenReturn("ext-domain");
        when(session.isMiniappDomain()).thenReturn(false);
        when(session.isImDomain()).thenReturn(false);

        StreamMessage msg = StreamMessage.builder()
                .type(StreamMessage.Types.ERROR)
                .error("agent offline")
                .build();
        msg.setSessionId("101");
        msg.setWelinkSessionId("101");

        when(externalStreamHandler.pushToOne(anyString(), anyString())).thenReturn(true);
        ArgumentCaptor<String> jsonCap = ArgumentCaptor.forClass(String.class);

        // when
        strategy.deliver(session, "101", null, msg);

        // then: 捕获发出的 JSON payload，断言含 welinkSessionId
        verify(externalStreamHandler).pushToOne(anyString(), jsonCap.capture());
        var payload = new ObjectMapper().readTree(jsonCap.getValue());
        assertEquals("101", payload.path("welinkSessionId").asText());
        assertEquals("error", payload.path("type").asText());
    }
```

> **注意**：本测试类原有 `@Mock ExternalStreamHandler externalStreamHandler` 和 `strategy` 字段；若命名不同，按实际调整。import 需包含 `com.fasterxml.jackson.databind.ObjectMapper` 与 `org.mockito.ArgumentCaptor`。

- [ ] **Step 2: 跑测试**

Run: `mvn -pl skill-server test -Dtest=ExternalWsDeliveryStrategyTest -q`
Expected: 全部 PASS（新增 1 条 + 原有）

---

### Task 17: Phase 2 整体回归 + grep 审计 + 单 commit 提交

**Files:** 无新改动，只做验证与提交

- [ ] **Step 1: 跑 skill-server 全量测试**

Run: `mvn -pl skill-server test -q`
Expected: BUILD SUCCESS，所有既有测试 + 新增用例 PASS

- [ ] **Step 2: Grep 审计**

```bash
grep -rn "enrichStreamMessage" skill-server/src
```
Expected: 零匹配

```bash
grep -rn "outboundDeliveryDispatcher\.deliver" skill-server/src/main
```
Expected: 仅 1 处（`StreamMessageEmitter.java` 内部）

```bash
grep -rn "redisMessageBroker\.publishToUser\|redisBroker\.publishToUser" skill-server/src/main
```
Expected: 仅 2 处（`StreamMessageEmitter.java` + `MiniappDeliveryStrategy.java`）

- [ ] **Step 3: 将 Phase 2 所有修改聚合为单 commit**

如果 Phase 2 中间状态被拆成了多个 commit，用 interactive rebase 合并为一个：

```bash
# 将 Phase 1 之后的所有 commits 合并（假设 Phase 1 结束在 <phase1-hash>）
git log --oneline <phase1-hash>..HEAD
# 确认要合并的范围后：
git reset --soft <phase1-hash>
git commit -m "refactor(skill-server): unify StreamMessage outbound via Emitter

Migrates 18 call sites to StreamMessageEmitter, deletes
GatewayMessageRouter.enrichStreamMessage, and thin-wraps the public
broadcast/publishProtocolMessage APIs. Closes agent-offline external-ws
welinkSessionId bug by ensuring every outbound path enriches.

Test coverage: 19 emitter cases, 1 InboundProcessingService regression,
1 ExternalWsDeliveryStrategy serialization guard."
```

> **注意**：若已推送到远端，**不要** 强推 main；在 feature 分支上 reset/force-push 属于常规操作。

---

## Phase 3 — 手工 / E2E 验收

### Task 18: 本地启服务 + agent offline 端到端验证

**Files:** 无代码改动

- [ ] **Step 1: 启动 skill-server 本地实例**

Run: `mvn -pl skill-server spring-boot:run`（或 IDE 启动）

- [ ] **Step 2: 启动一个 mock external ws client 并连接 WebSocket endpoint**

External WS 客户端是**直接连 WebSocket**，不需要自己订阅 Redis。真实流程：
1. 客户端发起 `ws://localhost:<port>/ws/external/stream` 连接，携带 `Sec-WebSocket-Protocol: auth.<base64-url-encoded-json>` subprotocol（JSON 内含 `token` / `source` / `instanceId`，参见 `ExternalStreamHandler.verifyToken`）
2. 服务端握手通过后，`ExternalStreamHandler.afterConnectionEstablished` 会**自动**订阅对应的 Redis `stream:<source>` channel，并把落到这个 channel 的消息推回这条 WS 连接

所以客户端只需收 WebSocket message 就行。参考 `benchmark/mocks/` 已有的 mock 实现，或自写最小 Python ws client（`websockets` 库即可）。记录每条收到的 JSON 文本。

- [ ] **Step 3: 触发 agent offline 场景**

构造请求让 skill-server 走 `InboundProcessingService.handleAgentOffline` 路径（例如：对一个非 miniapp/IM 的 session 发起 IM 入站，且该 session 的 ak 无在线 agent）。具体参数依本地现有 e2e 数据准备，可参考 `tools/e2e-test-comprehensive.py`。

- [ ] **Step 4: 断言 external ws 客户端收到的 error 事件含 `welinkSessionId`**

在 mock client 日志中找到 `type=error` 的 JSON，验证字段存在且等于 session id。

- [ ] **Step 5: 跑现有字段一致性脚本**

Run: `python tools/e2e-test-field-consistency.py`
Expected: 全部字段断言 PASS（该脚本已覆盖 welinkSessionId 在 Part/Message/Session 级事件中的存在性）

- [ ] **Step 6: 观测日志前缀**

在 skill-server 日志中确认出现 `[EMIT->CLIENT]`，不再出现 `[EXIT->FE] Broadcast StreamMessage`。在 PR 描述中记录此日志前缀变更，提示观测团队。

---

### Task 19: 开 PR 并附验收产物

**Files:** 无代码改动

- [ ] **Step 1: 推分支并开 PR**

```bash
git push -u origin <feature-branch>
gh pr create --title "refactor(skill-server): unify StreamMessage outbound via Emitter" \
  --body "$(cat <<'EOF'
## Summary

- 新建 `StreamMessageEmitter` 作为所有 StreamMessage 出站的唯一权威入口
- 迁移 18 处调用点；删除 `GatewayMessageRouter.enrichStreamMessage`
- 修复 external ws `type=error` 事件缺失 `welinkSessionId` 的 bug
- 保留 `GatewayRelayService.publishProtocolMessage` / `Router.broadcastStreamMessage` / `Router.publishProtocolMessage` 外部 API（thin wrapper）

## Spec

`docs/superpowers/specs/2026-04-21-stream-message-emitter-refactor-design.md` (v4)

## Test plan

- [x] 新增 19 条 `StreamMessageEmitterTest` 单元测试
- [x] 新增 `InboundProcessingServiceTest` agent-offline 回归用例
- [x] 新增 `ExternalWsDeliveryStrategyTest` JSON 序列化守护
- [x] `mvn -pl skill-server test` 全绿
- [x] grep 审计通过（enrichStreamMessage 绝迹；dispatcher.deliver 仅 emitter 内部 1 处）
- [x] 本地 e2e-test-field-consistency.py 通过
- [x] 手工验 agent offline 场景 external ws 收到的 JSON 含 welinkSessionId

## Log prefix change

`[EXIT->FE] Broadcast StreamMessage: ...` → `[EMIT->CLIENT] sessionId=..., type=..., userId=...`

若线上观测有基于原前缀的 Grafana/Loki 查询，请同步更新。

🤖 Generated with Claude Code
EOF
)"
```

- [ ] **Step 2: 在 PR 评论中附 diff 摘要**

```bash
git diff --stat <main>..HEAD
```
贴入 PR 评论，便于 reviewer 一眼看清改动面（应该集中在 skill-server + 新 spec + 新 plan）。

---

## Revisions

- **v1**（初版）
- **v2**（Codex 静态审阅后修订）：
  - **新增 Task 9.5**：现有测试 Fixture 批量适配 — 覆盖 5 个手动 `new` Router/RelayService 的测试文件的构造器参数补齐，以及 `GatewayRelayServiceTest` 20+ 处 / `ImOutboundFilterTest` 3 处 `verify(outboundDeliveryDispatcher).deliver(...)` → `verify(emitter).emitToSession(...)` 的断言重定位。这是原稿最大盲点
  - **新增 Shell 环境说明**（顶部）：提供 bash ↔ PowerShell 等价命令表，便于 Windows / Codex CLI 执行者
  - **修正 Task 5**：剔除 boundary-1（原稿会把失败测试提进 commit 历史），只保留 enrich-9；boundary-1 整体挪到 Task 8 Step 3（emitToClient / emitToClientWithBuffer 实现完才能全面断言）
  - **修正 Task 18 Step 2**：external ws client 描述从"订阅 `stream:<source>` channel"改为"连 WebSocket endpoint `/ws/external/stream`"，澄清 Redis 订阅是服务端 `ExternalStreamHandler.afterConnectionEstablished` 内部行为
- **v3**（Codex 三次审阅后修订）：
  - **收窄 Task 9.5 迁移范围**：原稿把 `verify(bufferService).accumulate(...)` 也一刀切到 `verify(emitter).emitToClientWithBuffer(...)`，但事实上这类断言守的是 `GatewayMessageRouter.dispatchStreamMessage` / `handleToolDone` 内 deliver 之后**独立**调 accumulate 的业务副作用（miniapp / null session 的 buffer 累积，line 551 / 596），**不是** `publishProtocolMessage` 那条已迁到 emitter 的路径。修正后：`bufferService.accumulate` / `persistIfFinal` 断言一律保持不变；sed 规则只改 dispatcher；Task 10 开头加"业务副作用保留"边界提醒

---

## 附录：Checklist（来自 spec §8）

- [ ] Step 1 单测全绿（Phase 1 结束，19 条 emitter 用例 PASS）
- [ ] Step 2 全量 `mvn -pl skill-server test` 全绿
- [ ] `grep -r "enrichStreamMessage" skill-server/src` 为空
- [ ] `grep -r "outboundDeliveryDispatcher\.deliver" skill-server/src/main` 仅 1 处
- [ ] `grep -r "redisMessageBroker\.publishToUser" skill-server/src/main` 仅 2 处
- [ ] 新增 19 条 emitter 用例 + 1 条 agent-offline 回归 + 1 条 ExternalWs 序列化守护
- [ ] `tools/e2e-test-field-consistency.py` 本地通过
- [ ] 手工验 agent offline：external ws JSON 含 `welinkSessionId`
- [ ] PR 描述含"`[EXIT->FE]` → `[EMIT->CLIENT]`"日志变更说明
