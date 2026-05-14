package com.opencode.cui.gateway.service.cloud;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IntegrationTokenAuthStrategyTest {

    @Test
    void getAuthType_returnsIntegrationToken() {
        IntegrationTokenAuthStrategy strategy = new IntegrationTokenAuthStrategy("tk");
        assertThat(strategy.getAuthType()).isEqualTo("integration_token");
    }

    @Test
    void applyAuth_writesAuthorizationHeaderDirectly() {
        IntegrationTokenAuthStrategy strategy = new IntegrationTokenAuthStrategy("tk-123");
        HttpRequest.Builder b = HttpRequest.newBuilder().uri(URI.create("https://example.com")).GET();
        strategy.applyAuth(b, "any-appid");
        HttpRequest req = b.build();
        assertThat(req.headers().firstValue("Authorization")).hasValue("tk-123");
    }

    @Test
    void applyAuth_blankToken_throwsWithoutLeakingToken() {
        IntegrationTokenAuthStrategy strategy = new IntegrationTokenAuthStrategy("");
        HttpRequest.Builder b = HttpRequest.newBuilder().uri(URI.create("https://example.com")).GET();
        assertThatThrownBy(() -> strategy.applyAuth(b, "any-appid"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("integration token not configured");
    }

    @Test
    void applyAuth_nullToken_throws() {
        IntegrationTokenAuthStrategy strategy = new IntegrationTokenAuthStrategy(null);
        HttpRequest.Builder b = HttpRequest.newBuilder().uri(URI.create("https://example.com")).GET();
        assertThatThrownBy(() -> strategy.applyAuth(b, "any-appid"))
                .isInstanceOf(IllegalStateException.class);
    }
}
