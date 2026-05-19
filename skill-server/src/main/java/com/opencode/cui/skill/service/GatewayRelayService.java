package com.opencode.cui.skill.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.opencode.cui.skill.logging.MdcHelper;
import com.opencode.cui.skill.model.AssistantInfo;
import com.opencode.cui.skill.model.InvokeCommand;
import com.opencode.cui.skill.model.SkillSession;
import com.opencode.cui.skill.model.StreamMessage;
import com.opencode.cui.skill.service.scope.AssistantScopeDispatcher;
import com.opencode.cui.skill.service.scope.AssistantScopeStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * AI Gateway 通信服务。
 * 负责 Skill Server 与 AI Gateway 之间的双向消息传输。
 *
 * 下行（Skill → Gateway）：通过 WebSocket 发送 invoke 指令
 * 上行（Gateway → Skill）：接收并路由到 GatewayMessageRouter
 * 多实例广播：通过 Redis Pub/Sub 实现跨实例消息广播
 */
@Slf4j
@Service
public class GatewayRelayService {

    public static final String SOURCE = "skill-server"; // 消息来源标识

    /** Gateway WebSocket 通信接口（由 WebSocket Handler 注入） */
    public interface GatewayRelayTarget {
        /** 发送消息到 Gateway（round-robin 选择可用连接） */
        boolean sendToGateway(String message);

        /** 是否有活跃的 WebSocket 连接 */
        boolean hasActiveConnection();
    }

    private final ObjectMapper objectMapper;
    private final GatewayMessageRouter messageRouter;
    private final SessionRebuildService rebuildService;
    private final RedisMessageBroker redisMessageBroker;
    private final AssistantIdResolverService assistantIdResolverService;
    private final AssistantInfoService assistantInfoService;
    private final AssistantScopeDispatcher scopeDispatcher;
    private final com.opencode.cui.skill.service.delivery.StreamMessageEmitter emitter;
    private volatile GatewayRelayTarget gatewayRelayTarget;

    public GatewayRelayService(ObjectMapper objectMapper,
            GatewayMessageRouter messageRouter,
            SessionRebuildService rebuildService,
            RedisMessageBroker redisMessageBroker,
            AssistantIdResolverService assistantIdResolverService,
            AssistantInfoService assistantInfoService,
            AssistantScopeDispatcher scopeDispatcher,
            com.opencode.cui.skill.service.delivery.StreamMessageEmitter emitter) {
        this.objectMapper = objectMapper;
        this.messageRouter = messageRouter;
        this.rebuildService = rebuildService;
        this.redisMessageBroker = redisMessageBroker;
        this.assistantIdResolverService = assistantIdResolverService;
        this.assistantInfoService = assistantInfoService;
        this.scopeDispatcher = scopeDispatcher;
        this.emitter = emitter;

        // 向 MessageRouter 注入下行发送能力，避免循环依赖
        messageRouter.setDownstreamSender(this::sendInvokeToGateway);
        // 向 MessageRouter 注入路由响应发送能力（Task 2.10）
        messageRouter.setRouteResponseSender(new GatewayMessageRouter.RouteResponseSender() {
            @Override
            public boolean sendRouteConfirm(String toolSessionId, String welinkSessionId) {
                return GatewayRelayService.this.sendRouteConfirm(toolSessionId, welinkSessionId);
            }

            @Override
            public void sendRouteReject(String toolSessionId) {
                GatewayRelayService.this.sendRouteReject(toolSessionId);
            }
        });
    }

    public void setGatewayRelayTarget(GatewayRelayTarget gatewayRelayTarget) {
        this.gatewayRelayTarget = gatewayRelayTarget;
    }

    // ==================== 下行：Skill → Gateway ====================

