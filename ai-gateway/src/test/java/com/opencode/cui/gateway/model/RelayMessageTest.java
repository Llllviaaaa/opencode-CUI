package com.opencode.cui.gateway.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link RelayMessage} serialization/deserialization.
 *
 * Verifies that the record survives a Jackson round-trip with all field combinations,
 * including null routingKeys and the type discriminator used for legacy-format detection.
 */
class RelayMessageTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ==================== type discriminator ====================

    @Test
    @DisplayName("TYPE constant is 'relay'")
    void typeConstantIsRelay() {
        assertEquals("relay", RelayMessage.TYPE);
    }

    // ==================== factory methods ====================

    @Test
    @DisplayName("of(json) creates relay message with type='relay' and null metadata")
    void ofJsonOnlySetsTypeAndOriginalMessage() {
        RelayMessage msg = RelayMessage.of("{\"type\":\"invoke\"}");

        assertEquals("relay", msg.type());
        assertNull(msg.sourceType());
        assertNull(msg.routingKeys());
        assertEquals("{\"type\":\"invoke\"}", msg.originalMessage());
    }

    @Test
    @DisplayName("of(sourceType, routingKeys, json) sets all fields")
    void ofFullFactoryPopulatesAllFields() {
        List<String> keys = List.of("ts-abc", "w:42");
        RelayMessage msg = RelayMessage.of("skill-server", keys, "{\"type\":\"invoke\"}");

        assertEquals("relay", msg.type());
        assertEquals("skill-server", msg.sourceType());
        assertEquals(keys, msg.routingKeys());
        assertEquals("{\"type\":\"invoke\"}", msg.originalMessage());
    }

    // ==================== serialization ====================

    @Nested
    @DisplayName("Jackson serialization round-trip")
    class SerializationTests {

        @Test
        @DisplayName("full relay message round-trips correctly")
        void fullRelayMessageRoundTrips() throws Exception {
            List<String> keys = List.of("ts-abc", "w:42");
            RelayMessage original = RelayMessage.of("skill-server", keys, "{\"type\":\"invoke\",\"ak\":\"ak-001\"}");

            String json = objectMapper.writeValueAsString(original);
            RelayMessage deserialized = objectMapper.readValue(json, RelayMessage.class);

            assertEquals(original.type(), deserialized.type());
            assertEquals(original.sourceType(), deserialized.sourceType());
            assertEquals(original.routingKeys(), deserialized.routingKeys());
            assertEquals(original.originalMessage(), deserialized.originalMessage());
        }

        @Test
        @DisplayName("null routingKeys is excluded from JSON output")
        void nullRoutingKeysExcludedFromJson() throws Exception {
            RelayMessage msg = RelayMessage.of("{\"type\":\"invoke\"}");

            String json = objectMapper.writeValueAsString(msg);

            assertFalse(json.contains("routingKeys"), "null routingKeys should not appear in JSON");
            assertFalse(json.contains("sourceType"), "null sourceType should not appear in JSON");
            assertTrue(json.contains("\"type\":\"relay\""));
        }

        @Test
        @DisplayName("null routingKeys round-trips as null")
        void nullRoutingKeysRoundTripsAsNull() throws Exception {
            RelayMessage original = RelayMessage.of("{\"type\":\"invoke\"}");

            String json = objectMapper.writeValueAsString(original);
            RelayMessage deserialized = objectMapper.readValue(json, RelayMessage.class);

            assertNull(deserialized.routingKeys());
            assertNull(deserialized.sourceType());
            assertEquals("relay", deserialized.type());
            assertEquals("{\"type\":\"invoke\"}", deserialized.originalMessage());
        }

        @Test
        @DisplayName("type field is present and equals 'relay'")
        void typeFieldPresentInJson() throws Exception {
            RelayMessage msg = RelayMessage.of("{\"type\":\"invoke\"}");

            String json = objectMapper.writeValueAsString(msg);

            assertTrue(json.contains("\"type\":\"relay\""),
                    "JSON must contain type discriminator for legacy-format detection");
        }

        @Test
        @DisplayName("empty routingKeys list round-trips correctly")
        void emptyRoutingKeysRoundTrips() throws Exception {
            RelayMessage original = RelayMessage.of("skill-server", List.of(), "{\"type\":\"invoke\"}");

            String json = objectMapper.writeValueAsString(original);
            RelayMessage deserialized = objectMapper.readValue(json, RelayMessage.class);

            assertNotNull(deserialized.routingKeys());
            assertTrue(deserialized.routingKeys().isEmpty());
        }

        @Test
        @DisplayName("multiple routingKeys round-trip correctly")
        void multipleRoutingKeysRoundTrip() throws Exception {
            List<String> keys = List.of("ts-abc", "w:42", "ak:agent-001");
            RelayMessage original = RelayMessage.of("skill-server", keys, "{\"type\":\"invoke\"}");

            String json = objectMapper.writeValueAsString(original);
            RelayMessage deserialized = objectMapper.readValue(json, RelayMessage.class);

            assertEquals(3, deserialized.routingKeys().size());
            assertEquals("ts-abc", deserialized.routingKeys().get(0));
            assertEquals("w:42", deserialized.routingKeys().get(1));
            assertEquals("ak:agent-001", deserialized.routingKeys().get(2));
        }
    }

    // ==================== legacy detection ====================

    @Test
    @DisplayName("legacy raw GatewayMessage JSON lacks 'type':'relay' — detection scenario")
    void legacyMessageDetection() throws Exception {
        // A legacy relay message is just a raw GatewayMessage JSON, e.g. {"type":"invoke",...}
        // The receiver checks for "type":"relay" to detect new-format messages.
        String legacyJson = "{\"type\":\"invoke\",\"ak\":\"ak-001\"}";

        // Parsing as RelayMessage should fail or produce wrong type — this verifies our
        // detection logic: legacyJson.contains('"type":"relay"') == false
        assertFalse(legacyJson.contains("\"type\":\"relay\""),
                "Legacy GatewayMessage JSON must not contain type=relay discriminator");

        // New format always has type=relay
        RelayMessage newFormat = RelayMessage.of(legacyJson);
        String newJson = objectMapper.writeValueAsString(newFormat);
        assertTrue(newJson.contains("\"type\":\"relay\""),
                "New RelayMessage JSON must contain type=relay discriminator");
    }
}
