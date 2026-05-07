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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atMostOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChannelSuppressReplyWhitelistServiceTest {

    @Mock SysConfigService sysConfigService;
    @Mock SysConfigMapper sysConfigMapper;
    @Mock StringRedisTemplate redisTemplate;
    @Mock ValueOperations<String, String> valueOperations;
    @Mock SysConfigProperties properties;

    private ChannelSuppressReplyWhitelistService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        service = new ChannelSuppressReplyWhitelistService(
                sysConfigService, sysConfigMapper, redisTemplate, properties, objectMapper);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(properties.getCacheTtlMinutes()).thenReturn(5L);
    }

    private SysConfig row(String key, int status) {
        SysConfig c = new SysConfig();
        c.setConfigType("suppress_reply_channel_whitelist");
        c.setConfigKey(key);
        c.setConfigValue("1");
        c.setStatus(status);
        return c;
    }

    @Test
    @DisplayName("switch=0 → false (do not touch whitelist)")
    void switchOff_false() {
        when(sysConfigService.getValue("channel_suppress_reply", "channel_suppress_reply_enabled"))
                .thenReturn("0");
        assertFalse(service.shouldSuppress("opencode"));
        verify(sysConfigMapper, never()).findByType(anyString());
    }

    @Test
    @DisplayName("switch=null/unconfigured → false (default disabled)")
    void switchUnconfigured_false() {
        when(sysConfigService.getValue("channel_suppress_reply", "channel_suppress_reply_enabled"))
                .thenReturn(null);
        assertFalse(service.shouldSuppress("opencode"));
    }

    @Test
    @DisplayName("switch=1 + toolType null/blank → false")
    void blankToolType_false() {
        when(sysConfigService.getValue("channel_suppress_reply", "channel_suppress_reply_enabled"))
                .thenReturn("1");
        assertFalse(service.shouldSuppress(null));
        assertFalse(service.shouldSuppress(""));
        assertFalse(service.shouldSuppress("   "));
    }

    @Test
    @DisplayName("switch=1 + empty whitelist → false (fail-safe)")
    void switchOn_emptyTable_false() {
        when(sysConfigService.getValue("channel_suppress_reply", "channel_suppress_reply_enabled"))
                .thenReturn("1");
        when(valueOperations.get("ss:config:set:suppress_reply_channel_whitelist")).thenReturn(null);
        when(sysConfigMapper.findByType("suppress_reply_channel_whitelist"))
                .thenReturn(Collections.emptyList());
        assertFalse(service.shouldSuppress("opencode"));
    }

    @Test
    @DisplayName("switch=1 + toolType in whitelist (status=1) → true")
    void hit_true() {
        when(sysConfigService.getValue("channel_suppress_reply", "channel_suppress_reply_enabled"))
                .thenReturn("1");
        when(valueOperations.get("ss:config:set:suppress_reply_channel_whitelist")).thenReturn(null);
        when(sysConfigMapper.findByType("suppress_reply_channel_whitelist"))
                .thenReturn(List.of(row("opencode", 1), row("channel", 1)));
        assertTrue(service.shouldSuppress("opencode"));
    }

    @Test
    @DisplayName("switch=1 + toolType in whitelist but status=0 → false")
    void hitDisabled_false() {
        when(sysConfigService.getValue("channel_suppress_reply", "channel_suppress_reply_enabled"))
                .thenReturn("1");
        when(valueOperations.get("ss:config:set:suppress_reply_channel_whitelist")).thenReturn(null);
        when(sysConfigMapper.findByType("suppress_reply_channel_whitelist"))
                .thenReturn(List.of(row("opencode", 0)));
        assertFalse(service.shouldSuppress("opencode"));
    }

    @Test
    @DisplayName("switch=1 + toolType not in whitelist → false")
    void miss_false() {
        when(sysConfigService.getValue("channel_suppress_reply", "channel_suppress_reply_enabled"))
                .thenReturn("1");
        when(valueOperations.get("ss:config:set:suppress_reply_channel_whitelist")).thenReturn(null);
        when(sysConfigMapper.findByType("suppress_reply_channel_whitelist"))
                .thenReturn(List.of(row("opencode", 1)));
        assertFalse(service.shouldSuppress("other"));
    }

    @Test
    @DisplayName("DB exception → fail-safe (false), opposite of business whitelist's fail-open")
    void dbException_failSafe() {
        when(sysConfigService.getValue("channel_suppress_reply", "channel_suppress_reply_enabled"))
                .thenReturn("1");
        when(valueOperations.get("ss:config:set:suppress_reply_channel_whitelist")).thenReturn(null);
        when(sysConfigMapper.findByType("suppress_reply_channel_whitelist"))
                .thenThrow(new RuntimeException("db down"));
        assertFalse(service.shouldSuppress("opencode"));
    }

    @Test
    @DisplayName("switch read exception → false (treat as disabled)")
    void switchReadException_false() {
        when(sysConfigService.getValue("channel_suppress_reply", "channel_suppress_reply_enabled"))
                .thenThrow(new RuntimeException("config service down"));
        assertFalse(service.shouldSuppress("opencode"));
    }

    @Test
    @DisplayName("switch unknown value → false (treat as disabled)")
    void switchUnknown_false() {
        when(sysConfigService.getValue("channel_suppress_reply", "channel_suppress_reply_enabled"))
                .thenReturn("yes");
        assertFalse(service.shouldSuppress("opencode"));
    }

    @Test
    @DisplayName("Set cache hit on second call: DB queried at most once")
    void setCache_secondCallNoDb() {
        when(sysConfigService.getValue("channel_suppress_reply", "channel_suppress_reply_enabled"))
                .thenReturn("1");
        when(valueOperations.get("ss:config:set:suppress_reply_channel_whitelist"))
                .thenReturn(null)
                .thenReturn("[\"opencode\"]");
        when(sysConfigMapper.findByType("suppress_reply_channel_whitelist"))
                .thenReturn(List.of(row("opencode", 1)));

        service.shouldSuppress("opencode");
        service.shouldSuppress("opencode");

        verify(sysConfigMapper, atMostOnce()).findByType("suppress_reply_channel_whitelist");
    }
}
