package com.opencode.cui.gateway.service.cloud;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.WebSocket;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class CloudAuthServiceTest {

    private CloudAuthService cloudAuthService;

    @BeforeEach
    void setUp() {
        cloudAuthService = new CloudAuthService(List.of(
                new SoaAuthStrategy(),
                new ApigAuthStrategy(),
                new NoAuthStrategy(),
                new IntegrationTokenAuthStrategy("tk-123")));
    }

    @Nested
    @DisplayName("strategy dispatch")
    class StrategyDispatchTests {

        @Test
        @DisplayName("authType=soa dispatches to SoaAuthStrategy")
        void shouldDispatchToSoaStrategy() {
            HttpRequest.Builder builder = HttpRequest.newBuilder().uri(URI.create("https://example.com"));

            cloudAuthService.applyAuth(builder, "app_123", "soa");

            HttpRequest request = builder.build();
            assertEquals("soa", request.headers().firstValue("X-Auth-Type").orElse(null));
            assertEquals("app_123", request.headers().firstValue("X-App-Id").orElse(null));
        }

        @Test
        @DisplayName("authType=apig dispatches to ApigAuthStrategy")
        void shouldDispatchToApigStrategy() {
            HttpRequest.Builder builder = HttpRequest.newBuilder().uri(URI.create("https://example.com"));

            cloudAuthService.applyAuth(builder, "app_456", "apig");

            HttpRequest request = builder.build();
            assertEquals("apig", request.headers().firstValue("X-Auth-Type").orElse(null));
            assertEquals("app_456", request.headers().firstValue("X-App-Id").orElse(null));
        }

        @Test
        @DisplayName("authType=none writes no auth headers")
        void applyAuth_noneAuthType_noHeaderWritten() {
            HttpRequest.Builder builder = HttpRequest.newBuilder().uri(URI.create("https://x.example"));

            cloudAuthService.applyAuth(builder, null, "none");

            HttpRequest request = builder.GET().build();
            assertTrue(request.headers().firstValue("X-Auth-Type").isEmpty());
            assertTrue(request.headers().firstValue("X-App-Id").isEmpty());
        }

        @Test
        @DisplayName("soa skips X-App-Id when appId is null")
        void soaStrategy_appIdNull_skipsXAppIdHeader() {
            HttpRequest.Builder builder = HttpRequest.newBuilder().uri(URI.create("https://x.example"));

            cloudAuthService.applyAuth(builder, null, "soa");

            HttpRequest request = builder.GET().build();
            assertEquals("soa", request.headers().firstValue("X-Auth-Type").orElse(null));
            assertTrue(request.headers().allValues("X-App-Id").isEmpty());
        }

        @Test
        @DisplayName("soa writes X-App-Id exactly once")
        void soaStrategy_appIdPresent_writesXAppIdExactlyOnce() {
            HttpRequest.Builder builder = HttpRequest.newBuilder().uri(URI.create("https://x.example"));

            cloudAuthService.applyAuth(builder, "app_only_once", "soa");

            HttpRequest request = builder.GET().build();
            assertEquals(List.of("app_only_once"), request.headers().allValues("X-App-Id"));
        }

        @Test
        @DisplayName("resolveAuthHeaders exposes generated auth headers for request logging")
        void resolveAuthHeaders_returnsGeneratedHeaders() {
            Map<String, List<String>> headers = cloudAuthService.resolveAuthHeaders("app_for_log", "soa");

            assertEquals(List.of("soa"), headers.get("X-Auth-Type"));
            assertEquals(List.of("app_for_log"), headers.get("X-App-Id"));
        }

        @Test
        @DisplayName("apig skips X-App-Id when appId is null")
        void apigStrategy_appIdNull_skipsXAppIdHeader() {
            HttpRequest.Builder builder = HttpRequest.newBuilder().uri(URI.create("https://x.example"));

            cloudAuthService.applyAuth(builder, null, "apig");

            HttpRequest request = builder.GET().build();
            assertEquals("apig", request.headers().firstValue("X-Auth-Type").orElse(null));
            assertTrue(request.headers().allValues("X-App-Id").isEmpty());
        }

        @Test
        @DisplayName("WebSocket authType=soa dispatches to SoaAuthStrategy")
        void applyAuth_webSocketBuilder_dispatchesSoaStrategyHeaders() {
            WebSocket.Builder builder = mock(WebSocket.Builder.class);
            when(builder.header(anyString(), anyString())).thenReturn(builder);

            cloudAuthService.applyAuth(builder, "app_ws", "soa");

            verify(builder).header("X-Auth-Type", "soa");
            verify(builder).header("X-App-Id", "app_ws");
            verify(builder, never()).header(eq("X-Bot-Token"), anyString());
        }

        @Test
        @DisplayName("WebSocket authType=integration_token dispatches to IntegrationTokenAuthStrategy")
        void applyAuth_webSocketBuilder_dispatchesIntegrationTokenStrategy() {
            WebSocket.Builder builder = mock(WebSocket.Builder.class);
            when(builder.header(anyString(), anyString())).thenReturn(builder);

            cloudAuthService.applyAuth(builder, "ignored-app", "integration_token");

            verify(builder).header("Authorization", "tk-123");
            verify(builder, never()).header(eq("X-App-Id"), anyString());
        }
    }

    @Nested
    @DisplayName("unknown authType")
    class UnknownAuthTypeTests {

        @Test
        @DisplayName("unknown authType throws IllegalArgumentException")
        void shouldThrowOnUnknownAuthType() {
            HttpRequest.Builder builder = HttpRequest.newBuilder().uri(URI.create("https://example.com"));

            IllegalArgumentException ex = assertThrows(
                    IllegalArgumentException.class,
                    () -> cloudAuthService.applyAuth(builder, "app_789", "unknown_type"));
            assertTrue(ex.getMessage().contains("unknown_type"));
        }

        @Test
        @DisplayName("null authType throws IllegalArgumentException")
        void shouldThrowOnNullAuthType() {
            HttpRequest.Builder builder = HttpRequest.newBuilder().uri(URI.create("https://example.com"));

            assertThrows(
                    IllegalArgumentException.class,
                    () -> cloudAuthService.applyAuth(builder, "app_789", null));
        }

        @Test
        @DisplayName("WebSocket unknown authType throws IllegalArgumentException")
        void webSocketBuilder_shouldThrowOnUnknownAuthType() {
            WebSocket.Builder builder = mock(WebSocket.Builder.class);

            IllegalArgumentException ex = assertThrows(
                    IllegalArgumentException.class,
                    () -> cloudAuthService.applyAuth(builder, null, "mystery"));
            assertTrue(ex.getMessage().contains("mystery"));
        }
    }
}
