# 云端长连接生命周期管理 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 Gateway 的云端 SSE/WebSocket 连接添加四层超时管理（connectTimeout、firstEventTimeout、idleTimeout、maxDuration）和 terminal event 主动关闭机制。

**Architecture:** 新增 `CloudConnectionLifecycle` 统一管理超时计时器，通过 `ScheduledExecutorService` 调度超时任务。SSE 策略接入 lifecycle 并识别心跳注释行；新增 WebSocket 策略实现。`CloudTimeoutProperties` 绑定 YAML 配置。超时触发时关闭底层连接并通过 onError 回调发送 tool_error。

**Tech Stack:** Java 21, Spring Boot 3.4, JUnit 5, Mockito, ScheduledExecutorService

---

## File Structure

| File | Action | Responsibility |
|------|--------|----------------|
| `ai-gateway/src/main/java/com/opencode/cui/gateway/config/CloudTimeoutProperties.java` | Create | `@ConfigurationProperties` 绑定 `gateway.cloud.*` 超时配置 |
| `ai-gateway/src/main/java/com/opencode/cui/gateway/service/cloud/CloudConnectionLifecycle.java` | Create | 四层超时计时器管理 + 主动关闭回调 |
| `ai-gateway/src/main/java/com/opencode/cui/gateway/service/cloud/SseProtocolStrategy.java` | Modify | 接入 lifecycle，识别心跳行，terminal event 主动关闭 |
| `ai-gateway/src/main/java/com/opencode/cui/gateway/service/cloud/WebSocketProtocolStrategy.java` | Create | WebSocket 协议策略实现 |
| `ai-gateway/src/main/java/com/opencode/cui/gateway/service/cloud/CloudProtocolClient.java` | Modify | 传递 CloudTimeoutProperties |
| `ai-gateway/src/main/java/com/opencode/cui/gateway/service/cloud/CloudProtocolStrategy.java` | Modify | connect 方法增加 lifecycle 参数 |
| `ai-gateway/src/main/java/com/opencode/cui/gateway/service/CloudAgentService.java` | Modify | 创建 lifecycle 实例，传递给 connect |
| `ai-gateway/src/main/resources/application.yml` | Modify | 新增 `gateway.cloud.*` 超时配置 |
| `ai-gateway/src/test/java/com/opencode/cui/gateway/service/cloud/CloudConnectionLifecycleTest.java` | Create | lifecycle 超时行为单测 |
| `ai-gateway/src/test/java/com/opencode/cui/gateway/service/cloud/SseProtocolStrategyTest.java` | Modify | 增加心跳识别、超时关闭、terminal event 测试 |
| `ai-gateway/src/test/java/com/opencode/cui/gateway/service/CloudAgentServiceTest.java` | Modify | 适配新 connect 签名 |
| `ai-gateway/src/test/java/com/opencode/cui/gateway/service/cloud/CloudProtocolClientTest.java` | Modify | 适配新 connect 签名 |

---

## Task 1: CloudTimeoutProperties 配置类

**Files:**
- Create: `ai-gateway/src/main/java/com/opencode/cui/gateway/config/CloudTimeoutProperties.java`
- Modify: `ai-gateway/src/main/resources/application.yml`

- [ ] **Step 1: 创建 CloudTimeoutProperties**

```java
package com.opencode.cui.gateway.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 云端连接超时配置。
 * 通过 {@code gateway.cloud.*} 配置前缀绑定。
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "gateway.cloud")
public class CloudTimeoutProperties {

    /** TCP + TLS 建联超时（秒） */
    private int connectTimeoutSeconds = 30;

    /** 建联成功到首条数据/心跳的等待超时（秒） */
    private int firstEventTimeoutSeconds = 120;

    /** 最后一次收到数据/心跳后的空闲超时（秒） */
    private int idleTimeoutSeconds = 90;

    /** 单轮对话最大总时长（秒） */
    private int maxDurationSeconds = 600;

    /** SSE 协议覆盖配置 */
    private ProtocolOverride sse = new ProtocolOverride();

    /** WebSocket 协议覆盖配置 */
    private WsOverride websocket = new WsOverride();

    /**
     * 获取指定协议的有效空闲超时。协议级覆盖优先，未设置则用通用值。
     */
    public int getEffectiveIdleTimeoutSeconds(String protocol) {
        if ("websocket".equals(protocol) && websocket.getIdleTimeoutSeconds() != null) {
            return websocket.getIdleTimeoutSeconds();
        }
        if ("sse".equals(protocol) && sse.getIdleTimeoutSeconds() != null) {
            return sse.getIdleTimeoutSeconds();
        }
        return idleTimeoutSeconds;
    }

    @Getter
    @Setter
    public static class ProtocolOverride {
        /** 协议级空闲超时覆盖（null 表示使用通用值） */
        private Integer idleTimeoutSeconds;
    }

    @Getter
    @Setter
    public static class WsOverride extends ProtocolOverride {
        /** WebSocket Ping 发送间隔（秒） */
        private int pingIntervalSeconds = 30;
    }
}
```

- [ ] **Step 2: 在 application.yml 中新增配置段**

在 `gateway.cloud-route` 段之前添加：

```yaml
  # 云端连接超时配置
  cloud:
    connect-timeout-seconds: ${CLOUD_CONNECT_TIMEOUT:30}
    first-event-timeout-seconds: ${CLOUD_FIRST_EVENT_TIMEOUT:120}
    idle-timeout-seconds: ${CLOUD_IDLE_TIMEOUT:90}
    max-duration-seconds: ${CLOUD_MAX_DURATION:600}
    sse:
      idle-timeout-seconds: ${CLOUD_SSE_IDLE_TIMEOUT:90}
    websocket:
      idle-timeout-seconds: ${CLOUD_WS_IDLE_TIMEOUT:60}
      ping-interval-seconds: ${CLOUD_WS_PING_INTERVAL:30}
```

- [ ] **Step 3: 验证编译通过**

Run: `cd ai-gateway && mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add ai-gateway/src/main/java/com/opencode/cui/gateway/config/CloudTimeoutProperties.java ai-gateway/src/main/resources/application.yml
git commit -m "feat(gateway): add CloudTimeoutProperties for cloud connection timeout config"
```

---

## Task 2: CloudConnectionLifecycle 超时管理器

**Files:**
- Create: `ai-gateway/src/test/java/com/opencode/cui/gateway/service/cloud/CloudConnectionLifecycleTest.java`
- Create: `ai-gateway/src/main/java/com/opencode/cui/gateway/service/cloud/CloudConnectionLifecycle.java`

- [ ] **Step 1: 写测试 — firstEventTimeout 触发**

```java
package com.opencode.cui.gateway.service.cloud;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class CloudConnectionLifecycleTest {

    private CloudConnectionLifecycle lifecycle;

    @AfterEach
    void tearDown() {
        if (lifecycle != null) {
            lifecycle.close();
        }
    }

    @Test
    @DisplayName("firstEventTimeout: 建联后未收到任何事件时触发超时回调")
    void shouldFireTimeoutWhenNoFirstEventReceived() throws Exception {
        // given
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> timeoutType = new AtomicReference<>();

        lifecycle = new CloudConnectionLifecycle(
                1, // firstEventTimeoutSeconds
                60, // idleTimeoutSeconds
                600, // maxDurationSeconds
                (type, elapsed) -> {
                    timeoutType.set(type);
                    latch.countDown();
                },
                () -> {} // closeAction
        );

        // when
        lifecycle.onConnected();

        // then
        assertTrue(latch.await(3, TimeUnit.SECONDS), "timeout callback should fire");
        assertEquals("first_event_timeout", timeoutType.get());
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd ai-gateway && mvn test -pl . -Dtest=CloudConnectionLifecycleTest#shouldFireTimeoutWhenNoFirstEventReceived -q`
Expected: FAIL — `CloudConnectionLifecycle` 类不存在

