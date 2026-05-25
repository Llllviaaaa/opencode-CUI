package com.opencode.cui.gateway.logging;

import org.slf4j.Logger;

/**
 * Emits raw WebSocket/SSE boundary events for gateway ingress.
 */
public final class GatewayStreamEventLogHelper {

    private GatewayStreamEventLogHelper() {
    }

    public static void inbound(Logger log, String endpoint, String result, String payload) {
        if (log == null) {
            return;
        }
        log.info("event=ws_event direction=inbound endpoint={} result={} payload={}",
                endpoint, result, payload);
    }

    public static void inbound(Logger log, String endpoint, String result, Object payload) {
        if (log == null) {
            return;
        }
        inbound(log, endpoint, result, String.valueOf(payload));
    }
}
