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
 * v1 实现：把现有 Cloud Route 旧接口包装为 {@link CallbackConfigResolver}。
 *
 * <p>仅支持 {@code callback:weagent:chat} scope，其他 scope 在 v1 阶段直接返回 null。
 * 旧接口形态为 GET-with-body（沿用历史 cloud route 接入端的非常规写法）。</p>
 *
 * <p>当 {@code gateway.cloud-route.api-version} 未配置或值为 {@code v1} 时装配此 Bean（默认）。</p>
 */
@Slf4j
@Component
public class LegacyRouteResolver implements CallbackConfigResolver {

    private static final String CHAT_SCOPE = "callback:weagent:chat";

    private final ObjectMapper objectMapper;
    private final String apiUrl;
    private final String bearerToken;
    private final HttpClient httpClient;

    public LegacyRouteResolver(
            ObjectMapper objectMapper,
            @Value("${gateway.cloud-route.api-url:}") String apiUrl,
            @Value("${gateway.cloud-route.bearer-token:}") String bearerToken) {
        this.objectMapper = objectMapper;
        this.apiUrl = apiUrl;
        this.bearerToken = bearerToken;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @Override public String version() { return "v1"; }

    @Override
    public CallbackConfig resolve(String ak, String scope) {
        if (!CHAT_SCOPE.equals(scope)) {
            log.debug("[CALLBACK_CONFIG_V1] scope not supported in v1: {}", scope);
            return null;
        }
        try {
            String body = objectMapper.writeValueAsString(Map.of("ak", ak));
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .header("Authorization", "Bearer " + bearerToken)
                    .header("Content-Type", "application/json")
                    .method("GET", HttpRequest.BodyPublishers.ofString(body))   // GET-with-body 沿用现状
                    .timeout(Duration.ofSeconds(5))
                    .build();
            HttpResponse<String> resp = sendRequest(req);
            if (resp.statusCode() != 200) {
                log.warn("[CALLBACK_CONFIG_V1] HTTP error: ak={}, status={}", ak, resp.statusCode());
                return null;
            }
            return parseResponse(ak, scope, resp.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[CALLBACK_CONFIG_V1] interrupted: ak={}", ak);
            return null;
        } catch (Exception e) {
            log.warn("[CALLBACK_CONFIG_V1] error: ak={}, error={}", ak, e.getMessage());
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
        cfg.setAppId(data.path("hisAppId").asText(null));
        cfg.setChannelAddress(data.path("endpoint").asText(null));
        cfg.setChannelType(mapProtocol(data.path("protocol").asText(null)));
        cfg.setAuthType(mapAuthType(data.path("authType").asText(null)));
        return cfg;
    }

    /**
     * 旧上游 protocol 数字码 → CallbackConfig.channelType。
     *
     * <p>对齐 v2 channelType 字典（1=webhook / 2=sse / 3=websocket），
     * 与历史 cloud route 接入端的协议映射（1=rest）不同；
     * 由于 {@code CloudProtocolClient} 从未注册 rest/webhook 策略，
     * 该差异在 chat 场景（仅消费 sse/websocket）下无运行时影响。</p>
     */
    private static String mapProtocol(String s) {
        if (s == null) return null;
        return switch (s) {
            case "1" -> "webhook";
            case "2" -> "sse";
            case "3" -> "websocket";
            default -> s;
        };
    }
    private static String mapAuthType(String s) {
        if (s == null) return null;
        return switch (s) {
            case "1" -> "soa";
            case "2" -> "apig";
            default -> s;
        };
    }
}
