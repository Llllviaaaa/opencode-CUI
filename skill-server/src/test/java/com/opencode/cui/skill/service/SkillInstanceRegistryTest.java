package com.opencode.cui.skill.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.scheduling.TaskScheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ScheduledFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
/** SkillInstanceRegistry 单元测试：验证 SS 实例心跳写入、探活查询、自检自重连、调度时机和销毁逻辑。 */
class SkillInstanceRegistryTest {

    private static final String INSTANCE_ID = "ss-az1-test";
    private static final String REDIS_KEY = "ss:internal:instance:" + INSTANCE_ID;
    private static final String RELAY_CHANNEL = "ss:relay:" + INSTANCE_ID;
    private static final long REFRESH_INTERVAL_MS = 10_000L;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private RedisMessageBroker redisMessageBroker;

    @Mock
    private TaskScheduler taskScheduler;

    @Mock
    @SuppressWarnings("rawtypes")
    private ScheduledFuture scheduledFuture;

    private SkillInstanceRegistry registry;

    @BeforeEach
    void setUp() {
        // lenient：仅 register/refresh 等写入场景需要 opsForValue；其他测试无需此 stub
        org.mockito.Mockito.lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        registry = new SkillInstanceRegistry(redisTemplate, redisMessageBroker, taskScheduler,
                INSTANCE_ID, REFRESH_INTERVAL_MS);
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
    @DisplayName("register 写心跳时顺手维护花名册：ZADD instance:roster + lazy GC")
    void register_shouldMaintainRoster() {
        registry.register();

        verify(redisMessageBroker).addToInstanceRoster(eq(INSTANCE_ID), anyLong());
        // pruneRoster 触发：lazy GC 60s 前的过期实例
        verify(redisMessageBroker).pruneRoster(anyLong());
    }

    @Test
    @DisplayName("listAliveInstances 用 now-30s cutoff 调 broker.rangeAliveInstances")
    void listAliveInstances_usesCutoff() {
        when(redisMessageBroker.rangeAliveInstances(anyLong()))
                .thenReturn(List.of("ss-pod-1", "ss-pod-2"));

        List<String> alive = registry.listAliveInstances();

        assertEquals(List.of("ss-pod-1", "ss-pod-2"), alive);
        ArgumentCaptor<Long> cutoffCap = ArgumentCaptor.forClass(Long.class);
        verify(redisMessageBroker).rangeAliveInstances(cutoffCap.capture());
        // cutoff 应在 now - 30s 附近（±5s 容差，避免 CI 抖动）
        long expected = System.currentTimeMillis() - 30_000L;
        long actual = cutoffCap.getValue();
        assertTrue(Math.abs(actual - expected) < 5_000L,
                "cutoff should be ~now-30s, got delta=" + (actual - expected));
    }

    @Test
    @DisplayName("listAliveInstances broker 返回 empty/null 时返回 empty list（L3 降级）")
    void listAliveInstances_emptyFromBroker() {
        when(redisMessageBroker.rangeAliveInstances(anyLong())).thenReturn(List.of());
        assertTrue(registry.listAliveInstances().isEmpty());
    }

    @Test
    @DisplayName("register 不会注册定时任务到 TaskScheduler（只在 ApplicationReadyEvent 后才调度）")
    void register_shouldNotRegisterScheduledTask() {
        registry.register();

        verifyNoInteractions(taskScheduler);
    }

    @Test
    @DisplayName("ApplicationReadyEvent 触发后才注册 scheduleWithFixedDelay；首次执行延迟 = interval（不立即 fire）")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void startScheduling_onlyRegistersAfterApplicationReadyEvent() {
        // 事件触发前：scheduler 没收到任何调用
        verifyNoInteractions(taskScheduler);

        when(taskScheduler.scheduleWithFixedDelay(
                any(Runnable.class), any(Instant.class), any(Duration.class)))
                .thenReturn(scheduledFuture);

        Instant before = Instant.now();
        ApplicationReadyEvent event = org.mockito.Mockito.mock(ApplicationReadyEvent.class);
        registry.startScheduling(event);
        Instant after = Instant.now();

        ArgumentCaptor<Instant> startCaptor = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Duration> intervalCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(taskScheduler, times(1)).scheduleWithFixedDelay(
                any(Runnable.class), startCaptor.capture(), intervalCaptor.capture());

        // 首次执行时间应在 [before + interval, after + interval] 之间，给订阅 settle 留窗口
        Instant firstRun = startCaptor.getValue();
        assertTrue(!firstRun.isBefore(before.plusMillis(REFRESH_INTERVAL_MS)),
                "firstRun should be >= before + interval");
        assertTrue(!firstRun.isAfter(after.plusMillis(REFRESH_INTERVAL_MS)),
                "firstRun should be <= after + interval");
        assertEquals(Duration.ofMillis(REFRESH_INTERVAL_MS), intervalCaptor.getValue());
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
    @DisplayName("destroy 关闭时删除 Redis 心跳 key，并取消已注册的调度任务")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void destroy_shouldDeleteKeyAndCancelScheduledFuture() {
        // 先模拟启动期：调度任务已注册
        when(taskScheduler.scheduleWithFixedDelay(
                any(Runnable.class), any(Instant.class), any(Duration.class)))
                .thenReturn(scheduledFuture);
        ApplicationReadyEvent event = org.mockito.Mockito.mock(ApplicationReadyEvent.class);
        registry.startScheduling(event);

        registry.destroy();

        verify(scheduledFuture).cancel(false);
        verify(redisTemplate).delete(REDIS_KEY);
        // 同时从活实例花名册移除自己（graceful 路径）
        verify(redisMessageBroker).removeFromInstanceRoster(INSTANCE_ID);
    }

    @Test
    @DisplayName("destroy 在未注册调度任务时也能安全删除心跳 key")
    void destroy_withoutScheduledFuture_shouldStillDeleteKey() {
        registry.destroy();

        verify(redisTemplate).delete(REDIS_KEY);
        verifyNoInteractions(taskScheduler);
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
