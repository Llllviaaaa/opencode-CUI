package com.opencode.cui.gateway.service.cloud.decoder.assistantsquare;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.gateway.model.GatewayMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StandardProtocolHandlerTest {

    private final ObjectMapper om = new ObjectMapper();
    private final StandardProtocolHandler handler = new StandardProtocolHandler(om);

    private JsonNode jn(String s) throws Exception {
        return om.readTree(s);
    }

    @Test
    void firstEvent_emitsBusyStepStartThenDelta() throws Exception {
        AssistantSquareDecoderSession s = new AssistantSquareDecoderSession();
        List<GatewayMessage> out = handler.handle("message",
                jn("{\"messageType\":\"TEXT\",\"messageId\":\"m1\",\"messageBody\":\"he\"}"), s);

        assertThat(out).hasSize(3);
        assertThat(eventType(out, 0)).isEqualTo("session.status");
        assertThat(props(out, 0).path("status").asText()).isEqualTo("busy");
        assertThat(props(out, 0).path("sessionStatus").asText()).isEqualTo("busy");
        assertThat(eventType(out, 1)).isEqualTo("step.start");
        assertThat(eventType(out, 2)).isEqualTo("text.delta");
        assertThat(props(out, 2).path("content").asText()).isEqualTo("he");
        assertThat(s.isStepStarted()).isTrue();
        assertThat(s.getOpenPartType()).isEqualTo("text");
        assertThat(s.getOpenPartContent().toString()).isEqualTo("he");
    }

    @Test
    void messageBodyObject_extractsTextField() throws Exception {
        AssistantSquareDecoderSession s = new AssistantSquareDecoderSession();
        List<GatewayMessage> out = handler.handle("message",
                jn("{\"messageType\":\"TEXT\",\"messageId\":\"m1\",\"messageBody\":{\"text\":\"hello\"}}"), s);

        assertThat(out).hasSize(3);
        assertThat(eventType(out, 2)).isEqualTo("text.delta");
        assertThat(props(out, 2).path("content").asText()).isEqualTo("hello");
        assertThat(s.getOpenPartContent().toString()).isEqualTo("hello");
    }

    @Test
    void streamingAccumulates_thenDoneEmittedOnTypeSwitch() throws Exception {
        AssistantSquareDecoderSession s = new AssistantSquareDecoderSession();
        handler.handle("message", jn("{\"messageType\":\"TEXT\",\"messageId\":\"m1\",\"messageBody\":\"he\"}"), s);
        handler.handle("message", jn("{\"messageType\":\"TEXT\",\"messageId\":\"m1\",\"messageBody\":\"llo\"}"), s);
        assertThat(s.getOpenPartContent().toString()).isEqualTo("hello");

        List<GatewayMessage> out = handler.handle("think",
                jn("{\"messageId\":\"m1\",\"think\":\"hmm\"}"), s);

        assertThat(out).hasSize(2);
        assertThat(eventType(out, 0)).isEqualTo("text.done");
        assertThat(props(out, 0).path("content").asText()).isEqualTo("hello");
        assertThat(eventType(out, 1)).isEqualTo("thinking.delta");
        assertThat(s.getOpenPartType()).isEqualTo("thinking");
        assertThat(s.getOpenPartContent().toString()).isEqualTo("hmm");
    }

    @Test
    void messageIdSwitch_alsoTriggersDone() throws Exception {
        AssistantSquareDecoderSession s = new AssistantSquareDecoderSession();
        handler.handle("message", jn("{\"messageType\":\"TEXT\",\"messageId\":\"m1\",\"messageBody\":\"a\"}"), s);

        List<GatewayMessage> out = handler.handle("message",
                jn("{\"messageType\":\"TEXT\",\"messageId\":\"m2\",\"messageBody\":\"b\"}"), s);

        assertThat(out).hasSize(2);
        assertThat(eventType(out, 0)).isEqualTo("text.done");
        assertThat(props(out, 0).path("messageId").asText()).isEqualTo("m1");
        assertThat(eventType(out, 1)).isEqualTo("text.delta");
        assertThat(props(out, 1).path("messageId").asText()).isEqualTo("m2");
    }

    @Test
    void singleEventInterruptsStreaming_emitsDoneFirst() throws Exception {
        AssistantSquareDecoderSession s = new AssistantSquareDecoderSession();
        handler.handle("message", jn("{\"messageType\":\"TEXT\",\"messageId\":\"m1\",\"messageBody\":\"x\"}"), s);

        List<GatewayMessage> out = handler.handle("searching",
                jn("{\"messageId\":\"m1\",\"searching\":[\"k1\",\"k2\"]}"), s);

        assertThat(out).hasSize(2);
        assertThat(eventType(out, 0)).isEqualTo("text.done");
        assertThat(eventType(out, 1)).isEqualTo("searching");
        assertThat(props(out, 1).path("keywords")).isEqualTo(jn("[\"k1\",\"k2\"]"));
        assertThat(s.getOpenPartType()).isNull();
    }

    @Test
    void planningAndAskMore_areMappedFromMessageBody() throws Exception {
        AssistantSquareDecoderSession s = new AssistantSquareDecoderSession();
        List<GatewayMessage> p = handler.handle("planning",
                jn("{\"messageType\":\"PLANNING\",\"messageId\":\"m1\",\"messageBody\":{\"planning\":\"plan\"}}"), s);
        assertThat(p).hasSize(3);
        assertThat(eventType(p, 2)).isEqualTo("planning.delta");
        assertThat(props(p, 2).path("content").asText()).isEqualTo("plan");

        List<GatewayMessage> a = handler.handle("askMore",
                jn("{\"messageId\":\"m1\",\"messageBody\":{\"askMore\":[\"q1\",\"q2\"]}}"), s);
        assertThat(a).hasSize(2);
        assertThat(eventType(a, 0)).isEqualTo("planning.done");
        assertThat(eventType(a, 1)).isEqualTo("ask_more");
        assertThat(props(a, 1).path("askMoreQuestions")).isEqualTo(jn("[\"q1\",\"q2\"]"));
    }

    @Test
    void processStep_isMappedToThinkingDelta() throws Exception {
        AssistantSquareDecoderSession s = new AssistantSquareDecoderSession();
        List<GatewayMessage> out = handler.handle("processStep",
                jn("{\"messageId\":\"m1\",\"processStep\":{\"type\":\"TEXT\",\"code\":\"think\",\"message\":\"thinking\"}}"), s);

        assertThat(out).hasSize(3);
        assertThat(eventType(out, 0)).isEqualTo("session.status");
        assertThat(eventType(out, 1)).isEqualTo("step.start");
        assertThat(eventType(out, 2)).isEqualTo("thinking.delta");
        assertThat(props(out, 2).path("content").asText()).isEqualTo("thinking");
    }

    @Test
    void standardSingleEvents_readPayloadFromMessageBody() throws Exception {
        AssistantSquareDecoderSession s = new AssistantSquareDecoderSession();
        handler.handle("route", jn("{\"messageId\":\"m1\"}"), s);

        List<GatewayMessage> searching = handler.handle("searching",
                jn("{\"messageId\":\"m1\",\"messageBody\":{\"searching\":[\"JDK8\",\"JDK21\"]}}"), s);
        assertThat(searching).hasSize(1);
        assertThat(eventType(searching, 0)).isEqualTo("searching");
        assertThat(props(searching, 0).path("keywords")).isEqualTo(jn("[\"JDK8\",\"JDK21\"]"));

        List<GatewayMessage> results = handler.handle("searchResult",
                jn("{\"messageId\":\"m1\",\"messageBody\":{\"searchResult\":[{\"index\":1,\"title\":\"java\"}]}}"), s);
        assertThat(results).hasSize(1);
        assertThat(eventType(results, 0)).isEqualTo("search_result");
        assertThat(props(results, 0).path("results").get(0).path("title").asText()).isEqualTo("java");

        List<GatewayMessage> references = handler.handle("reference",
                jn("{\"messageId\":\"m1\",\"messageBody\":{\"references\":[{\"index\":1,\"url\":\"https://example.com\"}]}}"), s);
        assertThat(references).hasSize(1);
        assertThat(eventType(references, 0)).isEqualTo("reference");
        assertThat(props(references, 0).path("references").get(0).path("url").asText())
                .isEqualTo("https://example.com");
    }

    @Test
    void unsupportedMessageType_isDroppedButStillCompliesWithStepStart() throws Exception {
        AssistantSquareDecoderSession s = new AssistantSquareDecoderSession();
        List<GatewayMessage> out = handler.handle("message",
                jn("{\"messageType\":\"HTML\",\"messageId\":\"m1\",\"messageBody\":\"<p>x</p>\"}"), s);

        assertThat(out).hasSize(2);
        assertThat(eventType(out, 0)).isEqualTo("session.status");
        assertThat(eventType(out, 1)).isEqualTo("step.start");
        assertThat(s.isStepStarted()).isTrue();
        assertThat(s.getOpenPartType()).isNull();
    }

    @Test
    void questionTextList_isDroppedAfterStepStarted() throws Exception {
        AssistantSquareDecoderSession s = new AssistantSquareDecoderSession();
        handler.handle("route", jn("{\"messageId\":\"m1\"}"), s);

        List<GatewayMessage> out = handler.handle("question",
                jn("{\"messageType\":\"TEXT_LIST\",\"messageId\":\"m1\",\"messageBody\":{\"textList\":[\"q1\"]}}"), s);

        assertThat(out).isEmpty();
    }

    @Test
    void errorEvent_emitsTopLevelToolError() throws Exception {
        AssistantSquareDecoderSession s = new AssistantSquareDecoderSession();
        List<GatewayMessage> out = handler.handle("error",
                jn("{\"message\":\"upstream blew up\"}"), s);
        assertThat(out).hasSize(1);
        assertThat(out.get(0).getType()).isEqualTo(GatewayMessage.Type.TOOL_ERROR);
        assertThat(out.get(0).getError()).isEqualTo("upstream blew up");
    }

    @Test
    void flush_emitsPartDoneStepDoneThenIdle() throws Exception {
        AssistantSquareDecoderSession s = new AssistantSquareDecoderSession();
        handler.handle("message", jn("{\"messageType\":\"TEXT\",\"messageId\":\"m1\",\"messageBody\":\"all\"}"), s);

        List<GatewayMessage> out = handler.flush(s);
        assertThat(out).hasSize(3);
        assertThat(eventType(out, 0)).isEqualTo("text.done");
        assertThat(props(out, 0).path("content").asText()).isEqualTo("all");
        assertThat(eventType(out, 1)).isEqualTo("step.done");
        assertThat(out.get(1).getUsage()).isNull();
        assertThat(eventType(out, 2)).isEqualTo("session.status");
        assertThat(props(out, 2).path("status").asText()).isEqualTo("idle");
    }

    @Test
    void flush_onlyStepStarted_emitsStepDoneThenIdle() {
        AssistantSquareDecoderSession s = new AssistantSquareDecoderSession();
        s.setStepStarted(true);
        List<GatewayMessage> out = handler.flush(s);
        assertThat(out).hasSize(2);
        assertThat(eventType(out, 0)).isEqualTo("step.done");
        assertThat(eventType(out, 1)).isEqualTo("session.status");
        assertThat(props(out, 1).path("status").asText()).isEqualTo("idle");
    }

    private static String eventType(List<GatewayMessage> out, int index) {
        return out.get(index).getEvent().path("type").asText();
    }

    private static JsonNode props(List<GatewayMessage> out, int index) {
        return out.get(index).getEvent().path("properties");
    }
}
