package com.opencode.cui.skill.service.scope;

import com.opencode.cui.skill.model.AssistantInfo;
import com.opencode.cui.skill.model.DefaultAssistantRule;
import com.opencode.cui.skill.service.BusinessWhitelistService;
import com.opencode.cui.skill.service.DefaultAssistantRuleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.lenient;
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
    private DefaultAssistantScopeStrategy defaultStrategy;

    @Mock
    private BusinessWhitelistService whitelistService;

    @Mock
    private DefaultAssistantRuleService ruleService;

    private AssistantScopeDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        when(personalStrategy.getScope()).thenReturn("personal");
        when(businessStrategy.getScope()).thenReturn("business");
        lenient().when(defaultStrategy.getScope()).thenReturn(DefaultAssistantScopeStrategy.SCOPE);
        dispatcher = new AssistantScopeDispatcher(
                List.of(personalStrategy, businessStrategy, defaultStrategy),
                whitelistService,
                ruleService,
                defaultStrategy);
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

    // ============ 新 API: getStrategy(domain, domainType, info) ============

    @Test
    @DisplayName("getStrategy(domain,type,info) rule 命中 → 返 DefaultAssistantScopeStrategy（忽略 info）")
    void getStrategy_threeArg_ruleHit_returnsDefault() {
        DefaultAssistantRule rule = new DefaultAssistantRule("AK_V", "ACC_V", "assistant_square");
        when(ruleService.lookup("helpdesk", "direct")).thenReturn(Optional.of(rule));

        AssistantInfo info = new AssistantInfo();
        info.setAssistantScope("personal");

        AssistantScopeStrategy result = dispatcher.getStrategy("helpdesk", "direct", info);

        assertSame(defaultStrategy, result);
        // info 完全忽略 —— 不去走老 API 的 personal/business 分支
        verifyNoInteractions(whitelistService);
    }

    @Test
    @DisplayName("getStrategy(domain,type,null) rule 命中 → 返 DefaultAssistantScopeStrategy（virtual ak 上游不存在的场景）")
    void getStrategy_threeArg_nullInfo_ruleHit_returnsDefault() {
        DefaultAssistantRule rule = new DefaultAssistantRule("AK_V", "ACC_V", "assistant_square");
        when(ruleService.lookup("helpdesk", "direct")).thenReturn(Optional.of(rule));

        AssistantScopeStrategy result = dispatcher.getStrategy("helpdesk", "direct", null);

        assertSame(defaultStrategy, result);
        verifyNoInteractions(whitelistService);
    }

    @Test
    @DisplayName("getStrategy(null,null,info) → rule 返 empty → 委托老 API → 走 info.scope")
    void getStrategy_threeArg_nullDomain_delegatesToOldApi() {
        when(ruleService.lookup(null, null)).thenReturn(Optional.empty());

        AssistantInfo info = new AssistantInfo();
        info.setAssistantScope("personal");

        AssistantScopeStrategy result = dispatcher.getStrategy(null, null, info);

        assertSame(personalStrategy, result);
        verifyNoInteractions(whitelistService);
    }

    @Test
    @DisplayName("getStrategy(\"nomatch\",\"type\",info) → rule miss → 委托老 API")
    void getStrategy_threeArg_ruleMiss_delegatesToOldApi() {
        when(ruleService.lookup("nomatch", "type")).thenReturn(Optional.empty());

        AssistantInfo info = new AssistantInfo();
        info.setAssistantScope("business");
        info.setBusinessTag("tag-foo");
        when(whitelistService.allowsCloud("tag-foo")).thenReturn(true);

        AssistantScopeStrategy result = dispatcher.getStrategy("nomatch", "type", info);

        assertSame(businessStrategy, result);
    }

    @Test
    @DisplayName("getStrategy(domain,type,null) rule miss + info null → 委托老 API → personal 兜底")
    void getStrategy_threeArg_ruleMissAndNullInfo_returnsPersonal() {
        when(ruleService.lookup("nomatch", "type")).thenReturn(Optional.empty());

        AssistantScopeStrategy result = dispatcher.getStrategy("nomatch", "type", null);

        assertSame(personalStrategy, result);
        verifyNoInteractions(whitelistService);
    }
}
