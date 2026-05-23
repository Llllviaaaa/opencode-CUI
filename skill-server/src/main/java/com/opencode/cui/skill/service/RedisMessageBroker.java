package com.opencode.cui.skill.service;

import io.lettuce.core.api.async.BaseRedisAsyncCommands;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnection;
import org.springframework.data.redis.core.RedisConnectionUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;


/**
 * Redis pub/sub message broker for multi-instance coordination.
 *
 * Channel patterns:
 * - agent:{agentId} - messages to specific agent
 * - user-stream:{userId} - realtime messages to all instances holding the
 * user's stream link
 */
@Slf4j
@Service
public class RedisMessageBroker {

    private final StringRedisTemplate redisTemplate;
    private final RedisMessageListenerContainer listenerContainer;

    /** Track active subscriptions for cleanup */
    private final Map<String, MessageListener> activeListeners = new ConcurrentHashMap<>();
    private final AtomicBoolean reconnectInProgress = new AtomicBoolean(false);

    public RedisMessageBroker(StringRedisTemplate redisTemplate,
            RedisMessageListenerContainer listenerContainer) {
        this.redisTemplate = redisTemplate;
        this.listenerContainer = listenerContainer;
    }

    /**
     * Publish a message to an agent channel.
     *
     * @param agentId the target agent ID
     * @param message the message to publish (as JSON string)
     */
    public void publishToAgent(String agentId, String message) {
        String channel = "agent:" + agentId;
        publishMessage(channel, message);
    }

    public void publishToUser(String userId, String message) {
        String channel = "user-stream:" + userId;
        publishMessage(channel, message);
    }

    /**
     * Subscribe to an agent channel.
     *
     * @param agentId the agent ID to subscribe to
     * @param handler callback to handle received messages (JSON string)
     */
    public void subscribeToAgent(String agentId, Consumer<String> handler) {
        String channel = "agent:" + agentId;
        subscribe(channel, handler);
    }

    public void subscribeToUser(String userId, Consumer<String> handler) {
        String channel = "user-stream:" + userId;
        subscribe(channel, handler);
    }

    /**
     * Unsubscribe from an agent channel.
     *
     * @param agentId the agent ID to unsubscribe from
     */
    public void unsubscribeFromAgent(String agentId) {
        String channel = "agent:" + agentId;
        unsubscribe(channel);
    }

    public void unsubscribeFromUser(String userId) {
        String channel = "user-stream:" + userId;
        unsubscribe(channel);
    }

    // ========== Generic channel pub/sub ==========

    /**
     * Publish a message to a named channel.
     *
     * @param channel the full channel name (e.g. "stream:im")
     * @param message the message to publish (as JSON string)
     */
    public void publishToChannel(String channel, String message) {
        publishMessage(channel, message);
    }

    /**
     * Subscribe to a named channel.
     *
     * @param channel the full channel name
     * @param handler callback to handle received messages
     */
    public void subscribeToChannel(String channel, Consumer<String> handler) {
        subscribe(channel, handler);
    }

    /**
     * Unsubscribe from a named channel.
     *
     * @param channel the full channel name
     */
    public void unsubscribeFromChannel(String channel) {
        unsubscribe(channel);
    }

    /**
     * Check if a channel is currently subscribed.
     *
     * @param channel the full channel name
     * @return true if subscribed
     */
    public boolean isChannelSubscribed(String channel) {
        return activeListeners.containsKey(channel);
    }

    // ========== Internal methods ==========

    private void publishMessage(String channel, String message) {
        try {
            redisTemplate.convertAndSend(channel, message);
            log.info("Published to Redis channel {}", channel);
        } catch (Exception e) {
            log.error("Failed to publish to Redis channel {}: {}", channel, e.getMessage(), e);
        }
    }

    private void subscribe(String channel, Consumer<String> handler) {
        unsubscribe(channel);

        MessageListener listener = (Message message, byte[] pattern) -> {
            try {
                String json = new String(message.getBody(), StandardCharsets.UTF_8);
                handler.accept(json);

                log.info("Received from Redis channel {}", channel);
            } catch (Exception e) {
                log.error("Failed to process message from channel {}: {}",
                        channel, e.getMessage(), e);
            }
        };

        activeListeners.put(channel, listener);
        listenerContainer.addMessageListener(listener, new ChannelTopic(channel));

        log.info("Subscribed to Redis channel: {}", channel);
    }

