package com.opencode.cui.gateway.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.opencode.cui.gateway.model.GatewayMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Upstream routing table for mapping session IDs to source service types.
 *
 * <p>
 * When the Gateway receives an {@code invoke} message from a source service
 * (e.g., skill-server, bot-platform), it learns the association between
 * session identifiers and the originating source type. This mapping is later
 * used to route agent replies back to the correct upstream service.
 * </p>
 *
 * <h3>Key encoding scheme</h3>
 * <ul>
 * <li>{@code toolSessionId} — stored as-is (e.g., {@code "ts-abc"})</li>
 * <li>{@code welinkSessionId} — stored with {@code "w:"} prefix
 *     (e.g., {@code "w:42"}) to avoid collisions with toolSessionId namespace</li>
 * </ul>
 *
 * <h3>Learning scenarios</h3>
 * <ul>
 * <li>{@code create_session} invoke: carries {@code welinkSessionId} in top-level field</li>
 * <li>{@code chat} invoke: carries {@code toolSessionId} in {@code payload.toolSessionId}</li>
 * </ul>
 */
@Slf4j
@Component
public class UpstreamRoutingTable {

    /** Key prefix for welinkSessionId entries to distinguish from toolSessionId namespace. */
    static final String WELINK_KEY_PREFIX = "w:";

    private final Cache<String, String> routingTable;

    public UpstreamRoutingTable(
            @Value("${gateway.upstream-routing.cache-max-size:100000}") long cacheMaxSize,
            @Value("${gateway.upstream-routing.cache-expire-minutes:30}") long cacheExpireMinutes) {
        this.routingTable = Caffeine.newBuilder()
                .maximumSize(cacheMaxSize)
                .expireAfterWrite(cacheExpireMinutes, TimeUnit.MINUTES)
                .build();
        log.info("[ENTRY] UpstreamRoutingTable initialized: maxSize={}, expireMinutes={}",
                cacheMaxSize, cacheExpireMinutes);
    }

    /**
     * Learns routing from an incoming invoke message received from a source service.
     *
     * <p>Extracts session IDs from the message and stores their association with
     * the given {@code sourceType}. Two possible learning paths:</p>
     * <ol>
     * <li>Top-level {@code welinkSessionId} → {@code "w:" + welinkSessionId}</li>
     * <li>{@code payload.toolSessionId} → toolSessionId value</li>
     * </ol>
     *
     * @param message    the invoke message from the source service
     * @param sourceType the source service type identifier (e.g., {@code "skill-server"})
     */
    public void learnRoute(GatewayMessage message, String sourceType) {
        if (message == null || sourceType == null || sourceType.isBlank()) {
            return;
        }

        // Learn from top-level welinkSessionId (create_session scenario)
        String welinkSessionId = message.getWelinkSessionId();
        if (welinkSessionId != null && !welinkSessionId.isBlank()) {
            String key = WELINK_KEY_PREFIX + welinkSessionId;
            routingTable.put(key, sourceType);
            log.info("[ENTRY] UpstreamRoutingTable.learnRoute: learned welinkSessionId route key={} -> sourceType={}",
                    key, sourceType);
        }

        // Learn from payload.toolSessionId (chat scenario)
        String toolSessionId = extractToolSessionIdFromPayload(message);
        if (toolSessionId != null && !toolSessionId.isBlank()) {
            routingTable.put(toolSessionId, sourceType);
            log.info("[ENTRY] UpstreamRoutingTable.learnRoute: learned toolSessionId route key={} -> sourceType={}",
                    toolSessionId, sourceType);
        }
    }

    /**
     * Propagates routing knowledge from relay routing keys to a source type.
     *
     * <p>Used when relay messages carry a list of routing keys that should all
     * be associated with the same upstream source.</p>
     *
     * @param routingKeys list of routing keys (toolSessionId values or prefixed welinkSessionId values)
     * @param sourceType  the source service type to associate with all given keys
     */
    public void learnFromRelay(List<String> routingKeys, String sourceType) {
        if (routingKeys == null || routingKeys.isEmpty() || sourceType == null || sourceType.isBlank()) {
            return;
        }

        for (String key : routingKeys) {
            if (key != null && !key.isBlank()) {
                routingTable.put(key, sourceType);
                log.info("[ENTRY] UpstreamRoutingTable.learnFromRelay: propagated route key={} -> sourceType={}",
                        key, sourceType);
            }
        }
    }

    /**
     * Resolves the source type for an agent reply message.
     *
     * <p>Lookup priority:</p>
     * <ol>
     * <li>Top-level {@code toolSessionId} field</li>
     * <li>Top-level {@code welinkSessionId} field (with {@code "w:"} prefix lookup)</li>
     * </ol>
     *
     * @param message the message from the agent (tool_event, tool_done, tool_error, etc.)
     * @return the source type string if found, {@code null} otherwise
     */
    public String resolveSourceType(GatewayMessage message) {
        if (message == null) {
            return null;
        }

        // Priority 1: resolve by top-level toolSessionId
        String toolSessionId = message.getToolSessionId();
        if (toolSessionId != null && !toolSessionId.isBlank()) {
            String sourceType = routingTable.getIfPresent(toolSessionId);
            if (sourceType != null) {
                log.info("[EXIT] UpstreamRoutingTable.resolveSourceType: resolved via toolSessionId={} -> sourceType={}",
                        toolSessionId, sourceType);
                return sourceType;
            }
        }

        // Priority 2: fallback to welinkSessionId
        String welinkSessionId = message.getWelinkSessionId();
        if (welinkSessionId != null && !welinkSessionId.isBlank()) {
            String key = WELINK_KEY_PREFIX + welinkSessionId;
            String sourceType = routingTable.getIfPresent(key);
            if (sourceType != null) {
                log.info("[EXIT] UpstreamRoutingTable.resolveSourceType: resolved via welinkSessionId={} -> sourceType={}",
                        welinkSessionId, sourceType);
                return sourceType;
            }
        }

        log.info("[EXIT] UpstreamRoutingTable.resolveSourceType: no route found for toolSessionId={}, welinkSessionId={}",
                toolSessionId, welinkSessionId);
        return null;
    }

    /**
     * Extracts the {@code toolSessionId} field from the message payload JSON.
     *
     * @param message the gateway message
     * @return the toolSessionId string if present in payload, {@code null} otherwise
     */
    private String extractToolSessionIdFromPayload(GatewayMessage message) {
        if (message.getPayload() == null) {
            return null;
        }
        String toolSessionId = message.getPayload().path("toolSessionId").asText(null);
        if (toolSessionId != null && toolSessionId.isBlank()) {
            return null;
        }
        return toolSessionId;
    }
}
