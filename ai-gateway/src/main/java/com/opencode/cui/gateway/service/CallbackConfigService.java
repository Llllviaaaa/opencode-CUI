package com.opencode.cui.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * 回调订阅配置服务（Spring 外观）。
 *
 * <p>依赖 Spring 注入唯一装配的 {@link CallbackConfigResolver}（v1/v2 二选一，由 feature flag 决定），
 * 使用 {@link StringRedisTemplate} 做 per-(ak, scope) 缓存。</p>
 *
 * <h3>缓存策略</h3>
 * <ul>
 *   <li>缓存 key：{@code gw:cloud:route:{ak}:{scope}}</li>
 *   <li>TTL 通过 {@code gateway.cloud-route.cache-ttl-seconds} 配置（默认 300s）</li>
 *   <li>仅在 resolver 返回非 null 时缓存</li>
 * </ul>
 */
@Slf4j
@Service
public class CallbackConfigService {

    private static final String CACHE_KEY_PREFIX = "gw:cloud:route:";

    private final CallbackConfigResolver resolver;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final long cacheTtlSeconds;

    public CallbackConfigService(CallbackConfigResolver resolver,
                                 StringRedisTemplate redisTemplate,
                                 ObjectMapper objectMapper,
                                 @Value("${gateway.cloud-route.cache-ttl-seconds:300}") long cacheTtlSeconds) {
        this.resolver = resolver;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.cacheTtlSeconds = cacheTtlSeconds;
        log.info("[CALLBACK_CONFIG] active resolver version: {}, cacheTtlSeconds={}",
                resolver.version(), cacheTtlSeconds);
    }

    public CallbackConfig getConfig(String ak, String scope) {
        String cacheKey = CACHE_KEY_PREFIX + ak + ":" + scope;
        CallbackConfig cached = readCache(cacheKey);
        if (cached != null) {
            log.debug("[CALLBACK_CONFIG] cache hit: ak={}, scope={}", ak, scope);
            return cached;
        }
        CallbackConfig fresh = resolver.resolve(ak, scope);
        if (fresh != null) {
            writeCache(cacheKey, fresh);
            log.info("[CALLBACK_CONFIG] fetched and cached: ak={}, scope={}, channelType={}, authType={}",
                    ak, scope, fresh.getChannelType(), fresh.getAuthType());
        }
        return fresh;
    }

    private CallbackConfig readCache(String key) {
        try {
            String json = redisTemplate.opsForValue().get(key);
            return json == null ? null : objectMapper.readValue(json, CallbackConfig.class);
        } catch (Exception e) {
            log.debug("[CALLBACK_CONFIG] cache read error: key={}, error={}", key, e.getMessage());
            return null;
        }
    }

    private void writeCache(String key, CallbackConfig cfg) {
        try {
            redisTemplate.opsForValue().set(key,
                    objectMapper.writeValueAsString(cfg),
                    cacheTtlSeconds, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("[CALLBACK_CONFIG] cache write error: key={}, error={}", key, e.getMessage());
        }
    }
}
