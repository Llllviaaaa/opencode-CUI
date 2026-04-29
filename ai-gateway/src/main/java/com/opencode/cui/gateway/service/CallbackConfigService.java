package com.opencode.cui.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 回调订阅配置服务（Spring 外观）。
 *
 * <p>同时装配 v1（{@link LegacyRouteResolver}）与 v2（{@link GatewayCallbackResolver}）
 * 两个 resolver。运行时按 invoke 消息携带的 {@code apiVersion} 字段动态选择：</p>
 * <ul>
 *   <li>{@code apiVersion == "v2"} → 走 v2 callback config 接口</li>
 *   <li>其它（null / blank / "v1"）→ 走 v1 旧接口（默认）</li>
 * </ul>
 *
 * <p>v1/v2 切换由 SS 端 SysConfig 控制（{@code cloud_route.v2_enabled = "1"} 启用 v2），
 * SS 在 buildInvoke 时把开关结果写入 invoke 消息的 {@code apiVersion} 字段。</p>
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
    private static final String DEFAULT_VERSION = "v1";

    private final Map<String, CallbackConfigResolver> resolversByVersion;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final long cacheTtlSeconds;

    public CallbackConfigService(List<CallbackConfigResolver> resolvers,
                                 StringRedisTemplate redisTemplate,
                                 ObjectMapper objectMapper,
                                 @Value("${gateway.cloud-route.cache-ttl-seconds:300}") long cacheTtlSeconds) {
        this.resolversByVersion = new HashMap<>();
        for (CallbackConfigResolver r : resolvers) {
            this.resolversByVersion.put(r.version(), r);
        }
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.cacheTtlSeconds = cacheTtlSeconds;
        log.info("[CALLBACK_CONFIG] registered resolvers: {}, cacheTtlSeconds={}",
                resolversByVersion.keySet(), cacheTtlSeconds);
    }

    /**
     * 按 invoke 消息指定的 apiVersion 路由到对应 resolver；缺失/未知时 fallback 到 v1。
     *
     * @param ak              access key
     * @param scope           回调 scope
     * @param requestedVersion invoke 消息的 apiVersion 字段（"v2" 走 v2，其它走 v1）
     */
    public CallbackConfig getConfig(String ak, String scope, String requestedVersion) {
        String version = (requestedVersion != null && !requestedVersion.isBlank())
                ? requestedVersion : DEFAULT_VERSION;
        CallbackConfigResolver resolver = resolversByVersion.get(version);
        if (resolver == null) {
            log.warn("[CALLBACK_CONFIG] unknown apiVersion={}, fallback to {}", version, DEFAULT_VERSION);
            resolver = resolversByVersion.get(DEFAULT_VERSION);
            if (resolver == null) {
                log.error("[CALLBACK_CONFIG] no resolver registered for default version {}", DEFAULT_VERSION);
                return null;
            }
            version = DEFAULT_VERSION;
        }

        String cacheKey = CACHE_KEY_PREFIX + version + ":" + ak + ":" + scope;
        CallbackConfig cached = readCache(cacheKey);
        if (cached != null) {
            log.debug("[CALLBACK_CONFIG] cache hit: ak={}, scope={}, version={}", ak, scope, version);
            return cached;
        }
        CallbackConfig fresh = resolver.resolve(ak, scope);
        if (fresh != null) {
            writeCache(cacheKey, fresh);
            log.info("[CALLBACK_CONFIG] fetched and cached: ak={}, scope={}, version={}, channelType={}, authType={}",
                    ak, scope, version, fresh.getChannelType(), fresh.getAuthType());
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
