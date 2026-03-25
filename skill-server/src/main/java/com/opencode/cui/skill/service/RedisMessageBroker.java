package com.opencode.cui.skill.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
     * Uses Redis PUBLISH which returns the number of subscribers that received the message.
     *
     * @param targetInstanceId the target SS instance ID
     * @param message          the message to relay (JSON string)
     * @return number of subscribers that received the message; 0 means nobody is listening
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

    // ==================== Gateway 实例发现（v3 新增） ====================

    /**
     * 扫描 Redis 中所有 Gateway 实例注册 key。
     *
     * @return key → value 映射（key 格式: gw:instance:{id}，value: JSON）
     */
    private static final String GW_INSTANCE_KEY_PREFIX = "gw:instance:";

    public Map<String, String> scanGatewayInstances() {
        Map<String, String> result = new HashMap<>();
        ScanOptions options = ScanOptions.scanOptions()
                .match(GW_INSTANCE_KEY_PREFIX + "*")
                .count(100)
                .build();
        try (Cursor<String> cursor = redisTemplate.scan(options)) {
            while (cursor.hasNext()) {
                String key = cursor.next();
                String value = redisTemplate.opsForValue().get(key);
                if (value != null) {
                    String instanceId = key.substring(GW_INSTANCE_KEY_PREFIX.length());
                    result.put(instanceId, value);
                }
            }
        }
        return result;
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

    // ==================== 用户 WS 连接注册表（Task 2.7） ====================

    private static final String USER_WS_KEY_PREFIX = "ss:internal:user-ws:";

    /**
     * 注册用户 WS 连接：对指定实例的计数 HINCRBY +1。
     *
     * @param userId     用户 ID
     * @param instanceId SS 实例 ID
     */
    public void registerUserWs(String userId, String instanceId) {
        String key = USER_WS_KEY_PREFIX + userId;
        Long newCount = redisTemplate.opsForHash().increment(key, instanceId, 1L);
        log.info("[ENTRY] registerUserWs: userId={}, instanceId={}, count={}", userId, instanceId, newCount);
    }

    /**
     * 注销用户 WS 连接：对指定实例的计数 HINCRBY -1，结果 ≤ 0 则 HDEL 该 field。
     *
     * @param userId     用户 ID
     * @param instanceId SS 实例 ID
     */
    public void unregisterUserWs(String userId, String instanceId) {
        String key = USER_WS_KEY_PREFIX + userId;
        Long remaining = redisTemplate.opsForHash().increment(key, instanceId, -1L);
        log.info("[EXIT] unregisterUserWs: userId={}, instanceId={}, remaining={}", userId, instanceId, remaining);
        if (remaining != null && remaining <= 0) {
            redisTemplate.opsForHash().delete(key, instanceId);
            log.info("[EXIT] unregisterUserWs: cleaned up field, userId={}, instanceId={}", userId, instanceId);
        }
    }

    /**
     * 查询该用户有 WS 连接的所有 SS 实例 ID 集合。
     *
     * @param userId 用户 ID
     * @return 有活跃连接的实例 ID 集合（无连接时返回空集合）
     */
    public Set<String> getUserWsInstances(String userId) {
        String key = USER_WS_KEY_PREFIX + userId;
        Set<Object> rawKeys = redisTemplate.opsForHash().keys(key);
        Set<String> result = new HashSet<>();
        for (Object k : rawKeys) {
            result.add(String.valueOf(k));
        }
        log.info("getUserWsInstances: userId={}, instances={}", userId, result);
        return result;
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

    /**
     * SS 实例启动时清理宕机残留：扫描所有 {@code ss:internal:user-ws:*} Hash，
     * 删除其中属于本实例的 field，防止历史脏数据影响路由判断。
     *
     * @param instanceId 本 SS 实例 ID
     */
    public void cleanupUserWsForInstance(String instanceId) {
        log.info("[ENTRY] cleanupUserWsForInstance: instanceId={}", instanceId);
        String pattern = USER_WS_KEY_PREFIX + "*";
        ScanOptions options = ScanOptions.scanOptions().match(pattern).count(100).build();
        int cleaned = 0;
        try (Cursor<String> cursor = redisTemplate.scan(options)) {
            while (cursor.hasNext()) {
                String key = cursor.next();
                Object value = redisTemplate.opsForHash().get(key, instanceId);
                if (value != null) {
                    redisTemplate.opsForHash().delete(key, instanceId);
                    cleaned++;
                    log.info("cleanupUserWsForInstance: removed stale entry, key={}, instanceId={}", key, instanceId);
                }
            }
        }
        log.info("[EXIT] cleanupUserWsForInstance: instanceId={}, cleaned={}", instanceId, cleaned);
    }
}
