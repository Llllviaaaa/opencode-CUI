package com.opencode.cui.skill.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Gateway instance discovery service with multi-level fallback.
 *
 * Discovery chain:
 *   1. HTTP endpoint (GET {discovery-url})
 *   2. Redis scan (gw:instance:*, legacy GW compatibility)
 *   3. Keep existing connections unchanged (all-fail safety net)
 *
 * Scheduling is driven externally (e.g. @Scheduled or manual discover() calls).
 */
@Slf4j
@Service
public class GatewayDiscoveryService {

    // ========== Configuration ==========

    @Value("${skill.gateway.discovery-url:}")
    private String discoveryUrl;

    @Value("${skill.gateway.discovery-timeout-ms:3000}")
    private int discoveryTimeoutMs;

    @Value("${skill.gateway.discovery-fail-threshold:3}")
    private int discoveryFailThreshold;

    // ========== State ==========

    private final RedisMessageBroker redisMessageBroker;
    private final ObjectMapper objectMapper;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    /** Known GW instance IDs (thread-safe: discover() and getKnownInstanceIds() may run in different threads) */
    private final Set<String> knownInstanceIds = ConcurrentHashMap.newKeySet();

    /** Consecutive HTTP discovery failure counter; resets on success */
    private final AtomicInteger httpFailCount = new AtomicInteger(0);

    /** Lazily initialized; reuse across discover() calls */
    private volatile HttpClient httpClient;

    public GatewayDiscoveryService(RedisMessageBroker redisMessageBroker, ObjectMapper objectMapper) {
        this.redisMessageBroker = redisMessageBroker;
        this.objectMapper = objectMapper;
    }

    // ========== Listener ==========

    /**
     * Listener notified when GW instances are added or removed.
     */
    public interface Listener {
        void onGatewayAdded(String instanceId, String wsUrl);
        void onGatewayRemoved(String instanceId);
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    // ========== Public API ==========

    /**
     * Execute one discovery round.
     *
     * Tries HTTP discovery first; falls back to Redis scan on failure;
     * keeps existing connections if all methods fail.
     */
    public void discover() {
        // Level 1: HTTP discovery
        Map<String, String> instances = tryHttpDiscovery();

        // Level 2: Redis scan (legacy GW fallback)
        if (instances == null) {
            log.info("[ENTRY] HTTP discovery unavailable, falling back to Redis scan");
            instances = tryScanRedis();
        }

        // Level 3: All methods returned null (errors) — keep existing connections unchanged
        if (instances == null) {
            log.info("All discovery methods failed; keeping existing connections unchanged");
            return;
        }

        // Empty map is a valid result: all instances gone, notify removals
        notifyChanges(instances);
    }

    public Set<String> getKnownInstanceIds() {
        return Set.copyOf(knownInstanceIds);
    }

    // ========== HTTP Discovery ==========

    /**
     * Attempt to discover GW instances via HTTP endpoint.
     *
     * Expected response format:
     * <pre>
     * {
     *   "instances": [
     *     { "instanceId": "gw-1", "wsUrl": "ws://10.0.1.5:8081/ws/skill" }
     *   ]
     * }
     * </pre>
     *
     * @return instanceId → wsUrl map on success; null on skip or failure
     */
    Map<String, String> tryHttpDiscovery() {
        if (discoveryUrl == null || discoveryUrl.isBlank()) {
            return null;
        }

        int failures = httpFailCount.get();
        if (failures >= discoveryFailThreshold) {
            log.info("HTTP discovery skipped: consecutive failures ({}) reached threshold ({})",
                    failures, discoveryFailThreshold);
            return null;
        }

        try {
            HttpClient client = getOrCreateHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(discoveryUrl))
                    .timeout(Duration.ofMillis(discoveryTimeoutMs))
                    .GET()
                    .build();

            log.info("[EXT_CALL] HTTP discovery GET {}", discoveryUrl);
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                int count = httpFailCount.incrementAndGet();
                log.info("HTTP discovery returned status {}, failCount={}", response.statusCode(), count);
                return null;
            }