    /**
     * 通过 WebSocket 发送 invoke 指令到 AI Gateway。
     * 
     * @param command 调用指令，包含 ak、userId、sessionId、action 和 payload
     */
    public void sendInvokeToGateway(InvokeCommand command) {
        String action = command.action();

        log.info("[ENTRY] GatewayRelayService.sendInvokeToGateway: ak={}, userId={}, sessionId={}, action={}",
                command.ak(), command.userId(), command.sessionId(), action);

        // 发送新消息时清除已完成标记，防止新一轮对话的 tool_event 被误拦截
        if (GatewayActions.CHAT.equals(action)) {
            messageRouter.clearCompletionMark(command.sessionId());
        }

        // 根据助手类型（scope）选择构建策略
        // PR3 收口（方案 B）：调 dispatcher 新 API getStrategy(domain, domainType, info)
        // strategy 选择全部收口到 dispatcher 一处；caller 不再自己 findByAk 反查。
        // 老 caller 不传 domain/domainType（命令字段为 null），dispatcher 内部 lookup(null, null)
        // 返 empty → 委托老 API getStrategy(info)，行为完全不变。
        String messageText;
        AssistantInfo info = assistantInfoService.getAssistantInfo(command.ak());
        AssistantScopeStrategy strategy = scopeDispatcher.getStrategy(
                command.domain(), command.domainType(), info);
        String scope = strategy.getScope();
        if ("business".equals(scope) || "default_assistant".equals(scope)) {
            messageText = strategy.buildInvoke(command, info);
            if (messageText == null) {
                log.warn("[SKIP] GatewayRelayService.sendInvokeToGateway: reason=strategy_build_null, ak={}, scope={}",
                        command.ak(), scope);
                return;
            }
        } else {
            // personal 策略（含白名单未命中降级 / 上游故障兜底）：保留本地 buildInvokeMessage
            messageText = buildInvokeMessage(command);
            if (messageText == null) {
                return;
            }
        }

        GatewayRelayTarget relayTarget = gatewayRelayTarget;
        if (relayTarget == null || !relayTarget.hasActiveConnection()) {
            log.warn("[SKIP] GatewayRelayService.sendInvokeToGateway: reason=no_connection, ak={}, action={}",
                    command.ak(), action);
            return;
        }

        boolean sent = relayTarget.sendToGateway(messageText);

        if (!sent) {
            log.warn("[ERROR] GatewayRelayService.sendInvokeToGateway: reason=send_failed, ak={}, action={}",
                    command.ak(), action);
            return;
        }

        log.info("[EXIT->GW] GatewayRelayService.sendInvokeToGateway: action={}, ak={}",
                action, command.ak());
    }

    /**
     * 构建发往 Gateway 的 invoke JSON 消息体。
     *
     * @return 序列化后的 JSON 字符串，构建失败返回 null
     */
    private String buildInvokeMessage(InvokeCommand command) {
        ObjectNode message = objectMapper.createObjectNode();
        message.put("type", "invoke");
        message.put("ak", command.ak());
        message.put("source", SOURCE);
        if (command.userId() != null && !command.userId().isBlank()) {
            message.put("userId", command.userId());
        }
        if (GatewayActions.CREATE_SESSION.equals(command.action())
                && command.sessionId() != null && !command.sessionId().isBlank()) {
            // 使用字符串传输 welinkSessionId，防止 JavaScript IEEE 754 大数精度丢失
            message.put("welinkSessionId", command.sessionId());
        }
        message.put("action", command.action());

        // 顶层 suppressReply 标志：仅在 chat 群聊 + channel 命中白名单时由调用方显式置 true，
        // 其它路径 command.suppressReply() 为 null，不写入 INVOKE 报文。
        if (Boolean.TRUE.equals(command.suppressReply())) {
            message.put("suppressReply", true);
        }

        // 注入 traceId：从 MDC 获取或自动生成，确保跨服务链路可追踪
        String traceId = MdcHelper.ensureTraceId();
        message.put("traceId", traceId);

        try {
            if (command.payload() != null) {
                JsonNode payloadNode = objectMapper.readTree(command.payload());
                message.set("payload", payloadNode);
            }
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse invoke payload as JSON, sending as string: {}", e.getMessage());
            message.put("payload", command.payload());
        }

        // 注入 assistantId：仅在 create_session 和 chat 时注入
        if (GatewayActions.CREATE_SESSION.equals(command.action())
                || GatewayActions.CHAT.equals(command.action())) {
            String assistantId = assistantIdResolverService.resolve(command.ak(), command.sessionId());
            if (assistantId != null) {
                ObjectNode targetPayload;
                JsonNode existingPayload = message.get("payload");
                if (existingPayload instanceof ObjectNode on) {
                    targetPayload = on;
                } else {
                    targetPayload = objectMapper.createObjectNode();
                    message.set("payload", targetPayload);
                }
                targetPayload.put("assistantId", assistantId);
            }
        }

        // PR2 platformExtParam：personal scope（buildInvokeMessage 是其唯一出站路径）也补
        // extParameters 信封，与 business / default_assistant 形态对齐。同时把 payload 顶层的
        // businessExtParam 搬到 extParameters.businessExtParam，跟 business wire 形态一致（P2a）。
        //
        // 幂等保护：如果 payload 已经有 extParameters（例如 retryPendingMessages 提前构造好），
        // 不要覆盖，避免双注入。
        JsonNode payloadAfterAssistantId = message.get("payload");
        if (payloadAfterAssistantId instanceof ObjectNode payloadObj
                && !payloadObj.has("extParameters")) {
            // 1) 把 payload 顶层 businessExtParam 摘出来（与 P2a 对齐：搬入 extParameters）
            JsonNode removedBusinessExt = payloadObj.remove("businessExtParam");

            // 2) 构造 extParameters 信封：businessExtParam（兜底 {}）+ platformExtParam（三字段）
            ObjectNode extParameters = objectMapper.createObjectNode();
            if (removedBusinessExt != null && !removedBusinessExt.isNull()) {
                extParameters.set("businessExtParam", removedBusinessExt);
            } else {
                extParameters.set("businessExtParam", objectMapper.createObjectNode());
            }
            extParameters.set("platformExtParam",
                    PlatformExtParamBuilder.build(objectMapper,
                            command.domain(),
                            command.domainType(),
                            command.businessSessionId()));
            payloadObj.set("extParameters", extParameters);
        }

        try {
            return objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize invoke message", e);
            return null;
        }
    }

