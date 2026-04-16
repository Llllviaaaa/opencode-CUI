package com.opencode.cui.skill.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.config.DeliveryProperties;
import com.opencode.cui.skill.service.ExternalWsRegistry;
import com.opencode.cui.skill.service.RedisMessageBroker;
import com.opencode.cui.skill.service.SkillInstanceRegistry;
import jakarta.annotation.PostConstruct;
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
    private final ExternalWsRegistry wsRegistry;
    private final SkillInstanceRegistry instanceRegistry;
    private final DeliveryProperties deliveryProperties;

    /** source → { instanceId → WebSocketSession } */
    private final Map<String, Map<String, WebSocketSession>> connectionPool = new ConcurrentHashMap<>();
    /** wsSessionId → last activity time */
    private final Map<String, Instant> lastActivity = new ConcurrentHashMap<>();

    public ExternalStreamHandler(ObjectMapper objectMapper,
                                  RedisMessageBroker redisMessageBroker,
                                  @Value("${skill.im.inbound-token:changeme}") String inboundToken,
                                  ExternalWsRegistry wsRegistry,
                                  SkillInstanceRegistry instanceRegistry,
                                  DeliveryProperties deliveryProperties) {
        this.objectMapper = objectMapper;
        this.redisMessageBroker = redisMessageBroker;
        this.inboundToken = inboundToken;
        this.wsRegistry = wsRegistry;
        this.instanceRegistry = instanceRegistry;
        this.deliveryProperties = deliveryProperties;
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
        wsRegistry.register(source, instances.size());
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
                wsRegistry.unregister(source);
            } else {
                wsRegistry.register(source, instances.size());
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

    /**
     * 精确投递：选择一条活跃 WS 连接推送消息。
     * 与 pushToSource（广播到所有连接）不同，pushToOne 只发一次。
     *
     * @return true 如果成功推送，false 如果无可用连接
     */
    public boolean pushToOne(String source, String message) {
        Map<String, WebSocketSession> instances = connectionPool.get(source);
        if (instances == null || instances.isEmpty()) {
            return false;
        }
        TextMessage textMessage = new TextMessage(message);
        for (WebSocketSession session : instances.values()) {
            if (session.isOpen()) {
                try {
                    synchronized (session) { session.sendMessage(textMessage); }
                    return true;
                } catch (Exception e) {
                    log.error("Failed to pushToOne: source={}, sessionId={}, error={}",
                            source, session.getId(), e.getMessage());
                }
            }
        }
        return false;
    }

    @PostConstruct
    public void subscribeRelayChannel() {
        String instanceId = instanceRegistry.getInstanceId();
        redisMessageBroker.subscribeToChannel("ss:external-relay:" + instanceId,
                this::handleExternalRelayMessage);
        log.info("Subscribed to external relay channel: ss:external-relay:{}", instanceId);
    }

    private void handleExternalRelayMessage(String message) {
        try {
            var node = objectMapper.readTree(message);
            String domain = node.path("domain").asText(null);
            String payload = node.path("payload").asText(null);
            if (domain != null && payload != null) {
                boolean sent = pushToOne(domain, payload);
                log.info("[RELAY-RX] External relay: domain={}, sent={}", domain, sent);
            }
        } catch (Exception e) {
            log.error("Failed to handle external relay message: {}", e.getMessage());
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
        // 续期 WS 连接注册表
        for (String source : connectionPool.keySet()) {
            wsRegistry.heartbeat(source);
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

    /**
     * 多连接池：source → { instanceId → { wsSessionId → WebSocketSession } }。
     * 封装并发安全的连接管理，对外暴露简洁 API。
     */
    static class ConnectionPool {
        private final ConcurrentHashMap<String, ConcurrentHashMap<String, ConcurrentHashMap<String, WebSocketSession>>> pool
                = new ConcurrentHashMap<>();

        void add(String source, String instanceId, WebSocketSession session) {
            pool.computeIfAbsent(source, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(instanceId, k -> new ConcurrentHashMap<>())
                .put(session.getId(), session);
        }

        int remove(String source, String instanceId, WebSocketSession session) {
            var instances = pool.get(source);
            if (instances == null) return 0;
            var sessions = instances.get(instanceId);
            if (sessions != null) {
                sessions.remove(session.getId(), session);
                if (sessions.isEmpty()) instances.remove(instanceId);
            }
            if (instances.isEmpty()) pool.remove(source);
            return countBySource(source);
        }

        int removeBySessionId(String source, String instanceId, String wsSessionId) {
            var instances = pool.get(source);
            if (instances == null) return 0;
            var sessions = instances.get(instanceId);
            if (sessions != null) {
                sessions.remove(wsSessionId);
                if (sessions.isEmpty()) instances.remove(instanceId);
            }
            if (instances.isEmpty()) pool.remove(source);
            return countBySource(source);
        }

        WebSocketSession pickOne(String source) {
            var instances = pool.get(source);
            if (instances == null) return null;
            for (var sessions : instances.values()) {
                for (WebSocketSession session : sessions.values()) {
                    if (session.isOpen()) return session;
                }
            }
            return null;
        }

        int countBySource(String source) {
            var instances = pool.get(source);
            if (instances == null) return 0;
            int count = 0;
            for (var sessions : instances.values()) {
                count += sessions.size();
            }
            return count;
        }

        boolean hasActiveConnections(String source) {
            return pickOne(source) != null;
        }

        java.util.Set<String> sources() {
            return pool.keySet();
        }

        void forEach(String source, java.util.function.BiConsumer<String, WebSocketSession> action) {
            var instances = pool.get(source);
            if (instances == null) return;
            for (var entry : instances.entrySet()) {
                String instanceId = entry.getKey();
                for (var sessionEntry : entry.getValue().entrySet()) {
                    action.accept(instanceId + ":" + sessionEntry.getKey(), sessionEntry.getValue());
                }
            }
        }

        void forEachAll(java.util.function.Consumer<WebSocketSession> action) {
            for (var instances : pool.values()) {
                for (var sessions : instances.values()) {
                    for (WebSocketSession session : sessions.values()) {
                        action.accept(session);
                    }
                }
            }
        }
    }

    private record HandshakeAuth(String protocol, String source, String instanceId) {}
}
