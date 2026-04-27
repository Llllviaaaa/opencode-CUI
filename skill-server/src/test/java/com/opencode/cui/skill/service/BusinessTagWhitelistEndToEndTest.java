package com.opencode.cui.skill.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.config.SysConfigProperties;
import com.opencode.cui.skill.model.AssistantInfo;
import com.opencode.cui.skill.model.SysConfig;
import com.opencode.cui.skill.repository.SysConfigMapper;
import com.opencode.cui.skill.service.scope.AssistantScopeDispatcher;
import com.opencode.cui.skill.service.scope.AssistantScopeStrategy;
import com.opencode.cui.skill.service.scope.BusinessScopeStrategy;
import com.opencode.cui.skill.service.scope.PersonalScopeStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 端到端覆盖：SysConfig 真实数据流 → BusinessWhitelistService.allowsCloud →
 * AssistantScopeDispatcher.getStrategy(AssistantInfo) → 选对 strategy
 *
 * <p>使用真实的 AssistantScopeDispatcher + BusinessWhitelistService 实例，
 * 仅 mock 底层 IO 依赖（SysConfigMapper / StringRedisTemplate），
 * 符合项目既有纯 Mockito 测试风格（无需真实 MySQL/Redis 环境）。
 *
 * <p>矩阵：总开关 0/1 × 空白名单 / 命中 / 不命中 / null tag
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("端到端: BusinessTag 白名单 × AssistantScopeDispatcher 矩阵")
class BusinessTagWhitelistEndToEndTest {

    // ---- 底层 IO mock ----
    @Mock private SysConfigMapper sysConfigMapper;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;
    @Mock private SysConfigProperties properties;

    // ---- 真实策略 mock（仅需 getScope() 返回正确 scope 即可） ----
    @Mock private PersonalScopeStrategy personalStrategy;
    @Mock private BusinessScopeStrategy businessStrategy;

    /** 被测对象（全真实实例，无 mock） */
    private AssistantScopeDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        // 策略标识
        when(personalStrategy.getScope()).thenReturn("personal");
        when(businessStrategy.getScope()).thenReturn("business");

        // Redis 缓存默认 miss（每个 case 按需 stub）
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(valueOperations.get(anyString())).thenReturn(null);

        // 白名单缓存写入不抛异常
        lenient().when(properties.getCacheTtlMinutes()).thenReturn(5L);

        // 构造真实服务链
        SysConfigService sysConfigService =
                new SysConfigService(sysConfigMapper, redisTemplate, properties);
        BusinessWhitelistService whitelistService =
                new BusinessWhitelistService(sysConfigService, sysConfigMapper, redisTemplate,
                        properties, new ObjectMapper());
        dispatcher = new AssistantScopeDispatcher(
                List.of(personalStrategy, businessStrategy), whitelistService);
    }

    // -------- 工具方法 --------

    /** 模拟总开关值（cloud_route / business_whitelist_enabled） */
    private void stubSwitch(String value) {
        SysConfig sw = new SysConfig();
        sw.setConfigType("cloud_route");
        sw.setConfigKey("business_whitelist_enabled");
        sw.setConfigValue(value);
        sw.setStatus(1);
        when(sysConfigMapper.findByTypeAndKey("cloud_route", "business_whitelist_enabled"))
                .thenReturn(sw);
    }

    /** 模拟白名单 DB 数据 */
    private void stubWhitelist(List<String> activeTags) {
        List<SysConfig> rows = activeTags.stream().map(tag -> {
            SysConfig c = new SysConfig();
            c.setConfigType("business_cloud_whitelist");
            c.setConfigKey(tag);
            c.setConfigValue("1");
            c.setStatus(1);
            return c;
        }).toList();
        when(sysConfigMapper.findByType("business_cloud_whitelist")).thenReturn(rows);
    }

    private AssistantInfo businessInfo(String tag) {
        AssistantInfo info = new AssistantInfo();
        info.setAssistantScope("business");
        info.setBusinessTag(tag);
        return info;
    }

    // -------- 矩阵测试 --------

    @Test
    @DisplayName("switch=0 → all business → cloud (regardless of whitelist)")
    void switchOff_allBusinessCloud() {
        stubSwitch("0");

        AssistantScopeStrategy s = dispatcher.getStrategy(businessInfo("any-tag"));

        assertEquals("business", s.getScope());
        // 开关关闭时不应查白名单
        verify(sysConfigMapper, never()).findByType(anyString());
    }

    @Test
    @DisplayName("switch=1 + empty whitelist → all business → cloud (fail-open)")
    void switchOn_emptyWhitelist_cloud() {
        stubSwitch("1");
        when(sysConfigMapper.findByType("business_cloud_whitelist"))
                .thenReturn(Collections.emptyList());

        AssistantScopeStrategy s = dispatcher.getStrategy(businessInfo("tag-foo"));

        assertEquals("business", s.getScope());
    }

    @Test
    @DisplayName("switch=1 + tag in whitelist → cloud")
    void switchOn_tagInWhitelist_cloud() {
        stubSwitch("1");
        stubWhitelist(List.of("tag-foo", "tag-bar"));

        AssistantScopeStrategy s = dispatcher.getStrategy(businessInfo("tag-foo"));

        assertEquals("business", s.getScope());
    }

    @Test
    @DisplayName("switch=1 + tag NOT in whitelist → personal (downgrade)")
    void switchOn_tagNotInWhitelist_personal() {
        stubSwitch("1");
        stubWhitelist(List.of("tag-foo"));

        AssistantScopeStrategy s = dispatcher.getStrategy(businessInfo("tag-other"));

        assertEquals("personal", s.getScope());
    }

    @Test
    @DisplayName("switch=1 + null businessTag → personal")
    void switchOn_nullTag_personal() {
        stubSwitch("1");

        AssistantScopeStrategy s = dispatcher.getStrategy(businessInfo(null));

        assertEquals("personal", s.getScope());
        // tag 为 null 时不应查白名单表
        verify(sysConfigMapper, never()).findByType(anyString());
    }

    @Test
    @DisplayName("switch=0 + null businessTag → cloud (do NOT downgrade)")
    void switchOff_nullTag_cloud() {
        stubSwitch("0");

        AssistantScopeStrategy s = dispatcher.getStrategy(businessInfo(null));

        assertEquals("business", s.getScope());
    }
}
