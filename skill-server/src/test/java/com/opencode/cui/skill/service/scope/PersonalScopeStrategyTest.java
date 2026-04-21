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
            // 每个实例用唯一 name，避免 Log4j2 按 name 去重导致 addAppender 丢弃新 instance。
            super("CapturingAppender-" + java.util.UUID.randomUUID(),
                    null, PatternLayout.createDefaultLayout(), true, Property.EMPTY_ARRAY);
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

    // 用单个 @BeforeEach 合并 Mockito mock 装配 + appender 挂载，避免 JUnit 5 对多个
    // @BeforeEach 无排序保证带来的时序不稳（在全量测试下前序类可能已初始化 Logger 缓存）。
    @BeforeEach
    void beforeEach() {
        LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        strategyLog4jLogger = ctx.getLogger(PersonalScopeStrategy.class.getName());
        capturingAppender = new CapturingAppender();
        capturingAppender.start();
        strategyLog4jLogger.addAppender(capturingAppender);
        // logger level 由 src/test/resources/log4j2.xml 声明为 TRACE（WARN/DEBUG 都穿透）。

        strategy = new PersonalScopeStrategy(openCodeEventTranslator, cloudEventTranslator);
    }

    @AfterEach
    void detachLogAppender() {
        if (strategyLog4jLogger != null && capturingAppender != null) {
            strategyLog4jLogger.removeAppender(capturingAppender);
            capturingAppender.stop();
        }
    }

    private long countWarnLogs() {
        // 容忍 Log4j2 异步 append（disruptor 在 classpath 时可能触发）：
        // 轮询至计数稳定或 200ms 超时。snapshot-based CopyOnWriteArrayList 读没副作用。
        long deadline = System.currentTimeMillis() + 200;
        long last = -1;
        while (true) {
            long cur = capturingAppender.getEvents().stream()
                    .filter(e -> e.getLevel() == Level.WARN)
                    .count();
            if (cur == last) return cur;
            last = cur;
            if (System.currentTimeMillis() >= deadline) return cur;
            try { Thread.sleep(10); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); return cur; }
        }
    }

    // ---- end log appender infrastructure ----

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

    @Test
    @DisplayName("protocol=cloud delegates to CloudEventTranslator with sessionId passthrough, no warn")
    void translateEvent_protocolCloud_delegatesToCloud() {
        ObjectNode event = objectMapper.createObjectNode();
        event.put("protocol", "cloud");
        event.put("type", StreamMessage.Types.TEXT_DELTA);
        StreamMessage expected = StreamMessage.builder()
                .type(StreamMessage.Types.TEXT_DELTA).content("cloud-content").build();
        when(cloudEventTranslator.translate(event, "session-X")).thenReturn(expected);

        StreamMessage result = strategy.translateEvent(event, "session-X");

        assertSame(expected, result);
        verify(cloudEventTranslator).translate(event, "session-X");
        verifyNoInteractions(openCodeEventTranslator);
        assertEquals(0, countWarnLogs(),
                "protocol=cloud must not produce WARN (DEBUG only)");
    }

    @Test
    @DisplayName("protocol=CLOUD (uppercase) also delegates to CloudEventTranslator, no warn")
    void translateEvent_protocolCloudUpperCase_caseInsensitive() {
        ObjectNode event = objectMapper.createObjectNode();
        event.put("protocol", "CLOUD");
        event.put("type", StreamMessage.Types.TEXT_DELTA);
        StreamMessage expected = StreamMessage.builder()
                .type(StreamMessage.Types.TEXT_DELTA).content("c").build();
        when(cloudEventTranslator.translate(event, "session-Y")).thenReturn(expected);

        StreamMessage result = strategy.translateEvent(event, "session-Y");

        assertSame(expected, result);
        verify(cloudEventTranslator).translate(event, "session-Y");
        verifyNoInteractions(openCodeEventTranslator);
        assertEquals(0, countWarnLogs(),
                "protocol=CLOUD must not produce WARN (DEBUG only)");
    }

    @Test
    @DisplayName("protocol='' emits WARN and falls back to OpenCodeEventTranslator")
    void translateEvent_protocolEmptyString_warnsAndFallsBackToOpenCode() {
        ObjectNode event = objectMapper.createObjectNode();
        event.put("protocol", "");
        event.put("type", "message.part.updated");
        StreamMessage expected = StreamMessage.builder()
                .type(StreamMessage.Types.TEXT_DELTA).content("fb").build();
        when(openCodeEventTranslator.translate(event)).thenReturn(expected);

        StreamMessage result = strategy.translateEvent(event, "session-E");

        assertSame(expected, result);
        verify(openCodeEventTranslator).translate(event);
        verifyNoInteractions(cloudEventTranslator);
        assertEquals(1, countWarnLogs(),
                "expected exactly one WARN log for empty protocol");
    }

    @Test
    @DisplayName("protocol=mcp (unknown) emits WARN and falls back to OpenCodeEventTranslator")
    void translateEvent_protocolUnknownValue_warnsAndFallsBackToOpenCode() {
        ObjectNode event = objectMapper.createObjectNode();
        event.put("protocol", "mcp");
        event.put("type", "message.part.updated");
        StreamMessage expected = StreamMessage.builder()
                .type(StreamMessage.Types.TEXT_DELTA).content("fb").build();
        when(openCodeEventTranslator.translate(event)).thenReturn(expected);

        StreamMessage result = strategy.translateEvent(event, "session-U");

        assertSame(expected, result);
        verify(openCodeEventTranslator).translate(event);
        verifyNoInteractions(cloudEventTranslator);
        assertEquals(1, countWarnLogs(),
                "expected exactly one WARN log for unknown protocol value");
    }
}
