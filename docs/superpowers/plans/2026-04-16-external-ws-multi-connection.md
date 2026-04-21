# External WS 多连接支持 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 允许同一个外部 pod 在同一个 SS 实例上建立多条 WS 连接，实现高可用冗余，发送失败自动重试下一条连接。

**Architecture:** 在 `ExternalStreamHandler` 中新增 `ConnectionPool` 静态内部类，将三层嵌套 `ConcurrentHashMap` 封装为干净的 API。移除旧的踢连接逻辑，适配 `afterConnectionEstablished`、`afterConnectionClosed`、`pushToOne`、`pushToSource`、`hasActiveConnections`、`checkHeartbeatTimeouts` 六个方法。`ExternalWsRegistry` 和 `ExternalWsDeliveryStrategy` 无需改动。

**Tech Stack:** Java 21, Spring WebSocket, JUnit 5, Mockito

**Spec:** `docs/superpowers/specs/2026-04-16-external-ws-multi-connection-design.md`

---

## File Map

| File | Action | Responsibility |
|------|--------|----------------|
| `skill-server/src/main/java/com/opencode/cui/skill/ws/ExternalStreamHandler.java` | Modify | 新增 `ConnectionPool` 内部类，重构所有连接池相关方法 |
| `skill-server/src/test/java/com/opencode/cui/skill/ws/ExternalStreamHandlerTest.java` | Modify | 新增多连接、重试、心跳遍历等测试用例；更新现有测试适配新行为 |

---

### Task 1: ConnectionPool 内部类 — 测试

**Files:**
- Modify: `skill-server/src/test/java/com/opencode/cui/skill/ws/ExternalStreamHandlerTest.java`

- [ ] **Step 1: 新增 ConnectionPool 单元测试**

在 `ExternalStreamHandlerTest.java` 文件末尾（`}` 之前）新增一个嵌套测试类，测试 `ConnectionPool` 的核心 API：

