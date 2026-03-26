package com.opencode.cui.skill.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
/** GatewayDiscoveryService unit tests: HTTP discovery + cache + fallback logic. */
class GatewayDiscoveryServiceTest {

    @Mock
    private GatewayDiscoveryService.Listener listener;

    private GatewayDiscoveryService service;

    @BeforeEach
    void setUp() {
        service = spy(new GatewayDiscoveryService(new ObjectMapper()));
        service.addListener(listener);
        // Inject config defaults
        ReflectionTestUtils.setField(service, "discoveryUrl", "http://localhost:8081/internal/instances");
        ReflectionTestUtils.setField(service, "discoveryTimeoutMs", 3000);
        ReflectionTestUtils.setField(service, "discoveryFailThreshold", 3);
        ReflectionTestUtils.setField(service, "discoveryCacheTtlSeconds", 30);
    }

    @Test
    @DisplayName("HTTP 发现成功时通知 listener onGatewayAdded")
    void discover_httpSuccess_shouldNotifyAdded() {
        Map<String, String> httpResult = Map.of("gw-1", "ws://10.0.1.5:8081/ws/skill");
        doReturn(httpResult).when(service).tryHttpDiscovery();

        service.discover();

        verify(listener).onGatewayAdded("gw-1", "ws://10.0.1.5:8081/ws/skill");
    }

    @Test
    @DisplayName("GW 实例消失时通知 listener onGatewayRemoved")
    void discover_instanceGone_shouldNotifyRemoved() {
        doReturn(Map.of("gw-1", "ws://10.0.1.5:8081/ws/skill")).when(service).tryHttpDiscovery();
        service.discover();

        // 清除缓存以允许第二次 HTTP 调用
        ReflectionTestUtils.setField(service, "cachedResult",
                new java.util.concurrent.atomic.AtomicReference<>(null));
        doReturn(Map.<String, String>of()).when(service).tryHttpDiscovery();
        service.discover();

        verify(listener).onGatewayRemoved("gw-1");
    }

    @Test
    @DisplayName("无变化时不重复通知 listener")
    void discover_noChange_shouldNotNotifyAgain() {
        Map<String, String> instances = Map.of("gw-1", "ws://10.0.1.5:8081/ws/skill");
        doReturn(instances).when(service).tryHttpDiscovery();

        service.discover();
        // 清除缓存以允许第二次 HTTP 调用
        ReflectionTestUtils.setField(service, "cachedResult",
                new java.util.concurrent.atomic.AtomicReference<>(null));
        service.discover();

        verify(listener, times(1)).onGatewayAdded("gw-1", "ws://10.0.1.5:8081/ws/skill");
    }

    @Test
    @DisplayName("getKnownInstanceIds 返回已知实例集合")
    void getKnownInstanceIds_shouldReturnDiscoveredIds() {
        doReturn(Map.of("gw-1", "ws://a", "gw-2", "ws://b")).when(service).tryHttpDiscovery();

        service.discover();

        assertEquals(Set.of("gw-1", "gw-2"), service.getKnownInstanceIds());
    }

    @Test
    @DisplayName("缓存未过期时不发 HTTP 请求")
    void discover_cacheStillFresh_shouldSkipHttp() {
        doReturn(Map.of("gw-1", "ws://10.0.1.5:8081/ws/skill")).when(service).tryHttpDiscovery();
        service.discover();

        // 第二次调用：缓存未过期，不应调用 tryHttpDiscovery
        service.discover();

        // tryHttpDiscovery 只被调用 1 次（第一次）
        verify(service, times(1)).tryHttpDiscovery();
    }

    @Test
    @DisplayName("缓存过期后重新发 HTTP 请求")
    void discover_cacheExpired_shouldCallHttp() {
        doReturn(Map.of("gw-1", "ws://10.0.1.5:8081/ws/skill")).when(service).tryHttpDiscovery();
        service.discover();

        // 模拟缓存过期：设置缓存 TTL 为 0
        ReflectionTestUtils.setField(service, "discoveryCacheTtlSeconds", 0);
        service.discover();

        // tryHttpDiscovery 被调用 2 次
        verify(service, times(2)).tryHttpDiscovery();
    }

    @Test
    @DisplayName("HTTP 失败时维持现有连接不变")
    void discover_httpFail_shouldKeepExisting() {
        doReturn(Map.of("gw-1", "ws://10.0.1.5:8081/ws/skill")).when(service).tryHttpDiscovery();
        service.discover();
        verify(listener).onGatewayAdded("gw-1", "ws://10.0.1.5:8081/ws/skill");

        // 缓存过期 + HTTP 失败
        ReflectionTestUtils.setField(service, "discoveryCacheTtlSeconds", 0);
        doReturn(null).when(service).tryHttpDiscovery();
        service.discover();

        // 不应有 remove 通知
        verify(listener, never()).onGatewayRemoved(any());
        assertEquals(Set.of("gw-1"), service.getKnownInstanceIds());
    }

    @Test
    @DisplayName("HTTP 连续失败超过阈值后跳过 HTTP")
    void discover_httpFailExceedsThreshold_shouldSkipHttp() {
        ReflectionTestUtils.setField(service, "httpFailCount",
                new java.util.concurrent.atomic.AtomicInteger(3));

        service.discover();

        // tryHttpDiscovery 内部 skip 后返回 null，保持现有连接
        verify(listener, never()).onGatewayAdded(any(), any());
    }
}
