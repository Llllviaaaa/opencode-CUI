package com.opencode.cui.skill.service.scope;

import com.fasterxml.jackson.databind.JsonNode;
import com.opencode.cui.skill.model.AssistantInfo;
import com.opencode.cui.skill.model.InvokeCommand;
import com.opencode.cui.skill.model.StreamMessage;
import com.opencode.cui.skill.service.CloudEventTranslator;
import com.opencode.cui.skill.service.OpenCodeEventTranslator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 个人助理策略。
 * <ul>
 *   <li>invoke 通过 AI-Gateway WebSocket 协议发送</li>
 *   <li>toolSessionId 由 Agent 返回 session_created 回调时绑定</li>
 *   <li>需要 Agent 在线检查</li>
 *   <li>事件翻译：按 event 顶层 `protocol` 字段分派
 *       <ul>
 *         <li>缺失 / `opencode`（不分大小写）→ {@link OpenCodeEventTranslator}</li>
 *         <li>`cloud`（不分大小写）→ {@link CloudEventTranslator}</li>
 *         <li>空串或其它非空未知值 → WARN + fallback 到 {@link OpenCodeEventTranslator}</li>
 *       </ul>
 *   </li>
 * </ul>
 */
@Slf4j
@Component
public class PersonalScopeStrategy implements AssistantScopeStrategy {

    private final OpenCodeEventTranslator openCodeEventTranslator;
    private final CloudEventTranslator cloudEventTranslator;

    public PersonalScopeStrategy(OpenCodeEventTranslator openCodeEventTranslator,
                                 CloudEventTranslator cloudEventTranslator) {
        this.openCodeEventTranslator = openCodeEventTranslator;
        this.cloudEventTranslator = cloudEventTranslator;
    }

    @Override
    public String getScope() {
        return "personal";
    }

    /**
     * 个人助手的 invoke 构建。
     * 当前返回 null 作为占位——实际发送逻辑仍在 GatewayRelayService 中。
     */
    @Override
    public String buildInvoke(InvokeCommand command, AssistantInfo info) {
        log.debug("PersonalScopeStrategy.buildInvoke: placeholder, ak={}, action={}",
                command.ak(), command.action());
        return null;
    }

    /**
     * 个人助手不预生成 toolSessionId，由 Agent session_created 回调时绑定。
     */
    @Override
    public String generateToolSessionId() {
        return null;
    }

    @Override
    public boolean requiresSessionCreatedCallback() {
        return true;
    }

    @Override
    public boolean requiresOnlineCheck() {
        return true;
    }

    @Override
    public StreamMessage translateEvent(JsonNode event, String sessionId) {
        if (event == null) {
            return null;
        }
        // Task 1：保守实现，non-null 统一走 opencode；cloud/warn 分支在后续 Task 引入。
        return openCodeEventTranslator.translate(event);
    }
}