    private void unsubscribe(String channel) {
        MessageListener listener = activeListeners.remove(channel);
        if (listener != null) {
            listenerContainer.removeMessageListener(listener);
            log.info("Unsubscribed from Redis channel: {}", channel);
        }
    }

    public boolean isUserSubscribed(String userId) {
        String channel = "user-stream:" + userId;
        return activeListeners.containsKey(channel);
    }

    // ==================== Pub/sub silent-failure self-healing ====================

    /**
     * 查询某 channel 在 Redis 端真实订阅者数量（PUBSUB NUMSUB）。
     *
     * <p>这是 Redis server 端真值，能捕获 Spring 侧 {@code activeListeners} 内存
     * map 检测不到的"长连接断、listener 仍在 map"的 silent-failure 场景。</p>
     *
     * @param channel 完整 channel 名
     * @return 真实订阅者数；channel 不存在或异常时返回 0
     */
    public long physicalSubscriberCount(String channel) {
        if (channel == null || channel.isBlank()) {
            return 0L;
        }
        // 直接从 ConnectionFactory 拿 raw RedisConnection，绕开 RedisTemplate.execute
        // 默认走的 CloseSuppressingInvocationHandler。后者会把 RedisConnection 包成
        // JDK 动态代理（jdk.proxy2.$ProxyN），代理实现 RedisConnection interface
        // 但**不是** LettuceConnection 类的实例，导致下面 instanceof cast 永远 false
        // → 早 return 0L → self-check 永远失败 → forceReconnectListenerContainer 风暴。
        // 见 spec/skill-server/backend/conventions.md "RedisTemplate.execute 包 connection 成 proxy"。
        RedisConnectionFactory factory = redisTemplate.getRequiredConnectionFactory();
        RedisConnection conn = RedisConnectionUtils.getConnection(factory);
        try {
            // Lettuce native async API：避免 raw execute("PUBSUB", "NUMSUB", ...) 经
            // ByteArrayOutput 解码 RESP integer 时抛 UnsupportedOperationException
            // (Lettuce 6.4.x：ByteArrayOutput 不实现 set(long))。
            if (!(conn instanceof LettuceConnection lettuce)) {
                // 切到 Jedis 或其他实现时 graceful 降级；本路径在 Lettuce 部署下走不到。
                return 0L;
            }
            Object nativeConn = lettuce.getNativeConnection();
            if (!(nativeConn instanceof BaseRedisAsyncCommands<?, ?> base)) {
                return 0L;
            }
            @SuppressWarnings("unchecked")
            BaseRedisAsyncCommands<byte[], byte[]> async =
                    (BaseRedisAsyncCommands<byte[], byte[]>) base;
            byte[] channelBytes = channel.getBytes(StandardCharsets.UTF_8);
            try {
                Map<byte[], Long> result = async.pubsubNumsub(channelBytes)
                        .get(2, TimeUnit.SECONDS);
                // PUBSUB NUMSUB 协议保证 [name, count] 成对返回；0 订阅时
                // entry 仍存在 (channel, 0L)，不会 missing。
                return result.values().stream().findFirst().orElse(0L);
            } catch (TimeoutException | ExecutionException e) {
                log.warn("physicalSubscriberCount failed: channel={}, error={}",
                        channel, e.getMessage(), e);
                return 0L;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("physicalSubscriberCount interrupted: channel={}", channel);
                return 0L;
            }
        } catch (Exception e) {
            log.warn("physicalSubscriberCount failed: channel={}, error={}", channel, e.getMessage(), e);
            return 0L;
        } finally {
            RedisConnectionUtils.releaseConnection(conn, factory);
        }
    }

