package com.opencode.cui.gateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.opencode.cui.gateway.config.CloudTimeoutProperties;
import com.opencode.cui.gateway.logging.GatewayStreamEventLogHelper;
import com.opencode.cui.gateway.logging.MdcHelper;
import com.opencode.cui.gateway.model.AssistantInstanceInfo;
import com.opencode.cui.gateway.model.GatewayMessage;
import com.opencode.cui.gateway.model.RelayMessage;
import com.opencode.cui.gateway.service.cloud.CloudConnectionContext;
import com.opencode.cui.gateway.service.cloud.CloudConnectionHandle;
import com.opencode.cui.gateway.service.cloud.CloudConnectionLifecycle;
import com.opencode.cui.gateway.service.cloud.CloudProtocolClient;
import com.opencode.cui.gateway.service.cloud.WebHookExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
 *   <li>按 assistantAccount 查询远端 remoteProperty，未命中则直接读取 SS SysConfig</li>
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

    private static final String ACTION_ABORT_SESSION = "abort_session";

    /** action → callback scope 硬编码映射。 */
    private static final Map<String, String> ACTION_TO_SCOPE = Map.of(
            "chat",             "callback:weagent:chat",
            "question_reply",   "callback:weagent:question_reply",
            "permission_reply", "callback:weagent:permission_reply"
    );

    /** tool_error reason 枚举：让 SS 能精确区分失败类型，不再依赖 error 文案启发式。 */
    static final String REASON_CALLBACK_CONFIG_MISSING = "callback_config_missing";

    private final SysConfigFallbackProviderV2 sysConfigRouteProvider;
    private final CloudRouteSwitchService cloudRouteSwitchService;
    private final AssistantInstanceInfoService assistantInstanceInfoService;
    private final CloudProtocolClient cloudProtocolClient;
    private final WebHookExecutor webHookExecutor;
    private final CloudTimeoutProperties timeoutProperties;
    private final RedisMessageBroker redisMessageBroker;
    private final ObjectMapper objectMapper;
    private final String gatewayInstanceId;
    private final ConcurrentHashMap<String, ActiveCloudConnection> activeStreamingConnections =
            new ConcurrentHashMap<>();

    @Autowired
    public CloudAgentService(SysConfigFallbackProviderV2 sysConfigRouteProvider,
                             CloudRouteSwitchService cloudRouteSwitchService,
                             AssistantInstanceInfoService assistantInstanceInfoService,
                             CloudProtocolClient cloudProtocolClient,
                             WebHookExecutor webHookExecutor,
                             CloudTimeoutProperties timeoutProperties,
                             RedisMessageBroker redisMessageBroker,
                             ObjectMapper objectMapper,
                             @Value("${gateway.instance-id:${HOSTNAME:gateway-local}}") String gatewayInstanceId) {
        this.sysConfigRouteProvider = sysConfigRouteProvider;
        this.cloudRouteSwitchService = cloudRouteSwitchService;
        this.assistantInstanceInfoService = assistantInstanceInfoService;
        this.cloudProtocolClient = cloudProtocolClient;
        this.webHookExecutor = webHookExecutor;
        this.timeoutProperties = timeoutProperties;
        this.redisMessageBroker = redisMessageBroker;
        this.objectMapper = objectMapper;
        this.gatewayInstanceId = gatewayInstanceId;
    }

    public CloudAgentService(SysConfigFallbackProviderV2 sysConfigRouteProvider,
                             CloudRouteSwitchService cloudRouteSwitchService,
                             AssistantInstanceInfoService assistantInstanceInfoService,
                             CloudProtocolClient cloudProtocolClient,
                             WebHookExecutor webHookExecutor,
                             CloudTimeoutProperties timeoutProperties) {
        this(sysConfigRouteProvider, cloudRouteSwitchService, assistantInstanceInfoService,
                cloudProtocolClient, webHookExecutor, timeoutProperties, null, new ObjectMapper(), "gateway-local");
    }

    public CloudAgentService(SysConfigFallbackProviderV2 sysConfigRouteProvider,
                             CloudRouteSwitchService cloudRouteSwitchService,
                             CloudProtocolClient cloudProtocolClient,
                             WebHookExecutor webHookExecutor,
                             CloudTimeoutProperties timeoutProperties) {
        this(sysConfigRouteProvider, cloudRouteSwitchService, null, cloudProtocolClient, webHookExecutor, timeoutProperties);
    }

    public CloudAgentService(SysConfigFallbackProviderV2 sysConfigRouteProvider,
                             CloudProtocolClient cloudProtocolClient,
                             WebHookExecutor webHookExecutor,
                             CloudTimeoutProperties timeoutProperties) {
        this(sysConfigRouteProvider, null, null, cloudProtocolClient, webHookExecutor, timeoutProperties);
    }

    /**
     * 处理 invoke 消息，编排云端 AI 调用流程。
     *
     * @param invokeMessage invoke 消息
     * @param onRelay       回调：将需要转发的消息回传给调用方（通常是 SkillRelayService::relayToSkill）
     */
    public void handleInvoke(GatewayMessage invokeMessage, Consumer<GatewayMessage> onRelay) {
        String ak = invokeMessage.getAk();
        String action = normalizeAction(invokeMessage.getAction());
        if (action != null && !action.equals(invokeMessage.getAction())) {
            invokeMessage = invokeMessage.toBuilder().action(action).build();
        }
        JsonNode payload = invokeMessage.getPayload();
        JsonNode cloudRequest = payload == null ? null : payload.path("cloudRequest");
        String toolSessionId = firstNonBlank(
                invokeMessage.getToolSessionId(),
                textAt(payload, "toolSessionId"));
        String assistantAccount = firstNonBlank(
                invokeMessage.getAssistantAccount(),
                textAt(payload, "assistantAccount"),
                textAt(payload, "partnerAccount"));
        String businessTag = firstNonBlank(
                invokeMessage.getBusinessTag(),
                textAt(payload, "businessTag"),
                textAt(payload, "cloudProfile"));

        log.info("[CLOUD_AGENT] handleInvoke: ak={}, action={}, assistantAccount={}, businessTag={}, toolSessionId={}, traceId={}",
                ak, action, mask(assistantAccount), businessTag, toolSessionId, invokeMessage.getTraceId());

        if (ACTION_ABORT_SESSION.equals(action)) {
            cancelStreamingConnection(invokeMessage, toolSessionId);
            return;
        }

        // 1. action → scope 映射
        String scope = ACTION_TO_SCOPE.get(action);
        if (scope == null) {
            log.warn("[CLOUD_AGENT] unknown action: ak={}, action={}", ak, action);
            onRelay.accept(buildCloudError(invokeMessage, toolSessionId,
                    new RuntimeException("Unknown action: " + action), null));
            return;
        }

        RemoteRoute remoteRoute = null;
        if (assistantAccount != null && !assistantAccount.isBlank()) {
            if (remotePropertyEnabled()) {
                remoteRoute = resolveRemoteRoute(assistantAccount, action, businessTag);
            } else {
                log.info("[CLOUD_AGENT] remoteProperty lookup disabled by SysConfig: assistantAccount={}, businessTag={}, action={}",
                        mask(assistantAccount), businessTag, action);
            }
        }
        if (remoteRoute != null) {
            invokeRemoteRoute(invokeMessage, onRelay, cloudRequest, toolSessionId, scope, remoteRoute);
            return;
        }

        CallbackConfig cfg = sysConfigRouteProvider.load(ak, scope, businessTag);
        if (cfg == null) {
            String reason = "Cloud route SysConfig not found for businessTag: " + businessTag
                    + ", action: " + action;
            log.warn("[CLOUD_AGENT] sysconfig route missing: ak={}, scope={}, action={}, assistantAccount={}, businessTag={}",
                    ak, scope, action, mask(assistantAccount), businessTag);
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
                .cloudProfile(businessTag)
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
                .authType(remoteRoute.authType())
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

    private RemoteRoute resolveRemoteRoute(String assistantAccount, String action, String fallbackBusinessTag) {
        if (assistantInstanceInfoService == null
                || assistantAccount == null || assistantAccount.isBlank()) {
            return null;
        }
        AssistantInstanceInfo info = assistantInstanceInfoService.getInstanceInfo(assistantAccount);
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
            String authType = resolveAuthType(property.getHeaders());
            return new RemoteRoute(channelAddress, channelType, null,
                    firstNonBlank(info.getBizRobotTag(), fallbackBusinessTag),
                    authType);
        }
        return null;
    }

    private boolean remotePropertyEnabled() {
        return cloudRouteSwitchService == null || cloudRouteSwitchService.remotePropertyEnabled();
    }

    private static String abilityType(String action) {
        return "chat".equals(action) ? "chat" : "question";
    }

    private static String normalizeAction(String action) {
        if (action == null) {
            return null;
        }
        return action.trim().toLowerCase(Locale.ROOT);
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

    private static String resolveAuthType(List<AssistantInstanceInfo.RemoteHeader> headers) {
        if (headers == null || headers.isEmpty()) {
            return "none";
        }
        AssistantInstanceInfo.RemoteHeader first = headers.get(0);
        return mapRemoteHeaderTypeToAuthType(first == null ? null : first.getType());
    }

    private static String mapRemoteHeaderTypeToAuthType(String type) {
        String value = blankToEmpty(type).trim().toLowerCase();
        return switch (value) {
            case "" -> null;
            case "0", "none", "noauth", "no_auth" -> "none";
            case "1", "soa" -> "soa";
            case "2", "apig" -> "apig";
            case "3", "integration", "integration_token" -> "integration_token";
            default -> value;
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
        CloudConnectionHandle connectionHandle = new CloudConnectionHandle();
        context.setConnectionHandle(connectionHandle);
        ActiveCloudConnection activeConnection = registerActiveConnection(
                toolSessionId, invokeMessage.getWelinkSessionId(), connectionHandle);
        registerCloudStreamRoute(toolSessionId);

        CloudConnectionLifecycle lifecycle = new CloudConnectionLifecycle(
                timeoutProperties.getFirstEventTimeoutSeconds(),
                timeoutProperties.getEffectiveIdleTimeoutSeconds(protocol),
                timeoutProperties.getMaxDurationSeconds(),
                (timeoutType, elapsedSeconds) -> {
                    if (connectionHandle.isCancelled()) {
                        log.info("[CLOUD_AGENT] Ignore timeout after cancellation: ak={}, traceId={}, type={}",
                                ak, invokeMessage.getTraceId(), timeoutType);
                        return;
                    }
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
                        if (connectionHandle.isCancelled()) {
                            return;
                        }
                        String rawPayload = rawCloudPayload(event);
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
                        var previousMdc = MdcHelper.snapshot();
                        try {
                            MdcHelper.fromGatewayMessage(event);
                            MdcHelper.putScenario("cloud-agent-stream-rx");
                            if (!isSseProtocol(protocol)) {
                                GatewayStreamEventLogHelper.inbound(log, "gw.cloud_agent", "received", rawPayload);
                            }
                            onRelay.accept(event);
                        } finally {
                            MdcHelper.restore(previousMdc);
                        }
                    },
                    error -> {
                        if (connectionHandle.isCancelled()) {
                            log.info("[CLOUD_AGENT] Cloud connection cancelled: ak={}, traceId={}, toolSessionId={}",
                                    ak, invokeMessage.getTraceId(), toolSessionId);
                            return;
                        }
                        log.error("[CLOUD_AGENT] Cloud connection error: ak={}, traceId={}, error={}",
                                ak, invokeMessage.getTraceId(), error.getMessage());
                        GatewayMessage errorMsg = buildCloudError(invokeMessage, toolSessionId, error, null);
                        if (errorSent.compareAndSet(false, true)) { onRelay.accept(errorMsg); }
                    }
            );
        } finally {
            removeActiveConnection(activeConnection);
            removeCloudStreamRoute(toolSessionId);
            lifecycle.close();
        }
    }

    private void cancelStreamingConnection(GatewayMessage invokeMessage, String toolSessionId) {
        List<String> keys = activeConnectionKeys(toolSessionId, invokeMessage.getWelinkSessionId());
        if (keys.isEmpty()) {
            log.info("[CLOUD_AGENT] abort_session ignored: reason=no_connection_key, traceId={}",
                    invokeMessage.getTraceId());
            return;
        }

        ActiveCloudConnection activeConnection = null;
        for (String key : keys) {
            activeConnection = activeStreamingConnections.get(key);
            if (activeConnection != null) {
                break;
            }
        }
        if (activeConnection == null) {
            if (relayAbortToOwningGateway(invokeMessage, toolSessionId)) {
                return;
            }
            log.info("[CLOUD_AGENT] abort_session ignored: reason=no_active_connection, toolSessionId={}, welinkSessionId={}, traceId={}",
                    toolSessionId, invokeMessage.getWelinkSessionId(), invokeMessage.getTraceId());
            return;
        }

        removeActiveConnection(activeConnection);
        removeCloudStreamRoute(toolSessionId);
        boolean cancelled = activeConnection.handle().cancel();
        log.info("[CLOUD_AGENT] abort_session cancelled active stream: cancelled={}, toolSessionId={}, welinkSessionId={}, traceId={}",
                cancelled, toolSessionId, invokeMessage.getWelinkSessionId(), invokeMessage.getTraceId());
    }

    private void registerCloudStreamRoute(String toolSessionId) {
        if (!hasText(toolSessionId) || redisMessageBroker == null || !hasText(gatewayInstanceId)) {
            return;
        }
        redisMessageBroker.setCloudStreamRoute(toolSessionId, gatewayInstanceId, cloudStreamRouteTtl());
    }

    private void removeCloudStreamRoute(String toolSessionId) {
        if (!hasText(toolSessionId) || redisMessageBroker == null || !hasText(gatewayInstanceId)) {
            return;
        }
        redisMessageBroker.removeCloudStreamRoute(toolSessionId, gatewayInstanceId);
    }

    private Duration cloudStreamRouteTtl() {
        long maxDurationSeconds = Math.max(0, timeoutProperties.getMaxDurationSeconds());
        return Duration.ofSeconds(Math.max(60, maxDurationSeconds + 60));
    }

    private boolean relayAbortToOwningGateway(GatewayMessage invokeMessage, String toolSessionId) {
        if (!hasText(toolSessionId)
                || redisMessageBroker == null
                || objectMapper == null
                || !hasText(gatewayInstanceId)) {
            return false;
        }
        String ownerGatewayId = redisMessageBroker.getCloudStreamRoute(toolSessionId);
        if (!hasText(ownerGatewayId) || gatewayInstanceId.equals(ownerGatewayId)) {
            return false;
        }
        try {
            String originalJson = objectMapper.writeValueAsString(invokeMessage);
            String relayJson = objectMapper.writeValueAsString(RelayMessage.toCloudControl(originalJson));
            redisMessageBroker.publishToGwRelay(ownerGatewayId, relayJson);
            log.info("[CLOUD_AGENT] abort_session relayed to active stream owner: ownerGw={}, toolSessionId={}, traceId={}",
                    ownerGatewayId, toolSessionId, invokeMessage.getTraceId());
            return true;
        } catch (Exception e) {
            log.warn("[CLOUD_AGENT] abort_session owner relay failed: ownerGw={}, toolSessionId={}, traceId={}, error={}",
                    ownerGatewayId, toolSessionId, invokeMessage.getTraceId(), e.getMessage());
            return false;
        }
    }

    private ActiveCloudConnection registerActiveConnection(String toolSessionId, String welinkSessionId,
            CloudConnectionHandle handle) {
        List<String> keys = activeConnectionKeys(toolSessionId, welinkSessionId);
        if (keys.isEmpty()) {
            return null;
        }
        ActiveCloudConnection activeConnection = new ActiveCloudConnection(handle, keys);
        for (String key : keys) {
            ActiveCloudConnection previous = activeStreamingConnections.put(key, activeConnection);
            if (previous != null && previous != activeConnection) {
                previous.handle().cancel();
                removeActiveConnection(previous);
            }
        }
        return activeConnection;
    }

    private void removeActiveConnection(ActiveCloudConnection activeConnection) {
        if (activeConnection == null) {
            return;
        }
        for (String key : activeConnection.keys()) {
            activeStreamingConnections.remove(key, activeConnection);
        }
    }

    private static List<String> activeConnectionKeys(String toolSessionId, String welinkSessionId) {
        List<String> keys = new ArrayList<>(2);
        if (toolSessionId != null && !toolSessionId.isBlank()) {
            keys.add("tool:" + toolSessionId);
        }
        if (welinkSessionId != null && !welinkSessionId.isBlank()) {
            keys.add("welink:" + welinkSessionId);
        }
        return List.copyOf(keys);
    }

    private static boolean isSseProtocol(String protocol) {
        return "sse".equalsIgnoreCase(protocol);
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

    private static String rawCloudPayload(GatewayMessage event) {
        if (event == null) {
            return null;
        }
        JsonNode eventNode = event.getEvent();
        if (eventNode != null && !eventNode.isMissingNode() && !eventNode.isNull()) {
            return eventNode.toString();
        }
        JsonNode payload = event.getPayload();
        if (payload != null && !payload.isMissingNode() && !payload.isNull()) {
            return payload.toString();
        }
        return String.valueOf(event);
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

    private static String textAt(JsonNode node, String fieldName) {
        if (node == null || !node.isObject()) {
            return null;
        }
        JsonNode value = node.path(fieldName);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        String text = value.asText(null);
        return (text == null || text.isBlank()) ? null : text;
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

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record RemoteRoute(String channelAddress,
                               String channelType,
                               String appId,
                               String cloudProfile,
                               String authType) {
    }

    private record ActiveCloudConnection(CloudConnectionHandle handle, List<String> keys) {
    }
}