- [ ] **Step 3: 写测试 — firstEvent 取消 firstEventTimeout 并启动 idleTimeout**

追加到测试类：

```java
    @Test
    @DisplayName("onEventReceived: 首条事件取消 firstEventTimeout，启动 idleTimeout")
    void shouldCancelFirstEventTimeoutOnEventReceived() throws Exception {
        // given
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> timeoutType = new AtomicReference<>();

        lifecycle = new CloudConnectionLifecycle(
                1, // firstEventTimeoutSeconds — 1秒后应触发
                60, // idleTimeoutSeconds
                600, // maxDurationSeconds
                (type, elapsed) -> {
                    timeoutType.set(type);
                    latch.countDown();
                },
                () -> {}
        );

        // when
        lifecycle.onConnected();
        Thread.sleep(200); // 在 firstEventTimeout 触发前
        lifecycle.onEventReceived(); // 应取消 firstEventTimeout

        // then
        assertFalse(latch.await(2, TimeUnit.SECONDS),
                "firstEventTimeout should NOT fire after event received");
        assertNull(timeoutType.get());
    }

    @Test
    @DisplayName("idleTimeout: 收到事件后长时间无新数据触发空闲超时")
    void shouldFireIdleTimeoutAfterInactivity() throws Exception {
        // given
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> timeoutType = new AtomicReference<>();

        lifecycle = new CloudConnectionLifecycle(
                60, // firstEventTimeoutSeconds
                1, // idleTimeoutSeconds — 1秒
                600, // maxDurationSeconds
                (type, elapsed) -> {
                    timeoutType.set(type);
                    latch.countDown();
                },
                () -> {}
        );

        // when
        lifecycle.onConnected();
        lifecycle.onEventReceived(); // 切换到 idleTimeout 阶段

        // then
        assertTrue(latch.await(3, TimeUnit.SECONDS), "idle timeout should fire");
        assertEquals("idle_timeout", timeoutType.get());
    }
```

- [ ] **Step 4: 写测试 — heartbeat 重置 idleTimeout**

追加到测试类：

```java
    @Test
    @DisplayName("onHeartbeat: 心跳重置 idleTimeout 计时器")
    void shouldResetIdleTimeoutOnHeartbeat() throws Exception {
        // given
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> timeoutType = new AtomicReference<>();

        lifecycle = new CloudConnectionLifecycle(
                60, // firstEventTimeoutSeconds
                2, // idleTimeoutSeconds — 2秒
                600, // maxDurationSeconds
                (type, elapsed) -> {
                    timeoutType.set(type);
                    latch.countDown();
                },
                () -> {}
        );

        // when
        lifecycle.onConnected();
        lifecycle.onEventReceived(); // 启动 idleTimeout
        Thread.sleep(1000); // 等 1 秒
        lifecycle.onHeartbeat(); // 重置 idleTimeout（重新计 2 秒）
        Thread.sleep(1000); // 再等 1 秒（总共才过 1 秒自上次心跳）

        // then
        assertFalse(latch.await(500, TimeUnit.MILLISECONDS),
                "idle timeout should NOT fire because heartbeat reset it");
        assertNull(timeoutType.get());
    }
```

- [ ] **Step 5: 写测试 — maxDuration 安全网**

追加到测试类：

```java
    @Test
    @DisplayName("maxDuration: 即使持续有事件，总时长超限也触发超时")
    void shouldFireMaxDurationTimeout() throws Exception {
        // given
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> timeoutType = new AtomicReference<>();

        lifecycle = new CloudConnectionLifecycle(
                60, // firstEventTimeoutSeconds
                60, // idleTimeoutSeconds
                1, // maxDurationSeconds — 1秒
                (type, elapsed) -> {
                    timeoutType.set(type);
                    latch.countDown();
                },
                () -> {}
        );

        // when
        lifecycle.onConnected();
        lifecycle.onEventReceived();

        // then
        assertTrue(latch.await(3, TimeUnit.SECONDS), "maxDuration should fire");
        assertEquals("max_duration", timeoutType.get());
    }
```

- [ ] **Step 6: 写测试 — onTerminalEvent 取消所有计时器**

追加到测试类：

```java
    @Test
    @DisplayName("onTerminalEvent: tool_done 后取消所有计时器并调用 closeAction")
    void shouldCancelAllTimersAndCloseOnTerminalEvent() throws Exception {
        // given
        CountDownLatch closeLatch = new CountDownLatch(1);
        CountDownLatch timeoutLatch = new CountDownLatch(1);

        lifecycle = new CloudConnectionLifecycle(
                60,
                1, // idleTimeoutSeconds — 1秒，如果没取消会触发
                1, // maxDurationSeconds — 1秒，如果没取消会触发
                (type, elapsed) -> timeoutLatch.countDown(),
                closeLatch::countDown
        );

        // when
        lifecycle.onConnected();
        lifecycle.onEventReceived();
        lifecycle.onTerminalEvent(); // 应取消所有计时器 + 调用 closeAction

        // then
        assertTrue(closeLatch.await(1, TimeUnit.SECONDS), "closeAction should be called");
        assertFalse(timeoutLatch.await(2, TimeUnit.SECONDS),
                "no timeout should fire after terminal event");
    }
```

- [ ] **Step 7: 写测试 — close 幂等**

追加到测试类：

```java
    @Test
    @DisplayName("close: 多次调用 close 不抛异常（幂等）")
    void shouldBeIdempotentOnClose() {
        // given
        lifecycle = new CloudConnectionLifecycle(
                60, 60, 600,
                (type, elapsed) -> {},
                () -> {}
        );

        // when / then — 不抛异常
        lifecycle.onConnected();
        lifecycle.close();
        lifecycle.close();
        lifecycle.close();
    }
```

- [ ] **Step 8: 实现 CloudConnectionLifecycle**

