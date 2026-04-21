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

    @Test
    void enrich3_emittedAt_excludedTypesKeepNull() {
        SkillSession session = mock(SkillSession.class);
        StreamMessage msg = StreamMessage.builder()
                .type(StreamMessage.Types.ERROR)      // 在白名单内
                .error("oops")
                .build();

        emitter.emitToSession(session, "101", null, msg);

        assertNull(msg.getEmittedAt());
    }

    @Test
    void enrich4_emittedAt_nonExcludedAndBlank_filled() {
        SkillSession session = mock(SkillSession.class);
        StreamMessage msg = StreamMessage.builder()
                .type(StreamMessage.Types.TEXT_DELTA)  // 非白名单
                .role("assistant")
                .build();

        emitter.emitToSession(session, "101", null, msg);

        assertNotNull(msg.getEmittedAt());
        assertFalse(msg.getEmittedAt().isBlank());
    }

    @Test
    void enrich5_emittedAt_alreadySet_preserved() {
        SkillSession session = mock(SkillSession.class);
        StreamMessage msg = StreamMessage.builder()
                .type(StreamMessage.Types.TEXT_DELTA)
                .emittedAt("2026-01-01T00:00:00Z")
                .role("assistant")
                .build();

        emitter.emitToSession(session, "101", null, msg);

        assertEquals("2026-01-01T00:00:00Z", msg.getEmittedAt());
    }
}
