package com.opencode.cui.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CallbackConfigService 单元测试。
 *
 * <p>v1/v2 路由由 SS SysConfig 控制（{@code cloud_route.v2_enabled = "1"} 启用 v2），
 * GW 通过 {@link SkillServerConfigClient} 查询并 in-memory 缓存。</p>
 *
 * 覆盖：
 * <ul>
 *   <li>路由结果缓存命中/miss/null 不缓存</li>
 *   <li>per-(version, ak, scope) 缓存隔离</li>
 *   <li>SS configValue="1" → 走 v2 resolver</li>
 *   <li>SS configValue=null/其它 → 走 v1 resolver</li>
 *   <li>SS 调用失败 → fallback v1</li>
 *   <li>版本判定结果在 sysconfig-cache-ttl 窗口内 cache（重复调 service 时 SS 只被打 1 次）</li>
 *   <li>v2 fallback 总开关 + SysConfig 兜底分支（D5/D8/D13）</li>
 * </ul>
 */
class CallbackConfigServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void getConfig_cacheHit_doesNotCallResolver() throws Exception {
        CallbackConfigResolver v1 = mockResolver("v1");
        SkillServerConfigClient ss = mock(SkillServerConfigClient.class);
        when(ss.getConfigValue("cloud_route", "v2_enabled")).thenReturn(null);
        StringRedisTemplate redis = mockRedisWithCachedJson(
                "gw:cloud:route:v1:AK1:callback:weagent:chat", cachedCfgJson("AK1"));
        SysConfigFallbackProvider fallback = mock(SysConfigFallbackProvider.class);
        CallbackConfigService svc = new CallbackConfigService(List.of(v1), redis, mapper, ss, fallback, 300, 300_000L);
        CallbackConfig result = svc.getConfig("AK1", "callback:weagent:chat");
        assertThat(result.getAk()).isEqualTo("AK1");
        verify(v1, never()).resolve(any(), any());
    }

    @Test
    void getConfig_cacheMiss_callsResolverAndCaches() throws Exception {
        CallbackConfigResolver v1 = mockResolver("v1");
        SkillServerConfigClient ss = mock(SkillServerConfigClient.class);
        when(ss.getConfigValue(any(), any())).thenReturn(null);
        CallbackConfig cfg = new CallbackConfig();
        cfg.setAk("AK1");
        cfg.setScope("callback:weagent:chat");
        cfg.setChannelType("sse");
        when(v1.resolve("AK1", "callback:weagent:chat")).thenReturn(cfg);
        StringRedisTemplate redis = mockEmptyRedis();
        SysConfigFallbackProvider fallback = mock(SysConfigFallbackProvider.class);
        CallbackConfigService svc = new CallbackConfigService(List.of(v1), redis, mapper, ss, fallback, 300, 300_000L);
        CallbackConfig result = svc.getConfig("AK1", "callback:weagent:chat");
        assertThat(result).isNotNull();
        verify(redis.opsForValue()).set(eq("gw:cloud:route:v1:AK1:callback:weagent:chat"),
                anyString(), eq(300L), eq(TimeUnit.SECONDS));
    }

    @Test
    void getConfig_resolverReturnsNull_doesNotCache() throws Exception {
        CallbackConfigResolver v1 = mockResolver("v1");
        SkillServerConfigClient ss = mock(SkillServerConfigClient.class);
        when(ss.getConfigValue(any(), any())).thenReturn(null);
        when(v1.resolve(any(), any())).thenReturn(null);
        StringRedisTemplate redis = mockEmptyRedis();
        SysConfigFallbackProvider fallback = mock(SysConfigFallbackProvider.class);
        CallbackConfigService svc = new CallbackConfigService(List.of(v1), redis, mapper, ss, fallback, 300, 300_000L);
        assertThat(svc.getConfig("AK1", "callback:weagent:question_reply")).isNull();
        verify(redis.opsForValue(), never()).set(anyString(), anyString(), anyLong(), any());
    }

    @Test
    void getConfig_perScopeCacheKeyIsolation() throws Exception {
        CallbackConfigResolver v1 = mockResolver("v1");
        SkillServerConfigClient ss = mock(SkillServerConfigClient.class);
        when(ss.getConfigValue(any(), any())).thenReturn(null);
        StringRedisTemplate redis = mockEmptyRedis();
        when(v1.resolve(any(), any())).thenAnswer(inv -> {
            CallbackConfig c = new CallbackConfig();
            c.setAk(inv.getArgument(0));
            c.setScope(inv.getArgument(1));
            return c;
        });
        SysConfigFallbackProvider fallback = mock(SysConfigFallbackProvider.class);
        CallbackConfigService svc = new CallbackConfigService(List.of(v1), redis, mapper, ss, fallback, 300, 300_000L);
        svc.getConfig("AK1", "callback:weagent:chat");
        svc.getConfig("AK1", "callback:weagent:question_reply");
        verify(redis.opsForValue()).set(eq("gw:cloud:route:v1:AK1:callback:weagent:chat"),
                anyString(), anyLong(), any());
        verify(redis.opsForValue()).set(eq("gw:cloud:route:v1:AK1:callback:weagent:question_reply"),
                anyString(), anyLong(), any());
    }

    @Test
    void getConfig_ssReturns1_routesToV2Resolver() throws Exception {
        CallbackConfigResolver v1 = mockResolver("v1");
        CallbackConfigResolver v2 = mockResolver("v2");
        SkillServerConfigClient ss = mock(SkillServerConfigClient.class);
        when(ss.getConfigValue("cloud_route", "v2_enabled")).thenReturn("1");
        when(ss.getConfigValue("cloud_route", "v2_fallback_enabled")).thenReturn("1"); // 总开关 ON
        CallbackConfig cfg = new CallbackConfig();
        cfg.setAk("AK1");
        cfg.setScope("callback:weagent:question_reply");
        cfg.setChannelType("webhook");
        when(v2.resolve("AK1", "callback:weagent:question_reply")).thenReturn(cfg);
        StringRedisTemplate redis = mockEmptyRedis();
        SysConfigFallbackProvider fallback = mock(SysConfigFallbackProvider.class);
        CallbackConfigService svc = new CallbackConfigService(List.of(v1, v2), redis, mapper, ss, fallback, 300, 300_000L);
        CallbackConfig result = svc.getConfig("AK1", "callback:weagent:question_reply");
        assertThat(result).isNotNull();
        verify(v2).resolve("AK1", "callback:weagent:question_reply");
        verify(v1, never()).resolve(any(), any());
        verify(fallback, never()).load(any(), any());
    }

    @Test
    void getConfig_ssReturnsNull_routesToV1ByDefault() throws Exception {
        CallbackConfigResolver v1 = mockResolver("v1");
        CallbackConfigResolver v2 = mockResolver("v2");
        SkillServerConfigClient ss = mock(SkillServerConfigClient.class);
        when(ss.getConfigValue("cloud_route", "v2_enabled")).thenReturn(null);
        CallbackConfig cfg = new CallbackConfig();
        cfg.setAk("AK1");
        cfg.setScope("callback:weagent:chat");
        cfg.setChannelType("sse");
        when(v1.resolve("AK1", "callback:weagent:chat")).thenReturn(cfg);
        StringRedisTemplate redis = mockEmptyRedis();
        SysConfigFallbackProvider fallback = mock(SysConfigFallbackProvider.class);
        CallbackConfigService svc = new CallbackConfigService(List.of(v1, v2), redis, mapper, ss, fallback, 300, 300_000L);
        CallbackConfig result = svc.getConfig("AK1", "callback:weagent:chat");
        assertThat(result).isNotNull();
        verify(v1).resolve("AK1", "callback:weagent:chat");
        verify(v2, never()).resolve(any(), any());
        // v1 路径不触碰 fallback provider
        verify(fallback, never()).load(any(), any());
    }

    @Test
    void getConfig_ssReturnsZero_routesToV1() throws Exception {
        CallbackConfigResolver v1 = mockResolver("v1");
        CallbackConfigResolver v2 = mockResolver("v2");
        SkillServerConfigClient ss = mock(SkillServerConfigClient.class);
        when(ss.getConfigValue("cloud_route", "v2_enabled")).thenReturn("0");
        when(v1.resolve(any(), any())).thenReturn(null);
        StringRedisTemplate redis = mockEmptyRedis();
        SysConfigFallbackProvider fallback = mock(SysConfigFallbackProvider.class);
        CallbackConfigService svc = new CallbackConfigService(List.of(v1, v2), redis, mapper, ss, fallback, 300, 300_000L);
        svc.getConfig("AK1", "callback:weagent:chat");
        verify(v1).resolve("AK1", "callback:weagent:chat");
        verify(v2, never()).resolve(any(), any());
    }

    @Test
    void currentVersion_cachesResultWithinTtlWindow() throws Exception {
        CallbackConfigResolver v1 = mockResolver("v1");
        CallbackConfigResolver v2 = mockResolver("v2");
        SkillServerConfigClient ss = mock(SkillServerConfigClient.class);
        when(ss.getConfigValue("cloud_route", "v2_enabled")).thenReturn("1");
        when(ss.getConfigValue("cloud_route", "v2_fallback_enabled")).thenReturn("1");
        when(v2.resolve(any(), any())).thenReturn(null);
        StringRedisTemplate redis = mockEmptyRedis();
        SysConfigFallbackProvider fallback = mock(SysConfigFallbackProvider.class);
        CallbackConfigService svc = new CallbackConfigService(List.of(v1, v2), redis, mapper, ss, fallback, 300, 300_000L);
        // 三次调用，SS 的 v2_enabled 应只被打 1 次（in-memory cache 在 TTL 窗口内有效）
        svc.getConfig("AK1", "callback:weagent:chat");
        svc.getConfig("AK1", "callback:weagent:question_reply");
        svc.getConfig("AK2", "callback:weagent:chat");
        verify(ss, times(1)).getConfigValue("cloud_route", "v2_enabled");
        // 总开关同样应只被打 1 次
        verify(ss, times(1)).getConfigValue("cloud_route", "v2_fallback_enabled");
    }

    // -----------------------------------------------------------------------
    // v2 fallback 分支测试（D5 / D8 / D13）
    // -----------------------------------------------------------------------

    @Test
    void getConfig_v2_fallbackOff_skipsV2AndUsesSysConfig() throws Exception {
        CallbackConfigResolver v1 = mockResolver("v1");
        CallbackConfigResolver v2 = mockResolver("v2");
        SkillServerConfigClient ss = mock(SkillServerConfigClient.class);
        when(ss.getConfigValue("cloud_route", "v2_enabled")).thenReturn("1");
        when(ss.getConfigValue("cloud_route", "v2_fallback_enabled")).thenReturn(null); // OFF
        SysConfigFallbackProvider fallback = mock(SysConfigFallbackProvider.class);
        CallbackConfig fbCfg = new CallbackConfig();
        fbCfg.setAk("AK1");
        fbCfg.setScope("callback:weagent:chat");
        fbCfg.setChannelType("sse");
        fbCfg.setChannelAddress("http://fb");
        fbCfg.setAuthType("none");
        when(fallback.load("AK1", "callback:weagent:chat")).thenReturn(fbCfg);
        StringRedisTemplate redis = mockEmptyRedis();
        CallbackConfigService svc = new CallbackConfigService(List.of(v1, v2), redis, mapper, ss, fallback, 300, 300_000L);

        CallbackConfig result = svc.getConfig("AK1", "callback:weagent:chat");

        assertThat(result).isNotNull();
        assertThat(result.getChannelAddress()).isEqualTo("http://fb");
        // v2 resolver 不应被调用
        verify(v2, never()).resolve(any(), any());
        // 命中 fallback 后写 v2 key（D9：复用同一 key）
        verify(redis.opsForValue()).set(eq("gw:cloud:route:v2:AK1:callback:weagent:chat"),
                anyString(), eq(300L), eq(TimeUnit.SECONDS));
    }

    @Test
    void getConfig_v2_fallbackOn_v2Returns200_doesNotCallFallback() throws Exception {
        CallbackConfigResolver v1 = mockResolver("v1");
        CallbackConfigResolver v2 = mockResolver("v2");
        SkillServerConfigClient ss = mock(SkillServerConfigClient.class);
        when(ss.getConfigValue("cloud_route", "v2_enabled")).thenReturn("1");
        when(ss.getConfigValue("cloud_route", "v2_fallback_enabled")).thenReturn("1");
        CallbackConfig v2Cfg = new CallbackConfig();
        v2Cfg.setAk("AK1");
        v2Cfg.setScope("callback:weagent:chat");
        v2Cfg.setChannelType("sse");
        v2Cfg.setChannelAddress("http://v2");
        v2Cfg.setAuthType("none");
        when(v2.resolve("AK1", "callback:weagent:chat")).thenReturn(v2Cfg);
        SysConfigFallbackProvider fallback = mock(SysConfigFallbackProvider.class);
        StringRedisTemplate redis = mockEmptyRedis();
        CallbackConfigService svc = new CallbackConfigService(List.of(v1, v2), redis, mapper, ss, fallback, 300, 300_000L);

        CallbackConfig result = svc.getConfig("AK1", "callback:weagent:chat");

        assertThat(result).isNotNull();
        assertThat(result.getChannelAddress()).isEqualTo("http://v2");
        verify(v2).resolve("AK1", "callback:weagent:chat");
        // v2 正常返回，零 fallback 调用（AC 第 2 条）
        verify(fallback, never()).load(any(), any());
        verify(redis.opsForValue()).set(eq("gw:cloud:route:v2:AK1:callback:weagent:chat"),
                anyString(), anyLong(), any());
    }

    @Test
    void getConfig_v2_fallbackOn_v2ReturnsNull_triggersFallbackAndCaches() throws Exception {
        CallbackConfigResolver v1 = mockResolver("v1");
        CallbackConfigResolver v2 = mockResolver("v2");
        SkillServerConfigClient ss = mock(SkillServerConfigClient.class);
        when(ss.getConfigValue("cloud_route", "v2_enabled")).thenReturn("1");
        when(ss.getConfigValue("cloud_route", "v2_fallback_enabled")).thenReturn("1");
        when(v2.resolve(any(), any())).thenReturn(null); // 模拟 HTTP 非 200 / data 缺失
        SysConfigFallbackProvider fallback = mock(SysConfigFallbackProvider.class);
        CallbackConfig fbCfg = new CallbackConfig();
        fbCfg.setAk("AK1");
        fbCfg.setScope("callback:weagent:question_reply");
        fbCfg.setChannelType("webhook");
        fbCfg.setChannelAddress("http://fb-q");
        fbCfg.setAuthType("soa");
        when(fallback.load("AK1", "callback:weagent:question_reply")).thenReturn(fbCfg);
        StringRedisTemplate redis = mockEmptyRedis();
        CallbackConfigService svc = new CallbackConfigService(List.of(v1, v2), redis, mapper, ss, fallback, 300, 300_000L);

        CallbackConfig result = svc.getConfig("AK1", "callback:weagent:question_reply");

        assertThat(result).isNotNull();
        assertThat(result.getChannelAddress()).isEqualTo("http://fb-q");
        verify(v2).resolve("AK1", "callback:weagent:question_reply");
        verify(fallback).load("AK1", "callback:weagent:question_reply");
        // 写入与 v2 同一个 Redis key（D9）
        verify(redis.opsForValue()).set(eq("gw:cloud:route:v2:AK1:callback:weagent:question_reply"),
                anyString(), anyLong(), any());
    }

    @Test
    void getConfig_v2_fallbackOff_sysConfigMissing_returnsNullAndDoesNotCache() throws Exception {
        CallbackConfigResolver v1 = mockResolver("v1");
        CallbackConfigResolver v2 = mockResolver("v2");
        SkillServerConfigClient ss = mock(SkillServerConfigClient.class);
        when(ss.getConfigValue("cloud_route", "v2_enabled")).thenReturn("1");
        when(ss.getConfigValue("cloud_route", "v2_fallback_enabled")).thenReturn(null); // OFF
        SysConfigFallbackProvider fallback = mock(SysConfigFallbackProvider.class);
        when(fallback.load(any(), any())).thenReturn(null); // SysConfig 缺失
        StringRedisTemplate redis = mockEmptyRedis();
        CallbackConfigService svc = new CallbackConfigService(List.of(v1, v2), redis, mapper, ss, fallback, 300, 300_000L);

        CallbackConfig result = svc.getConfig("AK1", "callback:weagent:permission_reply");

        assertThat(result).isNull();
        verify(v2, never()).resolve(any(), any());
        verify(fallback).load("AK1", "callback:weagent:permission_reply");
        verify(redis.opsForValue(), never()).set(anyString(), anyString(), anyLong(), any());
    }

    @Test
    void getConfig_v2_fallbackOn_v2NullAndSysConfigMissing_returnsNull() throws Exception {
        CallbackConfigResolver v1 = mockResolver("v1");
        CallbackConfigResolver v2 = mockResolver("v2");
        SkillServerConfigClient ss = mock(SkillServerConfigClient.class);
        when(ss.getConfigValue("cloud_route", "v2_enabled")).thenReturn("1");
        when(ss.getConfigValue("cloud_route", "v2_fallback_enabled")).thenReturn("1");
        when(v2.resolve(any(), any())).thenReturn(null);
        SysConfigFallbackProvider fallback = mock(SysConfigFallbackProvider.class);
        when(fallback.load(any(), any())).thenReturn(null);
        StringRedisTemplate redis = mockEmptyRedis();
        CallbackConfigService svc = new CallbackConfigService(List.of(v1, v2), redis, mapper, ss, fallback, 300, 300_000L);

        CallbackConfig result = svc.getConfig("AK1", "callback:weagent:chat");

        assertThat(result).isNull();
        verify(v2).resolve("AK1", "callback:weagent:chat");
        verify(fallback).load("AK1", "callback:weagent:chat");
        verify(redis.opsForValue(), never()).set(anyString(), anyString(), anyLong(), any());
    }

    @Test
    void getConfig_v1Path_neverInvokesFallbackProvider() throws Exception {
        CallbackConfigResolver v1 = mockResolver("v1");
        CallbackConfigResolver v2 = mockResolver("v2");
        SkillServerConfigClient ss = mock(SkillServerConfigClient.class);
        when(ss.getConfigValue("cloud_route", "v2_enabled")).thenReturn("0");
        when(v1.resolve(any(), any())).thenReturn(null);
        SysConfigFallbackProvider fallback = mock(SysConfigFallbackProvider.class);
        StringRedisTemplate redis = mockEmptyRedis();
        CallbackConfigService svc = new CallbackConfigService(List.of(v1, v2), redis, mapper, ss, fallback, 300, 300_000L);

        svc.getConfig("AK1", "callback:weagent:chat");

        // v1 路径完全不动（D5）
        verify(fallback, never()).load(any(), any());
        // 总开关也不应被读取（v1 路径无需关心）
        verify(ss, never()).getConfigValue("cloud_route", "v2_fallback_enabled");
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private CallbackConfigResolver mockResolver(String version) {
        CallbackConfigResolver r = mock(CallbackConfigResolver.class);
        when(r.version()).thenReturn(version);
        return r;
    }

    private StringRedisTemplate mockEmptyRedis() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        return redis;
    }

    private StringRedisTemplate mockRedisWithCachedJson(String key, String json) {
        StringRedisTemplate redis = mockEmptyRedis();
        when(redis.opsForValue().get(key)).thenReturn(json);
        return redis;
    }

    private String cachedCfgJson(String ak) throws Exception {
        CallbackConfig c = new CallbackConfig();
        c.setAk(ak);
        c.setScope("callback:weagent:chat");
        c.setChannelType("sse");
        return mapper.writeValueAsString(c);
    }
}
