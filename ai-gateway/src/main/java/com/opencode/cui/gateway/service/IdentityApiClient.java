package com.opencode.cui.gateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * 外部统一身份校验 API 客户端。
 *
 * 调用外部 API 完成 AK/SK 签名验证，返回 userId。
 * 当外部 API 不可用或未配置时，调用方应降级到本地 DB。
 *
 * API 端点: POST /appstore/wecodeapi/open/identity/check
 */
@Slf4j
@Component
public class IdentityApiClient {

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String bearerToken;
    private final boolean enabled;

    public IdentityApiClient(
            ObjectMapper objectMapper,
            @Value("${gateway.auth.identity-api.base-url:}") String baseUrl,
            @Value("${gateway.auth.identity-api.bearer-token:}") String bearerToken,
            @Value("${gateway.auth.identity-api.timeout-ms:3000}") int timeoutMs) {
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl;
        this.bearerToken = bearerToken;
        this.enabled = baseUrl != null && !baseUrl.isBlank();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(timeoutMs))
                .build();
    }

    /**
     * 是否已配置外部 API（base-url 非空）。
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 调用外部身份校验 API。
     *
     * @return checkResult 为 true 时返回 userId；否则返回 null
     * @throws IdentityApiException 网络/超时/解析异常
     */
    public String check(String ak, String timestamp, String nonce, String signature) {
        if (!enabled) {
            throw new IdentityApiException("Identity API not configured");
        }

        long start = System.nanoTime();
        try {
            String requestBody = objectMapper.writeValueAsString(Map.of(
                    "ak", ak,
                    "timestamp", Long.parseLong(timestamp),
                    "nonce", nonce,
                    "sign", signature));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/appstore/wecodeapi/open/identity/check"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + bearerToken)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .timeout(Duration.ofSeconds(3))
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            if (response.statusCode() != 200) {
                log.warn("[EXT_CALL] IdentityAPI.check failed: status={}, ak={}, durationMs={}",
                        response.statusCode(), ak, elapsedMs);
                throw new IdentityApiException("HTTP " + response.statusCode());
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode data = root.path("data");

            if (data.path("checkResult").asBoolean(false)) {
                String userId = data.path("userId").asText(null);
                log.info("[EXT_CALL] IdentityAPI.check success: ak={}, userId={}, durationMs={}",
                        ak, userId, elapsedMs);
                return userId;
            }

            log.info("[EXT_CALL] IdentityAPI.check rejected: ak={}, code={}, durationMs={}",
                    ak, root.path("code").asText(), elapsedMs);
            return null;

        } catch (IdentityApiException e) {
            throw e;
        } catch (Exception e) {
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            log.warn("[EXT_CALL] IdentityAPI.check error: ak={}, durationMs={}, error={}",
                    ak, elapsedMs, e.getMessage());
            throw new IdentityApiException("API call failed: " + e.getMessage(), e);
        }
    }

    /**
     * 外部 API 调用异常（网络、超时、解析错误等）。
     * 调用方捕获此异常后应降级到本地 DB。
     */
    public static class IdentityApiException extends RuntimeException {
        public IdentityApiException(String message) {
            super(message);
        }

        public IdentityApiException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
