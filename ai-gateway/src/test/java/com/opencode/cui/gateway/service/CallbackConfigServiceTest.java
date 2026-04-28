package com.opencode.cui.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

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
 * CallbackConfigService 单元测试（TDD）。
 *
 * <ul>
 *   <li>缓存命中不调 resolver</li>
 *   <li>缓存未命中调 resolver 并写缓存</li>
 *   <li>resolver 返回 null 不写缓存</li>
 *   <li>(ak, scope) 维度缓存隔离</li>
 * </ul>
 */
class CallbackConfigServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void getConfig_cacheHit_doesNotCallResolver() throws Exception {
        CallbackConfigResolver resolver = mock(CallbackConfigResolver.class);
        when(resolver.version()).thenReturn("v2");
        StringRedisTemplate redis = mockRedisWithCachedJson(
                "gw:cloud:route:AK1:callback:weagent:chat", cachedCfgJson("AK1"));
        CallbackConfigService svc = new CallbackConfigService(resolver, redis, mapper, 300);
        CallbackConfig result = svc.getConfig("AK1", "callback:weagent:chat");
        assertThat(result.getAk()).isEqualTo("AK1");
        verify(resolver, never()).resolve(any(), any());
    }

    @Test
    void getConfig_cacheMiss_callsResolverAndCaches() throws Exception {
        CallbackConfigResolver resolver = mock(CallbackConfigResolver.class);
        CallbackConfig cfg = new CallbackConfig();
        cfg.setAk("AK1");
        cfg.setScope("callback:weagent:chat");
        cfg.setChannelType("sse");
        when(resolver.resolve("AK1", "callback:weagent:chat")).thenReturn(cfg);
        StringRedisTemplate redis = mockEmptyRedis();
        CallbackConfigService svc = new CallbackConfigService(resolver, redis, mapper, 300);
        CallbackConfig result = svc.getConfig("AK1", "callback:weagent:chat");
        assertThat(result).isNotNull();
        verify(redis.opsForValue()).set(eq("gw:cloud:route:AK1:callback:weagent:chat"),
                anyString(), eq(300L), eq(TimeUnit.SECONDS));
    }

    @Test
    void getConfig_resolverReturnsNull_doesNotCache() throws Exception {
        CallbackConfigResolver resolver = mock(CallbackConfigResolver.class);
        when(resolver.resolve(any(), any())).thenReturn(null);
        StringRedisTemplate redis = mockEmptyRedis();
        CallbackConfigService svc = new CallbackConfigService(resolver, redis, mapper, 300);
        assertThat(svc.getConfig("AK1", "callback:weagent:question_reply")).isNull();
        verify(redis.opsForValue(), never()).set(anyString(), anyString(), anyLong(), any());
    }

    @Test
    void getConfig_perScopeCacheKeyIsolation() throws Exception {
        CallbackConfigResolver resolver = mock(CallbackConfigResolver.class);
        StringRedisTemplate redis = mockEmptyRedis();
        when(resolver.resolve(any(), any())).thenAnswer(inv -> {
            CallbackConfig c = new CallbackConfig();
            c.setAk(inv.getArgument(0));
            c.setScope(inv.getArgument(1));
            return c;
        });
        CallbackConfigService svc = new CallbackConfigService(resolver, redis, mapper, 300);
        svc.getConfig("AK1", "callback:weagent:chat");
        svc.getConfig("AK1", "callback:weagent:question_reply");
        verify(redis.opsForValue()).set(eq("gw:cloud:route:AK1:callback:weagent:chat"),
                anyString(), anyLong(), any());
        verify(redis.opsForValue()).set(eq("gw:cloud:route:AK1:callback:weagent:question_reply"),
                anyString(), anyLong(), any());
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

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
