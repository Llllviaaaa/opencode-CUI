package com.opencode.cui.gateway.service.cloud.decoder.assistantsquare;

import com.fasterxml.jackson.databind.JsonNode;
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

        assertThat(out).hasSize(3);
        assertThat(eventType(out, 0)).isEqualTo("session.status");
        assertThat(eventType(out, 1)).isEqualTo("step.start");
        assertThat(eventType(out, 2)).isEqualTo("text.delta");
    }

    @Test
    void decode_explicitStandard_routesToStandardHandler() {
        DecoderSession s = decoder.createSession();
        List<GatewayMessage> out = decoder.decode(
                "{\"protocolType\":\"standard\",\"eventType\":\"message\",\"messageType\":\"TEXT\",\"messageId\":\"m1\",\"messageBody\":\"x\"}", s);

        assertThat(out).hasSize(3);
        assertThat(eventType(out, 0)).isEqualTo("session.status");
        assertThat(eventType(out, 1)).isEqualTo("step.start");
        assertThat(eventType(out, 2)).isEqualTo("text.delta");
    }

    @Test
    void decode_numericStandardProtocolWithNestedData_keepsStreaming() {
        DecoderSession s = decoder.createSession();

        List<GatewayMessage> route = decoder.decode(
                "{\"code\":\"200\",\"data\":{\"messageId\":\"m1\",\"skillInfo\":{\"resultType\":\"STREAM\"}},\"eventType\":\"route\",\"protocolType\":\"5\"}", s);
        assertThat(route).hasSize(2);
        assertThat(eventType(route, 0)).isEqualTo("session.status");
        assertThat(props(route, 0).path("status").asText()).isEqualTo("busy");
        assertThat(eventType(route, 1)).isEqualTo("step.start");
        assertThat(props(route, 1).path("messageId").asText()).isEqualTo("m1");

        List<GatewayMessage> message = decoder.decode(
                "{\"code\":\"200\",\"data\":{\"messageId\":\"m1\",\"messageType\":\"TEXT\",\"messageBody\":{\"text\":\"hello\"}},\"eventType\":\"message\",\"protocolType\":\"5\"}", s);
        assertThat(message).hasSize(1);
        assertThat(eventType(message, 0)).isEqualTo("text.delta");
        assertThat(props(message, 0).path("content").asText()).isEqualTo("hello");
        assertThat(props(message, 0).path("messageId").asText()).isEqualTo("m1");
    }

    @Test
    void decode_protocolFiveStandardEvents_readNestedPayloads() {
        DecoderSession s = decoder.createSession();
        decoder.decode(
                "{\"code\":\"200\",\"data\":{\"messageId\":\"m1\",\"messageType\":\"PLANNING\",\"messageBody\":{\"planning\":\"plan\"}},\"eventType\":\"planning\",\"protocolType\":\"5\"}",
                s);

        List<GatewayMessage> searching = decoder.decode(
                "{\"code\":\"200\",\"data\":{\"messageId\":\"m1\",\"messageBody\":{\"searching\":[\"JDK8\"]}},\"eventType\":\"searching\",\"protocolType\":\"5\"}",
                s);
        assertThat(searching).hasSize(2);
        assertThat(eventType(searching, 0)).isEqualTo("planning.done");
        assertThat(eventType(searching, 1)).isEqualTo("searching");
        assertThat(props(searching, 1).path("keywords")).isEqualTo(json("[\"JDK8\"]"));

        List<GatewayMessage> reference = decoder.decode(
                "{\"code\":\"200\",\"data\":{\"messageId\":\"m1\",\"messageBody\":{\"references\":[{\"index\":1,\"title\":\"java\"}]}},\"eventType\":\"reference\",\"protocolType\":\"5\"}",
                s);
        assertThat(reference).hasSize(1);
        assertThat(eventType(reference, 0)).isEqualTo("reference");
        assertThat(props(reference, 0).path("references").get(0).path("title").asText()).isEqualTo("java");
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
        decoder.decode("{\"eventType\":\"message\",\"messageType\":\"TEXT\",\"messageId\":\"m1\",\"messageBody\":\"x\"}", s);
        List<GatewayMessage> out = decoder.flush(s);

        assertThat(out).hasSize(3);
        assertThat(eventType(out, 0)).isEqualTo("text.done");
        assertThat(eventType(out, 1)).isEqualTo("step.done");
        assertThat(eventType(out, 2)).isEqualTo("session.status");
        assertThat(props(out, 2).path("status").asText()).isEqualTo("idle");
    }

    private JsonNode json(String value) {
        try {
            return om.readTree(value);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static String eventType(List<GatewayMessage> out, int index) {
        return out.get(index).getEvent().path("type").asText();
    }

    private static JsonNode props(List<GatewayMessage> out, int index) {
        return out.get(index).getEvent().path("properties");
    }
}
