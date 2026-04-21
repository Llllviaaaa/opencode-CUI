package com.opencode.cui.skill.service.delivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.model.SkillSession;
import com.opencode.cui.skill.model.StreamMessage;
import com.opencode.cui.skill.service.ExternalWsRegistry;
import com.opencode.cui.skill.service.ImOutboundService;
import com.opencode.cui.skill.service.RedisMessageBroker;
import com.opencode.cui.skill.ws.ExternalStreamHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
public class ExternalWsDeliveryStrategy implements OutboundDeliveryStrategy {

    private final ExternalStreamHandler externalStreamHandler;
    private final ExternalWsRegistry wsRegistry;
    private final RedisMessageBroker redisMessageBroker;
    private final ImOutboundService imOutboundService;
    private final ObjectMapper objectMapper;

    public ExternalWsDeliveryStrategy(ExternalStreamHandler externalStreamHandler,
                                       ExternalWsRegistry wsRegistry,
                                       RedisMessageBroker redisMessageBroker,
                                       ImOutboundService imOutboundService,
                                       ObjectMapper objectMapper) {
        this.externalStreamHandler = externalStreamHandler;
        this.wsRegistry = wsRegistry;
        this.redisMessageBroker = redisMessageBroker;
        this.imOutboundService = imOutboundService;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(SkillSession session) {
        if (session == null || session.isMiniappDomain()) return false;
        return !session.isImDomain();
    }

    @Override
    public int order() { return 2; }

    @Override
    public void deliver(SkillSession session, String sessionId, String userId, StreamMessage msg) {
        String domain = session.getBusinessSessionDomain();
        try {
            if (sessionId != null) {
                msg.setSeq(redisMessageBroker.nextStreamSeq(sessionId));
            }
            String json = objectMapper.writeValueAsString(msg);

            // L1: 本地投递
            if (externalStreamHandler.pushToOne(domain, json)) {
                log.debug("[DELIVERY] ExternalWs-L1: sessionId={}, type={}, domain={}",
                        sessionId, msg.getType(), domain);
                return;
            }

            // L2: 跨 SS relay
            String targetInstance = wsRegistry.findInstanceWithConnection(domain);
            if (targetInstance != null) {
                String relayPayload = objectMapper.writeValueAsString(
                        Map.of("domain", domain, "payload", json));
                redisMessageBroker.publishToChannel(
                        "ss:external-relay:" + targetInstance, relayPayload);
                log.info("[DELIVERY] ExternalWs-L2: sessionId={}, type={}, domain={}, target={}",
                        sessionId, msg.getType(), domain, targetInstance);
                return;
            }

            // L3: 降级
            if (session.isImDomain()) {
                String fallbackText = buildFallbackText(msg);
                if (fallbackText != null && !fallbackText.isBlank()) {
                    imOutboundService.sendTextToIm(
                            session.getBusinessSessionType(),
                            session.getBusinessSessionId(),
                            fallbackText,
                            session.getAssistantAccount());
                    log.warn("[DELIVERY] ExternalWs-L3: no WS connections, fell back to ImRest: " +
                            "sessionId={}, domain={}", sessionId, domain);
                }
            } else {
                log.warn("[DELIVERY] ExternalWs-L3: no WS connections, discarding: " +
                        "sessionId={}, domain={}, type={}", sessionId, domain, msg.getType());
            }
        } catch (Exception e) {
            log.error("Failed to deliver external WS message: sessionId={}, domain={}, error={}",
                    sessionId, domain, e.getMessage());
        }
    }

    private String buildFallbackText(StreamMessage msg) {
        if (msg == null) return null;
        return switch (msg.getType()) {
            case StreamMessage.Types.TEXT_DONE -> msg.getContent();
            case StreamMessage.Types.ERROR, StreamMessage.Types.SESSION_ERROR -> msg.getError();
            default -> null;
        };
    }
}
