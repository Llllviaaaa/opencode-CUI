package com.opencode.cui.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the gw:pending:{ak} downlink message buffer queue in {@link RedisMessageBroker}.
 *
 * <p>All tests use Mockito to avoid requiring a live Redis instance, following the same
 * pattern as {@link RedisMessageBrokerTest}.
 */
@ExtendWith(MockitoExtension.class)
class PendingQueueTest {

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private RedisMessageListenerContainer listenerContainer;
    @Mock
    private ListOperations<String, String> listOperations;

    private RedisMessageBroker broker;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForList()).thenReturn(listOperations);
        broker = new RedisMessageBroker(redisTemplate, listenerContainer, new ObjectMapper());
    }

    // ==================== enqueuePending ====================

    @Test
    @DisplayName("enqueuePending 向 gw:pending:{ak} 追加消息并设置 TTL")
    void enqueuePending_shouldAddToList() {
        String ak = "agent-001";
        String message = "{\"type\":\"invoke\"}";
        Duration ttl = Duration.ofSeconds(60);

        broker.enqueuePending(ak, message, ttl);

        verify(listOperations).rightPush("gw:pending:agent-001", message);
        verify(redisTemplate).expire("gw:pending:agent-001", ttl);
    }

    @Test
    @DisplayName("enqueuePending null/blank 参数不执行任何操作")
    void enqueuePending_nullOrBlankParams_shouldDoNothing() {
        broker.enqueuePending(null, "{}", Duration.ofSeconds(60));
        broker.enqueuePending("", "{}", Duration.ofSeconds(60));
        broker.enqueuePending("agent-001", null, Duration.ofSeconds(60));

        verify(listOperations, never()).rightPush(any(), any());
        verify(redisTemplate, never()).expire(any(), any(Duration.class));
    }

    @Test
    @DisplayName("enqueuePending 多次入队后每次都刷新 TTL")
    void enqueuePending_multipleTimes_shouldPreserveOrder() {
        String ak = "agent-002";
        Duration ttl = Duration.ofSeconds(60);
        String msg1 = "{\"type\":\"invoke\",\"seq\":1}";
        String msg2 = "{\"type\":\"invoke\",\"seq\":2}";
        String msg3 = "{\"type\":\"invoke\",\"seq\":3}";

        broker.enqueuePending(ak, msg1, ttl);
        broker.enqueuePending(ak, msg2, ttl);
        broker.enqueuePending(ak, msg3, ttl);

        // Each enqueue should rightPush and refresh TTL
        verify(listOperations).rightPush("gw:pending:agent-002", msg1);
        verify(listOperations).rightPush("gw:pending:agent-002", msg2);
        verify(listOperations).rightPush("gw:pending:agent-002", msg3);
        verify(redisTemplate, times(3)).expire("gw:pending:agent-002", ttl);

        // Verify rightPush was called in FIFO order (3 times total)
        ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
        verify(listOperations, times(3)).rightPush(eq("gw:pending:agent-002"), valueCaptor.capture());
        List<String> capturedValues = valueCaptor.getAllValues();
        assertEquals(msg1, capturedValues.get(0), "First enqueued message should be at index 0");
        assertEquals(msg2, capturedValues.get(1), "Second enqueued message should be at index 1");
        assertEquals(msg3, capturedValues.get(2), "Third enqueued message should be at index 2");
    }

    // ==================== drainPending ====================

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("drainPending 通过 Lua 脚本原子获取全部并清空队列")
    void drainPending_shouldReturnAllAndClear() {
        String ak = "agent-003";
        List<String> expected = List.of("{\"type\":\"invoke\",\"seq\":1}", "{\"type\":\"invoke\",\"seq\":2}");

        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList()))
                .thenReturn(expected);

        List<String> result = broker.drainPending(ak);

        // Must have invoked a Lua script (LRANGE + DEL atomically)
        verify(redisTemplate).execute(any(DefaultRedisScript.class), eq(List.of("gw:pending:agent-003")));

        assertNotNull(result);
        assertEquals(2, result.size());
        assertEquals(expected.get(0), result.get(0));
        assertEquals(expected.get(1), result.get(1));
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("drainPending 队列为空时返回空列表")
    void drainPending_emptyQueue_shouldReturnEmptyList() {
        String ak = "agent-004";

        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList()))
                .thenReturn(List.of());

        List<String> result = broker.drainPending(ak);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("drainPending Redis 返回 null 时安全返回空列表")
    void drainPending_redisReturnsNull_shouldReturnEmptyList() {
        String ak = "agent-005";

        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList()))
                .thenReturn(null);

        List<String> result = broker.drainPending(ak);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("drainPending null/blank ak 返回空列表且不调用 Redis")
    void drainPending_nullOrBlankAk_shouldReturnEmptyListWithoutRedisCall() {
        List<String> r1 = broker.drainPending(null);
        List<String> r2 = broker.drainPending("");

        assertTrue(r1.isEmpty());
        assertTrue(r2.isEmpty());
        verify(redisTemplate, never()).execute(any(DefaultRedisScript.class), anyList());
    }
}
