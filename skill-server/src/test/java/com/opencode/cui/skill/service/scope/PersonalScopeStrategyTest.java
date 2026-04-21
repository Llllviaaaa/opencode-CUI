package com.opencode.cui.skill.service.scope;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.opencode.cui.skill.model.StreamMessage;
import com.opencode.cui.skill.service.CloudEventTranslator;
import com.opencode.cui.skill.service.OpenCodeEventTranslator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
@DisplayName("PersonalScopeStrategy")
class PersonalScopeStrategyTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private OpenCodeEventTranslator openCodeEventTranslator;

    @Mock
    private CloudEventTranslator cloudEventTranslator;

    private PersonalScopeStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new PersonalScopeStrategy(openCodeEventTranslator, cloudEventTranslator);
    }

    @Test
    @DisplayName("null event returns null and invokes neither translator")
    void translateEvent_nullEvent_returnsNull() {
        StreamMessage result = strategy.translateEvent(null, "session-1");

        assertNull(result);
        verifyNoInteractions(openCodeEventTranslator);
        verifyNoInteractions(cloudEventTranslator);
    }
}
