package com.opencode.cui.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GatewayInstanceRegistryTest {

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private HashOperations<String, Object, Object> hashOperations;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String INSTANCE_ID = "gw-az1-1";

    private GatewayInstanceRegistry createRegistry(String podIp, int serverPort, String contextPath) {
        GatewayInstanceRegistry registry = new GatewayInstanceRegistry(redisTemplate, objectMapper);
        ReflectionTestUtils.setField(registry, "instanceId", INSTANCE_ID);
        ReflectionTestUtils.setField(registry, "podIp", podIp);
        ReflectionTestUtils.setField(registry, "serverPort", serverPort);
        ReflectionTestUtils.setField(registry, "contextPath", contextPath);
        return registry;
    }

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForHash()).thenReturn(hashOperations);
    }

    @Test
    @DisplayName("register 使用 POD_IP 自动拼接 wsUrl 并写入 HASH")
    void registerAutoDetectsWsUrlFromPodIp() {
        GatewayInstanceRegistry registry = createRegistry("10.244.1.5", 8081, "");
        registry.register();

        ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
        verify(hashOperations).put(
                eq(GatewayInstanceRegistry.INSTANCES_HASH_KEY),
                eq(INSTANCE_ID),
                valueCaptor.capture());

        String value = valueCaptor.getValue();
        assertTrue(value.contains("ws://10.244.1.5:8081/ws/skill"));
    }

    @Test
    @DisplayName("register 带上下文根时正确拼接 wsUrl")
    void registerWithContextPath() {
        GatewayInstanceRegistry registry = createRegistry("10.244.1.5", 8081, "/gateway");
        registry.register();

        ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
        verify(hashOperations).put(
                eq(GatewayInstanceRegistry.INSTANCES_HASH_KEY),
                eq(INSTANCE_ID),
                valueCaptor.capture());

        String value = valueCaptor.getValue();
        assertTrue(value.contains("ws://10.244.1.5:8081/gateway/ws/skill"));
    }

    @Test
    @DisplayName("register IPv6 地址包裹方括号")
    void registerWrapsIpv6InBrackets() {
        GatewayInstanceRegistry registry = createRegistry("fe80::1", 8081, "");
        registry.register();

        ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
        verify(hashOperations).put(
                eq(GatewayInstanceRegistry.INSTANCES_HASH_KEY),
                eq(INSTANCE_ID),
                valueCaptor.capture());

        String value = valueCaptor.getValue();
        assertTrue(value.contains("ws://[fe80::1]:8081/ws/skill"));
    }

    @Test
    @DisplayName("POD_IP 为空时 fallback 到 getLocalHost 并生成有效 wsUrl")
    void registerFallsBackToLocalHost() {
        GatewayInstanceRegistry registry = createRegistry("", 8081, "");
        registry.register();

        ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
        verify(hashOperations).put(
                eq(GatewayInstanceRegistry.INSTANCES_HASH_KEY),
                eq(INSTANCE_ID),
                valueCaptor.capture());

        String value = valueCaptor.getValue();
        assertTrue(value.contains("ws://"));
        assertTrue(value.contains(":8081/ws/skill"));
    }

    @Test
    @DisplayName("refreshHeartbeat 更新 HASH 中的心跳时间戳")
    void refreshHeartbeatUpdatesHash() {
        GatewayInstanceRegistry registry = createRegistry("10.0.0.1", 8081, "");
        registry.register();
        registry.refreshHeartbeat();

        verify(hashOperations, times(2)).put(
                eq(GatewayInstanceRegistry.INSTANCES_HASH_KEY),
                eq(INSTANCE_ID),
                anyString());
    }

    @Test
    @DisplayName("destroy 从聚合 HASH 中删除本实例 field")
    void destroyRemovesFromHash() {
        GatewayInstanceRegistry registry = createRegistry("10.0.0.1", 8081, "");
        registry.destroy();

        verify(hashOperations).delete(GatewayInstanceRegistry.INSTANCES_HASH_KEY, INSTANCE_ID);
    }

    @Test
    @DisplayName("getInstanceId 返回配置的实例 ID")
    void getInstanceIdReturnsConfiguredId() {
        GatewayInstanceRegistry registry = createRegistry("10.0.0.1", 8081, "");
        assertEquals(INSTANCE_ID, registry.getInstanceId());
    }

    @Test
    @DisplayName("register 的 value 包含 JSON 格式的 wsUrl、startedAt、lastHeartbeat")
    void registerValueContainsJsonFields() {
        GatewayInstanceRegistry registry = createRegistry("10.0.0.1", 8081, "");
        registry.register();

        ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
        verify(hashOperations).put(
                eq(GatewayInstanceRegistry.INSTANCES_HASH_KEY),
                eq(INSTANCE_ID),
                valueCaptor.capture());

        String value = valueCaptor.getValue();
        assertTrue(value.contains("\"wsUrl\""));
        assertTrue(value.contains("\"startedAt\""));
        assertTrue(value.contains("\"lastHeartbeat\""));
        assertTrue(value.contains("\"instanceId\""));
    }
}
