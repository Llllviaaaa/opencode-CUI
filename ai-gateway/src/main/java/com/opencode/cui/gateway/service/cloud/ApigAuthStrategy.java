package com.opencode.cui.gateway.service.cloud;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.http.HttpRequest;

/**
 * APIG 认证策略（占位实现）。
 *
 * <p>实际 APIG 认证逻辑后续填充，当前设置占位 Header 标识认证类型。</p>
 */
@Slf4j
@Component
public class ApigAuthStrategy implements CloudAuthStrategy {

    @Override
    public String getAuthType() {
        return "apig";
    }

    @Override
    public void applyAuth(HttpRequest.Builder requestBuilder, String appId) {
        // 占位实现：设置认证类型和应用 ID Header
        requestBuilder.header("X-Auth-Type", "apig");
        requestBuilder.header("X-App-Id", appId);
        log.debug("[CLOUD_AUTH] Applied APIG auth: appId={}", appId);
    }
}
