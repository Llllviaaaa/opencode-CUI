# External WebSocket Channel Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a generic WebSocket outbound channel + unified REST inbound endpoint so external modules (IM, CRM, etc.) can receive StreamMessage pushes and send chat/question_reply/permission_reply/rebuild actions — zero code change per new module.

**Architecture:** Strategy pattern replaces the hardcoded miniapp/IM if-else in GatewayMessageRouter. An OutboundDeliveryDispatcher holds ordered strategies (Miniapp, ExternalWs, ImRest). ExternalStreamHandler manages service-level WS connections pooled by source+instanceId, with dynamic Redis pub/sub per domain. A new InboundProcessingService extracts shared chat logic from ImInboundController, reused by the new ExternalInboundController.

**Tech Stack:** Spring Boot 3.4, Java 21, Spring WebSocket (TextWebSocketHandler), Redis pub/sub (Lettuce), Jackson, Lombok, JUnit 5 + Mockito

**Spec:** `docs/superpowers/specs/2026-04-13-external-ws-channel-design.md`

---

## File Structure

### New files (all under `skill-server/src/main/java/com/opencode/cui/skill/`)

| File | Responsibility |
|------|----------------|
| `model/ExternalInvokeRequest.java` | DTO: fixed envelope (action, businessDomain, etc.) + JsonNode payload |
| `service/delivery/OutboundDeliveryStrategy.java` | Interface: `supports()`, `order()`, `deliver()` |
| `service/delivery/OutboundDeliveryDispatcher.java` | Collects all Strategy beans, sorts by order, dispatches to first match |
| `service/delivery/MiniappDeliveryStrategy.java` | Miniapp: publishes to `user-stream:{userId}` via existing RedisMessageBroker |
| `service/delivery/ExternalWsDeliveryStrategy.java` | External: publishes to `stream:{domain}` when ExternalStreamHandler has connections |
| `service/delivery/ImRestDeliveryStrategy.java` | IM REST fallback: `buildImText()` → `ImOutboundService.sendTextToIm()` |
| `ws/ExternalStreamHandler.java` | Service-level WS gateway: connection pool, heartbeat, Redis subscribe per source |
| `service/InboundProcessingService.java` | Shared inbound logic: processChat, processQuestionReply, processPermissionReply, processRebuild |
| `controller/ExternalInboundController.java` | REST `POST /api/external/invoke`, routes by action to InboundProcessingService |

### New test files (all under `skill-server/src/test/java/com/opencode/cui/skill/`)

| File | Tests |
|------|-------|
| `service/delivery/OutboundDeliveryDispatcherTest.java` | Strategy ordering, dispatch, fallback |
| `service/delivery/MiniappDeliveryStrategyTest.java` | supports() for miniapp domain, deliver() calls publishToUser |
| `service/delivery/ExternalWsDeliveryStrategyTest.java` | supports() checks connection existence, deliver() calls publishToChannel |
| `service/delivery/ImRestDeliveryStrategyTest.java` | supports() for IM domain, deliver() calls sendTextToIm with built text |
| `ws/ExternalStreamHandlerTest.java` | Handshake auth, connection pool, heartbeat, push |
| `controller/ExternalInboundControllerTest.java` | Envelope validation, action routing, all 4 actions |
| `service/InboundProcessingServiceTest.java` | processChat (3 session states), processQuestionReply, processPermissionReply, processRebuild |

### Modified files

| File | Change |
|------|--------|
| `service/RedisMessageBroker.java` | Add `publishToChannel()`, `subscribeToChannel()`, `unsubscribeFromChannel()` |
| `service/GatewayMessageRouter.java` | Replace 6 if/else sites with `outboundDeliveryDispatcher.deliver()` |
| `controller/ImInboundController.java` | Delegate chat logic to `InboundProcessingService.processChat()` |
| `config/WebMvcConfig.java` | Add `/api/external/**` to ImTokenAuthInterceptor paths |
| `config/SkillConfig.java` | Register `/ws/external/stream` WS endpoint |

---

### Task 1: OutboundDeliveryStrategy interface + Dispatcher

**Files:**
- Create: `skill-server/src/main/java/com/opencode/cui/skill/service/delivery/OutboundDeliveryStrategy.java`
- Create: `skill-server/src/main/java/com/opencode/cui/skill/service/delivery/OutboundDeliveryDispatcher.java`
- Test: `skill-server/src/test/java/com/opencode/cui/skill/service/delivery/OutboundDeliveryDispatcherTest.java`

- [ ] **Step 1: Write the OutboundDeliveryStrategy interface**

```java
package com.opencode.cui.skill.service.delivery;

import com.opencode.cui.skill.model.SkillSession;
import com.opencode.cui.skill.model.StreamMessage;

/**
 * 出站投递策略接口。
 * 按 order 优先级匹配，第一个 supports 的策略执行 deliver。
 */
public interface OutboundDeliveryStrategy {

    /**
     * 是否支持该 session 的出站投递。
     *
     * @param session 会话对象（可为 null）
     * @return true 表示此策略可处理
     */
    boolean supports(SkillSession session);

    /**
     * 投递优先级，数值小 = 优先匹配。
     */
    int order();

    /**
     * 执行投递。
     *
     * @param session   会话对象
     * @param sessionId 会话 ID 字符串
     * @param userId    用户 ID（可为 null）
     * @param msg       StreamMessage 消息
     */
    void deliver(SkillSession session, String sessionId, String userId, StreamMessage msg);
}
```

- [ ] **Step 2: Write the OutboundDeliveryDispatcher**

```java
package com.opencode.cui.skill.service.delivery;

import com.opencode.cui.skill.model.SkillSession;
import com.opencode.cui.skill.model.StreamMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/**
 * 出站投递调度器。
 * 按 order 排序所有策略，匹配第一个 supports 的策略执行。
 */
@Slf4j
@Component
public class OutboundDeliveryDispatcher {

    private final List<OutboundDeliveryStrategy> strategies;

    public OutboundDeliveryDispatcher(List<OutboundDeliveryStrategy> strategies) {
        this.strategies = strategies.stream()
                .sorted(Comparator.comparingInt(OutboundDeliveryStrategy::order))
                .toList();
        log.info("OutboundDeliveryDispatcher initialized with {} strategies: {}",
                this.strategies.size(),
                this.strategies.stream()
                        .map(s -> s.getClass().getSimpleName() + "(order=" + s.order() + ")")
                        .toList());
    }

    /**
     * 投递消息到匹配的策略。
     */
    public void deliver(SkillSession session, String sessionId, String userId, StreamMessage msg) {
        for (OutboundDeliveryStrategy strategy : strategies) {
            if (strategy.supports(session)) {
                log.debug("Delivering via {}: sessionId={}, type={}",
                        strategy.getClass().getSimpleName(), sessionId,
                        msg != null ? msg.getType() : null);
                strategy.deliver(session, sessionId, userId, msg);
                return;
            }
        }
        log.warn("No delivery strategy matched: sessionId={}, domain={}",
                sessionId, session != null ? session.getBusinessSessionDomain() : "null");
    }
}
```

- [ ] **Step 3: Write the Dispatcher test**

```java
package com.opencode.cui.skill.service.delivery;

import com.opencode.cui.skill.model.SkillSession;
import com.opencode.cui.skill.model.StreamMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OutboundDeliveryDispatcherTest {

    @Test
    @DisplayName("dispatches to first matching strategy by order")
    void dispatchesToFirstMatch() {
        OutboundDeliveryStrategy s1 = mock(OutboundDeliveryStrategy.class);
        when(s1.order()).thenReturn(1);
        when(s1.supports(any())).thenReturn(false);

        OutboundDeliveryStrategy s2 = mock(OutboundDeliveryStrategy.class);
        when(s2.order()).thenReturn(2);
        when(s2.supports(any())).thenReturn(true);

        OutboundDeliveryStrategy s3 = mock(OutboundDeliveryStrategy.class);
        when(s3.order()).thenReturn(3);

        OutboundDeliveryDispatcher dispatcher = new OutboundDeliveryDispatcher(List.of(s3, s1, s2));

        SkillSession session = SkillSession.builder().build();
        StreamMessage msg = StreamMessage.builder().type("delta").build();
        dispatcher.deliver(session, "123", "user1", msg);

        verify(s2).deliver(session, "123", "user1", msg);
        verify(s1, never()).deliver(any(), any(), any(), any());
        verify(s3, never()).deliver(any(), any(), any(), any());
    }

    @Test
    @DisplayName("logs warning when no strategy matches")
    void noMatchDoesNotThrow() {
        OutboundDeliveryStrategy s1 = mock(OutboundDeliveryStrategy.class);
        when(s1.order()).thenReturn(1);
        when(s1.supports(any())).thenReturn(false);

        OutboundDeliveryDispatcher dispatcher = new OutboundDeliveryDispatcher(List.of(s1));

        SkillSession session = SkillSession.builder()
                .businessSessionDomain("unknown").build();
        StreamMessage msg = StreamMessage.builder().type("delta").build();
        dispatcher.deliver(session, "123", "user1", msg);

        verify(s1, never()).deliver(any(), any(), any(), any());
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd skill-server && mvn test -pl . -Dtest="OutboundDeliveryDispatcherTest" -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add skill-server/src/main/java/com/opencode/cui/skill/service/delivery/OutboundDeliveryStrategy.java skill-server/src/main/java/com/opencode/cui/skill/service/delivery/OutboundDeliveryDispatcher.java skill-server/src/test/java/com/opencode/cui/skill/service/delivery/OutboundDeliveryDispatcherTest.java
git commit -m "feat(delivery): add OutboundDeliveryStrategy interface + Dispatcher"
```

---

### Task 2: RedisMessageBroker — add generic channel pub/sub

