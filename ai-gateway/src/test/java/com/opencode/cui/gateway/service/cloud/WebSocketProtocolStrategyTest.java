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
}
