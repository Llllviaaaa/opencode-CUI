package com.opencode.cui.skill.logging;

import org.slf4j.Logger;

/**
 * Emits raw WebSocket boundary events for skill-server egress.
 */
public final class StreamEventLogHelper {

    private StreamEventLogHelper() {
    }

    public static void outbound(Logger log, String endpoint, String result, String payload) {
        if (log == null) {
            return;
        }
        log.info("event=ws_event direction=outbound endpoint={} result={} payload={}",
                endpoint, result, payload);
    }

    public static void outbound(Logger log, String endpoint, String result, Object payload) {
        if (log == null) {
            return;
        }
        outbound(log, endpoint, result, String.valueOf(payload));
    }
}
