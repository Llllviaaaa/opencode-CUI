package com.opencode.cui.skill.service;

import com.opencode.cui.skill.config.DeliveryProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * WS 连接注册表服务。
 * 管理 External WS 连接在 Redis 中的全局注册信息，
 * 用于跨 SS 实例精确投递。
 */
@Slf4j
@Service
public class ExternalWsRegistry {

    private final RedisMessageBroker redisMessageBroker;
    private final SkillInstanceRegistry instanceRegistry;
    private final DeliveryProperties deliveryProperties;

    public ExternalWsRegistry(RedisMessageBroker redisMessageBroker,
                               SkillInstanceRegistry instanceRegistry,
                               DeliveryProperties deliveryProperties) {
        this.redisMessageBroker = redisMessageBroker;
        this.instanceRegistry = instanceRegistry;
        this.deliveryProperties = deliveryProperties;
    }

    /** 注册本实例持有的 WS 连接数。 */
    public void register(String domain, int connectionCount) {
        redisMessageBroker.registerWsConnection(
                domain, instanceRegistry.getInstanceId(),
                connectionCount, deliveryProperties.getRegistryTtlSeconds());
    }

    /** 注销本实例在指定 domain 的 WS 连接。 */
    public void unregister(String domain) {
        redisMessageBroker.unregisterWsConnection(domain, instanceRegistry.getInstanceId());
    }

    /** 续期本实例注册的指定 domain 的 TTL。 */
    public void heartbeat(String domain) {
        redisMessageBroker.expireWsRegistry(domain, deliveryProperties.getRegistryTtlSeconds());
    }

    /**
     * 查找一台持有指定 domain WS 连接的远程 SS 实例。
     * 跳过本实例，返回第一个连接数 > 0 的远程实例 ID。
     * 无可用实例返回 null。
     */
    public String findInstanceWithConnection(String domain) {
        Map<String, String> registry = redisMessageBroker.getWsRegistry(domain);
        String selfId = instanceRegistry.getInstanceId();
        for (Map.Entry<String, String> entry : registry.entrySet()) {
            if (!entry.getKey().equals(selfId)) {
                int count = 0;
                try { count = Integer.parseInt(entry.getValue()); } catch (NumberFormatException ignored) {}
                if (count > 0) {
                    return entry.getKey();
                }
            }
        }
        return null;
    }
}
