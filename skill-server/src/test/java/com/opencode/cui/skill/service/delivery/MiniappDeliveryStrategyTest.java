package com.opencode.cui.skill.service.delivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.model.SkillSession;
import com.opencode.cui.skill.model.StreamMessage;
import com.opencode.cui.skill.service.RedisMessageBroker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MiniappDeliveryStrategyTest {

    @Mock private RedisMessageBroker redisMessageBroker;
    @Spy private ObjectMapper objectMapper = new ObjectMapper();
    @InjectMocks private MiniappDeliveryStrategy strategy;

    @Test
    @DisplayName("supports miniapp domain session")
    void supportsMiniapp() {
        SkillSession session = SkillSession.builder().businessSessionDomain("miniapp").build();
        assertTrue(strategy.supports(session));
    }

    @Test
    @DisplayName("supports null session (treated as miniapp)")
    void supportsNullSession() {
        assertTrue(strategy.supports(null));
    }

    @Test
    @DisplayName("does not support IM domain session")
    void doesNotSupportIm() {
        SkillSession session = SkillSession.builder().businessSessionDomain("im").build();
        assertFalse(strategy.supports(session));
    }

    @Test
    @DisplayName("delivers to user-stream:{userId} via Redis")
    void deliversToUserStream() {
        SkillSession session = SkillSession.builder().businessSessionDomain("miniapp").build();
        StreamMessage msg = StreamMessage.builder().type("delta").content("hello").build();
        strategy.deliver(session, "sess-1", "user-42", msg);
        ArgumentCaptor<String> cap = ArgumentCaptor.forClass(String.class);
        verify(redisMessageBroker).publishToUser(eq("user-42"), cap.capture());
        assertTrue(cap.getValue().contains("\"userId\":\"user-42\""));
        assertTrue(cap.getValue().contains("\"sessionId\":\"sess-1\""));
    }

    @Test
    @DisplayName("order is 1")
    void orderIsOne() { assertEquals(1, strategy.order()); }
}
