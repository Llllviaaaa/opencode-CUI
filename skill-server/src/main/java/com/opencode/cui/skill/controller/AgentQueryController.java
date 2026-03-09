package com.opencode.cui.skill.controller;

import com.opencode.cui.skill.model.ApiResponse;
import com.opencode.cui.skill.service.GatewayApiClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Proxy endpoint for querying online agents from AI-Gateway.
 * Miniapp calls this instead of querying Gateway directly.
 */
@Slf4j
@RestController
@RequestMapping("/api/skill/agents")
public class AgentQueryController {

    private final GatewayApiClient gatewayApiClient;

    public AgentQueryController(GatewayApiClient gatewayApiClient) {
        this.gatewayApiClient = gatewayApiClient;
    }

    /**
     * GET /api/skill/agents
     * Returns online agents belonging to the cookie-authenticated user.
     */
    @GetMapping
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getOnlineAgents(
            @CookieValue(value = "userId", required = false) String userIdCookie) {
        if (userIdCookie == null || userIdCookie.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error(400, "userId is required"));
        }
        log.debug("Querying online agents for userId={}", userIdCookie);
        List<Map<String, Object>> agents = gatewayApiClient.getOnlineAgentsByUserId(userIdCookie);
        return ResponseEntity.ok(ApiResponse.ok(agents.stream().map(this::normalizeAgent).toList()));
    }

    private Map<String, Object> normalizeAgent(Map<String, Object> agent) {
        Map<String, Object> normalized = new LinkedHashMap<>(agent);
        Object ak = normalized.get("ak");
        if (!normalized.containsKey("akId") && ak instanceof String akValue && !akValue.isBlank()) {
            normalized.put("akId", akValue);
        }
        return normalized;
    }
}
