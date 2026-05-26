package com.opencode.cui.gateway.service.cloud;

import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Cancellable handle for a single cloud streaming connection.
 */
@Slf4j
public class CloudConnectionHandle {

    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final List<Runnable> closeActions = new CopyOnWriteArrayList<>();

    public boolean isCancelled() {
        return cancelled.get();
    }

    public void onCancel(Runnable closeAction) {
        if (closeAction == null) {
            return;
        }
        if (cancelled.get()) {
            runQuietly(closeAction);
            return;
        }
        closeActions.add(closeAction);
        if (cancelled.get() && closeActions.remove(closeAction)) {
            runQuietly(closeAction);
        }
    }

    public boolean cancel() {
        if (!cancelled.compareAndSet(false, true)) {
            return false;
        }
        for (Runnable closeAction : closeActions) {
            runQuietly(closeAction);
        }
        closeActions.clear();
        return true;
    }

    private void runQuietly(Runnable closeAction) {
        try {
            closeAction.run();
        } catch (Exception e) {
            log.debug("[CLOUD_CANCEL] close action failed: {}", e.getMessage());
        }
    }
}
