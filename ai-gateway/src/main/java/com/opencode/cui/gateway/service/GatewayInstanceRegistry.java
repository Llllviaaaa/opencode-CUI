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

import java.time.Instant;
import java.util.Map;

/**
 * Gateway 实例注册服务。
 *
 * <p>将本 Gateway 实例信息注册到 Redis 聚合 HASH，
 * 供 Skill Server 通过 HTTP 接口 {@code GET /internal/instances} 发现所有 GW 实例。
 *
 * <p>Redis 数据结构：
 * <ul>
 *   <li>Key: {@value #INSTANCES_HASH_KEY} (HASH)</li>
 *   <li>Field: instanceId</li>
 *   <li>Value: JSON (instanceId, wsUrl, startedAt, lastHeartbeat)</li>
 * </ul>
 *
 * <p>HASH 无整体 TTL，单个 field 靠 {@code lastHeartbeat} 时间戳判活，
 * 由 {@link com.opencode.cui.gateway.controller.InternalInstanceController} 读取时惰性清理过期条目。
 */
@Slf4j
@Service
public class GatewayInstanceRegistry {

    /** 所有 GW 实例聚合的 Redis HASH key。 */
    public static final String INSTANCES_HASH_KEY = "gw:internal:instances";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final String instanceId;
    private final String wsUrl;
    private final String startedAt;

    public GatewayInstanceRegistry(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            @Value("${gateway.instance-id:${HOSTNAME:gateway-local}}") String instanceId,
            @Value("${gateway.instance-registry.ws-url:ws://localhost:8081/ws/skill}") String wsUrl) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.instanceId = instanceId;
        this.wsUrl = wsUrl;
        this.startedAt = Instant.now().toString();
    }

    @PostConstruct
    public void register() {
        writeToRedis();
        log.info("[ENTRY] Gateway instance registered: instanceId={}, wsUrl={}", instanceId, wsUrl);
    }

    /**
     * 定时刷新心跳，更新 {@code lastHeartbeat} 时间戳。
     * 读端通过检查 {@code lastHeartbeat} 是否过期来判断实例是否存活。
     */
    @Scheduled(fixedDelayString = "${gateway.instance-registry.refresh-interval-seconds:10}000")
    public void refreshHeartbeat() {
        writeToRedis();
        log.debug("Gateway instance heartbeat refreshed: instanceId={}", instanceId);
    }

    @PreDestroy
    public void destroy() {
        try {
            redisTemplate.opsForHash().delete(INSTANCES_HASH_KEY, instanceId);
            log.info("[EXIT] Gateway instance deregistered: instanceId={}", instanceId);
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
        redisTemplate.opsForHash().put(INSTANCES_HASH_KEY, instanceId, value);
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
