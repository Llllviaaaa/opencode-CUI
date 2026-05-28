package com.opencode.cui.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.gateway.model.GatewayMessage;
import com.opencode.cui.gateway.model.RelayMessage;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import com.opencode.cui.gateway.ws.AsyncSessionSender;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import com.opencode.cui.gateway.service.cloud.InvokeRouteStrategy;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Source 服务 WebSocket 连接管理 + 上行消息路由（V2 Mesh）。
 *
 * <p>所有 Source 服务握手时必须带 instanceId。
 * 上行路由通过 {@link UpstreamRoutingTable} + {@link ConsistentHashRing} 实现。</p>
 */
@Slf4j
@Service
public class SkillRelayService {

    public static final String SOURCE_ATTR = "source";
    public static final String INSTANCE_ID_ATTR = "instanceId";
    public static final String ERROR_SOURCE_NOT_ALLOWED = "source_not_allowed";
    public static final String ERROR_SOURCE_MISMATCH = "source_mismatch";
    private static final String ACTION_ABORT_SESSION = "abort_session";

    private final RedisMessageBroker redisMessageBroker;
    private final ObjectMapper objectMapper;
    private final String gatewayInstanceId;
    private final UpstreamRoutingTable routingTable;

    /** Invoke 路由策略 Map：scope → strategy */
    private final Map<String, InvokeRouteStrategy> routeStrategyMap;

    /**
     * [Mesh] Source 实例连接池：source_type → { ssInstanceId → { sessionId → WebSocketSession } }
     */
    private final Map<String, Map<String, Map<String, WebSocketSession>>> sourceTypeSessions = new ConcurrentHashMap<>();

    /**
     * [V2] Consistent hash ring per source type for deterministic upstream routing.
     */
    private final Map<String, ConsistentHashRing<WebSocketSession>> hashRings = new ConcurrentHashMap<>();

    /** Pending queue TTL for offline agent messages. */
    private static final Duration PENDING_TTL = Duration.ofMinutes(30);

    @Value("${gateway.upstream-routing.broadcast-timeout-ms:200}")
    private int broadcastTimeoutMs;

    private final ConcurrentHashMap<String, AsyncSessionSender> sessionSenders = new ConcurrentHashMap<>();

    // ==================== Metrics counters ====================

    /** Count of invoke messages delivered to a locally connected Agent. */
    private final AtomicLong relayLocalCount = new AtomicLong();
    /** Count of invoke messages relayed to a remote GW instance via Redis pub/sub. */
    private final AtomicLong relayPubsubCount = new AtomicLong();
    /** Count of invoke messages enqueued to the pending queue (Agent offline). */
    private final AtomicLong relayPendingCount = new AtomicLong();
    /** Count of upstream route lookups that hit a known entry in UpstreamRoutingTable. */
    private final AtomicLong routingHitCount = new AtomicLong();
    /** Count of upstream route lookups that fell back to broadcast (no routing table entry). */
    private final AtomicLong routingBroadcastCount = new AtomicLong();
    /** Count of upstream messages routed via Redis L2 (cross-GW relay). */
    private final AtomicLong routingRedisL2Count = new AtomicLong();
    /** Count of upstream messages routed via Level 3 broadcast relay. */
    private final AtomicLong routingL3BroadcastCount = new AtomicLong();

