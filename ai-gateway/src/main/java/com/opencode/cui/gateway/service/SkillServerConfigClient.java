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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * skill-server SysConfig 客户端。
 *
 * <p>对外暴露 {@link #getConfigValue(String, String)} 方法，用于跨服务读取 SS 端
 * 的 {@code sys_config} 表配置。GW 内部的云端路由兜底直接通过本客户端读取。</p>
 *
 * <p>调用 SS 端 {@code GET /api/admin/configs/value?type=&key=}（见
 * {@code SysConfigController.getValue}），用 Bearer token 鉴权（与 IM inbound 同
 * 一个 token，配置 {@code gateway.skill-server.api-token}）。</p>
 */
@Slf4j
@Component
public class SkillServerConfigClient {

    private final ObjectMapper objectMapper;
    private final String apiBase;
    private final String authToken;
    private final HttpClient httpClient;

    public SkillServerConfigClient(
            ObjectMapper objectMapper,
            @Value("${gateway.skill-server.api-base:http://localhost:8082}") String apiBase,
            @Value("${gateway.skill-server.api-token:}") String authToken) {
        this.objectMapper = objectMapper;
        this.apiBase = apiBase;
        this.authToken = authToken;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
    }

    /**
     * 查询单条 SysConfig 值；配置不存在 / 上游异常 / 超时一律返回 null。
     */
    public String getConfigValue(String configType, String configKey) {
        try {
            String url = apiBase + "/api/admin/configs/value"
                    + "?type=" + URLEncoder.encode(configType, StandardCharsets.UTF_8)
                    + "&key=" + URLEncoder.encode(configKey, StandardCharsets.UTF_8);
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .timeout(Duration.ofSeconds(3));
            if (authToken != null && !authToken.isBlank()) {
                builder.header("Authorization", "Bearer " + authToken);
            }
            HttpResponse<String> resp = sendRequest(builder.build());
            if (resp.statusCode() != 200) {
                log.warn("[SS_CONFIG] non-200: type={}, key={}, status={}",
                        configType, configKey, resp.statusCode());
                return null;
            }
            JsonNode root = objectMapper.readTree(resp.body());
            JsonNode value = root.path("data").path("configValue");
            if (value.isMissingNode() || value.isNull()) return null;
            String s = value.asText(null);
            return (s == null || s.isBlank()) ? null : s;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[SS_CONFIG] interrupted: type={}, key={}", configType, configKey);
            return null;
        } catch (Exception e) {
            log.warn("[SS_CONFIG] error: type={}, key={}, error={}",
                    configType, configKey, e.getMessage());
            return null;
        }
    }

    /** 暴露给测试 mock */
    protected HttpResponse<String> sendRequest(HttpRequest req) throws Exception {
        return httpClient.send(req, HttpResponse.BodyHandlers.ofString());
    }
}