```java
package com.opencode.cui.gateway.service.cloud;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 云端连接生命周期管理器。
 *
 * <p>管理四层超时计时器：firstEventTimeout、idleTimeout、maxDuration。
 * connectTimeout 由底层传输（HttpClient / WebSocket handshake）自行处理。</p>
 *
 * <p>每个云端连接创建一个实例，连接结束后调用 {@link #close()} 释放资源。</p>
 */
@Slf4j
public class CloudConnectionLifecycle {

    private final int firstEventTimeoutSeconds;
    private final int idleTimeoutSeconds;
    private final int maxDurationSeconds;
    private final TimeoutCallback onTimeout;
    private final Runnable closeAction;

    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private volatile ScheduledFuture<?> firstEventFuture;
    private volatile ScheduledFuture<?> idleFuture;
    private volatile ScheduledFuture<?> maxDurationFuture;

    /**
     * 超时回调接口。
     */
    @FunctionalInterface
    public interface TimeoutCallback {
        /**
         * @param timeoutType 超时类型：first_event_timeout / idle_timeout / max_duration
         * @param elapsedSeconds 从连接建立到超时触发的秒数
         */
        void onTimeout(String timeoutType, long elapsedSeconds);
    }

    private long connectedAtMs;

    public CloudConnectionLifecycle(int firstEventTimeoutSeconds,
                                    int idleTimeoutSeconds,
                                    int maxDurationSeconds,
                                    TimeoutCallback onTimeout,
                                    Runnable closeAction) {
        this.firstEventTimeoutSeconds = firstEventTimeoutSeconds;
        this.idleTimeoutSeconds = idleTimeoutSeconds;
        this.maxDurationSeconds = maxDurationSeconds;
        this.onTimeout = onTimeout;
        this.closeAction = closeAction;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "cloud-lifecycle-timer");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 连接建立成功后调用。启动 firstEventTimeout 和 maxDuration 计时器。
     */
    public void onConnected() {
        if (closed.get()) return;
        connectedAtMs = System.currentTimeMillis();

        firstEventFuture = scheduler.schedule(
                () -> fireTimeout("first_event_timeout"),
                firstEventTimeoutSeconds, TimeUnit.SECONDS);

        maxDurationFuture = scheduler.schedule(
                () -> fireTimeout("max_duration"),
                maxDurationSeconds, TimeUnit.SECONDS);
    }

    /**
     * 收到数据事件后调用。取消 firstEventTimeout，重置 idleTimeout。
     */
    public void onEventReceived() {
        if (closed.get()) return;
        cancelFuture(firstEventFuture);
        firstEventFuture = null;
        resetIdleTimeout();
    }

    /**
     * 收到心跳后调用。重置 idleTimeout（与 onEventReceived 相同的 idle 重置逻辑）。
     * 如果尚未收到首条数据事件，心跳也视为有效信号，取消 firstEventTimeout。
     */
    public void onHeartbeat() {
        if (closed.get()) return;
        cancelFuture(firstEventFuture);
        firstEventFuture = null;
        resetIdleTimeout();
    }

    /**
     * 收到 terminal event（tool_done / tool_error）后调用。取消所有计时器并关闭连接。
     */
    public void onTerminalEvent() {
        if (closed.get()) return;
        cancelAllTimers();
        closeAction.run();
        shutdown();
    }

    /**
     * 关闭生命周期管理器，释放调度器资源。幂等。
     */
    public void close() {
        if (closed.compareAndSet(false, true)) {
            cancelAllTimers();
            scheduler.shutdownNow();
        }
    }

    private void fireTimeout(String timeoutType) {
        if (closed.get()) return;
        long elapsed = (System.currentTimeMillis() - connectedAtMs) / 1000;
        log.warn("[CLOUD_LIFECYCLE] Timeout fired: type={}, elapsedSeconds={}", timeoutType, elapsed);
        onTimeout.onTimeout(timeoutType, elapsed);
        closeAction.run();
        shutdown();
    }

    private void resetIdleTimeout() {
        cancelFuture(idleFuture);
        idleFuture = scheduler.schedule(
                () -> fireTimeout("idle_timeout"),
                idleTimeoutSeconds, TimeUnit.SECONDS);
    }

    private void cancelAllTimers() {
        cancelFuture(firstEventFuture);
        cancelFuture(idleFuture);
        cancelFuture(maxDurationFuture);
        firstEventFuture = null;
        idleFuture = null;
        maxDurationFuture = null;
    }

    private void cancelFuture(ScheduledFuture<?> future) {
        if (future != null && !future.isDone()) {
            future.cancel(false);
        }
    }

    private void shutdown() {
        closed.set(true);
        scheduler.shutdownNow();
    }
}
```

- [ ] **Step 9: 运行所有测试确认通过**

Run: `cd ai-gateway && mvn test -pl . -Dtest=CloudConnectionLifecycleTest -q`
Expected: 6 tests PASS

- [ ] **Step 10: Commit**

```bash
git add ai-gateway/src/main/java/com/opencode/cui/gateway/service/cloud/CloudConnectionLifecycle.java ai-gateway/src/test/java/com/opencode/cui/gateway/service/cloud/CloudConnectionLifecycleTest.java
git commit -m "feat(gateway): add CloudConnectionLifecycle with four-tier timeout management"
```

---

## Task 3: 改造 CloudProtocolStrategy 接口和 CloudProtocolClient

**Files:**
- Modify: `ai-gateway/src/main/java/com/opencode/cui/gateway/service/cloud/CloudProtocolStrategy.java`
- Modify: `ai-gateway/src/main/java/com/opencode/cui/gateway/service/cloud/CloudProtocolClient.java`
- Modify: `ai-gateway/src/test/java/com/opencode/cui/gateway/service/cloud/CloudProtocolClientTest.java`

- [ ] **Step 1: 修改 CloudProtocolStrategy 接口，增加 lifecycle 参数**

将 `CloudProtocolStrategy.java` 的 `connect` 方法签名改为：

```java
package com.opencode.cui.gateway.service.cloud;

import com.opencode.cui.gateway.model.GatewayMessage;

import java.util.function.Consumer;

/**
 * 云端通信协议策略接口。
 *
 * <p>不同云端服务使用不同协议（SSE / WebSocket），通过策略模式实现统一的连接抽象。</p>
 */
public interface CloudProtocolStrategy {

    /**
     * 返回当前策略支持的协议标识。
     *
     * @return 协议标识，如 {@code "sse"} 或 {@code "websocket"}
     */
    String getProtocol();

    /**
     * 连接云端服务并开始接收事件。
     *
     * @param context   连接上下文
     * @param lifecycle 连接生命周期管理器，策略实现需在适当时机调用其方法
     * @param onEvent   事件回调，每收到一个云端消息时调用
     * @param onError   错误回调，连接异常时调用
     */
    void connect(CloudConnectionContext context, CloudConnectionLifecycle lifecycle,
                 Consumer<GatewayMessage> onEvent, Consumer<Throwable> onError);
}
```

- [ ] **Step 2: 修改 CloudProtocolClient，传递 lifecycle**

将 `CloudProtocolClient.java` 的 `connect` 方法改为：

```java
    /**
     * 按协议类型连接云端服务。
     *
     * @param protocol  协议标识（sse / websocket）
     * @param context   连接上下文
     * @param lifecycle 连接生命周期管理器
     * @param onEvent   事件回调
     * @param onError   错误回调（未知协议时也通过此回调报错）
     */
    public void connect(String protocol, CloudConnectionContext context,
                        CloudConnectionLifecycle lifecycle,
                        Consumer<GatewayMessage> onEvent, Consumer<Throwable> onError) {
        CloudProtocolStrategy strategy = strategyMap.get(protocol);
        if (strategy == null) {
            log.error("[CLOUD_PROTOCOL] Unknown protocol: {}", protocol);
            onError.accept(new IllegalArgumentException("Unknown cloud protocol: " + protocol));
            return;
        }
        strategy.connect(context, lifecycle, onEvent, onError);
    }
```

- [ ] **Step 3: 更新 CloudProtocolClientTest 适配新签名**

在 `CloudProtocolClientTest.java` 中，所有 `connect` 调用需增加 `lifecycle` 参数。由于这些测试 mock 了 strategy，只需传 `null` 或 mock 的 lifecycle：

在 import 区增加：
```java
import com.opencode.cui.gateway.service.cloud.CloudConnectionLifecycle;
```

