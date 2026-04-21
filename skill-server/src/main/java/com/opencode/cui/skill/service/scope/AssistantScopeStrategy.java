package com.opencode.cui.skill.service.scope;

import com.fasterxml.jackson.databind.JsonNode;
import com.opencode.cui.skill.model.AssistantInfo;
import com.opencode.cui.skill.model.InvokeCommand;
import com.opencode.cui.skill.model.StreamMessage;

/**
 * 助手作用域策略接口。
 * 按 assistantScope（"personal" / "business"）分别实现 invoke 构建、事件翻译等行为差异。
 */
public interface AssistantScopeStrategy {

    /**
     * 策略对应的 scope 标识。
     *
     * @return "business" 或 "personal"
     */
    String getScope();

    /**
     * 构建发往 Gateway 的 invoke 消息体（JSON 字符串）。
     *
     * @param command 调用指令
     * @param info    助手配置信息
     * @return 序列化后的 JSON 字符串，构建失败返回 null
     */
    String buildInvoke(InvokeCommand command, AssistantInfo info);

    /**
     * 生成 toolSessionId。
     * <ul>
     *   <li>personal: 返回 null（由 Agent 返回 session_created 时绑定）</li>
     *   <li>business: 返回 "cloud-" + 唯一标识</li>
     * </ul>
     *
     * @return toolSessionId 或 null
     */
    String generateToolSessionId();

    /**
     * 是否需要等待 session_created 回调来绑定 toolSessionId。
     */
    boolean requiresSessionCreatedCallback();

    /**
     * 是否需要 Agent 在线检查。
     */
    boolean requiresOnlineCheck();

    /**
     * 将上行事件翻译为前端 StreamMessage。
     *
     * @param event     原始事件 JSON
     * @param sessionId 会话 ID
     * @return 翻译后的 StreamMessage，不可处理时返回 null
     */
    StreamMessage translateEvent(JsonNode event, String sessionId);
}