    /**
     * 自愈 Redis pub/sub silent failure。
     *
     * <p>当 {@link #physicalSubscriberCount} 检测到本实例 relay channel 在 Redis 端订阅数为 0，
     * 但 listener 仍在 {@code activeListeners} 中时，优先重新提交该单个 channel 的订阅并确认
     * {@code PUBSUB NUMSUB} 恢复。这样可以避开整容器 {@code stop/start} 导致的 Spring
     * {@code SubscriptionTask aborted} 路径。</p>
     *
     * <p>如果单通道重订阅失败或超时，再退回到重启 {@link RedisMessageListenerContainer}。
     * 退回路径会短暂影响该 container 下所有订阅（agent:*、user-stream:*、ss:relay:*）。</p>
     *
     * @param verifyChannel 用于校验订阅恢复的 channel；通常是调用方自己的 {@code ss:relay:{instanceId}}
     * @param timeoutMs     等待物理订阅恢复的最大时间（毫秒），超时返回 false
     * @return true 表示 {@code verifyChannel} 在 Redis 端订阅数 >0；false 表示异常或超时未恢复
     */
    public boolean forceReconnectListenerContainer(String verifyChannel, long timeoutMs) {
        if (!reconnectInProgress.compareAndSet(false, true)) {
            log.warn("Force reconnect already in progress: verifyChannel={}", verifyChannel);
            return waitForSubscriptionRestored(verifyChannel, timeoutMs);
        }
        int affectedSubscriptions = activeListeners.size();
        log.warn("Force reconnecting RedisMessageListenerContainer: affectedSubscriptions={}, verifyChannel={}",
                affectedSubscriptions, verifyChannel);
        try {
            if (resubscribeActiveListener(verifyChannel, timeoutMs)) {
                return true;
            }
            listenerContainer.stop();
            if (!startListenerContainer(verifyChannel)) {
                return false;
            }
            return waitForSubscriptionRestored(verifyChannel, timeoutMs);
        } catch (Exception e) {
            log.error("forceReconnectListenerContainer failed: verifyChannel={}, error={}",
                    verifyChannel, e.getMessage(), e);
            return false;
        } finally {
            reconnectInProgress.set(false);
        }
    }

    private boolean resubscribeActiveListener(String channel, long timeoutMs) {
        if (channel == null || channel.isBlank()) {
            return false;
        }
        MessageListener listener = activeListeners.get(channel);
        if (listener == null) {
            log.warn("Cannot resubscribe Redis channel without active listener: channel={}", channel);
            return false;
        }
        try {
            listenerContainer.addMessageListener(listener, new ChannelTopic(channel));
            if (waitForSubscriptionRestored(channel, timeoutMs)) {
                log.warn("Redis channel resubscribe restored subscriber: channel={}", channel);
                return true;
            }
            log.warn("Redis channel resubscribe timed out: channel={}, timeoutMs={}", channel, timeoutMs);
            return false;
        } catch (Exception e) {
            log.warn("Redis channel resubscribe failed: channel={}, error={}", channel, e.getMessage(), e);
            return false;
        }
    }

    private boolean startListenerContainer(String verifyChannel) {
        try {
            listenerContainer.start();
            return true;
        } catch (Exception e) {
            log.error("forceReconnectListenerContainer start failed: verifyChannel={}, error={}",
                    verifyChannel, e.getMessage(), e);
            resetListenerContainerAfterFailedStart(verifyChannel);
            return false;
        }
    }

    private void resetListenerContainerAfterFailedStart(String verifyChannel) {
        try {
            listenerContainer.stop();
        } catch (Exception stopError) {
            log.warn("Failed to reset RedisMessageListenerContainer after start failure: verifyChannel={}, error={}",
                    verifyChannel, stopError.getMessage(), stopError);
        }
    }

