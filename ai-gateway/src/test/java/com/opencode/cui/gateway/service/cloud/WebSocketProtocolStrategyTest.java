package com.opencode.cui.gateway.service.cloud;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.gateway.config.CloudTimeoutProperties;
import com.opencode.cui.gateway.model.GatewayMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WebSocketProtocolStrategyTest {

    @Mock
    private CloudAuthService cloudAuthService;
    @Mock
    private CloudConnectionLifecycle lifecycle;
    @Mock
    private CloudTimeoutProperties.WsOverride wsOverride;
    @Mock
    private CloudTimeoutProperties timeoutProperties;

    private ObjectMapper objectMapper;
    private List<GatewayMessage> receivedEvents;
    private List<Throwable> receivedErrors;
    private Consumer<GatewayMessage> onEvent;
    private Consumer<Throwable> onError;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        receivedEvents = new ArrayList<>();
        receivedErrors = new ArrayList<>();
        onEvent = receivedEvents::add;
        onError = receivedErrors::add;

        lenient().when(timeoutProperties.getWebsocket()).thenReturn(wsOverride);
        lenient().when(wsOverride.getPingIntervalSeconds()).thenReturn(30);
    }

    @Test
    @DisplayName("getProtocol 返回 websocket")
    void shouldReturnWebsocketProtocol() {
        WebSocketProtocolStrategy strategy = new WebSocketProtocolStrategy(
                cloudAuthService, objectMapper, timeoutProperties);
        assertEquals("websocket", strategy.getProtocol());
    }

    @Test
    @DisplayName("createListener: tool_event 消息触发 onEventReceived 和 onEvent 回调")
    void shouldCallOnEventReceivedForToolEvent() {
        WebSocketProtocolStrategy strategy = new WebSocketProtocolStrategy(
                cloudAuthService, objectMapper, timeoutProperties);
        WebSocket.Listener listener = strategy.createListener(lifecycle, onEvent, onError);
        WebSocket ws = mock(WebSocket.class);

        String json = "{\"type\":\"tool_event\",\"toolSessionId\":\"s1\",\"event\":{\"text\":\"hi\"}}";
        listener.onText(ws, json, true);

        verify(lifecycle).onEventReceived();
        assertEquals(1, receivedEvents.size());
        assertEquals(GatewayMessage.Type.TOOL_EVENT, receivedEvents.get(0).getType());
    }

    @Test
    @DisplayName("createListener: tool_done 消息触发 onTerminalEvent")
    void shouldCallOnTerminalEventForToolDone() {
        WebSocketProtocolStrategy strategy = new WebSocketProtocolStrategy(
                cloudAuthService, objectMapper, timeoutProperties);
        WebSocket.Listener listener = strategy.createListener(lifecycle, onEvent, onError);
        WebSocket ws = mock(WebSocket.class);

        String json = "{\"type\":\"tool_done\",\"toolSessionId\":\"s2\"}";
        listener.onText(ws, json, true);

        verify(lifecycle).onTerminalEvent();
        assertEquals(1, receivedEvents.size());
        assertEquals(GatewayMessage.Type.TOOL_DONE, receivedEvents.get(0).getType());
    }

    @Test
    @DisplayName("createListener: onPong 触发 lifecycle.onHeartbeat")
    void shouldCallOnHeartbeatForPong() {
        WebSocketProtocolStrategy strategy = new WebSocketProtocolStrategy(
                cloudAuthService, objectMapper, timeoutProperties);
        WebSocket.Listener listener = strategy.createListener(lifecycle, onEvent, onError);
        WebSocket ws = mock(WebSocket.class);

        listener.onPong(ws, ByteBuffer.allocate(0));

        verify(lifecycle).onHeartbeat();
    }

    @Test
    @DisplayName("createListener: onError 触发 onError 回调")
    void shouldCallOnErrorCallbackOnWebSocketError() {
        WebSocketProtocolStrategy strategy = new WebSocketProtocolStrategy(
                cloudAuthService, objectMapper, timeoutProperties);
        WebSocket.Listener listener = strategy.createListener(lifecycle, onEvent, onError);
        WebSocket ws = mock(WebSocket.class);

        listener.onError(ws, new RuntimeException("ws error"));

        assertEquals(1, receivedErrors.size());
        assertTrue(receivedErrors.get(0).getMessage().contains("ws error"));
    }

    @Test
    @DisplayName("createListener: cancelled handle suppresses late messages and errors")
    void shouldSuppressCallbacksAfterCancellation() {
        WebSocketProtocolStrategy strategy = new WebSocketProtocolStrategy(
                cloudAuthService, objectMapper, timeoutProperties);
        CloudConnectionHandle handle = new CloudConnectionHandle();
        handle.cancel();
        WebSocket.Listener listener = strategy.createListener(lifecycle, onEvent, onError, null, handle);
        WebSocket ws = mock(WebSocket.class);

        String json = "{\"type\":\"tool_event\",\"toolSessionId\":\"s1\",\"event\":{\"text\":\"late\"}}";
        listener.onText(ws, json, true);
        listener.onPong(ws, ByteBuffer.allocate(0));
        listener.onError(ws, new RuntimeException("closed"));

        verify(lifecycle, never()).onEventReceived();
        verify(lifecycle, never()).onHeartbeat();
        assertTrue(receivedEvents.isEmpty());
        assertTrue(receivedErrors.isEmpty());
    }

    // ==================== T13: question/permission 事件保活 ====================

    @Test
    @DisplayName("T13: event.type=question 触发 lifecycle.pauseIdleTimer()")
    void ws_questionEvent_triggersPauseIdleTimer() {
        WebSocketProtocolStrategy strategy = new WebSocketProtocolStrategy(
                cloudAuthService, objectMapper, timeoutProperties);
        WebSocket.Listener listener = strategy.createListener(lifecycle, onEvent, onError);
        WebSocket ws = mock(WebSocket.class);

        String json = "{\"type\":\"tool_event\",\"toolSessionId\":\"ts-1\","
                + "\"event\":{\"type\":\"question\",\"properties\":{\"toolCallId\":\"call-1\"}}}";
        listener.onText(ws, json, true);

        verify(lifecycle).pauseIdleTimer();
        verify(lifecycle, never()).resumeIdleTimer();
    }

    @Test
    @DisplayName("T13: event.type=permission.ask 触发 lifecycle.pauseIdleTimer()")
    void ws_permissionAskEvent_triggersPause() {
        WebSocketProtocolStrategy strategy = new WebSocketProtocolStrategy(
                cloudAuthService, objectMapper, timeoutProperties);
        WebSocket.Listener listener = strategy.createListener(lifecycle, onEvent, onError);
        WebSocket ws = mock(WebSocket.class);

        String json = "{\"type\":\"tool_event\",\"toolSessionId\":\"ts-2\","
                + "\"event\":{\"type\":\"permission.ask\",\"properties\":{\"permissionId\":\"p-1\"}}}";
        listener.onText(ws, json, true);

        verify(lifecycle).pauseIdleTimer();
        verify(lifecycle, never()).resumeIdleTimer();
    }

    @Test
    @DisplayName("T13: event.type=permission.reply 不调 pause；调 resume")
    void ws_permissionReplyEvent_doesNotTriggerPause() {
        WebSocketProtocolStrategy strategy = new WebSocketProtocolStrategy(
                cloudAuthService, objectMapper, timeoutProperties);
        WebSocket.Listener listener = strategy.createListener(lifecycle, onEvent, onError);
        WebSocket ws = mock(WebSocket.class);

        String json = "{\"type\":\"tool_event\",\"toolSessionId\":\"ts-3\","
                + "\"event\":{\"type\":\"permission.reply\",\"properties\":{\"permissionId\":\"p-1\"}}}";
        listener.onText(ws, json, true);

        verify(lifecycle, never()).pauseIdleTimer();
        verify(lifecycle).resumeIdleTimer();
    }

    @Test
    @DisplayName("T13: event.type=text.delta 触发 lifecycle.resumeIdleTimer()")
    void ws_textDeltaEvent_triggersResume() {
        WebSocketProtocolStrategy strategy = new WebSocketProtocolStrategy(
                cloudAuthService, objectMapper, timeoutProperties);
        WebSocket.Listener listener = strategy.createListener(lifecycle, onEvent, onError);
        WebSocket ws = mock(WebSocket.class);

        String json = "{\"type\":\"tool_event\",\"toolSessionId\":\"ts-4\","
                + "\"event\":{\"type\":\"text.delta\",\"properties\":{\"text\":\"hi\"}}}";
        listener.onText(ws, json, true);

        verify(lifecycle, never()).pauseIdleTimer();
        verify(lifecycle).resumeIdleTimer();
    }

    @Test
    @DisplayName("T13: appId=null 时 applyHeaders 不写 X-App-Id header")
    void ws_appIdNull_skipsXAppIdHeader() {
        WebSocketProtocolStrategy strategy = new WebSocketProtocolStrategy(
                cloudAuthService, objectMapper, timeoutProperties);
        WebSocket.Builder builder = mock(WebSocket.Builder.class);
        when(builder.header(anyString(), anyString())).thenReturn(builder);

        CloudConnectionContext context = CloudConnectionContext.builder()
                .channelAddress("wss://cloud.example.com/ws")
                .appId(null)
                .authType("soa")
                .traceId("trace_null_app")
                .build();

        strategy.applyHeaders(builder, context);

        verify(builder).header("X-Trace-Id", "trace_null_app");
        verify(cloudAuthService).applyAuth(builder, null, "soa");
        verify(builder, never()).header(eq("X-App-Id"), anyString());
    }

    @Test
    @DisplayName("T13: appId 非空时 applyHeaders 交给 CloudAuthService 写认证 header")
    void ws_appIdPresent_delegatesAuthHeadersToCloudAuthService() {
        WebSocketProtocolStrategy strategy = new WebSocketProtocolStrategy(
                cloudAuthService, objectMapper, timeoutProperties);
        WebSocket.Builder builder = mock(WebSocket.Builder.class);
        when(builder.header(anyString(), anyString())).thenReturn(builder);

        CloudConnectionContext context = CloudConnectionContext.builder()
                .channelAddress("wss://cloud.example.com/ws")
                .appId("app_test")
                .authType("soa")
                .traceId("trace_001")
                .build();

        strategy.applyHeaders(builder, context);

        verify(builder).header("X-Trace-Id", "trace_001");
        verify(cloudAuthService).applyAuth(builder, "app_test", "soa");
        verify(builder, never()).header(eq("X-App-Id"), anyString());
    }
}