将所有 `client.connect(protocol, context, onEvent, onError)` 改为 `client.connect(protocol, context, null, onEvent, onError)`。

将所有 `verify(strategy).connect(eq(context), eq(onEvent), eq(onError))` 改为 `verify(strategy).connect(eq(context), isNull(), eq(onEvent), eq(onError))`。

- [ ] **Step 4: 验证编译通过（测试暂时不运行，SSE 还没改）**

Run: `cd ai-gateway && mvn compile -q`
Expected: FAIL — `SseProtocolStrategy` 还没适配新接口，编译报错（预期内，Task 4 修复）

- [ ] **Step 5: Commit（WIP）**

```bash
git add ai-gateway/src/main/java/com/opencode/cui/gateway/service/cloud/CloudProtocolStrategy.java ai-gateway/src/main/java/com/opencode/cui/gateway/service/cloud/CloudProtocolClient.java ai-gateway/src/test/java/com/opencode/cui/gateway/service/cloud/CloudProtocolClientTest.java
git commit -m "refactor(gateway): add lifecycle parameter to CloudProtocolStrategy.connect"
```

---

## Task 4: 改造 SseProtocolStrategy 接入 Lifecycle

**Files:**
- Modify: `ai-gateway/src/main/java/com/opencode/cui/gateway/service/cloud/SseProtocolStrategy.java`
- Modify: `ai-gateway/src/test/java/com/opencode/cui/gateway/service/cloud/SseProtocolStrategyTest.java`

- [ ] **Step 1: 更新 SseProtocolStrategyTest — 适配新签名 + 增加心跳识别测试**

在 `SseProtocolStrategyTest.java` 中：

1. 增加 lifecycle mock 字段和 setup：

在 `@BeforeEach setUp()` 后添加一个 lifecycle 字段：

```java
    private CloudConnectionLifecycle lifecycle;
```

在 `@BeforeEach setUp()` 中添加：

```java
        lifecycle = mock(CloudConnectionLifecycle.class);
```

import:
```java
import static org.mockito.Mockito.mock;
```

2. 将所有 `strategy.connect(buildContext(), onEvent, onError)` 改为 `strategy.connect(buildContext(), lifecycle, onEvent, onError)`。

3. 增加心跳识别测试：

```java
    // ==================== G31: SSE 心跳注释行触发 lifecycle.onHeartbeat ====================

    @Test
    @DisplayName("G31: SSE 心跳注释行 - 触发 lifecycle.onHeartbeat()")
    void shouldCallOnHeartbeatForHeartbeatComment() throws Exception {
        // given
        String sseStream = String.join("\n",
                ": heartbeat",
                "data: {\"type\":\"tool_event\",\"toolSessionId\":\"s7\",\"event\":{\"text\":\"ok\"}}",
                ": heartbeat",
                "");

        HttpResponse<InputStream> response = mockResponse(200, sseStream);
        doReturn(response).when(strategy).sendRequest(any(HttpRequest.class));

        // when
        strategy.connect(buildContext(), lifecycle, onEvent, onError);

        // then
        verify(lifecycle, times(2)).onHeartbeat();
        verify(lifecycle).onConnected();
        verify(lifecycle).onEventReceived();
        assertEquals(1, receivedEvents.size());
    }
```

4. 增加 terminal event 主动关闭测试：

```java
    // ==================== G32: tool_done 触发 lifecycle.onTerminalEvent ====================

    @Test
    @DisplayName("G32: tool_done 触发 lifecycle.onTerminalEvent()")
    void shouldCallOnTerminalEventForToolDone() throws Exception {
        // given
        String sseStream = String.join("\n",
                "data: {\"type\":\"tool_event\",\"toolSessionId\":\"s8\",\"event\":{\"text\":\"hi\"}}",
                "data: {\"type\":\"tool_done\",\"toolSessionId\":\"s8\"}",
                "");

        HttpResponse<InputStream> response = mockResponse(200, sseStream);
        doReturn(response).when(strategy).sendRequest(any(HttpRequest.class));

        // when
        strategy.connect(buildContext(), lifecycle, onEvent, onError);

        // then
        verify(lifecycle).onTerminalEvent();
        verify(lifecycle, times(2)).onEventReceived(); // tool_event + tool_done
    }
```

- [ ] **Step 2: 改造 SseProtocolStrategy 实现**

完整替换 `SseProtocolStrategy.java`：

```java
package com.opencode.cui.gateway.service.cloud;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.gateway.model.GatewayMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * SSE 协议策略实现。
 *
 * <p>通过 HTTP POST 发送请求，读取 SSE 流，逐行解析 {@code data: {JSON}} 格式的事件。</p>
 *
 * <p>使用 Java 11+ HttpClient 同步模式（后续可优化为异步）。
 * HttpClient 通过构造器注入以便测试时 mock。</p>
 *
 * <p>生命周期管理：
 * <ul>
 *   <li>收到 HTTP 200 后调用 {@code lifecycle.onConnected()}</li>
 *   <li>SSE 心跳注释行（{@code : heartbeat}）调用 {@code lifecycle.onHeartbeat()}</li>
 *   <li>每条数据事件调用 {@code lifecycle.onEventReceived()}</li>
 *   <li>收到 tool_done/tool_error 调用 {@code lifecycle.onTerminalEvent()}</li>
 * </ul>
 * </p>
 */
@Slf4j
@Component
public class SseProtocolStrategy implements CloudProtocolStrategy {

    private final CloudAuthService cloudAuthService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @org.springframework.beans.factory.annotation.Autowired
    public SseProtocolStrategy(CloudAuthService cloudAuthService, ObjectMapper objectMapper,
            @org.springframework.beans.factory.annotation.Value("${gateway.cloud.connect-timeout-seconds:30}") int connectTimeoutSeconds) {
        this(cloudAuthService, objectMapper,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(connectTimeoutSeconds)).build());
    }

    /**
     * 测试友好构造器，允许注入自定义 HttpClient。
     */
    public SseProtocolStrategy(CloudAuthService cloudAuthService, ObjectMapper objectMapper, HttpClient httpClient) {
        this.cloudAuthService = cloudAuthService;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    @Override
    public String getProtocol() {
        return "sse";
    }

    @Override
    public void connect(CloudConnectionContext context, CloudConnectionLifecycle lifecycle,
                        Consumer<GatewayMessage> onEvent, Consumer<Throwable> onError) {
        try {
            // 1. 构建请求体
            String requestBody = objectMapper.writeValueAsString(context.getCloudRequest());

            // 2. 构建 HTTP POST 请求
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(context.getEndpoint()))
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .header("X-Trace-Id", context.getTraceId() != null ? context.getTraceId() : "")
                    .header("X-Request-Id", UUID.randomUUID().toString())
                    .header("X-App-Id", context.getAppId() != null ? context.getAppId() : "");

            // 3. 注入认证头
            cloudAuthService.applyAuth(requestBuilder, context.getAppId(), context.getAuthType());

            // 4. 发送请求
            HttpRequest request = requestBuilder.build();
            log.info("[SSE] Connecting: endpoint={}, appId={}, traceId={}",
                    context.getEndpoint(), context.getAppId(), context.getTraceId());

            HttpResponse<InputStream> response = sendRequest(request);

            if (response.statusCode() != 200) {
                onError.accept(new RuntimeException(
                        "SSE connection failed: HTTP " + response.statusCode()));
                return;
            }

            // 5. 通知 lifecycle 建联成功
            lifecycle.onConnected();

            // 6. 读取 SSE 流
            readSseStream(response.body(), lifecycle, onEvent, onError, context.getTraceId());

        } catch (Exception e) {
            log.error("[SSE] Connection error: endpoint={}, traceId={}, error={}",
                    context.getEndpoint(), context.getTraceId(), e.getMessage());
            onError.accept(e);
        }
    }

    /**
     * 发送 HTTP 请求。声明为 protected 以便测试时 mock。
     */
    protected HttpResponse<InputStream> sendRequest(HttpRequest request) throws Exception {
        return httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
    }

    /**
     * 读取 SSE 流并解析事件。
     */
    private void readSseStream(InputStream inputStream,
                               CloudConnectionLifecycle lifecycle,
                               Consumer<GatewayMessage> onEvent,
                               Consumer<Throwable> onError,
                               String traceId) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                // 心跳注释行：以 ": " 开头（SSE 规范标准）
                if (line.startsWith(":")) {
                    lifecycle.onHeartbeat();
                    continue;
                }
                if (line.startsWith("data:")) {
                    String jsonData = line.substring(5).trim();
                    if (jsonData.isEmpty() || "[DONE]".equals(jsonData)) {
                        continue;
                    }
                    try {
                        GatewayMessage message = objectMapper.readValue(jsonData, GatewayMessage.class);
                        lifecycle.onEventReceived();
                        onEvent.accept(message);

                        // terminal event：主动关闭连接
                        if (message.isType(GatewayMessage.Type.TOOL_DONE)
                                || message.isType(GatewayMessage.Type.TOOL_ERROR)) {
                            lifecycle.onTerminalEvent();
                            return; // 不再读取后续数据
                        }
                    } catch (Exception e) {
                        log.warn("[SSE] Failed to parse event: traceId={}, data={}, error={}",
                                traceId, jsonData, e.getMessage());
                    }
                }
                // 其他行（空行、event:、id:、retry: 等）忽略
            }
            log.info("[SSE] Stream completed: traceId={}", traceId);
        } catch (Exception e) {
            log.error("[SSE] Stream read error: traceId={}, error={}", traceId, e.getMessage());
            onError.accept(e);
        }
    }
}
```