**Files:**
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/service/RedisMessageBroker.java`

- [ ] **Step 1: Add publishToChannel, subscribeToChannel, unsubscribeFromChannel methods**

Add these methods after the existing `unsubscribeFromUser` method (after line 86):

```java
/**
 * Publish a message to a named channel.
 *
 * @param channel the full channel name (e.g. "stream:im")
 * @param message the message to publish (as JSON string)
 */
public void publishToChannel(String channel, String message) {
    publishMessage(channel, message);
}

/**
 * Subscribe to a named channel.
 *
 * @param channel the full channel name
 * @param handler callback to handle received messages
 */
public void subscribeToChannel(String channel, Consumer<String> handler) {
    subscribe(channel, handler);
}

/**
 * Unsubscribe from a named channel.
 *
 * @param channel the full channel name
 */
public void unsubscribeFromChannel(String channel) {
    unsubscribe(channel);
}

/**
 * Check if a channel is currently subscribed.
 *
 * @param channel the full channel name
 * @return true if subscribed
 */
public boolean isChannelSubscribed(String channel) {
    return activeListeners.containsKey(channel);
}
```

- [ ] **Step 2: Commit**

```bash
git add skill-server/src/main/java/com/opencode/cui/skill/service/RedisMessageBroker.java
git commit -m "feat(redis): add generic channel pub/sub to RedisMessageBroker"
```

---

### Task 3: MiniappDeliveryStrategy

**Files:**
- Create: `skill-server/src/main/java/com/opencode/cui/skill/service/delivery/MiniappDeliveryStrategy.java`
- Test: `skill-server/src/test/java/com/opencode/cui/skill/service/delivery/MiniappDeliveryStrategyTest.java`

- [ ] **Step 1: Write the test**

```java
package com.opencode.cui.skill.service.delivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.model.SkillSession;
import com.opencode.cui.skill.model.StreamMessage;
import com.opencode.cui.skill.service.RedisMessageBroker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MiniappDeliveryStrategyTest {

    @Mock
    private RedisMessageBroker redisMessageBroker;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private MiniappDeliveryStrategy strategy;

    @Test
    @DisplayName("supports miniapp domain session")
    void supportsMiniapp() {
        SkillSession session = SkillSession.builder()
                .businessSessionDomain("miniapp").build();
        assertTrue(strategy.supports(session));
    }

    @Test
    @DisplayName("supports null session (treated as miniapp)")
    void supportsNullSession() {
        assertTrue(strategy.supports(null));
    }

    @Test
    @DisplayName("does not support IM domain session")
    void doesNotSupportIm() {
        SkillSession session = SkillSession.builder()
                .businessSessionDomain("im").build();
        assertFalse(strategy.supports(session));
    }

    @Test
    @DisplayName("delivers to user-stream:{userId} via Redis")
    void deliversToUserStream() {
        SkillSession session = SkillSession.builder()
                .businessSessionDomain("miniapp").build();
        StreamMessage msg = StreamMessage.builder()
                .type("delta").content("hello").build();

        strategy.deliver(session, "sess-1", "user-42", msg);

        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        verify(redisMessageBroker).publishToUser(eq("user-42"), messageCaptor.capture());
        String json = messageCaptor.getValue();
        assertTrue(json.contains("\"userId\":\"user-42\""));
        assertTrue(json.contains("\"sessionId\":\"sess-1\""));
    }

    @Test
    @DisplayName("order is 1")
    void orderIsOne() {
        assertEquals(1, strategy.order());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd skill-server && mvn test -pl . -Dtest="MiniappDeliveryStrategyTest" -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — `MiniappDeliveryStrategy` does not exist yet

- [ ] **Step 3: Write the implementation**

```java
package com.opencode.cui.skill.service.delivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.opencode.cui.skill.model.SkillSession;
import com.opencode.cui.skill.model.StreamMessage;
import com.opencode.cui.skill.service.RedisMessageBroker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * MiniApp 出站投递策略。
 * 通过 Redis user-stream:{userId} 频道将 StreamMessage 推送到 SkillStreamHandler。
 */
@Slf4j
@Component
public class MiniappDeliveryStrategy implements OutboundDeliveryStrategy {

    private final RedisMessageBroker redisMessageBroker;
    private final ObjectMapper objectMapper;

    public MiniappDeliveryStrategy(RedisMessageBroker redisMessageBroker,
                                    ObjectMapper objectMapper) {
        this.redisMessageBroker = redisMessageBroker;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(SkillSession session) {
        return session == null || session.isMiniappDomain();
    }

    @Override
    public int order() {
        return 1;
    }

    @Override
    public void deliver(SkillSession session, String sessionId, String userId, StreamMessage msg) {
        if (userId == null || userId.isBlank()) {
            log.warn("Cannot deliver miniapp message without userId: sessionId={}", sessionId);
            return;
        }
        try {
            ObjectNode envelope = objectMapper.createObjectNode();
            envelope.put("sessionId", sessionId);
            envelope.put("userId", userId);
            envelope.set("message", objectMapper.valueToTree(msg));
            redisMessageBroker.publishToUser(userId, objectMapper.writeValueAsString(envelope));
            log.info("[DELIVERY] Miniapp: sessionId={}, type={}, userId={}",
                    sessionId, msg != null ? msg.getType() : null, userId);
        } catch (Exception e) {
            log.error("Failed to deliver miniapp message: sessionId={}, error={}",
                    sessionId, e.getMessage());
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd skill-server && mvn test -pl . -Dtest="MiniappDeliveryStrategyTest" -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add skill-server/src/main/java/com/opencode/cui/skill/service/delivery/MiniappDeliveryStrategy.java skill-server/src/test/java/com/opencode/cui/skill/service/delivery/MiniappDeliveryStrategyTest.java
git commit -m "feat(delivery): add MiniappDeliveryStrategy"
```

---

### Task 4: ExternalStreamHandler (WS gateway + connection pool + heartbeat)

**Files:**
- Create: `skill-server/src/main/java/com/opencode/cui/skill/ws/ExternalStreamHandler.java`
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/config/SkillConfig.java`
- Test: `skill-server/src/test/java/com/opencode/cui/skill/ws/ExternalStreamHandlerTest.java`

- [ ] **Step 1: Write the test**

```java
package com.opencode.cui.skill.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.service.RedisMessageBroker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ExternalStreamHandlerTest {

    @Mock private RedisMessageBroker redisMessageBroker;
    @Mock private ServerHttpRequest request;
    @Mock private ServerHttpResponse response;
    @Mock private WebSocketHandler wsHandler;
    @Mock private WebSocketSession wsSession;

    private ObjectMapper objectMapper = new ObjectMapper();
    private ExternalStreamHandler handler;

    @BeforeEach
    void setUp() {
        handler = new ExternalStreamHandler(objectMapper, redisMessageBroker, "test-token");
    }

    private String buildAuthProtocol(String token, String source, String instanceId) {
        String json = "{\"token\":\"" + token + "\",\"source\":\"" + source
                + "\",\"instanceId\":\"" + instanceId + "\"}";
        return "auth." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("valid handshake registers connection")
    void validHandshakeAccepted() {
        HttpHeaders headers = new HttpHeaders();
        headers.put("Sec-WebSocket-Protocol", List.of(buildAuthProtocol("test-token", "im", "im-1")));
        when(request.getHeaders()).thenReturn(headers);
        HttpHeaders respHeaders = new HttpHeaders();
        when(response.getHeaders()).thenReturn(respHeaders);
        Map<String, Object> attrs = new HashMap<>();

        boolean result = handler.beforeHandshake(request, response, wsHandler, attrs);

        assertTrue(result);
        assertEquals("im", attrs.get("source"));
        assertEquals("im-1", attrs.get("instanceId"));
    }

    @Test
    @DisplayName("invalid token rejects handshake")
    void invalidTokenRejected() {
        HttpHeaders headers = new HttpHeaders();
        headers.put("Sec-WebSocket-Protocol", List.of(buildAuthProtocol("wrong-token", "im", "im-1")));
        when(request.getHeaders()).thenReturn(headers);
        Map<String, Object> attrs = new HashMap<>();

        boolean result = handler.beforeHandshake(request, response, wsHandler, attrs);

        assertFalse(result);
    }

    @Test
    @DisplayName("hasActiveConnections returns true after connection established")
    void hasActiveConnectionsAfterConnect() throws Exception {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("source", "im");
        attrs.put("instanceId", "im-1");
        when(wsSession.getAttributes()).thenReturn(attrs);
        when(wsSession.isOpen()).thenReturn(true);

        handler.afterConnectionEstablished(wsSession);

        assertTrue(handler.hasActiveConnections("im"));
        assertFalse(handler.hasActiveConnections("crm"));
    }

    @Test
    @DisplayName("hasActiveConnections returns false after connection closed")
    void noActiveConnectionsAfterClose() throws Exception {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("source", "im");
        attrs.put("instanceId", "im-1");
        when(wsSession.getAttributes()).thenReturn(attrs);
        when(wsSession.isOpen()).thenReturn(true);

        handler.afterConnectionEstablished(wsSession);
        handler.afterConnectionClosed(wsSession, CloseStatus.NORMAL);

        assertFalse(handler.hasActiveConnections("im"));
    }

    @Test
    @DisplayName("ping message receives pong reply")
    void pingReceivesPong() throws Exception {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("source", "im");
        attrs.put("instanceId", "im-1");
        when(wsSession.getAttributes()).thenReturn(attrs);
        when(wsSession.isOpen()).thenReturn(true);

        handler.handleTextMessage(wsSession, new TextMessage("{\"action\":\"ping\"}"));

        verify(wsSession).sendMessage(any(TextMessage.class));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd skill-server && mvn test -pl . -Dtest="ExternalStreamHandlerTest" -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — class does not exist

- [ ] **Step 3: Write ExternalStreamHandler**

```java
package com.opencode.cui.skill.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.service.RedisMessageBroker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 通用服务级 WebSocket 网关。
 * 外部业务模块（IM、CRM 等）连接此端点接收 StreamMessage 推送。
 *
 * <p>握手认证：Sec-WebSocket-Protocol 子协议 {@code auth.{base64json}}，
 * JSON 包含 token、source、instanceId。</p>
 *
 * <p>连接池结构：{@code source → { instanceId → WebSocketSession }}</p>
 */
@Slf4j
@Component
public class ExternalStreamHandler extends TextWebSocketHandler implements HandshakeInterceptor {

    private static final String AUTH_PROTOCOL_PREFIX = "auth.";
    private static final String SOURCE_ATTR = "source";
    private static final String INSTANCE_ID_ATTR = "instanceId";
    private static final String CHANNEL_PREFIX = "stream:";

    private final ObjectMapper objectMapper;
    private final RedisMessageBroker redisMessageBroker;
    private final String inboundToken;

    /** source → { instanceId → WebSocketSession } */
    private final Map<String, Map<String, WebSocketSession>> connectionPool = new ConcurrentHashMap<>();

    /** sessionId → last activity time (for heartbeat timeout) */
    private final Map<String, Instant> lastActivity = new ConcurrentHashMap<>();

    public ExternalStreamHandler(ObjectMapper objectMapper,
                                  RedisMessageBroker redisMessageBroker,
                                  @Value("${skill.im.inbound-token:changeme}") String inboundToken) {
        this.objectMapper = objectMapper;
        this.redisMessageBroker = redisMessageBroker;
        this.inboundToken = inboundToken;
    }

    // ==================== Handshake ====================

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                    WebSocketHandler wsHandler, Map<String, Object> attributes) {
        HandshakeAuth auth = extractAuth(request);
        if (auth == null) {
            log.warn("Rejected external handshake: invalid auth subprotocol");
            return false;
        }
        attributes.put(SOURCE_ATTR, auth.source());
        attributes.put(INSTANCE_ID_ATTR, auth.instanceId());
        response.getHeaders().set("Sec-WebSocket-Protocol", auth.protocol());
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                WebSocketHandler wsHandler, Exception exception) {
        // no-op
    }

    // ==================== Connection lifecycle ====================

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String source = (String) session.getAttributes().get(SOURCE_ATTR);
        String instanceId = (String) session.getAttributes().get(INSTANCE_ID_ATTR);

        connectionPool.computeIfAbsent(source, k -> new ConcurrentHashMap<>())
                .put(instanceId, session);
        lastActivity.put(session.getId(), Instant.now());

        // 动态订阅该 source 的 Redis 频道（首个连接时）
        String channel = CHANNEL_PREFIX + source;
        if (!redisMessageBroker.isChannelSubscribed(channel)) {
            redisMessageBroker.subscribeToChannel(channel, msg -> handleRedisMessage(source, msg));
        }

        log.info("External WS connected: source={}, instanceId={}, sessionId={}",
                source, instanceId, session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage textMessage) throws Exception {
        lastActivity.put(session.getId(), Instant.now());

        String payload = textMessage.getPayload();
        try {
            var node = objectMapper.readTree(payload);
            String action = node.path("action").asText("");
            if ("ping".equals(action)) {
                session.sendMessage(new TextMessage("{\"action\":\"pong\"}"));
            }
        } catch (Exception e) {
            log.warn("Invalid message from external WS: sessionId={}, error={}",
                    session.getId(), e.getMessage());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String source = (String) session.getAttributes().get(SOURCE_ATTR);
        String instanceId = (String) session.getAttributes().get(INSTANCE_ID_ATTR);

        Map<String, WebSocketSession> instances = connectionPool.get(source);
        if (instances != null) {
            instances.remove(instanceId);
            if (instances.isEmpty()) {
                connectionPool.remove(source);
                // 最后一个连接断开，取消 Redis 订阅
                redisMessageBroker.unsubscribeFromChannel(CHANNEL_PREFIX + source);
            }
        }
        lastActivity.remove(session.getId());

        log.info("External WS disconnected: source={}, instanceId={}, status={}",
                source, instanceId, status);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        log.error("External WS transport error: sessionId={}, error={}",
                session.getId(), exception.getMessage());
        afterConnectionClosed(session, CloseStatus.SERVER_ERROR);
    }

    // ==================== Public API ====================

    /**
     * 检查指定 source 是否有活跃的 WS 连接。
     */
    public boolean hasActiveConnections(String source) {
        Map<String, WebSocketSession> instances = connectionPool.get(source);
        if (instances == null || instances.isEmpty()) {
            return false;
        }
        return instances.values().stream().anyMatch(WebSocketSession::isOpen);
    }

    /**
     * 将消息推送到指定 source 的所有连接。
     */
    public void pushToSource(String source, String message) {
        Map<String, WebSocketSession> instances = connectionPool.get(source);
        if (instances == null || instances.isEmpty()) {
            log.warn("No active connections for source: {}", source);
            return;
        }
        TextMessage textMessage = new TextMessage(message);
        for (Map.Entry<String, WebSocketSession> entry : instances.entrySet()) {
            WebSocketSession session = entry.getValue();
            if (session.isOpen()) {
                try {
                    synchronized (session) {
                        session.sendMessage(textMessage);
                    }
                } catch (Exception e) {
                    log.error("Failed to push to external WS: source={}, instanceId={}, error={}",
                            source, entry.getKey(), e.getMessage());
                }
            }
        }
    }

    // ==================== Heartbeat timeout ====================

    /**
     * 每 30 秒检查一次超时连接（60 秒无活动则断开）。
     */
    @Scheduled(fixedRate = 30_000)
    public void checkHeartbeatTimeouts() {
        Instant timeout = Instant.now().minusSeconds(60);
        for (Map<String, WebSocketSession> instances : connectionPool.values()) {
            for (WebSocketSession session : instances.values()) {
                Instant last = lastActivity.get(session.getId());
                if (last != null && last.isBefore(timeout) && session.isOpen()) {
                    try {
                        log.warn("Closing external WS due to heartbeat timeout: sessionId={}",
                                session.getId());
                        session.close(CloseStatus.GOING_AWAY);
                    } catch (Exception e) {
                        log.error("Failed to close timed-out session: {}", e.getMessage());
                    }
                }
            }
        }
    }

    // ==================== Internal ====================

    /** 处理从 Redis 频道收到的消息，广播到对应 source 的所有 WS 连接。 */
    private void handleRedisMessage(String source, String message) {
        pushToSource(source, message);
    }

    /** 从握手请求头中提取认证信息。 */
    private HandshakeAuth extractAuth(ServerHttpRequest request) {
        List<String> protocols = request.getHeaders().get("Sec-WebSocket-Protocol");
        if (protocols == null || protocols.isEmpty()) {
            return null;
        }
        for (String protocolHeader : protocols) {
            for (String candidate : protocolHeader.split(",")) {
                String protocol = candidate.trim();
                if (protocol.startsWith(AUTH_PROTOCOL_PREFIX)) {
                    HandshakeAuth auth = verifyToken(protocol);
                    if (auth != null) {
                        return auth;
                    }
                }
            }
        }
        return null;
    }

    /** 解码并验证 Base64 认证令牌。 */
    private HandshakeAuth verifyToken(String protocol) {
        String encoded = protocol.substring(AUTH_PROTOCOL_PREFIX.length());
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(encoded);
            String json = new String(decoded, StandardCharsets.UTF_8);
            var authNode = objectMapper.readTree(json);
            String token = authNode.path("token").asText(null);
            String source = authNode.path("source").asText(null);
            String instanceId = authNode.path("instanceId").asText(null);
            if (!inboundToken.equals(token) || source == null || source.isBlank()
                    || instanceId == null || instanceId.isBlank()) {
                return null;
            }
            return new HandshakeAuth(protocol, source, instanceId);
        } catch (Exception e) {
            log.warn("Failed to decode external auth subprotocol: {}", e.getMessage());
            return null;
        }
    }

    private record HandshakeAuth(String protocol, String source, String instanceId) {
    }
}
```

- [ ] **Step 4: Register the WS endpoint in SkillConfig**

Modify `skill-server/src/main/java/com/opencode/cui/skill/config/SkillConfig.java`. Add the ExternalStreamHandler field and register the endpoint:

```java
package com.opencode.cui.skill.config;

import com.opencode.cui.skill.ws.ExternalStreamHandler;
import com.opencode.cui.skill.ws.SkillStreamHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
@EnableScheduling
public class SkillConfig implements WebSocketConfigurer {

    private final SkillStreamHandler skillStreamHandler;
    private final ExternalStreamHandler externalStreamHandler;

    public SkillConfig(SkillStreamHandler skillStreamHandler,
                       ExternalStreamHandler externalStreamHandler) {
        this.skillStreamHandler = skillStreamHandler;
        this.externalStreamHandler = externalStreamHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(skillStreamHandler, "/ws/skill/stream")
                .setAllowedOrigins("*");
        registry.addHandler(externalStreamHandler, "/ws/external/stream")
                .addInterceptors(externalStreamHandler)
                .setAllowedOrigins("*");
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `cd skill-server && mvn test -pl . -Dtest="ExternalStreamHandlerTest" -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add skill-server/src/main/java/com/opencode/cui/skill/ws/ExternalStreamHandler.java skill-server/src/main/java/com/opencode/cui/skill/config/SkillConfig.java skill-server/src/test/java/com/opencode/cui/skill/ws/ExternalStreamHandlerTest.java
git commit -m "feat(ws): add ExternalStreamHandler with connection pool and heartbeat"
```

---

### Task 5: ExternalWsDeliveryStrategy + ImRestDeliveryStrategy

**Files:**
- Create: `skill-server/src/main/java/com/opencode/cui/skill/service/delivery/ExternalWsDeliveryStrategy.java`
- Create: `skill-server/src/main/java/com/opencode/cui/skill/service/delivery/ImRestDeliveryStrategy.java`
- Test: `skill-server/src/test/java/com/opencode/cui/skill/service/delivery/ExternalWsDeliveryStrategyTest.java`
- Test: `skill-server/src/test/java/com/opencode/cui/skill/service/delivery/ImRestDeliveryStrategyTest.java`

- [ ] **Step 1: Write ExternalWsDeliveryStrategy test**

```java
package com.opencode.cui.skill.service.delivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.model.SkillSession;
import com.opencode.cui.skill.model.StreamMessage;
import com.opencode.cui.skill.service.RedisMessageBroker;
import com.opencode.cui.skill.ws.ExternalStreamHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ExternalWsDeliveryStrategyTest {

    @Mock private ExternalStreamHandler externalStreamHandler;
    @Mock private RedisMessageBroker redisMessageBroker;
    @Spy private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private ExternalWsDeliveryStrategy strategy;

    @Test
    @DisplayName("supports non-miniapp domain with active WS connections")
    void supportsWithActiveConnections() {
        SkillSession session = SkillSession.builder()
                .businessSessionDomain("im").build();
        when(externalStreamHandler.hasActiveConnections("im")).thenReturn(true);

        assertTrue(strategy.supports(session));
    }

    @Test
    @DisplayName("does not support when no WS connections")
    void doesNotSupportWithoutConnections() {
        SkillSession session = SkillSession.builder()
                .businessSessionDomain("im").build();
        when(externalStreamHandler.hasActiveConnections("im")).thenReturn(false);

        assertFalse(strategy.supports(session));
    }

    @Test
    @DisplayName("does not support miniapp domain")
    void doesNotSupportMiniapp() {
        SkillSession session = SkillSession.builder()
                .businessSessionDomain("miniapp").build();

        assertFalse(strategy.supports(session));
    }

    @Test
    @DisplayName("does not support null session")
    void doesNotSupportNull() {
        assertFalse(strategy.supports(null));
    }

    @Test
    @DisplayName("delivers to stream:{domain} via Redis")
    void deliversToStreamChannel() {
        SkillSession session = SkillSession.builder()
                .businessSessionDomain("im").build();
        StreamMessage msg = StreamMessage.builder()
                .type("delta").content("hello").build();

        strategy.deliver(session, "sess-1", "user-42", msg);

        verify(redisMessageBroker).publishToChannel(eq("stream:im"), any(String.class));
    }

    @Test
    @DisplayName("order is 2")
    void orderIsTwo() {
        assertEquals(2, strategy.order());
    }
}
```

- [ ] **Step 2: Write ImRestDeliveryStrategy test**

```java
package com.opencode.cui.skill.service.delivery;

import com.opencode.cui.skill.model.SkillSession;
import com.opencode.cui.skill.model.StreamMessage;
import com.opencode.cui.skill.service.ImOutboundService;
import com.opencode.cui.skill.ws.ExternalStreamHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ImRestDeliveryStrategyTest {

    @Mock private ImOutboundService imOutboundService;
    @Mock private ExternalStreamHandler externalStreamHandler;

    @InjectMocks
    private ImRestDeliveryStrategy strategy;

    @Test
    @DisplayName("supports IM domain when no active WS connections")
    void supportsImWithoutWs() {
        SkillSession session = SkillSession.builder()
                .businessSessionDomain("im").build();
        when(externalStreamHandler.hasActiveConnections("im")).thenReturn(false);

        assertTrue(strategy.supports(session));
    }

    @Test
    @DisplayName("does not support IM domain when WS connections exist")
    void doesNotSupportImWithWs() {
        SkillSession session = SkillSession.builder()
                .businessSessionDomain("im").build();
        when(externalStreamHandler.hasActiveConnections("im")).thenReturn(true);

        assertFalse(strategy.supports(session));
    }

    @Test
    @DisplayName("does not support miniapp domain")
    void doesNotSupportMiniapp() {
        SkillSession session = SkillSession.builder()
                .businessSessionDomain("miniapp").build();

        assertFalse(strategy.supports(session));
    }

    @Test
    @DisplayName("delivers text_done content via sendTextToIm")
    void deliversTextDone() {
        SkillSession session = SkillSession.builder()
                .businessSessionDomain("im")
                .businessSessionType("direct")
                .businessSessionId("dm-001")
                .assistantAccount("assist-01")
                .build();
        StreamMessage msg = StreamMessage.builder()
                .type(StreamMessage.Types.TEXT_DONE)
                .content("Hello from agent")
                .build();

        strategy.deliver(session, "sess-1", "user-42", msg);

        verify(imOutboundService).sendTextToIm("direct", "dm-001", "Hello from agent", "assist-01");
    }

    @Test
    @DisplayName("does not call sendTextToIm for non-text types")
    void ignoresNonTextTypes() {
        SkillSession session = SkillSession.builder()
                .businessSessionDomain("im")
                .businessSessionType("direct")
                .businessSessionId("dm-001")
                .assistantAccount("assist-01")
                .build();
        StreamMessage msg = StreamMessage.builder()
                .type(StreamMessage.Types.DELTA)
                .content("partial")
                .build();

        strategy.deliver(session, "sess-1", "user-42", msg);

        verify(imOutboundService, never()).sendTextToIm(any(), any(), any(), any());
    }

    @Test
    @DisplayName("order is 3")
    void orderIsThree() {
        assertEquals(3, strategy.order());
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `cd skill-server && mvn test -pl . -Dtest="ExternalWsDeliveryStrategyTest,ImRestDeliveryStrategyTest" -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL

- [ ] **Step 4: Write ExternalWsDeliveryStrategy**

```java
package com.opencode.cui.skill.service.delivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.opencode.cui.skill.model.SkillSession;
import com.opencode.cui.skill.model.StreamMessage;
import com.opencode.cui.skill.service.RedisMessageBroker;
import com.opencode.cui.skill.ws.ExternalStreamHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 通用外部 WS 出站投递策略。
 * 当指定 domain 有活跃 WS 连接时，通过 Redis stream:{domain} 频道推送 StreamMessage。
 */
@Slf4j
@Component
public class ExternalWsDeliveryStrategy implements OutboundDeliveryStrategy {

    private final ExternalStreamHandler externalStreamHandler;
    private final RedisMessageBroker redisMessageBroker;
    private final ObjectMapper objectMapper;

    public ExternalWsDeliveryStrategy(ExternalStreamHandler externalStreamHandler,
                                       RedisMessageBroker redisMessageBroker,
                                       ObjectMapper objectMapper) {
        this.externalStreamHandler = externalStreamHandler;
        this.redisMessageBroker = redisMessageBroker;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(SkillSession session) {
        if (session == null || session.isMiniappDomain()) {
            return false;
        }
        return externalStreamHandler.hasActiveConnections(session.getBusinessSessionDomain());
    }

    @Override
    public int order() {
        return 2;
    }

    @Override
    public void deliver(SkillSession session, String sessionId, String userId, StreamMessage msg) {
        String domain = session.getBusinessSessionDomain();
        try {
            ObjectNode envelope = objectMapper.createObjectNode();
            envelope.put("sessionId", sessionId);
            if (userId != null) {
                envelope.put("userId", userId);
            }
            envelope.put("domain", domain);
            envelope.set("message", objectMapper.valueToTree(msg));
            String channel = "stream:" + domain;
            redisMessageBroker.publishToChannel(channel, objectMapper.writeValueAsString(envelope));
            log.info("[DELIVERY] ExternalWs: sessionId={}, type={}, domain={}",
                    sessionId, msg != null ? msg.getType() : null, domain);
        } catch (Exception e) {
            log.error("Failed to deliver external WS message: sessionId={}, domain={}, error={}",
                    sessionId, domain, e.getMessage());
        }
    }
}
```

- [ ] **Step 5: Write ImRestDeliveryStrategy**

```java
package com.opencode.cui.skill.service.delivery;

import com.opencode.cui.skill.model.SkillSession;
import com.opencode.cui.skill.model.StreamMessage;
import com.opencode.cui.skill.service.ImOutboundService;
import com.opencode.cui.skill.ws.ExternalStreamHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * IM REST 兜底出站投递策略。
 * 当 IM 没有活跃 WS 连接时，通过 REST API 推送纯文本。
 */
@Slf4j
@Component
public class ImRestDeliveryStrategy implements OutboundDeliveryStrategy {

    private final ImOutboundService imOutboundService;
    private final ExternalStreamHandler externalStreamHandler;

    public ImRestDeliveryStrategy(ImOutboundService imOutboundService,
                                   ExternalStreamHandler externalStreamHandler) {
        this.imOutboundService = imOutboundService;
        this.externalStreamHandler = externalStreamHandler;
    }

    @Override
    public boolean supports(SkillSession session) {
        if (session == null || session.isMiniappDomain()) {
            return false;
        }
        // 仅 IM 域有 REST 兜底
        if (!session.isImDomain()) {
            return false;
        }
        // 如果有活跃 WS 连接，让 ExternalWsDeliveryStrategy 处理
        return !externalStreamHandler.hasActiveConnections(session.getBusinessSessionDomain());
    }

    @Override
    public int order() {
        return 3;
    }

    @Override
    public void deliver(SkillSession session, String sessionId, String userId, StreamMessage msg) {
        String text = buildImText(msg);
        if (text != null && !text.isBlank()) {
            imOutboundService.sendTextToIm(
                    session.getBusinessSessionType(),
                    session.getBusinessSessionId(),
                    text,
                    session.getAssistantAccount());
            log.info("[DELIVERY] ImRest: sessionId={}, type={}", sessionId,
                    msg != null ? msg.getType() : null);
        }
    }

    /** 将 StreamMessage 转为 IM 纯文本（仅提取有意义的文本类型）。 */
    private String buildImText(StreamMessage msg) {
        if (msg == null) {
            return null;
        }
        return switch (msg.getType()) {
            case StreamMessage.Types.TEXT_DONE -> msg.getContent();
            case StreamMessage.Types.ERROR, StreamMessage.Types.SESSION_ERROR -> msg.getError();
            case StreamMessage.Types.PERMISSION_ASK ->
                    msg.getTitle() != null && !msg.getTitle().isBlank()
                            ? msg.getTitle() + "\n请回复: once / always / reject"
                            : null;
            case StreamMessage.Types.QUESTION -> formatQuestionMessage(msg);
            default -> null;
        };
    }

    /** 格式化提问消息为 IM 文本。 */
    private String formatQuestionMessage(StreamMessage msg) {
        if (msg.getQuestionInfo() == null) {
            return null;
        }
        String status = msg.getStatus();
        if (status != null && !"running".equals(status) && !"pending".equals(status)) {
            return null;
        }
        StringBuilder text = new StringBuilder();
        if (msg.getQuestionInfo().getHeader() != null && !msg.getQuestionInfo().getHeader().isBlank()) {
            text.append(msg.getQuestionInfo().getHeader()).append('\n');
        }
        if (msg.getQuestionInfo().getQuestion() != null) {
            text.append(msg.getQuestionInfo().getQuestion());
        }
        if (msg.getQuestionInfo().getOptions() != null && !msg.getQuestionInfo().getOptions().isEmpty()) {
            text.append('\n');
            for (int i = 0; i < msg.getQuestionInfo().getOptions().size(); i++) {
                text.append(i + 1).append(". ").append(msg.getQuestionInfo().getOptions().get(i)).append('\n');
            }
        }
        return text.isEmpty() ? null : text.toString().trim();
    }
}
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `cd skill-server && mvn test -pl . -Dtest="ExternalWsDeliveryStrategyTest,ImRestDeliveryStrategyTest" -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add skill-server/src/main/java/com/opencode/cui/skill/service/delivery/ExternalWsDeliveryStrategy.java skill-server/src/main/java/com/opencode/cui/skill/service/delivery/ImRestDeliveryStrategy.java skill-server/src/test/java/com/opencode/cui/skill/service/delivery/ExternalWsDeliveryStrategyTest.java skill-server/src/test/java/com/opencode/cui/skill/service/delivery/ImRestDeliveryStrategyTest.java
git commit -m "feat(delivery): add ExternalWsDeliveryStrategy + ImRestDeliveryStrategy"
```

---

### Task 6: Refactor GatewayMessageRouter to use OutboundDeliveryDispatcher

**Files:**
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/service/GatewayMessageRouter.java`

This is the core refactor: replace all `isMiniappSession()` branching + `imOutboundService.sendTextToIm()` calls with `outboundDeliveryDispatcher.deliver()`.

- [ ] **Step 1: Add OutboundDeliveryDispatcher dependency to GatewayMessageRouter constructor**

Add field and constructor parameter. In the constructor (line 133-163), add:

```java
private final OutboundDeliveryDispatcher outboundDeliveryDispatcher;
```

Add `OutboundDeliveryDispatcher outboundDeliveryDispatcher` to constructor parameters and `this.outboundDeliveryDispatcher = outboundDeliveryDispatcher;` in the body. Import `com.opencode.cui.skill.service.delivery.OutboundDeliveryDispatcher`.

- [ ] **Step 2: Refactor routeAssistantMessage (line 503-525)**

Replace the entire method:

```java
/** 路由助手消息：通过策略模式统一投递。 */
private void routeAssistantMessage(String sessionId, String userId, StreamMessage msg,
        SkillSession session, Long numericId) {
    if (StreamMessage.Types.SESSION_TITLE.equals(msg.getType()) && numericId != null) {
        sessionService.updateTitle(numericId, msg.getTitle());
    }

    // 业务助手 IM 场景：过滤云端扩展事件
    if (session != null && !session.isMiniappDomain()) {
        String sessionAk = session.getAk();
        if (sessionAk != null) {
            String sessionScope = assistantInfoService.getCachedScope(sessionAk);
            if ("business".equals(sessionScope) && BUSINESS_IM_FILTERED_TYPES.contains(msg.getType())) {
                log.debug("Filtering business IM extended event: sessionId={}, type={}", session.getId(), msg.getType());
                return;
            }
        }
        syncPendingImInteraction(session, msg);
    }

    // 统一投递
    outboundDeliveryDispatcher.deliver(session, sessionId, userId, msg);

    // 缓冲（miniapp 用）
    if (session == null || session.isMiniappDomain()) {
        bufferService.accumulate(sessionId, msg);
    }

    // 持久化
    if (numericId != null && (session == null || session.isMiniappDomain() || session.isImDirectSession())) {
        try {
            persistenceService.persistIfFinal(numericId, msg);
        } catch (Exception e) {
            log.error("Failed to persist StreamMessage for session {}: {}", sessionId, e.getMessage(), e);
        }
    }
}
```

Remove the old `handleImAssistantMessage` method (lines 527-556) as its logic is now split into `routeAssistantMessage` + `ImRestDeliveryStrategy.buildImText()`.

- [ ] **Step 3: Refactor handleToolDone (line 558-587)**

Replace:

```java
private void handleToolDone(String sessionId, String userId, JsonNode node) {
    if (sessionId == null) {
        log.warn("tool_done missing sessionId, agentId={}", node.path("agentId").asText(null));
        return;
    }
    log.info("handleToolDone: sessionId={}", sessionId);
    completedSessions.put(sessionId, Instant.now());

    StreamMessage msg = StreamMessage.sessionStatus("idle");
    SkillSession session = resolveSession(sessionId);
    Long numericId = ProtocolUtils.parseSessionId(sessionId);

    // 统一投递 idle 状态
    outboundDeliveryDispatcher.deliver(session, sessionId, userId, msg);

    if (session == null || session.isMiniappDomain()) {
        bufferService.accumulate(sessionId, msg);
    }

    if (numericId != null && (isMiniappSession(session) || (session != null && session.isImDirectSession()))) {
        try {
            persistenceService.persistIfFinal(numericId, msg);
        } catch (Exception e) {
            log.error("Failed to persist tool_done for session {}: {}", sessionId, e.getMessage(), e);
        }
    }
    rebuildService.clearPendingMessages(sessionId);
}
```

- [ ] **Step 4: Refactor handleToolError (line 589-645)**

Replace:

```java
private void handleToolError(String sessionId, String userId, JsonNode node) {
    String error = node.path("error").asText("Unknown error");
    String reason = node.path("reason").asText("");
    log.info("handleToolError: sessionId={}, error={}, reason={}", sessionId, error, reason);

    if (sessionId == null) {
        log.error("Tool error without session: {}", error);
        return;
    }

    if ("session_not_found".equals(reason) || isSessionInvalidError(error)) {
        clearPendingImInteractionState(sessionId);
        rebuildService.handleSessionNotFound(sessionId, userId, rebuildCallback());
        return;
    }

    SkillSession session = resolveSession(sessionId);
    Long numericId = ProtocolUtils.parseSessionId(sessionId);

    if (numericId != null) {
        try {
            messageService.saveSystemMessage(numericId, "Error: " + error);
        } catch (Exception e) {
            log.error("Failed to persist tool_error for session {}: {}", sessionId, e.getMessage());
        }
    }

    StreamMessage errorMsg = StreamMessage.builder()
            .type(StreamMessage.Types.ERROR)
            .error(error)
            .build();
    outboundDeliveryDispatcher.deliver(session, sessionId, userId, errorMsg);

    if (numericId != null) {
        try {
            persistenceService.finalizeActiveAssistantTurn(numericId);
        } catch (Exception e) {
            log.warn("Cannot finalize active message after tool_error: sessionId={}", sessionId);
        }
    }
    log.error("Tool error for session {}: {}", sessionId, error);
}
```

- [ ] **Step 5: Refactor handlePermissionRequest (line 753-789)**

Replace:

```java
private void handlePermissionRequest(String sessionId, String userId, JsonNode node) {
    if (sessionId == null) {
        log.warn("permission_request missing sessionId");
        return;
    }
    log.info("handlePermissionRequest: sessionId={}", sessionId);

    StreamMessage msg = translator.translatePermissionFromGateway(node);

    JsonNode subagentNode = node.path("subagentSessionId");
    if (!subagentNode.isMissingNode() && !subagentNode.isNull()) {
        msg.setSubagentSessionId(subagentNode.asText());
        msg.setSubagentName(node.path("subagentName").asText(null));
    }

    SkillSession session = resolveSession(sessionId);
    if (session != null && !session.isMiniappDomain() && session.getId() != null
            && msg.getPermission() != null) {
        interactionStateService.markPermission(
                session.getId(),
                msg.getPermission().getPermissionId());
    }

    outboundDeliveryDispatcher.deliver(session, sessionId, userId, msg);
}
```

- [ ] **Step 6: Refactor handleContextOverflow (line 1036-1054)**

Replace:

```java
private void handleContextOverflow(String sessionId, String userId, SkillSession session) {
    clearPendingImInteractionState(sessionId);
    rebuildService.handleContextOverflow(sessionId, userId, rebuildCallback());

    StreamMessage resetMsg = StreamMessage.builder()
            .type(StreamMessage.Types.ERROR)
            .error(CONTEXT_RESET_MESSAGE)
            .build();
    outboundDeliveryDispatcher.deliver(session, sessionId, userId, resetMsg);

    if (session.isImDirectSession()) {
        Long numericId = ProtocolUtils.parseSessionId(sessionId);
        if (numericId != null) {
            try {
                messageService.saveSystemMessage(numericId, CONTEXT_RESET_MESSAGE);
            } catch (Exception e) {
                log.warn("Failed to persist context reset message for session {}", sessionId);
            }
        }
    }
}
```

- [ ] **Step 7: Remove the old isMiniappSession, buildImText, formatPermissionPrompt, formatQuestionMessage, syncPendingImInteraction methods**

These are now handled by the delivery strategies. Remove:
- `isMiniappSession` (line 1020-1022) — replaced by strategy `supports()` logic
- `buildImText` (line 1058-1072) — moved to `ImRestDeliveryStrategy`
- `formatPermissionPrompt` (line 1114-1119) — moved to `ImRestDeliveryStrategy.buildImText()`
- `formatQuestionMessage` (line 1122-1141) — moved to `ImRestDeliveryStrategy`
- `handleImAssistantMessage` (line 527-556) — logic merged into `routeAssistantMessage`

Keep `syncPendingImInteraction` — it's still called from `routeAssistantMessage`.

- [ ] **Step 8: Run all existing tests to verify no regressions**

Run: `cd skill-server && mvn test -pl .`
Expected: All existing tests PASS (some may need mock adjustments for the new constructor parameter)

- [ ] **Step 9: Fix any test failures from the constructor change**

If tests like `GatewayMessageRouterImPushTest`, `GatewayRelayServiceTest`, `ImOutboundFilterTest` fail because of the new `OutboundDeliveryDispatcher` constructor parameter, add `@Mock private OutboundDeliveryDispatcher outboundDeliveryDispatcher;` and update the constructor call.

- [ ] **Step 10: Commit**

```bash
git add skill-server/src/main/java/com/opencode/cui/skill/service/GatewayMessageRouter.java skill-server/src/test/
git commit -m "refactor(router): replace if/else with OutboundDeliveryDispatcher strategy pattern"
```

---

### Task 7: ExternalInvokeRequest DTO

**Files:**
- Create: `skill-server/src/main/java/com/opencode/cui/skill/model/ExternalInvokeRequest.java`

- [ ] **Step 1: Write the DTO**

```java
package com.opencode.cui.skill.model;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

/**
 * 外部入站统一请求 DTO。
 * 固定信封 + 灵活 payload，与 GatewayMessage 风格一致。
 */
@Data
public class ExternalInvokeRequest {

    /** 动作类型：chat / question_reply / permission_reply / rebuild */
    private String action;

    /** 业务域（需与 WS source 一致） */
    private String businessDomain;

    /** 会话类型：group / direct */
    private String sessionType;

    /** 业务侧会话 ID */
    private String sessionId;

    /** 助手账号 */
    private String assistantAccount;

    /** action 专属数据 */
    private JsonNode payload;

    // ==================== Payload 便捷访问 ====================

    public String payloadString(String field) {
        if (payload == null || payload.isMissingNode() || payload.isNull()) {
            return null;
        }
        JsonNode node = payload.path(field);
        return node.isMissingNode() || node.isNull() ? null : node.asText();
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add skill-server/src/main/java/com/opencode/cui/skill/model/ExternalInvokeRequest.java
git commit -m "feat(model): add ExternalInvokeRequest DTO with envelope+payload"
```

---

### Task 8: InboundProcessingService (shared chat logic)

**Files:**
- Create: `skill-server/src/main/java/com/opencode/cui/skill/service/InboundProcessingService.java`
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/controller/ImInboundController.java`
- Test: `skill-server/src/test/java/com/opencode/cui/skill/service/InboundProcessingServiceTest.java`

- [ ] **Step 1: Write InboundProcessingService test (processChat — session ready path)**

```java
package com.opencode.cui.skill.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.config.AssistantIdProperties;
import com.opencode.cui.skill.model.AssistantResolveResult;
import com.opencode.cui.skill.model.SkillSession;
import com.opencode.cui.skill.service.delivery.OutboundDeliveryDispatcher;
import com.opencode.cui.skill.service.scope.AssistantScopeDispatcher;
import com.opencode.cui.skill.service.scope.AssistantScopeStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InboundProcessingServiceTest {

    @Mock private AssistantAccountResolverService resolverService;
    @Mock private AssistantInfoService assistantInfoService;
    @Mock private AssistantScopeDispatcher scopeDispatcher;
    @Mock private AssistantScopeStrategy scopeStrategy;
    @Mock private GatewayApiClient gatewayApiClient;
    @Mock private ImSessionManager sessionManager;
    @Mock private ContextInjectionService contextInjectionService;
    @Mock private GatewayRelayService gatewayRelayService;
    @Mock private SkillMessageService messageService;
    @Mock private SessionRebuildService rebuildService;
    @Mock private OutboundDeliveryDispatcher outboundDeliveryDispatcher;

    private AssistantIdProperties assistantIdProperties;
    private ObjectMapper objectMapper;
    private InboundProcessingService service;

    @BeforeEach
    void setUp() {
        assistantIdProperties = new AssistantIdProperties();
        assistantIdProperties.setEnabled(true);
        objectMapper = new ObjectMapper();

        service = new InboundProcessingService(
                resolverService, assistantIdProperties, gatewayApiClient,
                sessionManager, contextInjectionService, gatewayRelayService,
                messageService, rebuildService, objectMapper,
                assistantInfoService, scopeDispatcher, outboundDeliveryDispatcher);
    }

    @Test
    @DisplayName("processChat: session ready → sends CHAT invoke to gateway")
    void processChatSessionReady() {
        when(resolverService.resolve("assist-01"))
                .thenReturn(new AssistantResolveResult("ak-1", "owner-1"));
        when(assistantInfoService.getCachedScope("ak-1")).thenReturn("personal");
        when(scopeDispatcher.getStrategy("personal")).thenReturn(scopeStrategy);
        when(scopeStrategy.requiresOnlineCheck()).thenReturn(false);
        when(contextInjectionService.resolvePrompt(eq("direct"), eq("hello"), any()))
                .thenReturn("hello");

        SkillSession session = SkillSession.builder()
                .id(100L).ak("ak-1").toolSessionId("tool-1")
                .businessSessionDomain("im").businessSessionType("direct")
                .businessSessionId("dm-001").assistantAccount("assist-01")
                .build();
        when(sessionManager.findSession("im", "direct", "dm-001", "ak-1"))
                .thenReturn(session);

        var result = service.processChat("im", "direct", "dm-001",
                "assist-01", "hello", "text", null, null);

        assertTrue(result.success());
        verify(gatewayRelayService).sendInvokeToGateway(any());
        verify(messageService).saveUserMessage(eq(100L), eq("hello"));
    }

    @Test
    @DisplayName("processChat: invalid assistant → returns error")
    void processChatInvalidAssistant() {
        when(resolverService.resolve("invalid")).thenReturn(null);

        var result = service.processChat("im", "direct", "dm-001",
                "invalid", "hello", "text", null, null);

        assertFalse(result.success());
        assertEquals(404, result.code());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd skill-server && mvn test -pl . -Dtest="InboundProcessingServiceTest" -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL

- [ ] **Step 3: Write InboundProcessingService**

```java
package com.opencode.cui.skill.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.config.AssistantIdProperties;
import com.opencode.cui.skill.model.*;
import com.opencode.cui.skill.service.delivery.OutboundDeliveryDispatcher;
import com.opencode.cui.skill.service.scope.AssistantScopeDispatcher;
import com.opencode.cui.skill.service.scope.AssistantScopeStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 入站处理共享逻辑。
 * ImInboundController 和 ExternalInboundController 共用。
 */
@Slf4j
@Service
public class InboundProcessingService {

    private static final String AGENT_OFFLINE_MESSAGE = "任务下发失败，请检查助理是否离线，确保助理在线后重试";

    private final AssistantAccountResolverService resolverService;
    private final AssistantIdProperties assistantIdProperties;
    private final GatewayApiClient gatewayApiClient;
    private final ImSessionManager sessionManager;
    private final ContextInjectionService contextInjectionService;
    private final GatewayRelayService gatewayRelayService;
    private final SkillMessageService messageService;
    private final SessionRebuildService rebuildService;
    private final ObjectMapper objectMapper;
    private final AssistantInfoService assistantInfoService;
    private final AssistantScopeDispatcher scopeDispatcher;
    private final OutboundDeliveryDispatcher outboundDeliveryDispatcher;

    public InboundProcessingService(
            AssistantAccountResolverService resolverService,
            AssistantIdProperties assistantIdProperties,
            GatewayApiClient gatewayApiClient,
            ImSessionManager sessionManager,
            ContextInjectionService contextInjectionService,
            GatewayRelayService gatewayRelayService,
            SkillMessageService messageService,
            SessionRebuildService rebuildService,
            ObjectMapper objectMapper,
            AssistantInfoService assistantInfoService,
            AssistantScopeDispatcher scopeDispatcher,
            OutboundDeliveryDispatcher outboundDeliveryDispatcher) {
        this.resolverService = resolverService;
        this.assistantIdProperties = assistantIdProperties;
        this.gatewayApiClient = gatewayApiClient;
        this.sessionManager = sessionManager;
        this.contextInjectionService = contextInjectionService;
        this.gatewayRelayService = gatewayRelayService;
        this.messageService = messageService;
        this.rebuildService = rebuildService;
        this.objectMapper = objectMapper;
        this.assistantInfoService = assistantInfoService;
        this.scopeDispatcher = scopeDispatcher;
        this.outboundDeliveryDispatcher = outboundDeliveryDispatcher;
    }

    /**
     * 处理 chat 动作。
     */
    public InboundResult processChat(String businessDomain, String sessionType, String sessionId,
                                      String assistantAccount, String content, String msgType,
                                      String imageUrl, List<ImMessageRequest.ChatMessage> chatHistory) {
        // 1. 解析助手账号
        AssistantResolveResult resolveResult = resolverService.resolve(assistantAccount);
        if (resolveResult == null) {
            return InboundResult.error(404, "Invalid assistant account");
        }
        String ak = resolveResult.ak();
        String ownerWelinkId = resolveResult.ownerWelinkId();

        // 2. Scope 策略
        AssistantScopeStrategy scopeStrategy = scopeDispatcher.getStrategy(
                assistantInfoService.getCachedScope(ak));

        // 3. Agent 在线检查
        if (assistantIdProperties.isEnabled() && scopeStrategy.requiresOnlineCheck()) {
            if (gatewayApiClient.getAgentByAk(ak) == null) {
                handleAgentOffline(businessDomain, sessionType, sessionId, ak, assistantAccount);
                return InboundResult.ok();
            }
        }

        // 4. 上下文注入
        String prompt = contextInjectionService.resolvePrompt(sessionType, content, chatHistory);

        // 5. 查找 session
        SkillSession session = sessionManager.findSession(businessDomain, sessionType, sessionId, ak);

        // 6A: session 不存在 → 异步创建
        if (session == null) {
            sessionManager.createSessionAsync(businessDomain, sessionType, sessionId,
                    ak, ownerWelinkId, assistantAccount, prompt);
            return InboundResult.ok();
        }

        // 6B: session 存在但 toolSessionId 未就绪 → 请求重建
        if (session.getToolSessionId() == null || session.getToolSessionId().isBlank()) {
            sessionManager.requestToolSession(session, prompt);
            return InboundResult.ok();
        }

        // 6C: session 就绪 → 转发
        if (session.isImDirectSession()) {
            messageService.saveUserMessage(session.getId(), content);
        }
        rebuildService.appendPendingMessage(String.valueOf(session.getId()), prompt);

        Map<String, String> payloadFields = new LinkedHashMap<>();
        payloadFields.put("text", prompt);
        payloadFields.put("toolSessionId", session.getToolSessionId());
        payloadFields.put("assistantAccount", assistantAccount);
        payloadFields.put("sendUserAccount", ownerWelinkId);
        payloadFields.put("imGroupId", "group".equals(sessionType) ? sessionId : null);
        payloadFields.put("messageId", String.valueOf(System.currentTimeMillis()));
        gatewayRelayService.sendInvokeToGateway(new InvokeCommand(
                ak, ownerWelinkId, String.valueOf(session.getId()),
                GatewayActions.CHAT,
                PayloadBuilder.buildPayload(objectMapper, payloadFields)));

        return InboundResult.ok();
    }

    /**
     * 处理 question_reply 动作。
     */
    public InboundResult processQuestionReply(String businessDomain, String sessionType,
                                               String sessionId, String assistantAccount,
                                               String content, String toolCallId,
                                               String subagentSessionId) {
        AssistantResolveResult resolveResult = resolverService.resolve(assistantAccount);
        if (resolveResult == null) {
            return InboundResult.error(404, "Invalid assistant account");
        }
        String ak = resolveResult.ak();
        String ownerWelinkId = resolveResult.ownerWelinkId();

        SkillSession session = sessionManager.findSession(businessDomain, sessionType, sessionId, ak);
        if (session == null || session.getToolSessionId() == null || session.getToolSessionId().isBlank()) {
            return InboundResult.error(404, "Session not found or not ready");
        }

        String targetToolSessionId = subagentSessionId != null ? subagentSessionId : session.getToolSessionId();
        Map<String, String> payloadFields = new LinkedHashMap<>();
        payloadFields.put("answer", content);
        payloadFields.put("toolCallId", toolCallId);
        payloadFields.put("toolSessionId", targetToolSessionId);
        gatewayRelayService.sendInvokeToGateway(new InvokeCommand(
                ak, ownerWelinkId, String.valueOf(session.getId()),
                GatewayActions.QUESTION_REPLY,
                PayloadBuilder.buildPayload(objectMapper, payloadFields)));

        return InboundResult.ok();
    }

    /**
     * 处理 permission_reply 动作。
     */
    public InboundResult processPermissionReply(String businessDomain, String sessionType,
                                                 String sessionId, String assistantAccount,
                                                 String permissionId, String response,
                                                 String subagentSessionId) {
        AssistantResolveResult resolveResult = resolverService.resolve(assistantAccount);
        if (resolveResult == null) {
            return InboundResult.error(404, "Invalid assistant account");
        }
        String ak = resolveResult.ak();
        String ownerWelinkId = resolveResult.ownerWelinkId();

        SkillSession session = sessionManager.findSession(businessDomain, sessionType, sessionId, ak);
        if (session == null || session.getToolSessionId() == null || session.getToolSessionId().isBlank()) {
            return InboundResult.error(404, "Session not found or not ready");
        }

        String targetToolSessionId = subagentSessionId != null ? subagentSessionId : session.getToolSessionId();
        Map<String, String> payloadFields = new LinkedHashMap<>();
        payloadFields.put("permissionId", permissionId);
        payloadFields.put("response", response);
        payloadFields.put("toolSessionId", targetToolSessionId);
        gatewayRelayService.sendInvokeToGateway(new InvokeCommand(
                ak, ownerWelinkId, String.valueOf(session.getId()),
                GatewayActions.PERMISSION_REPLY,
                PayloadBuilder.buildPayload(objectMapper, payloadFields)));

        // 广播 permission.reply StreamMessage
        StreamMessage replyMsg = StreamMessage.builder()
                .type(StreamMessage.Types.PERMISSION_REPLY)
                .role("assistant")
                .permission(StreamMessage.PermissionInfo.builder()
                        .permissionId(permissionId)
                        .response(response)
                        .build())
                .subagentSessionId(subagentSessionId)
                .build();
        gatewayRelayService.publishProtocolMessage(String.valueOf(session.getId()), replyMsg);

        return InboundResult.ok();
    }

    /**
     * 处理 rebuild 动作。
     */
    public InboundResult processRebuild(String businessDomain, String sessionType,
                                         String sessionId, String assistantAccount) {
        AssistantResolveResult resolveResult = resolverService.resolve(assistantAccount);
        if (resolveResult == null) {
            return InboundResult.error(404, "Invalid assistant account");
        }
        String ak = resolveResult.ak();
        String ownerWelinkId = resolveResult.ownerWelinkId();

        SkillSession session = sessionManager.findSession(businessDomain, sessionType, sessionId, ak);
        if (session != null) {
            // session 存在 → 重建 toolSession
            sessionManager.requestToolSession(session, null);
        } else {
            // session 不存在 → 新建
            sessionManager.createSessionAsync(businessDomain, sessionType, sessionId,
                    ak, ownerWelinkId, assistantAccount, null);
        }
        return InboundResult.ok();
    }

    // ==================== Internal ====================

    private void handleAgentOffline(String businessDomain, String sessionType,
                                     String sessionId, String ak, String assistantAccount) {
        SkillSession session = sessionManager.findSession(businessDomain, sessionType, sessionId, ak);

        // 通过投递策略发送离线提示
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
    }

    /**
     * 入站处理结果。
     */
    public record InboundResult(boolean success, int code, String message) {
        public static InboundResult ok() {
            return new InboundResult(true, 0, null);
        }

        public static InboundResult error(int code, String message) {
            return new InboundResult(false, code, message);
        }
    }
}
```

- [ ] **Step 4: Refactor ImInboundController to delegate to InboundProcessingService**

Replace the `receiveMessage` method body to delegate to `InboundProcessingService.processChat()`, keeping the same endpoint and response format. The controller becomes a thin validation + delegation layer.

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd skill-server && mvn test -pl . -Dtest="InboundProcessingServiceTest,ImInboundControllerTest" -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add skill-server/src/main/java/com/opencode/cui/skill/service/InboundProcessingService.java skill-server/src/main/java/com/opencode/cui/skill/controller/ImInboundController.java skill-server/src/test/java/com/opencode/cui/skill/service/InboundProcessingServiceTest.java
git commit -m "feat(inbound): add InboundProcessingService, refactor ImInboundController to delegate"
```

---

### Task 9: ExternalInboundController + Auth config

**Files:**
- Create: `skill-server/src/main/java/com/opencode/cui/skill/controller/ExternalInboundController.java`
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/config/WebMvcConfig.java`
- Test: `skill-server/src/test/java/com/opencode/cui/skill/controller/ExternalInboundControllerTest.java`

- [ ] **Step 1: Write the test**

```java
package com.opencode.cui.skill.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.model.ExternalInvokeRequest;
import com.opencode.cui.skill.service.InboundProcessingService;
import com.opencode.cui.skill.service.InboundProcessingService.InboundResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ExternalInboundControllerTest {

    @Mock private InboundProcessingService processingService;
    private ObjectMapper objectMapper;
    private ExternalInboundController controller;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        controller = new ExternalInboundController(processingService, objectMapper);
    }

    private ExternalInvokeRequest buildRequest(String action, String payload) throws Exception {
        String json = "{\"action\":\"" + action + "\","
                + "\"businessDomain\":\"im\",\"sessionType\":\"direct\","
                + "\"sessionId\":\"dm-001\",\"assistantAccount\":\"assist-01\","
                + "\"payload\":" + payload + "}";
        return objectMapper.readValue(json, ExternalInvokeRequest.class);
    }

    @Test
    @DisplayName("chat action dispatches to processChat")
    void chatAction() throws Exception {
        when(processingService.processChat(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(InboundResult.ok());

        var request = buildRequest("chat", "{\"content\":\"hello\",\"msgType\":\"text\"}");
        var response = controller.invoke(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(0, response.getBody().getCode());
        verify(processingService).processChat(
                eq("im"), eq("direct"), eq("dm-001"), eq("assist-01"),
                eq("hello"), eq("text"), isNull(), isNull());
    }

    @Test
    @DisplayName("missing action returns 400")
    void missingAction() {
        ExternalInvokeRequest request = new ExternalInvokeRequest();
        request.setBusinessDomain("im");
        request.setSessionType("direct");
        request.setSessionId("dm-001");
        request.setAssistantAccount("assist-01");

        var response = controller.invoke(request);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    @DisplayName("chat without content returns 400")
    void chatWithoutContent() throws Exception {
        var request = buildRequest("chat", "{\"msgType\":\"text\"}");
        var response = controller.invoke(request);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    @DisplayName("question_reply dispatches correctly")
    void questionReplyAction() throws Exception {
        when(processingService.processQuestionReply(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(InboundResult.ok());

        var request = buildRequest("question_reply",
                "{\"content\":\"A\",\"toolCallId\":\"tc-1\"}");
        var response = controller.invoke(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(processingService).processQuestionReply(
                eq("im"), eq("direct"), eq("dm-001"), eq("assist-01"),
                eq("A"), eq("tc-1"), isNull());
    }

    @Test
    @DisplayName("permission_reply dispatches correctly")
    void permissionReplyAction() throws Exception {
        when(processingService.processPermissionReply(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(InboundResult.ok());

        var request = buildRequest("permission_reply",
                "{\"permissionId\":\"perm-1\",\"response\":\"once\"}");
        var response = controller.invoke(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(processingService).processPermissionReply(
                eq("im"), eq("direct"), eq("dm-001"), eq("assist-01"),
                eq("perm-1"), eq("once"), isNull());
    }

    @Test
    @DisplayName("rebuild dispatches correctly")
    void rebuildAction() throws Exception {
        when(processingService.processRebuild(any(), any(), any(), any()))
                .thenReturn(InboundResult.ok());

        var request = buildRequest("rebuild", "{}");
        var response = controller.invoke(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(processingService).processRebuild("im", "direct", "dm-001", "assist-01");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd skill-server && mvn test -pl . -Dtest="ExternalInboundControllerTest" -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL

- [ ] **Step 3: Write ExternalInboundController**

```java
package com.opencode.cui.skill.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.model.ApiResponse;
import com.opencode.cui.skill.model.ExternalInvokeRequest;
import com.opencode.cui.skill.model.ImMessageRequest;
import com.opencode.cui.skill.service.InboundProcessingService;
import com.opencode.cui.skill.service.InboundProcessingService.InboundResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 外部入站统一控制器。
 * 通过 action 字段路由到 InboundProcessingService 对应处理方法。
 */
@Slf4j
@RestController
@RequestMapping("/api/external")
public class ExternalInboundController {

    private static final Set<String> VALID_ACTIONS = Set.of(
            "chat", "question_reply", "permission_reply", "rebuild");
    private static final Set<String> VALID_SESSION_TYPES = Set.of("group", "direct");
    private static final Set<String> VALID_RESPONSES = Set.of("once", "always", "reject");

    private final InboundProcessingService processingService;
    private final ObjectMapper objectMapper;

    public ExternalInboundController(InboundProcessingService processingService,
                                      ObjectMapper objectMapper) {
        this.processingService = processingService;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/invoke")
    public ResponseEntity<ApiResponse<Void>> invoke(@RequestBody ExternalInvokeRequest request) {
        log.info("[ENTRY] ExternalInboundController.invoke: action={}, domain={}, sessionType={}, sessionId={}, assistant={}",
                request != null ? request.getAction() : null,
                request != null ? request.getBusinessDomain() : null,
                request != null ? request.getSessionType() : null,
                request != null ? request.getSessionId() : null,
                request != null ? request.getAssistantAccount() : null);

        // 信封校验
        String envelopeError = validateEnvelope(request);
        if (envelopeError != null) {
            return ResponseEntity.badRequest().body(ApiResponse.error(400, envelopeError));
        }

        // Payload 校验
        String payloadError = validatePayload(request);
        if (payloadError != null) {
            return ResponseEntity.badRequest().body(ApiResponse.error(400, payloadError));
        }

        // 按 action 路由
        InboundResult result = switch (request.getAction()) {
            case "chat" -> processingService.processChat(
                    request.getBusinessDomain(),
                    request.getSessionType(),
                    request.getSessionId(),
                    request.getAssistantAccount(),
                    request.payloadString("content"),
                    request.payloadString("msgType"),
                    request.payloadString("imageUrl"),
                    parseChatHistory(request.getPayload()));
            case "question_reply" -> processingService.processQuestionReply(
                    request.getBusinessDomain(),
                    request.getSessionType(),
                    request.getSessionId(),
                    request.getAssistantAccount(),
                    request.payloadString("content"),
                    request.payloadString("toolCallId"),
                    request.payloadString("subagentSessionId"));
            case "permission_reply" -> processingService.processPermissionReply(
                    request.getBusinessDomain(),
                    request.getSessionType(),
                    request.getSessionId(),
                    request.getAssistantAccount(),
                    request.payloadString("permissionId"),
                    request.payloadString("response"),
                    request.payloadString("subagentSessionId"));
            case "rebuild" -> processingService.processRebuild(
                    request.getBusinessDomain(),
                    request.getSessionType(),
                    request.getSessionId(),
                    request.getAssistantAccount());
            default -> InboundResult.error(400, "Unknown action: " + request.getAction());
        };

        if (!result.success()) {
            return ResponseEntity.ok(ApiResponse.error(result.code(), result.message()));
        }
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    private String validateEnvelope(ExternalInvokeRequest request) {
        if (request == null) {
            return "Request body is required";
        }
        if (request.getAction() == null || request.getAction().isBlank()) {
            return "action is required";
        }
        if (!VALID_ACTIONS.contains(request.getAction())) {
            return "Invalid action: " + request.getAction();
        }
        if (request.getBusinessDomain() == null || request.getBusinessDomain().isBlank()) {
            return "businessDomain is required";
        }
        if (request.getSessionType() == null || !VALID_SESSION_TYPES.contains(request.getSessionType())) {
            return "Invalid sessionType";
        }
        if (request.getSessionId() == null || request.getSessionId().isBlank()) {
            return "sessionId is required";
        }
        if (request.getAssistantAccount() == null || request.getAssistantAccount().isBlank()) {
            return "assistantAccount is required";
        }
        return null;
    }

    private String validatePayload(ExternalInvokeRequest request) {
        return switch (request.getAction()) {
            case "chat" -> {
                String content = request.payloadString("content");
                yield (content == null || content.isBlank()) ? "payload.content is required for chat" : null;
            }
            case "question_reply" -> {
                String content = request.payloadString("content");
                String toolCallId = request.payloadString("toolCallId");
                if (content == null || content.isBlank()) yield "payload.content is required for question_reply";
                if (toolCallId == null || toolCallId.isBlank()) yield "payload.toolCallId is required for question_reply";
                yield null;
            }
            case "permission_reply" -> {
                String permissionId = request.payloadString("permissionId");
                String resp = request.payloadString("response");
                if (permissionId == null || permissionId.isBlank()) yield "payload.permissionId is required";
                if (resp == null || !VALID_RESPONSES.contains(resp)) yield "payload.response must be once/always/reject";
                yield null;
            }
            case "rebuild" -> null;
            default -> "Unknown action";
        };
    }

    private List<ImMessageRequest.ChatMessage> parseChatHistory(JsonNode payload) {
        if (payload == null) {
            return null;
        }
        JsonNode historyNode = payload.path("chatHistory");
        if (historyNode.isMissingNode() || historyNode.isNull() || !historyNode.isArray()) {
            return null;
        }
        try {
            List<ImMessageRequest.ChatMessage> history = new ArrayList<>();
            for (JsonNode item : historyNode) {
                history.add(new ImMessageRequest.ChatMessage(
                        item.path("senderAccount").asText(null),
                        item.path("senderName").asText(null),
                        item.path("content").asText(null),
                        item.path("timestamp").asLong(0)));
            }
            return history;
        } catch (Exception e) {
            log.warn("Failed to parse chatHistory: {}", e.getMessage());
            return null;
        }
    }
}
```

- [ ] **Step 4: Add /api/external/** to ImTokenAuthInterceptor in WebMvcConfig**

Modify `skill-server/src/main/java/com/opencode/cui/skill/config/WebMvcConfig.java`:

```java
@Override
public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(mdcRequestInterceptor)
            .addPathPatterns("/api/**");
    registry.addInterceptor(imTokenAuthInterceptor)
            .addPathPatterns("/api/inbound/**", "/api/external/**");
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `cd skill-server && mvn test -pl . -Dtest="ExternalInboundControllerTest" -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add skill-server/src/main/java/com/opencode/cui/skill/controller/ExternalInboundController.java skill-server/src/main/java/com/opencode/cui/skill/config/WebMvcConfig.java skill-server/src/test/java/com/opencode/cui/skill/controller/ExternalInboundControllerTest.java
git commit -m "feat(external): add ExternalInboundController + auth config for /api/external/**"
```

---

### Task 10: Full integration test + verify all existing tests pass

**Files:**
- No new files — validation only

- [ ] **Step 1: Run the full test suite**

Run: `cd skill-server && mvn test -pl .`
Expected: ALL tests PASS

- [ ] **Step 2: Fix any remaining test failures**

If any existing tests fail due to new constructor parameters (e.g., `OutboundDeliveryDispatcher` in `GatewayMessageRouter`), add the mock and update the constructor call.

- [ ] **Step 3: Run compilation check**

Run: `cd skill-server && mvn compile -pl .`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit any test fixes**

```bash
git add -A
git commit -m "fix(test): adapt existing tests to new OutboundDeliveryDispatcher dependency"
```

---

## Summary

| Task | What | Files |
|------|------|-------|
| 1 | Strategy interface + Dispatcher | 3 new |
| 2 | Redis generic channel pub/sub | 1 modified |
| 3 | MiniappDeliveryStrategy | 2 new |
| 4 | ExternalStreamHandler (WS gateway) | 2 new, 1 modified |
| 5 | ExternalWs + ImRest strategies | 4 new |
| 6 | Refactor GatewayMessageRouter | 1 modified |
| 7 | ExternalInvokeRequest DTO | 1 new |
| 8 | InboundProcessingService + ImInbound refactor | 2 new, 1 modified |
| 9 | ExternalInboundController + auth config | 2 new, 1 modified |
| 10 | Integration verification | 0 new |

**Total: ~16 new files, ~5 modified files, 10 commits**
