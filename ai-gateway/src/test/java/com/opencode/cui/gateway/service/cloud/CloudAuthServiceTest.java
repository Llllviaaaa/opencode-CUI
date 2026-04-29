package com.opencode.cui.gateway.service.cloud;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.http.HttpRequest;
import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CloudAuthService 单元测试（TDD）。
 *
 * <ul>
 *   <li>调度到正确的认证策略</li>
 *   <li>未知 authType 抛出异常</li>
 * </ul>
 */
class CloudAuthServiceTest {

    private CloudAuthService cloudAuthService;
    private SoaAuthStrategy soaStrategy;
    private ApigAuthStrategy apigStrategy;
    private NoAuthStrategy noAuthStrategy;

    @BeforeEach
    void setUp() {
        soaStrategy = new SoaAuthStrategy();
        apigStrategy = new ApigAuthStrategy();
        noAuthStrategy = new NoAuthStrategy();
        cloudAuthService = new CloudAuthService(List.of(soaStrategy, apigStrategy, noAuthStrategy));
    }

    @Nested
    @DisplayName("策略调度")
    class StrategyDispatchTests {

        @Test
        @DisplayName("authType=soa 时调度到 SoaAuthStrategy 并设置 SOA header")
        void shouldDispatchToSoaStrategy() {
            HttpRequest.Builder builder = HttpRequest.newBuilder().uri(URI.create("https://example.com"));

            cloudAuthService.applyAuth(builder, "app_123", "soa");

            HttpRequest request = builder.build();
            assertTrue(request.headers().firstValue("X-Auth-Type").isPresent());
            assertEquals("soa", request.headers().firstValue("X-Auth-Type").get());
            assertTrue(request.headers().firstValue("X-App-Id").isPresent());
            assertEquals("app_123", request.headers().firstValue("X-App-Id").get());
        }

        @Test
        @DisplayName("authType=apig 时调度到 ApigAuthStrategy 并设置 APIG header")
        void shouldDispatchToApigStrategy() {
            HttpRequest.Builder builder = HttpRequest.newBuilder().uri(URI.create("https://example.com"));

            cloudAuthService.applyAuth(builder, "app_456", "apig");

            HttpRequest request = builder.build();
            assertTrue(request.headers().firstValue("X-Auth-Type").isPresent());
            assertEquals("apig", request.headers().firstValue("X-Auth-Type").get());
            assertTrue(request.headers().firstValue("X-App-Id").isPresent());
            assertEquals("app_456", request.headers().firstValue("X-App-Id").get());
        }

        @Test
        @DisplayName("authType=none 时调度到 NoAuthStrategy 且不写入任何鉴权 header")
        void applyAuth_noneAuthType_noHeaderWritten() {
            HttpRequest.Builder builder = HttpRequest.newBuilder().uri(URI.create("https://x.example"));

            cloudAuthService.applyAuth(builder, null, "none");

            HttpRequest request = builder.GET().build();
            assertTrue(request.headers().firstValue("X-Auth-Type").isEmpty());
            assertTrue(request.headers().firstValue("X-App-Id").isEmpty());
        }
    }

    @Nested
    @DisplayName("未知 authType")
    class UnknownAuthTypeTests {

        @Test
        @DisplayName("未知 authType 抛出 IllegalArgumentException")
        void shouldThrowOnUnknownAuthType() {
            HttpRequest.Builder builder = HttpRequest.newBuilder().uri(URI.create("https://example.com"));

            IllegalArgumentException ex = assertThrows(
                    IllegalArgumentException.class,
                    () -> cloudAuthService.applyAuth(builder, "app_789", "unknown_type")
            );
            assertTrue(ex.getMessage().contains("unknown_type"));
        }

        @Test
        @DisplayName("null authType 抛出 IllegalArgumentException")
        void shouldThrowOnNullAuthType() {
            HttpRequest.Builder builder = HttpRequest.newBuilder().uri(URI.create("https://example.com"));

            assertThrows(
                    IllegalArgumentException.class,
                    () -> cloudAuthService.applyAuth(builder, "app_789", null)
            );
        }
    }
}
