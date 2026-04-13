package com.opencode.cui.skill.service.delivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.opencode.cui.skill.model.SkillSession;
import com.opencode.cui.skill.model.StreamMessage;
import com.opencode.cui.skill.service.RedisMessageBroker;
import com.opencode.cui.skill.ws.ExternalStreamHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class ExternalWsDeliveryStrategy implements OutboundDeliveryStrategy {

    private final ExternalStreamHandler externalStreamHandler;
    private final RedisMessageBroker redisMessageBroker;
    private final ObjectMapper objectMapper;

    public ExternalWsDeliveryStrategy(ExternalStreamHandler externalStreamHandler,
                                       RedisMessageBroker redisMessageBroker,
                                       ObjectMapper objectMapper) {
        this.externalStreamHandler = externalStreamHandler;
        this.redisMessageBroker = redisMessageBroker;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(SkillSession session) {
        if (session == null || session.isMiniappDomain()) return false;
        return externalStreamHandler.hasActiveConnections(session.getBusinessSessionDomain());
    }

    @Override
    public int order() { return 2; }

    @Override
    public void deliver(SkillSession session, String sessionId, String userId, StreamMessage msg) {
        String domain = session.getBusinessSessionDomain();
        try {
            ObjectNode envelope = objectMapper.createObjectNode();
            envelope.put("sessionId", sessionId);
            if (userId != null) envelope.put("userId", userId);
            envelope.put("domain", domain);
            envelope.set("message", objectMapper.valueToTree(msg));
            redisMessageBroker.publishToChannel("stream:" + domain, objectMapper.writeValueAsString(envelope));
            log.info("[DELIVERY] ExternalWs: sessionId={}, type={}, domain={}",
                    sessionId, msg != null ? msg.getType() : null, domain);
        } catch (Exception e) {
            log.error("Failed to deliver external WS message: sessionId={}, domain={}, error={}",
                    sessionId, domain, e.getMessage());
        }
    }
}
