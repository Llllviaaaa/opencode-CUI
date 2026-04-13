package com.opencode.cui.skill.service.delivery;

import com.opencode.cui.skill.model.SkillSession;
import com.opencode.cui.skill.model.StreamMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

@Slf4j
@Component
public class OutboundDeliveryDispatcher {

    private final List<OutboundDeliveryStrategy> strategies;

    public OutboundDeliveryDispatcher(List<OutboundDeliveryStrategy> strategies) {
        this.strategies = strategies.stream()
                .sorted(Comparator.comparingInt(OutboundDeliveryStrategy::order))
                .toList();
        log.info("OutboundDeliveryDispatcher initialized with {} strategies: {}",
                this.strategies.size(),
                this.strategies.stream()
                        .map(s -> s.getClass().getSimpleName() + "(order=" + s.order() + ")")
                        .toList());
    }

    public void deliver(SkillSession session, String sessionId, String userId, StreamMessage msg) {
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
}
