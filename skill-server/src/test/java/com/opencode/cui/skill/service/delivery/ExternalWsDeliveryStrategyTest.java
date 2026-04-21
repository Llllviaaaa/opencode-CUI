package com.opencode.cui.skill.service.delivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.model.SkillSession;
import com.opencode.cui.skill.model.StreamMessage;
import com.opencode.cui.skill.service.ExternalWsRegistry;
import com.opencode.cui.skill.service.ImOutboundService;
import com.opencode.cui.skill.service.RedisMessageBroker;
import com.opencode.cui.skill.ws.ExternalStreamHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ExternalWsDeliveryStrategyTest {

    @Mock private ExternalStreamHandler externalStreamHandler;
    @Mock private ExternalWsRegistry wsRegistry;
    @Mock private RedisMessageBroker redisMessageBroker;
    @Mock private ImOutboundService imOutboundService;
    @Spy private ObjectMapper objectMapper = new ObjectMapper();
    @InjectMocks private ExternalWsDeliveryStrategy strategy;

    @Test
    @DisplayName("supports non-miniapp non-im domain")
    void supportsNonMiniapp() {
        SkillSession session = SkillSession.builder().businessSessionDomain("ext").build();
        assertTrue(strategy.supports(session));
    }

    @Test
    @DisplayName("does not support miniapp domain")
    void doesNotSupportMiniapp() {
        SkillSession session = SkillSession.builder().businessSessionDomain("miniapp").build();
        assertFalse(strategy.supports(session));
    }

    @Test
    @DisplayName("does not support null session")
    void doesNotSupportNull() {
        assertFalse(strategy.supports(null));
    }

    @Test
    @DisplayName("L1: delivers via local pushToOne when local connections exist")
    void l1LocalDelivery() {
        SkillSession session = SkillSession.builder().businessSessionDomain("im").build();
        StreamMessage msg = StreamMessage.builder().type("text.done").content("hello").build();
        when(redisMessageBroker.nextStreamSeq("sess-1")).thenReturn(1L);
        when(externalStreamHandler.pushToOne(eq("im"), anyString())).thenReturn(true);

        strategy.deliver(session, "sess-1", "user-42", msg);

        verify(externalStreamHandler).pushToOne(eq("im"), anyString());
        verify(wsRegistry, never()).findInstanceWithConnection(any());
    }

    @Test
    @DisplayName("L2: relays to remote SS when no local connections")
    void l2RemoteRelay() {
        SkillSession session = SkillSession.builder().businessSessionDomain("im").build();
        StreamMessage msg = StreamMessage.builder().type("text.done").content("hello").build();
        when(redisMessageBroker.nextStreamSeq("sess-1")).thenReturn(1L);
        when(externalStreamHandler.pushToOne(eq("im"), anyString())).thenReturn(false);
        when(wsRegistry.findInstanceWithConnection("im")).thenReturn("ss-pod-2");

        strategy.deliver(session, "sess-1", "user-42", msg);

        verify(redisMessageBroker).publishToChannel(eq("ss:external-relay:ss-pod-2"), anyString());
    }

    @Test
    @DisplayName("L3: falls back to ImRest for IM domain when no WS connections anywhere")
    void l3FallbackImRest() {
        SkillSession session = SkillSession.builder()
                .businessSessionDomain("im").businessSessionType("direct")
                .businessSessionId("dm-001").assistantAccount("assist-01").build();
        StreamMessage msg = StreamMessage.builder()
                .type(StreamMessage.Types.TEXT_DONE).content("fallback msg").build();
        when(redisMessageBroker.nextStreamSeq("sess-1")).thenReturn(1L);
        when(externalStreamHandler.pushToOne(eq("im"), anyString())).thenReturn(false);
        when(wsRegistry.findInstanceWithConnection("im")).thenReturn(null);

        strategy.deliver(session, "sess-1", "user-42", msg);

        verify(imOutboundService).sendTextToIm("direct", "dm-001", "fallback msg", "assist-01");
    }

    @Test
    @DisplayName("L3: discards for non-IM domain when no WS connections anywhere")
    void l3DiscardNonIm() {
        SkillSession session = SkillSession.builder().businessSessionDomain("custom").build();
        StreamMessage msg = StreamMessage.builder().type("text.done").content("hello").build();
        when(redisMessageBroker.nextStreamSeq("sess-1")).thenReturn(1L);
        when(externalStreamHandler.pushToOne(eq("custom"), anyString())).thenReturn(false);
        when(wsRegistry.findInstanceWithConnection("custom")).thenReturn(null);

        strategy.deliver(session, "sess-1", "user-42", msg);

        verify(imOutboundService, never()).sendTextToIm(any(), any(), any(), any());
        verify(redisMessageBroker, never()).publishToChannel(startsWith("ss:external-relay:"), any());
    }

    @Test
    @DisplayName("order is 2")
    void orderIsTwo() { assertEquals(2, strategy.order()); }

    @Test
    @DisplayName("serialized JSON preserves welinkSessionId for error events")
    void deliver_errorEvent_serializedJsonContainsWelinkSessionId() throws Exception {
        // given: 一条 enrich 过的 error StreamMessage（模拟 emitter 输出状态）
        SkillSession session = SkillSession.builder().businessSessionDomain("ext-domain").build();

        StreamMessage msg = StreamMessage.builder()
                .type(StreamMessage.Types.ERROR)
                .error("agent offline")
                .build();
        msg.setSessionId("101");
        msg.setWelinkSessionId("101");

        when(redisMessageBroker.nextStreamSeq("101")).thenReturn(1L);
        when(externalStreamHandler.pushToOne(anyString(), anyString())).thenReturn(true);
        ArgumentCaptor<String> jsonCap = ArgumentCaptor.forClass(String.class);

        // when
        strategy.deliver(session, "101", null, msg);

        // then: 捕获发出的 JSON payload，断言含 welinkSessionId
        verify(externalStreamHandler).pushToOne(anyString(), jsonCap.capture());
        var payload = objectMapper.readTree(jsonCap.getValue());
        assertEquals("101", payload.path("welinkSessionId").asText());
        assertEquals("error", payload.path("type").asText());
    }
}
