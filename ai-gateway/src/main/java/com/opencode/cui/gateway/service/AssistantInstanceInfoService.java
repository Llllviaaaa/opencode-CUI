package com.opencode.cui.gateway.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.gateway.model.AssistantInstanceInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Duration;
import java.util.List;

/**
 * Gateway 侧数字分身实例信息查询服务。
 *
 * <p>按 {@code partnerAccount/assistantAccount} 查询 instance/query 接口，并缓存完整 data。
 * UNKNOWN / NOT_EXISTS 不写缓存，避免把临时故障或空数据固化到远端调用热路径。</p>
 */
@Slf4j
@Service
public class AssistantInstanceInfoService {

    private static final String CACHE_KEY_PREFIX = "gw:assistant:instance:";

    private final RestTemplate restTemplate;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final String queryUrl;
    private final String queryToken;
    private final int cacheTtlSeconds;

    public AssistantInstanceInfoService(RestTemplate restTemplate,
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            @Value("${gateway.assistant.instance-query-url:${gateway.assistant-resolve.api-url:}}") String queryUrl,
            @Value("${gateway.assistant.instance-query-token:${gateway.assistant-resolve.bearer-token:}}") String queryToken,
            @Value("${gateway.assistant.instance-cache-ttl-seconds:${gateway.assistant-resolve.cache-ttl-seconds:300}}") int cacheTtlSeconds) {
        this.restTemplate = restTemplate;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.queryUrl = queryUrl;
        this.queryToken = queryToken;
        this.cacheTtlSeconds = cacheTtlSeconds;
    }

    public AssistantInstanceInfo getInstanceInfo(String partnerAccount) {
        if (partnerAccount == null || partnerAccount.isBlank()
                || queryUrl == null || queryUrl.isBlank()) {
            return null;
        }

        String cacheKey = cacheKey(partnerAccount);
        AssistantInstanceInfo cached = readFromCache(cacheKey);
        if (cached != null) {
            log.debug("[ASSISTANT_INSTANCE] cache hit: partnerAccount={}", mask(partnerAccount));
            return cached;
        }

        long start = System.nanoTime();
        String masked = mask(partnerAccount);
        try {
            String requestUrl = UriComponentsBuilder.fromUriString(queryUrl)
                    .queryParam("partnerAccount", partnerAccount)
                    .build(true)
                    .toUriString();
            HttpHeaders headers = new HttpHeaders();
            headers.setAccept(List.of(MediaType.APPLICATION_JSON));
            applyAuthorization(headers);
            ResponseEntity<JsonNode> response = restTemplate.exchange(
                    requestUrl, HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            AssistantInstanceInfo info = parseResponse(response, masked, elapsedMs);
            if (info != null) {
                writeCache(cacheKey, info);
            }
            return info;
        } catch (Exception e) {
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            log.warn("[EXT_CALL] AssistantInstance.query failed: partnerAccount={}, durationMs={}, error={}",
                    masked, elapsedMs, e.getMessage());
            return null;
        }
    }

    private AssistantInstanceInfo parseResponse(ResponseEntity<JsonNode> response,
                                                String maskedAccount,
                                                long elapsedMs) {
        if (response == null || !response.getStatusCode().is2xxSuccessful()) {
            log.warn("[EXT_CALL] AssistantInstance.query non-2xx: partnerAccount={}, durationMs={}, httpStatus={}",
                    maskedAccount, elapsedMs, response != null ? response.getStatusCode() : null);
            return null;
        }
        JsonNode body = response.getBody();
        if (body == null || body.isNull() || !isSuccessCode(body.get("code"))) {
            log.warn("[EXT_CALL] AssistantInstance.query business failure: partnerAccount={}, durationMs={}",
                    maskedAccount, elapsedMs);
            return null;
        }
        JsonNode data = body.get("data");
        if (data == null || data.isNull() || data.isMissingNode()
                || (data.isObject() && data.size() == 0)) {
            log.info("[EXT_CALL] AssistantInstance.query empty data: partnerAccount={}, durationMs={}",
                    maskedAccount, elapsedMs);
            return null;
        }
        try {
            AssistantInstanceInfo info = objectMapper.treeToValue(data, AssistantInstanceInfo.class);
            log.info("[EXT_CALL] AssistantInstance.query success: partnerAccount={}, durationMs={}, remote={}, remotePropertyCount={}",
                    maskedAccount, elapsedMs, info.remoteAssistant(),
                    info.getRemoteProperty() != null ? info.getRemoteProperty().size() : 0);
            return info;
        } catch (JsonProcessingException e) {
            log.warn("[EXT_CALL] AssistantInstance.query parse failed: partnerAccount={}, durationMs={}, error={}",
                    maskedAccount, elapsedMs, e.getMessage());
            return null;
        }
    }

    private void applyAuthorization(HttpHeaders headers) {
        if (queryToken == null || queryToken.isBlank()) {
            return;
        }
        if (queryToken.regionMatches(true, 0, "Bearer ", 0, 7)
                || queryToken.contains(" ")) {
            headers.set(HttpHeaders.AUTHORIZATION, queryToken);
            return;
        }
        headers.setBearerAuth(queryToken);
    }

    private AssistantInstanceInfo readFromCache(String cacheKey) {
        try {
            String raw = redisTemplate.opsForValue().get(cacheKey);
            if (raw == null || raw.isBlank()) {
                return null;
            }
            return objectMapper.readValue(raw, AssistantInstanceInfo.class);
        } catch (Exception e) {
            log.warn("[ASSISTANT_INSTANCE] cache read failed: key={}, error={}", cacheKey, e.getMessage());
            return null;
        }
    }

    private void writeCache(String cacheKey, AssistantInstanceInfo info) {
        try {
            redisTemplate.opsForValue().set(cacheKey,
                    objectMapper.writeValueAsString(info),
                    Duration.ofSeconds(cacheTtlSeconds));
        } catch (Exception e) {
            log.warn("[ASSISTANT_INSTANCE] cache write failed: key={}, error={}", cacheKey, e.getMessage());
        }
    }

    private String cacheKey(String partnerAccount) {
        return CACHE_KEY_PREFIX + partnerAccount;
    }

    private static boolean isSuccessCode(JsonNode codeNode) {
        if (codeNode == null || codeNode.isNull()) {
            return false;
        }
        if (codeNode.isInt() || codeNode.isLong()) {
            return codeNode.asInt() == 200;
        }
        return "200".equals(codeNode.asText());
    }

    private static String mask(String value) {
        if (value == null || value.length() <= 4) {
            return "****";
        }
        return value.substring(0, 2) + "****" + value.substring(value.length() - 2);
    }
}
