package com.opencode.cui.skill.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.model.DefaultAssistantRule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * DefaultAssistantRuleService 单元测试。
 * 覆盖 PRD AC §A 的 7 个 case + 缓存语义验证（通过 mock SysConfigService 反映）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DefaultAssistantRuleService")
class DefaultAssistantRuleServiceTest {

    private static final String CONFIG_TYPE = "default_assistant_rule";

    @Mock
    private SysConfigService sysConfigService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private DefaultAssistantRuleService service;

    @BeforeEach
    void setUp() {
        service = new DefaultAssistantRuleService(sysConfigService, objectMapper);
    }

    @Test
    @DisplayName("lookup(\"helpdesk\",\"direct\") + 合法 JSON → Optional.of(rule)，字段一致")
    void lookup_validJson_returnsRule() {
        String json = "{\"ak\":\"AK_V\",\"assistantAccount\":\"ACC_V\",\"businessTag\":\"assistant_square\"}";
        when(sysConfigService.getValue(CONFIG_TYPE, "helpdesk:direct")).thenReturn(json);

        Optional<DefaultAssistantRule> result = service.lookup("helpdesk", "direct");

        assertTrue(result.isPresent());
        DefaultAssistantRule rule = result.get();
        assertEquals("AK_V", rule.ak());
        assertEquals("ACC_V", rule.assistantAccount());
        assertEquals("assistant_square", rule.businessTag());
    }

    @Test
    @DisplayName("lookup(null, type) → Optional.empty()，不打 SysConfigService")
    void lookup_nullDomain_returnsEmpty_skipSysConfig() {
        Optional<DefaultAssistantRule> result = service.lookup(null, "direct");

        assertTrue(result.isEmpty());
        verifyNoInteractions(sysConfigService);
    }

    @Test
    @DisplayName("lookup(domain, null) → Optional.empty()，不打 SysConfigService")
    void lookup_nullType_returnsEmpty_skipSysConfig() {
        Optional<DefaultAssistantRule> result = service.lookup("helpdesk", null);

        assertTrue(result.isEmpty());
        verifyNoInteractions(sysConfigService);
    }

    @Test
    @DisplayName("lookup(\"\", \"\") → Optional.empty()，不打 SysConfigService")
    void lookup_blankInputs_returnsEmpty_skipSysConfig() {
        assertTrue(service.lookup("", "direct").isEmpty());
        assertTrue(service.lookup("helpdesk", "  ").isEmpty());
        verifyNoInteractions(sysConfigService);
    }

    @Test
    @DisplayName("lookup: SysConfigService 返 null → Optional.empty()")
    void lookup_sysConfigReturnsNull_returnsEmpty() {
        when(sysConfigService.getValue(CONFIG_TYPE, "no:match")).thenReturn(null);

        Optional<DefaultAssistantRule> result = service.lookup("no", "match");

        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("lookup: 非法 JSON → Optional.empty() + log warn（覆盖 JsonProcessingException 分支）")
    void lookup_invalidJson_returnsEmpty() {
        when(sysConfigService.getValue(CONFIG_TYPE, "bad:json"))
                .thenReturn("not-a-valid-json{");

        Optional<DefaultAssistantRule> result = service.lookup("bad", "json");

        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("lookup: JSON 字段缺失（businessTag 空） → Optional.empty()（IAE 被 catch）")
    void lookup_jsonMissingField_returnsEmpty() {
        // ak/assistantAccount 有值，businessTag 为空字符串 → record 构造器抛 IAE
        String json = "{\"ak\":\"AK_V\",\"assistantAccount\":\"ACC_V\",\"businessTag\":\"\"}";
        when(sysConfigService.getValue(CONFIG_TYPE, "miss:field")).thenReturn(json);

        Optional<DefaultAssistantRule> result = service.lookup("miss", "field");

        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("lookup: JSON 字段全 null（ak/assistantAccount/businessTag 全空） → Optional.empty()")
    void lookup_jsonAllFieldsNull_returnsEmpty() {
        String json = "{}";
        when(sysConfigService.getValue(CONFIG_TYPE, "all:null")).thenReturn(json);

        Optional<DefaultAssistantRule> result = service.lookup("all", "null");

        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("lookup 缓存语义：两次同 key 调用 → SysConfigService.getValue 被两次调用（缓存在 SysConfigService 内部）")
    void lookup_repeatedCalls_alwaysHitSysConfigService() {
        // SysConfigService 内部已经有 Redis 缓存（本 service 只是薄壳）；
        // 验证 RuleService 不在外层再加一层缓存：每次都委托 SysConfigService
        String json = "{\"ak\":\"AK_V\",\"assistantAccount\":\"ACC_V\",\"businessTag\":\"assistant_square\"}";
        when(sysConfigService.getValue(CONFIG_TYPE, "helpdesk:direct")).thenReturn(json);

        service.lookup("helpdesk", "direct");
        service.lookup("helpdesk", "direct");

        verify(sysConfigService, times(2)).getValue(CONFIG_TYPE, "helpdesk:direct");
    }

    @Test
    @DisplayName("lookup 缓存失效后取新值：mock 第二次返新 JSON → lookup 拿新值")
    void lookup_sysConfigEvictedThenUpdated_returnsNewValue() {
        String oldJson = "{\"ak\":\"AK_OLD\",\"assistantAccount\":\"ACC_OLD\",\"businessTag\":\"old\"}";
        String newJson = "{\"ak\":\"AK_NEW\",\"assistantAccount\":\"ACC_NEW\",\"businessTag\":\"new\"}";
        // 模拟"运维 update 后 evict 缓存，下次 lookup 拿到新值"
        when(sysConfigService.getValue(CONFIG_TYPE, "helpdesk:direct"))
                .thenReturn(oldJson)
                .thenReturn(newJson);

        DefaultAssistantRule firstRule = service.lookup("helpdesk", "direct").orElseThrow();
        assertEquals("AK_OLD", firstRule.ak());

        DefaultAssistantRule secondRule = service.lookup("helpdesk", "direct").orElseThrow();
        assertEquals("AK_NEW", secondRule.ak());
        assertEquals("new", secondRule.businessTag());
    }

    @Test
    @DisplayName("lookup: config_key 拼接采用 \"{domain}:{type}\" 字面（精确拼接，无 trim / 大小写归一）")
    void lookup_keyFormat() {
        when(sysConfigService.getValue(CONFIG_TYPE, "im:direct")).thenReturn(null);
        when(sysConfigService.getValue(CONFIG_TYPE, "Helpdesk:Direct")).thenReturn(null);

        service.lookup("im", "direct");
        service.lookup("Helpdesk", "Direct"); // 大小写敏感字面拼接

        // 验证 key 完全是 "{domain}:{domainType}" 字面 —— 不做 trim/大小写归一
        verify(sysConfigService).getValue(CONFIG_TYPE, "im:direct");
        verify(sysConfigService).getValue(CONFIG_TYPE, "Helpdesk:Direct");
        verifyNoMoreInteractions(sysConfigService);
    }
}