    /**
     * 短轮询等待 {@code channel} 在 Redis 端订阅数恢复 (>0)。
     *
     * <p>{@link RedisMessageListenerContainer#start()} 是异步注册 cachedListeners，
     * 必须等待物理订阅真正落到 Redis 后再返回，否则调用方可能在 listener 还未生效时
     * 就开始处理消息。轮询间隔 100ms。</p>
     */
    private boolean waitForSubscriptionRestored(String channel, long timeoutMs) {
        if (channel == null || channel.isBlank()) {
            return false;
        }
        long deadline = System.currentTimeMillis() + Math.max(0L, timeoutMs);
        while (true) {
            if (physicalSubscriberCount(channel) > 0L) {
                return true;
            }
            if (System.currentTimeMillis() >= deadline) {
                return false;
            }
            try {
                Thread.sleep(100L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    // ==================== SS relay pub/sub (Task 2.6) ====================

    private static final String SS_RELAY_CHANNEL_PREFIX = "ss:relay:";

    /**
     * Subscribe to this instance's SS relay channel.
     * Messages relayed from other SS instances arrive here.
     *
     * @param instanceId the local SS instance ID
     * @param handler    callback to handle received relay messages (JSON string)
     */
    public void subscribeToSsRelay(String instanceId, Consumer<String> handler) {
        String channel = SS_RELAY_CHANNEL_PREFIX + instanceId;
        subscribe(channel, handler);
    }

    /**
     * Publish a message to the target SS instance's relay channel.
     * Uses Redis PUBLISH which returns the number of subscribers that received the
     * message.
     *
     * @param targetInstanceId the target SS instance ID
     * @param message          the message to relay (JSON string)
     * @return number of subscribers that received the message; 0 means nobody is
     *         listening
     */
    public long publishToSsRelay(String targetInstanceId, String message) {
        String channel = SS_RELAY_CHANNEL_PREFIX + targetInstanceId;
        try {
            Long receivers = redisTemplate.convertAndSend(channel, message);
            log.info("Published to SS relay channel: target={}, receivers={}", targetInstanceId, receivers);
            return receivers != null ? receivers : 0;
        } catch (Exception e) {
            log.error("Failed to publish to SS relay channel: target={}, error={}",
                    targetInstanceId, e.getMessage(), e);
            return 0;
        }
    }

    /**
     * Unsubscribe from this instance's SS relay channel.
     *
     * @param instanceId the local SS instance ID
     */
    public void unsubscribeFromSsRelay(String instanceId) {
        String channel = SS_RELAY_CHANNEL_PREFIX + instanceId;
        unsubscribe(channel);
    }

    // ==================== conn:ak 查询（v3 新增） ====================

    /**
     * 查询 AK 连接在哪个 Gateway 实例上。
     *
     * @return gatewayInstanceId，不存在返回 null
     */
    public String getConnAk(String ak) {
        if (ak == null || ak.isBlank()) {
            return null;
        }
        return redisTemplate.opsForValue().get("conn:ak:" + ak);
    }

    // ==================== toolSessionId → sessionId 缓存 ====================

    private static final String TOOL_SESSION_PREFIX = "ss:tool-session:";
    private static final long TOOL_SESSION_TTL_HOURS = 24;

    public String getToolSessionMapping(String toolSessionId) {
        return redisTemplate.opsForValue().get(TOOL_SESSION_PREFIX + toolSessionId);
    }

    public void setToolSessionMapping(String toolSessionId, String sessionId) {
        redisTemplate.opsForValue().set(
                TOOL_SESSION_PREFIX + toolSessionId,
                sessionId,
                TOOL_SESSION_TTL_HOURS, TimeUnit.HOURS);
    }

    /**
     * 失效 toolSessionId → welinkSessionId 反查缓存。
     * 用于 session rebuild / updateToolSessionId remap 场景，避免旧 toolSessionId 错误路由。
     */
    public void deleteToolSessionMapping(String toolSessionId) {
        if (toolSessionId == null || toolSessionId.isBlank()) {
            return;
        }
        log.info("[ENTRY] RedisMessageBroker.deleteToolSessionMapping: toolSessionId={}", toolSessionId);
        redisTemplate.delete(TOOL_SESSION_PREFIX + toolSessionId);
    }

    // ==================== 跨实例消息序号（Task 2.8） ====================

    private static final String STREAM_SEQ_KEY_PREFIX = "ss:stream-seq:";

    /**
     * 获取指定会话的下一个跨实例传输序号（Redis INCR）。
     * 多 SS 实例共享同一序号空间，确保消息在前端按正确顺序渲染。
     *
     * @param welinkSessionId 会话 ID
     * @return 递增后的序号（从 1 开始）
     */
    public long nextStreamSeq(String welinkSessionId) {
        String key = STREAM_SEQ_KEY_PREFIX + welinkSessionId;
        Long seq = redisTemplate.opsForValue().increment(key);
        return seq != null ? seq : 1L;
    }

    // ==================== invoke-source 标记 ====================

    private static final String INVOKE_SOURCE_PREFIX = "invoke-source:";

    public void setInvokeSource(String sessionId, String source, int ttlSeconds) {
        try {
            redisTemplate.opsForValue().set(
                    INVOKE_SOURCE_PREFIX + sessionId, source, ttlSeconds, TimeUnit.SECONDS);
            log.debug("Set invoke-source: sessionId={}, source={}, ttl={}s", sessionId, source, ttlSeconds);
        } catch (Exception e) {
            log.error("Failed to set invoke-source: sessionId={}, error={}", sessionId, e.getMessage());
        }
    }

    public String getInvokeSource(String sessionId) {
        try {
            return redisTemplate.opsForValue().get(INVOKE_SOURCE_PREFIX + sessionId);
        } catch (Exception e) {
            log.error("Failed to get invoke-source: sessionId={}, error={}", sessionId, e.getMessage());
            return null;
        }
    }

    public void expireInvokeSource(String sessionId, int ttlSeconds) {
        try {
            redisTemplate.expire(INVOKE_SOURCE_PREFIX + sessionId, ttlSeconds, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.error("Failed to expire invoke-source: sessionId={}, error={}", sessionId, e.getMessage());
        }
    }

    // ==================== WS 实例自管路由表（owner-only writes） ====================

    /**
     * 每实例自管的 WS 持有路由表 key 前缀。
     * <p>结构：HASH {@code external-ws:held-by:{instanceId}} → {@code {domain → connectionCount}}。
     * <p>仅 owner 实例自己写入和续 TTL；任何其他实例不得 EXPIRE/HSET 该 key，
     * 避免旧实现中"任一活实例 EXPIRE 续整个 hash → 死实例字段被无限期续命"。
     */
    private static final String WS_HELD_BY_PREFIX = "external-ws:held-by:";

    /** 活实例花名册 ZSET key（score = unix-ms 心跳时间）。 */
    private static final String INSTANCE_ROSTER_KEY = "instance:roster";

    /**
     * 批量写入本实例持有的 domain → connectionCount，并续 TTL。
     * 适用于 owner 周期心跳：先 snapshot 本地 connection pool，整批 putAll + EXPIRE。
     *
     * @param instanceId 本实例 ID（owner-only）
     * @param snapshot {@code {domain → connectionCount}}；empty 时跳过（调用方应改走 {@link #heldByDeleteKey}）
     * @param ttlSeconds key TTL
     */
    public void heldByPutAll(String instanceId, Map<String, Integer> snapshot, int ttlSeconds) {
        if (instanceId == null || instanceId.isBlank() || snapshot == null || snapshot.isEmpty()) {
            return;
        }
        try {
            String key = WS_HELD_BY_PREFIX + instanceId;
            Map<String, String> stringSnapshot = new HashMap<>(snapshot.size());
            snapshot.forEach((domain, count) -> stringSnapshot.put(domain, String.valueOf(count)));
            redisTemplate.opsForHash().putAll(key, stringSnapshot);
            redisTemplate.expire(key, ttlSeconds, TimeUnit.SECONDS);
            log.debug("heldByPutAll: instanceId={}, domains={}, ttlSec={}",
                    instanceId, snapshot.keySet(), ttlSeconds);
        } catch (Exception e) {
            log.error("Failed to heldByPutAll: instanceId={}, error={}", instanceId, e.getMessage());
        }
    }

    /** 删除本实例 held-by hash 中某个 domain 字段。 */
    public void heldByDeleteField(String instanceId, String domain) {
        if (instanceId == null || instanceId.isBlank() || domain == null || domain.isBlank()) {
            return;
        }
        try {
            redisTemplate.opsForHash().delete(WS_HELD_BY_PREFIX + instanceId, domain);
        } catch (Exception e) {
            log.error("Failed to heldByDeleteField: instanceId={}, domain={}, error={}",
                    instanceId, domain, e.getMessage());
        }
    }

    /** 删除本实例 held-by 整个 key（@PreDestroy 或 snapshot empty 时调用）。 */
    public void heldByDeleteKey(String instanceId) {
        if (instanceId == null || instanceId.isBlank()) {
            return;
        }
        try {
            redisTemplate.delete(WS_HELD_BY_PREFIX + instanceId);
            log.debug("heldByDeleteKey: instanceId={}", instanceId);
        } catch (Exception e) {
            log.error("Failed to heldByDeleteKey: instanceId={}, error={}", instanceId, e.getMessage());
        }
    }

    /**
     * 批量读取多个实例针对同一 domain 的 connectionCount（pipeline HGET）。
     *
     * @param instanceIds 候选实例 ID 列表（调用方应先用 {@link #rangeAliveInstances} 过滤死实例）
     * @param domain WS source / domain
     * @return {@code {instanceId → count}}；HGET 返回 null 的实例不会出现在结果中
     */
    public Map<String, Integer> heldByGetBatch(List<String> instanceIds, String domain) {
        if (instanceIds == null || instanceIds.isEmpty() || domain == null || domain.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            // executePipelined 一次性把多个 HGET 发出去，减少 RTT
            List<Object> results = redisTemplate.executePipelined(
                    (org.springframework.data.redis.core.RedisCallback<Object>) connection -> {
                        for (String id : instanceIds) {
                            byte[] keyBytes = (WS_HELD_BY_PREFIX + id).getBytes(StandardCharsets.UTF_8);
                            byte[] fieldBytes = domain.getBytes(StandardCharsets.UTF_8);
                            connection.hashCommands().hGet(keyBytes, fieldBytes);
                        }
                        return null;
                    });
            Map<String, Integer> out = new LinkedHashMap<>();
            for (int i = 0; i < instanceIds.size() && i < results.size(); i++) {
                Object raw = results.get(i);
                if (raw == null) {
                    continue;
                }
                Integer count = parseCount(raw);
                if (count == null) {
                    continue;
                }
                out.put(instanceIds.get(i), count);
            }
            return out;
        } catch (Exception e) {
            log.error("Failed to heldByGetBatch: domain={}, candidates={}, error={}",
                    domain, instanceIds.size(), e.getMessage());
            return Collections.emptyMap();
        }
    }

    private Integer parseCount(Object raw) {
        try {
            if (raw instanceof Number n) {
                return n.intValue();
            }
            return Integer.parseInt(raw.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ==================== 活实例花名册 ZSET ====================

    /** ZADD instance:roster {nowMs} {instanceId}：owner-only。 */
    public void addToInstanceRoster(String instanceId, long nowMs) {
        if (instanceId == null || instanceId.isBlank()) {
            return;
        }
        try {
            redisTemplate.opsForZSet().add(INSTANCE_ROSTER_KEY, instanceId, (double) nowMs);
        } catch (Exception e) {
            log.error("Failed to addToInstanceRoster: instanceId={}, error={}", instanceId, e.getMessage());
        }
    }

    /** ZREM instance:roster {instanceId}：owner-only，@PreDestroy graceful 路径加速感知。 */
    public void removeFromInstanceRoster(String instanceId) {
        if (instanceId == null || instanceId.isBlank()) {
            return;
        }
        try {
            redisTemplate.opsForZSet().remove(INSTANCE_ROSTER_KEY, instanceId);
        } catch (Exception e) {
            log.error("Failed to removeFromInstanceRoster: instanceId={}, error={}", instanceId, e.getMessage());
        }
    }

    /**
     * ZRANGEBYSCORE instance:roster {cutoffMs} +inf：返回 score >= cutoffMs 的活实例列表。
     *
     * @param cutoffMs 时间 cutoff（unix-ms），通常 = now - heartbeatTtlMs
     * @return 活实例 ID 列表；异常时返回空（调用方走 L3 降级）
     */
    public List<String> rangeAliveInstances(long cutoffMs) {
        try {
            Set<String> alive = redisTemplate.opsForZSet().rangeByScore(
                    INSTANCE_ROSTER_KEY, (double) cutoffMs, Double.POSITIVE_INFINITY);
            if (alive == null || alive.isEmpty()) {
                return Collections.emptyList();
            }
            return new java.util.ArrayList<>(alive);
        } catch (Exception e) {
            log.error("Failed to rangeAliveInstances: cutoffMs={}, error={}", cutoffMs, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * ZREMRANGEBYSCORE instance:roster 0 {beforeMs}：lazy GC 过期实例条目。
     * 由 owner 顺手在心跳时执行，无需独立调度。
     */
    public void pruneRoster(long beforeMs) {
        try {
            redisTemplate.opsForZSet().removeRangeByScore(INSTANCE_ROSTER_KEY, 0d, (double) beforeMs);
        } catch (Exception e) {
            log.error("Failed to pruneRoster: beforeMs={}, error={}", beforeMs, e.getMessage());
        }
    }


    /**
     * 尝试获取分布式去重锁（SET NX + TTL）。
     *
     * @param key 去重 key
     * @param ttl 锁超时时间
     * @return true 表示获取成功（首次处理），false 表示已被其他实例处理
     */
    public Boolean tryAcquire(String key, java.time.Duration ttl) {
        try {
            return redisTemplate.opsForValue().setIfAbsent(key, "1", ttl);
        } catch (Exception e) {
            log.warn("tryAcquire failed, allowing through: key={}, error={}", key, e.getMessage());
            return true; // Redis 异常时放行，避免消息丢失
        }
    }

}