    // ==================== 上行：Gateway → Skill ====================

    /**
     * 处理来自 AI Gateway 的上行消息。
     * 解析 JSON 后委派给 GatewayMessageRouter 进行路由分发。
     */
    public void handleGatewayMessage(String rawMessage) {
        long start = System.nanoTime();
        log.info("[ENTRY] GatewayRelayService.handleGatewayMessage: length={}",
                rawMessage != null ? rawMessage.length() : 0);
        JsonNode node;
        try {
            node = objectMapper.readTree(rawMessage);
        } catch (JsonProcessingException e) {
            log.error("[ERROR] GatewayRelayService.handleGatewayMessage: reason=parse_failed, length={}",
                    rawMessage != null ? rawMessage.length() : 0, e);
            return;
        }

        try {
            // 从 Gateway 消息提取关联字段到 MDC，实现跨服务链路追踪
            MdcHelper.fromJsonNode(node);
            MdcHelper.putScenario("ws-gateway-" + node.path("type").asText("unknown"));

            String type = node.path("type").asText("");
            String ak = node.path("ak").asText(null);
            String userId = node.path("userId").asText(null);
            if (ak == null || ak.isBlank()) {
                ak = node.path("agentId").asText(null);
                MdcHelper.putAk(ak); // 补充 fallback 的 ak 到 MDC
            }
            log.info("GatewayRelayService.handleGatewayMessage: dispatching type={}, ak={}, userId={}",
                    type, ak, userId);

            messageRouter.route(type, ak, userId, node);

            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            log.info("[EXIT] GatewayRelayService.handleGatewayMessage: type={}, ak={}, durationMs={}",
                    type, ak, elapsedMs);
        } finally {
            MdcHelper.clearAll();
        }
    }

    // ==================== 路由协议响应（Task 2.10） ====================

    /**
     * 向 AI Gateway 发送 route_confirm，确认 SS 已接受该 toolSessionId 的路由归属。
     *
     * @param toolSessionId  OpenCode 侧会话 ID
     * @param welinkSessionId SS 侧会话 ID（可为 null）
     * @return true 表示已成功投递到 GW；false 表示因序列化失败或无活跃连接而未投递。
     *         调用方（{@link GatewayMessageRouter#maybeSendRouteConfirm}）依赖该返回值实现 cache-after-success。
     */
    public boolean sendRouteConfirm(String toolSessionId, String welinkSessionId) {
        log.info("[ENTRY] GatewayRelayService.sendRouteConfirm: toolSessionId={}, welinkSessionId={}",
                toolSessionId, welinkSessionId);

        ObjectNode message = objectMapper.createObjectNode();
        message.put("type", "route_confirm");
        message.put("toolSessionId", toolSessionId);
        message.put("source", SOURCE);
        if (welinkSessionId != null && !welinkSessionId.isBlank()) {
            message.put("welinkSessionId", welinkSessionId);
        }

        String traceId = MdcHelper.ensureTraceId();
        message.put("traceId", traceId);

        String messageText;
        try {
            messageText = objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            log.error("[ERROR] GatewayRelayService.sendRouteConfirm: serialize_failed, toolSessionId={}", toolSessionId, e);
            return false;
        }

        GatewayRelayTarget relayTarget = gatewayRelayTarget;
        if (relayTarget == null || !relayTarget.hasActiveConnection()) {
            log.warn("[SKIP] GatewayRelayService.sendRouteConfirm: reason=no_connection, toolSessionId={}", toolSessionId);
            return false;
        }

        boolean sent = relayTarget.sendToGateway(messageText);
        log.info("[EXIT] GatewayRelayService.sendRouteConfirm: toolSessionId={}, sent={}", toolSessionId, sent);
        return sent;
    }

