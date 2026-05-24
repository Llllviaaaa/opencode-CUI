package com.opencode.cui.skill.service.delivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.logging.MdcConstants;
import com.opencode.cui.skill.logging.MdcHelper;
import com.opencode.cui.skill.model.SkillSession;
import com.opencode.cui.skill.model.StreamMessage;
import com.opencode.cui.skill.service.ExternalWsRegistry;
import com.opencode.cui.skill.service.RedisMessageBroker;
import com.opencode.cui.skill.ws.ExternalStreamHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExternalWsDeliveryStrategyTest {

    @Mock private ExternalStreamHandler externalStreamHandler;
    @Mock private ExternalWsRegistry wsRegistry;
    @Mock private RedisMessageBroker redisMessageBroker;
    @Spy private ObjectMapper objectMapper = new ObjectMapper();
    @InjectMocks private ExternalWsDeliveryStrategy strategy;

    @AfterEach
    void tearDown() {
        MdcHelper.clearAll();
    }

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
        verify(wsRegistry, never()).findInstancesWithConnection(anyString());
    }

    @Test
    @DisplayName("L2: accepted relay publish stops without trying other candidates")
    void l2RemoteRelay_publishAcceptedStops() {
        SkillSession session = SkillSession.builder().businessSessionDomain("im").build();
        StreamMessage msg = StreamMessage.builder().type("text.done").content("hello").build();
        when(redisMessageBroker.nextStreamSeq("sess-1")).thenReturn(1L);
        when(externalStreamHandler.pushToOne(eq("im"), anyString())).thenReturn(false);
        when(wsRegistry.findInstancesWithConnection("im"))
                .thenReturn(List.of("ss-pod-2", "ss-pod-3"));
        when(redisMessageBroker.publishToExternalRelayBestEffort(eq("ss-pod-2"), anyString()))
                .thenReturn(true);

        strategy.deliver(session, "sess-1", "user-42", msg);

        verify(redisMessageBroker).publishToExternalRelayBestEffort(eq("ss-pod-2"), anyString());
        verify(redisMessageBroker, never()).publishToExternalRelayBestEffort(eq("ss-pod-3"), anyString());
        verify(redisMessageBroker, never()).publishToExternalRelay(eq("ss-pod-2"), anyString());
    }

    @Test
    @DisplayName("L2: relay envelope carries MDC trace context across pubsub")
    void l2RemoteRelay_envelopeCarriesTraceContext() throws Exception {
        MdcHelper.putTraceId("trace-001");
        MdcHelper.putAk("ak-001");
        SkillSession session = SkillSession.builder().businessSessionDomain("im").build();
        StreamMessage msg = StreamMessage.builder().type(StreamMessage.Types.TEXT_DONE).content("hello").build();
        when(redisMessageBroker.nextStreamSeq("sess-1")).thenReturn(1L);
        when(externalStreamHandler.pushToOne(eq("im"), anyString())).thenReturn(false);
        when(wsRegistry.findInstancesWithConnection("im")).thenReturn(List.of("ss-pod-2"));
        when(redisMessageBroker.publishToExternalRelayBestEffort(eq("ss-pod-2"), anyString()))
                .thenReturn(true);
        ArgumentCaptor<String> relayCaptor = ArgumentCaptor.forClass(String.class);

        strategy.deliver(session, "sess-1", "user-42", msg);

        verify(redisMessageBroker).publishToExternalRelayBestEffort(eq("ss-pod-2"), relayCaptor.capture());
        var envelope = objectMapper.readTree(relayCaptor.getValue());
        assertEquals("im", envelope.path("domain").asText());
        assertEquals("trace-001", envelope.path(MdcConstants.TRACE_ID).asText());
        assertEquals("sess-1", envelope.path("welinkSessionId").asText());
        assertEquals("ak-001", envelope.path(MdcConstants.AK).asText());
        assertEquals("user-42", envelope.path(MdcConstants.USER_ID).asText());

        var payload = objectMapper.readTree(envelope.path("payload").asText());
        assertEquals(StreamMessage.Types.TEXT_DONE, payload.path("type").asText());
        assertEquals("sess-1", payload.path("welinkSessionId").asText());
    }

    @Test
    @DisplayName("no WS connection skips without IM REST fallback")
    void noWsConnections_skipsWithoutFallback() {
        SkillSession session = SkillSession.builder()
                .businessSessionDomain("im")
                .businessSessionType("direct")
                .businessSessionId("dm-001")
                .assistantAccount("assist-01")
                .build();
        StreamMessage msg = StreamMessage.builder()
                .type(StreamMessage.Types.TEXT_DONE)
                .content("should not go to IM REST")
                .build();
        when(redisMessageBroker.nextStreamSeq("sess-1")).thenReturn(1L);
        when(externalStreamHandler.pushToOne(eq("im"), anyString())).thenReturn(false);
        when(wsRegistry.findInstancesWithConnection("im")).thenReturn(List.of());

        strategy.deliver(session, "sess-1", "user-42", msg);

        verify(redisMessageBroker, never()).publishToExternalRelayBestEffort(anyString(), anyString());
        verify(redisMessageBroker, never()).publishToExternalRelay(anyString(), anyString());
    }

    @Test
    @DisplayName("all remote publish attempts fail then stop without IM REST fallback")
    void allRemoteRelayPublishesFail_stopsWithoutFallback() {
        SkillSession session = SkillSession.builder().businessSessionDomain("im").build();
        StreamMessage msg = StreamMessage.builder()
                .type(StreamMessage.Types.TEXT_DONE)
                .content("should not go to IM REST")
                .build();
        when(redisMessageBroker.nextStreamSeq("sess-1")).thenReturn(1L);
        when(externalStreamHandler.pushToOne(eq("im"), anyString())).thenReturn(false);
        when(wsRegistry.findInstancesWithConnection("im"))
                .thenReturn(List.of("ss-pod-2", "ss-pod-3"));
        when(redisMessageBroker.publishToExternalRelayBestEffort(anyString(), anyString()))
                .thenReturn(false);

        strategy.deliver(session, "sess-1", "user-42", msg);

        verify(redisMessageBroker).publishToExternalRelayBestEffort(eq("ss-pod-2"), anyString());
        verify(redisMessageBroker).publishToExternalRelayBestEffort(eq("ss-pod-3"), anyString());
        verify(redisMessageBroker, never()).publishToExternalRelay(anyString(), anyString());
    }

    @Test
    @DisplayName("order is 2")
    void orderIsTwo() {
        assertEquals(2, strategy.order());
    }

    @Test
    @DisplayName("serialized JSON preserves welinkSessionId for error events")
    void deliver_errorEvent_serializedJsonContainsWelinkSessionId() throws Exception {
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

        strategy.deliver(session, "101", null, msg);

        verify(externalStreamHandler).pushToOne(anyString(), jsonCap.capture());
        var payload = objectMapper.readTree(jsonCap.getValue());
        assertEquals("101", payload.path("welinkSessionId").asText());
        assertEquals("error", payload.path("type").asText());
    }

    @Test
    @DisplayName("deliver creates traceId when caller did not provide one")
    void deliver_createsTraceIdWhenMissing() {
        SkillSession session = SkillSession.builder().businessSessionDomain("im").build();
        StreamMessage msg = StreamMessage.builder().type(StreamMessage.Types.TEXT_DONE).content("hello").build();
        when(redisMessageBroker.nextStreamSeq("sess-1")).thenReturn(1L);
        when(externalStreamHandler.pushToOne(eq("im"), anyString())).thenReturn(false);
        when(wsRegistry.findInstancesWithConnection("im")).thenReturn(List.of());

        strategy.deliver(session, "sess-1", "user-42", msg);

        assertTrue(MDC.get(MdcConstants.TRACE_ID) != null && !MDC.get(MdcConstants.TRACE_ID).isBlank());
    }
}
