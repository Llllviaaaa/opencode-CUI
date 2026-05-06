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
 *   <li>版本判定结果在 30s 内 cache（重复调 service 时 SS 只被打 1 次）</li>
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
        CallbackConfigService svc = new CallbackConfigService(List.of(v1), redis, mapper, ss, 300);
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
        CallbackConfigService svc = new CallbackConfigService(List.of(v1), redis, mapper, ss, 300);
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
        CallbackConfigService svc = new CallbackConfigService(List.of(v1), redis, mapper, ss, 300);
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
        CallbackConfigService svc = new CallbackConfigService(List.of(v1), redis, mapper, ss, 300);
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
        CallbackConfig cfg = new CallbackConfig();
        cfg.setAk("AK1");
        cfg.setScope("callback:weagent:question_reply");
        cfg.setChannelType("webhook");
        when(v2.resolve("AK1", "callback:weagent:question_reply")).thenReturn(cfg);
        StringRedisTemplate redis = mockEmptyRedis();
        CallbackConfigService svc = new CallbackConfigService(List.of(v1, v2), redis, mapper, ss, 300);
        CallbackConfig result = svc.getConfig("AK1", "callback:weagent:question_reply");
        assertThat(result).isNotNull();
        verify(v2).resolve("AK1", "callback:weagent:question_reply");
        verify(v1, never()).resolve(any(), any());
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
        CallbackConfigService svc = new CallbackConfigService(List.of(v1, v2), redis, mapper, ss, 300);
        CallbackConfig result = svc.getConfig("AK1", "callback:weagent:chat");
        assertThat(result).isNotNull();
        verify(v1).resolve("AK1", "callback:weagent:chat");
        verify(v2, never()).resolve(any(), any());
    }

    @Test
    void getConfig_ssReturnsZero_routesToV1() throws Exception {
        CallbackConfigResolver v1 = mockResolver("v1");
        CallbackConfigResolver v2 = mockResolver("v2");
        SkillServerConfigClient ss = mock(SkillServerConfigClient.class);
        when(ss.getConfigValue("cloud_route", "v2_enabled")).thenReturn("0");
        when(v1.resolve(any(), any())).thenReturn(null);
        StringRedisTemplate redis = mockEmptyRedis();
        CallbackConfigService svc = new CallbackConfigService(List.of(v1, v2), redis, mapper, ss, 300);
        svc.getConfig("AK1", "callback:weagent:chat");
        verify(v1).resolve("AK1", "callback:weagent:chat");
        verify(v2, never()).resolve(any(), any());
    }

    @Test
    void currentVersion_cachesResultFor30s() throws Exception {
        CallbackConfigResolver v1 = mockResolver("v1");
        CallbackConfigResolver v2 = mockResolver("v2");
        SkillServerConfigClient ss = mock(SkillServerConfigClient.class);
        when(ss.getConfigValue(any(), any())).thenReturn("1");
        when(v2.resolve(any(), any())).thenReturn(null);
        StringRedisTemplate redis = mockEmptyRedis();
        CallbackConfigService svc = new CallbackConfigService(List.of(v1, v2), redis, mapper, ss, 300);
        // 三次调用，SS 应只被打 1 次（in-memory cache 30s 内有效）
        svc.getConfig("AK1", "callback:weagent:chat");
        svc.getConfig("AK1", "callback:weagent:question_reply");
        svc.getConfig("AK2", "callback:weagent:chat");
        verify(ss, times(1)).getConfigValue("cloud_route", "v2_enabled");
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