    /**
     * Per-source broadcast rate limiter: sourceType → last broadcast timestamps (epoch millis).
     * Uses a Caffeine cache with sliding window to enforce max 10 broadcasts/source/second.
     */
    private final Cache<String, AtomicLong> broadcastRateLimiter = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofMinutes(5))
            .maximumSize(1000)
            .build();
    private static final int BROADCAST_RATE_LIMIT_PER_SECOND = 10;

    /** Lazy-initialized reference to EventRelayService (set via setter to break circular dependency). */
    private EventRelayService eventRelayService;

    public SkillRelayService(RedisMessageBroker redisMessageBroker,
            ObjectMapper objectMapper,
            @Value("${gateway.instance-id:${HOSTNAME:gateway-local}}") String gatewayInstanceId,
            UpstreamRoutingTable routingTable,
            List<InvokeRouteStrategy> invokeRouteStrategies) {
        this.redisMessageBroker = redisMessageBroker;
        this.objectMapper = objectMapper;
        this.gatewayInstanceId = gatewayInstanceId;
        this.routingTable = routingTable;
        Map<String, InvokeRouteStrategy> strategyMap = new HashMap<>();
        for (InvokeRouteStrategy s : invokeRouteStrategies) {
            strategyMap.put(s.getScope(), s);
        }
        this.routeStrategyMap = strategyMap;
    }

    /**
     * Sets the EventRelayService reference. Called by EventRelayService to break circular dependency.
     */
    public void setEventRelayService(EventRelayService eventRelayService) {
        this.eventRelayService = eventRelayService;
    }

    /**
     * Cleans up stale source connection entries left by a previous crash of this GW instance.
     */
    @PostConstruct
    public void cleanupStaleSourceConnectionsOnStartup() {
        redisMessageBroker.cleanupStaleSourceConnections(gatewayInstanceId);
        log.info("[ENTRY] SkillRelayService: cleaned up stale source-conn entries for gwInstanceId={}", gatewayInstanceId);
    }

    // ==================== 连接管理 ====================

    /**
     * 注册 Source 服务的 WebSocket 连接。
     */
    public void registerSourceSession(WebSocketSession session) {
        String sourceType = resolveBoundSource(session);
        String ssInstanceId = resolveSsInstanceId(session);
        if (sourceType == null || sourceType.isBlank()) {
            log.warn("Skipping source session registration: missing source attribute, linkId={}",
                    session.getId());
            return;
        }
        if (ssInstanceId == null || ssInstanceId.isBlank()) {
            ssInstanceId = session.getId(); // fallback 用 WS session ID
        }

        String sessionId = session.getId();
        sourceTypeSessions
                .computeIfAbsent(sourceType, ignored -> new ConcurrentHashMap<>())
                .computeIfAbsent(ssInstanceId, ignored -> new ConcurrentHashMap<>())
                .put(sessionId, session);

        // V2: update consistent hash ring for this source type
        String nodeKey = ssInstanceId + "#" + sessionId;
        hashRings.computeIfAbsent(sourceType, k -> new ConsistentHashRing<>(150))
                .addNode(nodeKey, session);

        // Register source connection in Redis for cross-cluster discovery
        redisMessageBroker.registerSourceConnection(sourceType, ssInstanceId, gatewayInstanceId, sessionId);

        log.info("[Mesh] Registered source session: sourceType={}, ssInstanceId={}, sessionId={}, gwInstanceId={}, activeLinks={}, hashRingSize={}",
                sourceType, ssInstanceId, sessionId, gatewayInstanceId, getActiveConnectionCount(sourceType),
                hashRings.containsKey(sourceType) ? hashRings.get(sourceType).size() : 0);
    }

    /**
     * 移除 Source 服务的 WebSocket 连接。
     */
    public void removeSourceSession(WebSocketSession session) {
        removeSessionSender(session.getId());

        String sourceType = resolveBoundSource(session);
        if (sourceType == null || sourceType.isBlank()) {
            return;
        }

        Map<String, Map<String, WebSocketSession>> instanceMap = sourceTypeSessions.get(sourceType);
        if (instanceMap == null) {
            return;
        }

        String ssInstanceId = resolveSsInstanceId(session);
        String sessionId = session.getId();

        if (ssInstanceId != null) {
            Map<String, WebSocketSession> sessionMap = instanceMap.get(ssInstanceId);
            if (sessionMap != null) {
                sessionMap.remove(sessionId, session);
                if (sessionMap.isEmpty()) {
                    instanceMap.remove(ssInstanceId, sessionMap);
                }
            }
        }
        // Also try by linkId as ssInstanceId fallback
        Map<String, WebSocketSession> fallbackMap = instanceMap.get(sessionId);
        if (fallbackMap != null) {
            fallbackMap.remove(sessionId, session);
            if (fallbackMap.isEmpty()) {
                instanceMap.remove(sessionId, fallbackMap);
            }
        }

        if (instanceMap.isEmpty()) {
            sourceTypeSessions.remove(sourceType, instanceMap);
        }

        // V2: remove from consistent hash ring
        String nodeKey = ((ssInstanceId != null) ? ssInstanceId : sessionId) + "#" + sessionId;
        hashRings.computeIfPresent(sourceType, (k, ring) -> {
            ring.removeNode(nodeKey);
            return ring.isEmpty() ? null : ring;
        });

        // Unregister source connection from Redis
        if (ssInstanceId != null) {
            redisMessageBroker.unregisterSourceConnection(sourceType, ssInstanceId, gatewayInstanceId, sessionId);
        }

        log.info("[Mesh] Removed source session: sourceType={}, ssInstanceId={}, sessionId={}, gwInstanceId={}, activeLinks={}",
                sourceType, ssInstanceId, sessionId, gatewayInstanceId, getActiveConnectionCount(sourceType));
    }

    // ==================== 上行消息路由 ====================

    /**
     * V2: Routes upstream messages to the correct source service using UpstreamRoutingTable + ConsistentHashRing.
     *
     * <p>Routing strategy:</p>
     * <ol>
     * <li>Query UpstreamRoutingTable to resolve sourceType</li>
     * <li>If sourceType known → hash-select a connection from that sourceType's ring</li>
     * <li>If sourceType unknown → broadcast to all sourceType groups (one connection per group via hash)</li>
     * </ol>
     *
     * @return true if the message was delivered, false if delivery failed
     */
    public boolean relayToSkill(GatewayMessage message) {
        return v2RelayToSkill(message);
    }

    /**
     * V2: Three-level upstream routing.
     *
     * <p>Level 1: Caffeine L1 (UpstreamRoutingTable) + local hashRing — 0-hop local delivery</p>
     * <p>Level 2: Redis L2 — precise session route lookup, local delivery or cross-GW relay</p>
     * <p>Level 3: Broadcast fallback — relay to all GWs holding Source connections</p>
     */
    private boolean v2RelayToSkill(GatewayMessage message) {
        GatewayMessage tracedMessage = message.ensureTraceId();
        String routingKey = resolveRoutingKey(tracedMessage);

        // ===== Level 1: Caffeine L1 — UpstreamRoutingTable + local hashRing =====
        String sourceType = routingTable.resolveSourceType(tracedMessage);

        if (sourceType != null) {
            ConsistentHashRing<WebSocketSession> ring = hashRings.get(sourceType);
            if (ring != null && !ring.isEmpty() && routingKey != null) {
                WebSocketSession target = ring.getNode(routingKey);
                if (target != null && target.isOpen()) {
                    logRoutingInfo(tracedMessage,
                            "[V2-L1] Hash-routed to source: sourceType={}, routingKey={}, linkId={}, type={}",
                            sourceType, routingKey, target.getId(), tracedMessage.getType());
                    if (sendToSession(target, tracedMessage)) {
                        routingHitCount.incrementAndGet();
                        return true;
                    }
                }
            }
            // L1 known sourceType but no local connection — try local broadcast to that type first
            if (broadcastToSourceType(sourceType, tracedMessage)) {
                routingHitCount.incrementAndGet();
                return true;
            }
            // Fall through to L2
        }

        // Also try message.source field as sourceType hint for L1
        String messageSource = tracedMessage.getSource();
        if (messageSource != null && !messageSource.isBlank()) {
            ConsistentHashRing<WebSocketSession> ring = hashRings.get(messageSource);
            if (ring != null && !ring.isEmpty() && routingKey != null) {
                WebSocketSession target = ring.getNode(routingKey);
                if (target != null && target.isOpen()) {
                    logRoutingInfo(tracedMessage,
                            "[V2-L1] Hash-routed via message.source: sourceType={}, routingKey={}, linkId={}, type={}",
                            messageSource, routingKey, target.getId(), tracedMessage.getType());
                    if (sendToSession(target, tracedMessage)) {
                        routingHitCount.incrementAndGet();
                        return true;
                    }
                }
            }
            if (broadcastToSourceType(messageSource, tracedMessage)) {
                routingHitCount.incrementAndGet();
                return true;
            }
        }

        // ===== Level 2: Redis L2 — precise session route from Redis =====
        boolean l2Result = l2RedisRoute(tracedMessage, routingKey);
        if (l2Result) {
            routingRedisL2Count.incrementAndGet();
            return true;
        }

        // ===== Level 3: Broadcast fallback — relay to all GWs with Source connections =====
        routingBroadcastCount.incrementAndGet();
        // Try local broadcast first
        boolean localBroadcast = broadcastToAllGroups(tracedMessage, routingKey);
        // Also relay to remote GWs
        l3BroadcastRelay(tracedMessage);
        routingL3BroadcastCount.incrementAndGet();
        return localBroadcast;
    }

    /**
     * Level 2: Redis-based precise session route lookup.
     * Queries Redis for toolSessionId/welinkSessionId → sourceType:sourceInstanceId mapping,
     * then either delivers locally or relays to the correct remote GW.
     *
     * @return true if message was delivered or relayed
     */
    private boolean l2RedisRoute(GatewayMessage message, String routingKey) {
        String toolSessionId = message.getToolSessionId();
        String payloadToolSessionId = extractToolSessionIdFromPayload(message);
        String welinkSessionId = message.getWelinkSessionId();

        // Query Redis route table
        String routeValue = null;
        if (toolSessionId != null && !toolSessionId.isBlank()) {
            routeValue = redisMessageBroker.getSessionRoute(toolSessionId);
        }
        if (routeValue == null && payloadToolSessionId != null && !payloadToolSessionId.isBlank()
                && !payloadToolSessionId.equals(toolSessionId)) {
            routeValue = redisMessageBroker.getSessionRoute(payloadToolSessionId);
        }
        if (routeValue == null) {
            routeValue = redisMessageBroker.getWelinkSessionRoute(welinkSessionId);
        }

        if (routeValue == null) {
            log.debug("[V2-L2] No Redis route found: toolSessionId={}, payloadToolSessionId={}, welinkSessionId={}",
                    toolSessionId, payloadToolSessionId, welinkSessionId);
            return false;
        }

        // Parse "sourceType:sourceInstanceId"
        String[] parts = routeValue.split(":", 2);
        if (parts.length < 2) {
            log.warn("[V2-L2] Invalid route format: routeValue={}", routeValue);
            return false;
        }
        String targetSourceType = parts[0];
        String targetSourceInstanceId = parts[1];

        // Try local delivery first
        WebSocketSession localSession = findLocalSourceConnection(targetSourceType, targetSourceInstanceId);
        if (localSession != null) {
            logRoutingInfo(message, "[V2-L2] Local delivery: sourceType={}, sourceInstanceId={}, linkId={}, type={}",
                    targetSourceType, targetSourceInstanceId, localSession.getId(), message.getType());
            if (sendToSession(localSession, message)) {
                return true;
            }
        }

        // Query Redis: which GWs hold this Source connection?
        Map<String, Long> gwMap = redisMessageBroker.getSourceConnections(targetSourceType, targetSourceInstanceId);
        Set<String> uniqueGwIds = redisMessageBroker.extractUniqueGwInstances(gwMap);
        for (String targetGwId : uniqueGwIds) {
            if (!gatewayInstanceId.equals(targetGwId)) {
                try {
                    String messageJson = objectMapper.writeValueAsString(message);
                    redisMessageBroker.publishToSourceRelay(targetGwId, targetSourceType,
                            targetSourceInstanceId, messageJson);
                    logRoutingInfo(message, "[V2-L2] Cross-GW relay: targetGw={}, sourceType={}, sourceInstanceId={}, type={}",
                            targetGwId, targetSourceType, targetSourceInstanceId, message.getType());
                    return true;
                } catch (Exception e) {
                    log.error("[V2-L2] Failed to serialize message for cross-GW relay: type={}", message.getType(), e);
                }
            }
        }

        log.debug("[V2-L2] Route found but no reachable GW: sourceType={}, sourceInstanceId={}",
                targetSourceType, targetSourceInstanceId);
        return false;
    }

    /**
     * Level 3: Broadcast relay to all remote GWs that hold any Source connection.
     * Subject to per-source rate limiting (max {@link #BROADCAST_RATE_LIMIT_PER_SECOND} broadcasts/source/second).
     *
     * <p>Receiving GWs that have no matching local Source connection will silently discard the message.</p>
     */
    private void l3BroadcastRelay(GatewayMessage message) {
        String sourceHint = message.getSource();
        String rateLimitKey = sourceHint != null ? sourceHint : "__all__";

        // Per-source rate limiting using sliding 1-second window
        if (!acquireBroadcastPermit(rateLimitKey)) {
            log.warn("[V2-L3] Broadcast rate limited: source={}", rateLimitKey);
            return;
        }

        try {
            String messageJson = objectMapper.writeValueAsString(message);
            RelayMessage broadcastRelay = RelayMessage.toSourceBroadcast(messageJson);
            String relayJson = objectMapper.writeValueAsString(broadcastRelay);

            // Discover all GW instances that hold Source connections
            java.util.Set<String> gwIds = redisMessageBroker.discoverAllSourceGwInstances();
            int relayed = 0;
            for (String targetGwId : gwIds) {
                if (gatewayInstanceId.equals(targetGwId)) {
                    continue; // skip self — local broadcast already handled
                }
                redisMessageBroker.publishToGwRelay(targetGwId, relayJson);
                relayed++;
            }
            logRoutingInfo(message, "[V2-L3] Broadcast relay to {} remote GWs: type={}, source={}",
                    relayed, message.getType(), sourceHint);
        } catch (Exception e) {
            log.error("[V2-L3] Failed to broadcast relay: type={}", message.getType(), e);
        }
    }

    /**
     * Attempts to acquire a broadcast permit for the given source key.
     * Implements a simple sliding window rate limiter: max {@link #BROADCAST_RATE_LIMIT_PER_SECOND} per second.
     *
     * @return true if permit acquired, false if rate limited
     */
    private boolean acquireBroadcastPermit(String sourceKey) {
        // Window timestamp tracker — uses CAS to safely rotate window
        AtomicLong windowStart = broadcastRateLimiter.get(sourceKey + ":ts", k -> new AtomicLong(0));
        AtomicLong counter = broadcastRateLimiter.get(sourceKey + ":cnt", k -> new AtomicLong(0));

        long now = System.currentTimeMillis();
        long ws = windowStart.get();

        if (now - ws > 1000) {
            // Attempt to rotate window via CAS; loser threads fall through to increment
            if (windowStart.compareAndSet(ws, now)) {
                counter.set(1);
                return true;
            }
            // CAS failed — another thread already rotated, fall through to increment
        }
        long count = counter.incrementAndGet();
        return count <= BROADCAST_RATE_LIMIT_PER_SECOND;
    }

    /**
     * Resolves the routing key for consistent hash selection.
     * Priority: welinkSessionId > toolSessionId > payload.toolSessionId.
     */
    private String resolveRoutingKey(GatewayMessage message) {
        String welinkSessionId = message.getWelinkSessionId();
        if (welinkSessionId != null && !welinkSessionId.isBlank()) {
            return welinkSessionId;
        }
        String toolSessionId = message.getToolSessionId();
        if (toolSessionId != null && !toolSessionId.isBlank()) {
            return toolSessionId;
        }
        String payloadToolSessionId = extractToolSessionIdFromPayload(message);
        if (payloadToolSessionId != null && !payloadToolSessionId.isBlank()) {
            return payloadToolSessionId;
        }
        return null;
    }

    /**
     * Broadcasts message to all sourceType groups.
     * Each group selects one connection via hash ring (or first open connection as fallback).
     */
    private boolean broadcastToAllGroups(GatewayMessage message, String routingKey) {
        if (hashRings.isEmpty() && sourceTypeSessions.isEmpty()) {
            log.warn("[V2] No source connections available for broadcast: type={}", message.getType());
            return false;
        }

        int groupsSent = 0;
        for (Map.Entry<String, ConsistentHashRing<WebSocketSession>> entry : hashRings.entrySet()) {
            String st = entry.getKey();
            ConsistentHashRing<WebSocketSession> ring = entry.getValue();
            if (ring.isEmpty()) {
                continue;
            }

            WebSocketSession target = null;
            if (routingKey != null) {
                target = ring.getNode(routingKey);
            }
            // Fallback: find any open session in this sourceType pool
            if (target == null || !target.isOpen()) {
                Map<String, Map<String, WebSocketSession>> instanceMap = sourceTypeSessions.get(st);
                if (instanceMap != null) {
                    target = instanceMap.values().stream()
                            .flatMap(m -> m.values().stream())
                            .filter(WebSocketSession::isOpen)
                            .findFirst()
                            .orElse(null);
                }
            }

            if (target != null && target.isOpen()) {
                sendToSession(target, message);
                groupsSent++;
            }
        }

        logRoutingInfo(message, "[V2] Broadcast to all groups: groupsSent={}, totalGroups={}, type={}",
                groupsSent, hashRings.size(), message.getType());
        return groupsSent > 0;
    }

    /**
     * Broadcasts to all SS connections of the specified source_type.
     */
    private boolean broadcastToSourceType(String sourceType, GatewayMessage message) {
        Map<String, Map<String, WebSocketSession>> instanceMap = sourceTypeSessions.get(sourceType);
        if (instanceMap == null || instanceMap.isEmpty()) {
            log.warn("No source instances for type: {}, type={}", sourceType, message.getType());
            return false;
        }

        int sent = 0;
        for (Map<String, WebSocketSession> sessionMap : instanceMap.values()) {
            for (WebSocketSession ss : sessionMap.values()) {
                if (ss.isOpen()) {
                    sendToSession(ss, message);
                    sent++;
                }
            }
        }

        logRoutingInfo(message, "Broadcast to source_type={}: sent to {} connections, msgType={}",
                sourceType, sent, message.getType());
        return sent > 0;
    }

    /**
     * Handles a to-source-broadcast relay from a remote GW (Level 3).
     * Broadcasts the payload to all local Source connections.
     * Called by EventRelayService when it receives a {@code to-source-broadcast} relay.
     *
     * @param payload the GatewayMessage JSON payload to broadcast
     */
    public void handleToSourceBroadcastRelay(String payload) {
        try {
            GatewayMessage message = objectMapper.readValue(payload, GatewayMessage.class);
            GatewayMessage tracedMessage = message.ensureTraceId();
            String routingKey = resolveRoutingKey(tracedMessage);
            boolean delivered = broadcastToAllGroups(tracedMessage, routingKey);
            if (!delivered) {
                log.debug("[V2-L3-RX] No local source connections for broadcast relay: type={}", message.getType());
            }
        } catch (Exception e) {
            log.error("[V2-L3-RX] Failed to handle to-source-broadcast relay", e);
        }
    }

    /**
     * Handles a cloud-control relay from another GW instance.
     * Used for abort_session when the receiving GW is the owner of the local cloud stream.
     *
     * @param payload the GatewayMessage JSON payload to process through business cloud routing
     */
    public void handleCloudControlRelay(String payload) {
        InvokeRouteStrategy strategy = routeStrategyMap.get("business");
        if (strategy == null) {
            log.warn("[CLOUD_CONTROL_RX] Business route strategy missing, dropping control relay");
            return;
        }
        try {
            GatewayMessage message = objectMapper.readValue(payload, GatewayMessage.class).ensureTraceId();
            log.info("[CLOUD_CONTROL_RX] Routing cloud control relay locally: action={}, toolSessionId={}, traceId={}",
                    message.getAction(), resolveRoutingKey(message), message.getTraceId());
            strategy.route(message, this::relayToSkill);
        } catch (Exception e) {
            log.error("[CLOUD_CONTROL_RX] Failed to handle cloud-control relay", e);
        }
    }

    // ==================== 下行 invoke 处理 ====================

    /**
     * V2: Handles invoke messages from source services, routing to the target Agent.
     *
     * <p>Routing strategy:</p>
     * <ol>
     * <li>Learn route via UpstreamRoutingTable</li>
     * <li>Check local Agent session → deliver locally if found</li>
     * <li>Check Redis internal agent registry → relay via GW pub/sub if found on another GW</li>
     * <li>Neither found → enqueue to pending queue</li>
     * </ol>
     */
    public void handleInvokeFromSkill(WebSocketSession session, GatewayMessage message) {
        // 业务助手走云端路由策略，个人助手保持现有逻辑
        String scope = Optional.ofNullable(message.getAssistantScope()).orElse("personal");
        if (shouldRouteBusinessInvoke(message, scope) && routeBusinessInvoke(session, message)) {
            return;
        }

        GatewayMessage tracedMessage = message.ensureTraceId();

        log.info("[ENTRY] SkillRelayService.handleInvokeFromSkill: ak={}, action={}, linkId={}",
                tracedMessage.getAk(), tracedMessage.getAction(), session.getId());

        if (!validateInvokeMessage(session, tracedMessage)) {
            return;
        }

        String messageSource = tracedMessage.getSource();

        // Learn route and write session route to Redis
        routingTable.learnRoute(tracedMessage, messageSource);
        writeSessionRouteToRedis(tracedMessage, messageSource, session);

        // 3-tier delivery — local → remote GW relay → pending queue
        dispatchToAgent(tracedMessage, messageSource);
    }

    /**
     * 业务助手云端路由。
     *
     * @return true 表示已由云端路由策略处理
     */
    private boolean routeBusinessInvoke(WebSocketSession session, GatewayMessage message) {
        InvokeRouteStrategy strategy = routeStrategyMap.get("business");
        if (strategy == null) {
            return false;
        }
        GatewayMessage tracedMsg = message.ensureTraceId();
        String source = tracedMsg.getSource();

        if (source != null && !source.isBlank()) {
            routingTable.learnRoute(tracedMsg, source);
            writeSessionRouteToRedis(tracedMsg, source, session);
        }

        strategy.route(tracedMsg, this::relayToSkill);
        return true;
    }

    /**
     * 校验上行 invoke 消息的 source、ak、userId。
     *
     * @return true 表示校验通过
     */
    private boolean shouldRouteBusinessInvoke(GatewayMessage message, String scope) {
        if ("business".equals(scope)) {
            return true;
        }
        if (message == null) {
            return false;
        }
        return ACTION_ABORT_SESSION.equals(normalizeAction(message.getAction()))
                && hasCloudRoutingHint(message);
    }

    private boolean hasCloudRoutingHint(GatewayMessage message) {
        if (message == null) {
            return false;
        }
        if (message.getBusinessTag() != null && !message.getBusinessTag().isBlank()) {
            return true;
        }
        return textAt(message.getPayload(), "cloudProfile") != null;
    }

    private static String normalizeAction(String action) {
        return action == null ? null : action.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static String textAt(com.fasterxml.jackson.databind.JsonNode node, String fieldName) {
        if (node == null || !node.isObject()) {
            return null;
        }
        com.fasterxml.jackson.databind.JsonNode value = node.path(fieldName);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        String text = value.asText(null);
        return (text == null || text.isBlank()) ? null : text;
    }

    private boolean validateInvokeMessage(WebSocketSession session, GatewayMessage tracedMessage) {
        // Validate source
        String boundSource = resolveBoundSource(session);
        String messageSource = tracedMessage.getSource();
        if (messageSource == null || messageSource.isBlank()) {
            log.warn("[SKIP] SkillRelayService.handleInvokeFromSkill: reason=missing_source, linkId={}",
                    session.getId());
            sendProtocolError(session, ERROR_SOURCE_NOT_ALLOWED);
            return false;
        }
        if (boundSource == null || !boundSource.equals(messageSource)) {
            log.warn(
                    "[SKIP] SkillRelayService.handleInvokeFromSkill: reason=source_mismatch, bound={}, message={}, linkId={}",
                    boundSource, messageSource, session.getId());
            sendProtocolError(session, ERROR_SOURCE_MISMATCH);
            return false;
        }

        // Validate ak
        if (tracedMessage.getAk() == null || tracedMessage.getAk().isBlank()) {
            log.warn("[SKIP] SkillRelayService.handleInvokeFromSkill: reason=missing_ak, linkId={}",
                    session.getId());
            return false;
        }

        // Validate userId
        String expectedUserId = redisMessageBroker.getAgentUser(tracedMessage.getAk());
        if (tracedMessage.getUserId() == null || tracedMessage.getUserId().isBlank()) {
            log.warn("[SKIP] SkillRelayService.handleInvokeFromSkill: reason=missing_userId, linkId={}, ak={}",
                    session.getId(), tracedMessage.getAk());
            return false;
        }
        if (expectedUserId == null || !tracedMessage.getUserId().equals(expectedUserId)) {
            log.warn(
                    "[SKIP] SkillRelayService.handleInvokeFromSkill: reason=userId_mismatch, expected={}, actual={}, ak={}",
                    expectedUserId, tracedMessage.getUserId(), tracedMessage.getAk());
            return false;
        }
        return true;
    }

    /**
     * 将 session 路由信息写入 Redis，用于跨集群路由。
     */
    private void writeSessionRouteToRedis(GatewayMessage tracedMessage, String source,
                                          WebSocketSession session) {
        String ssInstanceId = resolveSsInstanceId(session);
        if (ssInstanceId == null) {
            return;
        }
        String toolSessionId = extractToolSessionIdFromPayload(tracedMessage);
        if (toolSessionId != null && !toolSessionId.isBlank()) {
            redisMessageBroker.setSessionRoute(toolSessionId, source, ssInstanceId);
        }
        String welinkSessionId = tracedMessage.getWelinkSessionId();
        if (welinkSessionId != null && !welinkSessionId.isBlank()) {
            redisMessageBroker.setWelinkSessionRoute(welinkSessionId, source, ssInstanceId);
        }
    }

    /**
     * 3-tier delivery：local → remote GW relay → pending queue。
     */
    private void dispatchToAgent(GatewayMessage tracedMessage, String messageSource) {
        String ak = tracedMessage.getAk();
        GatewayMessage agentMessage = tracedMessage.withoutRoutingContext();

        if (deliverToLocalAgent(ak, agentMessage)) {
            relayLocalCount.incrementAndGet();
            log.info("[EXIT->AGENT] Delivered invoke locally: ak={}, action={}, source={}",
                    ak, tracedMessage.getAction(), messageSource);
            return;
        }

        if (relayToRemoteGw(ak, tracedMessage, messageSource)) {
            relayPubsubCount.incrementAndGet();
            log.info("[EXIT->RELAY] Relayed invoke to remote GW: ak={}, action={}, source={}",
                    ak, tracedMessage.getAction(), messageSource);
            return;
        }

        enqueueToPending(ak, agentMessage);
        relayPendingCount.incrementAndGet();
        log.info("[EXIT->PENDING] Enqueued invoke to pending: ak={}, action={}, source={}",
                ak, tracedMessage.getAction(), messageSource);
    }

    /**
     * Attempts to deliver a message to a locally connected Agent.
     *
     * @return true if the Agent is local and message was delivered
     */
    private boolean deliverToLocalAgent(String ak, GatewayMessage message) {
        if (eventRelayService == null) {
            return false;
        }
        return eventRelayService.sendToLocalAgentIfPresent(ak, message);
    }

    /**
     * Relays a message to a remote GW instance that holds the Agent connection.
     *
     * @return true if a remote GW instance was found and the relay was published
     */
    private boolean relayToRemoteGw(String ak, GatewayMessage originalMessage, String sourceType) {
        String targetInstanceId = redisMessageBroker.getInternalAgentInstance(ak);
        if (targetInstanceId == null || targetInstanceId.isBlank()) {
            return false;
        }
        // Do not relay to self
        if (gatewayInstanceId.equals(targetInstanceId)) {
            return false;
        }

        try {
            // Build routing keys for UpstreamRoutingTable propagation on the target GW
            List<String> routingKeys = buildRoutingKeys(originalMessage);
            String originalJson = objectMapper.writeValueAsString(originalMessage.withoutRoutingContext());
            RelayMessage relayMessage = RelayMessage.of(sourceType, routingKeys, originalJson);
            String relayJson = objectMapper.writeValueAsString(relayMessage);

            redisMessageBroker.publishToGwRelay(targetInstanceId, relayJson);
            log.info("[V2] Published relay to GW instance: target={}, ak={}, routingKeys={}",
                    targetInstanceId, ak, routingKeys);
            return true;
        } catch (Exception e) {
            log.error("[V2] Failed to relay to remote GW: target={}, ak={}", targetInstanceId, ak, e);
            return false;
        }
    }

    /**
     * Builds routing keys from a message for UpstreamRoutingTable propagation.
     */
    private List<String> buildRoutingKeys(GatewayMessage message) {
        List<String> keys = new ArrayList<>();
        String toolSessionId = message.getToolSessionId();
        if (toolSessionId != null && !toolSessionId.isBlank()) {
            keys.add(toolSessionId);
        }
        String welinkSessionId = message.getWelinkSessionId();
        if (welinkSessionId != null && !welinkSessionId.isBlank()) {
            keys.add(UpstreamRoutingTable.WELINK_KEY_PREFIX + welinkSessionId);
        }
        // Also check payload.toolSessionId
        String payloadToolSessionId = extractToolSessionIdFromPayload(message);
        if (payloadToolSessionId != null && !payloadToolSessionId.isBlank() && !payloadToolSessionId.equals(toolSessionId)) {
            keys.add(payloadToolSessionId);
        }
        return keys;
    }

    /**
     * Enqueues a message to the pending queue for an offline Agent.
     */
    private void enqueueToPending(String ak, GatewayMessage message) {
        try {
            String json = objectMapper.writeValueAsString(message);
            redisMessageBroker.enqueuePending(ak, json, PENDING_TTL);
        } catch (Exception e) {
            log.error("[V2] Failed to enqueue pending message: ak={}", ak, e);
        }
    }

    // ==================== V2 route_confirm / route_reject ====================

    /**
     * Handles route_confirm messages from source services.
     * Learns the confirmed route in UpstreamRoutingTable.
     */
    public void handleRouteConfirm(GatewayMessage message) {
        String toolSessionId = message.getToolSessionId();
        String sourceType = message.getSource();
        log.info("[ENTRY] SkillRelayService.handleRouteConfirm: toolSessionId={}, sourceType={}",
                toolSessionId, sourceType);

        if (toolSessionId != null && sourceType != null) {
            List<String> keys = new ArrayList<>();
            keys.add(toolSessionId);
            String welinkSessionId = message.getWelinkSessionId();
            if (welinkSessionId != null && !welinkSessionId.isBlank()) {
                keys.add(UpstreamRoutingTable.WELINK_KEY_PREFIX + welinkSessionId);
            }
            routingTable.learnFromRelay(keys, sourceType);
            log.info("[EXIT] SkillRelayService.handleRouteConfirm: learned route toolSessionId={} -> sourceType={}",
                    toolSessionId, sourceType);
        }
    }

    /**
     * Handles route_reject messages from source services.
     * Only logs the rejection; no further action taken.
     */
    public void handleRouteReject(GatewayMessage message) {
        log.info("[ENTRY] SkillRelayService.handleRouteReject: toolSessionId={}, source={}, reason=routing_rejected",
                message.getToolSessionId(), message.getSource());
    }

    /**
     * 从 invoke 消息的 payload 中提取 toolSessionId。
     */
    private String extractToolSessionIdFromPayload(GatewayMessage message) {
        if (message.getPayload() == null || message.getPayload().isNull()) {
            return null;
        }
        var toolSessionNode = message.getPayload().path("toolSessionId");
        return toolSessionNode.isMissingNode() || toolSessionNode.isNull()
                ? null
                : toolSessionNode.asText(null);
    }

    // ==================== 辅助方法 ====================

    public int getActiveSourceConnectionCount() {
        return (int) sourceTypeSessions.values().stream()
                .flatMap(instanceMap -> instanceMap.values().stream())
                .flatMap(sessionMap -> sessionMap.values().stream())
                .filter(WebSocketSession::isOpen)
                .count();
    }

    private int getActiveConnectionCount(String sourceType) {
        Map<String, Map<String, WebSocketSession>> instanceMap = sourceTypeSessions.get(sourceType);
        if (instanceMap == null)
            return 0;
        return (int) instanceMap.values().stream()
                .flatMap(sessionMap -> sessionMap.values().stream())
                .filter(WebSocketSession::isOpen)
                .count();
    }

    private AsyncSessionSender getOrCreateSender(WebSocketSession session) {
        return sessionSenders.computeIfAbsent(session.getId(), k -> {
            AsyncSessionSender sender = new AsyncSessionSender(session);
            sender.start();
            return sender;
        });
    }

    public void removeSessionSender(String sessionId) {
        AsyncSessionSender sender = sessionSenders.remove(sessionId);
        if (sender != null) {
            sender.shutdown();
        }
    }

    private void logRoutingInfo(GatewayMessage message, String format, Object... args) {
        if (message != null && GatewayMessage.Type.TOOL_EVENT.equals(message.getType())) {
            log.debug(format, args);
            return;
        }
        log.info(format, args);
    }

    private boolean sendToSession(WebSocketSession session, GatewayMessage message) {
        try {
            String json = objectMapper.writeValueAsString(message);
            AsyncSessionSender sender = getOrCreateSender(session);
            boolean enqueued = sender.enqueue(new TextMessage(json));
            if (enqueued) {
                logRoutingInfo(message, "[EXIT->SS] Enqueued to skill session: linkId={}, type={}, pending={}",
                        session.getId(), message.getType(), sender.pendingCount());
            }
            return enqueued;
        } catch (IOException e) {
            log.error("[EXIT->SS] Failed to serialize message: linkId={}, type={}",
                    session.getId(), message.getType(), e);
            return false;
        }
    }

    private String resolveBoundSource(WebSocketSession session) {
        Object source = session.getAttributes().get(SOURCE_ATTR);
        return source instanceof String ? (String) source : null;
    }

    private String resolveSsInstanceId(WebSocketSession session) {
        Object instanceId = session.getAttributes().get(INSTANCE_ID_ATTR);
        return instanceId instanceof String ? (String) instanceId : null;
    }

    private void sendProtocolError(WebSocketSession session, String reason) {
        try {
            String json = objectMapper.writeValueAsString(GatewayMessage.registerRejected(reason));
            AsyncSessionSender sender = getOrCreateSender(session);
            sender.enqueue(new TextMessage(json));
        } catch (IOException e) {
            log.error("Failed to serialize protocol error: linkId={}, reason={}", session.getId(), reason, e);
        }
    }

    // ==================== Source 连接查找 ====================

    /**
     * Finds a locally connected Source WebSocket session by sourceType and sourceInstanceId.
     * Used by EventRelayService for to-source relay delivery.
     *
     * @param sourceType       source type, e.g. "skill-server"
     * @param sourceInstanceId source instance ID
     * @return the WebSocket session if found locally and open, null otherwise
     */
    public WebSocketSession findLocalSourceConnection(String sourceType, String sourceInstanceId) {
        if (sourceType == null || sourceInstanceId == null) {
            return null;
        }
        Map<String, Map<String, WebSocketSession>> instanceMap = sourceTypeSessions.get(sourceType);
        if (instanceMap == null) {
            return null;
        }
        Map<String, WebSocketSession> sessionMap = instanceMap.get(sourceInstanceId);
        if (sessionMap == null) {
            return null;
        }
        return sessionMap.values().stream()
                .filter(WebSocketSession::isOpen)
                .findFirst()
                .orElse(null);
    }

    // ==================== 定时任务 ====================

    /**
     * Periodically logs routing metrics for observability.
     * Outputs cumulative counters since service startup.
     */
    @Scheduled(fixedDelay = 60_000)
    void logMetrics() {
        log.info("[METRICS] relay: local={}, pubsub={}, pending={} | routing: L1hit={}, L2redis={}, L3broadcast={}, broadcastFallback={}",
                relayLocalCount.get(), relayPubsubCount.get(), relayPendingCount.get(),
                routingHitCount.get(), routingRedisL2Count.get(), routingL3BroadcastCount.get(),
                routingBroadcastCount.get());
    }

    /**
     * Periodically refreshes source connection heartbeats in Redis.
     * Updates the timestamp for all locally connected Mesh source sessions.
     */
    @Scheduled(fixedDelay = 10_000)
    public void refreshSourceConnectionHeartbeats() {
        record StaleEntry(String sourceType, String ssInstanceId, String sessionId) {}
        List<StaleEntry> staleEntries = new ArrayList<>();

        for (Map.Entry<String, Map<String, Map<String, WebSocketSession>>> typeEntry : sourceTypeSessions.entrySet()) {
            String sourceType = typeEntry.getKey();
            for (Map.Entry<String, Map<String, WebSocketSession>> instanceEntry : typeEntry.getValue().entrySet()) {
                String ssInstanceId = instanceEntry.getKey();
                for (Map.Entry<String, WebSocketSession> sessionEntry : instanceEntry.getValue().entrySet()) {
                    String sessionId = sessionEntry.getKey();
                    WebSocketSession session = sessionEntry.getValue();
                    if (session.isOpen()) {
                        redisMessageBroker.refreshSourceConnectionHeartbeat(sourceType, ssInstanceId, gatewayInstanceId, sessionId);
                    } else {
                        staleEntries.add(new StaleEntry(sourceType, ssInstanceId, sessionId));
                    }
                }
            }
        }

        for (StaleEntry stale : staleEntries) {
            Map<String, Map<String, WebSocketSession>> instanceMap = sourceTypeSessions.get(stale.sourceType());
            if (instanceMap != null) {
                Map<String, WebSocketSession> sessionMap = instanceMap.get(stale.ssInstanceId());
                if (sessionMap != null) {
                    sessionMap.remove(stale.sessionId());
                    if (sessionMap.isEmpty()) {
                        instanceMap.remove(stale.ssInstanceId());
                    }
                }
                if (instanceMap.isEmpty()) {
                    sourceTypeSessions.remove(stale.sourceType());
                }
            }

            String nodeKey = stale.ssInstanceId() + "#" + stale.sessionId();
            hashRings.computeIfPresent(stale.sourceType(), (k, ring) -> {
                ring.removeNode(nodeKey);
                return ring.isEmpty() ? null : ring;
            });

            redisMessageBroker.unregisterSourceConnection(stale.sourceType(), stale.ssInstanceId(), gatewayInstanceId, stale.sessionId());

            log.info("[Mesh] Lazy-cleaned stale session during heartbeat: sourceType={}, ssInstanceId={}, sessionId={}",
                    stale.sourceType(), stale.ssInstanceId(), stale.sessionId());
        }
    }

    // ==================== Accessors for testing ====================

    /** Returns the consistent hash ring for the given sourceType. Package-private for testing. */
    ConsistentHashRing<WebSocketSession> getHashRing(String sourceType) {
        return hashRings.get(sourceType);
    }

    @PreDestroy
    public void destroy() {
        sourceTypeSessions.clear();
        hashRings.clear();
        log.info("SkillRelayService destroyed: cleared all mesh connections and hash rings");
    }
}
