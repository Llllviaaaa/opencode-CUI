package com.opencode.cui.skill.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "skill.delivery")
public class DeliveryProperties {

    /** 投递模式：rest | ws */
    private String mode = "rest";

    /** invoke-source Redis 标记 TTL（秒） */
    private int invokeSourceTtlSeconds = 300;

    /** WS 连接注册表 TTL（秒） */
    private int registryTtlSeconds = 30;

    /** 注册表心跳刷新间隔（毫秒） */
    private long registryHeartbeatIntervalMs = 10000;

    public boolean isWsMode() {
        return "ws".equalsIgnoreCase(mode);
    }
}
