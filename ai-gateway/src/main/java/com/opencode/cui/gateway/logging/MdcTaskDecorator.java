package com.opencode.cui.gateway.logging;

import org.springframework.core.task.TaskDecorator;

import java.util.Map;

/**
 * Captures the current MDC at task submission time and restores it while the task runs.
 */
public class MdcTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        Map<String, String> captured = MdcHelper.snapshot();
        return () -> {
            Map<String, String> previous = MdcHelper.snapshot();
            try {
                MdcHelper.restore(captured);
                runnable.run();
            } finally {
                MdcHelper.restore(previous);
            }
        };
    }
}
