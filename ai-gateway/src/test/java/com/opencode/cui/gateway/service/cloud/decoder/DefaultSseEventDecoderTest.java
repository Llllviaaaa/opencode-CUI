package com.opencode.cui.gateway.service.cloud.decoder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.gateway.model.GatewayMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultSseEventDecoderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DefaultSseEventDecoder decoder = new DefaultSseEventDecoder(objectMapper);

    @Test
    void name_isDefault() {
        assertThat(decoder.getName()).isEqualTo("default");
    }

    @Test
    void isTerminator_recognizesDoneSentinel() {
        assertThat(decoder.isTerminator("[DONE]")).isTrue();
        assertThat(decoder.isTerminator("FINISH")).isFalse();
        assertThat(decoder.isTerminator("")).isFalse();
    }

    @Test
    void isHeartbeat_defaultsFalse() {
        assertThat(decoder.isHeartbeat("\"eventType\":\"ping\"")).isFalse();
    }

    @Test
    void decode_parsesGatewayMessageEquivalentToLegacyReadValue() {
        String json = "{\"type\":\"tool_event\",\"toolSessionId\":\"s1\","
                + "\"event\":{\"type\":\"text.delta\",\"properties\":{\"content\":\"hi\"}}}";
        List<GatewayMessage> out = decoder.decode(json, decoder.createSession());
        assertThat(out).hasSize(1);
        GatewayMessage m = out.get(0);
        assertThat(m.getType()).isEqualTo(GatewayMessage.Type.TOOL_EVENT);
        assertThat(m.getToolSessionId()).isEqualTo("s1");
        assertThat(m.getEvent().path("type").asText()).isEqualTo("text.delta");
    }

    @Test
    void decode_malformedJson_returnsEmptyDoesNotThrow() {
        List<GatewayMessage> out = decoder.decode("{not json}", decoder.createSession());
        assertThat(out).isEmpty();
    }

    @Test
    void flush_returnsEmpty() {
        assertThat(decoder.flush(decoder.createSession())).isEmpty();
    }
}
