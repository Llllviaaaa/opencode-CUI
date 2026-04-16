package com.opencode.cui.skill.service.delivery;

import com.opencode.cui.skill.config.DeliveryProperties;
import com.opencode.cui.skill.model.SkillSession;
import com.opencode.cui.skill.model.StreamMessage;
import com.opencode.cui.skill.service.RedisMessageBroker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OutboundDeliveryDispatcherTest {

    @Mock private MiniappDeliveryStrategy miniappStrategy;
    @Mock private ExternalWsDeliveryStrategy externalWsStrategy;
    @Mock private ImRestDeliveryStrategy imRestStrategy;
    @Mock private RedisMessageBroker redisMessageBroker;
    @Mock private DeliveryProperties deliveryProperties;

    private OutboundDeliveryDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        when(miniappStrategy.order()).thenReturn(1);
        when(externalWsStrategy.order()).thenReturn(2);
        when(imRestStrategy.order()).thenReturn(3);
        dispatcher = new OutboundDeliveryDispatcher(
                List.of(miniappStrategy, externalWsStrategy, imRestStrategy),
                deliveryProperties, redisMessageBroker);
    }

    @Test
    @DisplayName("miniapp domain always goes to miniapp strategy via first-match")
    void miniappRouting() {
        SkillSession session = SkillSession.builder().businessSessionDomain("miniapp").build();
        when(miniappStrategy.supports(session)).thenReturn(true);
        StreamMessage msg = StreamMessage.builder().type("text.done").build();
        dispatcher.deliver(session, "sess-1", "user-1", msg);
        verify(miniappStrategy).deliver(session, "sess-1", "user-1", msg);
    }

    @Test
    @DisplayName("mode=rest routes IM domain to ImRest via first-match")
    void modeRestImDomain() {
        when(deliveryProperties.isWsMode()).thenReturn(false);
        when(miniappStrategy.supports(any())).thenReturn(false);
        when(externalWsStrategy.supports(any())).thenReturn(false);
        when(imRestStrategy.supports(any())).thenReturn(true);
        SkillSession session = SkillSession.builder().businessSessionDomain("im").build();
        StreamMessage msg = StreamMessage.builder().type("text.done").build();
        dispatcher.deliver(session, "sess-1", "user-1", msg);
        verify(imRestStrategy).deliver(session, "sess-1", "user-1", msg);
        verify(externalWsStrategy, never()).deliver(any(), any(), any(), any());
    }

    @Test
    @DisplayName("mode=ws + invoke-source=IM routes to ImRest by type")
    void modeWsInvokeSourceIm() {
        when(deliveryProperties.isWsMode()).thenReturn(true);
        when(deliveryProperties.getInvokeSourceTtlSeconds()).thenReturn(300);
        when(redisMessageBroker.getInvokeSource("sess-1")).thenReturn("IM");
        SkillSession session = SkillSession.builder().id(1L).businessSessionDomain("im").build();
        StreamMessage msg = StreamMessage.builder().type("text.done").build();
        dispatcher.deliver(session, "sess-1", "user-1", msg);
        verify(imRestStrategy).deliver(session, "sess-1", "user-1", msg);
        verify(externalWsStrategy, never()).deliver(any(), any(), any(), any());
    }

    @Test
    @DisplayName("mode=ws + invoke-source=EXTERNAL routes to ExternalWs by type")
    void modeWsInvokeSourceExternal() {
        when(deliveryProperties.isWsMode()).thenReturn(true);
        when(deliveryProperties.getInvokeSourceTtlSeconds()).thenReturn(300);
        when(redisMessageBroker.getInvokeSource("sess-1")).thenReturn("EXTERNAL");
        SkillSession session = SkillSession.builder().id(1L).businessSessionDomain("im").build();
        StreamMessage msg = StreamMessage.builder().type("text.done").build();
        dispatcher.deliver(session, "sess-1", "user-1", msg);
        verify(externalWsStrategy).deliver(session, "sess-1", "user-1", msg);
        verify(imRestStrategy, never()).deliver(any(), any(), any(), any());
    }

    @Test
    @DisplayName("mode=ws + no invoke-source falls back to first-match, IM goes to ImRest")
    void modeWsNoInvokeSourceFallback() {
        when(deliveryProperties.isWsMode()).thenReturn(true);
        when(miniappStrategy.supports(any())).thenReturn(false);
        when(externalWsStrategy.supports(any())).thenReturn(false);
        when(imRestStrategy.supports(any())).thenReturn(true);
        when(redisMessageBroker.getInvokeSource("sess-1")).thenReturn(null);
        SkillSession session = SkillSession.builder().id(1L).businessSessionDomain("im").build();
        StreamMessage msg = StreamMessage.builder().type("text.done").build();
        dispatcher.deliver(session, "sess-1", "user-1", msg);
        verify(imRestStrategy).deliver(session, "sess-1", "user-1", msg);
        verify(externalWsStrategy, never()).deliver(any(), any(), any(), any());
    }

    @Test
    @DisplayName("mode=ws + invoke-source=IM renews TTL")
    void modeWsRenewsTtl() {
        when(deliveryProperties.isWsMode()).thenReturn(true);
        when(deliveryProperties.getInvokeSourceTtlSeconds()).thenReturn(300);
        when(redisMessageBroker.getInvokeSource("sess-1")).thenReturn("IM");
        SkillSession session = SkillSession.builder().id(1L).businessSessionDomain("im").build();
        StreamMessage msg = StreamMessage.builder().type("text.done").build();
        dispatcher.deliver(session, "sess-1", "user-1", msg);
        verify(redisMessageBroker).expireInvokeSource("sess-1", 300);
    }
}
