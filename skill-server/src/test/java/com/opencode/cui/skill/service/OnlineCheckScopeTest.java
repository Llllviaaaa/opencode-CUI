package com.opencode.cui.skill.service;

import com.opencode.cui.skill.service.scope.AssistantScopeDispatcher;
import com.opencode.cui.skill.service.scope.AssistantScopeStrategy;
import com.opencode.cui.skill.service.scope.BusinessScopeStrategy;
import com.opencode.cui.skill.service.scope.PersonalScopeStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * 在线检查 scope 判断测试（S59-S61）。
 *
 * <p>验证 business scope 跳过在线检查，personal scope 执行在线检查，
 * 以及 AssistantScopeDispatcher 对 business AK 返回正确策略。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OnlineCheckScopeTest")
class OnlineCheckScopeTest {

    @Mock
    private CloudEventTranslator cloudEventTranslator;

    @Mock
    private com.opencode.cui.skill.service.cloud.CloudRequestBuilder cloudRequestBuilder;

    @Mock
    private OpenCodeEventTranslator openCodeEventTranslator;

    /**
     * S59: business scope -> requiresOnlineCheck = false
     */
    @Test
    @DisplayName("S59: business scope requiresOnlineCheck returns false")
    void businessScope_requiresOnlineCheck_returnsFalse() {
        BusinessScopeStrategy strategy = new BusinessScopeStrategy(
                cloudRequestBuilder, cloudEventTranslator, new com.fasterxml.jackson.databind.ObjectMapper());

        assertFalse(strategy.requiresOnlineCheck(),
                "business scope should NOT require online check");
    }

    /**
     * S60: personal scope -> requiresOnlineCheck = true
     */
    @Test
    @DisplayName("S60: personal scope requiresOnlineCheck returns true")
    void personalScope_requiresOnlineCheck_returnsTrue() {
        PersonalScopeStrategy strategy = new PersonalScopeStrategy(openCodeEventTranslator);

        assertTrue(strategy.requiresOnlineCheck(),
                "personal scope SHOULD require online check");
    }

    /**
     * S61: dispatcher 对 business AK 返回正确策略（requiresOnlineCheck=false）
     */
    @Test
    @DisplayName("S61: dispatcher returns business strategy for business scope with requiresOnlineCheck=false")
    void dispatcher_businessScope_returnsStrategyWithNoOnlineCheck() {
        // 构造 mock 策略
        AssistantScopeStrategy personalStrategy = new PersonalScopeStrategy(openCodeEventTranslator);
        BusinessScopeStrategy businessStrategy = new BusinessScopeStrategy(
                cloudRequestBuilder, cloudEventTranslator, new com.fasterxml.jackson.databind.ObjectMapper());

        AssistantScopeDispatcher dispatcher = new AssistantScopeDispatcher(
                List.of(personalStrategy, businessStrategy));

        AssistantScopeStrategy resolved = dispatcher.getStrategy("business");

        assertNotNull(resolved, "dispatcher should return a strategy for 'business'");
        assertEquals("business", resolved.getScope());
        assertFalse(resolved.requiresOnlineCheck(),
                "business strategy from dispatcher should NOT require online check");
    }
}
