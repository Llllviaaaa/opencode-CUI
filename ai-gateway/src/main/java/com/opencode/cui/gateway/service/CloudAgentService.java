package com.opencode.cui.gateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.opencode.cui.gateway.config.CloudTimeoutProperties;
import com.opencode.cui.gateway.model.AssistantInstanceInfo;
import com.opencode.cui.gateway.model.GatewayMessage;
import com.opencode.cui.gateway.service.cloud.CloudConnectionContext;
import com.opencode.cui.gateway.service.cloud.CloudConnectionLifecycle;
import com.opencode.cui.gateway.service.cloud.CloudProtocolClient;
import com.opencode.cui.gateway.service.cloud.WebHookExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 云端 Agent 服务编排器。
 *
 * <p>负责编排云端 AI 调用的完整流程：
 * <ol>
 *   <li>按 {@code action} 映射到 callback {@code scope}</li>
 *   <li>从 {@link CallbackConfigService} 获取回调订阅配置</li>
 *   <li>校验 {@code channelType} 与 {@code action} 匹配关系</li>
 *   <li>构建 {@link CloudConnectionContext}</li>
 *   <li>分叉到 {@link WebHookExecutor}（webhook）或 {@link CloudProtocolClient}（sse/websocket）</li>
 * </ol>
 * </p>
 *
 * <p>不再直接依赖 {@link SkillRelayService}，改由调用方传入 {@code onRelay} 回调，
 * 以打破循环依赖：SkillRelayService → BusinessInvokeRouteStrategy → CloudAgentService。</p>
 */
@Slf4j
@Service
public class CloudAgentService {

    /** action → callback scope 硬编码映射。 */
    private static final Map<String, String> ACTION_TO_SCOPE = Map.of(
            "chat",             "callback:weagent:chat",
            "question_reply",   "callback:weagent:question_reply",
            "permission_reply", "callback:weagent:permission_reply"
    );

    /** tool_error reason 枚举：让 SS 能精确区分失败类型，不再依赖 error 文案启发式。 */
    static final String REASON_CALLBACK_CONFIG_MISSING = "callback_config_missing";
    static final String REASON_REMOTE_PROPERTY_MISSING = "remote_property_missing";

    private final CallbackConfigService callbackConfigService;
    private final AssistantInstanceInfoService assistantInstanceInfoService;
    private final CloudProtocolClient cloudProtocolClient;
    private final WebHookExecutor webHookExecutor;
    private final CloudTimeoutProperties timeoutProperties;

    @Autowired
    public CloudAgentService(CallbackConfigService callbackConfigService,
                             AssistantInstanceInfoService assistantInstanceInfoService,
                             CloudProtocolClient cloudProtocolClient,
                             WebHookExecutor webHookExecutor,
                             CloudTimeoutProperties timeoutProperties) {
        this.callbackConfigService = callbackConfigService;
        this.assistantInstanceInfoService = assistantInstanceInfoService;
        this.cloudProtocolClient = cloudProtocolClient;
        this.webHookExecutor = webHookExecutor;
        this.timeoutProperties = timeoutProperties;
    }

    public CloudAgentService(CallbackConfigService callbackConfigService,
                             CloudProtocolClient cloudProtocolClient,
                             WebHookExecutor webHookExecutor,
                             CloudTimeoutProperties timeoutProperties) {
        this(callbackConfigService, null, cloudProtocolClient, webHookExecutor, timeoutProperties);
    }

