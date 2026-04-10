package com.opencode.cui.gateway.service.cloud;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.gateway.model.GatewayMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * CloudProtocolClient 单元测试（TDD）。
 *
 * <ul>
 *   <li>调度到正确的协议策略</li>
 *   <li>未知 protocol 报错</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class CloudProtocolClientTest {

    @Mock
    private CloudProtocolStrategy sseStrategy;

    @Mock
    private CloudProtocolStrategy wsStrategy;

    private CloudProtocolClient client;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        when(sseStrategy.getProtocol()).thenReturn("sse");
        when(wsStrategy.getProtocol()).thenReturn("websocket");
        client = new CloudProtocolClient(List.of(sseStrategy, wsStrategy));
    }

    @Nested
    @DisplayName("策略调度")
    class StrategyDispatchTests {

        @Test
        @DisplayName("protocol=sse 时调度到 SSE 策略")
        void shouldDispatchToSseStrategy() {
            CloudConnectionContext context = CloudConnectionContext.builder()
                    .endpoint("https://example.com/chat")
                    .cloudRequest(objectMapper.createObjectNode())
                    .appId("app_123")
                    .authType("soa")
                    .traceId("trace-001")
                    .build();
            Consumer<GatewayMessage> onEvent = msg -> {};
            Consumer<Throwable> onError = err -> {};

            client.connect("sse", context, onEvent, onError);

            verify(sseStrategy).connect(eq(context), eq(onEvent), eq(onError));
            verify(wsStrategy, never()).connect(any(), any(), any());
        }

        @Test
        @DisplayName("protocol=websocket 时调度到 WebSocket 策略")
        void shouldDispatchToWebSocketStrategy() {
            CloudConnectionContext context = CloudConnectionContext.builder()
                    .endpoint("https://ws.example.com/stream")
                    .cloudRequest(objectMapper.createObjectNode())
                    .appId("app_456")
                    .authType("apig")
                    .traceId("trace-002")
                    .build();
            Consumer<GatewayMessage> onEvent = msg -> {};
            Consumer<Throwable> onError = err -> {};

            client.connect("websocket", context, onEvent, onError);

            verify(wsStrategy).connect(eq(context), eq(onEvent), eq(onError));
            verify(sseStrategy, never()).connect(any(), any(), any());
        }
    }

    @Nested
    @DisplayName("未知 protocol")
    class UnknownProtocolTests {

        @Test
        @DisplayName("未知 protocol 调用 onError 回调")
        void shouldCallOnErrorForUnknownProtocol() {
            CloudConnectionContext context = CloudConnectionContext.builder()
                    .endpoint("https://example.com")
                    .cloudRequest(objectMapper.createObjectNode())
                    .appId("app_789")
                    .authType("soa")
                    .traceId("trace-003")
                    .build();

            List<Throwable> errors = new ArrayList<>();
            Consumer<GatewayMessage> onEvent = msg -> {};
            Consumer<Throwable> onError = errors::add;

            client.connect("grpc", context, onEvent, onError);

            assertEquals(1, errors.size());
            assertTrue(errors.get(0).getMessage().contains("grpc"));
        }

        @Test
        @DisplayName("null protocol 调用 onError 回调")
        void shouldCallOnErrorForNullProtocol() {
            CloudConnectionContext context = CloudConnectionContext.builder()
                    .endpoint("https://example.com")
                    .cloudRequest(objectMapper.createObjectNode())
                    .appId("app_789")
                    .authType("soa")
                    .traceId("trace-003")
                    .build();

            List<Throwable> errors = new ArrayList<>();
            Consumer<GatewayMessage> onEvent = msg -> {};
            Consumer<Throwable> onError = errors::add;

            client.connect(null, context, onEvent, onError);

            assertEquals(1, errors.size());
        }
    }
}
