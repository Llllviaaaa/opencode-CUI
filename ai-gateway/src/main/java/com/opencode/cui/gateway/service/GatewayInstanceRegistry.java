package com.opencode.cui.gateway.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Gateway 实例注册服务。
 *
 * 将本 Gateway 实例的 WebSocket 地址注册到 Redis，
 * 供 Source 服务（如 Skill Server）通过定期扫描发现所有 Gateway 实例。
 *
 * Redis Key (legacy, shared): gw:instance:{instanceId}
 * Redis Key (internal):       gw:internal:instance:{instanceId}
 * Value: {"instanceId":"...","wsUrl":"ws://...","startedAt":"...","lastHeartbeat":"..."}
 * TTL: 可配置（默认 30s），通过定时任务刷新
 *
 * Dual-write strategy: both keys are maintained so that legacy Skill Servers (scanning
 * gw:instance:*) and new Skill Servers (calling GET /internal/instances) can coexist.
 */
@Slf4j
@Service
public class GatewayInstanceRegistry {

    private static final String KEY_PREFIX = "gw:instance:";
    private static final String INTERNAL_KEY_PREFIX = "gw:internal:instance:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final String instanceId;
    private final String wsUrl;
    private final Duration ttl;
    private final String startedAt;

    public GatewayInstanceRegistry(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            @Value("${gateway.instance-id:${HOSTNAME:gateway-local}}") String instanceId,
            @Value("${gateway.instance-registry.ws-url:ws://localhost:8081/ws/skill}") String wsUrl,
            @Value("${gateway.instance-registry.ttl-seconds:30}") int ttlSeconds) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.instanceId = instanceId;
        this.wsUrl = wsUrl;
        this.ttl = Duration.ofSeconds(ttlSeconds);
        this.startedAt = Instant.now().toString();
    }

    @PostConstruct
    public void register() {
        writeToRedis();
        log.info("[ENTRY] Gateway instance registered: instanceId={}, wsUrl={}, ttl={}s",
                instanceId, wsUrl, ttl.getSeconds());
    }

    /**
     * 定时刷新心跳，防止 Redis key 过期导致 Source 服务误判 GW 下线。
     */
    @Scheduled(fixedDelayString = "${gateway.instance-registry.refresh-interval-seconds:10}000")
    public void refreshHeartbeat() {
        writeToRedis();
        log.debug("Gateway instance heartbeat refreshed: instanceId={}", instanceId);
    }

    @PreDestroy
    public void destroy() {
        try {
            redisTemplate.delete(redisKey());
            redisTemplate.delete(internalRedisKey());
            log.info("[EXIT] Gateway instance deregistered from both keys: instanceId={}", instanceId);
        } catch (Exception e) {
            log.warn("Failed to deregister gateway instance: instanceId={}, error={}",
                    instanceId, e.getMessage());
        }
    }

    public String getInstanceId() {
        return instanceId;
    }

    public String getWsUrl() {
        return wsUrl;
    }

    private void writeToRedis() {
        String value = buildRegistrationValue();
        // Legacy key: consumed by old-version Skill Servers scanning gw:instance:*
        redisTemplate.opsForValue().set(redisKey(), value, ttl);
        // Internal key: consumed by new-version Skill Servers via GET /internal/instances
        redisTemplate.opsForValue().set(internalRedisKey(), value, ttl);
    }

    private String redisKey() {
        return KEY_PREFIX + instanceId;
    }

    private String internalRedisKey() {
        return INTERNAL_KEY_PREFIX + instanceId;
    }

    private String buildRegistrationValue() {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "instanceId", instanceId,
                    "wsUrl", wsUrl,
                    "startedAt", startedAt,
                    "lastHeartbeat", Instant.now().toString()));
        } catch (JsonProcessingException e) {
            // ObjectMapper serialization of a simple Map should never fail; fallback to minimal JSON
            return "{\"instanceId\":\"" + instanceId + "\",\"wsUrl\":\"" + wsUrl + "\"}";
        }
    }
}
