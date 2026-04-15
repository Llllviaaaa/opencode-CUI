# Inbound Delivery Routing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 通过配置开关 + invoke-source Redis 标记 + 三层精确投递，实现按入站来源选择 REST/WS 投递路径，消除广播浪费。

**Architecture:** mode=rest 时全走 REST（过渡期兼容）；mode=ws 时通过 invoke-source 标记区分 IM→REST / EXTERNAL→WS，WS 路径走 L1 本地直推 → L2 跨 SS relay → L3 降级 REST 的三层精确投递。

**Tech Stack:** Java 21, Spring Boot, Redis (StringRedisTemplate), WebSocket, Mockito/JUnit 5

**Spec:** `docs/superpowers/specs/2026-04-15-inbound-delivery-routing-design.md`

---

### Task 1: 配置属性类 + application.yml

**Files:**
- Create: `skill-server/src/main/java/com/opencode/cui/skill/config/DeliveryProperties.java`
- Modify: `skill-server/src/main/resources/application.yml:93`
- Test: `skill-server/src/test/java/com/opencode/cui/skill/config/DeliveryPropertiesTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.opencode.cui.skill.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DeliveryPropertiesTest {

    @Test
    @DisplayName("defaults: mode=rest, invoke-source-ttl=300, registry-ttl=30, heartbeat=10000")
    void defaults() {
        DeliveryProperties props = new DeliveryProperties();
        assertEquals("rest", props.getMode());
        assertEquals(300, props.getInvokeSourceTtlSeconds());
        assertEquals(30, props.getRegistryTtlSeconds());
        assertEquals(10000, props.getRegistryHeartbeatIntervalMs());
    }

    @Test
    @DisplayName("isWsMode returns true only when mode is ws")
    void isWsMode() {
        DeliveryProperties props = new DeliveryProperties();
        assertFalse(props.isWsMode());
        props.setMode("ws");
        assertTrue(props.isWsMode());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd skill-server && mvn test -pl . -Dtest=DeliveryPropertiesTest -DfailIfNoTests=false`
Expected: FAIL — class not found

- [ ] **Step 3: Write DeliveryProperties**

```java
package com.opencode.cui.skill.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "skill.delivery")
public class DeliveryProperties {

    /** 投递模式：rest | ws */
    private String mode = "rest";

    /** invoke-source Redis 标记 TTL（秒） */
    private int invokeSourceTtlSeconds = 300;

    /** WS 连接注册表 TTL（秒） */
    private int registryTtlSeconds = 30;

    /** 注册表心跳刷新间隔（毫秒） */
    private long registryHeartbeatIntervalMs = 10000;

    public boolean isWsMode() {
        return "ws".equalsIgnoreCase(mode);
    }
}
```

- [ ] **Step 4: Add config to application.yml**

在 `skill-server/src/main/resources/application.yml` 的 `skill:` 块末尾（`assistant-id:` 之后）追加：

```yaml
  delivery:
    mode: ${SKILL_DELIVERY_MODE:rest}
    invoke-source-ttl-seconds: ${SKILL_DELIVERY_INVOKE_SOURCE_TTL_SECONDS:300}
    registry-ttl-seconds: ${SKILL_DELIVERY_REGISTRY_TTL_SECONDS:30}
    registry-heartbeat-interval-ms: ${SKILL_DELIVERY_REGISTRY_HEARTBEAT_INTERVAL_MS:10000}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `cd skill-server && mvn test -pl . -Dtest=DeliveryPropertiesTest`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add skill-server/src/main/java/com/opencode/cui/skill/config/DeliveryProperties.java \
       skill-server/src/test/java/com/opencode/cui/skill/config/DeliveryPropertiesTest.java \
       skill-server/src/main/resources/application.yml
git commit -m "feat: add DeliveryProperties config for delivery routing"
```

---

### Task 2: RedisMessageBroker — invoke-source 读写 + Registry Hash 操作

