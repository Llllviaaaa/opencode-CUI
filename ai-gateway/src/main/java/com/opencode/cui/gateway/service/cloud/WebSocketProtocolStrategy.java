package com.opencode.cui.gateway.service.cloud;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.gateway.config.CloudTimeoutProperties;
import com.opencode.cui.gateway.model.GatewayMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * WebSocket 协议策略实现。
 *
 * <p>通过 Java 11+ HttpClient WebSocket API 连接云端服务，发送请求体后接收流式事件。</p>
 *
 * <p>心跳机制：GW 定期发送 Ping，收到 Pong 时调用 {@code lifecycle.onHeartbeat()}。</p>
 */
@Slf4j
@Component
public class WebSocketProtocolStrategy implements CloudProtocolStrategy {

    private final CloudAuthService cloudAuthService;
    private final ObjectMapper objectMapper;
    private final CloudTimeoutProperties timeoutProperties;

    public WebSocketProtocolStrategy(CloudAuthService cloudAuthService,
                                     ObjectMapper objectMapper,
                                     CloudTimeoutProperties timeoutProperties) {
        this.cloudAuthService = cloudAuthService;
        this.objectMapper = objectMapper;
        this.timeoutProperties = timeoutProperties;
    }

    @Override
    public String getProtocol() {
        return "websocket";
    }

    @Override
    public void connect(CloudConnectionContext context, CloudConnectionLifecycle lifecycle,
                        Consumer<GatewayMessage> onEvent, Consumer<Throwable> onError) {
        CountDownLatch closeLatch = new CountDownLatch(1);
        ScheduledExecutorService pingScheduler = null;

        try {
            HttpClient httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(timeoutProperties.getConnectTimeoutSeconds()))
                    .build();

            WebSocket.Listener listener = createListener(lifecycle, onEvent, onError, closeLatch);

            WebSocket.Builder wsBuilder = httpClient.newWebSocketBuilder();
            wsBuilder.header("X-Trace-Id", context.getTraceId() != null ? context.getTraceId() : "");
            wsBuilder.header("X-App-Id", context.getAppId() != null ? context.getAppId() : "");

            log.info("[WS] Connecting: endpoint={}, appId={}, traceId={}",
                    context.getEndpoint(), context.getAppId(), context.getTraceId());

            WebSocket ws = wsBuilder
                    .buildAsync(URI.create(context.getEndpoint()), listener)
                    .get(timeoutProperties.getConnectTimeoutSeconds(), TimeUnit.SECONDS);

            if (lifecycle != null) lifecycle.onConnected();

            String requestBody = objectMapper.writeValueAsString(context.getCloudRequest());
            ws.sendText(requestBody, true).get(10, TimeUnit.SECONDS);

            // Start ping scheduler
            int pingInterval = timeoutProperties.getWebsocket().getPingIntervalSeconds();
            pingScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "ws-ping-timer");
                t.setDaemon(true);
                return t;
            });
            pingScheduler.scheduleAtFixedRate(() -> {
                try {
                    ws.sendPing(ByteBuffer.allocate(0)).get(5, TimeUnit.SECONDS);
                } catch (Exception e) {
                    log.warn("[WS] Ping failed: traceId={}, error={}", context.getTraceId(), e.getMessage());
                }
            }, pingInterval, pingInterval, TimeUnit.SECONDS);

            // Block until connection closes (same behavior as SSE synchronous read)
            closeLatch.await(timeoutProperties.getMaxDurationSeconds() + 30, TimeUnit.SECONDS);

        } catch (TimeoutException e) {
            log.error("[WS] Connect timeout: endpoint={}, traceId={}",
                    context.getEndpoint(), context.getTraceId());
            onError.accept(new RuntimeException("WebSocket connect timeout"));
        } catch (Exception e) {
            log.error("[WS] Connection error: endpoint={}, traceId={}, error={}",
                    context.getEndpoint(), context.getTraceId(), e.getMessage());
            onError.accept(e);
        } finally {
            if (pingScheduler != null) {
                pingScheduler.shutdownNow();
            }
        }
    }

    /**
     * Create WebSocket.Listener. Package-private for testing.
     */
    WebSocket.Listener createListener(CloudConnectionLifecycle lifecycle,
                                       Consumer<GatewayMessage> onEvent,
                                       Consumer<Throwable> onError) {
        return createListener(lifecycle, onEvent, onError, null);
    }

    /**
     * Create WebSocket.Listener with optional close latch.
     */
    WebSocket.Listener createListener(CloudConnectionLifecycle lifecycle,
                                       Consumer<GatewayMessage> onEvent,
                                       Consumer<Throwable> onError,
                                       CountDownLatch closeLatch) {
        return new WebSocket.Listener() {

            private final StringBuilder textBuffer = new StringBuilder();

            @Override
            public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                textBuffer.append(data);
                if (last) {
                    String json = textBuffer.toString();
                    textBuffer.setLength(0);
                    processMessage(json, lifecycle, onEvent, onError);
                }
                webSocket.request(1);
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public CompletionStage<?> onPong(WebSocket webSocket, ByteBuffer message) {
                if (lifecycle != null) lifecycle.onHeartbeat();
                webSocket.request(1);
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                log.info("[WS] Connection closed: statusCode={}, reason={}", statusCode, reason);
                if (closeLatch != null) closeLatch.countDown();
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public void onError(WebSocket webSocket, Throwable error) {
                log.error("[WS] Error: {}", error.getMessage());
                onError.accept(error);
                if (closeLatch != null) closeLatch.countDown();
            }
        };
    }

    private void processMessage(String json, CloudConnectionLifecycle lifecycle,
                                Consumer<GatewayMessage> onEvent, Consumer<Throwable> onError) {
        try {
            GatewayMessage message = objectMapper.readValue(json, GatewayMessage.class);
            if (lifecycle != null) lifecycle.onEventReceived();
            onEvent.accept(message);

            if (message.isType(GatewayMessage.Type.TOOL_DONE)
                    || message.isType(GatewayMessage.Type.TOOL_ERROR)) {
                if (lifecycle != null) lifecycle.onTerminalEvent();
            }
        } catch (Exception e) {
            log.warn("[WS] Failed to parse message: json={}, error={}", json, e.getMessage());
        }
    }
}
