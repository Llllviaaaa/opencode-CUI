package com.opencode.cui.gateway.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Internal HTTP API for Gateway instance discovery.
 *
 * <p>Exposes registered Gateway instances stored in the internal Redis key space
 * ({@code gw:internal:instance:*}) so that new-version Skill Servers can discover
 * all live Gateway instances via HTTP instead of scanning the shared Redis directly.</p>
 *
 * <p>Endpoint: {@code GET /internal/instances}</p>
 */
@Slf4j
@RestController
@RequestMapping("/internal")
public class InternalInstanceController {

    private static final String INTERNAL_KEY_PREFIX = "gw:internal:instance:";
    private static final TypeReference<Map<String, String>> MAP_TYPE = new TypeReference<>() {};

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public InternalInstanceController(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Returns all currently registered Gateway instances.
     *
     * <p>Scans {@code gw:internal:instance:*} keys in Redis, parses each value as
     * {@code {"instanceId":"...", "wsUrl":"..."}} JSON, and returns the assembled list.</p>
     *
     * @return 200 OK with body {@code {"instances": [{"instanceId":"...", "wsUrl":"..."}]}}
     */
    @GetMapping("/instances")
    public ResponseEntity<Map<String, Object>> getInstances() {
        log.info("[ENTRY] GET /internal/instances - scanning registered GW instances");

        Set<String> keys = redisTemplate.keys(INTERNAL_KEY_PREFIX + "*");
        List<Map<String, String>> instances = new ArrayList<>();

        if (keys != null && !keys.isEmpty()) {
            for (String key : keys) {
                String value = redisTemplate.opsForValue().get(key);
                if (value == null) {
                    log.info("Skipping key with null value: key={}", key);
                    continue;
                }
                try {
                    Map<String, String> entry = objectMapper.readValue(value, MAP_TYPE);
                    instances.add(Map.of(
                            "instanceId", entry.getOrDefault("instanceId", ""),
                            "wsUrl", entry.getOrDefault("wsUrl", "")));
                } catch (Exception e) {
                    log.info("Skipping key with malformed JSON value: key={}, error={}", key, e.getMessage());
                }
            }
        }

        log.info("[EXIT] GET /internal/instances - found {} instance(s)", instances.size());
        return ResponseEntity.ok(Map.of("instances", instances));
    }
}
