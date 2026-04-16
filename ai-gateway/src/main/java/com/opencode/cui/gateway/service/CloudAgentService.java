package com.opencode.cui.gateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.opencode.cui.gateway.model.CloudRouteInfo;
import com.opencode.cui.gateway.model.GatewayMessage;
import com.opencode.cui.gateway.service.cloud.CloudConnectionContext;
import com.opencode.cui.gateway.service.cloud.CloudProtocolClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 云端 Agent 服务编排器。
 *
 * <p>负责编排云端 AI 调用的完整流程：
 * <ol>
 *   <li>从 CloudRouteService 获取路由信息</li>
 *   <li>构建 CloudConnectionContext</li>
 *   <li>通过 CloudProtocolClient 连接云端服务</li>
 *   <li>为云端返回的事件注入路由上下文后通过 onRelay 回调转发</li>
 * </ol>
 * </p>
 *
 * <p>不再直接依赖 {@link SkillRelayService}，改由调用方传入 {@code onRelay} 回调，
 * 以打破循环依赖：SkillRelayService → BusinessInvokeRouteStrategy → CloudAgentService。</p>
 */
@Slf4j
@Service
public class CloudAgentService {

    private final CloudRouteService cloudRouteService;
    private final CloudProtocolClient cloudProtocolClient;

    public CloudAgentService(CloudRouteService cloudRouteService,
                             CloudProtocolClient cloudProtocolClient) {
        this.cloudRouteService = cloudRouteService;
        this.cloudProtocolClient = cloudProtocolClient;
    }

    /**
     * 处理 invoke 消息，编排云端 AI 调用流程。
     *
     * @param invokeMessage invoke 消息
     * @param onRelay       回调：将需要转发的消息回传给调用方（通常是 SkillRelayService::relayToSkill）
     */
    public void handleInvoke(GatewayMessage invokeMessage, Consumer<GatewayMessage> onRelay) {
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
            onRelay.accept(errorMsg);
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

        // 3. 兜底 messageId/partId（每次 SSE 连接生成一份，云端没传时补上）
        String fallbackMessageId = "cloud-msg-" + UUID.randomUUID().toString().replace("-", "");
        ConcurrentHashMap<String, String> fallbackPartIds = new ConcurrentHashMap<>();

        // 4. 连接云端服务
        cloudProtocolClient.connect(routeInfo.getProtocol(), context, null,
                event -> {
                    // 注入路由上下文
                    event.setAk(ak);
                    event.setUserId(invokeMessage.getUserId());
                    event.setWelinkSessionId(invokeMessage.getWelinkSessionId());
                    event.setTraceId(invokeMessage.getTraceId());
                    if (event.getToolSessionId() == null) {
                        event.setToolSessionId(toolSessionId);
                    }

                    // 兜底：云端未传 messageId/partId 时 GW 自动补充
                    // GatewayMessage.event 结构: {"type":"text.delta","properties":{"content":"..."}}
                    // SS 的 CloudEventTranslator handler 从 event.properties 中读取字段
                    // 所以注入到 properties 和 event 顶层都需要（properties 给 handler 读，顶层给 translate 方法读）
                    JsonNode eventNode = event.getEvent();
                    if (eventNode != null && !eventNode.isMissingNode() && eventNode.isObject()) {
                        String eventType = eventNode.path("type").asText("");
                        com.fasterxml.jackson.databind.node.ObjectNode eventObj =
                                (com.fasterxml.jackson.databind.node.ObjectNode) eventNode;
                        JsonNode props = eventObj.path("properties");
                        com.fasterxml.jackson.databind.node.ObjectNode propsObj =
                                (props != null && props.isObject())
                                        ? (com.fasterxml.jackson.databind.node.ObjectNode) props : null;

                        // messageId 兜底：注入到 properties 中
                        boolean needMsgId = propsObj == null
                                || !propsObj.has("messageId")
                                || propsObj.path("messageId").asText("").isBlank();
                        if (needMsgId && propsObj != null) {
                            propsObj.put("messageId", fallbackMessageId);
                        }

                        // partId 兜底：注入到 properties 中
                        boolean needPartId = propsObj == null
                                || !propsObj.has("partId")
                                || propsObj.path("partId").asText("").isBlank();
                        if (needPartId && propsObj != null) {
                            String normalizedType = normalizeEventType(eventType);
                            String fbPartId = fallbackPartIds.computeIfAbsent(normalizedType,
                                    t -> "cloud-part-" + t + "-" + UUID.randomUUID().toString().substring(0, 8));
                            propsObj.put("partId", fbPartId);
                        }
                    }

                    onRelay.accept(event);
                },
                error -> {
                    log.error("[CLOUD_AGENT] Cloud connection error: ak={}, traceId={}, error={}",
                            ak, invokeMessage.getTraceId(), error.getMessage());
                    GatewayMessage errorMsg = buildCloudError(invokeMessage, toolSessionId, error);
                    onRelay.accept(errorMsg);
                }
        );
    }

    /**
     * 归一化事件类型（去掉 .delta/.done 后缀），使同类型事件共享 partId。
     */
    private static String normalizeEventType(String eventType) {
        if (eventType == null) return "unknown";
        if (eventType.endsWith(".delta")) return eventType.substring(0, eventType.length() - 6);
        if (eventType.endsWith(".done")) return eventType.substring(0, eventType.length() - 5);
        return eventType;
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
