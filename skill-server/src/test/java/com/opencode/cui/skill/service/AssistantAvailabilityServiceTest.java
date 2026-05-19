package com.opencode.cui.skill.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.model.AvailabilityResult;
import com.opencode.cui.skill.model.AvailabilitySource;
import com.opencode.cui.skill.model.GatewayAvailabilityResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AssistantAvailabilityServiceTest {

    @Mock private GatewayApiClient gatewayApiClient;
    @Mock private SysConfigService sysConfigService;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private AssistantAvailabilityService service;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
        service = new AssistantAvailabilityService(gatewayApiClient, sysConfigService, redisTemplate, objectMapper);
    }

    // ------------------------------------------------------------------ resolve: ONLINE

    @Test
    @DisplayName("resolve: exists=true, online=true → ONLINE")
    void resolveOnline() {
        when(gatewayApiClient.getAvailability("ak-1"))
                .thenReturn(new GatewayAvailabilityResponse(true, true, "opencode", null));
        when(valueOps.get("ss:availability:ak-1")).thenReturn(null);
        doNothing().when(valueOps).set(anyString(), anyString(), any(Duration.class));

        AvailabilityResult r = service.resolve("ak-1");

        assertTrue(r.online());
        assertEquals(AvailabilitySource.ONLINE, r.source());
        assertNull(r.message());
        assertEquals("opencode", r.toolType());
    }

    // ------------------------------------------------------------------ resolve: NOT_CONFIGURED

    @Test
    @DisplayName("resolve: exists=false → NOT_CONFIGURED with sys_config message")
    void resolveNotConfigured() {
        when(gatewayApiClient.getAvailability("ak-new"))
                .thenReturn(new GatewayAvailabilityResponse(false, false, null, null));
        when(sysConfigService.getValue("assistant_offline", "not_configured"))
                .thenReturn("请前往平台完成绑定后重试。");
        when(valueOps.get("ss:availability:ak-new")).thenReturn(null);
        doNothing().when(valueOps).set(anyString(), anyString(), any(Duration.class));

        AvailabilityResult r = service.resolve("ak-new");

        assertFalse(r.online());
        assertEquals(AvailabilitySource.NOT_CONFIGURED, r.source());
        assertEquals("请前往平台完成绑定后重试。", r.message());
        assertNull(r.toolType());
    }

    @Test
    @DisplayName("resolve: exists=false, not_configured blank → fallback to message key")
    void resolveNotConfiguredFallbackToMessage() {
        when(gatewayApiClient.getAvailability("ak-new"))
                .thenReturn(new GatewayAvailabilityResponse(false, false, null, null));
        when(sysConfigService.getValue("assistant_offline", "not_configured")).thenReturn("");
        when(sysConfigService.getValue("assistant_offline", "message"))
                .thenReturn("兜底离线文案");
        when(valueOps.get("ss:availability:ak-new")).thenReturn(null);
        doNothing().when(valueOps).set(anyString(), anyString(), any(Duration.class));

        AvailabilityResult r = service.resolve("ak-new");

        assertEquals(AvailabilitySource.NOT_CONFIGURED, r.source());
        assertEquals("兜底离线文案", r.message());
    }

    @Test
    @DisplayName("resolve: exists=false, both blank → hardcoded default")
    void resolveNotConfiguredHardcodedFallback() {
        when(gatewayApiClient.getAvailability("ak-new"))
                .thenReturn(new GatewayAvailabilityResponse(false, false, null, null));
        when(sysConfigService.getValue("assistant_offline", "not_configured")).thenReturn(null);
        when(sysConfigService.getValue("assistant_offline", "message")).thenReturn(null);
        when(valueOps.get("ss:availability:ak-new")).thenReturn(null);
        doNothing().when(valueOps).set(anyString(), anyString(), any(Duration.class));

        AvailabilityResult r = service.resolve("ak-new");

        assertEquals("任务下发失败，请检查助理是否离线，确保助理在线后重试", r.message());
    }

    // ------------------------------------------------------------------ resolve: OFFLINE_TYPED

    @Test
    @DisplayName("resolve: offline + toolType=opencode + tool_type:opencode configured → OFFLINE_TYPED")
    void resolveOfflineTyped() {
        when(gatewayApiClient.getAvailability("ak-off"))
                .thenReturn(new GatewayAvailabilityResponse(true, false, "opencode", null));
        when(sysConfigService.getValue("assistant_offline", "tool_type:opencode"))
                .thenReturn("请确保 OpenCode 客户端在线后重试。");
        when(valueOps.get("ss:availability:ak-off")).thenReturn(null);
        doNothing().when(valueOps).set(anyString(), anyString(), any(Duration.class));

        AvailabilityResult r = service.resolve("ak-off");

        assertFalse(r.online());
        assertEquals(AvailabilitySource.OFFLINE_TYPED, r.source());
        assertEquals("请确保 OpenCode 客户端在线后重试。", r.message());
        assertEquals("opencode", r.toolType());
    }

    @Test
    @DisplayName("resolve: offline + toolType hit but typed key blank → fallback to message")
    void resolveOfflineTypedBlankFallbackToMessage() {
        when(gatewayApiClient.getAvailability("ak-off"))
                .thenReturn(new GatewayAvailabilityResponse(true, false, "opencode", null));
        when(sysConfigService.getValue("assistant_offline", "tool_type:opencode")).thenReturn("");
        when(sysConfigService.getValue("assistant_offline", "message"))
                .thenReturn("默认离线文案");
        when(valueOps.get("ss:availability:ak-off")).thenReturn(null);
        doNothing().when(valueOps).set(anyString(), anyString(), any(Duration.class));

        AvailabilityResult r = service.resolve("ak-off");

        assertEquals(AvailabilitySource.OFFLINE_DEFAULT, r.source());
        assertEquals("默认离线文案", r.message());
    }

    // ------------------------------------------------------------------ resolve: OFFLINE_DEFAULT

    @Test
    @DisplayName("resolve: offline + toolType null → OFFLINE_DEFAULT")
    void resolveOfflineDefaultToolTypeNull() {
        when(gatewayApiClient.getAvailability("ak-off"))
                .thenReturn(new GatewayAvailabilityResponse(true, false, null, null));
        when(sysConfigService.getValue("assistant_offline", "message"))
                .thenReturn("请检查助理是否离线。");
        when(valueOps.get("ss:availability:ak-off")).thenReturn(null);
        doNothing().when(valueOps).set(anyString(), anyString(), any(Duration.class));

        AvailabilityResult r = service.resolve("ak-off");

        assertEquals(AvailabilitySource.OFFLINE_DEFAULT, r.source());
        assertEquals("请检查助理是否离线。", r.message());
        assertNull(r.toolType());
    }

    @Test
    @DisplayName("resolve: offline + message blank → hardcoded default")
    void resolveOfflineDefaultHardcoded() {
        when(gatewayApiClient.getAvailability("ak-off"))
                .thenReturn(new GatewayAvailabilityResponse(true, false, null, null));
        when(sysConfigService.getValue("assistant_offline", "message")).thenReturn(null);
        when(valueOps.get("ss:availability:ak-off")).thenReturn(null);
        doNothing().when(valueOps).set(anyString(), anyString(), any(Duration.class));

        AvailabilityResult r = service.resolve("ak-off");

        assertEquals("任务下发失败，请检查助理是否离线，确保助理在线后重试", r.message());
    }

    // ------------------------------------------------------------------ resolve: FALLBACK_ERROR

    @Test
    @DisplayName("resolve: gateway returns null → FALLBACK_ERROR")
    void resolveGatewayNull() {
        when(gatewayApiClient.getAvailability("ak-x")).thenReturn(null);
        when(valueOps.get("ss:availability:ak-x")).thenReturn(null);
        doNothing().when(valueOps).set(anyString(), anyString(), any(Duration.class));

        AvailabilityResult r = service.resolve("ak-x");

        assertEquals(AvailabilitySource.FALLBACK_ERROR, r.source());
        assertEquals("任务下发失败，请检查助理是否离线，确保助理在线后重试", r.message());
    }

    @Test
    @DisplayName("resolve: gateway throws → FALLBACK_ERROR")
    void resolveGatewayThrows() {
        when(gatewayApiClient.getAvailability("ak-x"))
                .thenThrow(new RuntimeException("Connection refused"));
        when(valueOps.get("ss:availability:ak-x")).thenReturn(null);
        doNothing().when(valueOps).set(anyString(), anyString(), any(Duration.class));

        AvailabilityResult r = service.resolve("ak-x");

        assertEquals(AvailabilitySource.FALLBACK_ERROR, r.source());
    }

    // ------------------------------------------------------------------ Redis cache / degradation

    @Test
    @DisplayName("resolve: Redis cache hit → skip gateway call")
    void resolveCacheHit() {
        when(valueOps.get("ss:availability:ak-cached"))
                .thenReturn("{\"online\":true,\"toolType\":\"opencode\",\"source\":\"ONLINE\"}");

        AvailabilityResult r = service.resolve("ak-cached");

        assertTrue(r.online());
        assertEquals(AvailabilitySource.ONLINE, r.source());
        verify(gatewayApiClient, never()).getAvailability(any());
    }

    @Test
    @DisplayName("resolve: Redis read fails → falls through to gateway, still returns ONLINE")
    void resolveRedisReadFailureOnline() {
        when(valueOps.get("ss:availability:ak-1"))
                .thenThrow(new RedisConnectionFailureException("Connection refused"));
        when(gatewayApiClient.getAvailability("ak-1"))
                .thenReturn(new GatewayAvailabilityResponse(true, true, "opencode", null));
        doNothing().when(valueOps).set(anyString(), anyString(), any(Duration.class));

        AvailabilityResult r = service.resolve("ak-1");

        assertTrue(r.online());
        assertEquals(AvailabilitySource.ONLINE, r.source());
        verify(gatewayApiClient).getAvailability("ak-1");
    }

    @Test
    @DisplayName("resolve: Redis write fails → still returns correct result (best-effort)")
    void resolveRedisWriteFailureReturnsCorrectResult() {
        when(valueOps.get("ss:availability:ak-2")).thenReturn(null);
        when(gatewayApiClient.getAvailability("ak-2"))
                .thenReturn(new GatewayAvailabilityResponse(true, true, "opencode", null));
        doThrow(new RedisConnectionFailureException("Connection refused"))
                .when(valueOps).set(anyString(), anyString(), any(Duration.class));

        AvailabilityResult r = service.resolve("ak-2");

        assertTrue(r.online());
    }

    // ------------------------------------------------------------------ evict

    @Test
    @DisplayName("evict: deletes Redis key")
    void evictDeletesKey() {
        when(redisTemplate.delete("ss:availability:ak-1")).thenReturn(true);

        service.evict("ak-1");

        verify(redisTemplate).delete("ss:availability:ak-1");
    }

    @Test
    @DisplayName("evict: Redis failure is silent (best-effort)")
    void evictRedisFailureIsSilent() {
        when(redisTemplate.delete("ss:availability:ak-1"))
                .thenThrow(new RedisConnectionFailureException("Connection refused"));

        assertDoesNotThrow(() -> service.evict("ak-1"));
    }

    @Test
    @DisplayName("evict: null ak skips")
    void evictNullAkSkips() {
        service.evict(null);
        service.evict("");
        service.evict("   ");
        verify(redisTemplate, never()).delete(anyString());
    }

    // ------------------------------------------------------------------ toolType normalization

    @Test
    @DisplayName("resolve: toolType is trimmed and lowercased")
    void resolveToolTypeNormalized() {
        when(gatewayApiClient.getAvailability("ak-3"))
                .thenReturn(new GatewayAvailabilityResponse(true, false, "  OpenCode  ", null));
        when(sysConfigService.getValue("assistant_offline", "tool_type:opencode"))
                .thenReturn("OpenCode 离线文案");
        when(valueOps.get("ss:availability:ak-3")).thenReturn(null);
        doNothing().when(valueOps).set(anyString(), anyString(), any(Duration.class));

        AvailabilityResult r = service.resolve("ak-3");

        assertEquals(AvailabilitySource.OFFLINE_TYPED, r.source());
        assertEquals("opencode", r.toolType());
    }

    @Test
    @DisplayName("resolve: toolType blank after trim → treated as null → OFFLINE_DEFAULT")
    void resolveToolTypeBlankIsNull() {
        when(gatewayApiClient.getAvailability("ak-4"))
                .thenReturn(new GatewayAvailabilityResponse(true, false, "   ", null));
        when(sysConfigService.getValue("assistant_offline", "message")).thenReturn("离线兜底");
        when(valueOps.get("ss:availability:ak-4")).thenReturn(null);
        doNothing().when(valueOps).set(anyString(), anyString(), any(Duration.class));

        AvailabilityResult r = service.resolve("ak-4");

        assertEquals(AvailabilitySource.OFFLINE_DEFAULT, r.source());
        assertNull(r.toolType());
    }
}