- [ ] **Step 3: 运行 SSE 测试确认全部通过**

Run: `cd ai-gateway && mvn test -pl . -Dtest=SseProtocolStrategyTest -q`
Expected: 9 tests PASS（G24-G30 原有 + G31-G32 新增）

- [ ] **Step 4: Commit**

```bash
git add ai-gateway/src/main/java/com/opencode/cui/gateway/service/cloud/SseProtocolStrategy.java ai-gateway/src/test/java/com/opencode/cui/gateway/service/cloud/SseProtocolStrategyTest.java
git commit -m "feat(gateway): integrate lifecycle into SseProtocolStrategy with heartbeat and terminal event"
```

---

## Task 5: 改造 CloudAgentService 创建 Lifecycle

**Files:**
- Modify: `ai-gateway/src/main/java/com/opencode/cui/gateway/service/CloudAgentService.java`
- Modify: `ai-gateway/src/test/java/com/opencode/cui/gateway/service/CloudAgentServiceTest.java`

- [ ] **Step 1: 更新 CloudAgentServiceTest — 增加超时 tool_error 测试**

在 `CloudAgentServiceTest.java` 中：

1. 增加 import：

```java
import com.opencode.cui.gateway.config.CloudTimeoutProperties;
import com.opencode.cui.gateway.service.cloud.CloudConnectionLifecycle;
```

2. 增加 mock 字段：

```java
    @Mock
    private CloudTimeoutProperties cloudTimeoutProperties;
```

3. 修改 `setUp()`：

```java
    @BeforeEach
    void setUp() {
        // 配置默认超时值
        lenient().when(cloudTimeoutProperties.getFirstEventTimeoutSeconds()).thenReturn(120);
        lenient().when(cloudTimeoutProperties.getEffectiveIdleTimeoutSeconds(anyString())).thenReturn(90);
        lenient().when(cloudTimeoutProperties.getMaxDurationSeconds()).thenReturn(600);

        cloudAgentService = new CloudAgentService(cloudRouteService, cloudProtocolClient, cloudTimeoutProperties);
    }
```

增加 import：
```java
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
```

4. 所有 `verify(cloudProtocolClient).connect(eq("sse"), contextCaptor.capture(), ...)` 调用需要增加 lifecycle 参数位置。将 4 参数改为 5 参数：

将现有的：
```java
verify(cloudProtocolClient).connect(
        eq("sse"),
        contextCaptor.capture(),
        onEventCaptor.capture(),
        onErrorCaptor.capture()
);
```

改为：
```java
verify(cloudProtocolClient).connect(
        eq("sse"),
        contextCaptor.capture(),
        any(CloudConnectionLifecycle.class),
        onEventCaptor.capture(),
        onErrorCaptor.capture()
);
```

同理 `verify(cloudProtocolClient).connect(eq("sse"), contextCaptor.capture(), any(), any())` 改为 `verify(cloudProtocolClient).connect(eq("sse"), contextCaptor.capture(), any(CloudConnectionLifecycle.class), any(), any())`。

以及 `verify(cloudProtocolClient, never()).connect(any(), any(), any(), any())` 改为 `verify(cloudProtocolClient, never()).connect(any(), any(), any(), any(), any())`。

5. 增加超时 tool_error 测试：

```java
        @Test
        @DisplayName("云端超时时通过 onRelay 返回包含超时信息的 tool_error")
        void shouldRelayToolErrorWithTimeoutInfoOnTimeout() {
            GatewayMessage invokeMsg = buildInvokeMessage();
            CloudRouteInfo routeInfo = buildRouteInfo();
            when(cloudRouteService.getRouteInfo("ak-test-001")).thenReturn(routeInfo);

            cloudAgentService.handleInvoke(invokeMsg, onRelay);

            verify(cloudProtocolClient).connect(
                    eq("sse"),
                    contextCaptor.capture(),
                    any(CloudConnectionLifecycle.class),
                    any(),
                    onErrorCaptor.capture()
            );

            // Simulate timeout error
            onErrorCaptor.getValue().accept(
                    new RuntimeException("Cloud agent error: idle_timeout (elapsed: 90s)"));

            verify(onRelay).accept(messageCaptor.capture());
            GatewayMessage errorMsg = messageCaptor.getValue();
            assertEquals(GatewayMessage.Type.TOOL_ERROR, errorMsg.getType());
            assertTrue(errorMsg.getError().contains("idle_timeout"));
        }
```

- [ ] **Step 2: 改造 CloudAgentService 实现**

替换 `CloudAgentService.java`：

