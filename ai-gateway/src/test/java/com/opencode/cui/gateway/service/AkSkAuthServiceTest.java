package com.opencode.cui.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * AkSkAuthService 四级认证单元测试。
 *
 * L1 Caffeine → L2 Redis → L3 外部 API → L4 拒绝
 */
@ExtendWith(MockitoExtension.class)
class AkSkAuthServiceTest {

    private static final String TEST_AK = "test-ak-001";
    private static final String TEST_USER_ID = "user-1";

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private IdentityApiClient identityApiClient;

    private AkSkAuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AkSkAuthService(
                redisTemplate,
                new ObjectMapper(),
                identityApiClient,
                300L,   // timestampToleranceSeconds
                300L,   // nonceTtlSeconds
                300L,   // l1TtlSeconds
                10000L, // l1MaxSize
                3600L,  // l2TtlSeconds
                false   // skipVerification
        );
    }

    // -----------------------------------------------------------------------
    // Helper
    // -----------------------------------------------------------------------

    private String validTimestamp() {
        return String.valueOf(Instant.now().getEpochSecond());
    }

    private String randomNonce() {
        return UUID.randomUUID().toString();
    }

    private void mockNonceSuccess() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
                .thenReturn(true);
    }

    private void mockL2CacheMiss() {
        when(valueOperations.get(startsWith("auth:identity:"))).thenReturn(null);
        lenient().doNothing().when(valueOperations)
                .set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
    }

    private void mockExternalApiSuccess() {
        when(identityApiClient.isEnabled()).thenReturn(true);
        when(identityApiClient.check(eq(TEST_AK), anyString(), anyString(), anyString()))
                .thenReturn(TEST_USER_ID);
    }

    // -----------------------------------------------------------------------
    // 参数校验
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("参数校验")
    class ParameterValidation {

        @Test
        @DisplayName("缺少参数返回 null")
        void nullParamsReturnNull() {
            assertNull(authService.verify(null, "123", "abc", "sign"));
            assertNull(authService.verify("ak", null, "abc", "sign"));
            assertNull(authService.verify("ak", "123", null, "sign"));
            assertNull(authService.verify("ak", "123", "abc", null));
        }

        @Test
        @DisplayName("时间戳格式错误返回 null")
        void invalidTimestampFormat() {
            assertNull(authService.verify(TEST_AK, "not-a-number", "abc", "sign"));
        }

        @Test
        @DisplayName("时间戳过期（10 分钟前）返回 null")
        void expiredTimestamp() {
            String ts = String.valueOf(Instant.now().getEpochSecond() - 600);
            assertNull(authService.verify(TEST_AK, ts, "abc", "sign"));
        }

        @Test
        @DisplayName("时间戳超前（10 分钟后）返回 null")
        void futureTimestamp() {
            String ts = String.valueOf(Instant.now().getEpochSecond() + 600);
            assertNull(authService.verify(TEST_AK, ts, "abc", "sign"));
        }
    }

    // -----------------------------------------------------------------------
    // Nonce 防重放
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Nonce 防重放")
    class NonceReplay {

        @Test
        @DisplayName("重复 nonce 返回 null")
        void replayedNonce() {
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
                    .thenReturn(false);

            assertNull(authService.verify(TEST_AK, validTimestamp(), "replayed", "sign"));
        }
    }

    // -----------------------------------------------------------------------
    // L3 外部 API
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("L3 外部身份 API")
    class L3ExternalApi {

        @Test
        @DisplayName("L3 外部 API 验证成功，返回 userId")
        void l3Success() {
            mockNonceSuccess();
            mockL2CacheMiss();
            mockExternalApiSuccess();

            String userId = authService.verify(TEST_AK, validTimestamp(), randomNonce(), "valid-sig");

            assertEquals(TEST_USER_ID, userId);
        }

        @Test
        @DisplayName("L3 外部 API 明确拒绝，返回 null")
        void l3Rejected() {
            mockNonceSuccess();
            mockL2CacheMiss();
            when(identityApiClient.isEnabled()).thenReturn(true);
            when(identityApiClient.check(eq(TEST_AK), anyString(), anyString(), anyString()))
                    .thenReturn(null);

            assertNull(authService.verify(TEST_AK, validTimestamp(), randomNonce(), "bad-sig"));
        }

        @Test
        @DisplayName("L3 外部 API 不可用，返回 null（L4 拒绝）")
        void l3UnavailableRejects() {
            mockNonceSuccess();
            mockL2CacheMiss();
            when(identityApiClient.isEnabled()).thenReturn(true);
            when(identityApiClient.check(eq(TEST_AK), anyString(), anyString(), anyString()))
                    .thenThrow(new IdentityApiClient.IdentityApiException("Connection refused"));

            assertNull(authService.verify(TEST_AK, validTimestamp(), randomNonce(), "sign"));
            // nonce 应被清理
            verify(redisTemplate).delete(anyString());
        }

        @Test
        @DisplayName("外部 API 未配置，返回 null（L4 拒绝）")
        void l3NotConfiguredRejects() {
            mockNonceSuccess();
            mockL2CacheMiss();
            when(identityApiClient.isEnabled()).thenReturn(false);

            assertNull(authService.verify(TEST_AK, validTimestamp(), randomNonce(), "sign"));
            verify(redisTemplate).delete(anyString());
        }

        @Test
        @DisplayName("L3 成功后回填 L2 Redis 缓存")
        void l3BackfillsL2Cache() {
            mockNonceSuccess();
            mockL2CacheMiss();
            mockExternalApiSuccess();

            authService.verify(TEST_AK, validTimestamp(), randomNonce(), "valid-sig");

            // 验证 L2 缓存被写入
            verify(valueOperations).set(
                    eq("auth:identity:" + TEST_AK), anyString(), eq(3600L), eq(TimeUnit.SECONDS));
        }
    }

    // -----------------------------------------------------------------------
    // L1 Caffeine 缓存
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("L1 Caffeine 缓存")
    class L1CaffeineCache {

        @Test
        @DisplayName("第二次请求命中 L1 缓存，不调外部 API")
        void l1CacheHitOnSecondCall() {
            mockNonceSuccess();
            mockL2CacheMiss();
            mockExternalApiSuccess();

            // 第一次：L3 外部 API → 回填 L1
            assertEquals(TEST_USER_ID, authService.verify(TEST_AK, validTimestamp(), randomNonce(), "sig"));

            // 第二次：L1 缓存命中
            assertEquals(TEST_USER_ID, authService.verify(TEST_AK, validTimestamp(), randomNonce(), "sig"));

            // 外部 API 只调了一次
            verify(identityApiClient, times(1)).check(eq(TEST_AK), anyString(), anyString(), anyString());
        }
    }

    // -----------------------------------------------------------------------
    // L2 Redis 缓存
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("L2 Redis 缓存")
    class L2RedisCache {

        @Test
        @DisplayName("L2 Redis 缓存命中，不调外部 API")
        void l2CacheHit() {
            mockNonceSuccess();
            String cachedJson = "{\"userId\":\"" + TEST_USER_ID + "\",\"level\":\"L3\"}";
            when(valueOperations.get(startsWith("auth:identity:"))).thenReturn(cachedJson);

            assertEquals(TEST_USER_ID, authService.verify(TEST_AK, validTimestamp(), randomNonce(), "sig"));

            // 外部 API 未被调用
            verify(identityApiClient, never()).check(anyString(), anyString(), anyString(), anyString());
        }
    }

    // -----------------------------------------------------------------------
    // 调试模式
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("skip-verification 调试模式")
    class SkipVerification {

        @Test
        @DisplayName("开启后跳过全部校验，以 AK 作为 userId 返回")
        void skipReturnsAkAsUserId() {
            AkSkAuthService debugService = new AkSkAuthService(
                    redisTemplate, new ObjectMapper(), identityApiClient,
                    300L, 300L, 300L, 10000L, 3600L,
                    true  // skipVerification
            );

            String userId = debugService.verify(TEST_AK, "0", "any", "any");

            assertEquals(TEST_AK, userId);
            // Redis 和外部 API 均未被调用
            verifyNoInteractions(identityApiClient);
        }

        @Test
        @DisplayName("开启后 ak 为 null 仍然拒绝")
        void skipStillRejectsNullAk() {
            AkSkAuthService debugService = new AkSkAuthService(
                    redisTemplate, new ObjectMapper(), identityApiClient,
                    300L, 300L, 300L, 10000L, 3600L,
                    true
            );

            assertNull(debugService.verify(null, "0", "any", "any"));
        }
    }
}
