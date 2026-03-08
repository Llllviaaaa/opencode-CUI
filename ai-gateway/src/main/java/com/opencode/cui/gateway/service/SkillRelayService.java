package com.opencode.cui.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.gateway.model.GatewayMessage;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.time.Duration;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
public class SkillRelayService {

    private static final String TOOL_DONE = "tool_done";

    private final RedisMessageBroker redisMessageBroker;
    private final ObjectMapper objectMapper;

    private final String instanceId;
    private final Duration ownerTtl;
    private final Duration sessionRouteTtl;

    private final Map<String, WebSocketSession> skillSessions = new ConcurrentHashMap<>();
    private final Map<String, String> sessionBindings = new ConcurrentHashMap<>();
    private final AtomicReference<String> defaultLinkId = new AtomicReference<>();
    private final AtomicBoolean relaySubscribed = new AtomicBoolean(false);

    public SkillRelayService(RedisMessageBroker redisMessageBroker,
            ObjectMapper objectMapper,
            @Value("${gateway.instance-id:${HOSTNAME:gateway-local}}") String instanceId,
            @Value("${gateway.skill-relay.owner-ttl-seconds:30}") long ownerTtlSeconds,
            @Value("${gateway.skill-relay.session-route-ttl-seconds:1800}") long sessionRouteTtlSeconds) {
        this.redisMessageBroker = redisMessageBroker;
        this.objectMapper = objectMapper;
        this.instanceId = instanceId;
        this.ownerTtl = Duration.ofSeconds(ownerTtlSeconds);
        this.sessionRouteTtl = Duration.ofSeconds(sessionRouteTtlSeconds);
    }

    public void registerSkillSession(WebSocketSession session) {
        String linkId = session.getId();
        skillSessions.put(linkId, session);
        defaultLinkId.compareAndSet(null, linkId);

        ensureRelaySubscription();
        refreshOwnerState();

        log.info("Registered skill link: instanceId={}, linkId={}, activeLinks={}",
                instanceId, linkId, getActiveSkillConnectionCount());
    }

    public void removeSkillSession(WebSocketSession session) {
        String linkId = session.getId();
        skillSessions.remove(linkId);
        sessionBindings.entrySet().removeIf(entry -> linkId.equals(entry.getValue()));

        if (linkId.equals(defaultLinkId.get())) {
            defaultLinkId.set(selectAnyOpenLinkId());
        }

        if (skillSessions.isEmpty()) {
            clearOwnerState();
        } else {
            refreshOwnerState();
        }

        log.info("Removed skill link: instanceId={}, linkId={}, activeLinks={}",
                instanceId, linkId, getActiveSkillConnectionCount());
    }

    public void handleInvokeFromSkill(WebSocketSession session, GatewayMessage message) {
        if (message.getSessionId() != null && !message.getSessionId().isBlank()) {
            bindSession(message.getSessionId(), session.getId());
            touchSessionOwner(message.getSessionId(), instanceId);
        }

        if (message.getAgentId() == null || message.getAgentId().isBlank()) {
            log.warn("Invoke from skill missing agentId: linkId={}, action={}",
                    session.getId(), message.getAction());
            return;
        }

        redisMessageBroker.publishToAgent(message.getAgentId(), message);
        log.debug("Forwarded invoke from skill to agent: linkId={}, agentId={}, action={}",
                session.getId(), message.getAgentId(), message.getAction());
    }

    public boolean relayToSkill(GatewayMessage message) {
        String sessionId = normalize(message.getSessionId());
        if (sessionId != null) {
            if (sendToBoundLink(sessionId, message)) {
                touchSessionOwner(sessionId, instanceId);
                cleanupLocalBindingIfCompleted(message);
                return true;
            }

            String ownerId = resolveSessionOwner(sessionId);
            if (ownerId == null) {
                ownerId = selectOwner(sessionId);
                if (ownerId == null) {
                    log.warn("No active skill owner available for sessionId={}, type={}",
                            sessionId, message.getType());
                    return false;
                }
                touchSessionOwner(sessionId, ownerId);
            }

            if (instanceId.equals(ownerId)) {
                boolean sent = sendViaDefaultLink(message, sessionId);
                if (sent) {
                    touchSessionOwner(sessionId, instanceId);
                    cleanupLocalBindingIfCompleted(message);
                }
                return sent;
            }

            redisMessageBroker.publishToRelay(ownerId, message);
            return true;
        }

        if (sendViaDefaultLink(message, null)) {
            return true;
        }

        String ownerId = selectOwner(normalize(message.getAgentId()) != null
                ? message.getAgentId()
                : message.getType());
        if (ownerId == null) {
            log.warn("No active skill owner available for non-session message: type={}, agentId={}",
                    message.getType(), message.getAgentId());
            return false;
        }

        if (instanceId.equals(ownerId)) {
            return sendViaDefaultLink(message, null);
        }

        redisMessageBroker.publishToRelay(ownerId, message);
        return true;
    }

