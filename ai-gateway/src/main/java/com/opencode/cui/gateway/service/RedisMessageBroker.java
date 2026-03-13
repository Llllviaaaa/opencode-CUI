package com.opencode.cui.gateway.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.gateway.model.GatewayMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Redis pub/sub message broker for Gateway multi-instance coordination.
 *
 * Channel patterns:
 * - agent:{ak} — route invoke commands to the Gateway instance holding the
 * Agent WS
 * - gw:relay:{instanceId} — cross-instance message relay
 *
 * Key patterns:
 * - gw:skill:owner:{instanceId} — Skill Server heartbeat (KV + TTL)
 * - gw:skill:owners — active Skill Server instance set
 *
 * Session-level routing is NOT handled by Gateway. Skill Server resolves
 * toolSessionId → welinkSessionId via its own DB.
 */
@Slf4j
@Service
public class RedisMessageBroker {

    private static final String AGENT_CHANNEL_PREFIX = "agent:";
    private static final String RELAY_CHANNEL_PREFIX = "gw:relay:";
    private static final String SOURCE_OWNER_KEY_PREFIX = "gw:source:owner:";
    private static final String SOURCE_OWNERS_SET_PREFIX = "gw:source:owners:";
    private static final String AGENT_USER_KEY_PREFIX = "gw:agent:user:";
    private static final String AGENT_SOURCE_KEY_PREFIX = "gw:agent:source:";

    private final StringRedisTemplate redisTemplate;
    private final RedisMessageListenerContainer listenerContainer;
    private final ObjectMapper objectMapper;

    /** Track active subscriptions for cleanup */
    private final Map<String, MessageListener> activeListeners = new ConcurrentHashMap<>();

    public RedisMessageBroker(StringRedisTemplate redisTemplate,
            RedisMessageListenerContainer listenerContainer,
            ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.listenerContainer = listenerContainer;
        this.objectMapper = objectMapper;
    }

    /**
     * Publish a message to an agent channel.
     *
     * @param agentId the target agent ID
     * @param message the message to publish
     */
    public void publishToAgent(String agentId, GatewayMessage message) {
        String channel = AGENT_CHANNEL_PREFIX + agentId;
        publishMessage(channel, message);
    }

    /**
     * Subscribe to an agent channel.
     *
     * @param agentId the agent ID to subscribe to
     * @param handler callback to handle received messages
     */
    public void subscribeToAgent(String agentId, Consumer<GatewayMessage> handler) {
        String channel = AGENT_CHANNEL_PREFIX + agentId;
        subscribe(channel, handler);
    }

    /**
     * Unsubscribe from an agent channel.
     *
     * @param agentId the agent ID to unsubscribe from
     */
    public void unsubscribeFromAgent(String agentId) {
        String channel = AGENT_CHANNEL_PREFIX + agentId;
        unsubscribe(channel);
    }

    public void publishToRelay(String instanceId, GatewayMessage message) {
        publishMessage(relayChannel(instanceId), message);
    }

    public void subscribeToRelay(String instanceId, Consumer<GatewayMessage> handler) {
        subscribe(relayChannel(instanceId), handler);
    }

    public void unsubscribeFromRelay(String instanceId) {
        unsubscribe(relayChannel(instanceId));
    }

    public void refreshSourceOwner(String source, String instanceId, Duration ttl) {
        if (source == null || source.isBlank() || instanceId == null || instanceId.isBlank()) {
            return;
        }
        String ownerKey = sourceOwnerMember(source, instanceId);
        redisTemplate.opsForValue().set(sourceOwnerKey(ownerKey), "alive", ttl);
        redisTemplate.opsForSet().add(sourceOwnersSetKey(source), ownerKey);
    }

    public void removeSourceOwner(String source, String instanceId) {
        if (source == null || source.isBlank() || instanceId == null || instanceId.isBlank()) {
            return;
        }
        String ownerKey = sourceOwnerMember(source, instanceId);
        redisTemplate.delete(sourceOwnerKey(ownerKey));
        redisTemplate.opsForSet().remove(sourceOwnersSetKey(source), ownerKey);
    }

    public boolean hasActiveSourceOwner(String source, String instanceId) {
        if (source == null || source.isBlank() || instanceId == null || instanceId.isBlank()) {
            return false;
        }
        return Boolean.TRUE.equals(redisTemplate.hasKey(sourceOwnerKey(sourceOwnerMember(source, instanceId))));
    }

