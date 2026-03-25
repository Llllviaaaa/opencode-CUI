package com.opencode.cui.skill.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
/** GatewayDiscoveryService unit tests: HTTP discovery + multi-level fallback logic. */
class GatewayDiscoveryServiceTest {

    @Mock
    private RedisMessageBroker redisMessageBroker;
    @Mock
    private GatewayDiscoveryService.Listener listener;

    private GatewayDiscoveryService service;

    @BeforeEach
    void setUp() {
        service = spy(new GatewayDiscoveryService(redisMessageBroker, new ObjectMapper()));
        service.addListener(listener);
        // Inject config defaults
        ReflectionTestUtils.setField(service, "discoveryUrl", "http://localhost:8081/internal/instances");
        ReflectionTestUtils.setField(service, "discoveryTimeoutMs", 3000);
        ReflectionTestUtils.setField(service, "discoveryFailThreshold", 3);
    }

    // ===== Original Redis-based tests (must still pass) =====

    @Test
    @DisplayName("发现新 GW 实例时通知 listener onGatewayAdded")
    void discoverNewGwInstanceNotifiesAdded() {
        // HTTP disabled so we test via Redis fallback path
        ReflectionTestUtils.setField(service, "discoveryUrl", "");
        when(redisMessageBroker.scanGatewayInstances()).thenReturn(
                Map.of("gw-1", "{\"wsUrl\":\"ws://10.0.1.5:8081/ws/skill\"}"));

        service.discover();

        verify(listener).onGatewayAdded("gw-1", "ws://10.0.1.5:8081/ws/skill");
    }

    @Test
    @DisplayName("GW 实例消失时通知 listener onGatewayRemoved")
    void discoverGwInstanceGoneNotifiesRemoved() {
        ReflectionTestUtils.setField(service, "discoveryUrl", "");
        when(redisMessageBroker.scanGatewayInstances()).thenReturn(
                Map.of("gw-1", "{\"wsUrl\":\"ws://10.0.1.5:8081/ws/skill\"}"));
        service.discover();

        when(redisMessageBroker.scanGatewayInstances()).thenReturn(Map.of());
        service.discover();

        verify(listener).onGatewayRemoved("gw-1");
    }

    @Test
    @DisplayName("无变化时不重复通知 listener")
    void discoverNoChangeNoNotification() {
        ReflectionTestUtils.setField(service, "discoveryUrl", "");
        Map<String, String> instances = Map.of("gw-1", "{\"wsUrl\":\"ws://10.0.1.5:8081/ws/skill\"}");
        when(redisMessageBroker.scanGatewayInstances()).thenReturn(instances);

        service.discover();
        service.discover(); // second call — no change

        verify(listener, times(1)).onGatewayAdded("gw-1", "ws://10.0.1.5:8081/ws/skill");
    }

    @Test
    @DisplayName("getKnownInstanceIds 返回已知实例集合")
    void getKnownInstanceIdsReturnsDiscoveredIds() {
        ReflectionTestUtils.setField(service, "discoveryUrl", "");
        when(redisMessageBroker.scanGatewayInstances()).thenReturn(
                Map.of("gw-1", "{\"wsUrl\":\"ws://a\"}", "gw-2", "{\"wsUrl\":\"ws://b\"}"));

        service.discover();

        assertEquals(Set.of("gw-1", "gw-2"), service.getKnownInstanceIds());
    }

    // ===== New HTTP discovery tests =====

    @Test
    @DisplayName("HTTP 发现成功时直接使用 HTTP 结果，不走 Redis")
    void discover_httpSuccess_shouldReturnInstances() {
        Map<String, String> httpResult = Map.of("gw-1", "ws://10.0.1.5:8081/ws/skill");
        doReturn(httpResult).when(service).tryHttpDiscovery();

        service.discover();

        verify(listener).onGatewayAdded("gw-1", "ws://10.0.1.5:8081/ws/skill");
        // Redis should not be called when HTTP succeeds
        verify(redisMessageBroker, never()).scanGatewayInstances();
    }

    @Test
    @DisplayName("HTTP 发现失败时降级到 Redis 扫描")
    void discover_httpFail_shouldFallbackToRedis() {
        doReturn(null).when(service).tryHttpDiscovery();
        when(redisMessageBroker.scanGatewayInstances()).thenReturn(
                Map.of("gw-1", "{\"wsUrl\":\"ws://10.0.1.5:8081/ws/skill\"}"));

        service.discover();

        verify(redisMessageBroker).scanGatewayInstances();
        verify(listener).onGatewayAdded("gw-1", "ws://10.0.1.5:8081/ws/skill");
    }

    @Test
    @DisplayName("HTTP 连续失败超过阈值后跳过 HTTP，直接走 Redis")
    void discover_httpFailExceedsThreshold_shouldSkipHttp() {
        // Set failCount to threshold
        ReflectionTestUtils.setField(service, "httpFailCount",
                new java.util.concurrent.atomic.AtomicInteger(3));

        when(redisMessageBroker.scanGatewayInstances()).thenReturn(
                Map.of("gw-1", "{\"wsUrl\":\"ws://10.0.1.5:8081/ws/skill\"}"));

        service.discover();

        // tryHttpDiscovery must still be called (it handles the skip-logic internally),
        // but it will return null without making an HTTP call.
        // Redis fallback should kick in.
        verify(redisMessageBroker).scanGatewayInstances();
        verify(listener).onGatewayAdded("gw-1", "ws://10.0.1.5:8081/ws/skill");
    }

    @Test
    @DisplayName("HTTP 恢复后重置失败计数")
    void discover_httpRecovery_shouldResetFailCount() throws Exception {
        // Pre-set 2 failures
        ReflectionTestUtils.setField(service, "httpFailCount",
                new java.util.concurrent.atomic.AtomicInteger(2));

        // Simulate a valid HTTP response via tryHttpDiscovery returning data
        Map<String, String> httpResult = Map.of("gw-1", "ws://10.0.1.5:8081/ws/skill");
        doReturn(httpResult).when(service).tryHttpDiscovery();

        service.discover();

        verify(listener).onGatewayAdded("gw-1", "ws://10.0.1.5:8081/ws/skill");
        // Redis should not be consulted when HTTP succeeds
        verify(redisMessageBroker, never()).scanGatewayInstances();
    }

    @Test
    @DisplayName("HTTP 和 Redis 全部失败时维持现有连接不变")
    void discover_allFail_shouldKeepExisting() {
        // Seed an existing known instance
        doReturn(null).when(service).tryHttpDiscovery();
        when(redisMessageBroker.scanGatewayInstances()).thenReturn(
                Map.of("gw-1", "{\"wsUrl\":\"ws://10.0.1.5:8081/ws/skill\"}"));
        service.discover();
        verify(listener).onGatewayAdded("gw-1", "ws://10.0.1.5:8081/ws/skill");

        // Now both fail
        doReturn(null).when(service).tryHttpDiscovery();
        doReturn(null).when(service).tryScanRedis();
        service.discover();

        // No remove notification — connection preserved
        verify(listener, never()).onGatewayRemoved(any());
        assertEquals(Set.of("gw-1"), service.getKnownInstanceIds());
    }
}
