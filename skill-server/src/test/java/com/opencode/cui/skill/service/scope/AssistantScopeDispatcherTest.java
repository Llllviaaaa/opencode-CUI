package com.opencode.cui.skill.service.scope;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AssistantScopeDispatcher")
class AssistantScopeDispatcherTest {

    @Mock
    private AssistantScopeStrategy personalStrategy;

    @Mock
    private AssistantScopeStrategy businessStrategy;

    private AssistantScopeDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        when(personalStrategy.getScope()).thenReturn("personal");
        when(businessStrategy.getScope()).thenReturn("business");
        dispatcher = new AssistantScopeDispatcher(List.of(personalStrategy, businessStrategy));
    }

    @Test
    @DisplayName("getStrategy(\"business\") returns BusinessScopeStrategy")
    void getStrategy_business_returnsBusinessStrategy() {
        AssistantScopeStrategy result = dispatcher.getStrategy("business");
        assertSame(businessStrategy, result);
    }

    @Test
    @DisplayName("getStrategy(\"personal\") returns PersonalScopeStrategy")
    void getStrategy_personal_returnsPersonalStrategy() {
        AssistantScopeStrategy result = dispatcher.getStrategy("personal");
        assertSame(personalStrategy, result);
    }

    @Test
    @DisplayName("getStrategy(null) falls back to PersonalScopeStrategy")
    void getStrategy_null_fallsBackToPersonal() {
        AssistantScopeStrategy result = dispatcher.getStrategy(null);
        assertSame(personalStrategy, result);
    }

    @Test
    @DisplayName("getStrategy(\"unknown\") falls back to PersonalScopeStrategy")
    void getStrategy_unknown_fallsBackToPersonal() {
        AssistantScopeStrategy result = dispatcher.getStrategy("unknown");
        assertSame(personalStrategy, result);
    }
}
