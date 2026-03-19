package com.opencode.cui.skill.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.opencode.cui.skill.model.AssistantResolveResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.List;

/**
 * 助手账号解析服务。
 * 将 IM 平台的 assistantAccount 解析为 ak（应用密钥）和 ownerWelinkId（助手拥有者 WeLink ID）。
 *
 * 解析流程：
 * 1. 先查 Redis 缓存 → 命中则直接返回
 * 2. 未命中 → 调用远程接口 GET resolveUrl?partnerAccount=xxx
 * 3. 将结果写入 Redis 缓存（TTL 可配置，默认 30 分钟）
 */
@Slf4j
@Service
public class AssistantAccountResolverService {

    private static final String CACHE_KEY_PREFIX = "assistantAccount:ak:"; // Redis 缓存 key 前缀：ak
    private static final String OWNER_CACHE_KEY_PREFIX = "assistantAccount:owner:"; // Redis 缓存 key 前缀：ownerWelinkId

    private final RestTemplate restTemplate; // HTTP 客户端
    private final StringRedisTemplate redisTemplate; // Redis 操作模板
    private final String resolveUrl; // 远程解析接口地址
    private final String resolveToken; // Bearer Token 认证
    private final int cacheTtlMinutes; // 缓存有效期（分钟）

    public AssistantAccountResolverService(
            RestTemplate restTemplate,
            StringRedisTemplate redisTemplate,
            @org.springframework.beans.factory.annotation.Value("${skill.assistant.resolve-url:}") String resolveUrl,
            @org.springframework.beans.factory.annotation.Value("${skill.assistant.resolve-token:}") String resolveToken,
            @org.springframework.beans.factory.annotation.Value("${skill.assistant.cache-ttl-minutes:30}") int cacheTtlMinutes) {
        this.restTemplate = restTemplate;
        this.redisTemplate = redisTemplate;
        this.resolveUrl = resolveUrl;
        this.resolveToken = resolveToken;
        this.cacheTtlMinutes = cacheTtlMinutes;
    }

    /**
     * 解析助手账号，返回 ak 和 ownerWelinkId。
     * 优先从 Redis 缓存读取；未命中则调用远端接口并缓存结果。
     *
     * @param assistantAccount IM 平台上的助手账号标识
     * @return 解析结果（ak + ownerWelinkId），解析失败返回 null
     */
    public AssistantResolveResult resolve(String assistantAccount) {
        if (assistantAccount == null || assistantAccount.isBlank() || resolveUrl == null || resolveUrl.isBlank()) {
            return null;
        }

        // 从 Redis 读取缓存的 ak 和 ownerWelinkId
        String akCacheKey = CACHE_KEY_PREFIX + assistantAccount;
        String ownerCacheKey = OWNER_CACHE_KEY_PREFIX + assistantAccount;
        String cachedAk = redisTemplate.opsForValue().get(akCacheKey);
        String cachedOwner = redisTemplate.opsForValue().get(ownerCacheKey);
        if (cachedAk != null && !cachedAk.isBlank() && cachedOwner != null && !cachedOwner.isBlank()) {
            return new AssistantResolveResult(cachedAk, cachedOwner); // 缓存命中，直接返回
        }

        try {
            // 构建 GET 请求：resolveUrl?partnerAccount=xxx
            String requestUrl = UriComponentsBuilder.fromUriString(resolveUrl)
                    .queryParam("partnerAccount", assistantAccount)
                    .build(true)
                    .toUriString();
            HttpHeaders headers = new HttpHeaders();
            headers.setAccept(List.of(MediaType.APPLICATION_JSON));
            if (resolveToken != null && !resolveToken.isBlank()) {
                headers.setBearerAuth(resolveToken); // 设置 Bearer Token 认证
            }
            HttpEntity<Void> request = new HttpEntity<>(headers);
            ResponseEntity<JsonNode> response = restTemplate.exchange(
                    requestUrl, HttpMethod.GET, request, JsonNode.class);

            // 从响应体中提取 ak 和 ownerWelinkId
            AssistantResolveResult result = extractResult(response.getBody());
            if (result == null) {
                log.warn("Failed to resolve assistant account: assistantAccount={}", assistantAccount);
                return null;
            }

            // 缓存解析结果到 Redis
            Duration ttl = Duration.ofMinutes(cacheTtlMinutes);
            redisTemplate.opsForValue().set(akCacheKey, result.ak(), ttl);
            redisTemplate.opsForValue().set(ownerCacheKey, result.ownerWelinkId(), ttl);
            return result;
        } catch (Exception e) {
            log.warn("Resolve assistant account failed: assistantAccount={}, error={}",
                    assistantAccount, e.getMessage());
            return null;
        }
    }

    /**
     * 便捷方法：仅返回 ak。
     */
    public String resolveAk(String assistantAccount) {
        AssistantResolveResult result = resolve(assistantAccount);
        return result != null ? result.ak() : null;
    }

    /**
     * 从接口响应体中提取 ak 和 ownerWelinkId。
     * 支持嵌套在 data 字段或直接位于根节点的两种格式。
     */
    private AssistantResolveResult extractResult(JsonNode body) {
        if (body == null || body.isNull()) {
            return null;
        }
        String ak = extractField(body, "appKey", "ak");
        String ownerWelinkId = extractField(body, "ownerWelinkId", "welinkId");
        if (ak == null || ak.isBlank()) {
            return null;
        }
        if (ownerWelinkId == null || ownerWelinkId.isBlank()) {
            log.warn("ownerWelinkId not found in resolve response, using assistantAccount as fallback");
            return null;
        }
        return new AssistantResolveResult(ak, ownerWelinkId);
    }

    /**
     * 从 JSON 中按多个候选字段名提取值。
     * 先尝试 body.data.fieldName，再尝试 body.fieldName。
     */
    private String extractField(JsonNode body, String... fieldNames) {
        for (String fieldName : fieldNames) {
            String nested = body.path("data").path(fieldName).asText(null); // 先查 data 子对象
            if (nested != null && !nested.isBlank()) {
                return nested;
            }
            String direct = body.path(fieldName).asText(null); // 再查根对象
            if (direct != null && !direct.isBlank()) {
                return direct;
            }
        }
        return null;
    }
}
