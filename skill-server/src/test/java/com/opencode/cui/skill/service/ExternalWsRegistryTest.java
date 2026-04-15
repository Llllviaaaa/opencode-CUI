package com.opencode.cui.skill.service;

import com.opencode.cui.skill.config.DeliveryProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ExternalWsRegistryTest {

    @Mock private RedisMessageBroker redisMessageBroker;
    @Mock private SkillInstanceRegistry instanceRegistry;
    @Mock private DeliveryProperties deliveryProperties;
    @InjectMocks private ExternalWsRegistry registry;

    @Test
    @DisplayName("register writes hash with configured TTL")
    void register() {
        when(instanceRegistry.getInstanceId()).thenReturn("ss-pod-1");
        when(deliveryProperties.getRegistryTtlSeconds()).thenReturn(30);
        registry.register("im", 3);
        verify(redisMessageBroker).registerWsConnection("im", "ss-pod-1", 3, 30);
    }

    @Test
    @DisplayName("unregister removes hash field")
    void unregister() {
        when(instanceRegistry.getInstanceId()).thenReturn("ss-pod-1");
        registry.unregister("im");
        verify(redisMessageBroker).unregisterWsConnection("im", "ss-pod-1");
    }

    @Test
    @DisplayName("heartbeat renews TTL")
    void heartbeat() {
        when(deliveryProperties.getRegistryTtlSeconds()).thenReturn(30);
        registry.heartbeat("im");
        verify(redisMessageBroker).expireWsRegistry("im", 30);
    }

    @Test
    @DisplayName("findInstanceWithConnection returns first remote instance")
    void findInstanceWithConnection() {
        when(instanceRegistry.getInstanceId()).thenReturn("ss-pod-1");
        when(redisMessageBroker.getWsRegistry("im")).thenReturn(
                Map.of("ss-pod-1", "0", "ss-pod-2", "3"));
        String target = registry.findInstanceWithConnection("im");
        assertEquals("ss-pod-2", target);
    }

    @Test
    @DisplayName("findInstanceWithConnection returns null when no remote instances")
    void findInstanceNoRemote() {
        when(instanceRegistry.getInstanceId()).thenReturn("ss-pod-1");
        when(redisMessageBroker.getWsRegistry("im")).thenReturn(Collections.emptyMap());
        assertNull(registry.findInstanceWithConnection("im"));
    }

    @Test
    @DisplayName("findInstanceWithConnection skips self")
    void findInstanceSkipsSelf() {
        when(instanceRegistry.getInstanceId()).thenReturn("ss-pod-1");
        when(redisMessageBroker.getWsRegistry("im")).thenReturn(Map.of("ss-pod-1", "2"));
        assertNull(registry.findInstanceWithConnection("im"));
    }
}
