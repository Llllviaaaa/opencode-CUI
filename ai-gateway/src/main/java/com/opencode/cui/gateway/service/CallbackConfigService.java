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
 * 缓存 {@code sysconfigCacheTtlMs} 毫秒（由 {@code gateway.cloud-route.sysconfig-cache-ttl-ms} 配置）。</p>
 *
 * <p>切换 v1/v2 仅需修改 SS SysConfig，不需要重启任何服务（最长延迟 = TTL）。</p>
 *
 * <h3>v2 fallback 链路</h3>
 * <p>v2 分支额外支持「总开关 + SysConfig 兜底」：
 * <ul>
 *   <li>总开关 {@code cloud_route.v2_fallback_enabled}：{@code "1"} = ON，否则 OFF。</li>
 *   <li>总开关 ON：v2 resolver 返回 null（HTTP 非 200 / data 缺失 / 业务 code 非 200）时
 *       走 {@link SysConfigFallbackProvider} 拉取 SysConfig 兜底。</li>
 *   <li>总开关 OFF：直接跳过 v2 调用，全部走兜底。</li>
 * </ul>
 * fallback 命中后写入与 v2 同一个 Redis key，下游统一从 Redis 读。</p>
 *
 * <h3>缓存策略</h3>
 * <ul>
 *   <li>路由结果缓存 key：{@code gw:cloud:route:{version}:{ak}:{scope}}</li>
 *   <li>路由结果 TTL 由 {@code gateway.cloud-route.cache-ttl-seconds} 配置（默认 300s）</li>
 *   <li>v1/v2 版本判定 in-memory TTL 由 {@code gateway.cloud-route.sysconfig-cache-ttl-ms} 配置（默认 300000ms）</li>
 *   <li>fallback 总开关 in-memory 缓存复用同一 TTL（与版本判定对齐，由 {@link SysConfigFallbackProvider} 一并消费）</li>
 * </ul>
 */
@Slf4j
@Service
public class CallbackConfigService {

    private static final String CACHE_KEY_PREFIX = "gw:cloud:route:";
    private static final String DEFAULT_VERSION = "v1";
    private static final String SS_CONFIG_TYPE = "cloud_route";
    private static final String SS_CONFIG_KEY = "v2_enabled";
    private static final String SS_FALLBACK_SWITCH_KEY = "v2_fallback_enabled";

    private final Map<String, CallbackConfigResolver> resolversByVersion;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final long cacheTtlSeconds;
    private final long sysconfigCacheTtlMs;
    private final SkillServerConfigClient ssConfigClient;
    private final SysConfigFallbackProvider fallbackProvider;

    /** in-memory 版本判定缓存（避免每次 invoke 都打 SS） */
    private final AtomicReference<CachedVersion> versionCache = new AtomicReference<>();
    /** in-memory 总开关缓存（与 versionCache 同 TTL） */
    private final AtomicReference<CachedSwitch> fallbackSwitchCache = new AtomicReference<>();

    public CallbackConfigService(List<CallbackConfigResolver> resolvers,
                                 StringRedisTemplate redisTemplate,
                                 ObjectMapper objectMapper,
                                 SkillServerConfigClient ssConfigClient,
                                 SysConfigFallbackProvider fallbackProvider,
                                 @Value("${gateway.cloud-route.cache-ttl-seconds:300}") long cacheTtlSeconds,
                                 @Value("${gateway.cloud-route.sysconfig-cache-ttl-ms:300000}") long sysconfigCacheTtlMs) {
        this.resolversByVersion = new HashMap<>();
        for (CallbackConfigResolver r : resolvers) {
            this.resolversByVersion.put(r.version(), r);
        }
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.ssConfigClient = ssConfigClient;
        this.fallbackProvider = fallbackProvider;
        this.cacheTtlSeconds = cacheTtlSeconds;
        this.sysconfigCacheTtlMs = sysconfigCacheTtlMs;
        log.info("[CALLBACK_CONFIG] registered resolvers: {}, route cache TTL={}s, version cache TTL={}ms",
                resolversByVersion.keySet(), cacheTtlSeconds, sysconfigCacheTtlMs);
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

        // v2 路径：支持总开关 + SysConfig 兜底（D5：仅作用于 v2，v1 完全不动）
        if ("v2".equals(version)) {
            return resolveV2WithFallback(ak, scope, resolver, cacheKey);
        }

        // v1 路径：完全维持原状
        CallbackConfig fresh = resolver.resolve(ak, scope);
        if (fresh != null) {
            writeCache(cacheKey, fresh);
            log.info("[CALLBACK_CONFIG] fetched and cached: ak={}, scope={}, version={}, channelType={}, authType={}",
                    ak, scope, version, fresh.getChannelType(), fresh.getAuthType());
        }
        return fresh;
    }