```java
@org.junit.jupiter.api.Nested
@DisplayName("ConnectionPool")
class ConnectionPoolTest {

    private ExternalStreamHandler.ConnectionPool pool;

    @BeforeEach
    void setUp() {
        pool = new ExternalStreamHandler.ConnectionPool();
    }

    @Test
    @DisplayName("add and countBySource tracks multiple sessions per instanceId")
    void addMultipleSessions() {
        WebSocketSession s1 = mockSession("s1", true);
        WebSocketSession s2 = mockSession("s2", true);
        pool.add("im", "im-1", s1);
        pool.add("im", "im-1", s2);
        assertEquals(2, pool.countBySource("im"));
    }

    @Test
    @DisplayName("add sessions from different instanceIds under same source")
    void addDifferentInstances() {
        WebSocketSession s1 = mockSession("s1", true);
        WebSocketSession s2 = mockSession("s2", true);
        pool.add("im", "im-1", s1);
        pool.add("im", "im-2", s2);
        assertEquals(2, pool.countBySource("im"));
    }

    @Test
    @DisplayName("remove by session reference decrements count")
    void removeBySession() {
        WebSocketSession s1 = mockSession("s1", true);
        WebSocketSession s2 = mockSession("s2", true);
        pool.add("im", "im-1", s1);
        pool.add("im", "im-1", s2);
        int remaining = pool.remove("im", "im-1", s1);
        assertEquals(1, remaining);
        assertEquals(1, pool.countBySource("im"));
    }

    @Test
    @DisplayName("remove last session returns 0 and cleans up source")
    void removeLastSession() {
        WebSocketSession s1 = mockSession("s1", true);
        pool.add("im", "im-1", s1);
        int remaining = pool.remove("im", "im-1", s1);
        assertEquals(0, remaining);
        assertEquals(0, pool.countBySource("im"));
        assertFalse(pool.hasActiveConnections("im"));
    }

    @Test
    @DisplayName("removeBySessionId removes by wsSessionId string")
    void removeBySessionId() {
        WebSocketSession s1 = mockSession("s1", true);
        WebSocketSession s2 = mockSession("s2", true);
        pool.add("im", "im-1", s1);
        pool.add("im", "im-1", s2);
        int remaining = pool.removeBySessionId("im", "im-1", "s1");
        assertEquals(1, remaining);
    }

    @Test
    @DisplayName("pickOne returns an open session")
    void pickOneReturnsOpen() {
        WebSocketSession s1 = mockSession("s1", false);
        WebSocketSession s2 = mockSession("s2", true);
        pool.add("im", "im-1", s1);
        pool.add("im", "im-1", s2);
        WebSocketSession picked = pool.pickOne("im");
        assertNotNull(picked);
        assertTrue(picked.isOpen());
    }

    @Test
    @DisplayName("pickOne returns null when no open sessions")
    void pickOneReturnsNullWhenAllClosed() {
        WebSocketSession s1 = mockSession("s1", false);
        pool.add("im", "im-1", s1);
        assertNull(pool.pickOne("im"));
    }

    @Test
    @DisplayName("pickOne returns null for unknown source")
    void pickOneUnknownSource() {
        assertNull(pool.pickOne("unknown"));
    }

    @Test
    @DisplayName("hasActiveConnections returns true only if open session exists")
    void hasActiveConnections() {
        WebSocketSession s1 = mockSession("s1", false);
        pool.add("im", "im-1", s1);
        assertFalse(pool.hasActiveConnections("im"));

        WebSocketSession s2 = mockSession("s2", true);
        pool.add("im", "im-1", s2);
        assertTrue(pool.hasActiveConnections("im"));
    }

    @Test
    @DisplayName("sources returns all registered sources")
    void sourcesReturnsAll() {
        pool.add("im", "im-1", mockSession("s1", true));
        pool.add("crm", "crm-1", mockSession("s2", true));
        assertEquals(2, pool.sources().size());
        assertTrue(pool.sources().contains("im"));
        assertTrue(pool.sources().contains("crm"));
    }

    @Test
    @DisplayName("countBySource returns 0 for unknown source")
    void countBySourceUnknown() {
        assertEquals(0, pool.countBySource("unknown"));
    }

    private WebSocketSession mockSession(String id, boolean open) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.isOpen()).thenReturn(open);
        return session;
    }
}
```

- [ ] **Step 2: 运行测试确认编译失败**

Run: `cd skill-server && mvn test -pl . -Dtest="ExternalStreamHandlerTest$ConnectionPoolTest" -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败，因为 `ExternalStreamHandler.ConnectionPool` 还不存在。

---

### Task 2: ConnectionPool 内部类 — 实现

**Files:**
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/ws/ExternalStreamHandler.java`

- [ ] **Step 1: 在 ExternalStreamHandler 类内部添加 ConnectionPool 静态内部类**

在 `ExternalStreamHandler.java` 文件尾部 `private record HandshakeAuth` 之前，添加：

