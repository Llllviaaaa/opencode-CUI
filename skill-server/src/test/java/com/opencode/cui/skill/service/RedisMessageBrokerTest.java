package com.opencode.cui.skill.service;

import io.lettuce.core.RedisFuture;
import io.lettuce.core.cluster.api.async.RedisClusterAsyncCommands;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.lettuce.LettuceConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * RedisMessageBroker 单元测试：toolSessionId 反查缓存失效 + pub/sub silent-failure 自愈。
 *
 * <p>{@code physicalSubscriberCount_*} 测试 mock 深到 Lettuce {@code pubsubNumsub} 这一层，
 * 而非 Spring callback 的包装层，以确保 Lettuce 接口本身的方法签名 / 返回类型变化能被
 * 测试发现（旧版本 mock {@code redisTemplate.execute(callback)} 让 callback 完全没执行，
 * 是导致 {@code UnsupportedOperationException} 在生产爆炸但测试全绿的根因）。
 */
@ExtendWith(MockitoExtension.class)
class RedisMessageBrokerTest {

    private static final String TOOL_SESSION_PREFIX = "ss:tool-session:";
    private static final String VERIFY_CHANNEL = "ss:relay:ss-az1-test";

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private RedisMessageListenerContainer listenerContainer;
    @Mock
    private LettuceConnection lettuceConnection;
    /**
     * 与 {@link LettuceConnection#getNativeConnection()} 实际返回类型对齐
     * （{@link RedisClusterAsyncCommands} extends {@code BaseRedisAsyncCommands}）。
     */
    @Mock
    private RedisClusterAsyncCommands<byte[], byte[]> asyncCommands;
    @Mock
    @SuppressWarnings("rawtypes")
    private RedisFuture pubsubNumsubFuture;

    private RedisMessageBroker broker;

    @BeforeEach
    void setUp() {
        broker = new RedisMessageBroker(redisTemplate, listenerContainer);
    }

    @Test
    @DisplayName("deleteToolSessionMapping 正常 key 调用 redisTemplate.delete(prefix+key) 一次")
    void deleteToolSessionMappingDeletesPrefixedKey() {
        broker.deleteToolSessionMapping("T1");

        verify(redisTemplate).delete(TOOL_SESSION_PREFIX + "T1");
    }

    @Test
    @DisplayName("deleteToolSessionMapping null/blank → no-op，不触达 redisTemplate")
    void deleteToolSessionMappingNullOrBlankIsNoOp() {
        broker.deleteToolSessionMapping(null);
        broker.deleteToolSessionMapping("");
        broker.deleteToolSessionMapping("   ");

        verify(redisTemplate, never()).delete(org.mockito.ArgumentMatchers.anyString());
        verifyNoInteractions(listenerContainer);
    }

    // ==================== forceReconnectListenerContainer ====================

    @Test
    @DisplayName("forceReconnectListenerContainer 调用 stop() + start()，重连后 NUMSUB>0 返回 true")
    void forceReconnectListenerContainer_success_shouldStopThenStartAndReturnTrue() throws Exception {
        // Mock PUBSUB NUMSUB → 1 subscriber，深 mock Lettuce native async API 路径
        Map<byte[], Long> numsubResult = new LinkedHashMap<>();
        numsubResult.put(VERIFY_CHANNEL.getBytes(), 1L);
        stubPubsubNumsub(numsubResult);
        stubExecuteToInvokeCallback();

        boolean ok = broker.forceReconnectListenerContainer(VERIFY_CHANNEL, 1000L);

        assertTrue(ok);
        InOrder order = inOrder(listenerContainer);
        order.verify(listenerContainer).stop();
        order.verify(listenerContainer).start();
    }

    @Test
    @DisplayName("forceReconnectListenerContainer 重启异常 → 返回 false")
    void forceReconnectListenerContainer_stopThrows_shouldReturnFalse() {
        org.mockito.Mockito.doThrow(new RuntimeException("redis down"))
                .when(listenerContainer).stop();

        boolean ok = broker.forceReconnectListenerContainer(VERIFY_CHANNEL, 100L);

        assertFalse(ok);
    }

