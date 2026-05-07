package com.opencode.cui.skill.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.config.SysConfigProperties;
import com.opencode.cui.skill.model.SysConfig;
import com.opencode.cui.skill.repository.SysConfigMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Channel 抑制回复白名单服务（个人助手禁群聊场景）。
 *
 * <p>命中白名单 = 该 channel 的 plugin 在群聊场景下应跳过 opencode 回复。
 *
 * <p>判断顺序：
 * <ol>
 *   <li>总开关关闭 → 直接 false（不触碰白名单数据）</li>
 *   <li>toolType null/blank → false（无法判定时保持现状不拦截）</li>
 *   <li>查白名单集合（Redis 集合缓存 5min TTL，miss 穿透 DB）</li>
 *   <li>异常路径一律 fail-safe 返回 false（不拦截，保持原有行为）</li>
 * </ol>
 *
 * <p>注意：与 {@link BusinessWhitelistService} 的 fail-open 语义相反 —— 本服务的功能是"拦截"，
 * 任何不确定状态都应放行，避免错误抑制 plugin 回复。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChannelSuppressReplyWhitelistService {

    private static final String CONFIG_TYPE_WHITELIST = "suppress_reply_channel_whitelist";
    private static final String CONFIG_TYPE_SWITCH = "channel_suppress_reply";
    private static final String CONFIG_KEY_SWITCH = "channel_suppress_reply_enabled";
    private static final String CACHE_KEY_SET = "ss:config:set:suppress_reply_channel_whitelist";

    private final SysConfigService sysConfigService;
    private final SysConfigMapper sysConfigMapper;
    private final StringRedisTemplate redisTemplate;
    private final SysConfigProperties properties;
    private final ObjectMapper objectMapper;

    /**
     * 是否应抑制该 channel（toolType）的 plugin opencode 回复。
     *
     * @param toolType plugin 在 register 上报的 channel 标识（即 AgentConnection.toolType）
     * @return true = 命中白名单，需抑制；false = 不命中或异常时不抑制
     */
    public boolean shouldSuppress(String toolType) {
        if (!isFeatureEnabled()) {
            log.debug("[SuppressWhitelist] disabled, no suppression");
            return false;
        }
        if (toolType == null || toolType.isBlank()) {
            log.debug("[SuppressWhitelist] toolType missing, no suppression");
            return false;
        }
        Set<String> channels;
        try {
            channels = loadWhitelistChannels();
        } catch (RuntimeException e) {
            log.warn("[SuppressWhitelist] load failed, fail-safe (no suppression): error={}", e.getMessage());
            return false;
        }
        if (channels.isEmpty()) {
            log.debug("[SuppressWhitelist] empty whitelist, no suppression");
            return false;
        }
        boolean hit = channels.contains(toolType);
        if (hit) {
            log.info("[SuppressWhitelist] toolType {} hit, suppressing plugin reply", toolType);
        } else {
            log.debug("[SuppressWhitelist] toolType {} not in whitelist", toolType);
        }
        return hit;
    }

    private boolean isFeatureEnabled() {
        try {
            String v = sysConfigService.getValue(CONFIG_TYPE_SWITCH, CONFIG_KEY_SWITCH);
            if ("1".equals(v)) return true;
            if (v == null) {
                log.debug("[SuppressWhitelist] switch value is null (key not configured), treating as disabled");
                return false;
            }
            if ("0".equals(v)) return false;
            log.warn("[SuppressWhitelist] unknown switch value '{}', treating as disabled", v);
            return false;
        } catch (RuntimeException e) {
            log.warn("[SuppressWhitelist] switch read failed, treat as disabled: error={}", e.getMessage());
            return false;
        }
    }

    private Set<String> loadWhitelistChannels() {
        // 1. 集合缓存
        try {
            String cached = redisTemplate.opsForValue().get(CACHE_KEY_SET);
            if (cached != null) {
                List<String> list = objectMapper.readValue(cached, new TypeReference<List<String>>() {});
                return new HashSet<>(list);
            }
        } catch (Exception e) {
            log.warn("[SuppressWhitelist] cache read failed, fallback to DB: error={}", e.getMessage());
        }
        // 2. DB
        List<SysConfig> rows = sysConfigMapper.findByType(CONFIG_TYPE_WHITELIST);
        Set<String> channels = rows.stream()
                .filter(c -> c.getStatus() != null && c.getStatus() == 1)
                .map(SysConfig::getConfigKey)
                .collect(Collectors.toSet());
        // 3. 写缓存（失败静默；写前排序保证不同实例间 JSON 字节一致）
        try {
            List<String> sorted = channels.stream().sorted().toList();
            String json = objectMapper.writeValueAsString(sorted);
            redisTemplate.opsForValue().set(CACHE_KEY_SET, json,
                    Duration.ofMinutes(properties.getCacheTtlMinutes()));
        } catch (Exception e) {
            log.warn("[SuppressWhitelist] cache write failed: error={}", e.getMessage());
        }
        return channels;
    }
}
