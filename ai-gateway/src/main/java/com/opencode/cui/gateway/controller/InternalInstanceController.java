package com.opencode.cui.gateway.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.gateway.service.GatewayInstanceRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Internal HTTP API for Gateway instance discovery.
 *
 * <p>Exposes registered Gateway instances from the aggregated Redis HASH
 * {@value GatewayInstanceRegistry#INSTANCES_HASH_KEY},
 * so that Skill Servers can discover all live Gateway instances via HTTP.</p>
 *
 * <p>Endpoint: {@code GET /internal/instances}</p>
 */
@Slf4j
@RestController
@RequestMapping("/internal")
public class InternalInstanceController {

    private static final TypeReference<Map<String, String>> MAP_TYPE = new TypeReference<>() {};

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /** 心跳过期判定阈值（秒）：超过此时间未刷新 lastHeartbeat 的实例视为已死亡。 */
    @Value("${gateway.instance-registry.stale-threshold-seconds:60}")
    private int staleThresholdSeconds;

    public InternalInstanceController(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Returns all currently alive Gateway instances.
     *
     * <p>Reads all fields from the aggregated HASH {@value GatewayInstanceRegistry#INSTANCES_HASH_KEY},
     * filters out entries whose {@code lastHeartbeat} has expired (stale instances),
     * and lazily removes stale entries via HDEL.</p>
     *
     * @return 200 OK with body {@code {"instances": [{"instanceId":"...", "wsUrl":"..."}]}}
     */
    @GetMapping("/instances")
    public ResponseEntity<Map<String, Object>> getInstances() {
        log.info("[ENTRY] GET /internal/instances");

        Map<Object, Object> entries = redisTemplate.opsForHash()
                .entries(GatewayInstanceRegistry.INSTANCES_HASH_KEY);
        List<Map<String, String>> instances = new ArrayList<>();

        for (Map.Entry<Object, Object> entry : entries.entrySet()) {
            String instanceId = String.valueOf(entry.getKey());
            String value = String.valueOf(entry.getValue());

            try {
                Map<String, String> parsed = objectMapper.readValue(value, MAP_TYPE);

                // 检查 lastHeartbeat 是否过期（null 也视为过期，防止 fallback JSON 无此字段时泄漏脏条目）
                String lastHeartbeat = parsed.get("lastHeartbeat");
                if (lastHeartbeat == null || isStale(lastHeartbeat)) {
                    // 惰性清理：心跳过期的死实例
                    redisTemplate.opsForHash().delete(
                            GatewayInstanceRegistry.INSTANCES_HASH_KEY, instanceId);
                    log.info("Removed stale GW instance: instanceId={}, lastHeartbeat={}",
                            instanceId, lastHeartbeat);
                    continue;
                }

                instances.add(Map.of(
                        "instanceId", parsed.getOrDefault("instanceId", ""),
                        "wsUrl", parsed.getOrDefault("wsUrl", "")));
            } catch (Exception e) {
                log.info("Skipping malformed entry: instanceId={}, error={}", instanceId, e.getMessage());
            }
        }

        log.info("[EXIT] GET /internal/instances - found {} instance(s)", instances.size());
        return ResponseEntity.ok(Map.of("instances", instances));
    }

    /**
     * 判断 lastHeartbeat 是否已过期。
     */
    private boolean isStale(String lastHeartbeat) {
        try {
            Instant heartbeatTime = Instant.parse(lastHeartbeat);
            return Duration.between(heartbeatTime, Instant.now()).getSeconds() > staleThresholdSeconds;
        } catch (Exception e) {
            // 无法解析时间戳，视为过期
            return true;
        }
    }
}
