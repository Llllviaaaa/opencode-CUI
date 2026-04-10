package com.opencode.cui.gateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.opencode.cui.gateway.model.CloudRouteInfo;
import com.opencode.cui.gateway.model.GatewayMessage;
import com.opencode.cui.gateway.service.cloud.CloudConnectionContext;
import com.opencode.cui.gateway.service.cloud.CloudProtocolClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 云端 Agent 服务编排器。
 *
 * <p>负责编排云端 AI 调用的完整流程：
 * <ol>
 *   <li>从 CloudRouteService 获取路由信息</li>
 *   <li>构建 CloudConnectionContext</li>
 *   <li>通过 CloudProtocolClient 连接云端服务</li>
 *   <li>为云端返回的事件注入路由上下文后转发到 SkillRelayService</li>
 * </ol>
 * </p>
 */
@Slf4j
@Service
public class CloudAgentService {

    private final CloudRouteService cloudRouteService;
    private final CloudProtocolClient cloudProtocolClient;
    private final SkillRelayService skillRelayService;

    public CloudAgentService(CloudRouteService cloudRouteService,
                             CloudProtocolClient cloudProtocolClient,
                             SkillRelayService skillRelayService) {
        this.cloudRouteService = cloudRouteService;
        this.cloudProtocolClient = cloudProtocolClient;
        this.skillRelayService = skillRelayService;
    }

    /**
     * 处理 invoke 消息，编排云端 AI 调用流程。
     *
     * @param invokeMessage invoke 消息
     */
    public void handleInvoke(GatewayMessage invokeMessage) {
        String ak = invokeMessage.getAk();
        JsonNode cloudRequest = invokeMessage.getPayload().path("cloudRequest");
        String toolSessionId = invokeMessage.getPayload().path("toolSessionId").asText(null);

        log.info("[CLOUD_AGENT] handleInvoke: ak={}, toolSessionId={}, traceId={}",
                ak, toolSessionId, invokeMessage.getTraceId());

        // 1. 获取路由信息
        CloudRouteInfo routeInfo = cloudRouteService.getRouteInfo(ak);
        if (routeInfo == null) {
            log.warn("[CLOUD_AGENT] Route info not found: ak={}", ak);
            GatewayMessage errorMsg = buildCloudError(invokeMessage, toolSessionId,
                    new RuntimeException("Cloud route info not found for ak: " + ak));
            skillRelayService.relayToSkill(errorMsg);
            return;
        }

        // 2. 构建连接上下文
        CloudConnectionContext context = CloudConnectionContext.builder()
                .endpoint(routeInfo.getEndpoint())
                .cloudRequest(cloudRequest)
                .appId(routeInfo.getAppId())
                .authType(routeInfo.getAuthType())
                .traceId(invokeMessage.getTraceId())
                .build();

        // 3. 连接云端服务
        cloudProtocolClient.connect(routeInfo.getProtocol(), context,
                event -> {
                    // 注入路由上下文
                    event.setAk(ak);
                    event.setUserId(invokeMessage.getUserId());
                    event.setWelinkSessionId(invokeMessage.getWelinkSessionId());
                    event.setTraceId(invokeMessage.getTraceId());
                    if (event.getToolSessionId() == null) {
                        event.setToolSessionId(toolSessionId);
                    }
                    skillRelayService.relayToSkill(event);
                },
                error -> {
                    log.error("[CLOUD_AGENT] Cloud connection error: ak={}, traceId={}, error={}",
                            ak, invokeMessage.getTraceId(), error.getMessage());
                    GatewayMessage errorMsg = buildCloudError(invokeMessage, toolSessionId, error);
                    skillRelayService.relayToSkill(errorMsg);
                }
        );
    }

    /**
     * 构建云端错误消息（tool_error 类型）。
     */
    private GatewayMessage buildCloudError(GatewayMessage invokeMessage, String toolSessionId, Throwable error) {
        return GatewayMessage.builder()
                .type(GatewayMessage.Type.TOOL_ERROR)
                .ak(invokeMessage.getAk())
                .userId(invokeMessage.getUserId())
                .welinkSessionId(invokeMessage.getWelinkSessionId())
                .traceId(invokeMessage.getTraceId())
                .toolSessionId(toolSessionId)
                .error("Cloud agent error: " + error.getMessage())
                .build();
    }
}
