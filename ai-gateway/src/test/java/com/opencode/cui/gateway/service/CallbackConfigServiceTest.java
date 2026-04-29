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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CallbackConfigService 单元测试。
 *
 * <ul>
 *   <li>缓存命中不调 resolver</li>
 *   <li>缓存未命中调 resolver 并写缓存</li>
 *   <li>resolver 返回 null 不写缓存</li>
 *   <li>(version, ak, scope) 维度缓存隔离</li>
 *   <li>requestedVersion 路由：v2 → v2 resolver；其它 → v1 resolver</li>
 * </ul>
 */
class CallbackConfigServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void getConfig_cacheHit_doesNotCallResolver() throws Exception {
        CallbackConfigResolver v1Resolver = mockResolver("v1");
        StringRedisTemplate redis = mockRedisWithCachedJson(
                "gw:cloud:route:v1:AK1:callback:weagent:chat", cachedCfgJson("AK1"));
        CallbackConfigService svc = new CallbackConfigService(List.of(v1Resolver), redis, mapper, 300);
        CallbackConfig result = svc.getConfig("AK1", "callback:weagent:chat", null);
        assertThat(result.getAk()).isEqualTo("AK1");
        verify(v1Resolver, never()).resolve(any(), any());
    }

    @Test
    void getConfig_cacheMiss_callsResolverAndCaches() throws Exception {
        CallbackConfigResolver v1Resolver = mockResolver("v1");
        CallbackConfig cfg = new CallbackConfig();
        cfg.setAk("AK1");
        cfg.setScope("callback:weagent:chat");
        cfg.setChannelType("sse");
        when(v1Resolver.resolve("AK1", "callback:weagent:chat")).thenReturn(cfg);
        StringRedisTemplate redis = mockEmptyRedis();
        CallbackConfigService svc = new CallbackConfigService(List.of(v1Resolver), redis, mapper, 300);
        CallbackConfig result = svc.getConfig("AK1", "callback:weagent:chat", null);
        assertThat(result).isNotNull();
        verify(redis.opsForValue()).set(eq("gw:cloud:route:v1:AK1:callback:weagent:chat"),
                anyString(), eq(300L), eq(TimeUnit.SECONDS));
    }

    @Test
    void getConfig_resolverReturnsNull_doesNotCache() throws Exception {
        CallbackConfigResolver v1Resolver = mockResolver("v1");
        when(v1Resolver.resolve(any(), any())).thenReturn(null);
        StringRedisTemplate redis = mockEmptyRedis();
        CallbackConfigService svc = new CallbackConfigService(List.of(v1Resolver), redis, mapper, 300);
        assertThat(svc.getConfig("AK1", "callback:weagent:question_reply", null)).isNull();
        verify(redis.opsForValue(), never()).set(anyString(), anyString(), anyLong(), any());
    }

    @Test
    void getConfig_perScopeCacheKeyIsolation() throws Exception {
        CallbackConfigResolver v1Resolver = mockResolver("v1");
        StringRedisTemplate redis = mockEmptyRedis();
        when(v1Resolver.resolve(any(), any())).thenAnswer(inv -> {
            CallbackConfig c = new CallbackConfig();
            c.setAk(inv.getArgument(0));
            c.setScope(inv.getArgument(1));
            return c;
        });
        CallbackConfigService svc = new CallbackConfigService(List.of(v1Resolver), redis, mapper, 300);
        svc.getConfig("AK1", "callback:weagent:chat", null);
        svc.getConfig("AK1", "callback:weagent:question_reply", null);
        verify(redis.opsForValue()).set(eq("gw:cloud:route:v1:AK1:callback:weagent:chat"),
                anyString(), anyLong(), any());
        verify(redis.opsForValue()).set(eq("gw:cloud:route:v1:AK1:callback:weagent:question_reply"),
                anyString(), anyLong(), any());
    }

    @Test
    void getConfig_apiVersionV2_routesToV2Resolver() throws Exception {
        CallbackConfigResolver v1Resolver = mockResolver("v1");
        CallbackConfigResolver v2Resolver = mockResolver("v2");
        CallbackConfig cfg = new CallbackConfig();
        cfg.setAk("AK1");
        cfg.setScope("callback:weagent:question_reply");
        cfg.setChannelType("webhook");
        when(v2Resolver.resolve("AK1", "callback:weagent:question_reply")).thenReturn(cfg);
        StringRedisTemplate redis = mockEmptyRedis();
        CallbackConfigService svc = new CallbackConfigService(List.of(v1Resolver, v2Resolver), redis, mapper, 300);
        CallbackConfig result = svc.getConfig("AK1", "callback:weagent:question_reply", "v2");
        assertThat(result).isNotNull();
        verify(v2Resolver).resolve("AK1", "callback:weagent:question_reply");
        verify(v1Resolver, never()).resolve(any(), any());
    }

    @Test
    void getConfig_apiVersionNull_routesToV1ByDefault() throws Exception {
        CallbackConfigResolver v1Resolver = mockResolver("v1");
        CallbackConfigResolver v2Resolver = mockResolver("v2");
        CallbackConfig cfg = new CallbackConfig();
        cfg.setAk("AK1");
        cfg.setScope("callback:weagent:chat");
        cfg.setChannelType("sse");
        when(v1Resolver.resolve("AK1", "callback:weagent:chat")).thenReturn(cfg);
        StringRedisTemplate redis = mockEmptyRedis();
        CallbackConfigService svc = new CallbackConfigService(List.of(v1Resolver, v2Resolver), redis, mapper, 300);
        CallbackConfig result = svc.getConfig("AK1", "callback:weagent:chat", null);
        assertThat(result).isNotNull();
        verify(v1Resolver).resolve("AK1", "callback:weagent:chat");
        verify(v2Resolver, never()).resolve(any(), any());
    }

    @Test
    void getConfig_unknownVersion_fallbackToV1() throws Exception {
        CallbackConfigResolver v1Resolver = mockResolver("v1");
        CallbackConfigResolver v2Resolver = mockResolver("v2");
        when(v1Resolver.resolve(any(), any())).thenReturn(null);
        StringRedisTemplate redis = mockEmptyRedis();
        CallbackConfigService svc = new CallbackConfigService(List.of(v1Resolver, v2Resolver), redis, mapper, 300);
        svc.getConfig("AK1", "callback:weagent:chat", "v9");
        verify(v1Resolver).resolve("AK1", "callback:weagent:chat");
        verify(v2Resolver, never()).resolve(any(), any());
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