    /**
     * 处理 invoke 消息，编排云端 AI 调用流程。
     *
     * @param invokeMessage invoke 消息
     * @param onRelay       回调：将需要转发的消息回传给调用方（通常是 SkillRelayService::relayToSkill）
     */
    public void handleInvoke(GatewayMessage invokeMessage, Consumer<GatewayMessage> onRelay) {
        String ak = invokeMessage.getAk();
        String action = invokeMessage.getAction();
        JsonNode cloudRequest = invokeMessage.getPayload().path("cloudRequest");
        String toolSessionId = invokeMessage.getPayload().path("toolSessionId").asText(null);
        String partnerAccount = firstNonBlank(
                invokeMessage.getPayload().path("assistantAccount").asText(null),
                invokeMessage.getPayload().path("partnerAccount").asText(null));
        // cloudProfile 字段缺失时默认 "default"（向后兼容老 SS）
        String cloudProfile = invokeMessage.getPayload().path("cloudProfile").asText("default");
        if (cloudProfile == null || cloudProfile.isBlank()) {
            cloudProfile = "default";
        }

        log.info("[CLOUD_AGENT] handleInvoke: ak={}, action={}, toolSessionId={}, traceId={}",
                ak, action, toolSessionId, invokeMessage.getTraceId());

        // 1. action → scope 映射
        String scope = ACTION_TO_SCOPE.get(action);
        if (scope == null) {
            log.warn("[CLOUD_AGENT] unknown action: ak={}, action={}", ak, action);
            onRelay.accept(buildCloudError(invokeMessage, toolSessionId,
                    new RuntimeException("Unknown action: " + action), null));
            return;
        }

        // 2. 优先按 assistantAccount/partnerAccount 查询 instance/query 的 remoteProperty。
        RemoteRoute remoteRoute = resolveRemoteRoute(partnerAccount, action, cloudProfile);
        if (remoteRoute != null) {
            invokeRemoteRoute(invokeMessage, onRelay, cloudRequest, toolSessionId, scope, remoteRoute);
            return;
        }

        if ((ak == null || ak.isBlank()) && partnerAccount != null && !partnerAccount.isBlank()) {
            log.warn("[CLOUD_AGENT] remote property missing and ak absent: partnerAccount={}, action={}",
                    mask(partnerAccount), action);
            onRelay.accept(buildCloudError(invokeMessage, toolSessionId,
                    new RuntimeException("Remote assistant route config missing for action: " + action),
                    REASON_REMOTE_PROPERTY_MISSING));
            return;
        }

        // 3. 兼容回退：拉取 (ak, scope, cloudProfile) 对应的旧回调配置
        //    cloudProfile == null/blank/"default" → V1 路径（老 cache key + 老 provider，零 cold miss）
        //    cloudProfile 具体值（如 "assistant_square"） → V2 路径（独立 cache key + 新 provider）
        CallbackConfig cfg = callbackConfigService.getConfig(ak, scope, cloudProfile);
        if (cfg == null) {
            String reason = "chat".equals(action)
                    ? "Cloud route info not found for ak: " + ak
                    : action + " not enabled (v1 mode or AK not subscribed)";
            log.warn("[CLOUD_AGENT] callback config missing: ak={}, scope={}, action={}", ak, scope, action);
            onRelay.accept(buildCloudError(invokeMessage, toolSessionId,
                    new RuntimeException(reason), REASON_CALLBACK_CONFIG_MISSING));
            return;
        }

        // 4. channelType vs action 校验：
        //    - chat 必须 sse/websocket
        //    - question_reply / permission_reply 必须 webhook
        boolean expectsWebhook = !"chat".equals(action);
        boolean isWebhook = "webhook".equals(cfg.getChannelType());
        if (expectsWebhook != isWebhook) {
            String msg = "chat".equals(action)
                    ? "Invalid channel type for chat: " + cfg.getChannelType()
                    : "Invalid channel type for reply: " + cfg.getChannelType();
            log.warn("[CLOUD_AGENT] channel type mismatch: ak={}, action={}, channelType={}",
                    ak, action, cfg.getChannelType());
            onRelay.accept(buildCloudError(invokeMessage, toolSessionId, new RuntimeException(msg), null));
            return;
        }

        // 5. 构建连接上下文
        CloudConnectionContext context = CloudConnectionContext.builder()
                .channelAddress(cfg.getChannelAddress())
                .channelType(cfg.getChannelType())
                .scope(scope)
                .appId(cfg.getAppId())
                .authType(cfg.getAuthType())
                .cloudRequest(cloudRequest)
                .traceId(invokeMessage.getTraceId())
                .cloudProfile(cloudProfile)
                .build();

        // 6. 分叉
        if (isWebhook) {
            webHookExecutor.execute(context, onRelay, invokeMessage, toolSessionId);
            return;
        }

        // 7. chat：SSE / WebSocket 走原 CloudProtocolClient 逻辑（保留 lifecycle / fallback messageId / fallback partId）
        invokeStreaming(invokeMessage, onRelay, context, cfg.getChannelType(), ak, toolSessionId);
    }