    public Set<String> getActiveSourceOwners(String source) {
        if (source == null || source.isBlank()) {
            return Set.of();
        }
        Set<String> owners = redisTemplate.opsForSet().members(sourceOwnersSetKey(source));
        if (owners == null || owners.isEmpty()) {
            return Set.of();
        }

        Set<String> activeOwners = new LinkedHashSet<>();
        for (String owner : owners) {
            if (owner == null || owner.isBlank()) {
                continue;
            }
            String ownerSource = sourceFromOwnerKey(owner);
            String ownerInstanceId = instanceIdFromOwnerKey(owner);
            if (!source.equals(ownerSource) || ownerInstanceId == null) {
                redisTemplate.opsForSet().remove(sourceOwnersSetKey(source), owner);
                continue;
            }
            if (hasActiveSourceOwner(ownerSource, ownerInstanceId)) {
                activeOwners.add(owner);
            } else {
                redisTemplate.opsForSet().remove(sourceOwnersSetKey(source), owner);
            }
        }
        return activeOwners;
    }

    public static String sourceOwnerMember(String source, String instanceId) {
        return source + ":" + instanceId;
    }

    public static String sourceFromOwnerKey(String ownerKey) {
        int separatorIndex = ownerKey != null ? ownerKey.indexOf(':') : -1;
        if (separatorIndex <= 0) {
            return null;
        }
        return ownerKey.substring(0, separatorIndex);
    }

    public static String instanceIdFromOwnerKey(String ownerKey) {
        int separatorIndex = ownerKey != null ? ownerKey.indexOf(':') : -1;
        if (separatorIndex <= 0 || separatorIndex == ownerKey.length() - 1) {
            return null;
        }
        return ownerKey.substring(separatorIndex + 1);
    }

    public void bindAgentUser(String ak, String userId) {
        if (ak == null || ak.isBlank() || userId == null || userId.isBlank()) {
            return;
        }
        redisTemplate.opsForValue().set(agentUserKey(ak), userId);
    }

    public String getAgentUser(String ak) {
        if (ak == null || ak.isBlank()) {
            return null;
        }
        return redisTemplate.opsForValue().get(agentUserKey(ak));
    }

    public void removeAgentUser(String ak) {
        if (ak == null || ak.isBlank()) {
            return;
        }
        redisTemplate.delete(agentUserKey(ak));
    }

    public void bindAgentSource(String ak, String source) {
        if (ak == null || ak.isBlank() || source == null || source.isBlank()) {
            return;
        }
        redisTemplate.opsForValue().set(agentSourceKey(ak), source);
    }

    public String getAgentSource(String ak) {
        if (ak == null || ak.isBlank()) {
            return null;
        }
        return redisTemplate.opsForValue().get(agentSourceKey(ak));
    }

    public void removeAgentSource(String ak) {
        if (ak == null || ak.isBlank()) {
            return;
        }
        redisTemplate.delete(agentSourceKey(ak));
    }

    // ========== Internal methods ==========

    private void publishMessage(String channel, GatewayMessage message) {
        try {
            String json = objectMapper.writeValueAsString(message);
            redisTemplate.convertAndSend(channel, json);

            log.debug("Published to Redis channel {}: type={}", channel, message.getType());
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize message for channel {}: {}", channel, e.getMessage(), e);
        } catch (Exception e) {
            log.error("Failed to publish to Redis channel {}: {}", channel, e.getMessage(), e);
        }
    }

    private void subscribe(String channel, Consumer<GatewayMessage> handler) {
        unsubscribe(channel);

        MessageListener listener = (Message message, byte[] pattern) -> {
            try {
                String json = new String(message.getBody());
                GatewayMessage gatewayMessage = objectMapper.readValue(json, GatewayMessage.class);
                handler.accept(gatewayMessage);

                log.debug("Received from Redis channel {}: type={}",
                        channel, gatewayMessage.getType());
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

    private String relayChannel(String instanceId) {
        return RELAY_CHANNEL_PREFIX + instanceId;
    }

    private String sourceOwnerKey(String ownerKey) {
        return SOURCE_OWNER_KEY_PREFIX + ownerKey;
    }

    private String sourceOwnersSetKey(String source) {
        return SOURCE_OWNERS_SET_PREFIX + source;
    }

    private String agentUserKey(String ak) {
        return AGENT_USER_KEY_PREFIX + ak;
    }

    private String agentSourceKey(String ak) {
        return AGENT_SOURCE_KEY_PREFIX + ak;
    }

}
