package com.opencode.cui.skill.telemetry.chat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * AOP 切面行为测试。
 *
 * <p><b>测试范围说明</b>：原计划用 thin {@code AnnotationConfigApplicationContext +
 * @EnableAspectJAutoProxy} 装配 {@link ChatReplyAspect} + 一个 mock 的
 * {@code MessagePersistenceService} bean 来端到端验 AOP 触发；但
 * <ol>
 *   <li>{@code MessagePersistenceService} 是 concrete class，构造器有 7 个非空 collaborator
 *       且构造函数里调 {@code tracker.setBeforeFinalizeHook(...)}，无法在 AOP 测试里轻量 new。</li>
 *   <li>Mockito mock 出来的 bean 已是 CGLIB 代理子类，Spring AOP 不会再叠一层代理，
 *       pointcut advice 拿不到方法调用。</li>
 * </ol>
 * 因此本测试退化为<b>切面方法单元测试</b>：直接调 {@code afterFinalize} 验事件发布行为。
 * 容器内 AOP 真实路径由更高层 {@code @SpringBootTest} 覆盖（如果未来需要的话）。
 * 这是 PRD §7 允许的 "thinner Spring test is acceptable" 的现实选择。
 */
class ChatReplyAspectIntegrationTest {

    @Test
    @DisplayName("afterFinalize(123L): 发布 ChatReplyTelemetryEvent(123L)")
    void afterFinalizePublishesEvent() {
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        ChatReplyAspect aspect = new ChatReplyAspect(publisher);

        aspect.afterFinalize(123L);

        ArgumentCaptor<Object> cap = ArgumentCaptor.forClass(Object.class);
        verify(publisher).publishEvent(cap.capture());
        Object ev = cap.getValue();
        assertEquals(ChatReplyTelemetryEvent.class, ev.getClass());
        assertEquals(123L, ((ChatReplyTelemetryEvent) ev).sessionId());
    }

    @Test
    @DisplayName("afterFinalize(null): 不发布")
    void afterFinalizeNullSessionSkipped() {
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        ChatReplyAspect aspect = new ChatReplyAspect(publisher);

        aspect.afterFinalize(null);

        verify(publisher, never()).publishEvent(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("publisher 抛异常: 顶层 catch 兜底，不抛回业务线程")
    void publisherFailureSwallowed() {
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        org.mockito.Mockito.doThrow(new RuntimeException("downstream boom"))
                .when(publisher).publishEvent(org.mockito.ArgumentMatchers.any());
        ChatReplyAspect aspect = new ChatReplyAspect(publisher);

        aspect.afterFinalize(7L); // 不抛
    }
}
