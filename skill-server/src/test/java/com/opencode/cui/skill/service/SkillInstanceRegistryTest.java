package com.opencode.cui.skill.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
/** SkillInstanceRegistry 单元测试：验证 SS 实例心跳写入、探活查询、自检自重连和销毁逻辑。 */
class SkillInstanceRegistryTest {

    private static final String INSTANCE_ID = "ss-az1-test";
    private static final String REDIS_KEY = "ss:internal:instance:" + INSTANCE_ID;
    private static final String RELAY_CHANNEL = "ss:relay:" + INSTANCE_ID;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private RedisMessageBroker redisMessageBroker;

    private SkillInstanceRegistry registry;

    @BeforeEach
    void setUp() {
        // lenient：仅 register/refresh 等写入场景需要 opsForValue；其他测试无需此 stub
        org.mockito.Mockito.lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        registry = new SkillInstanceRegistry(redisTemplate, redisMessageBroker, INSTANCE_ID);
    }

    @Test
    @DisplayName("register 启动时向 Redis 写入心跳 key，TTL 30s")
    void register_shouldWriteRedisKey() {
        registry.register();

        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(valueOperations).set(eq(REDIS_KEY), eq("alive"), ttlCaptor.capture());
        assertEquals(Duration.ofSeconds(30), ttlCaptor.getValue());
    }

    @Test
    @DisplayName("isInstanceAlive 目标实例存在时返回 true")
    void isInstanceAlive_existing_shouldReturnTrue() {
        when(redisTemplate.hasKey(REDIS_KEY)).thenReturn(Boolean.TRUE);

        assertTrue(registry.isInstanceAlive(INSTANCE_ID));
    }

    @Test
    @DisplayName("isInstanceAlive 目标实例不存在时返回 false")
    void isInstanceAlive_missing_shouldReturnFalse() {
        when(redisTemplate.hasKey(REDIS_KEY)).thenReturn(Boolean.FALSE);

        assertFalse(registry.isInstanceAlive(INSTANCE_ID));
    }

    @Test
    @DisplayName("destroy 关闭时删除 Redis 心跳 key")
    void destroy_shouldDeleteKey() {
        registry.destroy();

        verify(redisTemplate).delete(REDIS_KEY);
    }

    @Test
    @DisplayName("refreshHeartbeat: NUMSUB>0 跳过重连，正常写入心跳")
    void refreshHeartbeat_subscriptionAlive_shouldRenewTtlAndSkipReconnect() {
        when(redisMessageBroker.physicalSubscriberCount(RELAY_CHANNEL)).thenReturn(1L);

        registry.refreshHeartbeat();

        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(valueOperations).set(eq(REDIS_KEY), eq("alive"), ttlCaptor.capture());
        assertEquals(Duration.ofSeconds(30), ttlCaptor.getValue());
        verify(redisMessageBroker, never()).forceReconnectListenerContainer(
                org.mockito.ArgumentMatchers.anyString(), anyLong());
    }

    @Test
    @DisplayName("refreshHeartbeat: NUMSUB=0 触发重连；重连成功后写心跳")
    void refreshHeartbeat_subscriptionDead_reconnectSucceeds_shouldWriteHeartbeat() {
        when(redisMessageBroker.physicalSubscriberCount(RELAY_CHANNEL)).thenReturn(0L);
        when(redisMessageBroker.forceReconnectListenerContainer(eq(RELAY_CHANNEL), anyLong()))
                .thenReturn(true);

        registry.refreshHeartbeat();

        verify(redisMessageBroker).forceReconnectListenerContainer(eq(RELAY_CHANNEL), anyLong());
        verify(valueOperations).set(eq(REDIS_KEY), eq("alive"),
                org.mockito.ArgumentMatchers.any(Duration.class));
    }

    @Test
    @DisplayName("refreshHeartbeat: NUMSUB=0 重连失败 → 跳过心跳写入，让 TTL 过期触发 takeover")
    void refreshHeartbeat_subscriptionDead_reconnectFails_shouldSkipHeartbeat() {
        when(redisMessageBroker.physicalSubscriberCount(RELAY_CHANNEL)).thenReturn(0L);
        when(redisMessageBroker.forceReconnectListenerContainer(eq(RELAY_CHANNEL), anyLong()))
                .thenReturn(false);

        registry.refreshHeartbeat();

        verify(redisMessageBroker).forceReconnectListenerContainer(eq(RELAY_CHANNEL), anyLong());
        verify(valueOperations, never()).set(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(Duration.class));
    }

    @Test
    @DisplayName("getInstanceId 返回注入的实例 ID")
    void getInstanceId_shouldReturnInjectedId() {
        assertEquals(INSTANCE_ID, registry.getInstanceId());
    }
}
