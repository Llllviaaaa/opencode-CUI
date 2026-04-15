package com.opencode.cui.gateway.service.cloud;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.gateway.model.GatewayMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * SSE 协议策略实现。
 *
 * <p>通过 HTTP POST 发送请求，读取 SSE 流，逐行解析 {@code data: {JSON}} 格式的事件。</p>
 *
 * <p>使用 Java 11+ HttpClient 同步模式（后续可优化为异步）。
 * HttpClient 通过构造器注入以便测试时 mock。</p>
 */
@Slf4j
@Component
public class SseProtocolStrategy implements CloudProtocolStrategy {

    private final CloudAuthService cloudAuthService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @org.springframework.beans.factory.annotation.Autowired
    public SseProtocolStrategy(CloudAuthService cloudAuthService, ObjectMapper objectMapper) {
        this(cloudAuthService, objectMapper,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    /**
     * 测试友好构造器，允许注入自定义 HttpClient。
     */
    public SseProtocolStrategy(CloudAuthService cloudAuthService, ObjectMapper objectMapper, HttpClient httpClient) {
        this.cloudAuthService = cloudAuthService;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    @Override
    public String getProtocol() {
        return "sse";
    }

    @Override
    public void connect(CloudConnectionContext context, Consumer<GatewayMessage> onEvent, Consumer<Throwable> onError) {
        try {
            // 1. 构建请求体
            String requestBody = objectMapper.writeValueAsString(context.getCloudRequest());

            // 2. 构建 HTTP POST 请求
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(context.getEndpoint()))
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .header("X-Trace-Id", context.getTraceId() != null ? context.getTraceId() : "")
                    .header("X-Request-Id", UUID.randomUUID().toString())
                    .header("X-App-Id", context.getAppId() != null ? context.getAppId() : "")
                    // 不设请求级超时，由云端关闭 SSE 流来结束连接
                    ;

            // 3. 注入认证头
            cloudAuthService.applyAuth(requestBuilder, context.getAppId(), context.getAuthType());

            // 4. 发送请求
            HttpRequest request = requestBuilder.build();
            log.info("[SSE] Connecting: endpoint={}, appId={}, traceId={}",
                    context.getEndpoint(), context.getAppId(), context.getTraceId());

            HttpResponse<InputStream> response = sendRequest(request);

            if (response.statusCode() != 200) {
                onError.accept(new RuntimeException(
                        "SSE connection failed: HTTP " + response.statusCode()));
                return;
            }

            // 5. 读取 SSE 流
            readSseStream(response.body(), onEvent, onError, context.getTraceId());

        } catch (Exception e) {
            log.error("[SSE] Connection error: endpoint={}, traceId={}, error={}",
                    context.getEndpoint(), context.getTraceId(), e.getMessage());
            onError.accept(e);
        }
    }

    /**
     * 发送 HTTP 请求。声明为 protected 以便测试时 mock。
     */
    protected HttpResponse<InputStream> sendRequest(HttpRequest request) throws Exception {
        return httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
    }

    /**
     * 读取 SSE 流并解析事件。
     */
    private void readSseStream(InputStream inputStream,
                               Consumer<GatewayMessage> onEvent,
                               Consumer<Throwable> onError,
                               String traceId) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("data:")) {
                    String jsonData = line.substring(5).trim();
                    if (jsonData.isEmpty() || "[DONE]".equals(jsonData)) {
                        continue;
                    }
                    try {
                        GatewayMessage message = objectMapper.readValue(jsonData, GatewayMessage.class);
                        onEvent.accept(message);
                    } catch (Exception e) {
                        log.warn("[SSE] Failed to parse event: traceId={}, data={}, error={}",
                                traceId, jsonData, e.getMessage());
                    }
                }
            }
            log.info("[SSE] Stream completed: traceId={}", traceId);
        } catch (Exception e) {
            log.error("[SSE] Stream read error: traceId={}, error={}", traceId, e.getMessage());
            onError.accept(e);
        }
    }
}
