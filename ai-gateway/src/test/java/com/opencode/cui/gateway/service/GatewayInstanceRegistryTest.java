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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

/** GatewayInstanceRegistry 单元测试：验证 HASH 注册、心跳刷新和注销逻辑。 */
@ExtendWith(MockitoExtension.class)
class GatewayInstanceRegistryTest {

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private HashOperations<String, Object, Object> hashOperations;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private GatewayInstanceRegistry registry;

    private static final String INSTANCE_ID = "gw-az1-1";
    private static final String WS_URL = "ws://10.0.1.5:8081/ws/skill";

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        registry = new GatewayInstanceRegistry(
                redisTemplate, objectMapper, INSTANCE_ID, WS_URL);
    }

    @Test
    @DisplayName("register 向聚合 HASH 写入实例信息")
    void registerWritesToHash() {
        registry.register();

        ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
        verify(hashOperations).put(
                eq(GatewayInstanceRegistry.INSTANCES_HASH_KEY),
                eq(INSTANCE_ID),
                valueCaptor.capture());

        String value = valueCaptor.getValue();
        assertTrue(value.contains(WS_URL));
        assertTrue(value.contains("\"lastHeartbeat\""));
        assertTrue(value.contains("\"instanceId\""));
    }

    @Test
    @DisplayName("refreshHeartbeat 更新 HASH 中的心跳时间戳")
    void refreshHeartbeatUpdatesHash() {
        registry.register();
        registry.refreshHeartbeat();

        // register + refreshHeartbeat = 2 次 put
        verify(hashOperations, org.mockito.Mockito.times(2)).put(
                eq(GatewayInstanceRegistry.INSTANCES_HASH_KEY),
                eq(INSTANCE_ID),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("destroy 从聚合 HASH 中删除本实例 field")
    void destroyRemovesFromHash() {
        registry.destroy();

        verify(hashOperations).delete(GatewayInstanceRegistry.INSTANCES_HASH_KEY, INSTANCE_ID);
    }

    @Test
    @DisplayName("getInstanceId 返回配置的实例 ID")
    void getInstanceIdReturnsConfiguredId() {
        assertEquals(INSTANCE_ID, registry.getInstanceId());
    }

    @Test
    @DisplayName("register 的 value 包含 JSON 格式的 wsUrl、startedAt、lastHeartbeat")
    void registerValueContainsJsonFields() {
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
        assertTrue(value.contains(WS_URL));
    }
}
