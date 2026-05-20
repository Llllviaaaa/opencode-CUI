package com.opencode.cui.skill.telemetry.core;

import com.opencode.cui.skill.telemetry.config.WelinkTelemetryProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 独立的 telemetry 异步执行器。
 *
 * <p>队列满 → {@link ThreadPoolExecutor.DiscardPolicy} + 内部丢弃计数 WARN。
 * <b>不复用业务线程池</b>，避免上报流量挤压 chat 主路径。
 */
@Slf4j
public class TelemetryExecutor {

    private final ThreadPoolTaskExecutor delegate;
    private final AtomicLong discardCount = new AtomicLong();

    public TelemetryExecutor(WelinkTelemetryProperties.Executor cfg) {
        this.delegate = new ThreadPoolTaskExecutor();
        this.delegate.setCorePoolSize(cfg.getCorePoolSize());
        this.delegate.setMaxPoolSize(cfg.getMaxPoolSize());
        this.delegate.setQueueCapacity(cfg.getQueueCapacity());
        this.delegate.setThreadNamePrefix("welink-telemetry-");
        // DiscardPolicy：队列满静默丢弃，外层包一层 try-catch 后再 WARN + 计数
        this.delegate.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy());
        this.delegate.initialize();
    }

    /**
     * 提交一个上报任务。execute() 本身不会因 DiscardPolicy 抛异常（DiscardPolicy 静默丢弃），
     * 但为防 race-condition / 其他异常 → 顶层 catch + WARN。
     */
    public void submit(Runnable task) {
        try {
            // Wrap so even reporter bug 也不会让线程死
            delegate.execute(() -> {
                try {
                    task.run();
                } catch (Throwable t) {
                    log.warn("[WelinkTelemetry] task threw: error={}", t.getMessage(), t);
                }
            });
        } catch (RejectedExecutionException e) {
            long dropped = discardCount.incrementAndGet();
            log.warn("[WelinkTelemetry] queue full, dropping event (totalDropped={})", dropped);
        } catch (Throwable t) {
            log.warn("[WelinkTelemetry] submit failed: error={}", t.getMessage());
        }
    }

    public long getDiscardCount() {
        return discardCount.get();
    }

    public void shutdown() {
        delegate.shutdown();
    }
}
