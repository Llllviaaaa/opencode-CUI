package com.opencode.cui.skill.service.delivery;

import com.opencode.cui.skill.model.SkillSession;
import com.opencode.cui.skill.model.StreamMessage;

/**
 * 出站投递策略接口。
 * 按 order 优先级匹配，第一个 supports 的策略执行 deliver。
 */
public interface OutboundDeliveryStrategy {

    boolean supports(SkillSession session);
    int order();
    void deliver(SkillSession session, String sessionId, String userId, StreamMessage msg);
}
