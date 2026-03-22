package com.opencode.cui.gateway.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * AK/SK 四级认证服务。
 *
 * <ul>
 *   <li>L1: Caffeine 本地缓存命中 → 信任缓存的 userId（外部 API 首次验签后回填）</li>
 *   <li>L2: Redis 缓存命中 → 信任缓存，回填 L1</li>
 *   <li>L3: 外部身份 API 校验（服务端验签）→ 回填 L1+L2</li>
 *   <li>L4: 拒绝认证</li>
 * </ul>
 *
 * 安全保证：
 * - 时间窗口: ±5 分钟（每次请求校验）
 * - Nonce 防重放: Redis SET NX TTL 5 分钟（每次请求校验）
 * - 缓存 TTL: L1 5min / L2 1h（限制信任窗口）
 * - 首次认证必须通过外部 API 真实验签
 */
@Slf4j
@Service
public class AkSkAuthService {

    private static final String NONCE_KEY_PREFIX = "gw:auth:nonce:";
    private static final String IDENTITY_CACHE_KEY_PREFIX = "auth:identity:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final IdentityApiClient identityApiClient;

    /** L1 本地缓存: ak → IdentityCacheEntry */
    private final Cache<String, IdentityCacheEntry> l1Cache;

    private final long timestampToleranceSeconds;
    private final long nonceTtlSeconds;
    private final long l2TtlSeconds;

    /** 本地调试开关：跳过全部签名校验，直接以 AK 作为 userId 放行 */
    private final boolean skipVerification;

