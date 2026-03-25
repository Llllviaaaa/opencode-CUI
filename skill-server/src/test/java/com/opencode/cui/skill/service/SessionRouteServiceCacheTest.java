package com.opencode.cui.skill.service;

import com.opencode.cui.skill.model.SessionRoute;
import com.opencode.cui.skill.repository.SessionRouteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SessionRouteService Redis 缓存层单元测试。
 * 覆盖：getOwnerInstance、createRoute、closeRoute 的缓存读写/删除行为。
 */
@ExtendWith(MockitoExtension.class)
class SessionRouteServiceCacheTest {

    private static final String INSTANCE_ID = "ss-az1-1";
    private static final int TTL_SECONDS = 1800;
    private static final String CACHE_PREFIX = "ss:internal:session:";

    @Mock
    private SessionRouteRepository repository;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;

    private SessionRouteService service;

    @BeforeEach
    void setUp() {
        // lenient: not all tests call opsForValue() (e.g. closeRoute uses delete directly)
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
        service = new SessionRouteService(repository, redisTemplate, INSTANCE_ID, TTL_SECONDS);
    }

    // ==================== getOwnerInstance ====================

    @Nested
    @DisplayName("getOwnerInstance - Redis 缓存命中")
    class GetOwnerInstanceCacheHit {

        @Test
        @DisplayName("Redis 命中时直接返回，不查 MySQL")
        void getOwnerInstance_redisCacheHit_shouldNotQueryMySQL() {
            String sessionId = "12345";
            when(valueOps.get(CACHE_PREFIX + sessionId)).thenReturn(INSTANCE_ID);

            String result = service.getOwnerInstance(sessionId);

            assertEquals(INSTANCE_ID, result);
            verify(repository, never()).findByWelinkSessionId(any());
        }
    }

    @Nested
    @DisplayName("getOwnerInstance - Redis 未命中")
    class GetOwnerInstanceCacheMiss {

        @Test
        @DisplayName("Redis 未命中时查 MySQL 并回填缓存")
        void getOwnerInstance_redisMiss_shouldQueryMySQLAndBackfill() {
            String sessionId = "12345";
            when(valueOps.get(CACHE_PREFIX + sessionId)).thenReturn(null);

            SessionRoute route = new SessionRoute();
            route.setSourceInstance(INSTANCE_ID);
            route.setStatus("ACTIVE");
            when(repository.findByWelinkSessionId(12345L)).thenReturn(route);

            String result = service.getOwnerInstance(sessionId);

            assertEquals(INSTANCE_ID, result);
            verify(repository).findByWelinkSessionId(12345L);
            verify(valueOps).set(
                    eq(CACHE_PREFIX + sessionId),
                    eq(INSTANCE_ID),
                    eq(Duration.ofSeconds(TTL_SECONDS)));
        }

        @Test
        @DisplayName("Redis 未命中且 MySQL 无 ACTIVE 路由时返回 null")
        void getOwnerInstance_noRoute_shouldReturnNull() {
            String sessionId = "12345";
            when(valueOps.get(CACHE_PREFIX + sessionId)).thenReturn(null);
            when(repository.findByWelinkSessionId(12345L)).thenReturn(null);

            String result = service.getOwnerInstance(sessionId);

            assertNull(result);
            verify(valueOps, never()).set(any(), any(), any(Duration.class));
        }

        @Test
        @DisplayName("MySQL 返回 CLOSED 路由时返回 null 且不回填缓存")
        void getOwnerInstance_closedRoute_shouldReturnNull() {
            String sessionId = "12345";
            when(valueOps.get(CACHE_PREFIX + sessionId)).thenReturn(null);

            SessionRoute route = new SessionRoute();
            route.setSourceInstance(INSTANCE_ID);
            route.setStatus("CLOSED");
            when(repository.findByWelinkSessionId(12345L)).thenReturn(route);

            String result = service.getOwnerInstance(sessionId);

            assertNull(result);
            verify(valueOps, never()).set(any(), any(), any(Duration.class));
        }

        @Test
        @DisplayName("Redis 异常时降级查 MySQL 并返回结果")
        void getOwnerInstance_redisException_shouldFallbackToMySQL() {
            String sessionId = "12345";
            when(valueOps.get(CACHE_PREFIX + sessionId)).thenThrow(new RuntimeException("Redis unavailable"));

            SessionRoute route = new SessionRoute();
            route.setSourceInstance(INSTANCE_ID);
            route.setStatus("ACTIVE");
            when(repository.findByWelinkSessionId(12345L)).thenReturn(route);

            String result = service.getOwnerInstance(sessionId);

            assertEquals(INSTANCE_ID, result);
        }
    }

    // ==================== createRoute ====================

    @Nested
    @DisplayName("createRoute - 写 MySQL + Redis")
    class CreateRouteCache {

        @Test
        @DisplayName("createRoute 成功时同时写 MySQL 和 Redis")
        void createRoute_shouldWriteBothMySQLAndRedis() {
            service.createRoute("ak-1", 12345L, "skill-server", "user-123");

            verify(repository).insert(any());
            verify(valueOps).set(
                    eq(CACHE_PREFIX + "12345"),
                    eq(INSTANCE_ID),
                    eq(Duration.ofSeconds(TTL_SECONDS)));
        }

        @Test
        @DisplayName("createRoute 重复插入时不写 Redis")
        void createRoute_duplicateKey_shouldNotWriteRedis() {
            when(repository.insert(any())).thenThrow(
                    new org.springframework.dao.DuplicateKeyException("Duplicate entry"));

            service.createRoute("ak-1", 12345L, "skill-server", "user-123");

            verify(valueOps, never()).set(any(), any(), any(Duration.class));
        }
    }

    // ==================== closeRoute ====================

    @Nested
    @DisplayName("closeRoute - 删 Redis")
    class CloseRouteCache {

        @Test
        @DisplayName("closeRoute 成功时删除 Redis 缓存")
        void closeRoute_shouldDeleteRedis() {
            service.closeRoute(12345L, "skill-server");

            verify(repository).updateStatus(12345L, "skill-server", "CLOSED");
            verify(redisTemplate).delete(CACHE_PREFIX + "12345");
        }

        @Test
        @DisplayName("closeRoute Redis 删除失败时不影响 MySQL 操作完成")
        void closeRoute_redisDeleteFails_shouldNotPropagateException() {
            when(redisTemplate.delete(CACHE_PREFIX + "12345")).thenThrow(
                    new RuntimeException("Redis unavailable"));

            // Should not throw
            service.closeRoute(12345L, "skill-server");

            verify(repository).updateStatus(12345L, "skill-server", "CLOSED");
        }
    }
}