    /**
     * v2 分支：按总开关决定是否调用 v2 resolver；resolver 返回 null 时回退到 SysConfig 兜底。
     * <p>命中 fallback 时仍写入 v2 的 Redis key（{@code gw:cloud:route:v2:{ak}:{scope}}），
     * 下游消费方对来源无感。</p>
     */
    private CallbackConfig resolveV2WithFallback(String ak, String scope,
                                                 CallbackConfigResolver resolver,
                                                 String cacheKey) {
        boolean fallbackEnabled = currentFallbackEnabled();
        if (!fallbackEnabled) {
            // 总开关 OFF：跳过 v2，直接走 SysConfig 兜底（D2/D5：OFF=全部走兜底）
            CallbackConfig fb = fallbackProvider.load(ak, scope);
            if (fb != null) {
                writeCache(cacheKey, fb);
                log.info("[CALLBACK_CONFIG] fallback switch OFF, served from SysConfig: ak={}, scope={}, channelType={}, authType={}",
                        ak, scope, fb.getChannelType(), fb.getAuthType());
            }
            return fb;
        }

        // 总开关 ON：先 v2，失败则 fallback
        CallbackConfig fresh = resolver.resolve(ak, scope);
        if (fresh != null) {
            writeCache(cacheKey, fresh);
            log.info("[CALLBACK_CONFIG] fetched and cached: ak={}, scope={}, version=v2, channelType={}, authType={}",
                    ak, scope, fresh.getChannelType(), fresh.getAuthType());
            return fresh;
        }

        // v2 返回 null：触发 fallback（D8/D13：不区分原因，只打一行 log）
        log.info("[CALLBACK_CONFIG] v2 returned null, fallback to SysConfig: ak={}, scope={}", ak, scope);
        CallbackConfig fb = fallbackProvider.load(ak, scope);
        if (fb != null) {
            writeCache(cacheKey, fb);
        }
        return fb;
    }

    /**
     * 当前活跃版本（v1/v2）。带 30s in-memory 缓存避免每次 invoke 打 SS。
     * SS 不可达 / 配置未设置 / 配置非 "1" → 返回默认 v1。
     */
    String currentVersion() {
        long now = System.currentTimeMillis();
        CachedVersion cached = versionCache.get();
        if (cached != null && now - cached.fetchedAtMs < sysconfigCacheTtlMs) {
            return cached.version;
        }
        String configValue = ssConfigClient.getConfigValue(SS_CONFIG_TYPE, SS_CONFIG_KEY);
        String version = "1".equals(configValue) ? "v2" : DEFAULT_VERSION;
        versionCache.set(new CachedVersion(version, now));
        log.debug("[CALLBACK_CONFIG] refreshed activeVersion={} (configValue={})", version, configValue);
        return version;
    }

    /**
     * 当前 v2 fallback 总开关状态。带 30s in-memory 缓存。
     * <p>SS 不可达 / 配置未设置 / 配置非 "1" → 返回 false（OFF），即默认走 SysConfig 兜底。</p>
     */
    boolean currentFallbackEnabled() {
        long now = System.currentTimeMillis();
        CachedSwitch cached = fallbackSwitchCache.get();
        if (cached != null && now - cached.fetchedAtMs < sysconfigCacheTtlMs) {
            return cached.enabled;
        }
        String configValue = ssConfigClient.getConfigValue(SS_CONFIG_TYPE, SS_FALLBACK_SWITCH_KEY);
        boolean enabled = "1".equals(configValue);
        fallbackSwitchCache.set(new CachedSwitch(enabled, now));
        log.debug("[CALLBACK_CONFIG] refreshed fallbackEnabled={} (configValue={})", enabled, configValue);
        return enabled;
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

    /** in-memory 总开关缓存 entry */
    private record CachedSwitch(boolean enabled, long fetchedAtMs) {}
}
