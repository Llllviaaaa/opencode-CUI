package com.opencode.cui.gateway.service.cloud;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.http.HttpRequest;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 云端认证调度器。
 *
 * <p>通过 Spring 自动注入所有 {@link CloudAuthStrategy} 实现，
 * 按 {@code authType} 构建查找表，运行时按类型分派认证逻辑。</p>
 */
@Slf4j
@Service
public class CloudAuthService {

    private final Map<String, CloudAuthStrategy> strategyMap;

    public CloudAuthService(List<CloudAuthStrategy> strategies) {
        this.strategyMap = strategies.stream()
                .collect(Collectors.toMap(CloudAuthStrategy::getAuthType, Function.identity()));
        log.info("[CLOUD_AUTH] Registered auth strategies: {}", strategyMap.keySet());
    }

    /**
     * 按 authType 分派认证逻辑，向请求构建器注入认证 Header。
     *
     * @param requestBuilder HTTP 请求构建器
     * @param appId          云端应用 ID
     * @param authType       认证类型标识（soa / apig）
     * @throws IllegalArgumentException 未知 authType 时抛出
     */
    public void applyAuth(HttpRequest.Builder requestBuilder, String appId, String authType) {
        CloudAuthStrategy strategy = strategyMap.get(authType);
        if (strategy == null) {
            throw new IllegalArgumentException("Unknown cloud auth type: " + authType);
        }
        strategy.applyAuth(requestBuilder, appId);
    }
}