    /**
     * 向 AI Gateway 发送 route_reject，通知 GW 本 SS 无法处理该 toolSessionId 的消息。
     *
     * @param toolSessionId OpenCode 侧会话 ID
     */
    public void sendRouteReject(String toolSessionId) {
        log.info("[ENTRY] GatewayRelayService.sendRouteReject: toolSessionId={}", toolSessionId);

        ObjectNode message = objectMapper.createObjectNode();
        message.put("type", "route_reject");
        message.put("toolSessionId", toolSessionId);
        message.put("source", SOURCE);

        String traceId = MdcHelper.ensureTraceId();
        message.put("traceId", traceId);

        String messageText;
        try {
            messageText = objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            log.error("[ERROR] GatewayRelayService.sendRouteReject: serialize_failed, toolSessionId={}", toolSessionId, e);
            return;
        }

        GatewayRelayTarget relayTarget = gatewayRelayTarget;
        if (relayTarget == null || !relayTarget.hasActiveConnection()) {
            log.warn("[SKIP] GatewayRelayService.sendRouteReject: reason=no_connection, toolSessionId={}", toolSessionId);
            return;
        }

        boolean sent = relayTarget.sendToGateway(messageText);
        log.info("[EXIT] GatewayRelayService.sendRouteReject: toolSessionId={}, sent={}", toolSessionId, sent);
    }

    // ==================== 公共委派方法 ====================

    /**
     * 通过广播 + 缓冲区发布协议消息。
     * 用于控制器中的即时推送（如权限回复）。
     */
    public void publishProtocolMessage(String sessionId, StreamMessage msg) {
        messageRouter.publishProtocolMessage(sessionId, msg);
    }

    /**
     * 触发 toolSession 重建（老 String 入参重载，被 {@code SkillMessageController.routeToGateway} 等使用）。
     * 缓存待发消息 → 通知前端重试 → 发送 create_session 到 Gateway。
     *
     * <p>PR3 改造：内部委托给
     * {@code SessionRebuildService.rebuildToolSession(String, SkillSession, String, RebuildCallback)}
     * 老 String 重载, 该重载在 {@code SessionRebuildService} 内已自动 fallback +
     * WARN（{@code reason=rebuild_legacy_string_overload}），所以 caller 无感升级。
     */
    public void rebuildToolSession(String sessionId, SkillSession session, String pendingMessage) {
        log.info("Initiating toolSession rebuild: sessionId={}, ak={}, hasPendingMessage={}",
                sessionId, session != null ? session.getAk() : null, pendingMessage != null);
        rebuildService.rebuildToolSession(sessionId, session, pendingMessage,
                new SessionRebuildService.RebuildCallback() {
                    @Override
                    public void broadcast(String sid, String uid, StreamMessage msg) {
                        emitter.emitToClient(sid, uid, msg);
                    }

                    @Override
                    public void sendInvoke(InvokeCommand command) {
                        sendInvokeToGateway(command);
                    }
                });
    }

    /**
     * 触发 toolSession 重建（PR3 新签名，接收完整 {@link com.opencode.cui.skill.model.PendingChatRequest}）。
     *
     * <p>用于 {@link ImSessionManager} 个人助手分支 — 首次对话场景需要把 sender / assistantAccount /
     * imGroupId / businessExtParam 完整入 Redis pending list, 等 Gateway 回 session_created
     * 触发 retry 时由 {@code GatewayMessageRouter.retryPendingMessages} 重建完整 chat invoke payload。
     *
     * <p>内部直接调用 {@code SessionRebuildService.rebuildToolSession} 新签名,
     * 避开老 String 重载的 fallback + WARN 日志。
     *
     * @param pendingRequest 完整 {@link com.opencode.cui.skill.model.PendingChatRequest}（可为 null,
     *                       表示无 pending 消息只重建 session）
     */
    public void rebuildToolSession(String sessionId, SkillSession session,
            com.opencode.cui.skill.model.PendingChatRequest pendingRequest) {
        log.info("Initiating toolSession rebuild (PendingChatRequest API): sessionId={}, ak={}, hasPendingRequest={}",
                sessionId, session != null ? session.getAk() : null, pendingRequest != null);
        rebuildService.rebuildToolSession(sessionId, session, pendingRequest,
                new SessionRebuildService.RebuildCallback() {
                    @Override
                    public void broadcast(String sid, String uid, StreamMessage msg) {
                        emitter.emitToClient(sid, uid, msg);
                    }

                    @Override
                    public void sendInvoke(InvokeCommand command) {
                        sendInvokeToGateway(command);
                    }
                });
    }
}