    private void invokeRemoteRoute(GatewayMessage invokeMessage,
                                   Consumer<GatewayMessage> onRelay,
                                   JsonNode cloudRequest,
                                   String toolSessionId,
                                   String scope,
                                   RemoteRoute remoteRoute) {
        boolean expectsWebhook = !"chat".equals(invokeMessage.getAction());
        boolean isWebhook = "webhook".equals(remoteRoute.channelType());
        if (expectsWebhook != isWebhook) {
            String msg = "chat".equals(invokeMessage.getAction())
                    ? "Invalid channel type for chat: " + remoteRoute.channelType()
                    : "Invalid channel type for reply: " + remoteRoute.channelType();
            onRelay.accept(buildCloudError(invokeMessage, toolSessionId, new RuntimeException(msg), null));
            return;
        }

        CloudConnectionContext context = CloudConnectionContext.builder()
                .channelAddress(remoteRoute.channelAddress())
                .channelType(remoteRoute.channelType())
                .scope(scope)
                .appId(remoteRoute.appId())
                .authType("none")
                .remoteHeaders(remoteRoute.headers())
                .cloudRequest(cloudRequest)
                .traceId(invokeMessage.getTraceId())
                .cloudProfile(remoteRoute.cloudProfile())
                .build();

        if (isWebhook) {
            webHookExecutor.execute(context, onRelay, invokeMessage, toolSessionId);
            return;
        }
        invokeStreaming(invokeMessage, onRelay, context, remoteRoute.channelType(),
                invokeMessage.getAk(), toolSessionId);
    }

    private RemoteRoute resolveRemoteRoute(String partnerAccount, String action, String fallbackCloudProfile) {
        if (assistantInstanceInfoService == null
                || partnerAccount == null || partnerAccount.isBlank()) {
            return null;
        }
        AssistantInstanceInfo info = assistantInstanceInfoService.getInstanceInfo(partnerAccount);
        if (info == null || info.getRemoteProperty() == null || info.getRemoteProperty().isEmpty()) {
            return null;
        }
        String abilityType = abilityType(action);
        for (AssistantInstanceInfo.RemoteProperty property : info.getRemoteProperty()) {
            if (property == null || !abilityType.equalsIgnoreCase(blankToEmpty(property.getType()))) {
                continue;
            }
            String channelAddress = firstNonBlank(property.getUrl());
            String channelType = mapChannelType(property.getCommProtocol());
            if (channelAddress == null || channelType == null) {
                continue;
            }
            return new RemoteRoute(channelAddress, channelType, null,
                    firstNonBlank(property.getDataProtocol(), fallbackCloudProfile),
                    property.getHeaders());
        }
        return null;
    }

    private static String abilityType(String action) {
        return "chat".equals(action) ? "chat" : "question";
    }

    private static String mapChannelType(String commProtocol) {
        String value = blankToEmpty(commProtocol).toLowerCase();
        return switch (value) {
            case "sse" -> "sse";
            case "ws", "websocket" -> "websocket";
            case "http", "webhook" -> "webhook";
            default -> null;
        };
    }

