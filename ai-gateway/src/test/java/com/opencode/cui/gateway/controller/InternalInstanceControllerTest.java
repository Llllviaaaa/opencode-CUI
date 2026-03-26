package com.opencode.cui.gateway.controller;

import com.opencode.cui.gateway.service.GatewayInstanceRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * InternalInstanceController 单元测试。
 *
 * 验证 HGETALL 读取聚合 HASH、lastHeartbeat 过滤、惰性清理逻辑。
 */
@ExtendWith(MockitoExtension.class)
class InternalInstanceControllerTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private HashOperations<String, Object, Object> hashOperations;

    private InternalInstanceController controller;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        controller = new InternalInstanceController(redisTemplate);
        ReflectionTestUtils.setField(controller, "staleThresholdSeconds", 60);
    }

    @Test
    @DisplayName("getInstances 返回存活实例列表")
    void getInstancesReturnsAliveInstances() {
        String freshHeartbeat = Instant.now().toString();
        String value = "{\"instanceId\":\"gw-pod-1\",\"wsUrl\":\"ws://10.0.1.1:8081/ws/skill\","
                + "\"lastHeartbeat\":\"" + freshHeartbeat + "\"}";

        Map<Object, Object> entries = Map.of("gw-pod-1", value);
        when(hashOperations.entries(GatewayInstanceRegistry.INSTANCES_HASH_KEY)).thenReturn(entries);

        ResponseEntity<Map<String, Object>> response = controller.getInstances();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());

        @SuppressWarnings("unchecked")
        var instances = (java.util.List<Map<String, String>>) response.getBody().get("instances");
        assertNotNull(instances);
        assertEquals(1, instances.size());
        assertEquals("gw-pod-1", instances.get(0).get("instanceId"));
        assertEquals("ws://10.0.1.1:8081/ws/skill", instances.get(0).get("wsUrl"));
    }

    @Test
    @DisplayName("getInstances 空 HASH 时返回空列表")
    void getInstancesReturnsEmptyListWhenHashIsEmpty() {
        when(hashOperations.entries(GatewayInstanceRegistry.INSTANCES_HASH_KEY))
                .thenReturn(Map.of());

        ResponseEntity<Map<String, Object>> response = controller.getInstances();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        @SuppressWarnings("unchecked")
        var instances = (java.util.List<Map<String, String>>) response.getBody().get("instances");
        assertNotNull(instances);
        assertTrue(instances.isEmpty());
    }

    @Test
    @DisplayName("getInstances 过滤并惰性清理过期实例")
    void getInstancesFiltersAndCleansUpStaleInstances() {
        // 存活实例
        String freshHeartbeat = Instant.now().toString();
        String aliveValue = "{\"instanceId\":\"gw-alive\",\"wsUrl\":\"ws://10.0.1.1:8081/ws/skill\","
                + "\"lastHeartbeat\":\"" + freshHeartbeat + "\"}";

        // 过期实例（2 分钟前的心跳）
        String staleHeartbeat = Instant.now().minusSeconds(120).toString();
        String staleValue = "{\"instanceId\":\"gw-dead\",\"wsUrl\":\"ws://10.0.1.2:8081/ws/skill\","
                + "\"lastHeartbeat\":\"" + staleHeartbeat + "\"}";

        // 使用 LinkedHashMap 保证顺序可预测
        Map<Object, Object> entries = new LinkedHashMap<>();
        entries.put("gw-alive", aliveValue);
        entries.put("gw-dead", staleValue);
        when(hashOperations.entries(GatewayInstanceRegistry.INSTANCES_HASH_KEY)).thenReturn(entries);

        ResponseEntity<Map<String, Object>> response = controller.getInstances();

        assertEquals(HttpStatus.OK, response.getStatusCode());

        @SuppressWarnings("unchecked")
        var instances = (java.util.List<Map<String, String>>) response.getBody().get("instances");
        assertEquals(1, instances.size());
        assertEquals("gw-alive", instances.get(0).get("instanceId"));

        // 验证过期实例被惰性 HDEL
        verify(hashOperations).delete(GatewayInstanceRegistry.INSTANCES_HASH_KEY, "gw-dead");
    }

    @Test
    @DisplayName("getInstances 跳过格式错误的条目")
    void getInstancesSkipsMalformedValues() {
        Map<Object, Object> entries = Map.of("gw-bad", "not-valid-json");
        when(hashOperations.entries(GatewayInstanceRegistry.INSTANCES_HASH_KEY)).thenReturn(entries);

        ResponseEntity<Map<String, Object>> response = controller.getInstances();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        @SuppressWarnings("unchecked")
        var instances = (java.util.List<Map<String, String>>) response.getBody().get("instances");
        assertTrue(instances.isEmpty());
    }
}
