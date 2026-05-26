package com.opencode.cui.gateway.service.cloud;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.gateway.logging.GatewayStreamEventLogHelper;
import com.opencode.cui.gateway.logging.MdcHelper;
import com.opencode.cui.gateway.model.GatewayMessage;
import com.opencode.cui.gateway.service.cloud.decoder.DecoderSession;
import com.opencode.cui.gateway.service.cloud.decoder.SseEventDecoder;
import com.opencode.cui.gateway.service.cloud.decoder.SseEventDecoderFactory;
import com.opencode.cui.gateway.service.cloud.profile.CloudResponseProfile;
import com.opencode.cui.gateway.service.cloud.profile.CloudResponseProfileRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * SSE 协议策略实现。
 *
 * <p>通过 HTTP POST 发送请求，读取 SSE 流，逐行解析 {@code data: {JSON}} 格式的事件。
 * 实际事件解析委托给 {@link SseEventDecoder}（按 {@code cloudProfile} 解析），
 * 让本类只承担传输和生命周期编排，不耦合具体协议格式。</p>
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
    private final SseEventDecoderFactory decoderFactory;
    private final CloudResponseProfileRegistry profileRegistry;

    @org.springframework.beans.factory.annotation.Autowired
    public SseProtocolStrategy(CloudAuthService cloudAuthService, ObjectMapper objectMapper,
            SseEventDecoderFactory decoderFactory,
            CloudResponseProfileRegistry profileRegistry,
            @org.springframework.beans.factory.annotation.Value("${gateway.cloud.connect-timeout-seconds:30}") int connectTimeoutSeconds) {
        this(cloudAuthService, objectMapper, decoderFactory, profileRegistry,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(connectTimeoutSeconds)).build());
    }

    /**
     * 测试友好构造器，允许注入自定义 HttpClient。
     */
    public SseProtocolStrategy(CloudAuthService cloudAuthService, ObjectMapper objectMapper,
                               SseEventDecoderFactory decoderFactory,
                               CloudResponseProfileRegistry profileRegistry,
                               HttpClient httpClient) {
        this.cloudAuthService = cloudAuthService;
        this.objectMapper = objectMapper;
        this.decoderFactory = decoderFactory;
        this.profileRegistry = profileRegistry;
        this.httpClient = httpClient;
    }

    @Override
    public String getProtocol() {
        return "sse";
    }

    @Override
    public void connect(CloudConnectionContext context, CloudConnectionLifecycle lifecycle,
                        Consumer<GatewayMessage> onEvent, Consumer<Throwable> onError) {
        CloudConnectionHandle connectionHandle = context.getConnectionHandle();
        // 通过 Registry 拼装 profile（查 cloud_protocol_profile_def:<name>，缺失则约定 fallback profile==decoder）
        CloudResponseProfile profile = profileRegistry.resolve(context.getCloudProfile());
        SseEventDecoder decoder = decoderFactory.resolveDecoder(profile.responseDecoderName());
        DecoderSession session = decoder.createSession();
        try {
            // 1. 构建请求体
            String requestBody = objectMapper.writeValueAsString(context.getCloudRequest());

            // 2. 构建 HTTP POST 请求
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(context.getChannelAddress()))
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .header("X-Trace-Id", context.getTraceId() != null ? context.getTraceId() : "")
                    .header("X-Request-Id", UUID.randomUUID().toString())
                    // 不设请求级超时，由云端关闭 SSE 流来结束连接
                    ;

            // 3. 注入认证头：X-App-Id 由 cloudAuthService 内部 strategy（Soa/Apig）按需写入；
            //    NoAuthStrategy 不写。这里不再重复写入避免出现两个同名 header。
            cloudAuthService.applyAuth(requestBuilder, context.getAppId(), context.getAuthType());

            // 4. 发送请求
            HttpRequest request = requestBuilder.build();
            log.info("[SSE] Connecting: endpoint={}, appId={}, traceId={}, cloudProfile={}, decoder={}",
                    context.getChannelAddress(), context.getAppId(), context.getTraceId(),
                    profile.name(), profile.responseDecoderName());

            HttpResponse<InputStream> response = sendRequest(request);
            InputStream responseBody = response.body();
            if (responseBody != null) {
                connectionHandleOnCancel(connectionHandle,
                        () -> closeQuietly(responseBody, context.getTraceId()));
            }

            if (response.statusCode() != 200) {
                closeQuietly(responseBody, context.getTraceId());
                onError.accept(new RuntimeException(
                        "SSE connection failed: HTTP " + response.statusCode()));
                return;
            }
            if (isCancelled(connectionHandle)) {
                closeQuietly(responseBody, context.getTraceId());
                return;
            }

            // 4.1 通知 lifecycle 已连接
            if (lifecycle != null) {
                lifecycle.onConnected();
            }

            // 5. 读取 SSE 流
            readSseStream(responseBody, lifecycle, onEvent, onError,
                    context.getTraceId(), decoder, session, connectionHandle);

        } catch (Exception e) {
            if (isCancelled(connectionHandle)) {
                log.info("[SSE] Connection cancelled: endpoint={}, traceId={}",
                        context.getChannelAddress(), context.getTraceId());
                return;
            }
            log.error("[SSE] Connection error: endpoint={}, traceId={}, error={}",
                    context.getChannelAddress(), context.getTraceId(), e.getMessage());
            onError.accept(e);
        } finally {
            if (isCancelled(connectionHandle)) {
                log.info("[SSE] Skip decoder flush after cancellation: traceId={}", context.getTraceId());
                return;
            }
            // 流终止 / 异常 / 超时三路径都补 flush
            try {
                List<GatewayMessage> tail = decoder.flush(session);
                if (tail != null) {
                    for (GatewayMessage m : tail) {
                        onEvent.accept(m);
                    }
                }
            } catch (Exception flushErr) {
                log.warn("[SSE] decoder flush error: traceId={}, error={}",
                        context.getTraceId(), flushErr.getMessage());
            }
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
                               CloudConnectionLifecycle lifecycle,
                               Consumer<GatewayMessage> onEvent,
                               Consumer<Throwable> onError,
                               String traceId,
                               SseEventDecoder decoder,
                               DecoderSession session,
                               CloudConnectionHandle connectionHandle) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while (!isCancelled(connectionHandle) && (line = reader.readLine()) != null) {
                if (line.startsWith(":")) {
                    // SSE 注释行（心跳）
                    notifyLifecycle(lifecycle, CloudConnectionLifecycle::onHeartbeat);
                    continue;
                }
                if (line.startsWith("data:")
                        && handleDataLine(line, lifecycle, onEvent, traceId, decoder, session, connectionHandle)) {
                    return;
                }
            }
            if (isCancelled(connectionHandle)) {
                log.info("[SSE] Stream cancelled: traceId={}", traceId);
                return;
            }
            log.info("[SSE] Stream completed: traceId={}", traceId);
        } catch (Exception e) {
            if (isCancelled(connectionHandle)) {
                log.info("[SSE] Stream read cancelled: traceId={}", traceId);
                return;
            }
            log.error("[SSE] Stream read error: traceId={}, error={}", traceId, e.getMessage());
            onError.accept(e);
        }
    }

    /**
     * 解析 SSE data 行并分发事件。
     *
     * @return true 表示遇到终态事件，调用方应停止读取
     */
    private boolean handleDataLine(String line, CloudConnectionLifecycle lifecycle,
                                   Consumer<GatewayMessage> onEvent, String traceId,
                                   SseEventDecoder decoder, DecoderSession session,
                                   CloudConnectionHandle connectionHandle) {
        String jsonData = line.substring(5).trim();
        if (jsonData.isEmpty()) {
            return false;
        }
        // 业务层心跳：识别后只重置 lifecycle，不下发，不进 decode
        if (decoder.isHeartbeat(jsonData)) {
            notifyLifecycle(lifecycle, CloudConnectionLifecycle::onHeartbeat);
            return false;
        }
        // 终止符：触发 flush（由 finally 块统一执行）+ onTerminalEvent，并停止读取
        if (decoder.isTerminator(jsonData)) {
            notifyLifecycle(lifecycle, CloudConnectionLifecycle::onTerminalEvent);
            return true;
        }
        logSseInbound(jsonData, traceId);
        try {
            List<GatewayMessage> messages = decoder.decode(jsonData, session);
            if (messages == null || messages.isEmpty()) {
                return false;
            }
            boolean terminal = false;
            for (GatewayMessage message : messages) {
                if (isCancelled(connectionHandle)) {
                    return true;
                }
                dispatchIdleTimerByEventType(lifecycle, message);
                notifyLifecycle(lifecycle, CloudConnectionLifecycle::onEventReceived);
                onEvent.accept(message);
                if (message.isType(GatewayMessage.Type.TOOL_DONE)
                        || message.isType(GatewayMessage.Type.TOOL_ERROR)) {
                    notifyLifecycle(lifecycle, CloudConnectionLifecycle::onTerminalEvent);
                    terminal = true;
                }
            }
            return terminal;
        } catch (Exception e) {
            log.warn("[SSE] Failed to parse event: traceId={}, data={}, error={}",
                    traceId, jsonData, e.getMessage());
        }
        return false;
    }

    private void logSseInbound(String payload, String traceId) {
        var previousMdc = MdcHelper.snapshot();
        try {
            MdcHelper.putTraceId(traceId);
            MdcHelper.putScenario("cloud-agent-sse-rx");
            GatewayStreamEventLogHelper.inbound(log, "gw.cloud_agent", "received", payload);
        } finally {
            MdcHelper.restore(previousMdc);
        }
    }

    private static void notifyLifecycle(CloudConnectionLifecycle lifecycle,
                                        Consumer<CloudConnectionLifecycle> action) {
        if (lifecycle != null) {
            action.accept(lifecycle);
        }
    }

    private static boolean isCancelled(CloudConnectionHandle connectionHandle) {
        return connectionHandle != null && connectionHandle.isCancelled();
    }

    private static void connectionHandleOnCancel(CloudConnectionHandle connectionHandle, Runnable action) {
        if (connectionHandle != null) {
            connectionHandle.onCancel(action);
        }
    }

    private static void closeQuietly(InputStream inputStream, String traceId) {
        if (inputStream == null) {
            return;
        }
        try {
            inputStream.close();
        } catch (IOException e) {
            log.debug("[SSE] Close stream failed: traceId={}, error={}", traceId, e.getMessage());
        }
    }

    /**
     * T13: 根据 event.type 派发 idle timer 操作。
     *
     * <ul>
     *   <li>{@code "question"} / {@code "permission.ask"} → pause（等待用户回复，关闭 idle 计时）</li>
     *   <li>其他事件类型 → resume（assistant 仍在产出，恢复 idle 计时）</li>
     *   <li>{@code "permission.reply"} 是 assistant role 应答事件，不触发 pause（落入 resume 分支）</li>
     * </ul>
     */
    private static void dispatchIdleTimerByEventType(CloudConnectionLifecycle lifecycle,
                                                     GatewayMessage message) {
        if (lifecycle == null || message == null || message.getEvent() == null) {
            return;
        }
        String eventType = message.getEvent().path("type").asText("");
        if ("question".equals(eventType) || "permission.ask".equals(eventType)) {
            lifecycle.pauseIdleTimer();
        } else {
            lifecycle.resumeIdleTimer();
        }
    }
}
