package com.opencode.cui.skill.logging;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class StreamEventLogHelperTest {

    @Test
    void outboundShouldAcceptRawPayload() {
        assertDoesNotThrow(() -> StreamEventLogHelper.outbound(
                LoggerFactory.getLogger(StreamEventLogHelperTest.class),
                "ss.miniapp",
                "sent",
                "{\"type\":\"text.delta\",\"content\":\"hello\"}"));
    }
}
