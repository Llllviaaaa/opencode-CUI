package com.opencode.cui.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * IdentityApiClient 单元测试。
 */
class IdentityApiClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("未配置 base-url 时 isEnabled 返回 false")
    void disabledWhenNoBaseUrl() {
        IdentityApiClient client = new IdentityApiClient(objectMapper, "", "", 3000);
        assertFalse(client.isEnabled());
    }

    @Test
    @DisplayName("配置 base-url 时 isEnabled 返回 true")
    void enabledWhenBaseUrlConfigured() {
        IdentityApiClient client = new IdentityApiClient(
                objectMapper, "http://localhost:9090", "token", 3000);
        assertTrue(client.isEnabled());
    }

    @Test
    @DisplayName("未配置时调用 check 抛出 IdentityApiException")
    void checkThrowsWhenDisabled() {
        IdentityApiClient client = new IdentityApiClient(objectMapper, "", "", 3000);
        assertThrows(IdentityApiClient.IdentityApiException.class,
                () -> client.check("ak", "123", "nonce", "sign"));
    }

    @Test
    @DisplayName("API 不可达时抛出 IdentityApiException")
    void checkThrowsOnUnreachableHost() {
        IdentityApiClient client = new IdentityApiClient(
                objectMapper, "http://192.0.2.1:1", "token", 500);
        assertThrows(IdentityApiClient.IdentityApiException.class,
                () -> client.check("ak", "123", "nonce", "sign"));
    }
}