    public void handleRelayedMessage(GatewayMessage message) {
        String sessionId = normalize(message.getSessionId());

        if (sessionId != null && sendToBoundLink(sessionId, message)) {
            touchSessionOwner(sessionId, instanceId);
            cleanupLocalBindingIfCompleted(message);
            return;
        }

        if (!sendViaDefaultLink(message, sessionId)) {
            log.warn("Relay target gateway has no active skill link: instanceId={}, type={}, sessionId={}",
                    instanceId, message.getType(), sessionId);
            return;
        }

        if (sessionId != null) {
            touchSessionOwner(sessionId, instanceId);
            cleanupLocalBindingIfCompleted(message);
        }
    }

    public int getActiveSkillConnectionCount() {
        return (int) skillSessions.values().stream()
                .filter(WebSocketSession::isOpen)
                .count();
    }

    @Scheduled(fixedDelayString = "#{T(java.time.Duration).ofSeconds(${gateway.skill-relay.owner-heartbeat-interval-seconds:10}).toMillis()}")
    public void refreshOwnerHeartbeat() {
        if (!skillSessions.isEmpty()) {
            refreshOwnerState();
        }
    }

    @PreDestroy
    public void destroy() {
        clearOwnerState();
    }

    private boolean sendToBoundLink(String sessionId, GatewayMessage message) {
        String linkId = sessionBindings.get(sessionId);
        if (linkId == null) {
            return false;
        }

        WebSocketSession session = skillSessions.get(linkId);
        if (session == null || !session.isOpen()) {
            sessionBindings.remove(sessionId, linkId);
            return false;
        }

        boolean sent = sendToSession(session, message);
        if (!sent) {
            sessionBindings.remove(sessionId, linkId);
        }
        return sent;
    }

    private boolean sendViaDefaultLink(GatewayMessage message, String sessionIdToBind) {
        WebSocketSession session = resolveDefaultSession();
        if (session == null) {
            return false;
        }

        boolean sent = sendToSession(session, message);
        if (sent && sessionIdToBind != null) {
            bindSession(sessionIdToBind, session.getId());
        }
        return sent;
    }

    private boolean sendToSession(WebSocketSession session, GatewayMessage message) {
        try {
            String json = objectMapper.writeValueAsString(message);
            synchronized (session) {
                session.sendMessage(new TextMessage(json));
            }
            log.debug("Sent to skill link: instanceId={}, linkId={}, type={}, sessionId={}",
                    instanceId, session.getId(), message.getType(), message.getSessionId());
            return true;
        } catch (IOException e) {
            log.error("Failed to send to skill link: instanceId={}, linkId={}, type={}",
                    instanceId, session.getId(), message.getType(), e);
            return false;
        }
    }

    private void bindSession(String sessionId, String linkId) {
        sessionBindings.put(sessionId, linkId);
    }

    private void cleanupLocalBindingIfCompleted(GatewayMessage message) {
        if (TOOL_DONE.equals(message.getType())) {
            String sessionId = normalize(message.getSessionId());
            if (sessionId != null) {
                sessionBindings.remove(sessionId);
            }
        }
    }

    private void touchSessionOwner(String sessionId, String ownerId) {
        redisMessageBroker.setSessionOwner(sessionId, ownerId, sessionRouteTtl);
    }

    private String resolveSessionOwner(String sessionId) {
        String ownerId = normalize(redisMessageBroker.getSessionOwner(sessionId));
        if (ownerId == null) {
            return null;
        }
        if (!redisMessageBroker.hasActiveSkillOwner(ownerId)) {
            redisMessageBroker.clearSessionOwner(sessionId);
            return null;
        }
        return ownerId;
    }

    private String selectOwner(String key) {
        Set<String> owners = redisMessageBroker.getActiveSkillOwners();
        if (owners.isEmpty()) {
            return null;
        }

        return owners.stream()
                .max(Comparator.comparingLong(ownerId -> rendezvousScore(key, ownerId)))
                .orElse(null);
    }

    private long rendezvousScore(String key, String ownerId) {
        String stableKey = normalize(key) != null ? key : "default";
        return Integer.toUnsignedLong((stableKey + "|" + ownerId).hashCode());
    }

    private WebSocketSession resolveDefaultSession() {
        String preferredLinkId = defaultLinkId.get();
        if (preferredLinkId != null) {
            WebSocketSession preferredSession = skillSessions.get(preferredLinkId);
            if (preferredSession != null && preferredSession.isOpen()) {
                return preferredSession;
            }
        }

        String replacement = selectAnyOpenLinkId();
        defaultLinkId.set(replacement);
        return replacement != null ? skillSessions.get(replacement) : null;
    }

    private String selectAnyOpenLinkId() {
        return skillSessions.entrySet().stream()
                .filter(entry -> entry.getValue() != null && entry.getValue().isOpen())
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);
    }

    private void ensureRelaySubscription() {
        if (relaySubscribed.compareAndSet(false, true)) {
            redisMessageBroker.subscribeToRelay(instanceId, this::handleRelayedMessage);
        }
    }

    private void refreshOwnerState() {
        redisMessageBroker.refreshSkillOwner(instanceId, ownerTtl);
    }

    private void clearOwnerState() {
        redisMessageBroker.removeSkillOwner(instanceId);
        if (relaySubscribed.compareAndSet(true, false)) {
            redisMessageBroker.unsubscribeFromRelay(instanceId);
        }
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value;
    }
}
