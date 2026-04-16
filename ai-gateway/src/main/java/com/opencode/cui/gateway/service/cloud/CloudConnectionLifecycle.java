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

    @FunctionalInterface
    public interface TimeoutCallback {
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

    public void onEventReceived() {
        if (closed.get()) return;
        cancelFuture(firstEventFuture);
        firstEventFuture = null;
        resetIdleTimeout();
    }

    public void onHeartbeat() {
        if (closed.get()) return;
        cancelFuture(firstEventFuture);
        firstEventFuture = null;
        resetIdleTimeout();
    }

    public void onTerminalEvent() {
        if (closed.get()) return;
        cancelAllTimers();
        closeAction.run();
        shutdown();
    }

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
