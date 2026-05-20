package com.opencode.cui.skill.telemetry.chat;

/**
 * 助手回复 chat 对话事件（{@code skill_chat_response}）。
 *
 * <p>Publish 时机：AOP {@code @AfterReturning} 切 {@code MessagePersistenceService.finalizeActiveAssistantTurn}。
 *
 * <p>Listener 内通过 {@code SkillSessionRepository.findById(sessionId)} + {@code AssistantInfoService}
 * 反查 5 个业务字段（domain/type/businessSessionId/assistantAccount/businessTag）。
 */
public record ChatReplyTelemetryEvent(Long sessionId) {
}
