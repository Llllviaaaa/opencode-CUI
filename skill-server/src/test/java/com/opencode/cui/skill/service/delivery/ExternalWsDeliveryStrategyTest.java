package com.opencode.cui.skill.service.delivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.model.SkillSession;
import com.opencode.cui.skill.model.StreamMessage;
import com.opencode.cui.skill.service.RedisMessageBroker;
import com.opencode.cui.skill.ws.ExternalStreamHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
    @Mock private RedisMessageBroker redisMessageBroker;
    @Spy private ObjectMapper objectMapper = new ObjectMapper();
    @InjectMocks private ExternalWsDeliveryStrategy strategy;

    @Test
    @DisplayName("supports non-miniapp domain with active WS connections")
    void supportsWithActiveConnections() {
        SkillSession session = SkillSession.builder().businessSessionDomain("im").build();
        when(externalStreamHandler.hasActiveConnections("im")).thenReturn(true);
        assertTrue(strategy.supports(session));
    }

    @Test
    @DisplayName("does not support when no WS connections")
    void doesNotSupportWithoutConnections() {
        SkillSession session = SkillSession.builder().businessSessionDomain("im").build();
        when(externalStreamHandler.hasActiveConnections("im")).thenReturn(false);
        assertFalse(strategy.supports(session));
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
    @DisplayName("delivers to stream:{domain} via Redis")
    void deliversToStreamChannel() {
        SkillSession session = SkillSession.builder().businessSessionDomain("im").build();
        StreamMessage msg = StreamMessage.builder().type("delta").content("hello").build();
        strategy.deliver(session, "sess-1", "user-42", msg);
        verify(redisMessageBroker).publishToChannel(eq("stream:im"), any(String.class));
    }

    @Test
    @DisplayName("order is 2")
    void orderIsTwo() { assertEquals(2, strategy.order()); }
}
