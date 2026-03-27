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

import java.net.InetAddress;
import java.net.UnknownHostException;
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
 * <p>wsUrl 在启动时自动探测：优先使用 K8s Downward API 注入的 {@code POD_IP} 环境变量，
 * fallback 到 {@code InetAddress.getLocalHost()}。
 *
 * <p>HASH 无整体 TTL，单个 field 靠 {@code lastHeartbeat} 时间戳判活，
 * 由 {@link com.opencode.cui.gateway.controller.InternalInstanceController} 读取时惰性清理过期条目。
 */
@Slf4j
@Service
public class GatewayInstanceRegistry {

    /** 所有 GW 实例聚合的 Redis HASH key。 */
    public static final String INSTANCES_HASH_KEY = "gw:internal:instances";

    private static final String WS_PATH = "/ws/skill";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Value("${gateway.instance-id:${HOSTNAME:gateway-local}}")
    private String instanceId;

    @Value("${POD_IP:}")
    private String podIp;

    @Value("${server.port:8081}")
    private int serverPort;

    @Value("${server.servlet.context-path:}")
    private String contextPath;

    private String wsUrl;
    private String startedAt;

    public GatewayInstanceRegistry(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void register() {
        this.startedAt = Instant.now().toString();
        this.wsUrl = buildWsUrl();
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

    private String buildWsUrl() {
        String ip = resolveIp();
        String host = ip.contains(":") ? "[" + ip + "]" : ip;
        String url = "ws://" + host + ":" + serverPort + contextPath + WS_PATH;

        if (isLoopback(ip)) {
            log.warn("GatewayInstanceRegistry: detected loopback address, wsUrl={} — multi-instance routing may not work", url);
        }

        return url;
    }

    private String resolveIp() {
        if (podIp != null && !podIp.isBlank()) {
            log.info("Using POD_IP for wsUrl: {}", podIp);
            return podIp;
        }
        try {
            String localIp = InetAddress.getLocalHost().getHostAddress();
            log.info("POD_IP not set, falling back to InetAddress.getLocalHost(): {}", localIp);
            return localIp;
        } catch (UnknownHostException e) {
            log.warn("Failed to resolve local IP, falling back to 127.0.0.1: {}", e.getMessage());
            return "127.0.0.1";
        }
    }

    private static boolean isLoopback(String ip) {
        return "127.0.0.1".equals(ip) || "::1".equals(ip);
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
            return "{\"instanceId\":\"" + instanceId + "\",\"wsUrl\":\"" + wsUrl + "\"}";
        }
    }
}
