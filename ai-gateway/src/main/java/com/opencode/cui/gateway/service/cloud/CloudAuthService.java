package com.opencode.cui.gateway.service.cloud;

import com.opencode.cui.gateway.model.AssistantInstanceInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.http.HttpRequest;
import java.net.http.WebSocket;
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

    public void applyAuth(HttpRequest.Builder requestBuilder, String appId, String authType,
                          List<AssistantInstanceInfo.RemoteHeader> remoteHeaders) {
        if (remoteHeaders != null && !remoteHeaders.isEmpty()) {
            applyRemoteHeaders(requestBuilder, remoteHeaders);
            return;
        }
        applyAuth(requestBuilder, appId, authType);
    }

    public void applyRemoteHeaders(WebSocket.Builder builder,
                                   List<AssistantInstanceInfo.RemoteHeader> remoteHeaders) {
        if (remoteHeaders == null || remoteHeaders.isEmpty()) {
            return;
        }
        for (AssistantInstanceInfo.RemoteHeader header : remoteHeaders) {
            HeaderPair pair = toHeaderPair(header);
            builder.header(pair.name(), pair.value());
        }
    }

    private void applyRemoteHeaders(HttpRequest.Builder builder,
                                    List<AssistantInstanceInfo.RemoteHeader> remoteHeaders) {
        for (AssistantInstanceInfo.RemoteHeader header : remoteHeaders) {
            HeaderPair pair = toHeaderPair(header);
            builder.header(pair.name(), pair.value());
        }
    }

    private HeaderPair toHeaderPair(AssistantInstanceInfo.RemoteHeader header) {
        if (header == null || header.getType() == null || header.getType().isBlank()) {
            throw new IllegalArgumentException("remote header type is required");
        }
        String type = header.getType().trim().toLowerCase();
        String customKey = blankToNull(header.getCustomKey());
        String customValue = blankToNull(header.getCustomValue());
        return switch (type) {
            case "custom" -> {
                if (customKey == null || customValue == null) {
                    throw new IllegalArgumentException("custom remote header requires customKey/customValue");
                }
                yield new HeaderPair(customKey, customValue);
            }
            case "cookie" -> {
                if (customValue == null) {
                    throw new IllegalArgumentException("cookie remote header requires customValue");
                }
                yield new HeaderPair("Cookie", customValue);
            }
            case "integration" -> {
                if (customValue == null) {
                    throw new IllegalArgumentException("integration remote header requires customValue");
                }
                yield new HeaderPair(customKey != null ? customKey : "Authorization", customValue);
            }
            case "soa", "apig", "iam" -> {
                if (customKey != null && customValue != null) {
                    yield new HeaderPair(customKey, customValue);
                }
                yield new HeaderPair("X-Auth-Type", type);
            }
            default -> throw new IllegalArgumentException("Unknown remote header type: " + type);
        };
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private record HeaderPair(String name, String value) {
    }
}
