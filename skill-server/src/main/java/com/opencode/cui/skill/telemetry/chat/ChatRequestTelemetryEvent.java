package com.opencode.cui.skill.telemetry.chat;

import com.opencode.cui.skill.model.SkillSession;

/**
 * 用户发起 chat 对话事件（{@code skill_chat_request}）。
 *
 * <p>Publish 时机：{@code SkillMessageController.routeToGateway()} 末尾，
 * 即将调用 gateway 前（早返回路径 agent_offline / no_toolSessionId / no_agent 不上报）。
 *
 * <p>{@link #businessTag} 来源：{@code AssistantInfo.businessTag}（routeToGateway
 * 局部变量 scopeInfo 已包含）；可能为 null（personal scope）。
 */
public record ChatRequestTelemetryEvent(
        SkillSession session,
        String senderUserAccount,
        String businessTag,
        String robotId) {
}
