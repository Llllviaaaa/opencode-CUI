package com.opencode.cui.gateway.logging;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MdcTaskDecoratorTest {

    @AfterEach
    void cleanup() {
        MDC.clear();
    }

    @Test
    void decorateShouldPropagateCapturedMdcAndRestorePreviousContext() {
        MdcHelper.putTraceId("trace-captured");
        MdcHelper.putSessionId("session-captured");
        Runnable decorated = new MdcTaskDecorator().decorate(() -> {
            assertEquals("trace-captured", MDC.get(MdcConstants.TRACE_ID));
            assertEquals("session-captured", MDC.get(MdcConstants.SESSION_ID));
            MdcHelper.putTraceId("trace-inside");
        });

        MdcHelper.putTraceId("trace-previous");
        MdcHelper.putSessionId("session-previous");
        decorated.run();

        assertEquals("trace-previous", MDC.get(MdcConstants.TRACE_ID));
        assertEquals("session-previous", MDC.get(MdcConstants.SESSION_ID));
    }
}
