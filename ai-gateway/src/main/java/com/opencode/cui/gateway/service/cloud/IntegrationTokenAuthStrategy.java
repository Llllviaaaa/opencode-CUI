package com.opencode.cui.gateway.service.cloud;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.http.HttpRequest;

/**
 * 集成 token 鉴权策略（助手广场等场景）。
 *
 * <p>{@code authType="integration_token"} —— callback v2 API 返回 authType 数字码 3 时映射到此。</p>
 *
 * <p>token 通过配置项 {@code gateway.cloud.assistant-square.integration-token}
 * 注入（生产环境从 secret manager 注入）。token 未配置时抛 {@link IllegalStateException}，
 * 异常 msg 不含 token 原文，避免泄漏。</p>
 */
@Slf4j
@Component
public class IntegrationTokenAuthStrategy implements CloudAuthStrategy {

    public static final String AUTH_TYPE = "integration_token";

    private final String token;

    public IntegrationTokenAuthStrategy(
            @Value("${gateway.cloud.assistant-square.integration-token:}") String token) {
        this.token = token;
    }

    @Override
    public String getAuthType() {
        return AUTH_TYPE;
    }

    @Override
    public void applyAuth(HttpRequest.Builder requestBuilder, String appId) {
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("integration token not configured");
        }
        requestBuilder.header("Authorization", token);
        log.debug("[CLOUD_AUTH] Applied integration_token");
    }
}
