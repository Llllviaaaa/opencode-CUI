package com.opencode.cui.gateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

@Slf4j
@Component
@ConditionalOnProperty(name = "gateway.cloud-route.api-version", havingValue = "v2")
public class GatewayCallbackResolver implements CallbackConfigResolver {

    private final ObjectMapper objectMapper;
    private final String apiUrl;
    private final String bearerToken;
    private final HttpClient httpClient;

    public GatewayCallbackResolver(
            ObjectMapper objectMapper,
            @Value("${gateway.cloud-route.v2-api-url:}") String apiUrl,
            @Value("${gateway.cloud-route.v2-bearer-token:}") String bearerToken) {
        this.objectMapper = objectMapper;
        this.apiUrl = apiUrl;
        this.bearerToken = bearerToken;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @Override public String version() { return "v2"; }

    @Override
    public CallbackConfig resolve(String ak, String scope) {
        try {
            String body = objectMapper.writeValueAsString(Map.of("ak", ak, "scope", scope));
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .header("Authorization", "Bearer " + bearerToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofSeconds(5))
                    .build();
            HttpResponse<String> resp = sendRequest(req);
            if (resp.statusCode() != 200) {
                log.warn("[CALLBACK_CONFIG_V2] HTTP error: ak={}, scope={}, status={}",
                        ak, scope, resp.statusCode());
                return null;
            }
            return parseResponse(ak, scope, resp.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[CALLBACK_CONFIG_V2] interrupted: ak={}, scope={}", ak, scope);
            return null;
        } catch (Exception e) {
            log.warn("[CALLBACK_CONFIG_V2] error: ak={}, scope={}, error={}",
                    ak, scope, e.getMessage());
            return null;
        }
    }

    /** 暴露给测试 mock */
    protected HttpResponse<String> sendRequest(HttpRequest req) throws Exception {
        return httpClient.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private CallbackConfig parseResponse(String ak, String scope, String body) throws Exception {
        JsonNode root = objectMapper.readTree(body);
        if (!"200".equals(root.path("code").asText(""))) return null;
        JsonNode data = root.path("data");
        if (data.isMissingNode() || data.isNull()) return null;
        CallbackConfig cfg = new CallbackConfig();
        cfg.setAk(ak);
        cfg.setScope(scope);
        cfg.setChannelType(mapChannelType(data.path("channelType").asInt(-1)));
        cfg.setChannelAddress(data.path("channelAddress").asText(null));
        cfg.setAuthType(mapAuthType(data.path("authType").asInt(-1)));
        cfg.setAppId(null);   // v2 不返回 appId
        return cfg;
    }

    private static String mapChannelType(int code) {
        return switch (code) {
            case 1 -> "webhook";
            case 2 -> "sse";
            case 3 -> "websocket";
            default -> null;
        };
    }
    private static String mapAuthType(int code) {
        return switch (code) {
            case 0 -> "none";
            case 1 -> "soa";
            case 2 -> "apig";
            default -> null;
        };
    }
}
