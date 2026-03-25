package com.opencode.cui.gateway.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Wrapper message used for GW-to-GW relay over Redis pub/sub.
 *
 * <p>Channel pattern: {@code gw:relay:{instanceId}}
 *
 * <p>When an {@code invoke} arrives at GW-A but the target Agent is connected to GW-B,
 * GW-A publishes a {@code RelayMessage} to {@code gw:relay:{gwB-instanceId}}.
 * GW-B receives it, extracts {@code originalMessage}, and delivers to the local Agent.
 *
 * <p>Backward-compatibility note: the legacy path published raw {@code GatewayMessage} JSON
 * to the same channel prefix. Receivers distinguish new-format messages by checking for the
 * {@code "type":"relay"} field.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RelayMessage(
        /** Always {@code "relay"} — used to distinguish from legacy raw GatewayMessage JSON. */
        String type,

        /**
         * Originating service type, e.g. {@code "skill-server"}.
         * Used for routing-learning propagation; may be {@code null} for direct GW-to-GW relay.
         */
        String sourceType,

        /**
         * Routing keys carried for upstream-routing-table learning,
         * e.g. {@code ["ts-abc", "w:42"]}. May be {@code null} when not applicable.
         */
        List<String> routingKeys,

        /** The original {@link GatewayMessage} serialized as JSON string. */
        String originalMessage
) {

    /** Canonical type discriminator value. */
    public static final String TYPE = "relay";

    /**
     * Factory: creates a minimal relay message (no routing-learning metadata).
     *
     * @param originalMessageJson JSON string of the GatewayMessage to be relayed
     * @return a new RelayMessage with {@code type="relay"}
     */
    public static RelayMessage of(String originalMessageJson) {
        return new RelayMessage(TYPE, null, null, originalMessageJson);
    }

    /**
     * Factory: creates a relay message with routing-learning metadata.
     *
     * @param sourceType          originating service type
     * @param routingKeys         routing keys for upstream-routing-table learning
     * @param originalMessageJson JSON string of the GatewayMessage to be relayed
     * @return a new RelayMessage
     */
    public static RelayMessage of(String sourceType, List<String> routingKeys, String originalMessageJson) {
        return new RelayMessage(TYPE, sourceType, routingKeys, originalMessageJson);
    }
}
