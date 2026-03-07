package com.opencode.cui.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.gateway.model.GatewayMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class EventRelayService {

    private final Map<String, WebSocketSession> agentSessions = new ConcurrentHashMap<>();

    private final ObjectMapper objectMapper;
    private final RedisMessageBroker redisMessageBroker;
    private final SkillRelayService skillRelayService;

    public EventRelayService(ObjectMapper objectMapper,
            RedisMessageBroker redisMessageBroker,
            SkillRelayService skillRelayService) {
        this.objectMapper = objectMapper;
        this.redisMessageBroker = redisMessageBroker;
        this.skillRelayService = skillRelayService;
    }

    public void registerAgentSession(String agentId, WebSocketSession session) {
        WebSocketSession old = agentSessions.put(agentId, session);
        if (old != null && old.isOpen()) {
            try {
                old.close();
                log.info("Closed old WebSocket session for agentId={}", agentId);
            } catch (IOException e) {
                log.warn("Error closing old session for agentId={}", agentId, e);
            }
        }

        redisMessageBroker.subscribeToAgent(agentId, message -> sendToLocalAgent(agentId, message));
        log.info("Registered agent session: agentId={}, sessionId={}", agentId, session.getId());
    }

    public void removeAgentSession(String agentId) {
        WebSocketSession session = agentSessions.remove(agentId);
        if (session != null && session.isOpen()) {
            try {
                session.close();
            } catch (IOException e) {
                log.warn("Error closing session during removal for agentId={}", agentId, e);
            }
        }

        redisMessageBroker.unsubscribeFromAgent(agentId);
        log.debug("Removed agent session: agentId={}", agentId);
    }

    public boolean hasAgentSession(String agentId) {
        WebSocketSession session = agentSessions.get(agentId);
        return session != null && session.isOpen();
    }

    public void relayToSkillServer(String agentId, GatewayMessage message) {
        GatewayMessage forwarded = message.withAgentId(agentId);

        log.debug("Relaying to skill: agentId={}, type={}, sessionId={}, toolSessionId={}",
                agentId, message.getType(), forwarded.getSessionId(), forwarded.getToolSessionId());

        if (forwarded.hasEnvelope()) {
            var env = forwarded.getEnvelope();
            log.debug("Relaying enveloped message: agentId={}, type={}, messageId={}, seq={}, source={}",
                    agentId, message.getType(), env.getMessageId(), env.getSequenceNumber(), env.getSource());
        }

        try {
            boolean routed = skillRelayService.relayToSkill(forwarded);
            if (!routed) {
                log.warn("Failed to route message to skill: agentId={}, type={}, sessionId={}",
                        agentId, message.getType(), forwarded.getSessionId());
            }
        } catch (Exception e) {
            log.error("Failed to relay to skill: agentId={}, type={}",
                    agentId, message.getType(), e);
        }
    }

    public void relayToAgent(String agentId, GatewayMessage message) {
        redisMessageBroker.publishToAgent(agentId, message);
        log.debug("Published to agent channel: agentId={}, type={}", agentId, message.getType());
    }

    private void sendToLocalAgent(String agentId, GatewayMessage message) {
        WebSocketSession session = agentSessions.get(agentId);
        if (session == null || !session.isOpen()) {
            log.debug("Agent not connected to this instance: agentId={}, type={}",
                    agentId, message.getType());
            return;
        }

        try {
            String json = objectMapper.writeValueAsString(message);
            synchronized (session) {
                session.sendMessage(new TextMessage(json));
            }
            log.debug("Sent to local agent: agentId={}, type={}, seq={}",
                    agentId, message.getType(), message.getSequenceNumber());
        } catch (IOException e) {
            log.error("Failed to send to local agent: agentId={}, type={}",
                    agentId, message.getType(), e);
        }
    }

    public int getActiveSessionCount() {
        return (int) agentSessions.values().stream()
                .filter(WebSocketSession::isOpen)
                .count();
    }
}
