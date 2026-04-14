package com.opencode.cui.skill.service.delivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.model.SkillSession;
import com.opencode.cui.skill.model.StreamMessage;
import com.opencode.cui.skill.service.RedisMessageBroker;
import com.opencode.cui.skill.ws.ExternalStreamHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 通用外部 WS 出站投递策略。
 * 直接序列化 StreamMessage 推送，与 miniapp 前端收到的报文格式完全一致（无信封包装）。
 */
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
            // 分配传输序号（与 miniapp 一致）
            if (sessionId != null) {
                msg.setSeq(redisMessageBroker.nextStreamSeq(sessionId));
            }
            // 直接序列化 StreamMessage，与 miniapp 前端收到的格式一致
            String json = objectMapper.writeValueAsString(msg);
            redisMessageBroker.publishToChannel("stream:" + domain, json);
            log.debug("[DELIVERY] ExternalWs: sessionId={}, type={}, domain={}",
                    sessionId, msg != null ? msg.getType() : null, domain);
        } catch (Exception e) {
            log.error("Failed to deliver external WS message: sessionId={}, domain={}, error={}",
                    sessionId, domain, e.getMessage());
        }
    }
}
