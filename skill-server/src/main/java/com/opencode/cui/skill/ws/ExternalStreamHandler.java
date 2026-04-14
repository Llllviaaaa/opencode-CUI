package com.opencode.cui.skill.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.service.RedisMessageBroker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class ExternalStreamHandler extends TextWebSocketHandler implements HandshakeInterceptor {

    private static final String AUTH_PROTOCOL_PREFIX = "auth.";
    private static final String SOURCE_ATTR = "source";
    private static final String INSTANCE_ID_ATTR = "instanceId";
    private static final String CHANNEL_PREFIX = "stream:";

    private final ObjectMapper objectMapper;
    private final RedisMessageBroker redisMessageBroker;
    private final String inboundToken;

    /** source → { instanceId → WebSocketSession } */
    private final Map<String, Map<String, WebSocketSession>> connectionPool = new ConcurrentHashMap<>();
    /** wsSessionId → last activity time */
    private final Map<String, Instant> lastActivity = new ConcurrentHashMap<>();

    public ExternalStreamHandler(ObjectMapper objectMapper,
                                  RedisMessageBroker redisMessageBroker,
                                  @Value("${skill.im.inbound-token:changeme}") String inboundToken) {
        this.objectMapper = objectMapper;
        this.redisMessageBroker = redisMessageBroker;
        this.inboundToken = inboundToken;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                    WebSocketHandler wsHandler, Map<String, Object> attributes) {
        HandshakeAuth auth = extractAuth(request);
        if (auth == null) {
            log.warn("Rejected external handshake: invalid auth subprotocol");
            return false;
        }
        attributes.put(SOURCE_ATTR, auth.source());
        attributes.put(INSTANCE_ID_ATTR, auth.instanceId());
        response.getHeaders().set("Sec-WebSocket-Protocol", auth.protocol());
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                WebSocketHandler wsHandler, Exception exception) {}

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String source = (String) session.getAttributes().get(SOURCE_ATTR);
        String instanceId = (String) session.getAttributes().get(INSTANCE_ID_ATTR);

        // 同一 source+instanceId 重连时，先关闭旧 session 并清理 lastActivity
        Map<String, WebSocketSession> instances = connectionPool.computeIfAbsent(source, k -> new ConcurrentHashMap<>());
        WebSocketSession oldSession = instances.put(instanceId, session);
        if (oldSession != null && oldSession != session) {
            lastActivity.remove(oldSession.getId());
            if (oldSession.isOpen()) {
                try {
                    oldSession.close(CloseStatus.GOING_AWAY);
                } catch (Exception e) {
                    log.debug("Failed to close replaced session: {}", e.getMessage());
                }
            }
            log.info("Replaced old WS session: source={}, instanceId={}, oldSessionId={}, newSessionId={}",
                    source, instanceId, oldSession.getId(), session.getId());
        }

        lastActivity.put(session.getId(), Instant.now());

        String channel = CHANNEL_PREFIX + source;
        if (!redisMessageBroker.isChannelSubscribed(channel)) {
            redisMessageBroker.subscribeToChannel(channel, msg -> handleRedisMessage(source, msg));
        }
        log.info("External WS connected: source={}, instanceId={}, sessionId={}", source, instanceId, session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage textMessage) throws Exception {
        lastActivity.put(session.getId(), Instant.now());
        try {
            var node = objectMapper.readTree(textMessage.getPayload());
            String action = node.path("action").asText("");
            if ("ping".equals(action)) {
                session.sendMessage(new TextMessage("{\"action\":\"pong\"}"));
            }
        } catch (Exception e) {
            log.warn("Invalid message from external WS: sessionId={}, error={}", session.getId(), e.getMessage());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String source = (String) session.getAttributes().get(SOURCE_ATTR);
        String instanceId = (String) session.getAttributes().get(INSTANCE_ID_ATTR);
        Map<String, WebSocketSession> instances = connectionPool.get(source);
        if (instances != null) {
            // 只移除当前关闭的 session，避免旧 session 的 close 事件误删已替换的新 session
            instances.remove(instanceId, session);
            if (instances.isEmpty()) {
                connectionPool.remove(source);
                redisMessageBroker.unsubscribeFromChannel(CHANNEL_PREFIX + source);
            }
        }
        lastActivity.remove(session.getId());
        log.info("External WS disconnected: source={}, instanceId={}, sessionId={}, status={}",
                source, instanceId, session.getId(), status);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        log.error("External WS transport error: sessionId={}, error={}", session.getId(), exception.getMessage());
        afterConnectionClosed(session, CloseStatus.SERVER_ERROR);
    }

    public boolean hasActiveConnections(String source) {
        Map<String, WebSocketSession> instances = connectionPool.get(source);
        if (instances == null || instances.isEmpty()) return false;
        return instances.values().stream().anyMatch(WebSocketSession::isOpen);
    }

    public void pushToSource(String source, String message) {
        Map<String, WebSocketSession> instances = connectionPool.get(source);
        if (instances == null || instances.isEmpty()) {
            log.warn("No active connections for source: {}", source);
            return;
        }
        TextMessage textMessage = new TextMessage(message);
        for (Map.Entry<String, WebSocketSession> entry : instances.entrySet()) {
            WebSocketSession session = entry.getValue();
            if (session.isOpen()) {
                try {
                    synchronized (session) { session.sendMessage(textMessage); }
                } catch (Exception e) {
                    log.error("Failed to push to external WS: source={}, instanceId={}, error={}",
                            source, entry.getKey(), e.getMessage());
                }
            }
        }
    }

    @Scheduled(fixedRate = 30_000)
    public void checkHeartbeatTimeouts() {
        Instant timeout = Instant.now().minusSeconds(60);
        for (Map<String, WebSocketSession> instances : connectionPool.values()) {
            for (WebSocketSession session : instances.values()) {
                Instant last = lastActivity.get(session.getId());
                if (last != null && last.isBefore(timeout) && session.isOpen()) {
                    try {
                        log.warn("Closing external WS due to heartbeat timeout: sessionId={}", session.getId());
                        session.close(CloseStatus.GOING_AWAY);
                    } catch (Exception e) {
                        log.error("Failed to close timed-out session: {}", e.getMessage());
                    }
                }
            }
        }
    }

    private void handleRedisMessage(String source, String message) { pushToSource(source, message); }

    private HandshakeAuth extractAuth(ServerHttpRequest request) {
        List<String> protocols = request.getHeaders().get("Sec-WebSocket-Protocol");
        if (protocols == null || protocols.isEmpty()) return null;
        for (String protocolHeader : protocols) {
            for (String candidate : protocolHeader.split(",")) {
                String protocol = candidate.trim();
                if (protocol.startsWith(AUTH_PROTOCOL_PREFIX)) {
                    HandshakeAuth auth = verifyToken(protocol);
                    if (auth != null) return auth;
                }
            }
        }
        return null;
    }

    private HandshakeAuth verifyToken(String protocol) {
        String encoded = protocol.substring(AUTH_PROTOCOL_PREFIX.length());
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(encoded);
            String json = new String(decoded, StandardCharsets.UTF_8);
            var authNode = objectMapper.readTree(json);
            String token = authNode.path("token").asText(null);
            String source = authNode.path("source").asText(null);
            String instanceId = authNode.path("instanceId").asText(null);
            if (!inboundToken.equals(token) || source == null || source.isBlank()
                    || instanceId == null || instanceId.isBlank()) return null;
            return new HandshakeAuth(protocol, source, instanceId);
        } catch (Exception e) {
            log.warn("Failed to decode external auth subprotocol: {}", e.getMessage());
            return null;
        }
    }

    private record HandshakeAuth(String protocol, String source, String instanceId) {}
}
