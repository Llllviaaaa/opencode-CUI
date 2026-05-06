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
import java.util.concurrent.atomic.AtomicReference;

/**
 * 回调订阅配置服务（Spring 外观）。
 *
 * <p>同时装配 v1（{@link LegacyRouteResolver}）与 v2（{@link GatewayCallbackResolver}）
 * 两个 resolver。运行时由 GW 自己向 SS 查询 SysConfig 开关
 * （{@code cloud_route.v2_enabled = "1"} 启用 v2），结果在 GW 端 in-memory
 * 缓存 {@link #VERSION_CACHE_TTL_MS} 毫秒。</p>
 *
 * <p>切换 v1/v2 仅需修改 SS SysConfig，不需要重启任何服务（最长延迟 = TTL）。</p>
 *
 * <h3>缓存策略</h3>
 * <ul>
 *   <li>路由结果缓存 key：{@code gw:cloud:route:{version}:{ak}:{scope}}</li>
 *   <li>路由结果 TTL 由 {@code gateway.cloud-route.cache-ttl-seconds} 配置（默认 300s）</li>
 *   <li>v1/v2 版本判定 in-memory 缓存 30s</li>
 * </ul>
 */
@Slf4j
@Service
public class CallbackConfigService {

    private static final String CACHE_KEY_PREFIX = "gw:cloud:route:";
    private static final String DEFAULT_VERSION = "v1";
    private static final String SS_CONFIG_TYPE = "cloud_route";
    private static final String SS_CONFIG_KEY = "v2_enabled";
    private static final long VERSION_CACHE_TTL_MS = 30_000L;

    private final Map<String, CallbackConfigResolver> resolversByVersion;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final long cacheTtlSeconds;
    private final SkillServerConfigClient ssConfigClient;

    /** in-memory 版本判定缓存（避免每次 invoke 都打 SS） */
    private final AtomicReference<CachedVersion> versionCache = new AtomicReference<>();

    public CallbackConfigService(List<CallbackConfigResolver> resolvers,
                                 StringRedisTemplate redisTemplate,
                                 ObjectMapper objectMapper,
                                 SkillServerConfigClient ssConfigClient,
                                 @Value("${gateway.cloud-route.cache-ttl-seconds:300}") long cacheTtlSeconds) {
        this.resolversByVersion = new HashMap<>();
        for (CallbackConfigResolver r : resolvers) {
            this.resolversByVersion.put(r.version(), r);
        }
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.ssConfigClient = ssConfigClient;
        this.cacheTtlSeconds = cacheTtlSeconds;
        log.info("[CALLBACK_CONFIG] registered resolvers: {}, route cache TTL={}s, version cache TTL={}ms",
                resolversByVersion.keySet(), cacheTtlSeconds, VERSION_CACHE_TTL_MS);
    }

    public CallbackConfig getConfig(String ak, String scope) {
        String version = currentVersion();
        CallbackConfigResolver resolver = resolversByVersion.get(version);
        if (resolver == null) {
            log.warn("[CALLBACK_CONFIG] resolver missing for version={}, fallback to {}", version, DEFAULT_VERSION);
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

    /**
     * 当前活跃版本（v1/v2）。带 30s in-memory 缓存避免每次 invoke 打 SS。
     * SS 不可达 / 配置未设置 / 配置非 "1" → 返回默认 v1。
     */
    String currentVersion() {
        long now = System.currentTimeMillis();
        CachedVersion cached = versionCache.get();
        if (cached != null && now - cached.fetchedAtMs < VERSION_CACHE_TTL_MS) {
            return cached.version;
        }
        String configValue = ssConfigClient.getConfigValue(SS_CONFIG_TYPE, SS_CONFIG_KEY);
        String version = "1".equals(configValue) ? "v2" : DEFAULT_VERSION;
        versionCache.set(new CachedVersion(version, now));
        log.debug("[CALLBACK_CONFIG] refreshed activeVersion={} (configValue={})", version, configValue);
        return version;
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

    /** in-memory 版本缓存 entry */
    private record CachedVersion(String version, long fetchedAtMs) {}
}