**Files:**
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/service/RedisMessageBroker.java`
- Test: `skill-server/src/test/java/com/opencode/cui/skill/service/RedisMessageBrokerDeliveryTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.opencode.cui.skill.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RedisMessageBrokerDeliveryTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private RedisMessageListenerContainer listenerContainer;
    @Mock private ValueOperations<String, String> valueOps;
    @Mock private HashOperations<String, Object, Object> hashOps;
    @InjectMocks private RedisMessageBroker broker;

    @Test
    @DisplayName("setInvokeSource writes key with TTL")
    void setInvokeSource() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        broker.setInvokeSource("12345", "IM", 300);
        verify(valueOps).set("invoke-source:12345", "IM", 300, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("getInvokeSource reads key")
    void getInvokeSource() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("invoke-source:12345")).thenReturn("EXTERNAL");
        assertEquals("EXTERNAL", broker.getInvokeSource("12345"));
    }

    @Test
    @DisplayName("expireInvokeSource renews TTL")
    void expireInvokeSource() {
        broker.expireInvokeSource("12345", 300);
        verify(redisTemplate).expire("invoke-source:12345", 300, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("registerWsConnection sets hash field and expires key")
    void registerWsConnection() {
        when(redisTemplate.opsForHash()).thenReturn(hashOps);
        broker.registerWsConnection("im", "ss-pod-1", 3, 30);
        verify(hashOps).put("external-ws:registry:im", "ss-pod-1", "3");
        verify(redisTemplate).expire("external-ws:registry:im", 30, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("unregisterWsConnection removes hash field")
    void unregisterWsConnection() {
        when(redisTemplate.opsForHash()).thenReturn(hashOps);
        broker.unregisterWsConnection("im", "ss-pod-1");
        verify(hashOps).delete("external-ws:registry:im", "ss-pod-1");
    }

    @Test
    @DisplayName("getWsRegistry returns all entries")
    @SuppressWarnings("unchecked")
    void getWsRegistry() {
        when(redisTemplate.opsForHash()).thenReturn(hashOps);
        when(hashOps.entries("external-ws:registry:im")).thenReturn(
                Map.of("ss-pod-1", "3", "ss-pod-2", "1"));
        Map<String, String> result = broker.getWsRegistry("im");
        assertEquals(2, result.size());
        assertEquals("3", result.get("ss-pod-1"));
    }

    @Test
    @DisplayName("expireWsRegistry renews TTL on registry key")
    void expireWsRegistry() {
        broker.expireWsRegistry("im", 30);
        verify(redisTemplate).expire("external-ws:registry:im", 30, TimeUnit.SECONDS);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd skill-server && mvn test -pl . -Dtest=RedisMessageBrokerDeliveryTest`
Expected: FAIL — methods not found

- [ ] **Step 3: Add methods to RedisMessageBroker**

在 `RedisMessageBroker.java` 末尾（`}` 之前）追加：

```java
    // ==================== invoke-source 标记 ====================

    private static final String INVOKE_SOURCE_PREFIX = "invoke-source:";

    public void setInvokeSource(String sessionId, String source, int ttlSeconds) {
        try {
            redisTemplate.opsForValue().set(
                    INVOKE_SOURCE_PREFIX + sessionId, source, ttlSeconds, TimeUnit.SECONDS);
            log.debug("Set invoke-source: sessionId={}, source={}, ttl={}s", sessionId, source, ttlSeconds);
        } catch (Exception e) {
            log.error("Failed to set invoke-source: sessionId={}, error={}", sessionId, e.getMessage());
        }
    }

    public String getInvokeSource(String sessionId) {
        try {
            return redisTemplate.opsForValue().get(INVOKE_SOURCE_PREFIX + sessionId);
        } catch (Exception e) {
            log.error("Failed to get invoke-source: sessionId={}, error={}", sessionId, e.getMessage());
            return null;
        }
    }

    public void expireInvokeSource(String sessionId, int ttlSeconds) {
        try {
            redisTemplate.expire(INVOKE_SOURCE_PREFIX + sessionId, ttlSeconds, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.error("Failed to expire invoke-source: sessionId={}, error={}", sessionId, e.getMessage());
        }
    }

    // ==================== WS 连接注册表 ====================

    private static final String WS_REGISTRY_PREFIX = "external-ws:registry:";

    public void registerWsConnection(String domain, String instanceId, int connectionCount, int ttlSeconds) {
        try {
            String key = WS_REGISTRY_PREFIX + domain;
            redisTemplate.opsForHash().put(key, instanceId, String.valueOf(connectionCount));
            redisTemplate.expire(key, ttlSeconds, TimeUnit.SECONDS);
            log.debug("Registered WS connection: domain={}, instanceId={}, count={}", domain, instanceId, connectionCount);
        } catch (Exception e) {
            log.error("Failed to register WS connection: domain={}, error={}", domain, e.getMessage());
        }
    }

    public void unregisterWsConnection(String domain, String instanceId) {
        try {
            redisTemplate.opsForHash().delete(WS_REGISTRY_PREFIX + domain, instanceId);
            log.debug("Unregistered WS connection: domain={}, instanceId={}", domain, instanceId);
        } catch (Exception e) {
            log.error("Failed to unregister WS connection: domain={}, error={}", domain, e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, String> getWsRegistry(String domain) {
        try {
            Map<Object, Object> entries = redisTemplate.opsForHash().entries(WS_REGISTRY_PREFIX + domain);
            Map<String, String> result = new java.util.HashMap<>();
            entries.forEach((k, v) -> result.put(k.toString(), v.toString()));
            return result;
        } catch (Exception e) {
            log.error("Failed to get WS registry: domain={}, error={}", domain, e.getMessage());
            return java.util.Collections.emptyMap();
        }
    }

    public void expireWsRegistry(String domain, int ttlSeconds) {
        try {
            redisTemplate.expire(WS_REGISTRY_PREFIX + domain, ttlSeconds, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.error("Failed to expire WS registry: domain={}, error={}", domain, e.getMessage());
        }
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd skill-server && mvn test -pl . -Dtest=RedisMessageBrokerDeliveryTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add skill-server/src/main/java/com/opencode/cui/skill/service/RedisMessageBroker.java \
       skill-server/src/test/java/com/opencode/cui/skill/service/RedisMessageBrokerDeliveryTest.java
git commit -m "feat: add invoke-source and WS registry methods to RedisMessageBroker"
```

---

### Task 3: ExternalWsRegistry — 连接注册表服务

**Files:**
- Create: `skill-server/src/main/java/com/opencode/cui/skill/service/ExternalWsRegistry.java`
- Test: `skill-server/src/test/java/com/opencode/cui/skill/service/ExternalWsRegistryTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.opencode.cui.skill.service;

import com.opencode.cui.skill.config.DeliveryProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ExternalWsRegistryTest {

    @Mock private RedisMessageBroker redisMessageBroker;
    @Mock private SkillInstanceRegistry instanceRegistry;
    @Mock private DeliveryProperties deliveryProperties;
    @InjectMocks private ExternalWsRegistry registry;

    @Test
    @DisplayName("register writes hash with configured TTL")
    void register() {
        when(instanceRegistry.getInstanceId()).thenReturn("ss-pod-1");
        when(deliveryProperties.getRegistryTtlSeconds()).thenReturn(30);
        registry.register("im", 3);
        verify(redisMessageBroker).registerWsConnection("im", "ss-pod-1", 3, 30);
    }

    @Test
    @DisplayName("unregister removes hash field")
    void unregister() {
        when(instanceRegistry.getInstanceId()).thenReturn("ss-pod-1");
        registry.unregister("im");
        verify(redisMessageBroker).unregisterWsConnection("im", "ss-pod-1");
    }

    @Test
    @DisplayName("findInstanceWithConnection returns first remote instance")
    void findInstanceWithConnection() {
        when(instanceRegistry.getInstanceId()).thenReturn("ss-pod-1");
        when(redisMessageBroker.getWsRegistry("im")).thenReturn(
                Map.of("ss-pod-1", "0", "ss-pod-2", "3"));
        String target = registry.findInstanceWithConnection("im");
        assertEquals("ss-pod-2", target);
    }

    @Test
    @DisplayName("findInstanceWithConnection returns null when no remote instances")
    void findInstanceNoRemote() {
        when(instanceRegistry.getInstanceId()).thenReturn("ss-pod-1");
        when(redisMessageBroker.getWsRegistry("im")).thenReturn(Collections.emptyMap());
        assertNull(registry.findInstanceWithConnection("im"));
    }

    @Test
    @DisplayName("findInstanceWithConnection skips self")
    void findInstanceSkipsSelf() {
        when(instanceRegistry.getInstanceId()).thenReturn("ss-pod-1");
        when(redisMessageBroker.getWsRegistry("im")).thenReturn(Map.of("ss-pod-1", "2"));
        assertNull(registry.findInstanceWithConnection("im"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd skill-server && mvn test -pl . -Dtest=ExternalWsRegistryTest`
Expected: FAIL — class not found

- [ ] **Step 3: Write ExternalWsRegistry**

```java
package com.opencode.cui.skill.service;

import com.opencode.cui.skill.config.DeliveryProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * WS 连接注册表服务。
 * 管理 External WS 连接在 Redis 中的全局注册信息，
 * 用于跨 SS 实例精确投递。
 */
@Slf4j
@Service
public class ExternalWsRegistry {

    private final RedisMessageBroker redisMessageBroker;
    private final SkillInstanceRegistry instanceRegistry;
    private final DeliveryProperties deliveryProperties;

    public ExternalWsRegistry(RedisMessageBroker redisMessageBroker,
                               SkillInstanceRegistry instanceRegistry,
                               DeliveryProperties deliveryProperties) {
        this.redisMessageBroker = redisMessageBroker;
        this.instanceRegistry = instanceRegistry;
        this.deliveryProperties = deliveryProperties;
    }

    /** 注册本实例持有的 WS 连接数。 */
    public void register(String domain, int connectionCount) {
        redisMessageBroker.registerWsConnection(
                domain, instanceRegistry.getInstanceId(),
                connectionCount, deliveryProperties.getRegistryTtlSeconds());
    }

    /** 注销本实例在指定 domain 的 WS 连接。 */
    public void unregister(String domain) {
        redisMessageBroker.unregisterWsConnection(domain, instanceRegistry.getInstanceId());
    }

    /** 续期本实例注册的所有 domain 的 TTL。 */
    public void heartbeat(String domain) {
        redisMessageBroker.expireWsRegistry(domain, deliveryProperties.getRegistryTtlSeconds());
    }

    /**
     * 查找一台持有指定 domain WS 连接的远程 SS 实例。
     * 跳过本实例，返回第一个连接数 > 0 的远程实例 ID。
     * 无可用实例返回 null。
     */
    public String findInstanceWithConnection(String domain) {
        Map<String, String> registry = redisMessageBroker.getWsRegistry(domain);
        String selfId = instanceRegistry.getInstanceId();
        for (Map.Entry<String, String> entry : registry.entrySet()) {
            if (!entry.getKey().equals(selfId)) {
                int count = 0;
                try { count = Integer.parseInt(entry.getValue()); } catch (NumberFormatException ignored) {}
                if (count > 0) {
                    return entry.getKey();
                }
            }
        }
        return null;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd skill-server && mvn test -pl . -Dtest=ExternalWsRegistryTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add skill-server/src/main/java/com/opencode/cui/skill/service/ExternalWsRegistry.java \
       skill-server/src/test/java/com/opencode/cui/skill/service/ExternalWsRegistryTest.java
git commit -m "feat: add ExternalWsRegistry for cross-SS WS connection tracking"
```

---

### Task 4: ExternalStreamHandler — pushToOne + Registry 集成 + relay 订阅

**Files:**
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/ws/ExternalStreamHandler.java`
- Test: `skill-server/src/test/java/com/opencode/cui/skill/ws/ExternalStreamHandlerDeliveryTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.opencode.cui.skill.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.config.DeliveryProperties;
import com.opencode.cui.skill.service.ExternalWsRegistry;
import com.opencode.cui.skill.service.RedisMessageBroker;
import com.opencode.cui.skill.service.SkillInstanceRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ExternalStreamHandlerDeliveryTest {

    @Mock private ObjectMapper objectMapper;
    @Mock private RedisMessageBroker redisMessageBroker;
    @Mock private ExternalWsRegistry wsRegistry;
    @Mock private SkillInstanceRegistry instanceRegistry;
    @Mock private DeliveryProperties deliveryProperties;
    @Mock private WebSocketSession session1;
    @Mock private WebSocketSession session2;

    private ExternalStreamHandler handler;

    @BeforeEach
    void setUp() {
        handler = new ExternalStreamHandler(objectMapper, redisMessageBroker,
                "changeme", wsRegistry, instanceRegistry, deliveryProperties);
    }

    @Test
    @DisplayName("pushToOne sends to exactly one open connection")
    void pushToOneSelectsFirst() throws Exception {
        // 模拟两条连接
        when(session1.isOpen()).thenReturn(true);
        when(session1.getAttributes()).thenReturn(java.util.Map.of("source", "im", "instanceId", "inst-1"));
        when(session2.isOpen()).thenReturn(true);
        when(session2.getAttributes()).thenReturn(java.util.Map.of("source", "im", "instanceId", "inst-2"));

        // 手动注入到 connectionPool（通过 afterConnectionEstablished 模拟）
        when(deliveryProperties.getRegistryTtlSeconds()).thenReturn(30);
        when(instanceRegistry.getInstanceId()).thenReturn("ss-1");
        handler.afterConnectionEstablished(session1);
        handler.afterConnectionEstablished(session2);

        boolean result = handler.pushToOne("im", "{\"type\":\"text.done\"}");
        assertTrue(result);

        // 只发一次，不广播
        int sendCount = 0;
        try { verify(session1).sendMessage(any(TextMessage.class)); sendCount++; } catch (AssertionError ignored) {}
        try { verify(session2).sendMessage(any(TextMessage.class)); sendCount++; } catch (AssertionError ignored) {}
        assertEquals(1, sendCount, "Should send to exactly one session");
    }

    @Test
    @DisplayName("pushToOne returns false when no connections")
    void pushToOneNoConnections() {
        assertFalse(handler.pushToOne("im", "{}"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd skill-server && mvn test -pl . -Dtest=ExternalStreamHandlerDeliveryTest`
Expected: FAIL — constructor mismatch / method not found

- [ ] **Step 3: Modify ExternalStreamHandler**

3a. 更新构造函数，注入新依赖：

将现有构造函数：
```java
    public ExternalStreamHandler(ObjectMapper objectMapper,
                                  RedisMessageBroker redisMessageBroker,
                                  @Value("${skill.im.inbound-token:changeme}") String inboundToken) {
        this.objectMapper = objectMapper;
        this.redisMessageBroker = redisMessageBroker;
        this.inboundToken = inboundToken;
    }
```

替换为：
```java
    private final ExternalWsRegistry wsRegistry;
    private final SkillInstanceRegistry instanceRegistry;
    private final DeliveryProperties deliveryProperties;

    public ExternalStreamHandler(ObjectMapper objectMapper,
                                  RedisMessageBroker redisMessageBroker,
                                  @Value("${skill.im.inbound-token:changeme}") String inboundToken,
                                  ExternalWsRegistry wsRegistry,
                                  SkillInstanceRegistry instanceRegistry,
                                  DeliveryProperties deliveryProperties) {
        this.objectMapper = objectMapper;
        this.redisMessageBroker = redisMessageBroker;
        this.inboundToken = inboundToken;
        this.wsRegistry = wsRegistry;
        this.instanceRegistry = instanceRegistry;
        this.deliveryProperties = deliveryProperties;
    }
```

需要添加 import：
```java
import com.opencode.cui.skill.config.DeliveryProperties;
import com.opencode.cui.skill.service.ExternalWsRegistry;
import com.opencode.cui.skill.service.SkillInstanceRegistry;
import jakarta.annotation.PostConstruct;
```

3b. 在 `afterConnectionEstablished()` 末尾追加 Registry 注册：

在 `log.info("External WS connected: ...)` 之后追加：
```java
        // 注册到全局 WS 连接表
        wsRegistry.register(source, instances.size());
```

3c. 在 `afterConnectionClosed()` 中追加 Registry 更新：

将现有 `if (instances.isEmpty())` 块：
```java
            if (instances.isEmpty()) {
                connectionPool.remove(source);
                redisMessageBroker.unsubscribeFromChannel(CHANNEL_PREFIX + source);
            }
```

替换为：
```java
            if (instances.isEmpty()) {
                connectionPool.remove(source);
                redisMessageBroker.unsubscribeFromChannel(CHANNEL_PREFIX + source);
                wsRegistry.unregister(source);
            } else {
                wsRegistry.register(source, instances.size());
            }
```

3d. 新增 `pushToOne()` 方法（在 `pushToSource()` 方法之后）：

```java
    /**
     * 精确投递：选择一条活跃 WS 连接推送消息。
     * 与 pushToSource（广播到所有连接）不同，pushToOne 只发一次。
     *
     * @return true 如果成功推送，false 如果无可用连接
     */
    public boolean pushToOne(String source, String message) {
        Map<String, WebSocketSession> instances = connectionPool.get(source);
        if (instances == null || instances.isEmpty()) {
            return false;
        }
        TextMessage textMessage = new TextMessage(message);
        for (WebSocketSession session : instances.values()) {
            if (session.isOpen()) {
                try {
                    synchronized (session) { session.sendMessage(textMessage); }
                    return true;
                } catch (Exception e) {
                    log.error("Failed to pushToOne: source={}, sessionId={}, error={}",
                            source, session.getId(), e.getMessage());
                }
            }
        }
        return false;
    }
```

3e. 新增 relay channel 订阅（`@PostConstruct`）：

```java
    @PostConstruct
    public void subscribeRelayChannel() {
        String instanceId = instanceRegistry.getInstanceId();
        redisMessageBroker.subscribeToChannel("ss:external-relay:" + instanceId,
                msg -> handleRelayMessage(msg));
        log.info("Subscribed to external relay channel: ss:external-relay:{}", instanceId);
    }

    private void handleRelayMessage(String message) {
        try {
            var node = objectMapper.readTree(message);
            String domain = node.path("domain").asText(null);
            String payload = node.path("payload").asText(null);
            if (domain != null && payload != null) {
                boolean sent = pushToOne(domain, payload);
                log.info("[RELAY-RX] External relay: domain={}, sent={}", domain, sent);
            }
        } catch (Exception e) {
            log.error("Failed to handle external relay message: {}", e.getMessage());
        }
    }
```

3f. 更新心跳定时任务，追加 Registry 续期：

在 `checkHeartbeatTimeouts()` 方法末尾追加：
```java
        // 续期 WS 连接注册表
        for (String source : connectionPool.keySet()) {
            wsRegistry.heartbeat(source);
        }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd skill-server && mvn test -pl . -Dtest=ExternalStreamHandlerDeliveryTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add skill-server/src/main/java/com/opencode/cui/skill/ws/ExternalStreamHandler.java \
       skill-server/src/test/java/com/opencode/cui/skill/ws/ExternalStreamHandlerDeliveryTest.java
git commit -m "feat: add pushToOne, registry integration, relay channel to ExternalStreamHandler"
```

---

### Task 5: InboundProcessingService — invoke-source 标记写入

**Files:**
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/service/InboundProcessingService.java`
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/controller/ImInboundController.java`
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/controller/ExternalInboundController.java`

- [ ] **Step 1: Add inboundSource parameter and Redis write to InboundProcessingService**

1a. 注入新依赖。在构造函数参数列表末尾追加：
```java
            DeliveryProperties deliveryProperties,
            RedisMessageBroker redisMessageBroker) {
```
追加字段：
```java
    private final DeliveryProperties deliveryProperties;
    private final RedisMessageBroker redisMessageBroker;
```
追加 import：
```java
import com.opencode.cui.skill.config.DeliveryProperties;
```

注意：`InboundProcessingService` 当前未直接注入 `RedisMessageBroker`，需新增。`RedisMessageBroker` 已在同包，无需额外 import。

1b. 修改 `processChat()` 签名，追加 `String inboundSource` 参数：

```java
    public InboundResult processChat(String businessDomain, String sessionType, String sessionId,
                                      String assistantAccount, String content, String msgType,
                                      String imageUrl, List<ImMessageRequest.ChatMessage> chatHistory,
                                      String inboundSource) {
```

1c. 在 `processChat()` 中所有 session 可用的位置写入标记。在情况 A（第 136 行附近）、情况 B（第 146 行附近）、情况 C（第 150 行附近），各自的 `return` 之前追加：

```java
        writeInvokeSource(session, inboundSource);
```

其中 helper 方法：
```java
    private void writeInvokeSource(SkillSession session, String inboundSource) {
        if (session != null && inboundSource != null && deliveryProperties.isWsMode()) {
            redisMessageBroker.setInvokeSource(
                    String.valueOf(session.getId()), inboundSource,
                    deliveryProperties.getInvokeSourceTtlSeconds());
        }
    }
```

1d. 同理修改 `processQuestionReply()` 和 `processPermissionReply()` 签名，追加 `String inboundSource`，在 session 可用后调用 `writeInvokeSource(session, inboundSource)`。

- [ ] **Step 2: Update ImInboundController**

修改 `receiveMessage()` 中调用 `processChat()` 的位置（第 61-69 行），追加最后一个参数 `"IM"`：

```java
        InboundResult result = processingService.processChat(
                request.businessDomain(),
                request.sessionType(),
                request.sessionId(),
                request.assistantAccount(),
                request.content(),
                request.msgType(),
                request.imageUrl(),
                request.chatHistory(),
                "IM");
```

- [ ] **Step 3: Update ExternalInboundController**

修改 `invoke()` 中的 switch 表达式（第 59-78 行），给 `processChat`、`processQuestionReply`、`processPermissionReply` 追加 `"EXTERNAL"` 参数：

```java
        InboundResult result = switch (request.getAction()) {
            case "chat" -> processingService.processChat(
                    request.getBusinessDomain(), request.getSessionType(),
                    request.getSessionId(), request.getAssistantAccount(),
                    request.payloadString("content"), request.payloadString("msgType"),
                    request.payloadString("imageUrl"), parseChatHistory(request.getPayload()),
                    "EXTERNAL");
            case "question_reply" -> processingService.processQuestionReply(
                    request.getBusinessDomain(), request.getSessionType(),
                    request.getSessionId(), request.getAssistantAccount(),
                    request.payloadString("content"), request.payloadString("toolCallId"),
                    request.payloadString("subagentSessionId"),
                    "EXTERNAL");
            case "permission_reply" -> processingService.processPermissionReply(
                    request.getBusinessDomain(), request.getSessionType(),
                    request.getSessionId(), request.getAssistantAccount(),
                    request.payloadString("permissionId"), request.payloadString("response"),
                    request.payloadString("subagentSessionId"),
                    "EXTERNAL");
            case "rebuild" -> processingService.processRebuild(
                    request.getBusinessDomain(), request.getSessionType(),
                    request.getSessionId(), request.getAssistantAccount());
            default -> InboundResult.error(400, "Unknown action: " + request.getAction());
        };
```

- [ ] **Step 4: Run full test suite to verify no regressions**

Run: `cd skill-server && mvn test -pl .`
Expected: PASS（如果有现有测试调用 processChat 的旧签名，需要追加参数 `null`）

- [ ] **Step 5: Commit**

```bash
git add skill-server/src/main/java/com/opencode/cui/skill/service/InboundProcessingService.java \
       skill-server/src/main/java/com/opencode/cui/skill/controller/ImInboundController.java \
       skill-server/src/main/java/com/opencode/cui/skill/controller/ExternalInboundController.java
git commit -m "feat: write invoke-source Redis marker from inbound controllers"
```

---

### Task 6: ImRestDeliveryStrategy — 去掉 hasActiveConnections 依赖

**Files:**
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/service/delivery/ImRestDeliveryStrategy.java`
- Modify: `skill-server/src/test/java/com/opencode/cui/skill/service/delivery/ImRestDeliveryStrategyTest.java`

- [ ] **Step 1: Update test**

替换整个测试文件：

```java
package com.opencode.cui.skill.service.delivery;

import com.opencode.cui.skill.model.SkillSession;
import com.opencode.cui.skill.model.StreamMessage;
import com.opencode.cui.skill.service.ImOutboundService;
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
    @InjectMocks private ImRestDeliveryStrategy strategy;

    @Test
    @DisplayName("supports IM domain regardless of WS connections")
    void supportsImDomain() {
        SkillSession session = SkillSession.builder().businessSessionDomain("im").build();
        assertTrue(strategy.supports(session));
    }

    @Test
    @DisplayName("does not support miniapp domain")
    void doesNotSupportMiniapp() {
        SkillSession session = SkillSession.builder().businessSessionDomain("miniapp").build();
        assertFalse(strategy.supports(session));
    }

    @Test
    @DisplayName("does not support non-IM non-miniapp domain")
    void doesNotSupportOtherDomain() {
        SkillSession session = SkillSession.builder().businessSessionDomain("custom").build();
        assertFalse(strategy.supports(session));
    }

    @Test
    @DisplayName("delivers text_done content via sendTextToIm")
    void deliversTextDone() {
        SkillSession session = SkillSession.builder()
                .businessSessionDomain("im").businessSessionType("direct")
                .businessSessionId("dm-001").assistantAccount("assist-01").build();
        StreamMessage msg = StreamMessage.builder()
                .type(StreamMessage.Types.TEXT_DONE).content("Hello from agent").build();
        strategy.deliver(session, "sess-1", "user-42", msg);
        verify(imOutboundService).sendTextToIm("direct", "dm-001", "Hello from agent", "assist-01");
    }

    @Test
    @DisplayName("does not call sendTextToIm for non-text types like DELTA")
    void ignoresNonTextTypes() {
        SkillSession session = SkillSession.builder()
                .businessSessionDomain("im").businessSessionType("direct")
                .businessSessionId("dm-001").assistantAccount("assist-01").build();
        StreamMessage msg = StreamMessage.builder()
                .type(StreamMessage.Types.TEXT_DELTA).content("partial").build();
        strategy.deliver(session, "sess-1", "user-42", msg);
        verify(imOutboundService, never()).sendTextToIm(any(), any(), any(), any());
    }

    @Test
    @DisplayName("order is 3")
    void orderIsThree() { assertEquals(3, strategy.order()); }
}
```

- [ ] **Step 2: Run test to verify it fails (ExternalStreamHandler dependency removed)**

Run: `cd skill-server && mvn test -pl . -Dtest=ImRestDeliveryStrategyTest`
Expected: FAIL — constructor has extra ExternalStreamHandler param

- [ ] **Step 3: Simplify ImRestDeliveryStrategy**

替换整个 `ImRestDeliveryStrategy.java`：

```java
package com.opencode.cui.skill.service.delivery;

import com.opencode.cui.skill.model.SkillSession;
import com.opencode.cui.skill.model.StreamMessage;
import com.opencode.cui.skill.service.ImOutboundService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class ImRestDeliveryStrategy implements OutboundDeliveryStrategy {

    private final ImOutboundService imOutboundService;

    public ImRestDeliveryStrategy(ImOutboundService imOutboundService) {
        this.imOutboundService = imOutboundService;
    }

    @Override
    public boolean supports(SkillSession session) {
        if (session == null || session.isMiniappDomain()) return false;
        return session.isImDomain();
    }

    @Override
    public int order() { return 3; }

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

    private String buildImText(StreamMessage msg) {
        if (msg == null) return null;
        return switch (msg.getType()) {
            case StreamMessage.Types.TEXT_DONE -> msg.getContent();
            case StreamMessage.Types.ERROR, StreamMessage.Types.SESSION_ERROR -> msg.getError();
            case StreamMessage.Types.PERMISSION_ASK ->
                    msg.getTitle() != null && !msg.getTitle().isBlank()
                            ? msg.getTitle() + "\n请回复: once / always / reject" : null;
            case StreamMessage.Types.QUESTION -> formatQuestionMessage(msg);
            default -> null;
        };
    }

    private String formatQuestionMessage(StreamMessage msg) {
        if (msg.getQuestionInfo() == null) return null;
        String status = msg.getStatus();
        if (status != null && !"running".equals(status) && !"pending".equals(status)) return null;
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

- [ ] **Step 4: Run test to verify it passes**

Run: `cd skill-server && mvn test -pl . -Dtest=ImRestDeliveryStrategyTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add skill-server/src/main/java/com/opencode/cui/skill/service/delivery/ImRestDeliveryStrategy.java \
       skill-server/src/test/java/com/opencode/cui/skill/service/delivery/ImRestDeliveryStrategyTest.java
git commit -m "refactor: remove ExternalStreamHandler dependency from ImRestDeliveryStrategy"
```

---

### Task 7: ExternalWsDeliveryStrategy — 三层精确投递

**Files:**
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/service/delivery/ExternalWsDeliveryStrategy.java`
- Modify: `skill-server/src/test/java/com/opencode/cui/skill/service/delivery/ExternalWsDeliveryStrategyTest.java`

- [ ] **Step 1: Update test**

替换整个测试文件：

```java
package com.opencode.cui.skill.service.delivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.model.SkillSession;
import com.opencode.cui.skill.model.StreamMessage;
import com.opencode.cui.skill.service.ExternalWsRegistry;
import com.opencode.cui.skill.service.ImOutboundService;
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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ExternalWsDeliveryStrategyTest {

    @Mock private ExternalStreamHandler externalStreamHandler;
    @Mock private ExternalWsRegistry wsRegistry;
    @Mock private RedisMessageBroker redisMessageBroker;
    @Mock private ImOutboundService imOutboundService;
    @Spy private ObjectMapper objectMapper = new ObjectMapper();
    @InjectMocks private ExternalWsDeliveryStrategy strategy;

    @Test
    @DisplayName("supports non-miniapp domain")
    void supportsNonMiniapp() {
        SkillSession session = SkillSession.builder().businessSessionDomain("im").build();
        assertTrue(strategy.supports(session));
    }

    @Test
    @DisplayName("does not support miniapp domain")
    void doesNotSupportMiniapp() {
        SkillSession session = SkillSession.builder().businessSessionDomain("miniapp").build();
        assertFalse(strategy.supports(session));
    }

    @Test
    @DisplayName("does not support null session")
    void doesNotSupportNull() {
        assertFalse(strategy.supports(null));
    }

    @Test
    @DisplayName("L1: delivers via local pushToOne when local connections exist")
    void l1LocalDelivery() throws Exception {
        SkillSession session = SkillSession.builder().businessSessionDomain("im").build();
        StreamMessage msg = StreamMessage.builder().type("text.done").content("hello").build();
        when(redisMessageBroker.nextStreamSeq("sess-1")).thenReturn(1L);
        when(externalStreamHandler.pushToOne(eq("im"), anyString())).thenReturn(true);

        strategy.deliver(session, "sess-1", "user-42", msg);

        verify(externalStreamHandler).pushToOne(eq("im"), anyString());
        verify(wsRegistry, never()).findInstanceWithConnection(any());
    }

    @Test
    @DisplayName("L2: relays to remote SS when no local connections")
    void l2RemoteRelay() throws Exception {
        SkillSession session = SkillSession.builder().businessSessionDomain("im").build();
        StreamMessage msg = StreamMessage.builder().type("text.done").content("hello").build();
        when(redisMessageBroker.nextStreamSeq("sess-1")).thenReturn(1L);
        when(externalStreamHandler.pushToOne(eq("im"), anyString())).thenReturn(false);
        when(wsRegistry.findInstanceWithConnection("im")).thenReturn("ss-pod-2");

        strategy.deliver(session, "sess-1", "user-42", msg);

        verify(redisMessageBroker).publishToChannel(eq("ss:external-relay:ss-pod-2"), anyString());
    }

    @Test
    @DisplayName("L3: falls back to ImRest for IM domain when no WS connections anywhere")
    void l3FallbackImRest() throws Exception {
        SkillSession session = SkillSession.builder()
                .businessSessionDomain("im").businessSessionType("direct")
                .businessSessionId("dm-001").assistantAccount("assist-01").build();
        StreamMessage msg = StreamMessage.builder()
                .type(StreamMessage.Types.TEXT_DONE).content("fallback msg").build();
        when(redisMessageBroker.nextStreamSeq("sess-1")).thenReturn(1L);
        when(externalStreamHandler.pushToOne(eq("im"), anyString())).thenReturn(false);
        when(wsRegistry.findInstanceWithConnection("im")).thenReturn(null);

        strategy.deliver(session, "sess-1", "user-42", msg);

        verify(imOutboundService).sendTextToIm("direct", "dm-001", "fallback msg", "assist-01");
    }

    @Test
    @DisplayName("L3: discards for non-IM domain when no WS connections anywhere")
    void l3DiscardNonIm() throws Exception {
        SkillSession session = SkillSession.builder().businessSessionDomain("custom").build();
        StreamMessage msg = StreamMessage.builder().type("text.done").content("hello").build();
        when(redisMessageBroker.nextStreamSeq("sess-1")).thenReturn(1L);
        when(externalStreamHandler.pushToOne(eq("custom"), anyString())).thenReturn(false);
        when(wsRegistry.findInstanceWithConnection("custom")).thenReturn(null);

        strategy.deliver(session, "sess-1", "user-42", msg);

        verify(imOutboundService, never()).sendTextToIm(any(), any(), any(), any());
        verify(redisMessageBroker, never()).publishToChannel(startsWith("ss:external-relay:"), any());
    }

    @Test
    @DisplayName("order is 2")
    void orderIsTwo() { assertEquals(2, strategy.order()); }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd skill-server && mvn test -pl . -Dtest=ExternalWsDeliveryStrategyTest`
Expected: FAIL — constructor mismatch

- [ ] **Step 3: Rewrite ExternalWsDeliveryStrategy with 3-layer routing**

替换整个文件：

```java
package com.opencode.cui.skill.service.delivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.model.SkillSession;
import com.opencode.cui.skill.model.StreamMessage;
import com.opencode.cui.skill.service.ExternalWsRegistry;
import com.opencode.cui.skill.service.ImOutboundService;
import com.opencode.cui.skill.service.RedisMessageBroker;
import com.opencode.cui.skill.ws.ExternalStreamHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class ExternalWsDeliveryStrategy implements OutboundDeliveryStrategy {

    private final ExternalStreamHandler externalStreamHandler;
    private final ExternalWsRegistry wsRegistry;
    private final RedisMessageBroker redisMessageBroker;
    private final ImOutboundService imOutboundService;
    private final ObjectMapper objectMapper;

    public ExternalWsDeliveryStrategy(ExternalStreamHandler externalStreamHandler,
                                       ExternalWsRegistry wsRegistry,
                                       RedisMessageBroker redisMessageBroker,
                                       ImOutboundService imOutboundService,
                                       ObjectMapper objectMapper) {
        this.externalStreamHandler = externalStreamHandler;
        this.wsRegistry = wsRegistry;
        this.redisMessageBroker = redisMessageBroker;
        this.imOutboundService = imOutboundService;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(SkillSession session) {
        if (session == null || session.isMiniappDomain()) return false;
        return true;
    }

    @Override
    public int order() { return 2; }

    @Override
    public void deliver(SkillSession session, String sessionId, String userId, StreamMessage msg) {
        String domain = session.getBusinessSessionDomain();
        try {
            if (sessionId != null) {
                msg.setSeq(redisMessageBroker.nextStreamSeq(sessionId));
            }
            String json = objectMapper.writeValueAsString(msg);

            // L1: 本地投递
            if (externalStreamHandler.pushToOne(domain, json)) {
                log.debug("[DELIVERY] ExternalWs-L1: sessionId={}, type={}, domain={}",
                        sessionId, msg.getType(), domain);
                return;
            }

            // L2: 跨 SS relay
            String targetInstance = wsRegistry.findInstanceWithConnection(domain);
            if (targetInstance != null) {
                String relayPayload = objectMapper.writeValueAsString(
                        java.util.Map.of("domain", domain, "payload", json));
                redisMessageBroker.publishToChannel(
                        "ss:external-relay:" + targetInstance, relayPayload);
                log.info("[DELIVERY] ExternalWs-L2: sessionId={}, type={}, domain={}, target={}",
                        sessionId, msg.getType(), domain, targetInstance);
                return;
            }

            // L3: 降级
            if (session.isImDomain()) {
                log.warn("[DELIVERY] ExternalWs-L3: no WS connections, falling back to ImRest: " +
                        "sessionId={}, domain={}", sessionId, domain);
                imOutboundService.sendTextToIm(
                        session.getBusinessSessionType(),
                        session.getBusinessSessionId(),
                        buildFallbackText(msg),
                        session.getAssistantAccount());
            } else {
                log.warn("[DELIVERY] ExternalWs-L3: no WS connections, discarding: " +
                        "sessionId={}, domain={}, type={}", sessionId, domain, msg.getType());
            }
        } catch (Exception e) {
            log.error("Failed to deliver external WS message: sessionId={}, domain={}, error={}",
                    sessionId, domain, e.getMessage());
        }
    }

    private String buildFallbackText(StreamMessage msg) {
        if (msg == null) return null;
        return switch (msg.getType()) {
            case StreamMessage.Types.TEXT_DONE -> msg.getContent();
            case StreamMessage.Types.ERROR, StreamMessage.Types.SESSION_ERROR -> msg.getError();
            default -> null;
        };
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd skill-server && mvn test -pl . -Dtest=ExternalWsDeliveryStrategyTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add skill-server/src/main/java/com/opencode/cui/skill/service/delivery/ExternalWsDeliveryStrategy.java \
       skill-server/src/test/java/com/opencode/cui/skill/service/delivery/ExternalWsDeliveryStrategyTest.java
git commit -m "feat: rewrite ExternalWsDeliveryStrategy with 3-layer precision routing"
```

---

### Task 8: OutboundDeliveryDispatcher — 开关 + invoke-source 路由

**Files:**
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/service/delivery/OutboundDeliveryDispatcher.java`
- Test: `skill-server/src/test/java/com/opencode/cui/skill/service/delivery/OutboundDeliveryDispatcherTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.opencode.cui.skill.service.delivery;

import com.opencode.cui.skill.config.DeliveryProperties;
import com.opencode.cui.skill.model.SkillSession;
import com.opencode.cui.skill.model.StreamMessage;
import com.opencode.cui.skill.service.RedisMessageBroker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OutboundDeliveryDispatcherTest {

    @Mock private MiniappDeliveryStrategy miniappStrategy;
    @Mock private ExternalWsDeliveryStrategy externalWsStrategy;
    @Mock private ImRestDeliveryStrategy imRestStrategy;
    @Mock private RedisMessageBroker redisMessageBroker;
    @Mock private DeliveryProperties deliveryProperties;

    private OutboundDeliveryDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        when(miniappStrategy.order()).thenReturn(1);
        when(externalWsStrategy.order()).thenReturn(2);
        when(imRestStrategy.order()).thenReturn(3);
        dispatcher = new OutboundDeliveryDispatcher(
                List.of(miniappStrategy, externalWsStrategy, imRestStrategy),
                deliveryProperties, redisMessageBroker);
    }

    @Test
    @DisplayName("miniapp domain always goes to miniapp strategy")
    void miniappRouting() {
        SkillSession session = SkillSession.builder().businessSessionDomain("miniapp").build();
        when(miniappStrategy.supports(session)).thenReturn(true);
        StreamMessage msg = StreamMessage.builder().type("text.done").build();
        dispatcher.deliver(session, "sess-1", "user-1", msg);
        verify(miniappStrategy).deliver(session, "sess-1", "user-1", msg);
    }

    @Test
    @DisplayName("mode=rest routes IM domain to ImRest")
    void modeRestImDomain() {
        when(deliveryProperties.isWsMode()).thenReturn(false);
        when(miniappStrategy.supports(any())).thenReturn(false);
        when(imRestStrategy.supports(any())).thenReturn(true);
        SkillSession session = SkillSession.builder().businessSessionDomain("im").build();
        StreamMessage msg = StreamMessage.builder().type("text.done").build();
        dispatcher.deliver(session, "sess-1", "user-1", msg);
        verify(imRestStrategy).deliver(session, "sess-1", "user-1", msg);
        verify(externalWsStrategy, never()).deliver(any(), any(), any(), any());
    }

    @Test
    @DisplayName("mode=ws + invoke-source=IM routes to ImRest")
    void modeWsInvokeSourceIm() {
        when(deliveryProperties.isWsMode()).thenReturn(true);
        when(deliveryProperties.getInvokeSourceTtlSeconds()).thenReturn(300);
        when(miniappStrategy.supports(any())).thenReturn(false);
        when(imRestStrategy.supports(any())).thenReturn(true);
        when(redisMessageBroker.getInvokeSource("sess-1")).thenReturn("IM");
        SkillSession session = SkillSession.builder().id(1L).businessSessionDomain("im").build();
        StreamMessage msg = StreamMessage.builder().type("text.done").build();
        dispatcher.deliver(session, "sess-1", "user-1", msg);
        verify(imRestStrategy).deliver(session, "sess-1", "user-1", msg);
        verify(externalWsStrategy, never()).deliver(any(), any(), any(), any());
    }

    @Test
    @DisplayName("mode=ws + invoke-source=EXTERNAL routes to ExternalWs")
    void modeWsInvokeSourceExternal() {
        when(deliveryProperties.isWsMode()).thenReturn(true);
        when(deliveryProperties.getInvokeSourceTtlSeconds()).thenReturn(300);
        when(miniappStrategy.supports(any())).thenReturn(false);
        when(externalWsStrategy.supports(any())).thenReturn(true);
        when(redisMessageBroker.getInvokeSource("sess-1")).thenReturn("EXTERNAL");
        SkillSession session = SkillSession.builder().id(1L).businessSessionDomain("im").build();
        StreamMessage msg = StreamMessage.builder().type("text.done").build();
        dispatcher.deliver(session, "sess-1", "user-1", msg);
        verify(externalWsStrategy).deliver(session, "sess-1", "user-1", msg);
        verify(imRestStrategy, never()).deliver(any(), any(), any(), any());
    }

    @Test
    @DisplayName("mode=ws + no invoke-source falls back to first-match")
    void modeWsNoInvokeSourceFallback() {
        when(deliveryProperties.isWsMode()).thenReturn(true);
        when(miniappStrategy.supports(any())).thenReturn(false);
        when(externalWsStrategy.supports(any())).thenReturn(true);
        when(redisMessageBroker.getInvokeSource("sess-1")).thenReturn(null);
        SkillSession session = SkillSession.builder().id(1L).businessSessionDomain("im").build();
        StreamMessage msg = StreamMessage.builder().type("text.done").build();
        dispatcher.deliver(session, "sess-1", "user-1", msg);
        verify(externalWsStrategy).deliver(session, "sess-1", "user-1", msg);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd skill-server && mvn test -pl . -Dtest=OutboundDeliveryDispatcherTest`
Expected: FAIL — constructor mismatch

- [ ] **Step 3: Rewrite OutboundDeliveryDispatcher**

替换整个文件：

```java
package com.opencode.cui.skill.service.delivery;

import com.opencode.cui.skill.config.DeliveryProperties;
import com.opencode.cui.skill.model.SkillSession;
import com.opencode.cui.skill.model.StreamMessage;
import com.opencode.cui.skill.service.RedisMessageBroker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

@Slf4j
@Component
public class OutboundDeliveryDispatcher {

    private final List<OutboundDeliveryStrategy> strategies;
    private final DeliveryProperties deliveryProperties;
    private final RedisMessageBroker redisMessageBroker;

    public OutboundDeliveryDispatcher(List<OutboundDeliveryStrategy> strategies,
                                       DeliveryProperties deliveryProperties,
                                       RedisMessageBroker redisMessageBroker) {
        this.strategies = strategies.stream()
                .sorted(Comparator.comparingInt(OutboundDeliveryStrategy::order))
                .toList();
        this.deliveryProperties = deliveryProperties;
        this.redisMessageBroker = redisMessageBroker;
        log.info("OutboundDeliveryDispatcher initialized with {} strategies: {}",
                this.strategies.size(),
                this.strategies.stream()
                        .map(s -> s.getClass().getSimpleName() + "(order=" + s.order() + ")")
                        .toList());
    }

    public void deliver(SkillSession session, String sessionId, String userId, StreamMessage msg) {
        // MiniApp 优先判断
        if (session != null && session.isMiniappDomain()) {
            deliverByFirstMatch(session, sessionId, userId, msg);
            return;
        }

        // mode=rest: 走 first-match（ImRest 会匹配 IM domain）
        if (!deliveryProperties.isWsMode()) {
            deliverByFirstMatch(session, sessionId, userId, msg);
            return;
        }

        // mode=ws: 读取 invoke-source 标记
        String invokeSource = redisMessageBroker.getInvokeSource(sessionId);
        if (invokeSource != null) {
            // 续期 TTL
            redisMessageBroker.expireInvokeSource(sessionId,
                    deliveryProperties.getInvokeSourceTtlSeconds());
        }

        if ("IM".equalsIgnoreCase(invokeSource)) {
            deliverByType(ImRestDeliveryStrategy.class, session, sessionId, userId, msg);
            return;
        }

        if ("EXTERNAL".equalsIgnoreCase(invokeSource)) {
            deliverByType(ExternalWsDeliveryStrategy.class, session, sessionId, userId, msg);
            return;
        }

        // 无标记：走 first-match 兜底
        log.debug("No invoke-source marker, falling back to first-match: sessionId={}", sessionId);
        deliverByFirstMatch(session, sessionId, userId, msg);
    }

    private void deliverByFirstMatch(SkillSession session, String sessionId,
                                      String userId, StreamMessage msg) {
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

    private void deliverByType(Class<? extends OutboundDeliveryStrategy> type,
                                SkillSession session, String sessionId,
                                String userId, StreamMessage msg) {
        for (OutboundDeliveryStrategy strategy : strategies) {
            if (type.isInstance(strategy)) {
                log.debug("Delivering via {} (invoke-source routed): sessionId={}, type={}",
                        strategy.getClass().getSimpleName(), sessionId,
                        msg != null ? msg.getType() : null);
                strategy.deliver(session, sessionId, userId, msg);
                return;
            }
        }
        log.warn("Strategy {} not found: sessionId={}", type.getSimpleName(), sessionId);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd skill-server && mvn test -pl . -Dtest=OutboundDeliveryDispatcherTest`
Expected: PASS

- [ ] **Step 5: Run full test suite**

Run: `cd skill-server && mvn test -pl .`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add skill-server/src/main/java/com/opencode/cui/skill/service/delivery/OutboundDeliveryDispatcher.java \
       skill-server/src/test/java/com/opencode/cui/skill/service/delivery/OutboundDeliveryDispatcherTest.java
git commit -m "feat: add config switch + invoke-source routing to OutboundDeliveryDispatcher"
```

---

### Task 9: 全量测试 + 回归验证

**Files:** (no new files)

- [ ] **Step 1: Run full skill-server test suite**

Run: `cd skill-server && mvn test -pl .`
Expected: PASS — all tests green

- [ ] **Step 2: Verify existing tests still pass**

重点检查：
- `MiniappDeliveryStrategyTest` — 不受影响
- `ImRestDeliveryStrategyTest` — 已更新，无 ExternalStreamHandler 依赖
- `ExternalWsDeliveryStrategyTest` — 已更新，三层路由
- `OutboundDeliveryDispatcherTest` — 已更新，开关 + invoke-source

Run: `cd skill-server && mvn test -pl . -Dtest="MiniappDeliveryStrategyTest,ImRestDeliveryStrategyTest,ExternalWsDeliveryStrategyTest,OutboundDeliveryDispatcherTest"`
Expected: PASS

- [ ] **Step 3: Compile check for ai-gateway (no changes expected)**

Run: `cd ai-gateway && mvn compile -pl .`
Expected: SUCCESS — ai-gateway 无改动，确认不影响
