package com.opencode.cui.skill.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.opencode.cui.skill.model.StreamMessage;
import com.opencode.cui.skill.service.scope.BusinessScopeStrategy;
import com.opencode.cui.skill.service.scope.PersonalScopeStrategy;
import com.opencode.cui.skill.service.cloud.CloudRequestBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 事件翻译 scope 分派测试（S62-S63）。
 *
 * <p>验证 business scope 使用 CloudEventTranslator，
 * personal scope 使用 OpenCodeEventTranslator。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EventTranslationScopeTest")
class EventTranslationScopeTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private CloudEventTranslator cloudEventTranslator;

    @Mock
    private CloudRequestBuilder cloudRequestBuilder;

    @Mock
    private OpenCodeEventTranslator openCodeEventTranslator;

    private BusinessScopeStrategy businessStrategy;
    private PersonalScopeStrategy personalStrategy;

    @BeforeEach
    void setUp() {
        businessStrategy = new BusinessScopeStrategy(cloudRequestBuilder, cloudEventTranslator, objectMapper);
        personalStrategy = new PersonalScopeStrategy(openCodeEventTranslator, cloudEventTranslator);
    }

    /**
     * S62: business scope + cloud event (text.delta) -> CloudEventTranslator 处理
     */
    @Test
    @DisplayName("S62: business scope delegates text.delta to CloudEventTranslator")
    void businessScope_textDelta_delegatesToCloudEventTranslator() {
        ObjectNode event = objectMapper.createObjectNode();
        event.put("type", StreamMessage.Types.TEXT_DELTA);
        event.put("content", "hello");

        StreamMessage expected = StreamMessage.builder()
                .type(StreamMessage.Types.TEXT_DELTA)
                .content("hello")
                .build();
        when(cloudEventTranslator.translate(event, "session-1")).thenReturn(expected);

        StreamMessage result = businessStrategy.translateEvent(event, "session-1");

        assertNotNull(result, "business strategy should return translated message");
        assertEquals(StreamMessage.Types.TEXT_DELTA, result.getType());
        assertEquals("hello", result.getContent());
        verify(cloudEventTranslator).translate(event, "session-1");
    }

    /**
     * S63: personal scope + OpenCode event -> OpenCodeEventTranslator 处理
     */
    @Test
    @DisplayName("S63: personal scope delegates event to OpenCodeEventTranslator")
    void personalScope_event_delegatesToOpenCodeEventTranslator() {
        ObjectNode event = objectMapper.createObjectNode();
        event.put("type", "message.part.updated");

        StreamMessage expected = StreamMessage.builder()
                .type(StreamMessage.Types.TEXT_DELTA)
                .content("opencode content")
                .build();
        when(openCodeEventTranslator.translate(event)).thenReturn(expected);

        StreamMessage result = personalStrategy.translateEvent(event, "session-2");

        assertNotNull(result, "personal strategy should return translated message");
        assertEquals(StreamMessage.Types.TEXT_DELTA, result.getType());
        verify(openCodeEventTranslator).translate(event);
    }
}
