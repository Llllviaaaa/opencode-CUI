package com.opencode.cui.gateway.service.cloud.decoder.assistantsquare;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UnknownProtocolFallbackHandlerTest {

    private final ObjectMapper om = new ObjectMapper();
    private final UnknownProtocolFallbackHandler handler = new UnknownProtocolFallbackHandler();

    @Test
    void getProtocolType_isUnknown() {
        assertThat(handler.getProtocolType()).isEqualTo("unknown");
    }

    @Test
    void handle_returnsEmptyListForAnyInput() throws Exception {
        AssistantSquareDecoderSession session = new AssistantSquareDecoderSession();
        assertThat(handler.handle("any", om.readTree("{}"), session)).isEmpty();
        assertThat(handler.handle(null, om.readTree("{\"eventType\":\"foo\"}"), session)).isEmpty();
        assertThat(handler.handle("message", om.readTree("{\"messageType\":\"HTML\"}"), session)).isEmpty();
    }

    @Test
    void flush_returnsEmpty() {
        assertThat(handler.flush(new AssistantSquareDecoderSession())).isEmpty();
    }
}