    public AkSkAuthService(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            IdentityApiClient identityApiClient,
            @Value("${gateway.auth.timestamp-tolerance-seconds:300}") long timestampToleranceSeconds,
            @Value("${gateway.auth.nonce-ttl-seconds:300}") long nonceTtlSeconds,
            @Value("${gateway.auth.identity-cache.l1-ttl-seconds:300}") long l1TtlSeconds,
            @Value("${gateway.auth.identity-cache.l1-max-size:10000}") long l1MaxSize,
            @Value("${gateway.auth.identity-cache.l2-ttl-seconds:3600}") long l2TtlSeconds,
            @Value("${gateway.auth.skip-verification:false}") boolean skipVerification) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.identityApiClient = identityApiClient;
        this.timestampToleranceSeconds = timestampToleranceSeconds;
        this.nonceTtlSeconds = nonceTtlSeconds;
        this.l2TtlSeconds = l2TtlSeconds;
        this.skipVerification = skipVerification;
        this.l1Cache = Caffeine.newBuilder()
                .maximumSize(l1MaxSize)
                .expireAfterWrite(Duration.ofSeconds(l1TtlSeconds))
                .build();
        if (skipVerification) {
            log.warn("⚠ Auth verification DISABLED (gateway.auth.skip-verification=true). DO NOT use in production!");
        }
    }

    /**
     * 验证 AK/SK 签名。
     *
     * @param ak        Access Key
     * @param timestamp Unix 时间戳（秒，字符串）
     * @param nonce     随机字符串（防重放）
     * @param signature Base64 编码的 HMAC-SHA256 签名
     * @return 验证成功返回 userId；失败返回 null
     */
    public String verify(String ak, String timestamp, String nonce, String signature) {
        if (ak == null || timestamp == null || nonce == null || signature == null) {
            log.warn("Auth failed: missing parameters. ak={}", ak);
            return null;
        }

        // 本地调试模式：跳过全部校验，以 AK 作为 userId 直接放行
        if (skipVerification) {
            log.info("Auth SKIPPED (debug mode). ak={}, userId={}", ak, ak);
            return ak;
        }

        // 1. 时间窗口校验
        long ts;
        try {
            ts = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            log.warn("Auth failed: invalid timestamp format. ak={}, ts={}", ak, timestamp);
            return null;
        }
        long now = Instant.now().getEpochSecond();
        if (Math.abs(now - ts) > timestampToleranceSeconds) {
            log.warn("Auth failed: timestamp out of window. ak={}, ts={}, now={}", ak, ts, now);
            return null;
        }

        // 2. Nonce 防重放
        String nonceKey = NONCE_KEY_PREFIX + nonce;
        Boolean isNew = redisTemplate.opsForValue()
                .setIfAbsent(nonceKey, "1", nonceTtlSeconds, TimeUnit.SECONDS);
        if (isNew == null || !isNew) {
            log.warn("Auth failed: nonce replay detected. ak={}", ak);
            return null;
        }

        // 3. 多级身份解析
        IdentityCacheEntry identity = resolveIdentity(ak, timestamp, nonce, signature);
        if (identity == null) {
            log.warn("Auth failed: identity not resolved. ak={}", ak);
            redisTemplate.delete(nonceKey);
            return null;
        }

        log.info("Auth success. ak={}, userId={}, level={}", ak, identity.userId(), identity.level());
        return identity.userId();
    }

    // -----------------------------------------------------------------------
    // 多级身份解析
    // -----------------------------------------------------------------------

    /**
     * 按 L1→L2→L3 优先级解析身份信息。
     *
     * L1/L2 命中时信任缓存的 userId（签名已在首次 L3 调用时由外部 API 验证）。
     * L3 外部 API 在服务端完成签名验证，成功后回填 L1+L2。
     */
    IdentityCacheEntry resolveIdentity(String ak, String timestamp, String nonce, String signature) {
        // L1: Caffeine 本地缓存
        IdentityCacheEntry l1 = l1Cache.getIfPresent(ak);
        if (l1 != null) {
            log.debug("Auth L1 cache hit: ak={}", ak);
            return l1;
        }

        // L2: Redis 缓存
        IdentityCacheEntry l2 = getFromRedisCache(ak);
        if (l2 != null) {
            log.debug("Auth L2 cache hit: ak={}", ak);
            l1Cache.put(ak, l2);
            return l2;
        }

        // L3: 外部身份 API
        if (!identityApiClient.isEnabled()) {
            log.error("Auth failed: external identity API not configured. ak={}", ak);
            return null;
        }

        try {
            String userId = identityApiClient.check(ak, timestamp, nonce, signature);
            if (userId != null) {
                IdentityCacheEntry entry = new IdentityCacheEntry(userId, "L3");
                l1Cache.put(ak, entry);
                writeToRedisCache(ak, entry);
                log.info("Auth L3 external API success: ak={}, userId={}", ak, userId);
                return entry;
            }
            // checkResult=false → 外部 API 明确拒绝
            log.info("Auth L3 external API rejected: ak={}", ak);
            return null;
        } catch (IdentityApiClient.IdentityApiException e) {
            log.error("Auth L3 external API unavailable: ak={}, error={}", ak, e.getMessage());
            return null;
        }

        // L4: 拒绝（隐含在上方所有分支 return null）
    }

    // -----------------------------------------------------------------------
    // L2 Redis 缓存操作
    // -----------------------------------------------------------------------

    private IdentityCacheEntry getFromRedisCache(String ak) {
        try {
            String json = redisTemplate.opsForValue().get(IDENTITY_CACHE_KEY_PREFIX + ak);
            if (json == null) {
                return null;
            }
            return objectMapper.readValue(json, IdentityCacheEntry.class);
        } catch (Exception e) {
            log.debug("Failed to read L2 cache: ak={}, error={}", ak, e.getMessage());
            return null;
        }
    }

    private void writeToRedisCache(String ak, IdentityCacheEntry entry) {
        try {
            String json = objectMapper.writeValueAsString(entry);
            redisTemplate.opsForValue().set(
                    IDENTITY_CACHE_KEY_PREFIX + ak, json, l2TtlSeconds, TimeUnit.SECONDS);
        } catch (JsonProcessingException e) {
            log.debug("Failed to write L2 cache: ak={}, error={}", ak, e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // 缓存条目
    // -----------------------------------------------------------------------

    /**
     * 身份缓存条目。
     *
     * @param userId 用户 ID
     * @param level  解析级别（L1/L2/L3，仅用于日志）
     */
    record IdentityCacheEntry(String userId, String level) {
    }
}