    @Test
    @DisplayName("physicalSubscriberCount 通过 Lettuce native pubsubNumsub 返回 channel 真实订阅者数")
    void physicalSubscriberCount_shouldQueryPubSubNumSub() throws Exception {
        Map<byte[], Long> numsubResult = new LinkedHashMap<>();
        numsubResult.put(VERIFY_CHANNEL.getBytes(), 3L);
        stubPubsubNumsub(numsubResult);
        stubExecuteToInvokeCallback();

        long count = broker.physicalSubscriberCount(VERIFY_CHANNEL);

        org.junit.jupiter.api.Assertions.assertEquals(3L, count);
    }

    @Test
    @DisplayName("physicalSubscriberCount: 0 订阅者时 entry 仍在 map 里（value=0L），返回 0")
    void physicalSubscriberCount_zeroSubscribers_returnsZero() throws Exception {
        Map<byte[], Long> numsubResult = new LinkedHashMap<>();
        numsubResult.put(VERIFY_CHANNEL.getBytes(), 0L);
        stubPubsubNumsub(numsubResult);
        stubExecuteToInvokeCallback();

        org.junit.jupiter.api.Assertions.assertEquals(0L, broker.physicalSubscriberCount(VERIFY_CHANNEL));
    }

    @Test
    @DisplayName("physicalSubscriberCount: pubsubNumsub 抛 TimeoutException → 降级返回 0L")
    void physicalSubscriberCount_timeout_returnsZero() throws Exception {
        when(lettuceConnection.getNativeConnection()).thenReturn(asyncCommands);
        when(asyncCommands.pubsubNumsub(any(byte[].class))).thenReturn(pubsubNumsubFuture);
        when(pubsubNumsubFuture.get(2L, TimeUnit.SECONDS)).thenThrow(new TimeoutException("test timeout"));
        stubExecuteToInvokeCallback();

        org.junit.jupiter.api.Assertions.assertEquals(0L, broker.physicalSubscriberCount(VERIFY_CHANNEL));
    }

    @Test
    @DisplayName("physicalSubscriberCount: null/blank channel → 0，不触达 Redis")
    void physicalSubscriberCount_nullOrBlank_returnsZero() {
        org.junit.jupiter.api.Assertions.assertEquals(0L, broker.physicalSubscriberCount(null));
        org.junit.jupiter.api.Assertions.assertEquals(0L, broker.physicalSubscriberCount(""));
        org.junit.jupiter.api.Assertions.assertEquals(0L, broker.physicalSubscriberCount("  "));
        verify(redisTemplate, never()).execute(any(RedisCallback.class));
    }

    // ==================== 测试辅助方法 ====================

    /**
     * 让 {@code redisTemplate.execute(callback)} 真实执行 callback（传入 mock 的
     * {@link LettuceConnection}），从而覆盖到我们新的 cast → getNativeConnection →
     * pubsubNumsub 路径。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void stubExecuteToInvokeCallback() {
        when(redisTemplate.execute(any(RedisCallback.class))).thenAnswer(inv -> {
            RedisCallback callback = inv.getArgument(0);
            return callback.doInRedis(lettuceConnection);
        });
    }

    /**
     * Mock {@code LettuceConnection.getNativeConnection()} → {@link RedisClusterAsyncCommands}（与
     * 真实返回类型对齐），然后让 {@code pubsubNumsub(...)} 返回提供的 map。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void stubPubsubNumsub(Map<byte[], Long> result) throws Exception {
        when(lettuceConnection.getNativeConnection()).thenReturn(asyncCommands);
        when(asyncCommands.pubsubNumsub(any(byte[].class))).thenReturn(pubsubNumsubFuture);
        when(pubsubNumsubFuture.get(2L, TimeUnit.SECONDS)).thenReturn(result);
    }
}
