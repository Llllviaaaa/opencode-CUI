package com.opencode.cui.skill.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.util.Arrays;
import java.util.List;

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
 */
@ExtendWith(MockitoExtension.class)
class RedisMessageBrokerTest {

    private static final String TOOL_SESSION_PREFIX = "ss:tool-session:";
    private static final String VERIFY_CHANNEL = "ss:relay:ss-az1-test";

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private RedisMessageListenerContainer listenerContainer;

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
    void forceReconnectListenerContainer_success_shouldStopThenStartAndReturnTrue() {
        // Mock PUBSUB NUMSUB → 1 subscriber (Lettuce raw 返回 [channel-bytes, count-Long])
        List<Object> result = Arrays.asList(VERIFY_CHANNEL.getBytes(), 1L);
        when(redisTemplate.execute(any(RedisCallback.class))).thenReturn(result);

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
    @DisplayName("physicalSubscriberCount 通过 PUBSUB NUMSUB 返回 channel 真实订阅者数")
    void physicalSubscriberCount_shouldQueryPubSubNumSub() {
        List<Object> result = Arrays.asList(VERIFY_CHANNEL.getBytes(), 3L);
        when(redisTemplate.execute(any(RedisCallback.class))).thenReturn(result);

        long count = broker.physicalSubscriberCount(VERIFY_CHANNEL);

        org.junit.jupiter.api.Assertions.assertEquals(3L, count);
    }

    @Test
    @DisplayName("physicalSubscriberCount: count 以 byte[] 形式返回时也能解析")
    void physicalSubscriberCount_byteArrayCount_shouldParse() {
        List<Object> result = Arrays.asList(VERIFY_CHANNEL.getBytes(), "5".getBytes());
        when(redisTemplate.execute(any(RedisCallback.class))).thenReturn(result);

        org.junit.jupiter.api.Assertions.assertEquals(5L, broker.physicalSubscriberCount(VERIFY_CHANNEL));
    }

    @Test
    @DisplayName("physicalSubscriberCount: null/blank channel → 0，不触达 Redis")
    void physicalSubscriberCount_nullOrBlank_returnsZero() {
        org.junit.jupiter.api.Assertions.assertEquals(0L, broker.physicalSubscriberCount(null));
        org.junit.jupiter.api.Assertions.assertEquals(0L, broker.physicalSubscriberCount(""));
        org.junit.jupiter.api.Assertions.assertEquals(0L, broker.physicalSubscriberCount("  "));
        verify(redisTemplate, never()).execute(any(RedisCallback.class));
    }
}