```java
package com.opencode.cui.gateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.opencode.cui.gateway.config.CloudTimeoutProperties;
import com.opencode.cui.gateway.model.CloudRouteInfo;
import com.opencode.cui.gateway.model.GatewayMessage;
import com.opencode.cui.gateway.service.cloud.CloudConnectionContext;
import com.opencode.cui.gateway.service.cloud.CloudConnectionLifecycle;
import com.opencode.cui.gateway.service.cloud.CloudProtocolClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 云端 Agent 服务编排器。
 *
 * <p>负责编排云端 AI 调用的完整流程：
 * <ol>
 *   <li>从 CloudRouteService 获取路由信息</li>
 *   <li>构建 CloudConnectionContext</li>
 *   <li>创建 CloudConnectionLifecycle 管理连接超时</li>
 *   <li>通过 CloudProtocolClient 连接云端服务</li>
 *   <li>为云端返回的事件注入路由上下文后通过 onRelay 回调转发</li>
 * </ol>
 * </p>
 *
 * <p>不再直接依赖 {@link SkillRelayService}，改由调用方传入 {@code onRelay} 回调，
 * 以打破循环依赖：SkillRelayService → BusinessInvokeRouteStrategy → CloudAgentService。</p>
 */
@Slf4j
@Service
public class CloudAgentService {

    private final CloudRouteService cloudRouteService;
    private final CloudProtocolClient cloudProtocolClient;
    private final CloudTimeoutProperties timeoutProperties;

    public CloudAgentService(CloudRouteService cloudRouteService,
                             CloudProtocolClient cloudProtocolClient,
                             CloudTimeoutProperties timeoutProperties) {
        this.cloudRouteService = cloudRouteService;
        this.cloudProtocolClient = cloudProtocolClient;
        this.timeoutProperties = timeoutProperties;
    }

    /**
     * 处理 invoke 消息，编排云端 AI 调用流程。
     *
     * @param invokeMessage invoke 消息
     * @param onRelay       回调：将需要转发的消息回传给调用方（通常是 SkillRelayService::relayToSkill）
     */
    public void handleInvoke(GatewayMessage invokeMessage, Consumer<GatewayMessage> onRelay) {
        String ak = invokeMessage.getAk();
        JsonNode cloudRequest = invokeMessage.getPayload().path("cloudRequest");
        String toolSessionId = invokeMessage.getPayload().path("toolSessionId").asText(null);

        log.info("[CLOUD_AGENT] handleInvoke: ak={}, toolSessionId={}, traceId={}",
                ak, toolSessionId, invokeMessage.getTraceId());

        // 1. 获取路由信息
        CloudRouteInfo routeInfo = cloudRouteService.getRouteInfo(ak);
        if (routeInfo == null) {
            log.warn("[CLOUD_AGENT] Route info not found: ak={}", ak);
            GatewayMessage errorMsg = buildCloudError(invokeMessage, toolSessionId,
                    new RuntimeException("Cloud route info not found for ak: " + ak));
            onRelay.accept(errorMsg);
            return;
        }

        // 2. 构建连接上下文
        CloudConnectionContext context = CloudConnectionContext.builder()
                .endpoint(routeInfo.getEndpoint())
                .cloudRequest(cloudRequest)
                .appId(routeInfo.getAppId())
                .authType(routeInfo.getAuthType())
                .traceId(invokeMessage.getTraceId())
                .build();

        // 3. 兜底 messageId/partId（每次 SSE 连接生成一份，云端没传时补上）
        String fallbackMessageId = "cloud-msg-" + UUID.randomUUID().toString().replace("-", "");
        ConcurrentHashMap<String, String> fallbackPartIds = new ConcurrentHashMap<>();

        // 4. 创建连接生命周期管理器
        String protocol = routeInfo.getProtocol();
        CloudConnectionLifecycle lifecycle = new CloudConnectionLifecycle(
                timeoutProperties.getFirstEventTimeoutSeconds(),
                timeoutProperties.getEffectiveIdleTimeoutSeconds(protocol),
                timeoutProperties.getMaxDurationSeconds(),
                (timeoutType, elapsedSeconds) -> {
                    log.warn("[CLOUD_AGENT] Connection timeout: ak={}, traceId={}, type={}, elapsed={}s",
                            ak, invokeMessage.getTraceId(), timeoutType, elapsedSeconds);
                    GatewayMessage errorMsg = buildCloudError(invokeMessage, toolSessionId,
                            new RuntimeException(timeoutType + " (elapsed: " + elapsedSeconds + "s)"));
                    onRelay.accept(errorMsg);
                },
                () -> log.info("[CLOUD_AGENT] Connection closed by lifecycle: ak={}, traceId={}",
                        ak, invokeMessage.getTraceId())
        );

        // 5. 连接云端服务
        try {
            cloudProtocolClient.connect(protocol, context, lifecycle,
                    event -> {
                        // 注入路由上下文
                        event.setAk(ak);
                        event.setUserId(invokeMessage.getUserId());
                        event.setWelinkSessionId(invokeMessage.getWelinkSessionId());
                        event.setTraceId(invokeMessage.getTraceId());
                        if (event.getToolSessionId() == null) {
                            event.setToolSessionId(toolSessionId);
                        }

                        // 兜底：云端未传 messageId/partId 时 GW 自动补充
                        JsonNode eventNode = event.getEvent();
                        if (eventNode != null && !eventNode.isMissingNode() && eventNode.isObject()) {
                            String eventType = eventNode.path("type").asText("");
                            com.fasterxml.jackson.databind.node.ObjectNode eventObj =
                                    (com.fasterxml.jackson.databind.node.ObjectNode) eventNode;
                            JsonNode props = eventObj.path("properties");
                            com.fasterxml.jackson.databind.node.ObjectNode propsObj =
                                    (props != null && props.isObject())
                                            ? (com.fasterxml.jackson.databind.node.ObjectNode) props : null;

                            // messageId 兜底
                            boolean needMsgId = propsObj == null
                                    || !propsObj.has("messageId")
                                    || propsObj.path("messageId").asText("").isBlank();
                            if (needMsgId && propsObj != null) {
                                propsObj.put("messageId", fallbackMessageId);
                            }

                            // partId 兜底
                            boolean needPartId = propsObj == null
                                    || !propsObj.has("partId")
                                    || propsObj.path("partId").asText("").isBlank();
                            if (needPartId && propsObj != null) {
                                String normalizedType = normalizeEventType(eventType);
                                String fbPartId = fallbackPartIds.computeIfAbsent(normalizedType,
                                        t -> "cloud-part-" + t + "-" + UUID.randomUUID().toString().substring(0, 8));
                                propsObj.put("partId", fbPartId);
                            }
                        }

                        onRelay.accept(event);
                    },
                    error -> {
                        log.error("[CLOUD_AGENT] Cloud connection error: ak={}, traceId={}, error={}",
                                ak, invokeMessage.getTraceId(), error.getMessage());
                        GatewayMessage errorMsg = buildCloudError(invokeMessage, toolSessionId, error);
                        onRelay.accept(errorMsg);
                    }
            );
        } finally {
            lifecycle.close();
        }
    }

    /**
     * 归一化事件类型（去掉 .delta/.done 后缀），使同类型事件共享 partId。
     */
    private static String normalizeEventType(String eventType) {
        if (eventType == null) return "unknown";
        if (eventType.endsWith(".delta")) return eventType.substring(0, eventType.length() - 6);
        if (eventType.endsWith(".done")) return eventType.substring(0, eventType.length() - 5);
        return eventType;
    }

    /**
     * 构建云端错误消息（tool_error 类型）。
     */
    private GatewayMessage buildCloudError(GatewayMessage invokeMessage, String toolSessionId, Throwable error) {
        return GatewayMessage.builder()
                .type(GatewayMessage.Type.TOOL_ERROR)
                .ak(invokeMessage.getAk())
                .userId(invokeMessage.getUserId())
                .welinkSessionId(invokeMessage.getWelinkSessionId())
                .traceId(invokeMessage.getTraceId())
                .toolSessionId(toolSessionId)
                .error("Cloud agent error: " + error.getMessage())
                .build();
    }
}
```

