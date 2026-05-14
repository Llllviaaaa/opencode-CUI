package com.opencode.cui.gateway.service.cloud.decoder.assistantsquare;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.gateway.model.GatewayMessage;
import com.opencode.cui.gateway.service.cloud.decoder.DecoderSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AssistantSquareSseEventDecoderTest {

    private final ObjectMapper om = new ObjectMapper();
    private StandardProtocolHandler standard;
    private UnknownProtocolFallbackHandler unknown;
    private AssistantSquareSseEventDecoder decoder;

    @BeforeEach
    void setUp() {
        standard = new StandardProtocolHandler(om);
        unknown = new UnknownProtocolFallbackHandler();
        decoder = new AssistantSquareSseEventDecoder(om, List.of(standard, unknown), standard, unknown);
    }

    @Test
    void name_isAssistantSquare() {
        assertThat(decoder.getName()).isEqualTo("assistant_square");
    }

    @Test
    void isTerminator_recognizesFinishAndDone() {
        assertThat(decoder.isTerminator("FINISH")).isTrue();
        assertThat(decoder.isTerminator("[DONE]")).isTrue();
        assertThat(decoder.isTerminator("")).isFalse();
        assertThat(decoder.isTerminator("{\"eventType\":\"ping\"}")).isFalse();
    }

    @Test
    void isHeartbeat_recognizesEventTypePing() {
        assertThat(decoder.isHeartbeat("{\"eventType\":\"ping\"}")).isTrue();
        assertThat(decoder.isHeartbeat("{\"eventType\":\"message\"}")).isFalse();
    }

    @Test
    void decode_missingProtocolType_defaultsToStandard() {
        DecoderSession s = decoder.createSession();
        List<GatewayMessage> out = decoder.decode(
                "{\"eventType\":\"message\",\"messageType\":\"TEXT\",\"messageId\":\"m1\",\"messageBody\":\"hi\"}", s);
        // standard 会补 step.start + text.delta
        assertThat(out).hasSize(2);
        assertThat(out.get(0).getEvent().path("type").asText()).isEqualTo("step.start");
        assertThat(out.get(1).getEvent().path("type").asText()).isEqualTo("text.delta");
    }

    @Test
    void decode_explicitStandard_routesToStandardHandler() {
        DecoderSession s = decoder.createSession();
        List<GatewayMessage> out = decoder.decode(
                "{\"protocolType\":\"standard\",\"eventType\":\"message\",\"messageType\":\"TEXT\",\"messageId\":\"m1\",\"messageBody\":\"x\"}", s);
        assertThat(out).hasSize(2);
    }

    @Test
    void decode_unknownProtocol_dropped() {
        DecoderSession s = decoder.createSession();
        assertThat(decoder.decode(
                "{\"protocolType\":\"athena\",\"eventType\":\"foo\"}", s)).isEmpty();
        assertThat(decoder.decode(
                "{\"protocolType\":\"uniknow\",\"eventType\":\"bar\"}", s)).isEmpty();
    }

    @Test
    void decode_malformedJson_returnsEmptyDoesNotThrow() {
        DecoderSession s = decoder.createSession();
        assertThat(decoder.decode("{not json}", s)).isEmpty();
    }

    @Test
    void flush_delegatesToStandardHandler() {
        DecoderSession s = decoder.createSession();
        // run one delta to create open part
        decoder.decode("{\"eventType\":\"message\",\"messageType\":\"TEXT\",\"messageId\":\"m1\",\"messageBody\":\"x\"}", s);
        List<GatewayMessage> out = decoder.flush(s);
        // expect text.done + step.done
        assertThat(out).hasSize(2);
        assertThat(out.get(0).getEvent().path("type").asText()).isEqualTo("text.done");
        assertThat(out.get(1).getEvent().path("type").asText()).isEqualTo("step.done");
    }
}
