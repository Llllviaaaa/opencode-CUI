package com.opencode.cui.skill.telemetry.chat;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * AOP 切面：{@code MessagePersistenceService.finalizeActiveAssistantTurn(Long sessionId)} 返回后，
 * 发布 {@link ChatReplyTelemetryEvent}。
 *
 * <p>仅在 {@code telemetry.welink.enabled=true} 时装载（与 reporter 同步生效；
 * disabled 时切面不存在，零运行时开销）。
 *
 * <p>异常隔离：顶层 try-catch，绝不让 publish 抛回业务线程。
 */
@Slf4j
@Aspect
@Component
@ConditionalOnProperty(name = "telemetry.welink.enabled", havingValue = "true")
public class ChatReplyAspect {

    private final ApplicationEventPublisher publisher;

    public ChatReplyAspect(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    @AfterReturning(
            value = "execution(* com.opencode.cui.skill.service.MessagePersistenceService.finalizeActiveAssistantTurn(..)) && args(sessionId)",
            argNames = "sessionId")
    public void afterFinalize(Long sessionId) {
        try {
            if (sessionId == null) {
                return;
            }
            publisher.publishEvent(new ChatReplyTelemetryEvent(sessionId));
        } catch (Throwable t) {
            log.warn("[WelinkTelemetry] ChatReplyAspect publish failed: sessionId={}, error={}",
                    sessionId, t.getMessage());
        }
    }
}
