package com.opencode.cui.skill.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.config.DeliveryProperties;
import com.opencode.cui.skill.service.ExternalWsRegistry;
import com.opencode.cui.skill.service.RedisMessageBroker;
import com.opencode.cui.skill.service.SkillInstanceRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ExternalStreamHandlerTest {

    @Mock private RedisMessageBroker redisMessageBroker;
    @Mock private ExternalWsRegistry wsRegistry;
    @Mock private SkillInstanceRegistry instanceRegistry;
    @Mock private DeliveryProperties deliveryProperties;
    @Mock private ServerHttpRequest request;
    @Mock private ServerHttpResponse response;
    @Mock private WebSocketHandler wsHandler;
    @Mock private WebSocketSession wsSession;

    private ObjectMapper objectMapper = new ObjectMapper();
    private ExternalStreamHandler handler;

    @BeforeEach
    void setUp() {
        when(instanceRegistry.getInstanceId()).thenReturn("test-instance-1");
        handler = new ExternalStreamHandler(objectMapper, redisMessageBroker, "test-token",
                wsRegistry, instanceRegistry, deliveryProperties);
        handler.subscribeRelayChannel();
    }

    private String buildAuthProtocol(String token, String source, String instanceId) {
        String json = "{\"token\":\"" + token + "\",\"source\":\"" + source
                + "\",\"instanceId\":\"" + instanceId + "\"}";
        return "auth." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("valid handshake registers connection")
    void validHandshakeAccepted() {
        HttpHeaders headers = new HttpHeaders();
        headers.put("Sec-WebSocket-Protocol", List.of(buildAuthProtocol("test-token", "im", "im-1")));
        when(request.getHeaders()).thenReturn(headers);
        HttpHeaders respHeaders = new HttpHeaders();
        when(response.getHeaders()).thenReturn(respHeaders);
        Map<String, Object> attrs = new HashMap<>();
        boolean result = handler.beforeHandshake(request, response, wsHandler, attrs);
        assertTrue(result);
        assertEquals("im", attrs.get("source"));
        assertEquals("im-1", attrs.get("instanceId"));
    }

    @Test
    @DisplayName("invalid token rejects handshake")
    void invalidTokenRejected() {
        HttpHeaders headers = new HttpHeaders();
        headers.put("Sec-WebSocket-Protocol", List.of(buildAuthProtocol("wrong-token", "im", "im-1")));
        when(request.getHeaders()).thenReturn(headers);
        Map<String, Object> attrs = new HashMap<>();
        boolean result = handler.beforeHandshake(request, response, wsHandler, attrs);
        assertFalse(result);
    }

    @Test
    @DisplayName("hasActiveConnections returns true after connection established")
    void hasActiveConnectionsAfterConnect() throws Exception {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("source", "im");
        attrs.put("instanceId", "im-1");
        when(wsSession.getAttributes()).thenReturn(attrs);
        when(wsSession.getId()).thenReturn("session-1");
        when(wsSession.isOpen()).thenReturn(true);
        handler.afterConnectionEstablished(wsSession);
        assertTrue(handler.hasActiveConnections("im"));
        assertFalse(handler.hasActiveConnections("crm"));
    }

    @Test
    @DisplayName("hasActiveConnections returns false after connection closed")
    void noActiveConnectionsAfterClose() throws Exception {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("source", "im");
        attrs.put("instanceId", "im-1");
        when(wsSession.getAttributes()).thenReturn(attrs);
        when(wsSession.getId()).thenReturn("session-2");
        handler.afterConnectionEstablished(wsSession);
        handler.afterConnectionClosed(wsSession, CloseStatus.NORMAL);
        assertFalse(handler.hasActiveConnections("im"));
    }

    @Test
    @DisplayName("ping message receives pong reply")
    void pingReceivesPong() throws Exception {
        when(wsSession.getId()).thenReturn("session-3");
        handler.handleTextMessage(wsSession, new TextMessage("{\"action\":\"ping\"}"));
        verify(wsSession).sendMessage(any(TextMessage.class));
    }
}