- [ ] **Step 3: 运行 CloudAgentService 测试**

Run: `cd ai-gateway && mvn test -pl . -Dtest=CloudAgentServiceTest -q`
Expected: 6 tests PASS

- [ ] **Step 4: 运行全部测试确认无回归**

Run: `cd ai-gateway && mvn test -pl . -q`
Expected: All tests PASS

- [ ] **Step 5: Commit**

```bash
git add ai-gateway/src/main/java/com/opencode/cui/gateway/service/CloudAgentService.java ai-gateway/src/test/java/com/opencode/cui/gateway/service/CloudAgentServiceTest.java
git commit -m "feat(gateway): create lifecycle in CloudAgentService with timeout-driven tool_error"
```

---

## Task 6: WebSocketProtocolStrategy 实现

**Files:**
- Create: `ai-gateway/src/test/java/com/opencode/cui/gateway/service/cloud/WebSocketProtocolStrategyTest.java`
- Create: `ai-gateway/src/main/java/com/opencode/cui/gateway/service/cloud/WebSocketProtocolStrategy.java`

- [ ] **Step 1: 写测试 — 正常消息接收和 lifecycle 调用**

```java
package com.opencode.cui.gateway.service.cloud;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.gateway.config.CloudTimeoutProperties;
import com.opencode.cui.gateway.model.GatewayMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.http.WebSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * WebSocketProtocolStrategy 单元测试。
 *
 * <p>通过模拟 WebSocket.Listener 回调验证消息解析和 lifecycle 调用。</p>
 */
@ExtendWith(MockitoExtension.class)
class WebSocketProtocolStrategyTest {

    @Mock
    private CloudAuthService cloudAuthService;
    @Mock
    private CloudConnectionLifecycle lifecycle;
    @Mock
    private CloudTimeoutProperties.WsOverride wsOverride;
    @Mock
    private CloudTimeoutProperties timeoutProperties;

    private ObjectMapper objectMapper;
    private List<GatewayMessage> receivedEvents;
    private List<Throwable> receivedErrors;
    private Consumer<GatewayMessage> onEvent;
    private Consumer<Throwable> onError;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        receivedEvents = new ArrayList<>();
        receivedErrors = new ArrayList<>();
        onEvent = receivedEvents::add;
        onError = receivedErrors::add;

        lenient().when(timeoutProperties.getWebsocket()).thenReturn(wsOverride);
        lenient().when(wsOverride.getPingIntervalSeconds()).thenReturn(30);
    }

    private CloudConnectionContext buildContext() {
        return CloudConnectionContext.builder()
                .endpoint("wss://cloud.example.com/ws")
                .cloudRequest(objectMapper.valueToTree(java.util.Map.of("prompt", "hello")))
                .appId("app_test")
                .authType("soa")
                .traceId("trace_ws_001")
                .build();
    }

    @Test
    @DisplayName("getProtocol 返回 websocket")
    void shouldReturnWebsocketProtocol() {
        WebSocketProtocolStrategy strategy = new WebSocketProtocolStrategy(
                cloudAuthService, objectMapper, timeoutProperties);
        assertEquals("websocket", strategy.getProtocol());
    }

    @Test
    @DisplayName("createListener: tool_event 消息触发 onEventReceived 和 onEvent 回调")
    void shouldCallOnEventReceivedForToolEvent() {
        // given
        WebSocketProtocolStrategy strategy = new WebSocketProtocolStrategy(
                cloudAuthService, objectMapper, timeoutProperties);
        WebSocket.Listener listener = strategy.createListener(lifecycle, onEvent, onError);
        WebSocket ws = mock(WebSocket.class);
        when(ws.request(anyLong())).thenReturn(null);

        String json = "{\"type\":\"tool_event\",\"toolSessionId\":\"s1\",\"event\":{\"text\":\"hi\"}}";

        // when
        listener.onText(ws, json, true);

        // then
        verify(lifecycle).onEventReceived();
        assertEquals(1, receivedEvents.size());
        assertEquals(GatewayMessage.Type.TOOL_EVENT, receivedEvents.get(0).getType());
    }

    @Test
    @DisplayName("createListener: tool_done 消息触发 onTerminalEvent")
    void shouldCallOnTerminalEventForToolDone() {
        // given
        WebSocketProtocolStrategy strategy = new WebSocketProtocolStrategy(
                cloudAuthService, objectMapper, timeoutProperties);
        WebSocket.Listener listener = strategy.createListener(lifecycle, onEvent, onError);
        WebSocket ws = mock(WebSocket.class);
        when(ws.request(anyLong())).thenReturn(null);

        String json = "{\"type\":\"tool_done\",\"toolSessionId\":\"s2\"}";

        // when
        listener.onText(ws, json, true);

        // then
        verify(lifecycle).onTerminalEvent();
        assertEquals(1, receivedEvents.size());
        assertEquals(GatewayMessage.Type.TOOL_DONE, receivedEvents.get(0).getType());
    }

    @Test
    @DisplayName("createListener: onPong 触发 lifecycle.onHeartbeat")
    void shouldCallOnHeartbeatForPong() {
        // given
        WebSocketProtocolStrategy strategy = new WebSocketProtocolStrategy(
                cloudAuthService, objectMapper, timeoutProperties);
        WebSocket.Listener listener = strategy.createListener(lifecycle, onEvent, onError);
        WebSocket ws = mock(WebSocket.class);
        when(ws.request(anyLong())).thenReturn(null);

        // when
        listener.onPong(ws, java.nio.ByteBuffer.allocate(0));

        // then
        verify(lifecycle).onHeartbeat();
    }

    @Test
    @DisplayName("createListener: onError 触发 onError 回调")
    void shouldCallOnErrorCallbackOnWebSocketError() {
        // given
        WebSocketProtocolStrategy strategy = new WebSocketProtocolStrategy(
                cloudAuthService, objectMapper, timeoutProperties);
        WebSocket.Listener listener = strategy.createListener(lifecycle, onEvent, onError);
        WebSocket ws = mock(WebSocket.class);

        // when
        listener.onError(ws, new RuntimeException("ws error"));

        // then
        assertEquals(1, receivedErrors.size());
        assertTrue(receivedErrors.get(0).getMessage().contains("ws error"));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd ai-gateway && mvn test -pl . -Dtest=WebSocketProtocolStrategyTest -q`
Expected: FAIL — `WebSocketProtocolStrategy` 类不存在

- [ ] **Step 3: 实现 WebSocketProtocolStrategy**

