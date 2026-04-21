package com.opencode.cui.gateway.service.cloud;

import java.net.http.HttpRequest;

/**
 * 云端认证策略接口。
 *
 * <p>不同云端服务使用不同的认证方式（SOA / APIG），
 * 通过策略模式实现统一的认证头注入。</p>
 */
public interface CloudAuthStrategy {

    /**
     * 返回当前策略支持的认证类型标识。
     *
     * @return 认证类型，如 {@code "soa"} 或 {@code "apig"}
     */
    String getAuthType();

    /**
     * 向 HTTP 请求构建器中注入认证相关的 Header。
     *
     * @param requestBuilder HTTP 请求构建器
     * @param appId          云端应用 ID
     */
    void applyAuth(HttpRequest.Builder requestBuilder, String appId);
}
