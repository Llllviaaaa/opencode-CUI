package com.opencode.cui.skill.service.delivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.opencode.cui.skill.model.SkillSession;
import com.opencode.cui.skill.model.StreamMessage;
import com.opencode.cui.skill.service.RedisMessageBroker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class MiniappDeliveryStrategy implements OutboundDeliveryStrategy {

    private final RedisMessageBroker redisMessageBroker;
    private final ObjectMapper objectMapper;

    public MiniappDeliveryStrategy(RedisMessageBroker redisMessageBroker, ObjectMapper objectMapper) {
        this.redisMessageBroker = redisMessageBroker;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(SkillSession session) {
        return session == null || session.isMiniappDomain();
    }

    @Override
    public int order() { return 1; }

    @Override
    public void deliver(SkillSession session, String sessionId, String userId, StreamMessage msg) {
        if (userId == null || userId.isBlank()) {
            log.warn("Cannot deliver miniapp message without userId: sessionId={}", sessionId);
            return;
        }
        try {
            ObjectNode envelope = objectMapper.createObjectNode();
            envelope.put("sessionId", sessionId);
            envelope.put("userId", userId);
            envelope.set("message", objectMapper.valueToTree(msg));
            redisMessageBroker.publishToUser(userId, objectMapper.writeValueAsString(envelope));
            log.info("[DELIVERY] Miniapp: sessionId={}, type={}, userId={}",
                    sessionId, msg != null ? msg.getType() : null, userId);
        } catch (Exception e) {
            log.error("Failed to deliver miniapp message: sessionId={}, error={}", sessionId, e.getMessage());
        }
    }
}
