package com.opencode.cui.gateway.logging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.gateway.model.GatewayMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MdcHelper 工具类测试。
 * 验证 MDC 上下文的设置、清理、快照恢复及从 GatewayMessage 提取字段。
 */
class MdcHelperTest {

    @AfterEach
    void cleanup() {
        MDC.clear();
    }

    // --- 基本 put/get ---

    @Test
    void putTraceIdShouldSetMdc() {
        MdcHelper.putTraceId("trace-001");
        assertEquals("trace-001", MDC.get(MdcConstants.TRACE_ID));
    }

    @Test
    void putSessionIdShouldSetMdc() {
        MdcHelper.putSessionId("sess-001");
        assertEquals("sess-001", MDC.get(MdcConstants.SESSION_ID));
    }

    @Test
    void putAkShouldSetMdc() {
        MdcHelper.putAk("ak-xyz");
        assertEquals("ak-xyz", MDC.get(MdcConstants.AK));
    }

    @Test
    void putUserIdShouldSetMdc() {
        MdcHelper.putUserId("user-123");
        assertEquals("user-123", MDC.get(MdcConstants.USER_ID));
    }

    @Test
    void putScenarioShouldSetMdc() {
        MdcHelper.putScenario("ws-agent-connect");
        assertEquals("ws-agent-connect", MDC.get(MdcConstants.SCENARIO));
    }

    // --- null 安全 ---

    @Test
    void putNullValuesShouldNotThrow() {
        assertDoesNotThrow(() -> {
            MdcHelper.putTraceId(null);
            MdcHelper.putSessionId(null);
            MdcHelper.putAk(null);
            MdcHelper.putUserId(null);
            MdcHelper.putScenario(null);
        });
        // null 值不应写入 MDC
        assertNull(MDC.get(MdcConstants.TRACE_ID));
    }

    @Test
    void putBlankValuesShouldNotSetMdc() {
        MdcHelper.putTraceId("   ");
        assertNull(MDC.get(MdcConstants.TRACE_ID));
    }

    // --- clearAll ---

    @Test
    void clearAllShouldRemoveAllCustomKeys() {
        MdcHelper.putTraceId("trace-001");
        MdcHelper.putSessionId("sess-001");
        MdcHelper.putAk("ak-xyz");
        MdcHelper.putUserId("user-123");
        MdcHelper.putScenario("ws-agent");

        MdcHelper.clearAll();

        assertNull(MDC.get(MdcConstants.TRACE_ID));
        assertNull(MDC.get(MdcConstants.SESSION_ID));
        assertNull(MDC.get(MdcConstants.AK));
        assertNull(MDC.get(MdcConstants.USER_ID));
        assertNull(MDC.get(MdcConstants.SCENARIO));
    }

    @Test
    void clearAllShouldNotAffectOtherMdcKeys() {
        MDC.put("foreignKey", "foreignValue");
        MdcHelper.putTraceId("trace-001");

        MdcHelper.clearAll();

        assertNull(MDC.get(MdcConstants.TRACE_ID));
        assertEquals("foreignValue", MDC.get("foreignKey"));
    }

    // --- ensureTraceId ---

    @Test
    void ensureTraceIdShouldGenerateWhenAbsent() {
        String traceId = MdcHelper.ensureTraceId();

        assertNotNull(traceId);
        assertFalse(traceId.isBlank());
        assertEquals(traceId, MDC.get(MdcConstants.TRACE_ID));
    }

    @Test
    void ensureTraceIdShouldKeepExistingValue() {
        MdcHelper.putTraceId("existing-trace");

        String traceId = MdcHelper.ensureTraceId();

        assertEquals("existing-trace", traceId);
        assertEquals("existing-trace", MDC.get(MdcConstants.TRACE_ID));
    }

    // --- fromGatewayMessage ---

    @Test
    void fromGatewayMessageShouldSetAllFields() {
        GatewayMessage msg = GatewayMessage.builder()
                .traceId("trace-abc")
                .welinkSessionId("sess-123")
                .ak("ak-456")
                .userId("user-789")
                .build();

        MdcHelper.fromGatewayMessage(msg);

        assertEquals("trace-abc", MDC.get(MdcConstants.TRACE_ID));
        assertEquals("sess-123", MDC.get(MdcConstants.SESSION_ID));
        assertEquals("ak-456", MDC.get(MdcConstants.AK));
        assertEquals("user-789", MDC.get(MdcConstants.USER_ID));
    }

    @Test
    void fromGatewayMessageShouldHandleNullFields() {
        GatewayMessage msg = GatewayMessage.builder()
                .traceId("trace-only")
                .build();

        MdcHelper.fromGatewayMessage(msg);

        assertEquals("trace-only", MDC.get(MdcConstants.TRACE_ID));
        assertNull(MDC.get(MdcConstants.SESSION_ID));
        assertNull(MDC.get(MdcConstants.AK));
        assertNull(MDC.get(MdcConstants.USER_ID));
    }

    @Test
    void fromGatewayMessageShouldHandleNullMessage() {
        assertDoesNotThrow(() -> MdcHelper.fromGatewayMessage(null));
    }

    // --- snapshot / restore ---

    @Test
    void snapshotShouldCaptureCurrentMdcState() {
        MdcHelper.putTraceId("trace-snap");
        MdcHelper.putAk("ak-snap");

        Map<String, String> snapshot = MdcHelper.snapshot();

        assertNotNull(snapshot);
        assertEquals("trace-snap", snapshot.get(MdcConstants.TRACE_ID));
        assertEquals("ak-snap", snapshot.get(MdcConstants.AK));
    }

    @Test
    void restoreShouldApplySnapshotToMdc() {
        MdcHelper.putTraceId("trace-original");
        MdcHelper.putAk("ak-original");
        Map<String, String> snapshot = MdcHelper.snapshot();

        MdcHelper.clearAll();
        assertNull(MDC.get(MdcConstants.TRACE_ID));

        MdcHelper.restore(snapshot);
        assertEquals("trace-original", MDC.get(MdcConstants.TRACE_ID));
        assertEquals("ak-original", MDC.get(MdcConstants.AK));
    }

    @Test
    void restoreNullShouldNotThrow() {
        assertDoesNotThrow(() -> MdcHelper.restore(null));
    }

    @Test
    void snapshotShouldReturnEmptyMapWhenNoMdcSet() {
        MdcHelper.clearAll();
        Map<String, String> snapshot = MdcHelper.snapshot();

        assertNotNull(snapshot);
        assertTrue(snapshot.values().stream().allMatch(v -> v == null));
    }
}
