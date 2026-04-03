package com.opencode.cui.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.opencode.cui.gateway.model.GatewayMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Unit tests for UpstreamRoutingTable — verifies route learning and resolution logic. */
class UpstreamRoutingTableTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SOURCE_SKILL = "skill-server";
    private static final String SOURCE_BOT = "bot-platform";

    private UpstreamRoutingTable table;

    @BeforeEach
    void setUp() {
        // Use small cache with long expiry for tests
        table = new UpstreamRoutingTable(1000, 60);
    }

    // ==================== learnRoute tests ====================

    @Test
    @DisplayName("learnRoute_fromCreateSession_shouldStoreWelinkSessionId")
    void learnRoute_fromCreateSession_shouldStoreWelinkSessionId() {
        // create_session: top-level welinkSessionId, no payload toolSessionId
        GatewayMessage msg = GatewayMessage.builder()
                .type(GatewayMessage.Type.INVOKE)
                .welinkSessionId("42")
                .build();

        table.learnRoute(msg, SOURCE_SKILL);

        // Resolve via a message that carries welinkSessionId
        GatewayMessage replyMsg = GatewayMessage.builder()
                .welinkSessionId("42")
                .build();
        assertEquals(SOURCE_SKILL, table.resolveSourceType(replyMsg));
    }

    @Test
    @DisplayName("learnRoute_fromChat_shouldStoreToolSessionId")
    void learnRoute_fromChat_shouldStoreToolSessionId() throws Exception {
        // chat: payload contains toolSessionId, no top-level welinkSessionId
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("toolSessionId", "ts-abc");

        GatewayMessage msg = GatewayMessage.builder()
                .type(GatewayMessage.Type.INVOKE)
                .payload(payload)
                .build();

        table.learnRoute(msg, SOURCE_SKILL);

        // Resolve via a message that carries toolSessionId
        GatewayMessage replyMsg = GatewayMessage.builder()
                .toolSessionId("ts-abc")
                .build();
        assertEquals(SOURCE_SKILL, table.resolveSourceType(replyMsg));
    }

    @Test
    @DisplayName("learnRoute_withBothIds_shouldStoreBoth")
    void learnRoute_withBothIds_shouldStoreBoth() throws Exception {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("toolSessionId", "ts-xyz");

        GatewayMessage msg = GatewayMessage.builder()
                .type(GatewayMessage.Type.INVOKE)
                .welinkSessionId("99")
                .payload(payload)
                .build();

        table.learnRoute(msg, SOURCE_SKILL);

        // Both lookups should resolve
        assertEquals(SOURCE_SKILL, table.resolveSourceType(
                GatewayMessage.builder().toolSessionId("ts-xyz").build()));
        assertEquals(SOURCE_SKILL, table.resolveSourceType(
                GatewayMessage.builder().welinkSessionId("99").build()));
    }

    // ==================== resolveSourceType tests ====================

    @Test
    @DisplayName("resolveSourceType_withToolSessionId_shouldReturnLearned")
    void resolveSourceType_withToolSessionId_shouldReturnLearned() throws Exception {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("toolSessionId", "ts-resolved");
        table.learnRoute(GatewayMessage.builder().payload(payload).build(), SOURCE_BOT);

        String result = table.resolveSourceType(
                GatewayMessage.builder().toolSessionId("ts-resolved").build());
        assertEquals(SOURCE_BOT, result);
    }

    @Test
    @DisplayName("resolveSourceType_withWelinkSessionId_shouldReturnLearned")
    void resolveSourceType_withWelinkSessionId_shouldReturnLearned() {
        table.learnRoute(GatewayMessage.builder().welinkSessionId("session-7").build(), SOURCE_BOT);

        String result = table.resolveSourceType(
                GatewayMessage.builder().welinkSessionId("session-7").build());
        assertEquals(SOURCE_BOT, result);
    }

    @Test
    @DisplayName("resolveSourceType_withUnknownId_shouldReturnNull")
    void resolveSourceType_withUnknownId_shouldReturnNull() {
        String result = table.resolveSourceType(
                GatewayMessage.builder().toolSessionId("unknown-ts").welinkSessionId("unknown-ws").build());
        assertNull(result);
    }

    @Test
    @DisplayName("resolveSourceType_withNullMessage_shouldReturnNull")
    void resolveSourceType_withNullMessage_shouldReturnNull() {
        assertNull(table.resolveSourceType(null));
    }

    // ==================== learnFromRelay tests ====================

    @Test
    @DisplayName("learnFromRelay_shouldPropagateRouting")
    void learnFromRelay_shouldPropagateRouting() {
        List<String> keys = List.of("ts-relay-1", "ts-relay-2", UpstreamRoutingTable.WELINK_KEY_PREFIX + "ws-88");
        table.learnFromRelay(keys, SOURCE_SKILL);

        assertEquals(SOURCE_SKILL, table.resolveSourceType(
                GatewayMessage.builder().toolSessionId("ts-relay-1").build()));
        assertEquals(SOURCE_SKILL, table.resolveSourceType(
                GatewayMessage.builder().toolSessionId("ts-relay-2").build()));
        assertEquals(SOURCE_SKILL, table.resolveSourceType(
                GatewayMessage.builder().welinkSessionId("ws-88").build()));
    }

    @Test
    @DisplayName("learnFromRelay_withEmptyList_shouldNotThrow")
    void learnFromRelay_withEmptyList_shouldNotThrow() {
        // No exception expected
        table.learnFromRelay(List.of(), SOURCE_SKILL);
        table.learnFromRelay(null, SOURCE_SKILL);
    }

    // ==================== priority tests ====================

    @Test
    @DisplayName("resolveSourceType_prioritizesToolSessionIdOverWelinkSessionId")
    void resolveSourceType_prioritizesToolSessionIdOverWelinkSessionId() throws Exception {
        // Learn welinkSessionId -> bot-platform
        table.learnRoute(GatewayMessage.builder().welinkSessionId("shared-ws").build(), SOURCE_BOT);

        // Learn toolSessionId -> skill-server
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("toolSessionId", "ts-priority");
        table.learnRoute(GatewayMessage.builder().payload(payload).build(), SOURCE_SKILL);

        // A message with both: toolSessionId should win
        GatewayMessage replyMsg = GatewayMessage.builder()
                .toolSessionId("ts-priority")
                .welinkSessionId("shared-ws")
                .build();

        assertEquals(SOURCE_SKILL, table.resolveSourceType(replyMsg));
    }
}
