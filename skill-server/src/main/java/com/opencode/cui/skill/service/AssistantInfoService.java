package com.opencode.cui.skill.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.config.AssistantInfoProperties;
import com.opencode.cui.skill.model.AssistantInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * AssistantInfo 查询服务。
 *
 * 功能：根据 ak 查询助手类型（business/personal），含 Redis 缓存。
 *
 * 缓存策略：
 * - Redis: key=ss:assistant:info:{ak}，value=JSON，TTL 可配置（默认 300s）
 *
 * 降级策略：
 * - 上游不可用时 getAssistantInfo 返回 null，getCachedScope 返回 "personal"
 *
 * identityType 映射：
 * - "3" → business
 * - "2" → personal
 */
@Slf4j
@Service
public class AssistantInfoService {

    private static final String CACHE_KEY_PREFIX = "ss:assistant:info:";

    /** identityType 值常量 */
    private static final String IDENTITY_TYPE_BUSINESS = "3";

    private final AssistantInfoProperties properties;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public AssistantInfoService(AssistantInfoProperties properties,
                                StringRedisTemplate redisTemplate) {
        this.properties = properties;
        this.redisTemplate = redisTemplate;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 获取 AssistantInfo。先查 Redis 缓存，miss 则调用上游 API 并写缓存。
     *
     * @param ak Agent 应用密钥
     * @return AssistantInfo，上游不可用时返回 null
     */
    public AssistantInfo getAssistantInfo(String ak) {
        String cacheKey = buildCacheKey(ak);

        // 1. 查询 Redis 缓存
        try {
            String cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached != null && !cached.isBlank()) {
                log.debug("[AssistantInfoService] cache hit: ak={}", ak);
                return objectMapper.readValue(cached, AssistantInfo.class);
            }
        } catch (Exception e) {
            log.warn("[AssistantInfoService] cache read error: ak={}, error={}", ak, e.getMessage());
        }

        // 2. 调用上游 API
        log.debug("[AssistantInfoService] cache miss, fetching from upstream: ak={}", ak);
        AssistantInfo info;
        try {
            info = fetchFromUpstream(ak);
        } catch (Exception e) {
            log.warn("[AssistantInfoService] upstream fetch failed: ak={}, error={}", ak, e.getMessage());
            return null;
        }

        if (info == null) {
            log.warn("[AssistantInfoService] upstream returned null: ak={}", ak);
            return null;
        }

        // 3. 写入 Redis 缓存
        try {
            String json = objectMapper.writeValueAsString(info);
            redisTemplate.opsForValue().set(cacheKey, json,
                    Duration.ofSeconds(properties.getCacheTtlSeconds()));
            log.debug("[AssistantInfoService] cached: ak={}, scope={}", ak, info.getAssistantScope());
        } catch (Exception e) {
            log.warn("[AssistantInfoService] cache write error: ak={}, error={}", ak, e.getMessage());
        }

        return info;
    }

    /**
     * 获取 scope，上游不可用或返回 null 时降级为 "personal"。
     *
     * @param ak Agent 应用密钥
     * @return "business" | "personal"
     */
    public String getCachedScope(String ak) {
        AssistantInfo info = getAssistantInfo(ak);
        if (info == null || info.getAssistantScope() == null) {
            log.debug("[AssistantInfoService] scope degraded to personal: ak={}", ak);
            return "personal";
        }
        return info.getAssistantScope();
    }

    /**
     * 调用上游 API 获取 AssistantInfo。
     *
     * 子类可 override 此方法，便于单元测试。
     *
     * @param ak Agent 应用密钥
     * @return AssistantInfo，解析失败时返回 null
     */
    protected AssistantInfo fetchFromUpstream(String ak) {
        String url = properties.getApiUrl() + "?ak=" + ak;
        long start = System.nanoTime();

        try {
            RestTemplate restTemplate = new RestTemplate();
            HttpHeaders headers = new HttpHeaders();
            if (properties.getApiToken() != null && !properties.getApiToken().isBlank()) {
                headers.set("Authorization", "Bearer " + properties.getApiToken());
            }
            HttpEntity<Void> request = new HttpEntity<>(headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.GET, request, String.class);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                log.warn("[AssistantInfoService] upstream non-success: ak={}, status={}, durationMs={}",
                        ak, response.getStatusCode(), elapsedMs);
                return null;
            }

            AssistantInfo info = parseApiResponse(response.getBody());
            log.info("[AssistantInfoService] upstream success: ak={}, scope={}, durationMs={}",
                    ak, info != null ? info.getAssistantScope() : null, elapsedMs);
            return info;

        } catch (Exception e) {
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            log.warn("[AssistantInfoService] upstream error: ak={}, durationMs={}, error={}",
                    ak, elapsedMs, e.getMessage());
            throw new RuntimeException("AssistantInfo upstream fetch failed: " + e.getMessage(), e);
        }
    }

    /**
     * 解析上游 API 响应 JSON。
     *
     * 响应格式：
     * <pre>
     * {
     *   "code": "200",
     *   "data": {
     *     "identityType": "3",   // "2"=personal, "3"=business
     *     "hisAppId": "app_36209",
     *     "endpoint": "https://cloud.example.com/chat",
     *     "protocol": "sse",
     *     "authType": "soa"
     *   }
     * }
     * </pre>
     *
     * @param responseBody 响应体 JSON 字符串
     * @return AssistantInfo，解析失败时返回 null
     */
    AssistantInfo parseApiResponse(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode dataNode = root.path("data");
            if (dataNode.isMissingNode()) {
                log.warn("[AssistantInfoService] parseApiResponse: missing data field");
                return null;
            }

            String identityType = dataNode.path("identityType").asText(null);
            String scope = IDENTITY_TYPE_BUSINESS.equals(identityType) ? "business" : "personal";

            AssistantInfo info = new AssistantInfo();
            info.setAssistantScope(scope);
            info.setAppId(dataNode.path("hisAppId").asText(null));
            info.setCloudEndpoint(dataNode.path("endpoint").asText(null));
            info.setCloudProtocol(dataNode.path("protocol").asText(null));
            info.setAuthType(dataNode.path("authType").asText(null));

            return info;

        } catch (Exception e) {
            log.warn("[AssistantInfoService] parseApiResponse error: {}", e.getMessage());
            return null;
        }
    }

    private String buildCacheKey(String ak) {
        return CACHE_KEY_PREFIX + ak;
    }
}
