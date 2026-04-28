package com.opencode.cui.skill.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.config.SysConfigProperties;
import com.opencode.cui.skill.model.SysConfig;
import com.opencode.cui.skill.repository.SysConfigMapper;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BusinessWhitelistServiceTest {

    @Mock SysConfigService sysConfigService;
    @Mock SysConfigMapper sysConfigMapper;
    @Mock StringRedisTemplate redisTemplate;
    @Mock ValueOperations<String, String> valueOperations;
    @Mock SysConfigProperties properties;

    private BusinessWhitelistService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        service = new BusinessWhitelistService(
                sysConfigService, sysConfigMapper, redisTemplate, properties, objectMapper);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(properties.getCacheTtlMinutes()).thenReturn(5L);
    }

    private SysConfig row(String key, int status) {
        SysConfig c = new SysConfig();
        c.setConfigType("business_cloud_whitelist");
        c.setConfigKey(key);
        c.setConfigValue("1");
        c.setStatus(status);
        return c;
    }

    // ================== 总开关 = '0' 时永远 true（不触碰 tag） ==================

    @Test
    @DisplayName("switch=0 + tag=null → true (do not touch tag)")
    void switchOff_nullTag_true() {
        when(sysConfigService.getValue("cloud_route", "business_whitelist_enabled")).thenReturn("0");
        assertTrue(service.allowsCloud(null));
        verify(sysConfigMapper, never()).findByType(anyString());
    }

    @Test
    @DisplayName("switch=0 + tag=blank → true")
    void switchOff_blankTag_true() {
        when(sysConfigService.getValue("cloud_route", "business_whitelist_enabled")).thenReturn("0");
        assertTrue(service.allowsCloud(""));
        assertTrue(service.allowsCloud("   "));
    }

    @Test
    @DisplayName("switch=0 + tag=anything → true (always)")
    void switchOff_anyTag_true() {
        when(sysConfigService.getValue("cloud_route", "business_whitelist_enabled")).thenReturn("0");
        assertTrue(service.allowsCloud("tag-foo"));
        assertTrue(service.allowsCloud("not-in-whitelist"));
    }

    // ================== 总开关 = '1' 各分支 ==================

    @Test
    @DisplayName("switch=1 + tag=null → false + WARN")
    void switchOn_nullTag_false() {
        when(sysConfigService.getValue("cloud_route", "business_whitelist_enabled")).thenReturn("1");
        assertFalse(service.allowsCloud(null));
    }

    @Test
    @DisplayName("switch=1 + tag=blank → false + WARN")
    void switchOn_blankTag_false() {
        when(sysConfigService.getValue("cloud_route", "business_whitelist_enabled")).thenReturn("1");
        assertFalse(service.allowsCloud(""));
        assertFalse(service.allowsCloud("   "));
    }

    @Test
    @DisplayName("switch=1 + empty whitelist → true (fail-open)")
    void switchOn_emptyTable_true() {
        when(sysConfigService.getValue("cloud_route", "business_whitelist_enabled")).thenReturn("1");
        when(valueOperations.get("ss:config:set:business_cloud_whitelist")).thenReturn(null);
        when(sysConfigMapper.findByType("business_cloud_whitelist")).thenReturn(Collections.emptyList());
        assertTrue(service.allowsCloud("tag-foo"));
    }

    @Test
    @DisplayName("switch=1 + tag in whitelist with status=1 → true")
    void switchOn_tagHit_true() {
        when(sysConfigService.getValue("cloud_route", "business_whitelist_enabled")).thenReturn("1");
        when(valueOperations.get("ss:config:set:business_cloud_whitelist")).thenReturn(null);
        when(sysConfigMapper.findByType("business_cloud_whitelist"))
                .thenReturn(List.of(row("tag-foo", 1), row("tag-bar", 1)));
        assertTrue(service.allowsCloud("tag-foo"));
    }

    @Test
    @DisplayName("switch=1 + tag in whitelist but status=0 → false")
    void switchOn_tagDisabled_false() {
        when(sysConfigService.getValue("cloud_route", "business_whitelist_enabled")).thenReturn("1");
        when(valueOperations.get("ss:config:set:business_cloud_whitelist")).thenReturn(null);
        when(sysConfigMapper.findByType("business_cloud_whitelist"))
                .thenReturn(List.of(row("tag-foo", 0), row("tag-bar", 1)));
        assertFalse(service.allowsCloud("tag-foo"));
    }

    @Test
    @DisplayName("switch=1 + tag not in whitelist → false")
    void switchOn_tagMiss_false() {
        when(sysConfigService.getValue("cloud_route", "business_whitelist_enabled")).thenReturn("1");
        when(valueOperations.get("ss:config:set:business_cloud_whitelist")).thenReturn(null);
        when(sysConfigMapper.findByType("business_cloud_whitelist"))
                .thenReturn(List.of(row("tag-foo", 1)));
        assertFalse(service.allowsCloud("tag-baz"));
    }

    // ================== 异常 / 未知值 fail-open ==================

    @Test
    @DisplayName("switch value not '0' or '1' → treated as disabled (true)")
    void switchUnknownValue_true() {
        when(sysConfigService.getValue("cloud_route", "business_whitelist_enabled")).thenReturn("yes");
        assertTrue(service.allowsCloud("tag-foo"));
    }

    @Test
    @DisplayName("DB exception while loading whitelist → fail-open (true)")
    void dbException_failOpen() {
        when(sysConfigService.getValue("cloud_route", "business_whitelist_enabled")).thenReturn("1");
        when(valueOperations.get("ss:config:set:business_cloud_whitelist")).thenReturn(null);
        when(sysConfigMapper.findByType("business_cloud_whitelist"))
                .thenThrow(new RuntimeException("db down"));
        assertTrue(service.allowsCloud("tag-foo"));
    }

    @Test
    @DisplayName("switch read exception → fail-open (true)")
    void switchReadException_failOpen_true() {
        when(sysConfigService.getValue("cloud_route", "business_whitelist_enabled"))
                .thenThrow(new RuntimeException("config service down"));
        assertTrue(service.allowsCloud("tag-foo"));
        verify(sysConfigMapper, never()).findByType(anyString());
    }

    // ================== 缓存 ==================

    @Test
    @DisplayName("Set cache hit on second call: DB queried at most once")
    void setCache_secondCallNoDb() {
        when(sysConfigService.getValue("cloud_route", "business_whitelist_enabled")).thenReturn("1");
        when(valueOperations.get("ss:config:set:business_cloud_whitelist"))
                .thenReturn(null)
                .thenReturn("[\"tag-foo\"]");
        when(sysConfigMapper.findByType("business_cloud_whitelist"))
                .thenReturn(List.of(row("tag-foo", 1)));

        service.allowsCloud("tag-foo");
        service.allowsCloud("tag-foo");

        verify(sysConfigMapper, atMostOnce()).findByType("business_cloud_whitelist");
    }

    @Test
    @DisplayName("Set cache: Redis read fail → fallback to DB")
    void setCache_redisFail_fallbackDb() {
        when(sysConfigService.getValue("cloud_route", "business_whitelist_enabled")).thenReturn("1");
        when(valueOperations.get("ss:config:set:business_cloud_whitelist"))
                .thenThrow(new RuntimeException("redis down"));
        when(sysConfigMapper.findByType("business_cloud_whitelist"))
                .thenReturn(List.of(row("tag-foo", 1)));
        assertTrue(service.allowsCloud("tag-foo"));
    }
}
