package com.opencode.cui.skill.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisHashCommands;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * RedisMessageBroker 投递相关方法测试：
 * <ul>
 *   <li>invoke-source TTL key</li>
 *   <li>新的 owner-only held-by hash 接口（heldByPutAll / heldByDeleteField / heldByDeleteKey）</li>
 *   <li>pipeline HGET 批量读取多实例 held-by</li>
 *   <li>活实例花名册 ZSET（add / remove / range / prune）</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class RedisMessageBrokerDeliveryTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private RedisMessageListenerContainer listenerContainer;
    @Mock private ValueOperations<String, String> valueOps;
    @Mock private HashOperations<String, Object, Object> hashOps;
    @Mock private ZSetOperations<String, String> zsetOps;
    @InjectMocks private RedisMessageBroker broker;

    @Test
    @DisplayName("setInvokeSource writes key with TTL")
    void setInvokeSource() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        broker.setInvokeSource("12345", "IM", 300);
        verify(valueOps).set("invoke-source:12345", "IM", 300, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("getInvokeSource reads key")
    void getInvokeSource() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("invoke-source:12345")).thenReturn("EXTERNAL");
        assertEquals("EXTERNAL", broker.getInvokeSource("12345"));
    }

    @Test
    @DisplayName("expireInvokeSource renews TTL")
    void expireInvokeSource() {
        broker.expireInvokeSource("12345", 300);
        verify(redisTemplate).expire("invoke-source:12345", 300, TimeUnit.SECONDS);
    }

    // ==================== held-by hash（owner-only） ====================

    @Test
    @DisplayName("heldByPutAll: 整批写入 {domain→count} 并续 TTL")
    void heldByPutAll_writesAndExpires() {
        when(redisTemplate.opsForHash()).thenReturn(hashOps);
        Map<String, Integer> snapshot = Map.of("im", 3, "crm", 1);
        broker.heldByPutAll("ss-pod-1", snapshot, 30);
        verify(hashOps).putAll(eq("external-ws:held-by:ss-pod-1"),
                eq(Map.of("im", "3", "crm", "1")));
        verify(redisTemplate).expire("external-ws:held-by:ss-pod-1", 30, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("heldByPutAll: empty snapshot 不触达 Redis")
    void heldByPutAll_emptySnapshotIsNoOp() {
        broker.heldByPutAll("ss-pod-1", Map.of(), 30);
        broker.heldByPutAll("ss-pod-1", null, 30);
        broker.heldByPutAll(null, Map.of("im", 1), 30);
        verifyNoInteractions(hashOps);
    }

    @Test
    @DisplayName("heldByDeleteField: 删除单 domain 字段")
    void heldByDeleteField() {
        when(redisTemplate.opsForHash()).thenReturn(hashOps);
        broker.heldByDeleteField("ss-pod-1", "im");
        verify(hashOps).delete("external-ws:held-by:ss-pod-1", "im");
    }

    @Test
    @DisplayName("heldByDeleteKey: 整 key DELETE（@PreDestroy / empty snapshot 路径）")
    void heldByDeleteKey() {
        broker.heldByDeleteKey("ss-pod-1");
        verify(redisTemplate).delete("external-ws:held-by:ss-pod-1");
    }

    @Test
    @DisplayName("heldByDeleteKey: null/blank 不触达 Redis")
    void heldByDeleteKey_nullOrBlank() {
        broker.heldByDeleteKey(null);
        broker.heldByDeleteKey("");
        broker.heldByDeleteKey("   ");
        verify(redisTemplate, never()).delete(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("heldByGetBatch: pipeline HGET 多实例，按候选顺序返回 count")
    @SuppressWarnings("unchecked")
    void heldByGetBatch_pipelineHGet() {
        // 模拟 executePipelined 调用 callback 并返回结果列表（与 instanceIds 同序）
        when(redisTemplate.executePipelined(any(RedisCallback.class))).thenAnswer(invocation -> {
            RedisCallback<?> cb = invocation.getArgument(0);
            // 真实运行 callback 以覆盖 hashCommands().hGet(...) 调用路径
            RedisConnection conn = org.mockito.Mockito.mock(RedisConnection.class);
            RedisHashCommands hashCmds = org.mockito.Mockito.mock(RedisHashCommands.class);
            when(conn.hashCommands()).thenReturn(hashCmds);
            cb.doInRedis(conn);
            // pipeline 返回 byte[]/String 解码后的值；这里直接给字符串
            return Arrays.asList("0", "3", null);
        });
        Map<String, Integer> result = broker.heldByGetBatch(
                List.of("ss-pod-1", "ss-pod-2", "ss-pod-3"), "im");
        assertEquals(2, result.size());
        assertEquals(0, result.get("ss-pod-1"));
        assertEquals(3, result.get("ss-pod-2"));
        assertFalse(result.containsKey("ss-pod-3")); // HGET 返回 null 不入结果
    }

    @Test
    @DisplayName("heldByGetBatch: 空 instanceIds 直接返回 empty")
    void heldByGetBatch_emptyCandidates() {
        Map<String, Integer> result = broker.heldByGetBatch(List.of(), "im");
        assertTrue(result.isEmpty());
        verifyNoInteractions(redisTemplate);
    }

    @Test
    @DisplayName("publishToExternalRelay: PUBLISH 到目标 external relay 并返回订阅者数")
    void publishToExternalRelay_returnsSubscriberCount() {
        when(redisTemplate.convertAndSend("ss:external-relay:ss-pod-2", "{}")).thenReturn(2L);

        long receivers = broker.publishToExternalRelay("ss-pod-2", "{}");

        assertEquals(2L, receivers);
        verify(redisTemplate).convertAndSend("ss:external-relay:ss-pod-2", "{}");
    }

    // ==================== instance:roster ZSET ====================

    @Test
    @DisplayName("addToInstanceRoster: ZADD instance:roster nowMs instanceId")
    void addToInstanceRoster() {
        when(redisTemplate.opsForZSet()).thenReturn(zsetOps);
        broker.addToInstanceRoster("ss-pod-1", 1_700_000_000_000L);
        verify(zsetOps).add("instance:roster", "ss-pod-1", 1_700_000_000_000d);
    }

    @Test
    @DisplayName("removeFromInstanceRoster: ZREM instance:roster instanceId")
    void removeFromInstanceRoster() {
        when(redisTemplate.opsForZSet()).thenReturn(zsetOps);
        broker.removeFromInstanceRoster("ss-pod-1");
        verify(zsetOps).remove("instance:roster", "ss-pod-1");
    }

    @Test
    @DisplayName("rangeAliveInstances: ZRANGEBYSCORE cutoffMs +inf 返回活实例")
    void rangeAliveInstances() {
        when(redisTemplate.opsForZSet()).thenReturn(zsetOps);
        Set<String> alive = new LinkedHashSet<>(List.of("ss-pod-1", "ss-pod-2"));
        when(zsetOps.rangeByScore(eq("instance:roster"), anyDouble(), eq(Double.POSITIVE_INFINITY)))
                .thenReturn(alive);
        List<String> result = broker.rangeAliveInstances(1_700_000_000_000L);
        assertEquals(List.of("ss-pod-1", "ss-pod-2"), result);
    }

    @Test
    @DisplayName("rangeAliveInstances: ZSET 不存在/空 返回 empty list")
    void rangeAliveInstances_empty() {
        when(redisTemplate.opsForZSet()).thenReturn(zsetOps);
        when(zsetOps.rangeByScore(eq("instance:roster"), anyDouble(), eq(Double.POSITIVE_INFINITY)))
                .thenReturn(null);
        assertTrue(broker.rangeAliveInstances(0L).isEmpty());
    }

    @Test
    @DisplayName("pruneRoster: ZREMRANGEBYSCORE instance:roster 0 beforeMs")
    void pruneRoster() {
        when(redisTemplate.opsForZSet()).thenReturn(zsetOps);
        broker.pruneRoster(1_700_000_000_000L);
        verify(zsetOps).removeRangeByScore("instance:roster", 0d, 1_700_000_000_000d);
    }
}
