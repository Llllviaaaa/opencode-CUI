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

    private JsonNode jn(String s) throws Exception { return om.readTree(s); }

    @Test
    void firstEvent_emitsStepStart_thenDelta() throws Exception {
        AssistantSquareDecoderSession s = new AssistantSquareDecoderSession();
        List<GatewayMessage> out = handler.handle("message",
                jn("{\"messageType\":\"TEXT\",\"messageId\":\"m1\",\"messageBody\":\"he\"}"), s);

        assertThat(out).hasSize(2);
        assertThat(out.get(0).getEvent().path("type").asText()).isEqualTo("step.start");
        assertThat(out.get(1).getEvent().path("type").asText()).isEqualTo("text.delta");
        assertThat(out.get(1).getEvent().path("properties").path("content").asText()).isEqualTo("he");
        assertThat(s.isStepStarted()).isTrue();
        assertThat(s.getOpenPartType()).isEqualTo("text");
        assertThat(s.getOpenPartContent().toString()).isEqualTo("he");
    }

    @Test
    void streamingAccumulates_thenDoneEmittedOnTypeSwitch() throws Exception {
        AssistantSquareDecoderSession s = new AssistantSquareDecoderSession();
        handler.handle("message", jn("{\"messageType\":\"TEXT\",\"messageId\":\"m1\",\"messageBody\":\"he\"}"), s);
        handler.handle("message", jn("{\"messageType\":\"TEXT\",\"messageId\":\"m1\",\"messageBody\":\"llo\"}"), s);
        assertThat(s.getOpenPartContent().toString()).isEqualTo("hello");

        // Switch to thinking
        List<GatewayMessage> out = handler.handle("think",
                jn("{\"messageId\":\"m1\",\"think\":\"hmm\"}"), s);

        // Should emit: text.done(content="hello"), thinking.delta(content="hmm")
        assertThat(out).hasSize(2);
        assertThat(out.get(0).getEvent().path("type").asText()).isEqualTo("text.done");
        assertThat(out.get(0).getEvent().path("properties").path("content").asText()).isEqualTo("hello");
        assertThat(out.get(1).getEvent().path("type").asText()).isEqualTo("thinking.delta");
        assertThat(s.getOpenPartType()).isEqualTo("thinking");
        assertThat(s.getOpenPartContent().toString()).isEqualTo("hmm");
    }

    @Test
    void messageIdSwitch_alsoTriggersDone() throws Exception {
        AssistantSquareDecoderSession s = new AssistantSquareDecoderSession();
        handler.handle("message", jn("{\"messageType\":\"TEXT\",\"messageId\":\"m1\",\"messageBody\":\"a\"}"), s);

        List<GatewayMessage> out = handler.handle("message",
                jn("{\"messageType\":\"TEXT\",\"messageId\":\"m2\",\"messageBody\":\"b\"}"), s);

        // Expect text.done(content="a", messageId="m1"), text.delta(content="b", messageId="m2")
        assertThat(out).hasSize(2);
        assertThat(out.get(0).getEvent().path("type").asText()).isEqualTo("text.done");
        assertThat(out.get(0).getEvent().path("properties").path("messageId").asText()).isEqualTo("m1");
        assertThat(out.get(1).getEvent().path("type").asText()).isEqualTo("text.delta");
        assertThat(out.get(1).getEvent().path("properties").path("messageId").asText()).isEqualTo("m2");
    }

    @Test
    void singleEventInterruptsStreaming_emitsDoneFirst() throws Exception {
        AssistantSquareDecoderSession s = new AssistantSquareDecoderSession();
        handler.handle("message", jn("{\"messageType\":\"TEXT\",\"messageId\":\"m1\",\"messageBody\":\"x\"}"), s);

        List<GatewayMessage> out = handler.handle("searching",
                jn("{\"messageId\":\"m1\",\"searching\":[\"k1\",\"k2\"]}"), s);

        // text.done first then searching
        assertThat(out).hasSize(2);
        assertThat(out.get(0).getEvent().path("type").asText()).isEqualTo("text.done");
        assertThat(out.get(1).getEvent().path("type").asText()).isEqualTo("searching");
        assertThat(out.get(1).getEvent().path("properties").path("keywords").isArray()).isTrue();
        assertThat(s.getOpenPartType()).isNull();
    }

    @Test
    void planningAndAskMore_areMapped() throws Exception {
        AssistantSquareDecoderSession s = new AssistantSquareDecoderSession();
        List<GatewayMessage> p = handler.handle("planning",
                jn("{\"messageType\":\"PLANNING\",\"messageId\":\"m1\",\"planning\":\"用户\"}"), s);
        assertThat(p.get(1).getEvent().path("type").asText()).isEqualTo("planning.delta");

        List<GatewayMessage> a = handler.handle("askMore",
                jn("{\"messageId\":\"m1\",\"askMore\":[{\"q\":\"x\"}]}"), s);
        // Expect planning.done + ask_more
        assertThat(a.get(0).getEvent().path("type").asText()).isEqualTo("planning.done");
        assertThat(a.get(1).getEvent().path("type").asText()).isEqualTo("ask_more");
    }

    @Test
    void unsupportedMessageType_isDroppedButStillCompliesWithStepStart() throws Exception {
        AssistantSquareDecoderSession s = new AssistantSquareDecoderSession();
        // HTML/卡片/processStep 等丢弃
        List<GatewayMessage> out = handler.handle("message",
                jn("{\"messageType\":\"HTML\",\"messageId\":\"m1\",\"messageBody\":\"<p>x</p>\"}"), s);
        // step.start 仍补一次
        assertThat(out).hasSize(1);
        assertThat(out.get(0).getEvent().path("type").asText()).isEqualTo("step.start");
        assertThat(s.isStepStarted()).isTrue();
        assertThat(s.getOpenPartType()).isNull();
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
    void flush_emitsPartDoneThenStepDone() throws Exception {
        AssistantSquareDecoderSession s = new AssistantSquareDecoderSession();
        handler.handle("message", jn("{\"messageType\":\"TEXT\",\"messageId\":\"m1\",\"messageBody\":\"all\"}"), s);

        List<GatewayMessage> out = handler.flush(s);
        assertThat(out).hasSize(2);
        assertThat(out.get(0).getEvent().path("type").asText()).isEqualTo("text.done");
        assertThat(out.get(0).getEvent().path("properties").path("content").asText()).isEqualTo("all");
        assertThat(out.get(1).getEvent().path("type").asText()).isEqualTo("step.done");
        // usage 留空（不写到 GatewayMessage.usage）
        assertThat(out.get(1).getUsage()).isNull();
    }

    @Test
    void flush_onlyStepStarted_emitsStepDoneOnly() {
        AssistantSquareDecoderSession s = new AssistantSquareDecoderSession();
        s.setStepStarted(true);
        List<GatewayMessage> out = handler.flush(s);
        assertThat(out).hasSize(1);
        assertThat(out.get(0).getEvent().path("type").asText()).isEqualTo("step.done");
    }
}
