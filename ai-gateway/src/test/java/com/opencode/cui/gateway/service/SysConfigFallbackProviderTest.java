package com.opencode.cui.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SysConfigFallbackProvider} 单元测试。
 *
 * <p>覆盖：
 * <ul>
 *   <li>scope 字面量 → 短名映射（chat / question / permission）</li>
 *   <li>JSON 完整 → 构造 CallbackConfig 成功</li>
 *   <li>JSON 字段缺失 → 返回 null（warn）</li>
 *   <li>JSON 解析失败 → 返回 null（warn）</li>
 *   <li>SysConfig 返回 null → 返回 null</li>
 *   <li>scope 不在白名单 → 返回 null（不打 SS）</li>
 *   <li>30s 内重复 load 仅打 SS 一次（in-mem 缓存）</li>
 * </ul>
 */
class SysConfigFallbackProviderTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void load_chatScope_jsonComplete_returnsConfig() {
        SkillServerConfigClient ss = mock(SkillServerConfigClient.class);
        when(ss.getConfigValue("cloud_route_fallback", "chat"))
                .thenReturn("{\"channelAddress\":\"http://x/chat\",\"channelType\":\"sse\",\"authType\":\"none\"}");
        SysConfigFallbackProvider p = new SysConfigFallbackProvider(ss, mapper);

        CallbackConfig cfg = p.load("AK1", "callback:weagent:chat");