```java
/**
 * 多连接池：source → { instanceId → { wsSessionId → WebSocketSession } }。
 * 封装并发安全的连接管理，对外暴露简洁 API。
 */
static class ConnectionPool {
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, ConcurrentHashMap<String, WebSocketSession>>> pool
            = new ConcurrentHashMap<>();

    void add(String source, String instanceId, WebSocketSession session) {
        pool.computeIfAbsent(source, k -> new ConcurrentHashMap<>())
            .computeIfAbsent(instanceId, k -> new ConcurrentHashMap<>())
            .put(session.getId(), session);
    }

    int remove(String source, String instanceId, WebSocketSession session) {
        var instances = pool.get(source);
        if (instances == null) return 0;
        var sessions = instances.get(instanceId);
        if (sessions != null) {
            sessions.remove(session.getId(), session);
            if (sessions.isEmpty()) instances.remove(instanceId);
        }
        if (instances.isEmpty()) pool.remove(source);
        return countBySource(source);
    }

    int removeBySessionId(String source, String instanceId, String wsSessionId) {
        var instances = pool.get(source);
        if (instances == null) return 0;
        var sessions = instances.get(instanceId);
        if (sessions != null) {
            sessions.remove(wsSessionId);
            if (sessions.isEmpty()) instances.remove(instanceId);
        }
        if (instances.isEmpty()) pool.remove(source);
        return countBySource(source);
    }

    WebSocketSession pickOne(String source) {
        var instances = pool.get(source);
        if (instances == null) return null;
        for (var sessions : instances.values()) {
            for (WebSocketSession session : sessions.values()) {
                if (session.isOpen()) return session;
            }
        }
        return null;
    }

    int countBySource(String source) {
        var instances = pool.get(source);
        if (instances == null) return 0;
        int count = 0;
        for (var sessions : instances.values()) {
            count += sessions.size();
        }
        return count;
    }

    boolean hasActiveConnections(String source) {
        return pickOne(source) != null;
    }

    java.util.Set<String> sources() {
        return pool.keySet();
    }

    void forEach(String source, java.util.function.BiConsumer<String, WebSocketSession> action) {
        var instances = pool.get(source);
        if (instances == null) return;
        for (var entry : instances.entrySet()) {
            String instanceId = entry.getKey();
            for (var sessionEntry : entry.getValue().entrySet()) {
                action.accept(instanceId + ":" + sessionEntry.getKey(), sessionEntry.getValue());
            }
        }
    }

    void forEachAll(java.util.function.Consumer<WebSocketSession> action) {
        for (var instances : pool.values()) {
            for (var sessions : instances.values()) {
                for (WebSocketSession session : sessions.values()) {
                    action.accept(session);
                }
            }
        }
    }
}
```

- [ ] **Step 2: 运行 ConnectionPool 测试确认通过**

Run: `cd skill-server && mvn test -pl . -Dtest="ExternalStreamHandlerTest$ConnectionPoolTest"`
Expected: 全部 PASS

- [ ] **Step 3: Commit**

```bash
git add skill-server/src/main/java/com/opencode/cui/skill/ws/ExternalStreamHandler.java skill-server/src/test/java/com/opencode/cui/skill/ws/ExternalStreamHandlerTest.java
git commit -m "feat(skill-server): add ConnectionPool inner class to ExternalStreamHandler"
```

---

### Task 3: 多连接行为测试 — Handler 层

**Files:**
- Modify: `skill-server/src/test/java/com/opencode/cui/skill/ws/ExternalStreamHandlerTest.java`

- [ ] **Step 1: 新增多连接 Handler 集成测试**

在 `ExternalStreamHandlerTest` 类中（`ConnectionPoolTest` 嵌套类之外），新增以下测试方法：

