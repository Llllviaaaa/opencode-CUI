package com.opencode.cui.skill.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.model.AgentSummary;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * REST client for querying AI-Gateway APIs.
 */
@Slf4j
@Service
public class GatewayApiClient {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String gatewayBaseUrl;
    private final String internalToken;

    public GatewayApiClient(
            RestTemplate restTemplate,
            ObjectMapper objectMapper,
            @Value("${skill.gateway.api-base-url:http://localhost:8081}") String gatewayBaseUrl,
            @Value("${skill.gateway.internal-token:changeme}") String internalToken) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.gatewayBaseUrl = gatewayBaseUrl;
        this.internalToken = internalToken;
    }

    /**
     * Query online agents for a specific user from the Gateway.
     *
     * @param userId the user ID to filter by
     * @return list of online agent info maps
     */
    public List<Map<String, Object>> getOnlineAgentsByUserId(String userId) {
        try {
            String url = gatewayBaseUrl + "/api/gateway/agents?userId=" + userId;
            ResponseEntity<String> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    new HttpEntity<>(buildHeaders()),
                    String.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Map<String, Object> envelope = objectMapper.readValue(
                        response.getBody(),
                        new TypeReference<Map<String, Object>>() {
                        });
                Object data = envelope.get("data");
                if (data == null) {
                    return Collections.emptyList();
                }
                List<Map<String, Object>> agents = objectMapper.convertValue(
                        data,
                        new TypeReference<List<Map<String, Object>>>() {
                        });
                log.debug("Fetched {} online agents for userId={}", agents.size(), userId);
                return agents;
            }

            log.warn("Gateway returned non-success status: {}", response.getStatusCode());
            return Collections.emptyList();
        } catch (Exception e) {
            log.error("Failed to query Gateway for online agents: userId={}, error={}",
                    userId, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 查询指定用户的在线 Agent 列表，返回类型化的 AgentSummary DTO。
     */
    public List<AgentSummary> getOnlineAgentSummaries(String userId) {
        return getOnlineAgentsByUserId(userId).stream()
                .map(this::toAgentSummary)
                .toList();
    }

    private AgentSummary toAgentSummary(Map<String, Object> raw) {
        return AgentSummary.builder()
                .ak(strVal(raw, "ak"))
                .status(strVal(raw, "status"))
                .deviceName(strVal(raw, "deviceName"))
                .os(strVal(raw, "os"))
                .toolType(strVal(raw, "toolType"))
                .toolVersion(strVal(raw, "toolVersion"))
                .connectedAt(strVal(raw, "connectedAt"))
                .build();
    }

    private static String strVal(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v != null ? v.toString() : null;
    }

    public boolean isAkOwnedByUser(String ak, String userId) {
        if (ak == null || ak.isBlank() || userId == null || userId.isBlank()) {
            return false;
        }

        return getOnlineAgentsByUserId(userId).stream()
                .map(item -> item.get("ak"))
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .anyMatch(ak::equals);
    }

    private HttpHeaders buildHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(internalToken);
        return headers;
    }
}