    /**
     * 调用流式协议（SSE / WebSocket）。保留原实现的全部行为：
     * <ul>
     *   <li>{@link CloudConnectionLifecycle} 三段超时（首事件 / 空闲 / 最大时长）</li>
     *   <li>fallback messageId：优先沿用云端首个事件携带的 messageId，缺失则生成</li>
     *   <li>fallback partId：按归一化后的事件类型分组，每类共享同一个 partId</li>
     *   <li>errorSent CAS：超时或 onError 任一方触发后只回流一次 tool_error</li>
     * </ul>
     */
    private void invokeStreaming(GatewayMessage invokeMessage,
                                 Consumer<GatewayMessage> onRelay,
                                 CloudConnectionContext context,
                                 String protocol,
                                 String ak,
                                 String toolSessionId) {
        AtomicReference<String> fallbackMessageIdRef = new AtomicReference<>(null);
        ConcurrentHashMap<String, String> fallbackPartIds = new ConcurrentHashMap<>();
        AtomicBoolean errorSent = new AtomicBoolean(false);

        CloudConnectionLifecycle lifecycle = new CloudConnectionLifecycle(
                timeoutProperties.getFirstEventTimeoutSeconds(),
                timeoutProperties.getEffectiveIdleTimeoutSeconds(protocol),
                timeoutProperties.getMaxDurationSeconds(),
                (timeoutType, elapsedSeconds) -> {
                    log.warn("[CLOUD_AGENT] Connection timeout: ak={}, traceId={}, type={}, elapsed={}s",
                            ak, invokeMessage.getTraceId(), timeoutType, elapsedSeconds);
                    GatewayMessage errorMsg = buildCloudError(invokeMessage, toolSessionId,
                            new RuntimeException(timeoutType + " (elapsed: " + elapsedSeconds + "s)"), null);
                    if (errorSent.compareAndSet(false, true)) { onRelay.accept(errorMsg); }
                },
                () -> log.info("[CLOUD_AGENT] Connection closed by lifecycle: ak={}, traceId={}",
                        ak, invokeMessage.getTraceId())
        );

        try {
            cloudProtocolClient.connect(protocol, context, lifecycle,
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
                            ObjectNode eventObj = (ObjectNode) eventNode;
                            JsonNode props = eventObj.path("properties");
                            ObjectNode propsObj = (props != null && props.isObject())
                                    ? (ObjectNode) props : null;

                            // messageId 兜底：优先从云端事件学习，不存在时生成
                            String eventMsgId = (propsObj != null && propsObj.has("messageId"))
                                    ? propsObj.path("messageId").asText("") : "";
                            if (!eventMsgId.isBlank()) {
                                // 学习云端首个携带 messageId 的事件，作为后续的 fallback
                                fallbackMessageIdRef.compareAndSet(null, eventMsgId);
                            } else if (propsObj != null) {
                                // 云端没传 messageId，使用已学习的或生成兜底
                                String fallback = fallbackMessageIdRef.updateAndGet(current ->
                                        current != null ? current
                                                : "cloud-msg-" + UUID.randomUUID().toString().replace("-", ""));
                                propsObj.put("messageId", fallback);
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

                        if (errorSent.get()) return;
                        onRelay.accept(event);
                    },
                    error -> {
                        log.error("[CLOUD_AGENT] Cloud connection error: ak={}, traceId={}, error={}",
                                ak, invokeMessage.getTraceId(), error.getMessage());
                        GatewayMessage errorMsg = buildCloudError(invokeMessage, toolSessionId, error, null);
                        if (errorSent.compareAndSet(false, true)) { onRelay.accept(errorMsg); }
                    }
            );
        } finally {
            lifecycle.close();
        }
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
     *
     * @param reason 失败原因枚举（如 {@link #REASON_CALLBACK_CONFIG_MISSING}）；
     *               可为 null，老 SS 仍可通过 error 文案启发式 fallback。
     */
    private GatewayMessage buildCloudError(GatewayMessage invokeMessage, String toolSessionId,
                                           Throwable error, String reason) {
        return GatewayMessage.builder()
                .type(GatewayMessage.Type.TOOL_ERROR)
                .ak(invokeMessage.getAk())
                .userId(invokeMessage.getUserId())
                .welinkSessionId(invokeMessage.getWelinkSessionId())
                .traceId(invokeMessage.getTraceId())
                .toolSessionId(toolSessionId)
                .error("Cloud agent error: " + error.getMessage())
                .reason(reason)
                .build();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String blankToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String mask(String value) {
        if (value == null || value.length() <= 4) {
            return "****";
        }
        return value.substring(0, 2) + "****" + value.substring(value.length() - 2);
    }

    private record RemoteRoute(String channelAddress,
                               String channelType,
                               String appId,
                               String cloudProfile,
                               List<AssistantInstanceInfo.RemoteHeader> headers) {
    }
}
