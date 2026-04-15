package com.opencode.cui.skill.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RedisMessageBrokerDeliveryTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private RedisMessageListenerContainer listenerContainer;
    @Mock private ValueOperations<String, String> valueOps;
    @Mock private HashOperations<String, Object, Object> hashOps;
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

    @Test
    @DisplayName("registerWsConnection sets hash field and expires key")
    void registerWsConnection() {
        when(redisTemplate.opsForHash()).thenReturn(hashOps);
        broker.registerWsConnection("im", "ss-pod-1", 3, 30);
        verify(hashOps).put("external-ws:registry:im", "ss-pod-1", "3");
        verify(redisTemplate).expire("external-ws:registry:im", 30, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("unregisterWsConnection removes hash field")
    void unregisterWsConnection() {
        when(redisTemplate.opsForHash()).thenReturn(hashOps);
        broker.unregisterWsConnection("im", "ss-pod-1");
        verify(hashOps).delete("external-ws:registry:im", "ss-pod-1");
    }

    @Test
    @DisplayName("getWsRegistry returns all entries")
    @SuppressWarnings("unchecked")
    void getWsRegistry() {
        when(redisTemplate.opsForHash()).thenReturn(hashOps);
        when(hashOps.entries("external-ws:registry:im")).thenReturn(
                Map.of("ss-pod-1", "3", "ss-pod-2", "1"));
        Map<String, String> result = broker.getWsRegistry("im");
        assertEquals(2, result.size());
        assertEquals("3", result.get("ss-pod-1"));
    }

    @Test
    @DisplayName("expireWsRegistry renews TTL on registry key")
    void expireWsRegistry() {
        broker.expireWsRegistry("im", 30);
        verify(redisTemplate).expire("external-ws:registry:im", 30, TimeUnit.SECONDS);
    }
}
