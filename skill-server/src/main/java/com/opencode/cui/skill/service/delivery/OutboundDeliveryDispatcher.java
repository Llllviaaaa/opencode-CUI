package com.opencode.cui.skill.service.delivery;

import com.opencode.cui.skill.config.DeliveryProperties;
import com.opencode.cui.skill.model.SkillSession;
import com.opencode.cui.skill.model.StreamMessage;
import com.opencode.cui.skill.service.RedisMessageBroker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

@Slf4j
@Component
public class OutboundDeliveryDispatcher {

    private final List<OutboundDeliveryStrategy> strategies;
    private final DeliveryProperties deliveryProperties;
    private final RedisMessageBroker redisMessageBroker;

    public OutboundDeliveryDispatcher(List<OutboundDeliveryStrategy> strategies,
                                       DeliveryProperties deliveryProperties,
                                       RedisMessageBroker redisMessageBroker) {
        this.strategies = strategies.stream()
                .sorted(Comparator.comparingInt(OutboundDeliveryStrategy::order))
                .toList();
        this.deliveryProperties = deliveryProperties;
        this.redisMessageBroker = redisMessageBroker;
        log.info("OutboundDeliveryDispatcher initialized with {} strategies: {}",
                this.strategies.size(),
                this.strategies.stream()
                        .map(s -> s.getClass().getSimpleName() + "(order=" + s.order() + ")")
                        .toList());
    }

    public void deliver(SkillSession session, String sessionId, String userId, StreamMessage msg) {
        // MiniApp 优先判断
        if (session != null && session.isMiniappDomain()) {
            deliverByFirstMatch(session, sessionId, userId, msg);
            return;
        }

        // mode=rest: 走 first-match（ImRest 会匹配 IM domain）
        if (!deliveryProperties.isWsMode()) {
            deliverByFirstMatch(session, sessionId, userId, msg);
            return;
        }

        // mode=ws: 读取 invoke-source 标记
        String invokeSource = redisMessageBroker.getInvokeSource(sessionId);
        if (invokeSource != null) {
            // 续期 TTL
            redisMessageBroker.expireInvokeSource(sessionId,
                    deliveryProperties.getInvokeSourceTtlSeconds());
        }

        if ("IM".equalsIgnoreCase(invokeSource)) {
            deliverByType(ImRestDeliveryStrategy.class, session, sessionId, userId, msg);
            return;
        }

        if ("EXTERNAL".equalsIgnoreCase(invokeSource)) {
            deliverByType(ExternalWsDeliveryStrategy.class, session, sessionId, userId, msg);
            return;
        }

        // 无标记：走 first-match 兜底
        log.debug("No invoke-source marker, falling back to first-match: sessionId={}", sessionId);
        deliverByFirstMatch(session, sessionId, userId, msg);
    }

    private void deliverByFirstMatch(SkillSession session, String sessionId,
                                      String userId, StreamMessage msg) {
        for (OutboundDeliveryStrategy strategy : strategies) {
            if (strategy.supports(session)) {
                log.debug("Delivering via {}: sessionId={}, type={}",
                        strategy.getClass().getSimpleName(), sessionId,
                        msg != null ? msg.getType() : null);
                strategy.deliver(session, sessionId, userId, msg);
                return;
            }
        }
        log.warn("No delivery strategy matched: sessionId={}, domain={}",
                sessionId, session != null ? session.getBusinessSessionDomain() : "null");
    }

    private void deliverByType(Class<? extends OutboundDeliveryStrategy> type,
                                SkillSession session, String sessionId,
                                String userId, StreamMessage msg) {
        for (OutboundDeliveryStrategy strategy : strategies) {
            if (type.isInstance(strategy)) {
                log.debug("Delivering via {} (invoke-source routed): sessionId={}, type={}",
                        strategy.getClass().getSimpleName(), sessionId,
                        msg != null ? msg.getType() : null);
                strategy.deliver(session, sessionId, userId, msg);
                return;
            }
        }
        log.warn("Strategy {} not found: sessionId={}", type.getSimpleName(), sessionId);
    }
}
