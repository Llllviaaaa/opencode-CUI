package com.opencode.cui.skill.service.delivery;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.opencode.cui.skill.model.SkillSession;
import com.opencode.cui.skill.model.StreamMessage;
import com.opencode.cui.skill.service.MessagePersistenceService;
import com.opencode.cui.skill.service.ProtocolUtils;
import com.opencode.cui.skill.service.RedisMessageBroker;
import com.opencode.cui.skill.service.SkillSessionService;
import com.opencode.cui.skill.service.StreamBufferService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Set;

/**
 * StreamMessage 出站的唯一权威入口。
 *
 * <p>封装三种既存出站语义：
 * <ul>
 *   <li>{@link #emitToSession}：按 session domain 路由到 miniapp/IM/ExternalWs 策略</li>
 *   <li>{@link #emitToClient}：强制推给 miniapp 前端（绕过 domain 路由）</li>
 *   <li>{@link #emitToClientWithBuffer}：前端强推 + buffer（断线重连回放）</li>
 * </ul>
 *
 * <p>所有方法内部统一完成 enrich（填充 sessionId/welinkSessionId/emittedAt + 分配 messageContext），
 * 调用方不再需要手动 enrich。
 */
@Slf4j
@Component
public class StreamMessageEmitter {

    /** 不需要 emittedAt 时间戳的消息类型集合（从 GatewayMessageRouter 迁移而来） */
    private static final Set<String> EMITTED_AT_EXCLUDED_TYPES = Set.of(
            StreamMessage.Types.PERMISSION_REPLY,
            StreamMessage.Types.AGENT_ONLINE,
            StreamMessage.Types.AGENT_OFFLINE,
            StreamMessage.Types.ERROR);

    private final OutboundDeliveryDispatcher dispatcher;
    private final RedisMessageBroker redisBroker;
    private final StreamBufferService bufferService;
    private final MessagePersistenceService persistenceService;
    private final SkillSessionService sessionService;
    private final ObjectMapper objectMapper;

    public StreamMessageEmitter(OutboundDeliveryDispatcher dispatcher,
                                 RedisMessageBroker redisBroker,
                                 StreamBufferService bufferService,
                                 MessagePersistenceService persistenceService,
                                 SkillSessionService sessionService,
                                 ObjectMapper objectMapper) {
        this.dispatcher = dispatcher;
        this.redisBroker = redisBroker;
        this.bufferService = bufferService;
        this.persistenceService = persistenceService;
        this.sessionService = sessionService;
        this.objectMapper = objectMapper;
    }

    private void enrich(String sessionId, StreamMessage msg) {
        if (msg == null || sessionId == null) return;

        msg.setSessionId(sessionId);               // 内部字段，始终覆盖
        msg.setWelinkSessionId(sessionId);          // 协议字段，canonical overwrite

        if (!EMITTED_AT_EXCLUDED_TYPES.contains(msg.getType())
                && (msg.getEmittedAt() == null || msg.getEmittedAt().isBlank())) {
            msg.setEmittedAt(Instant.now().toString());
        }

        if (!"user".equals(ProtocolUtils.normalizeRole(msg.getRole()))) {
            Long numericId = ProtocolUtils.parseSessionId(sessionId);
            if (numericId != null) {
                persistenceService.prepareMessageContext(numericId, msg);
            }
        }
    }

    public void emitToSession(SkillSession session, String sessionId,
                              String userId, StreamMessage msg) {
        enrich(sessionId, msg);
        dispatcher.deliver(session, sessionId, userId, msg);
    }

    public void emitToClient(String sessionId, String userIdHint, StreamMessage msg) {
        throw new UnsupportedOperationException("not yet implemented");
    }

    public void emitToClientWithBuffer(String sessionId, StreamMessage msg) {
        throw new UnsupportedOperationException("not yet implemented");
    }
}
