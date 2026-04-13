package com.opencode.cui.skill.service.delivery;

import com.opencode.cui.skill.model.SkillSession;
import com.opencode.cui.skill.model.StreamMessage;
import com.opencode.cui.skill.service.ImOutboundService;
import com.opencode.cui.skill.ws.ExternalStreamHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ImRestDeliveryStrategyTest {

    @Mock private ImOutboundService imOutboundService;
    @Mock private ExternalStreamHandler externalStreamHandler;
    @InjectMocks private ImRestDeliveryStrategy strategy;

    @Test
    @DisplayName("supports IM domain when no active WS connections")
    void supportsImWithoutWs() {
        SkillSession session = SkillSession.builder().businessSessionDomain("im").build();
        when(externalStreamHandler.hasActiveConnections("im")).thenReturn(false);
        assertTrue(strategy.supports(session));
    }

    @Test
    @DisplayName("does not support IM domain when WS connections exist")
    void doesNotSupportImWithWs() {
        SkillSession session = SkillSession.builder().businessSessionDomain("im").build();
        when(externalStreamHandler.hasActiveConnections("im")).thenReturn(true);
        assertFalse(strategy.supports(session));
    }

    @Test
    @DisplayName("does not support miniapp domain")
    void doesNotSupportMiniapp() {
        SkillSession session = SkillSession.builder().businessSessionDomain("miniapp").build();
        assertFalse(strategy.supports(session));
    }

    @Test
    @DisplayName("delivers text_done content via sendTextToIm")
    void deliversTextDone() {
        SkillSession session = SkillSession.builder()
                .businessSessionDomain("im").businessSessionType("direct")
                .businessSessionId("dm-001").assistantAccount("assist-01").build();
        StreamMessage msg = StreamMessage.builder()
                .type(StreamMessage.Types.TEXT_DONE).content("Hello from agent").build();
        strategy.deliver(session, "sess-1", "user-42", msg);
        verify(imOutboundService).sendTextToIm("direct", "dm-001", "Hello from agent", "assist-01");
    }

    @Test
    @DisplayName("does not call sendTextToIm for non-text types like DELTA")
    void ignoresNonTextTypes() {
        SkillSession session = SkillSession.builder()
                .businessSessionDomain("im").businessSessionType("direct")
                .businessSessionId("dm-001").assistantAccount("assist-01").build();
        StreamMessage msg = StreamMessage.builder()
                .type(StreamMessage.Types.TEXT_DELTA).content("partial").build();
        strategy.deliver(session, "sess-1", "user-42", msg);
        verify(imOutboundService, never()).sendTextToIm(any(), any(), any(), any());
    }

    @Test
    @DisplayName("order is 3")
    void orderIsThree() { assertEquals(3, strategy.order()); }
}
