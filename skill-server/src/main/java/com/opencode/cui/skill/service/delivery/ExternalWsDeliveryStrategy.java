package com.opencode.cui.skill.service.delivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.logging.MdcConstants;
import com.opencode.cui.skill.logging.MdcHelper;
import com.opencode.cui.skill.model.SkillSession;
import com.opencode.cui.skill.model.StreamMessage;
import com.opencode.cui.skill.service.ExternalWsRegistry;
import com.opencode.cui.skill.service.RedisMessageBroker;
import com.opencode.cui.skill.ws.ExternalStreamHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class ExternalWsDeliveryStrategy implements OutboundDeliveryStrategy {

    private final ExternalStreamHandler externalStreamHandler;
    private final ExternalWsRegistry wsRegistry;
    private final RedisMessageBroker redisMessageBroker;
    private final ObjectMapper objectMapper;

    public ExternalWsDeliveryStrategy(ExternalStreamHandler externalStreamHandler,
                                       ExternalWsRegistry wsRegistry,
                                       RedisMessageBroker redisMessageBroker,
                                       ObjectMapper objectMapper) {
        this.externalStreamHandler = externalStreamHandler;
        this.wsRegistry = wsRegistry;
        this.redisMessageBroker = redisMessageBroker;
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
            MdcHelper.ensureTraceId();
            if (sessionId != null) {
                msg.setSeq(redisMessageBroker.nextStreamSeq(sessionId));
                msg.setSessionId(sessionId);
                if (msg.getWelinkSessionId() == null || msg.getWelinkSessionId().isBlank()) {
                    msg.setWelinkSessionId(sessionId);
                }
            }
            String json = objectMapper.writeValueAsString(msg);

            // L1: local external WebSocket.
            if (externalStreamHandler.pushToOne(domain, json)) {
                log.debug("[DELIVERY] ExternalWs-L1: sessionId={}, type={}, domain={}",
                        sessionId, msg.getType(), domain);
                return;
            }

            // L2: remote skill-server relay. Redis PUBLISH subscriber count is diagnostic only.
            List<String> targetInstances = wsRegistry.findInstancesWithConnection(domain);
            if (!targetInstances.isEmpty()) {
                String relayPayload = objectMapper.writeValueAsString(
                        buildRelayEnvelope(domain, json, sessionId, userId));
                for (String targetInstance : targetInstances) {
                    if (redisMessageBroker.publishToExternalRelayBestEffort(targetInstance, relayPayload)) {
                        log.info("[DELIVERY] ExternalWs-L2: relay publish accepted: " +
                                        "sessionId={}, type={}, domain={}, target={}",
                                sessionId, msg.getType(), domain, targetInstance);
                        return;
                    }
                    log.warn("[DELIVERY] ExternalWs-L2: relay publish failed, trying next: " +
                            "sessionId={}, type={}, domain={}, target={}",
                            sessionId, msg.getType(), domain, targetInstance);
                }
                log.warn("[DELIVERY] ExternalWs-L2: all relay candidates failed: " +
                        "sessionId={}, type={}, domain={}, candidates={}",
                        sessionId, msg.getType(), domain, targetInstances.size());
                return;
            }

            log.warn("[DELIVERY] ExternalWs: no WS connections, skipped: " +
                    "sessionId={}, domain={}, type={}", sessionId, domain, msg.getType());
        } catch (Exception e) {
            log.error("Failed to deliver external WS message: sessionId={}, domain={}, error={}",
                    sessionId, domain, e.getMessage());
        }
    }

    private Map<String, Object> buildRelayEnvelope(String domain, String payload, String sessionId, String userId) {
        Map<String, String> mdc = MdcHelper.snapshot();
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("domain", domain);
        envelope.put("payload", payload);
        putIfNotBlank(envelope, "traceId", mdc.get(MdcConstants.TRACE_ID));
        putIfNotBlank(envelope, "welinkSessionId", firstNonBlank(mdc.get(MdcConstants.SESSION_ID), sessionId));
        putIfNotBlank(envelope, "ak", mdc.get(MdcConstants.AK));
        putIfNotBlank(envelope, "userId", firstNonBlank(mdc.get(MdcConstants.USER_ID), userId));
        return envelope;
    }

    private void putIfNotBlank(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }

    private String firstNonBlank(String primary, String fallback) {
        if (primary != null && !primary.isBlank()) {
            return primary;
        }
        return fallback;
    }
}
