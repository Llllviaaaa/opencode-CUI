package com.opencode.cui.skill.service.delivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.model.SkillSession;
import com.opencode.cui.skill.model.StreamMessage;
import com.opencode.cui.skill.service.MessagePersistenceService;
import com.opencode.cui.skill.service.RedisMessageBroker;
import com.opencode.cui.skill.service.SkillSessionService;
import com.opencode.cui.skill.service.StreamBufferService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StreamMessageEmitterTest {

    @Mock OutboundDeliveryDispatcher dispatcher;
    @Mock RedisMessageBroker redisBroker;
    @Mock StreamBufferService bufferService;
    @Mock MessagePersistenceService persistenceService;
    @Mock SkillSessionService sessionService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private StreamMessageEmitter emitter;

    @BeforeEach
    void setUp() {
        emitter = new StreamMessageEmitter(
                dispatcher, redisBroker, bufferService,
                persistenceService, sessionService, objectMapper);
    }

    // --- enrich semantics ---

    @Test
    void enrich1_welinkSessionId_isOverwrittenByCanonicalSessionId() {
        SkillSession session = mock(SkillSession.class);
        StreamMessage msg = StreamMessage.builder()
                .type(StreamMessage.Types.TEXT_DELTA)
                .welinkSessionId("business-123")
                .role("assistant")
                .build();

        emitter.emitToSession(session, "101", "user-a", msg);

        // canonical overwrite: "business-123" 被替换为 "101"
        assertEquals("101", msg.getWelinkSessionId());
    }

    @Test
    void enrich2_welinkSessionId_isFilledWhenBlank() {
        SkillSession session = mock(SkillSession.class);
        StreamMessage msg = StreamMessage.builder()
                .type(StreamMessage.Types.TEXT_DELTA)
                .role("assistant")
                .build();

        emitter.emitToSession(session, "101", "user-a", msg);

        assertEquals("101", msg.getWelinkSessionId());
    }
}
