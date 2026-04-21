package com.opencode.cui.gateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * 助手账号解析服务。
 *
 * <p>调用上游 resolve API 验证 assistantAccount 是否为有效 agent 账号，
 * 并获取其创建者信息。使用 Redis 缓存以减少外部调用。</p>
 *
 * <h3>缓存策略</h3>
 * <ul>
 *   <li>缓存 key：{@code gw:assistant:resolve:{assistantAccount}}</li>
 *   <li>TTL 通过 {@code gateway.assistant-resolve.cache-ttl-seconds} 配置（默认 300s）</li>
 * </ul>
 *
 * <h3>上游 API</h3>
 * <pre>GET {gateway.assistant-resolve.api-url}?partnerAccount={assistantAccount}
 * Authorization: Bearer {gateway.assistant-resolve.bearer-token}</pre>
 *
 * <p>响应格式：
 * <pre>{"data":{"appKey":"xxx","create_by":"900001"}}</pre>
 */
@Slf4j
@Service
public class AssistantAccountResolver {

    private static final String CACHE_KEY_PREFIX = "gw:assistant:resolve:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final String apiUrl;
    private final String bearerToken;
    private final long cacheTtlSeconds;
    private final HttpClient httpClient;

    public AssistantAccountResolver(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            @Value("${gateway.assistant-resolve.api-url:}") String apiUrl,
            @Value("${gateway.assistant-resolve.bearer-token:}") String bearerToken,
            @Value("${gateway.assistant-resolve.cache-ttl-seconds:300}") long cacheTtlSeconds) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.apiUrl = apiUrl;
        this.bearerToken = bearerToken;
        this.cacheTtlSeconds = cacheTtlSeconds;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    /**
     * 解析 assistantAccount，返回其 appKey 和创建者信息。
     *
     * <p>先查 Redis 缓存，命中则直接返回；未命中则调用上游 API 并回写缓存。</p>
     *
     * @param assistantAccount 助手账号
     * @return 解析结果，无效账号或上游不可用时返回 {@code null}
     */
    public ResolveResult resolve(String assistantAccount) {
        String cacheKey = CACHE_KEY_PREFIX + assistantAccount;

        // 1. 查 Redis 缓存
        ResolveResult cached = getFromCache(cacheKey);
        if (cached != null) {
            log.debug("[ASSISTANT_RESOLVE] Cache hit: account={}", assistantAccount);
            return cached;
        }

        // 2. 缓存未命中，调用上游 API
        String responseBody;
        try {
            responseBody = fetchFromUpstream(assistantAccount);
        } catch (Exception e) {
            log.warn("[ASSISTANT_RESOLVE] Upstream API error: account={}, error={}",
                    assistantAccount, e.getMessage());
            return null;
        }

        if (responseBody == null) {
            log.warn("[ASSISTANT_RESOLVE] Upstream API returned null: account={}", assistantAccount);
            return null;
        }

        // 3. 解析响应
        ResolveResult result = parseResponse(assistantAccount, responseBody);
        if (result == null) {
            return null;
        }

        // 4. 写入缓存
        writeToCache(cacheKey, result);
        log.info("[ASSISTANT_RESOLVE] Fetched and cached: account={}, ak={}, createBy={}",
                assistantAccount, result.getAk(), result.getCreateBy());
        return result;
    }

    // -----------------------------------------------------------------------
    // Protected 方法（便于测试时 Spy mock）
    // -----------------------------------------------------------------------

    /**
     * 调用上游 resolve API 获取原始响应体。
     */
    protected String fetchFromUpstream(String assistantAccount) throws Exception {
        String url = apiUrl + "?partnerAccount=" + assistantAccount;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + bearerToken)
                .GET()
                .timeout(Duration.ofSeconds(5))
                .build();

        long start = System.nanoTime();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        if (response.statusCode() != 200) {
            log.warn("[ASSISTANT_RESOLVE] Upstream HTTP error: account={}, status={}, durationMs={}",
                    assistantAccount, response.statusCode(), elapsedMs);
            throw new RuntimeException("Upstream HTTP " + response.statusCode());
        }

        log.debug("[ASSISTANT_RESOLVE] Upstream response: account={}, durationMs={}", assistantAccount, elapsedMs);
        return response.body();
    }

    // -----------------------------------------------------------------------
    // 私有辅助方法
    // -----------------------------------------------------------------------

    private ResolveResult getFromCache(String cacheKey) {
        try {
            String json = redisTemplate.opsForValue().get(cacheKey);
            if (json == null) {
                return null;
            }
            return objectMapper.readValue(json, ResolveResult.class);
        } catch (Exception e) {
            log.debug("[ASSISTANT_RESOLVE] Cache read error: key={}, error={}", cacheKey, e.getMessage());
            return null;
        }
    }

    private void writeToCache(String cacheKey, ResolveResult result) {
        try {
            String json = objectMapper.writeValueAsString(result);
            redisTemplate.opsForValue().set(cacheKey, json, cacheTtlSeconds, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("[ASSISTANT_RESOLVE] Cache write error: key={}, error={}", cacheKey, e.getMessage());
        }
    }

    /**
     * 解析上游 API 响应。
     *
     * <p>响应格式：{@code {"data":{"appKey":"xxx","create_by":"900001"}}}</p>
     * <p>当 data 为 null 时视为无效账号。</p>
     */
    private ResolveResult parseResponse(String assistantAccount, String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode data = root.path("data");
            if (data.isMissingNode() || data.isNull()) {
                log.warn("[ASSISTANT_RESOLVE] Upstream response data is null: account={}", assistantAccount);
                return null;
            }

            ResolveResult result = new ResolveResult();
            result.setAk(data.path("appKey").asText(null));
            result.setCreateBy(data.path("create_by").asText(null));
            return result;

        } catch (Exception e) {
            log.warn("[ASSISTANT_RESOLVE] Response parse error: account={}, error={}",
                    assistantAccount, e.getMessage());
            return null;
        }
    }

    /**
     * 助手账号解析结果。
     */
    @Data
    public static class ResolveResult {
        /** appKey */
        private String ak;
        /** 创建者账号 */
        private String createBy;
    }
}