```java
@Test
@DisplayName("multiple connections from same source+instanceId coexist")
void multipleConnectionsCoexist() throws Exception {
    WebSocketSession s1 = mock(WebSocketSession.class);
    WebSocketSession s2 = mock(WebSocketSession.class);
    Map<String, Object> attrs1 = new HashMap<>(Map.of("source", "im", "instanceId", "im-1"));
    Map<String, Object> attrs2 = new HashMap<>(Map.of("source", "im", "instanceId", "im-1"));
    when(s1.getAttributes()).thenReturn(attrs1);
    when(s2.getAttributes()).thenReturn(attrs2);
    when(s1.getId()).thenReturn("ws-1");
    when(s2.getId()).thenReturn("ws-2");
    when(s1.isOpen()).thenReturn(true);
    when(s2.isOpen()).thenReturn(true);

    handler.afterConnectionEstablished(s1);
    handler.afterConnectionEstablished(s2);

    assertTrue(handler.hasActiveConnections("im"));
    // 注册调用：第一次 count=1，第二次 count=2
    verify(wsRegistry).register("im", 1);
    verify(wsRegistry).register("im", 2);
}

@Test
@DisplayName("closing one connection does not remove others")
void closingOneKeepsOthers() throws Exception {
    WebSocketSession s1 = mock(WebSocketSession.class);
    WebSocketSession s2 = mock(WebSocketSession.class);
    Map<String, Object> attrs1 = new HashMap<>(Map.of("source", "im", "instanceId", "im-1"));
    Map<String, Object> attrs2 = new HashMap<>(Map.of("source", "im", "instanceId", "im-1"));
    when(s1.getAttributes()).thenReturn(attrs1);
    when(s2.getAttributes()).thenReturn(attrs2);
    when(s1.getId()).thenReturn("ws-1");
    when(s2.getId()).thenReturn("ws-2");
    when(s1.isOpen()).thenReturn(true);
    when(s2.isOpen()).thenReturn(true);

    handler.afterConnectionEstablished(s1);
    handler.afterConnectionEstablished(s2);

    handler.afterConnectionClosed(s1, CloseStatus.NORMAL);

    assertTrue(handler.hasActiveConnections("im"));
    // 关闭后应更新 registry，count=1
    verify(wsRegistry).register("im", 1);
}

@Test
@DisplayName("pushToOne retries on send failure")
void pushToOneRetriesOnFailure() throws Exception {
    WebSocketSession s1 = mock(WebSocketSession.class);
    WebSocketSession s2 = mock(WebSocketSession.class);
    Map<String, Object> attrs1 = new HashMap<>(Map.of("source", "im", "instanceId", "im-1"));
    Map<String, Object> attrs2 = new HashMap<>(Map.of("source", "im", "instanceId", "im-1"));
    when(s1.getAttributes()).thenReturn(attrs1);
    when(s2.getAttributes()).thenReturn(attrs2);
    when(s1.getId()).thenReturn("ws-fail");
    when(s2.getId()).thenReturn("ws-ok");
    when(s1.isOpen()).thenReturn(true);
    when(s2.isOpen()).thenReturn(true);

    handler.afterConnectionEstablished(s1);
    handler.afterConnectionEstablished(s2);

    // s1 发送抛异常，s2 发送成功
    doThrow(new RuntimeException("connection reset")).when(s1).sendMessage(any(TextMessage.class));

    boolean result = handler.pushToOne("im", "{\"type\":\"test\"}");
    assertTrue(result);
    // s2 应该收到消息
    verify(s2).sendMessage(any(TextMessage.class));
}

@Test
@DisplayName("pushToOne returns false when all connections fail")
void pushToOneAllFail() throws Exception {
    WebSocketSession s1 = mock(WebSocketSession.class);
    Map<String, Object> attrs1 = new HashMap<>(Map.of("source", "im", "instanceId", "im-1"));
    when(s1.getAttributes()).thenReturn(attrs1);
    when(s1.getId()).thenReturn("ws-fail");
    when(s1.isOpen()).thenReturn(true);

    handler.afterConnectionEstablished(s1);

    doThrow(new RuntimeException("broken")).when(s1).sendMessage(any(TextMessage.class));

    boolean result = handler.pushToOne("im", "{\"type\":\"test\"}");
    assertFalse(result);
}
```

- [ ] **Step 2: 运行测试确认编译失败或测试失败**

Run: `cd skill-server && mvn test -pl . -Dtest="ExternalStreamHandlerTest" -Dtest="ExternalStreamHandlerTest#multipleConnectionsCoexist+ExternalStreamHandlerTest#closingOneKeepsOthers+ExternalStreamHandlerTest#pushToOneRetriesOnFailure+ExternalStreamHandlerTest#pushToOneAllFail"`
Expected: FAIL — 旧的 `afterConnectionEstablished` 会踢掉 s1，导致 `multipleConnectionsCoexist` 断言 `register("im", 2)` 失败。

