package com.opencode.cui.skill.logging;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.*;

class LogTimerTest {

    private static final Logger testLog = LoggerFactory.getLogger(LogTimerTest.class);

    @Test
    void timedShouldReturnResultOnSuccess() {
        String result = LogTimer.timed(testLog, "TestOp", () -> "hello");
        assertEquals("hello", result);
    }

    @Test
    void timedShouldPropagateExceptionOnFailure() {
        assertThrows(RuntimeException.class, () ->
                LogTimer.timed(testLog, "FailOp", () -> { throw new RuntimeException("boom"); })
        );
    }

    @Test
    void timedRunShouldCompleteOnSuccess() {
        assertDoesNotThrow(() -> LogTimer.timedRun(testLog, "VoidOp", () -> {}));
    }

    @Test
    void timedRunShouldPropagateExceptionOnFailure() {
        assertThrows(RuntimeException.class, () ->
                LogTimer.timedRun(testLog, "FailVoidOp", () -> { throw new RuntimeException("boom"); })
        );
    }
}
