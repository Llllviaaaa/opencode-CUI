package com.opencode.cui.gateway.controller;

import com.opencode.cui.gateway.config.InternalAuthProperties;
import com.opencode.cui.gateway.model.AgentAvailabilityResponse;
import com.opencode.cui.gateway.model.AgentConnection;
import com.opencode.cui.gateway.model.AgentStatusResponse;
import com.opencode.cui.gateway.model.AgentSummaryResponse;
import com.opencode.cui.gateway.model.ApiResponse;
import com.opencode.cui.gateway.model.GatewayMessage;
import com.opencode.cui.gateway.model.InvokeResult;
import com.opencode.cui.gateway.service.AgentRegistryService;
import com.opencode.cui.gateway.service.EventRelayService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Gateway REST API 控制器。
 *
 * <p>协议端点：</p>
 * <ul>
 * <li>GET /api/gateway/agents — 查询在线 Agent 列表</li>
 * <li>GET /api/gateway/agents/status?ak= — 查询 Agent 状态</li>
 * <li>POST /api/gateway/invoke — 向 Agent 发送命令</li>
 * <li>GET /api/gateway/internal/agent/availability?ak= — 查询 Agent 可及性</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/gateway")
public class AgentController {

    private final AgentRegistryService agentRegistryService;
    private final EventRelayService eventRelayService;
    private final String internalToken;

    public AgentController(AgentRegistryService agentRegistryService,
            EventRelayService eventRelayService,
            InternalAuthProperties internalAuthProperties) {
        this.agentRegistryService = agentRegistryService;
        this.eventRelayService = eventRelayService;
        this.internalToken = internalAuthProperties.getInternalToken();
    }

    /** 查询在线 Agent 列表，支持按 AK 或 userId 过滤。 */
    @GetMapping("/agents")
    public ResponseEntity<ApiResponse<List<AgentSummaryResponse>>> listOnlineAgents(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(required = false) String ak,
            @RequestParam(required = false) String userId) {
        if (!isAuthorized(authorization)) {
            return ResponseEntity.status(401)
                    .body(ApiResponse.error(401, "Invalid or missing internal token"));
        }

        List<AgentConnection> agents;
        if (ak != null && !ak.isBlank()) {
            agents = agentRegistryService.findOnlineByAk(ak);
        } else if (userId != null && !userId.isBlank()) {
            agents = agentRegistryService.findOnlineByUserId(userId);
        } else {
            agents = agentRegistryService.findOnlineAgents();
        }

        List<AgentSummaryResponse> data = agents.stream()
                .map(AgentSummaryResponse::fromAgent)
                .toList();
        return ResponseEntity.ok(ApiResponse.ok(data));
    }

    /** 按 AK 查询 Agent 详细状态（含 WebSocket 连接和 OpenCode 在线状态）。 */
    @GetMapping("/agents/status")
    public ResponseEntity<ApiResponse<AgentStatusResponse>> getAgentStatusByAk(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam String ak) {
        if (!isAuthorized(authorization)) {
            return ResponseEntity.status(401)
                    .body(ApiResponse.error(401, "Invalid or missing internal token"));
        }

        AgentConnection agent = agentRegistryService.findLatestByAk(ak);
        if (agent == null) {
            return ResponseEntity.status(404).body(ApiResponse.error(404, "Agent not found"));
        }

        boolean wsActive = eventRelayService.hasAgentSession(ak);
        Boolean opencodeOnline = wsActive ? eventRelayService.requestAgentStatus(ak) : false;

        AgentStatusResponse status = new AgentStatusResponse(
                ak, agent.getStatus(), opencodeOnline, wsActive ? 1 : 0);

        return ResponseEntity.ok(ApiResponse.ok(status));
    }

    /** 通过 AK 向 Agent 发送 invoke 命令（新版协议端点）。 */
    @PostMapping("/invoke")
    public ResponseEntity<ApiResponse<InvokeResult>> invokeAgentByAk(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody GatewayMessage message) {
        if (!isAuthorized(authorization)) {
            return ResponseEntity.status(401)
                    .body(ApiResponse.error(401, "Invalid or missing internal token"));
        }

        String ak = message.getAk();
        if (ak == null || ak.isBlank()) {
            return ResponseEntity.badRequest().body(ApiResponse.error(400, "ak is required"));
        }

        AgentConnection agent = agentRegistryService.findLatestByAk(ak);
        if (agent == null) {
            return ResponseEntity.status(404).body(ApiResponse.error(404, "Agent not found"));
        }

        if (agent.getStatus() != AgentConnection.AgentStatus.ONLINE) {
            return ResponseEntity.badRequest().body(ApiResponse.error(400, "Agent is offline"));
        }

        if (!eventRelayService.hasAgentSession(ak)) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error(400, "No active WebSocket session for agent"));
        }

        log.info("[ENTRY] REST invoke to agent: ak={}, action={}", ak, message.getAction());
        eventRelayService.relayToAgent(ak, message.withAk(ak));

        log.info("[EXIT] REST invoke to agent: ak={}, action={}", ak, message.getAction());
        return ResponseEntity.ok(ApiResponse.ok(new InvokeResult(true, "Command sent to agent")));
    }


    /** 校验内部 Bearer Token 是否有效。 */
    private boolean isAuthorized(String authorization) {
        return authorization != null && authorization.equals("Bearer " + internalToken);
    }

    /** 查询 Agent 可及性信息（供 skill-server 差异化离线文案使用）。 */
    @GetMapping("/internal/agent/availability")
    public ResponseEntity<ApiResponse<AgentAvailabilityResponse>> getAgentAvailability(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam String ak) {
        if (!isAuthorized(authorization)) {
            return ResponseEntity.status(401)
                    .body(ApiResponse.error(401, "Invalid or missing internal token"));
        }
        if (ak.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error(400, "ak is required"));
        }
        AgentAvailabilityResponse availability = agentRegistryService.queryAvailability(ak);
        return ResponseEntity.ok(ApiResponse.ok(availability));
    }
}