---

### Task 4: 重构 ExternalStreamHandler 使用 ConnectionPool

**Files:**
- Modify: `skill-server/src/main/java/com/opencode/cui/skill/ws/ExternalStreamHandler.java`

- [ ] **Step 1: 替换连接池字段**

将第 45-46 行：

```java
/** source → { instanceId → WebSocketSession } */
private final Map<String, Map<String, WebSocketSession>> connectionPool = new ConcurrentHashMap<>();
```

替换为：

```java
private final ConnectionPool connectionPool = new ConnectionPool();
```

- [ ] **Step 2: 重写 afterConnectionEstablished**

将第 82-111 行的整个方法替换为：

```java
@Override
public void afterConnectionEstablished(WebSocketSession session) {
    String source = (String) session.getAttributes().get(SOURCE_ATTR);
    String instanceId = (String) session.getAttributes().get(INSTANCE_ID_ATTR);

    connectionPool.add(source, instanceId, session);
    lastActivity.put(session.getId(), Instant.now());

    String channel = CHANNEL_PREFIX + source;
    if (!redisMessageBroker.isChannelSubscribed(channel)) {
        redisMessageBroker.subscribeToChannel(channel, msg -> handleRedisMessage(source, msg));
    }
    log.info("External WS connected: source={}, instanceId={}, sessionId={}", source, instanceId, session.getId());
    wsRegistry.register(source, connectionPool.countBySource(source));
}
```

- [ ] **Step 3: 重写 afterConnectionClosed**

将第 127-146 行的整个方法替换为：

```java
@Override
public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
    String source = (String) session.getAttributes().get(SOURCE_ATTR);
    String instanceId = (String) session.getAttributes().get(INSTANCE_ID_ATTR);

    int remaining = connectionPool.remove(source, instanceId, session);
    lastActivity.remove(session.getId());

    if (remaining == 0) {
        redisMessageBroker.unsubscribeFromChannel(CHANNEL_PREFIX + source);
        wsRegistry.unregister(source);
    } else {
        wsRegistry.register(source, remaining);
    }
    log.info("External WS disconnected: source={}, instanceId={}, sessionId={}, status={}, remaining={}",
            source, instanceId, session.getId(), status, remaining);
}
```

- [ ] **Step 4: 重写 hasActiveConnections**

将第 154-158 行替换为：

```java
public boolean hasActiveConnections(String source) {
    return connectionPool.hasActiveConnections(source);
}
```

- [ ] **Step 5: 重写 pushToSource**

将第 160-178 行替换为：

```java
public void pushToSource(String source, String message) {
    if (!connectionPool.hasActiveConnections(source)) {
        log.warn("No active connections for source: {}", source);
        return;
    }
    TextMessage textMessage = new TextMessage(message);
    connectionPool.forEach(source, (key, session) -> {
        if (session.isOpen()) {
            try {
                synchronized (session) { session.sendMessage(textMessage); }
            } catch (Exception e) {
                log.error("Failed to push to external WS: source={}, key={}, error={}",
                        source, key, e.getMessage());
            }
        }
    });
}
```

- [ ] **Step 6: 重写 pushToOne（含发送失败重试）**

将第 180-204 行替换为：

```java
/**
 * 精确投递：选择一条活跃 WS 连接推送消息。
 * 发送失败时自动移除该连接并重试下一条，最多 3 次。
 *
 * @return true 如果成功推送，false 如果无可用连接
 */
public boolean pushToOne(String source, String message) {
    TextMessage textMessage = new TextMessage(message);
    int maxRetries = 3;
    for (int i = 0; i < maxRetries; i++) {
        WebSocketSession session = connectionPool.pickOne(source);
        if (session == null) return false;
        try {
            synchronized (session) { session.sendMessage(textMessage); }
            return true;
        } catch (Exception e) {
            log.warn("pushToOne failed, removing session and retrying: source={}, sessionId={}, attempt={}/{}",
                    source, session.getId(), i + 1, maxRetries);
            connectionPool.removeBySessionId(source,
                    (String) session.getAttributes().get(INSTANCE_ID_ATTR),
                    session.getId());
        }
    }
    return false;
}
```