        assertThat(cfg).isNotNull();
        assertThat(cfg.getAk()).isEqualTo("AK1");
        assertThat(cfg.getScope()).isEqualTo("callback:weagent:chat");
        assertThat(cfg.getChannelAddress()).isEqualTo("http://x/chat");
        assertThat(cfg.getChannelType()).isEqualTo("sse");
        assertThat(cfg.getAuthType()).isEqualTo("none");
        assertThat(cfg.getAppId()).isNull();
    }

    @Test
    void load_questionScope_jsonComplete_returnsConfig() {
        SkillServerConfigClient ss = mock(SkillServerConfigClient.class);
        when(ss.getConfigValue("cloud_route_fallback", "question"))
                .thenReturn("{\"channelAddress\":\"http://x/q\",\"channelType\":\"webhook\",\"authType\":\"soa\"}");
        SysConfigFallbackProvider p = new SysConfigFallbackProvider(ss, mapper);

        CallbackConfig cfg = p.load("AK1", "callback:weagent:question_reply");

        assertThat(cfg).isNotNull();
        assertThat(cfg.getChannelAddress()).isEqualTo("http://x/q");
        assertThat(cfg.getChannelType()).isEqualTo("webhook");
        assertThat(cfg.getAuthType()).isEqualTo("soa");
    }

    @Test
    void load_permissionScope_jsonComplete_returnsConfig() {
        SkillServerConfigClient ss = mock(SkillServerConfigClient.class);
        when(ss.getConfigValue("cloud_route_fallback", "permission"))
                .thenReturn("{\"channelAddress\":\"http://x/p\",\"channelType\":\"webhook\",\"authType\":\"apig\"}");
        SysConfigFallbackProvider p = new SysConfigFallbackProvider(ss, mapper);

        CallbackConfig cfg = p.load("AK1", "callback:weagent:permission_reply");

        assertThat(cfg).isNotNull();
        assertThat(cfg.getChannelAddress()).isEqualTo("http://x/p");
        assertThat(cfg.getChannelType()).isEqualTo("webhook");
        assertThat(cfg.getAuthType()).isEqualTo("apig");
    }

    @Test
    void load_jsonMissingField_returnsNull() {
        SkillServerConfigClient ss = mock(SkillServerConfigClient.class);
        // 缺 authType
        when(ss.getConfigValue("cloud_route_fallback", "chat"))
                .thenReturn("{\"channelAddress\":\"http://x\",\"channelType\":\"sse\"}");
        SysConfigFallbackProvider p = new SysConfigFallbackProvider(ss, mapper);

        CallbackConfig cfg = p.load("AK1", "callback:weagent:chat");

        assertThat(cfg).isNull();
    }

    @Test
    void load_jsonParseError_returnsNull() {
        SkillServerConfigClient ss = mock(SkillServerConfigClient.class);
        when(ss.getConfigValue("cloud_route_fallback", "chat"))
                .thenReturn("not-a-json{{");
        SysConfigFallbackProvider p = new SysConfigFallbackProvider(ss, mapper);

        CallbackConfig cfg = p.load("AK1", "callback:weagent:chat");

        assertThat(cfg).isNull();
    }

    @Test
    void load_sysConfigReturnsNull_returnsNull() {
        SkillServerConfigClient ss = mock(SkillServerConfigClient.class);
        when(ss.getConfigValue(any(), any())).thenReturn(null);
        SysConfigFallbackProvider p = new SysConfigFallbackProvider(ss, mapper);

        CallbackConfig cfg = p.load("AK1", "callback:weagent:chat");

        assertThat(cfg).isNull();
    }

    @Test
    void load_scopeNotInWhitelist_returnsNullWithoutCallingSs() {
        SkillServerConfigClient ss = mock(SkillServerConfigClient.class);
        SysConfigFallbackProvider p = new SysConfigFallbackProvider(ss, mapper);

        CallbackConfig cfg = p.load("AK1", "callback:unknown:scope");

        assertThat(cfg).isNull();
        verify(ss, times(0)).getConfigValue(any(), any());
    }

    @Test
    void load_repeatedWithin30s_callsSsOnlyOnce() {
        SkillServerConfigClient ss = mock(SkillServerConfigClient.class);
        when(ss.getConfigValue("cloud_route_fallback", "chat"))
                .thenReturn("{\"channelAddress\":\"http://x\",\"channelType\":\"sse\",\"authType\":\"none\"}");
        SysConfigFallbackProvider p = new SysConfigFallbackProvider(ss, mapper);

        CallbackConfig c1 = p.load("AK1", "callback:weagent:chat");
        CallbackConfig c2 = p.load("AK2", "callback:weagent:chat");
        CallbackConfig c3 = p.load("AK3", "callback:weagent:chat");

        assertThat(c1).isNotNull();
        assertThat(c2).isNotNull();
        assertThat(c3).isNotNull();
        // 缓存按 scope 短名维度，3 次 load 命中同一 cache，仅打 SS 1 次
        verify(ss, times(1)).getConfigValue("cloud_route_fallback", "chat");
        // 但 ak/scope 必须各自独立填充（不串号）
        assertThat(c1.getAk()).isEqualTo("AK1");
        assertThat(c2.getAk()).isEqualTo("AK2");
        assertThat(c3.getAk()).isEqualTo("AK3");
    }

    @Test
    void load_independentCachePerScope() {
        SkillServerConfigClient ss = mock(SkillServerConfigClient.class);
        when(ss.getConfigValue("cloud_route_fallback", "chat"))
                .thenReturn("{\"channelAddress\":\"http://chat\",\"channelType\":\"sse\",\"authType\":\"none\"}");
        when(ss.getConfigValue("cloud_route_fallback", "question"))
                .thenReturn("{\"channelAddress\":\"http://q\",\"channelType\":\"webhook\",\"authType\":\"soa\"}");
        SysConfigFallbackProvider p = new SysConfigFallbackProvider(ss, mapper);

        CallbackConfig c1 = p.load("AK1", "callback:weagent:chat");
        CallbackConfig c2 = p.load("AK1", "callback:weagent:question_reply");

        assertThat(c1.getChannelAddress()).isEqualTo("http://chat");
        assertThat(c2.getChannelAddress()).isEqualTo("http://q");
        verify(ss, times(1)).getConfigValue("cloud_route_fallback", "chat");
        verify(ss, times(1)).getConfigValue("cloud_route_fallback", "question");
    }
}