            Map<String, String> result = parseHttpDiscoveryResponse(response.body());
            httpFailCount.set(0);
            log.info("[EXIT] HTTP discovery succeeded: {} instances found", result.size());
            return result;

        } catch (Exception e) {
            int count = httpFailCount.incrementAndGet();
            log.info("HTTP discovery failed (failCount={}): {}", count, e.getMessage());
            return null;
        }
    }

    /**
     * Parse the HTTP discovery JSON response into an instanceId → wsUrl map.
     */
    private Map<String, String> parseHttpDiscoveryResponse(String body) throws Exception {
        Map<String, String> result = new HashMap<>();
        JsonNode root = objectMapper.readTree(body);
        JsonNode instances = root.path("instances");
        if (instances.isArray()) {
            for (JsonNode item : instances) {
                String instanceId = item.path("instanceId").asText(null);
                String wsUrl = item.path("wsUrl").asText(null);
                if (instanceId != null && wsUrl != null) {
                    result.put(instanceId, wsUrl);
                }
            }
        }
        return result;
    }

    // ========== Redis Scan (Legacy Fallback) ==========

    /**
     * Attempt to discover GW instances via Redis key scan (gw:instance:*).
     * Preserves compatibility with legacy GW deployments that register to Redis directly.
     *
     * @return instanceId → wsUrl map on success; null on failure
     */
    Map<String, String> tryScanRedis() {
        try {
            log.info("[EXT_CALL] Redis scan gateway instances");
            Map<String, String> raw = redisMessageBroker.scanGatewayInstances();
            Map<String, String> result = new HashMap<>();
            for (Map.Entry<String, String> entry : raw.entrySet()) {
                String wsUrl = extractWsUrl(entry.getValue());
                if (wsUrl != null) {
                    result.put(entry.getKey(), wsUrl);
                }
            }
            log.info("[EXIT] Redis scan succeeded: {} instances found", result.size());
            return result;
        } catch (Exception e) {
            log.info("Redis scan failed: {}", e.getMessage());
            return null;
        }
    }

    // ========== Change Notification ==========

    /**
     * Compare discovered instances against the known set and notify listeners of additions/removals.
     *
     * @param instances instanceId → wsUrl map from any discovery source
     */
    private void notifyChanges(Map<String, String> instances) {
        Set<String> discoveredIds = instances.keySet();

        // Added
        for (String id : discoveredIds) {
            if (!knownInstanceIds.contains(id)) {
                String wsUrl = instances.get(id);
                knownInstanceIds.add(id);
                for (Listener l : listeners) {
                    try {
                        l.onGatewayAdded(id, wsUrl);
                    } catch (Exception e) {
                        log.error("[ERROR] Listener onGatewayAdded failed: instanceId={}", id, e);
                    }
                }
                log.info("Gateway instance discovered: instanceId={}, wsUrl={}", id, wsUrl);
            }
        }

        // Removed
        Set<String> removed = new HashSet<>(knownInstanceIds);
        removed.removeAll(discoveredIds);
        for (String id : removed) {
            knownInstanceIds.remove(id);
            for (Listener l : listeners) {
                try {
                    l.onGatewayRemoved(id);
                } catch (Exception e) {
                    log.error("[ERROR] Listener onGatewayRemoved failed: instanceId={}", id, e);
                }
            }
            log.info("Gateway instance removed: instanceId={}", id);
        }
    }

    // ========== Utilities ==========

    private HttpClient getOrCreateHttpClient() {
        if (httpClient == null) {
            synchronized (this) {
                if (httpClient == null) {
                    httpClient = HttpClient.newBuilder()
                            .connectTimeout(Duration.ofMillis(discoveryTimeoutMs))
                            .build();
                }
            }
        }
        return httpClient;
    }

    /**
     * Extract wsUrl from a Redis-stored gateway instance JSON blob.
     * Expected format: {"wsUrl":"ws://..."}
     */
    private String extractWsUrl(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            return node.path("wsUrl").asText(null);
        } catch (Exception e) {
            log.warn("Failed to parse gateway instance info: {}", e.getMessage());
            return null;
        }
    }
}
