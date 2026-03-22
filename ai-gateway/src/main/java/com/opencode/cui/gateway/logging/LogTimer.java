package com.opencode.cui.gateway.logging;

import org.slf4j.Logger;

import java.util.function.Supplier;

/**
 * 外部调用计时日志工具。
 * 自动记录操作耗时和成功/失败状态。
 *
 * <pre>{@code
 * String result = LogTimer.timed(log, "IdentityAPI.check", () -> {
 *     return identityClient.check(ak);
 * });
 * }</pre>
 */
public final class LogTimer {

    private LogTimer() {
    }

    /**
     * 执行带计时的操作，自动记录 INFO（成功）或 ERROR（失败）日志。
     */
    public static <T> T timed(Logger log, String operation, Supplier<T> action) {
        long start = System.nanoTime();
        try {
            T result = action.get();
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            log.info("[EXT_CALL] {} completed: durationMs={}", operation, elapsedMs);
            return result;
        } catch (Exception e) {
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            log.error("[EXT_CALL] {} failed: durationMs={}, error={}", operation, elapsedMs, e.getMessage());
            throw e;
        }
    }

    /**
     * 执行带计时的 void 操作。
     */
    public static void timedRun(Logger log, String operation, Runnable action) {
        long start = System.nanoTime();
        try {
            action.run();
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            log.info("[EXT_CALL] {} completed: durationMs={}", operation, elapsedMs);
        } catch (Exception e) {
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            log.error("[EXT_CALL] {} failed: durationMs={}, error={}", operation, elapsedMs, e.getMessage());
            throw e;
        }
    }
}
