package com.opencode.cui.skill.service;

import io.lettuce.core.api.async.BaseRedisAsyncCommands;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.lettuce.LettuceConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
        try {
            // Lettuce native async API：避免 raw execute("PUBSUB", "NUMSUB", ...) 经
            // ByteArrayOutput 解码 RESP integer 时抛 UnsupportedOperationException
            // (Lettuce 6.4.x：ByteArrayOutput 不实现 set(long))。
            Long count = redisTemplate.execute((RedisCallback<Long>) conn -> {
                if (!(conn instanceof LettuceConnection lettuce)) {
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
                    log.error("physicalSubscriberCount failed: channel={}, error={}",
                            channel, e.getMessage(), e);
                    return 0L;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.error("physicalSubscriberCount interrupted: channel={}", channel);
                    return 0L;
                }
            });
            return count != null ? count : 0L;
        } catch (Exception e) {
            log.error("physicalSubscriberCount failed: channel={}, error={}", channel, e.getMessage(), e);
            return 0L;
        }
    }

    /**
     * 强制重启 {@link RedisMessageListenerContainer}，让 Spring 重建底层 subscription
     * 长连接，并重新注册 {@code activeListeners} 中所有 listener。
     *
     * <p>用于 pub/sub 长连接 silent failure 自愈：当 {@link #physicalSubscriberCount}
     * 检测到本实例 channel 的 Redis 端订阅数为 0（长连接已断、listener 对象仍在内存
     * map 中）时调用。{@code addMessageListener} 把 listener 挂在 container 当前的
     * subscription connection 上，简单 unsubscribe + subscribe 仍会挂到死连接上，
     * 必须 stop + start 重建底层连接。</p>
     *
     * <p>副作用：影响该 container 下所有订阅（agent:*、user-stream:*、ss:relay:*）
     * 短暂中断（毫秒级）。仅在自检失败路径触发，可接受。</p>
     *
     * @param verifyChannel 重连后用于校验订阅恢复的 channel；通常是调用方自己的
     *                      {@code ss:relay:{instanceId}}
     * @param timeoutMs     等待物理订阅恢复的最大时间（毫秒），超时返回 false
     * @return true 表示重连后 {@code verifyChannel} 在 Redis 端订阅数 >0；
     *         false 表示重启异常或超时未恢复
     */
    public boolean forceReconnectListenerContainer(String verifyChannel, long timeoutMs) {
        int affectedSubscriptions = activeListeners.size();
        log.warn("Force reconnecting RedisMessageListenerContainer: affectedSubscriptions={}, verifyChannel={}",
                affectedSubscriptions, verifyChannel);
        try {
            listenerContainer.stop();
            listenerContainer.start(); // Spring 异步重新注册 cachedListeners
            return waitForSubscriptionRestored(verifyChannel, timeoutMs);
        } catch (Exception e) {
            log.error("forceReconnectListenerContainer failed: verifyChannel={}, error={}",
                    verifyChannel, e.getMessage(), e);
            return false;
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

    // ==================== WS 连接注册表 ====================

    private static final String WS_REGISTRY_PREFIX = "external-ws:registry:";

    public void registerWsConnection(String domain, String instanceId, int connectionCount, int ttlSeconds) {
        try {
            String key = WS_REGISTRY_PREFIX + domain;
            redisTemplate.opsForHash().put(key, instanceId, String.valueOf(connectionCount));
            redisTemplate.expire(key, ttlSeconds, TimeUnit.SECONDS);
            log.debug("Registered WS connection: domain={}, instanceId={}, count={}", domain, instanceId, connectionCount);
        } catch (Exception e) {
            log.error("Failed to register WS connection: domain={}, error={}", domain, e.getMessage());
        }
    }

    public void unregisterWsConnection(String domain, String instanceId) {
        try {
            redisTemplate.opsForHash().delete(WS_REGISTRY_PREFIX + domain, instanceId);
            log.debug("Unregistered WS connection: domain={}, instanceId={}", domain, instanceId);
        } catch (Exception e) {
            log.error("Failed to unregister WS connection: domain={}, error={}", domain, e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, String> getWsRegistry(String domain) {
        try {
            Map<Object, Object> entries = redisTemplate.opsForHash().entries(WS_REGISTRY_PREFIX + domain);
            Map<String, String> result = new java.util.HashMap<>();
            entries.forEach((k, v) -> result.put(k.toString(), v.toString()));
            return result;
        } catch (Exception e) {
            log.error("Failed to get WS registry: domain={}, error={}", domain, e.getMessage());
            return java.util.Collections.emptyMap();
        }
    }

    public void expireWsRegistry(String domain, int ttlSeconds) {
        try {
            redisTemplate.expire(WS_REGISTRY_PREFIX + domain, ttlSeconds, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.error("Failed to expire WS registry: domain={}, error={}", domain, e.getMessage());
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
