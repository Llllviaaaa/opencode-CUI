package com.opencode.cui.gateway.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Unit tests for InternalInstanceController.
 *
 * Verifies that GET /internal/instances correctly scans gw:internal:instance:* keys,
 * parses each value as JSON, and assembles the instance list in the response.
 */
@ExtendWith(MockitoExtension.class)
class InternalInstanceControllerTest {

    private static final String KEY_PREFIX = "gw:internal:instance:";

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private InternalInstanceController controller;

    @BeforeEach
    void setUp() {
        controller = new InternalInstanceController(redisTemplate);
    }

    @Test
    @DisplayName("getInstances returns list when instances are registered")
    void getInstancesReturnsListWhenInstancesAreRegistered() {
        String instanceId = "gw-pod-1";
        String key = KEY_PREFIX + instanceId;
        String value = "{\"instanceId\":\"gw-pod-1\",\"wsUrl\":\"ws://10.0.1.1:8081/ws/skill\"}";

        when(redisTemplate.keys(KEY_PREFIX + "*")).thenReturn(Set.of(key));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(key)).thenReturn(value);

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
    @DisplayName("getInstances returns empty list when no instances are registered")
    void getInstancesReturnsEmptyListWhenNoInstancesRegistered() {
        when(redisTemplate.keys(KEY_PREFIX + "*")).thenReturn(Set.of());

        ResponseEntity<Map<String, Object>> response = controller.getInstances();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());

        @SuppressWarnings("unchecked")
        var instances = (java.util.List<Map<String, String>>) response.getBody().get("instances");
        assertNotNull(instances);
        assertTrue(instances.isEmpty());
    }

    @Test
    @DisplayName("getInstances returns empty list when keys() returns null")
    void getInstancesReturnsEmptyListWhenKeysReturnsNull() {
        when(redisTemplate.keys(KEY_PREFIX + "*")).thenReturn(null);

        ResponseEntity<Map<String, Object>> response = controller.getInstances();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());

        @SuppressWarnings("unchecked")
        var instances = (java.util.List<Map<String, String>>) response.getBody().get("instances");
        assertNotNull(instances);
        assertTrue(instances.isEmpty());
    }

    @Test
    @DisplayName("getInstances skips entries with null or malformed values")
    void getInstancesSkipsMalformedValues() {
        String key1 = KEY_PREFIX + "gw-pod-1";
        String key2 = KEY_PREFIX + "gw-pod-2";
        String validValue = "{\"instanceId\":\"gw-pod-1\",\"wsUrl\":\"ws://10.0.1.1:8081/ws/skill\"}";

        when(redisTemplate.keys(KEY_PREFIX + "*")).thenReturn(Set.of(key1, key2));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(key1)).thenReturn(validValue);
        when(valueOperations.get(key2)).thenReturn(null);

        ResponseEntity<Map<String, Object>> response = controller.getInstances();

        assertEquals(HttpStatus.OK, response.getStatusCode());

        @SuppressWarnings("unchecked")
        var instances = (java.util.List<Map<String, String>>) response.getBody().get("instances");
        assertNotNull(instances);
        assertEquals(1, instances.size());
        assertEquals("gw-pod-1", instances.get(0).get("instanceId"));
    }
}
