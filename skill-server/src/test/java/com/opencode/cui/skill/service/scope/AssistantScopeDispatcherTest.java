package com.opencode.cui.skill.service.scope;

import com.opencode.cui.skill.model.AssistantInfo;
import com.opencode.cui.skill.service.BusinessWhitelistService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AssistantScopeDispatcher")
class AssistantScopeDispatcherTest {

    @Mock
    private AssistantScopeStrategy personalStrategy;

    @Mock
    private AssistantScopeStrategy businessStrategy;

    @Mock
    private BusinessWhitelistService whitelistService;

    private AssistantScopeDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        when(personalStrategy.getScope()).thenReturn("personal");
        when(businessStrategy.getScope()).thenReturn("business");
        dispatcher = new AssistantScopeDispatcher(List.of(personalStrategy, businessStrategy), whitelistService);
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
        AssistantScopeStrategy result = dispatcher.getStrategy((String) null);
        assertSame(personalStrategy, result);
    }

    @Test
    @DisplayName("getStrategy(\"unknown\") falls back to PersonalScopeStrategy")
    void getStrategy_unknown_fallsBackToPersonal() {
        AssistantScopeStrategy result = dispatcher.getStrategy("unknown");
        assertSame(personalStrategy, result);
    }

    @Test
    @DisplayName("getStrategy(null info) → personalStrategy")
    void getStrategy_nullInfo_returnsPersonal() {
        AssistantScopeStrategy result = dispatcher.getStrategy((AssistantInfo) null);
        assertSame(personalStrategy, result);
        verifyNoInteractions(whitelistService);
    }

    @Test
    @DisplayName("getStrategy(personal info) → personal, whitelistService not called")
    void getStrategy_personalInfo_returnsPersonal_skipsWhitelist() {
        AssistantInfo info = new AssistantInfo();
        info.setAssistantScope("personal");

        AssistantScopeStrategy result = dispatcher.getStrategy(info);

        assertSame(personalStrategy, result);
        verifyNoInteractions(whitelistService);
    }

    @Test
    @DisplayName("getStrategy(business info, allowsCloud=true) → business")
    void getStrategy_businessAllowed_returnsBusiness() {
        AssistantInfo info = new AssistantInfo();
        info.setAssistantScope("business");
        info.setBusinessTag("tag-foo");
        when(whitelistService.allowsCloud("tag-foo")).thenReturn(true);

        AssistantScopeStrategy result = dispatcher.getStrategy(info);

        assertSame(businessStrategy, result);
    }

    @Test
    @DisplayName("getStrategy(business info, allowsCloud=false) → personal")
    void getStrategy_businessDenied_returnsPersonal() {
        AssistantInfo info = new AssistantInfo();
        info.setAssistantScope("business");
        info.setBusinessTag("tag-foo");
        when(whitelistService.allowsCloud("tag-foo")).thenReturn(false);

        AssistantScopeStrategy result = dispatcher.getStrategy(info);

        assertSame(personalStrategy, result);
    }
}