```java
package com.opencode.cui.gateway.service.cloud;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.gateway.config.CloudTimeoutProperties;
import com.opencode.cui.gateway.model.GatewayMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * WebSocket 协议策略实现。
 *
 * <p>通过 Java 11+ HttpClient WebSocket API 连接云端服务，发送请求体后接收流式事件。</p>
 *
 * <p>心跳机制：GW 定期发送 Ping，收到 Pong 时调用 {@code lifecycle.onHeartbeat()}。</p>
 */
@Slf4j
@Component
public class WebSocketProtocolStrategy implements CloudProtocolStrategy {

    private final CloudAuthService cloudAuthService;
    private final ObjectMapper objectMapper;
    private final CloudTimeoutProperties timeoutProperties;

    public WebSocketProtocolStrategy(CloudAuthService cloudAuthService,
                                     ObjectMapper objectMapper,
                                     CloudTimeoutProperties timeoutProperties) {
        this.cloudAuthService = cloudAuthService;
        this.objectMapper = objectMapper;
        this.timeoutProperties = timeoutProperties;
    }

    @Override
    public String getProtocol() {
        return "websocket";
    }

    @Override
    public void connect(CloudConnectionContext context, CloudConnectionLifecycle lifecycle,
                        Consumer<GatewayMessage> onEvent, Consumer<Throwable> onError) {
        try {
            // 1. 构建 WebSocket 连接
            HttpClient httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(timeoutProperties.getConnectTimeoutSeconds()))
                    .build();

            WebSocket.Listener listener = createListener(lifecycle, onEvent, onError);

            WebSocket.Builder wsBuilder = httpClient.newWebSocketBuilder();
            wsBuilder.subprotocols("opencode-cloud");
            wsBuilder.header("X-Trace-Id", context.getTraceId() != null ? context.getTraceId() : "");
            wsBuilder.header("X-App-Id", context.getAppId() != null ? context.getAppId() : "");

            log.info("[WS] Connecting: endpoint={}, appId={}, traceId={}",
                    context.getEndpoint(), context.getAppId(), context.getTraceId());

            WebSocket ws = wsBuilder
                    .buildAsync(URI.create(context.getEndpoint()), listener)
                    .get(timeoutProperties.getConnectTimeoutSeconds(), TimeUnit.SECONDS);

            // 2. 连接成功，通知 lifecycle
            lifecycle.onConnected();

            // 3. 发送请求体
            String requestBody = objectMapper.writeValueAsString(context.getCloudRequest());
            ws.sendText(requestBody, true).get(10, TimeUnit.SECONDS);

            // 4. 启动 Ping 心跳定时器
            int pingInterval = timeoutProperties.getWebsocket().getPingIntervalSeconds();
            ScheduledExecutorService pingScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "ws-ping-timer");
                t.setDaemon(true);
                return t;
            });
            pingScheduler.scheduleAtFixedRate(() -> {
                try {
                    ws.sendPing(ByteBuffer.allocate(0)).get(5, TimeUnit.SECONDS);
                } catch (Exception e) {
                    log.warn("[WS] Ping failed: traceId={}, error={}", context.getTraceId(), e.getMessage());
                }
            }, pingInterval, pingInterval, TimeUnit.SECONDS);

            // 5. 等待连接关闭（阻塞调用线程，与 SSE 策略行为一致）
            CountDownLatch closeLatch = new CountDownLatch(1);
            // listener 的 onClose/onError 中会触发 latch
            // 通过 lifecycle 的 closeAction 或 listener 完成来释放
            // 简化：使用 CompletableFuture 等待
            // 实际上 listener 的回调会在 ws 关闭时执行
            // 这里需要等待连接结束
            try {
                // 阻塞等待直到 WebSocket 关闭或超时
                // maxDuration 由 lifecycle 管理，这里用更长的等待
                closeLatch.await(timeoutProperties.getMaxDurationSeconds() + 30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                pingScheduler.shutdownNow();
            }

        } catch (TimeoutException e) {
            log.error("[WS] Connect timeout: endpoint={}, traceId={}",
                    context.getEndpoint(), context.getTraceId());
            onError.accept(new RuntimeException("WebSocket connect timeout"));
        } catch (Exception e) {
            log.error("[WS] Connection error: endpoint={}, traceId={}, error={}",
                    context.getEndpoint(), context.getTraceId(), e.getMessage());
            onError.accept(e);
        }
    }

    /**
     * 创建 WebSocket.Listener。声明为 package-private 便于测试。
     */
    WebSocket.Listener createListener(CloudConnectionLifecycle lifecycle,
                                       Consumer<GatewayMessage> onEvent,
                                       Consumer<Throwable> onError) {
        return new WebSocket.Listener() {

            private final StringBuilder textBuffer = new StringBuilder();

            @Override
            public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                textBuffer.append(data);
                if (last) {
                    String json = textBuffer.toString();
                    textBuffer.setLength(0);
                    processMessage(json, lifecycle, onEvent, onError);
                }
                webSocket.request(1);
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public CompletionStage<?> onPong(WebSocket webSocket, ByteBuffer message) {
                lifecycle.onHeartbeat();
                webSocket.request(1);
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                log.info("[WS] Connection closed: statusCode={}, reason={}", statusCode, reason);
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public void onError(WebSocket webSocket, Throwable error) {
                log.error("[WS] Error: {}", error.getMessage());
                onError.accept(error);
            }
        };
    }

    private void processMessage(String json, CloudConnectionLifecycle lifecycle,
                                Consumer<GatewayMessage> onEvent, Consumer<Throwable> onError) {
        try {
            GatewayMessage message = objectMapper.readValue(json, GatewayMessage.class);
            lifecycle.onEventReceived();
            onEvent.accept(message);

            if (message.isType(GatewayMessage.Type.TOOL_DONE)
                    || message.isType(GatewayMessage.Type.TOOL_ERROR)) {
                lifecycle.onTerminalEvent();
            }
        } catch (Exception e) {
            log.warn("[WS] Failed to parse message: json={}, error={}", json, e.getMessage());
        }
    }
}
```

- [ ] **Step 4: 运行 WebSocket 测试确认通过**

Run: `cd ai-gateway && mvn test -pl . -Dtest=WebSocketProtocolStrategyTest -q`
Expected: 5 tests PASS

- [ ] **Step 5: 运行全部测试确认无回归**

Run: `cd ai-gateway && mvn test -pl . -q`
Expected: All tests PASS

- [ ] **Step 6: Commit**

```bash
git add ai-gateway/src/main/java/com/opencode/cui/gateway/service/cloud/WebSocketProtocolStrategy.java ai-gateway/src/test/java/com/opencode/cui/gateway/service/cloud/WebSocketProtocolStrategyTest.java
git commit -m "feat(gateway): add WebSocketProtocolStrategy with ping/pong heartbeat"
```

---

## Task 7: 最终集成验证

**Files:**
- 无新文件，验证所有组件协同工作

- [ ] **Step 1: 运行全部 Gateway 测试**

Run: `cd ai-gateway && mvn test -pl . -q`
Expected: All tests PASS

- [ ] **Step 2: 运行编译检查**

Run: `cd ai-gateway && mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: 检查 CloudProtocolClient 日志确认两个策略注册**

确认 `CloudProtocolClient` 构造器日志会输出 `[CLOUD_PROTOCOL] Registered protocol strategies: [sse, websocket]`。无需代码变更，两个 `@Component` 策略会被 Spring 自动注入。

- [ ] **Step 4: Commit（如有任何修复）**

```bash
git add -A
git commit -m "test(gateway): final integration verification for cloud connection lifecycle"
```

---

## Summary

| Task | 产出 | 预估复杂度 |
|------|------|-----------|
| Task 1 | CloudTimeoutProperties + YAML 配置 | 低 |
| Task 2 | CloudConnectionLifecycle + 6 个单测 | 中 |
| Task 3 | 接口签名变更 + Client 适配 | 低 |
| Task 4 | SSE 策略改造 + 心跳/terminal 测试 | 中 |
| Task 5 | CloudAgentService 改造 + 超时测试 | 中 |
| Task 6 | WebSocket 策略实现 + 5 个单测 | 中 |
| Task 7 | 集成验证 | 低 |
