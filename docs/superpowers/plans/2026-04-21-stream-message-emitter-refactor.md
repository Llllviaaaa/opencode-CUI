# StreamMessageEmitter 重构实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 skill-server 中分散在各处的"手动 enrich + 出站"约定收口到统一的 `StreamMessageEmitter`，顺带修复 external ws `type=error` 事件缺失 `welinkSessionId` 的 bug。

**Architecture:** 新建 `StreamMessageEmitter` 作为所有 `StreamMessage` 出站的唯一权威入口；提供 3 个方法对应三种既存出站语义（业务投递 / 前端强推 / 前端强推+buffer）。迁移 18 处调用点。外部 API 签名保留（thin wrapper 向内委托），避免破坏测试与下游引用。

**Tech Stack:** Java 21, Spring Boot 3.4, Maven, JUnit 5, Mockito, Jackson, Redis (Lettuce), Lombok.

**关联 spec:** `docs/superpowers/specs/2026-04-21-stream-message-emitter-refactor-design.md` (v4)

**分支建议:** 在当前 `release-0417` 基础上建 feature 分支；本次改动只触及 `skill-server` 模块。

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

Run: `./mvnw -pl skill-server compile -q`
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

Run: `./mvnw -pl skill-server test -Dtest=StreamMessageEmitterTest -q`
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

Run: `./mvnw -pl skill-server test -Dtest=StreamMessageEmitterTest -q`
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

Run: `./mvnw -pl skill-server test -Dtest=StreamMessageEmitterTest -q`
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

Run: `./mvnw -pl skill-server test -Dtest=StreamMessageEmitterTest -q`
Expected: 全部 8 条 PASS

- [ ] **Step 3: Commit**

```bash
git add skill-server/src/test/java/com/opencode/cui/skill/service/delivery/StreamMessageEmitterTest.java
git commit -m "test(emitter): add enrich messageContext branch cases"
```

---

### Task 5: enrich 幂等 + 边界（2 条用例）

**Files:**
- Modify: `skill-server/src/test/java/com/opencode/cui/skill/service/delivery/StreamMessageEmitterTest.java`

- [ ] **Step 1: 追加 enrich-9 + boundary-1**

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

    @Test
    void boundary1_nullInputs_noOpAllMethods() {
        SkillSession session = mock(SkillSession.class);

        emitter.emitToSession(session, null, null, null);
        emitter.emitToClient(null, null, null);
        emitter.emitToClientWithBuffer(null, null);

        // Note: emitToClient/emitToClientWithBuffer 尚未实现，本条会部分失败
        // 暂时接受失败；完整验证在 Task 7/8 后重新跑
    }
```

- [ ] **Step 2: 运行并验证 enrich-9 通过（boundary-1 部分失败暂接受）**

Run: `./mvnw -pl skill-server test -Dtest=StreamMessageEmitterTest#enrich9_repeatedEmit_stableFields -q`
Expected: PASS

- [ ] **Step 3: Commit（注明 boundary-1 待后续方法实现后回补）**

```bash
git add skill-server/src/test/java/com/opencode/cui/skill/service/delivery/StreamMessageEmitterTest.java
git commit -m "test(emitter): add enrich idempotency and null-boundary cases"
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

Run: `./mvnw -pl skill-server test -Dtest=StreamMessageEmitterTest -q`
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

Run: `./mvnw -pl skill-server test -Dtest=StreamMessageEmitterTest -q`
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

- [ ] **Step 3: 回补 boundary-1 断言**

替换原 boundary-1 测试为：

```java
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

Run: `./mvnw -pl skill-server test -Dtest=StreamMessageEmitterTest -q`
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

Run: `./mvnw -pl skill-server test -q`
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

---

### Task 10: 迁移 `GatewayMessageRouter` 的 6 处业务投递调用

**Files:**
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/service/GatewayMessageRouter.java`

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

Run: `./mvnw -pl skill-server compile -q`
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

Run: `./mvnw -pl skill-server compile -q`
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

Run: `./mvnw -pl skill-server compile -q`
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

Run: `./mvnw -pl skill-server compile -q`
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

Run: `./mvnw -pl skill-server compile -q`
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

Run: `./mvnw -pl skill-server test -Dtest=InboundProcessingServiceTest -q`
Expected: PASS（既存用例 + 新 agent-offline 用例）

---

### Task 15: 迁移 `SkillMessageController` 的 3 处

**Files:**
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/controller/SkillMessageController.java`

- [ ] **Step 1: 控制器保留外部 API 调用形式，无需改**

根据 spec §3.3 和迁移策略，`SkillMessageController:173/423` 走 `gatewayRelayService.publishProtocolMessage(...)` 的调用**不动** — 该 public API 签名保留，内部已 delegate 到 emitter（Task 11 完成）。

同样，`SkillMessageController:131` 走 `messageRouter.broadcastStreamMessage(...)` 的调用也**不动** — 该 public API 同样保留 wrapper。

- [ ] **Step 2: 验证编译 + 原有测试**

Run: `./mvnw -pl skill-server test -Dtest=SkillMessageControllerTest -q`
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

Run: `./mvnw -pl skill-server test -Dtest=ExternalWsDeliveryStrategyTest -q`
Expected: 全部 PASS（新增 1 条 + 原有）

---

### Task 17: Phase 2 整体回归 + grep 审计 + 单 commit 提交

**Files:** 无新改动，只做验证与提交

- [ ] **Step 1: 跑 skill-server 全量测试**

Run: `./mvnw -pl skill-server test -q`
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

Run: `./mvnw -pl skill-server spring-boot:run`（或 IDE 启动）

- [ ] **Step 2: 启动一个 mock external ws client（订阅 `stream:<source>` channel）**

使用已有工具（参考 `benchmark/mocks/` 或自写一个简单 Python ws client）连接 `ws://localhost:<port>/ws/external/...`；记录收到的每条 JSON。

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
- [x] `./mvnw -pl skill-server test` 全绿
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
