package com.opencode.cui.skill.ws;

import com.opencode.cui.skill.service.RedisMessageBroker;
import com.opencode.cui.skill.service.SkillInstanceRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 用户 WS 注册表单元测试（Task 2.7）。
 *
 * <p>覆盖 RedisMessageBroker 中 Hash 引用计数相关方法：
 * registerUserWs / unregisterUserWs / getUserWsInstances / cleanupUserWsForInstance。
 */
@ExtendWith(MockitoExtension.class)
class UserWsRegistryTest {

    private static final String USER_ID = "user-001";
    private static final String INSTANCE_ID = "ss-az1-test";
    private static final String KEY = "ss:internal:user-ws:" + USER_ID;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private HashOperations<String, Object, Object> hashOps;

    private RedisMessageBroker broker;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForHash()).thenReturn(hashOps);
        // RedisMessageListenerContainer 在此测试中不使用，传 null 无影响
        broker = new RedisMessageBroker(redisTemplate, null);
    }

    @Test
    @DisplayName("registerUserWs 调用后应对 Hash field HINCRBY +1")
    void registerUserWs_shouldIncrementCount() {
        when(hashOps.increment(KEY, INSTANCE_ID, 1L)).thenReturn(1L);

        broker.registerUserWs(USER_ID, INSTANCE_ID);

        verify(hashOps).increment(KEY, INSTANCE_ID, 1L);
    }

    @Test
    @DisplayName("unregisterUserWs 计数 >0 时只做 HINCRBY -1，不执行 HDEL")
    void unregisterUserWs_shouldDecrementAndNotCleanupWhenPositive() {
        when(hashOps.increment(KEY, INSTANCE_ID, -1L)).thenReturn(1L);

        broker.unregisterUserWs(USER_ID, INSTANCE_ID);

        verify(hashOps).increment(KEY, INSTANCE_ID, -1L);
        verify(hashOps, never()).delete(eq(KEY), eq(INSTANCE_ID));
    }

    @Test
    @DisplayName("unregisterUserWs 计数降至 0 时应 HDEL 清理 field")
    void unregisterUserWs_shouldDecrementAndCleanup() {
        when(hashOps.increment(KEY, INSTANCE_ID, -1L)).thenReturn(0L);

        broker.unregisterUserWs(USER_ID, INSTANCE_ID);

        verify(hashOps).increment(KEY, INSTANCE_ID, -1L);
        verify(hashOps).delete(KEY, INSTANCE_ID);
    }

    @Test
    @DisplayName("unregisterUserWs 计数降至负数时也应 HDEL 清理 field")
    void unregisterUserWs_shouldCleanupWhenNegative() {
        when(hashOps.increment(KEY, INSTANCE_ID, -1L)).thenReturn(-1L);

        broker.unregisterUserWs(USER_ID, INSTANCE_ID);

        verify(hashOps).delete(KEY, INSTANCE_ID);
    }

    @Test
    @DisplayName("multipleConnections_sameInstance_shouldTrackCount：同实例多次注册计数递增")
    void multipleConnections_sameInstance_shouldTrackCount() {
        when(hashOps.increment(KEY, INSTANCE_ID, 1L)).thenReturn(1L, 2L, 3L);

        broker.registerUserWs(USER_ID, INSTANCE_ID);
        broker.registerUserWs(USER_ID, INSTANCE_ID);
        broker.registerUserWs(USER_ID, INSTANCE_ID);

        // 验证 HINCRBY +1 被调用三次
        verify(hashOps, org.mockito.Mockito.times(3)).increment(KEY, INSTANCE_ID, 1L);
    }

    @Test
    @DisplayName("getUserWsInstances 应返回 Hash HKEYS 中的所有实例 ID")
    void getUserWsInstances_shouldReturnActiveInstances() {
        when(hashOps.keys(KEY)).thenReturn(Set.of("ss-az1", "ss-az2"));

        Set<String> instances = broker.getUserWsInstances(USER_ID);

        assertEquals(Set.of("ss-az1", "ss-az2"), instances);
    }

    @Test
    @DisplayName("getUserWsInstances 无连接时应返回空集合")
    void getUserWsInstances_shouldReturnEmptyWhenNoConnections() {
        when(hashOps.keys(KEY)).thenReturn(Set.of());

        Set<String> instances = broker.getUserWsInstances(USER_ID);

        assertTrue(instances.isEmpty());
    }

    @Test
    @DisplayName("cleanupOnStartup_shouldRemoveStaleEntries：扫描到残留 field 时执行 HDEL")
    void cleanupOnStartup_shouldRemoveStaleEntries() {
        String staleKey = "ss:internal:user-ws:stale-user";

        @SuppressWarnings("unchecked")
        Cursor<String> cursor = mock(Cursor.class);
        when(cursor.hasNext()).thenReturn(true, false);
        when(cursor.next()).thenReturn(staleKey);

        when(redisTemplate.scan(any(ScanOptions.class))).thenReturn(cursor);
        when(hashOps.get(staleKey, INSTANCE_ID)).thenReturn("2");

        broker.cleanupUserWsForInstance(INSTANCE_ID);

        verify(hashOps).delete(staleKey, INSTANCE_ID);
    }

    @Test
    @DisplayName("cleanupOnStartup 无残留 field 时不执行任何 HDEL")
    void cleanupOnStartup_shouldSkipWhenNoStaleEntries() {
        String keyNoStale = "ss:internal:user-ws:clean-user";

        @SuppressWarnings("unchecked")
        Cursor<String> cursor = mock(Cursor.class);
        when(cursor.hasNext()).thenReturn(true, false);
        when(cursor.next()).thenReturn(keyNoStale);

        when(redisTemplate.scan(any(ScanOptions.class))).thenReturn(cursor);
        when(hashOps.get(keyNoStale, INSTANCE_ID)).thenReturn(null);

        broker.cleanupUserWsForInstance(INSTANCE_ID);

        verify(hashOps, never()).delete(any(), any());
    }
}
