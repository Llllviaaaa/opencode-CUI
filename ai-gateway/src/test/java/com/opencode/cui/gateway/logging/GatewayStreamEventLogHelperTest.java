package com.opencode.cui.gateway.logging;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class GatewayStreamEventLogHelperTest {

    @Test
    void inboundShouldAcceptRawPayload() {
        assertDoesNotThrow(() -> GatewayStreamEventLogHelper.inbound(
                LoggerFactory.getLogger(GatewayStreamEventLogHelperTest.class),
                "gw.local_agent",
                "received",
                "{\"type\":\"tool_event\",\"event\":{\"type\":\"text.delta\"}}"));
    }
}
