package com.opencode.cui.gateway.service.cloud;

import org.junit.jupiter.api.AfterEach;
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
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> timeoutType = new AtomicReference<>();

        lifecycle = new CloudConnectionLifecycle(
                1, 60, 600,
                (type, elapsed) -> { timeoutType.set(type); latch.countDown(); },
                () -> {}
        );
        lifecycle.onConnected();

        assertTrue(latch.await(3, TimeUnit.SECONDS), "timeout callback should fire");
        assertEquals("first_event_timeout", timeoutType.get());
    }

    @Test
    @DisplayName("onEventReceived: 首条事件取消 firstEventTimeout，启动 idleTimeout")
    void shouldCancelFirstEventTimeoutOnEventReceived() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> timeoutType = new AtomicReference<>();

        lifecycle = new CloudConnectionLifecycle(
                1, 60, 600,
                (type, elapsed) -> { timeoutType.set(type); latch.countDown(); },
                () -> {}
        );
        lifecycle.onConnected();
        Thread.sleep(200);
        lifecycle.onEventReceived();

        assertFalse(latch.await(2, TimeUnit.SECONDS), "firstEventTimeout should NOT fire after event received");
        assertNull(timeoutType.get());
    }

    @Test
    @DisplayName("idleTimeout: 收到事件后长时间无新数据触发空闲超时")
    void shouldFireIdleTimeoutAfterInactivity() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> timeoutType = new AtomicReference<>();

        lifecycle = new CloudConnectionLifecycle(
                60, 1, 600,
                (type, elapsed) -> { timeoutType.set(type); latch.countDown(); },
                () -> {}
        );
        lifecycle.onConnected();
        lifecycle.onEventReceived();

        assertTrue(latch.await(3, TimeUnit.SECONDS), "idle timeout should fire");
        assertEquals("idle_timeout", timeoutType.get());
    }

    @Test
    @DisplayName("onHeartbeat: 心跳重置 idleTimeout 计时器")
    void shouldResetIdleTimeoutOnHeartbeat() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> timeoutType = new AtomicReference<>();

        lifecycle = new CloudConnectionLifecycle(
                60, 2, 600,
                (type, elapsed) -> { timeoutType.set(type); latch.countDown(); },
                () -> {}
        );
        lifecycle.onConnected();
        lifecycle.onEventReceived();
        Thread.sleep(1000);
        lifecycle.onHeartbeat();
        Thread.sleep(1000);

        assertFalse(latch.await(500, TimeUnit.MILLISECONDS), "idle timeout should NOT fire because heartbeat reset it");
        assertNull(timeoutType.get());
    }

    @Test
    @DisplayName("maxDuration: 即使持续有事件，总时长超限也触发超时")
    void shouldFireMaxDurationTimeout() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> timeoutType = new AtomicReference<>();

        lifecycle = new CloudConnectionLifecycle(
                60, 60, 1,
                (type, elapsed) -> { timeoutType.set(type); latch.countDown(); },
                () -> {}
        );
        lifecycle.onConnected();
        lifecycle.onEventReceived();

        assertTrue(latch.await(3, TimeUnit.SECONDS), "maxDuration should fire");
        assertEquals("max_duration", timeoutType.get());
    }

    @Test
    @DisplayName("onTerminalEvent: tool_done 后取消所有计时器并调用 closeAction")
    void shouldCancelAllTimersAndCloseOnTerminalEvent() throws Exception {
        CountDownLatch closeLatch = new CountDownLatch(1);
        CountDownLatch timeoutLatch = new CountDownLatch(1);

        lifecycle = new CloudConnectionLifecycle(
                60, 1, 1,
                (type, elapsed) -> timeoutLatch.countDown(),
                closeLatch::countDown
        );
        lifecycle.onConnected();
        lifecycle.onEventReceived();
        lifecycle.onTerminalEvent();

        assertTrue(closeLatch.await(1, TimeUnit.SECONDS), "closeAction should be called");
        assertFalse(timeoutLatch.await(2, TimeUnit.SECONDS), "no timeout should fire after terminal event");
    }

    @Test
    @DisplayName("close: 多次调用 close 不抛异常（幂等）")
    void shouldBeIdempotentOnClose() {
        lifecycle = new CloudConnectionLifecycle(60, 60, 600, (type, elapsed) -> {}, () -> {});
        lifecycle.onConnected();
        lifecycle.close();
        lifecycle.close();
        lifecycle.close();
    }
}