- [ ] **Step 7: 重写 checkHeartbeatTimeouts**

将第 228-248 行替换为：

```java
@Scheduled(fixedRate = 30_000)
public void checkHeartbeatTimeouts() {
    Instant timeout = Instant.now().minusSeconds(60);
    connectionPool.forEachAll(session -> {
        Instant last = lastActivity.get(session.getId());
        if (last != null && last.isBefore(timeout) && session.isOpen()) {
            try {
                log.warn("Closing external WS due to heartbeat timeout: sessionId={}", session.getId());
                session.close(CloseStatus.GOING_AWAY);
            } catch (Exception e) {
                log.error("Failed to close timed-out session: {}", e.getMessage());
            }
        }
    });
    for (String source : connectionPool.sources()) {
        wsRegistry.heartbeat(source);
    }
}
```

- [ ] **Step 8: 运行全部测试**

Run: `cd skill-server && mvn test -pl . -Dtest="ExternalStreamHandlerTest"`
Expected: 全部 PASS

- [ ] **Step 9: Commit**

```bash
git add skill-server/src/main/java/com/opencode/cui/skill/ws/ExternalStreamHandler.java skill-server/src/test/java/com/opencode/cui/skill/ws/ExternalStreamHandlerTest.java
git commit -m "refactor(skill-server): replace raw connectionPool map with ConnectionPool class, support multi-connection per pod with send-failure retry"
```

---

### Task 5: 更新现有测试适配新行为

**Files:**
- Modify: `skill-server/src/test/java/com/opencode/cui/skill/ws/ExternalStreamHandlerTest.java`

- [ ] **Step 1: 验证现有测试是否已通过**

现有测试 `validHandshakeAccepted`、`invalidTokenRejected`、`hasActiveConnectionsAfterConnect`、`noActiveConnectionsAfterClose`、`pingReceivesPong` 不涉及踢连接逻辑，应该已全部通过。运行确认：

Run: `cd skill-server && mvn test -pl . -Dtest="ExternalStreamHandlerTest"`
Expected: 全部 PASS（如果有失败，需要检查 `verify(wsRegistry).register("im", 1)` 之类的调用次数，因为现有测试中 `hasActiveConnectionsAfterConnect` 的 `register` 调用参数可能需要调整）

- [ ] **Step 2: 如有失败，修复 verify 次数问题**

`hasActiveConnectionsAfterConnect` 测试中原来调用 `wsRegistry.register("im", 1)` 一次。新实现下仍然只有一条连接建立，所以 `register("im", 1)` 仍然只调用一次，不需要修改。

`noActiveConnectionsAfterClose` 测试中 `afterConnectionClosed` 后 remaining=0，会调用 `wsRegistry.unregister("im")`。原测试没有 verify registry 调用，所以不需要修改。

Run: `cd skill-server && mvn test -pl . -Dtest="ExternalStreamHandlerTest"`
Expected: 全部 PASS

- [ ] **Step 3: Commit（如有改动）**

```bash
git add skill-server/src/test/java/com/opencode/cui/skill/ws/ExternalStreamHandlerTest.java
git commit -m "test(skill-server): update ExternalStreamHandler tests for multi-connection behavior"
```

---

### Task 6: 全量构建验证

**Files:** 无新增改动

- [ ] **Step 1: 运行 skill-server 全量测试**

Run: `cd skill-server && mvn test`
Expected: BUILD SUCCESS，全部测试通过

- [ ] **Step 2: 运行编译检查（无 warning）**

Run: `cd skill-server && mvn compile -Xlint:all`
Expected: 编译成功
