package com.opencode.cui.gateway.service.cloud;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.gateway.model.GatewayMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.function.Consumer;

/**
 * WebHook 同步执行器（q_r / p_r 旁路）。
 *
 * <p>不进 {@link CloudConnectionLifecycle} 抽象。2xx 响应 fire-and-forget（不回流），
 * 失败（非 2xx / 网络 / 超时）回流 {@link GatewayMessage.Type#TOOL_ERROR}。不重试。</p>
 */
@Slf4j
@Component
public class WebHookExecutor {

    private final ObjectMapper objectMapper;
    private final CloudAuthService cloudAuthService;
    private final HttpClient httpClient;
    private final int webhookTimeoutSeconds;

    public WebHookExecutor(ObjectMapper objectMapper,
                           CloudAuthService cloudAuthService,
                           @Value("${gateway.cloud.timeout.webhook-timeout-seconds:10}") int webhookTimeoutSeconds) {
        this.objectMapper = objectMapper;
        this.cloudAuthService = cloudAuthService;
        this.webhookTimeoutSeconds = webhookTimeoutSeconds;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public void execute(CloudConnectionContext ctx,
                        Consumer<GatewayMessage> onRelay,
                        GatewayMessage invokeMessage,
                        String toolSessionId) {
        try {
            String body = objectMapper.writeValueAsString(ctx.getCloudRequest());
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(ctx.getChannelAddress()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofSeconds(webhookTimeoutSeconds));
            if (ctx.getTraceId() != null) {
                builder.header("X-Trace-Id", ctx.getTraceId());
            }
            // X-App-Id 由 cloudAuthService.applyAuth 内部 strategy（Soa/Apig）按需写入；
            // NoAuthStrategy 不写。这里不再重复写入避免出现两个同名 header。
            if (ctx.getRemoteHeaders() == null || ctx.getRemoteHeaders().isEmpty()) {
                cloudAuthService.applyAuth(builder, ctx.getAppId(), ctx.getAuthType());
            } else {
                cloudAuthService.applyAuth(builder, ctx.getAppId(), ctx.getAuthType(), ctx.getRemoteHeaders());
            }

            HttpResponse<String> resp = sendRequest(builder.build());
            if (resp.statusCode() / 100 == 2) {
                log.info("[WEBHOOK] success: scope={}, status={}, traceId={}",
                        ctx.getScope(), resp.statusCode(), ctx.getTraceId());
                // fire-and-forget：不调 onRelay
            } else {
                log.warn("[WEBHOOK] non-2xx: scope={}, status={}, traceId={}",
                        ctx.getScope(), resp.statusCode(), ctx.getTraceId());
                onRelay.accept(buildToolError(invokeMessage, toolSessionId,
                        "WebHook returned " + resp.statusCode()));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[WEBHOOK] interrupted: scope={}", ctx.getScope());
            onRelay.accept(buildToolError(invokeMessage, toolSessionId,
                    "WebHook interrupted"));
        } catch (Exception e) {
            log.warn("[WEBHOOK] error: scope={}, error={}", ctx.getScope(), e.getMessage());
            onRelay.accept(buildToolError(invokeMessage, toolSessionId,
                    "WebHook delivery failed: " + e.getMessage()));
        }
    }

    /** 暴露给测试 mock */
    protected HttpResponse<String> sendRequest(HttpRequest req) throws Exception {
        return httpClient.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private GatewayMessage buildToolError(GatewayMessage src, String tsid, String msg) {
        return GatewayMessage.builder()
                .type(GatewayMessage.Type.TOOL_ERROR)
                .ak(src.getAk())
                .userId(src.getUserId())
                .welinkSessionId(src.getWelinkSessionId())
                .traceId(src.getTraceId())
                .toolSessionId(tsid)
                .error(msg)
                .build();
    }
}
