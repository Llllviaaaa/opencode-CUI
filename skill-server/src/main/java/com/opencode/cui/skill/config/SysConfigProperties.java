package com.opencode.cui.skill.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * SysConfig 缓存配置。
 * 用于 SysConfigService 单条缓存与 BusinessWhitelistService 集合缓存。
 */
@Data
@Component
@ConfigurationProperties(prefix = "skill.sys-config")
public class SysConfigProperties {
    /** 缓存 TTL（分钟），默认 5 */
    private long cacheTtlMinutes = 5L;
}
