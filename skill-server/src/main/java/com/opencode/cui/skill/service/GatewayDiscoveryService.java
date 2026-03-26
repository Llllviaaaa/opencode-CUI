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
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Gateway instance discovery service.
 *
 * <p>Discovery chain:
 * <ol>
 *   <li>Local cache (Caffeine-like TTL check, avoids unnecessary HTTP calls)</li>
 *   <li>HTTP endpoint (GET {discovery-url})</li>
 *   <li>Keep existing connections unchanged (all-fail safety net)</li>
 * </ol>
 *
 * <p>Scheduling is driven externally (e.g. @Scheduled or manual discover() calls).
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

    /** HTTP 发现结果缓存有效期（秒）。在此时间内 discover() 直接复用缓存，不发 HTTP 请求。 */
    @Value("${skill.gateway.discovery-cache-ttl-seconds:30}")
    private int discoveryCacheTtlSeconds;

    // ========== State ==========

    private final ObjectMapper objectMapper;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    /** Known GW instance IDs (thread-safe: discover() and getKnownInstanceIds() may run in different threads) */
    private final Set<String> knownInstanceIds = ConcurrentHashMap.newKeySet();

    /** Consecutive HTTP discovery failure counter; resets on success */
    private final AtomicInteger httpFailCount = new AtomicInteger(0);

    /** Lazily initialized; reuse across discover() calls */
    private volatile HttpClient httpClient;

    /** HTTP 发现结果缓存：上次成功的结果 + 时间戳 */
    private final AtomicReference<CachedDiscoveryResult> cachedResult = new AtomicReference<>();

    public GatewayDiscoveryService(ObjectMapper objectMapper) {
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
     * <p>Uses local cache to avoid unnecessary HTTP calls.
     * If cache is fresh, reuses the cached result.
     * If cache is stale or empty, tries HTTP discovery.
     * Keeps existing connections unchanged if HTTP fails.
     *
     * <p><strong>延迟预期</strong>：GW 实例变化（新增/宕机）最长可能延迟
     * {@code discoveryCacheTtlSeconds}（默认 30s）才被感知。
     * 加上 GW 侧 {@code staleThresholdSeconds}（默认 60s），
     * 一个宕机 GW 最长约 90s 后从发现结果中消失。
     */
    public void discover() {
        // Level 1: 检查缓存是否仍然有效
        CachedDiscoveryResult cached = cachedResult.get();
        if (cached != null && !cached.isExpired(discoveryCacheTtlSeconds)) {
            log.debug("Discovery skipped: cache still fresh (age={}s)", cached.ageSeconds());
            return;
        }

        // Level 2: HTTP discovery
        Map<String, String> instances = tryHttpDiscovery();

        if (instances != null) {
            // 缓存成功结果
            cachedResult.set(new CachedDiscoveryResult(instances));
            notifyChanges(instances);
            return;
        }

        // Level 3: HTTP 失败 — 保持现有连接不变
        log.info("HTTP discovery failed; keeping existing connections unchanged");
    }

    public Set<String> getKnownInstanceIds() {
        return Set.copyOf(knownInstanceIds);
    }

    // ========== HTTP Discovery ==========

    /**
     * Attempt to discover GW instances via HTTP endpoint.
     *
     * <p>Expected response format:
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

    // ========== Cache ==========

    /**
     * HTTP 发现结果的不可变缓存条目。
     */
    private static final class CachedDiscoveryResult {
        private final Map<String, String> instances;
        private final Instant cachedAt;

        CachedDiscoveryResult(Map<String, String> instances) {
            this.instances = Map.copyOf(instances);
            this.cachedAt = Instant.now();
        }

        boolean isExpired(int ttlSeconds) {
            return Duration.between(cachedAt, Instant.now()).getSeconds() >= ttlSeconds;
        }

        long ageSeconds() {
            return Duration.between(cachedAt, Instant.now()).getSeconds();
        }
    }
}
