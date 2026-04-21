package com.opencode.cui.skill.service.scope;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.opencode.cui.skill.model.StreamMessage;
import com.opencode.cui.skill.service.CloudEventTranslator;
import com.opencode.cui.skill.service.OpenCodeEventTranslator;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PersonalScopeStrategy")
class PersonalScopeStrategyTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private OpenCodeEventTranslator openCodeEventTranslator;

    @Mock
    private CloudEventTranslator cloudEventTranslator;

    private PersonalScopeStrategy strategy;

    // ---- Log4j2 in-memory appender infrastructure ----

    private static class CapturingAppender extends AbstractAppender {
        private final List<LogEvent> events = new java.util.concurrent.CopyOnWriteArrayList<>();

        CapturingAppender() {
            super("CapturingAppender", null, PatternLayout.createDefaultLayout(), true, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            events.add(event.toImmutable());
        }

        List<LogEvent> getEvents() {
            return events;
        }
    }

    private CapturingAppender capturingAppender;
    private org.apache.logging.log4j.core.Logger strategyLog4jLogger;

    @BeforeEach
    void attachLogAppender() {
        LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        strategyLog4jLogger = ctx.getLogger(PersonalScopeStrategy.class.getName());
        capturingAppender = new CapturingAppender();
        capturingAppender.start();
        strategyLog4jLogger.addAppender(capturingAppender);
    }

    @AfterEach
    void detachLogAppender() {
        if (strategyLog4jLogger != null && capturingAppender != null) {
            strategyLog4jLogger.removeAppender(capturingAppender);
            capturingAppender.stop();
        }
    }

    private long countWarnLogs() {
        return capturingAppender.getEvents().stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .count();
    }

    // ---- end log appender infrastructure ----

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
        assertEquals(0, countWarnLogs(),
                "null event must not produce any WARN log");
    }

    @Test
    @DisplayName("no protocol field delegates to OpenCodeEventTranslator, no warn")
    void translateEvent_noProtocolField_delegatesToOpenCode() {
        ObjectNode event = objectMapper.createObjectNode();
        event.put("type", "message.part.updated");
        StreamMessage expected = StreamMessage.builder()
                .type(StreamMessage.Types.TEXT_DELTA).content("hi").build();
        when(openCodeEventTranslator.translate(event)).thenReturn(expected);

        StreamMessage result = strategy.translateEvent(event, "session-1");

        assertSame(expected, result);
        verify(openCodeEventTranslator).translate(event);
        verifyNoInteractions(cloudEventTranslator);
        assertEquals(0, countWarnLogs(),
                "missing protocol field must not produce a WARN log");
    }

    @Test
    @DisplayName("protocol=opencode (lowercase) delegates to OpenCodeEventTranslator, no warn")
    void translateEvent_protocolOpencode_delegatesToOpenCode() {
        ObjectNode event = objectMapper.createObjectNode();
        event.put("protocol", "opencode");
        event.put("type", "message.part.updated");
        StreamMessage expected = StreamMessage.builder()
                .type(StreamMessage.Types.TEXT_DELTA).content("x").build();
        when(openCodeEventTranslator.translate(event)).thenReturn(expected);

        StreamMessage result = strategy.translateEvent(event, "session-1");

        assertSame(expected, result);
        verify(openCodeEventTranslator).translate(event);
        verifyNoInteractions(cloudEventTranslator);
        assertEquals(0, countWarnLogs(),
                "explicit protocol=opencode must not produce a WARN log");
    }
}
