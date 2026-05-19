package com.opencode.cui.skill.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.config.InternalAuthProperties;
import com.opencode.cui.skill.model.AgentSummary;
import com.opencode.cui.skill.model.GatewayAvailabilityResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.List;

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
            @org.springframework.beans.factory.annotation.Value("${skill.gateway.api-base-url:http://localhost:8081}") String gatewayBaseUrl,
            InternalAuthProperties internalAuthProperties) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.gatewayBaseUrl = gatewayBaseUrl;
        this.internalToken = internalAuthProperties.getInternalToken();
    }

    /**
     * Query online agents for a specific user from the Gateway.
     *
     * @param userId the user ID to filter by
     * @return list of online agent summaries
     */
    public List<AgentSummary> getOnlineAgentsByUserId(String userId) {
        long start = System.nanoTime();
        try {
            String url = gatewayBaseUrl + "/api/gateway/agents?userId=" + userId;
            ResponseEntity<String> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    new HttpEntity<>(buildHeaders()),
                    String.class);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                var root = objectMapper.readTree(response.getBody());
                var dataNode = root.path("data");
                if (dataNode.isMissingNode() || dataNode.isNull()) {
                    return Collections.emptyList();
                }
                List<AgentSummary> agents = objectMapper.convertValue(
                        dataNode,
                        new TypeReference<List<AgentSummary>>() {
                        });
                log.info("[EXT_CALL] GatewayAPI.getAgents success: userId={}, count={}, durationMs={}",
                        userId, agents.size(), elapsedMs);
                return agents;
            }

            log.warn("[EXT_CALL] GatewayAPI.getAgents non-success: userId={}, status={}, durationMs={}",
                    userId, response.getStatusCode(), elapsedMs);
            return Collections.emptyList();
        } catch (Exception e) {
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            log.error("[EXT_CALL] GatewayAPI.getAgents failed: userId={}, durationMs={}, error={}",
                    userId, elapsedMs, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 查询指定用户的在线 Agent 列表，返回类型化的 AgentSummary DTO。
     * 
     * @deprecated 使用 {@link #getOnlineAgentsByUserId(String)} 代替，已直接返回
     *             AgentSummary。
     */
    @Deprecated
    public List<AgentSummary> getOnlineAgentSummaries(String userId) {
        return getOnlineAgentsByUserId(userId);
    }

    /**
     * 通过 ak 查询 Agent 摘要信息（含 toolType）。
     * 调用 GET {gatewayBaseUrl}/api/gateway/agents?ak={ak}，取返回列表第一条。
     *
     * @param ak Agent 应用密钥
     * @return Agent 摘要，查询失败或 Agent 不在线时返回 null
     */
    public AgentSummary getAgentByAk(String ak) {
        if (ak == null || ak.isBlank()) {
            return null;
        }

        long start = System.nanoTime();
        try {
            String url = gatewayBaseUrl + "/api/gateway/agents?ak=" + ak;
            ResponseEntity<String> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    new HttpEntity<>(buildHeaders()),
                    String.class);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                var root = objectMapper.readTree(response.getBody());
                var dataNode = root.path("data");
                if (dataNode.isMissingNode() || dataNode.isNull() || !dataNode.isArray() || dataNode.isEmpty()) {
                    log.info("[EXT_CALL] GatewayAPI.getAgentByAk not_found: ak={}, durationMs={}",
                            ak, elapsedMs);
                    return null;
                }
                AgentSummary agent = objectMapper.convertValue(dataNode.get(0), AgentSummary.class);
                log.info("[EXT_CALL] GatewayAPI.getAgentByAk success: ak={}, toolType={}, durationMs={}",
                        ak, agent.getToolType(), elapsedMs);
                return agent;
            }

            log.warn("[EXT_CALL] GatewayAPI.getAgentByAk non-success: ak={}, status={}, durationMs={}",
                    ak, response.getStatusCode(), elapsedMs);
            return null;
        } catch (Exception e) {
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            log.error("[EXT_CALL] GatewayAPI.getAgentByAk failed: ak={}, durationMs={}, error={}",
                    ak, elapsedMs, e.getMessage());
            return null;
        }
    }

    /**
     * 查询 Agent 可及性信息（供差异化离线文案使用）。
     * 调用 GET {gatewayBaseUrl}/internal/agent/availability?ak={ak}。
     *
     * @return GatewayAvailabilityResponse，网络 5xx/超时/解析失败时返回 null
     */
    public GatewayAvailabilityResponse getAvailability(String ak) {
        if (ak == null || ak.isBlank()) {
            return null;
        }
        long start = System.nanoTime();
        try {
            String url = gatewayBaseUrl + "/internal/agent/availability?ak=" + ak;
            ResponseEntity<String> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    new HttpEntity<>(buildHeaders()),
                    String.class);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                var root = objectMapper.readTree(response.getBody());
                var dataNode = root.path("data");
                if (dataNode.isMissingNode() || dataNode.isNull()) {
                    log.warn("[EXT_CALL] GatewayAPI.getAvailability null_data: ak={}, durationMs={}",
                            ak, elapsedMs);
                    return null;
                }
                GatewayAvailabilityResponse result = objectMapper.convertValue(
                        dataNode, GatewayAvailabilityResponse.class);
                log.info("[EXT_CALL] GatewayAPI.getAvailability success: ak={}, exists={}, online={}, toolType={}, durationMs={}",
                        ak, result.exists(), result.online(), result.latestToolType(), elapsedMs);
                return result;
            }

            log.warn("[EXT_CALL] GatewayAPI.getAvailability non-success: ak={}, status={}, durationMs={}",
                    ak, response.getStatusCode(), elapsedMs);
            return null;
        } catch (Exception e) {
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            log.error("[EXT_CALL] GatewayAPI.getAvailability failed: ak={}, durationMs={}, error={}",
                    ak, elapsedMs, e.getMessage());
            return null;
        }
    }

    private HttpHeaders buildHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(internalToken);
        return headers;
    }
}
