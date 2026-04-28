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
 * 业务助手云端协议白名单服务。
 *
 * <p>判断顺序（关键）：
 * <ol>
 *   <li>总开关 isFeatureEnabled() —— 关闭时不触碰 tag，永远返回 true</li>
 *   <li>tag null/blank → 返回 false + WARN（仅在白名单启用时触发此分支）</li>
 *   <li>查白名单集合（集合缓存 5min TTL，miss 时穿透 DB）</li>
 *   <li>异常路径一律 fail-open 返回 true + WARN</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BusinessWhitelistService {

    private static final String CONFIG_TYPE_WHITELIST = "business_cloud_whitelist";
    private static final String CONFIG_TYPE_SWITCH = "cloud_route";
    private static final String CONFIG_KEY_SWITCH = "business_whitelist_enabled";
    private static final String CACHE_KEY_SET = "ss:config:set:business_cloud_whitelist";

    private final SysConfigService sysConfigService;
    private final SysConfigMapper sysConfigMapper;
    private final StringRedisTemplate redisTemplate;
    private final SysConfigProperties properties;
    private final ObjectMapper objectMapper;

    /**
     * 是否允许该业务 tag 走云端协议。
     *
     * @param businessTag 业务标签（来自 AssistantInfo.businessTag）
     * @return true = 走云端；false = 走本地
     */
    public boolean allowsCloud(String businessTag) {
        if (!isFeatureEnabled()) {
            log.debug("[Whitelist] disabled, all business allowed");
            return true;
        }
        if (businessTag == null || businessTag.isBlank()) {
            log.warn("[Whitelist] business scope but businessTag missing");
            return false;
        }
        Set<String> tags;
        try {
            tags = loadWhitelistTags();
        } catch (RuntimeException e) {
            log.warn("[Whitelist] load failed, fail-open: error={}", e.getMessage());
            return true;
        }
        if (tags.isEmpty()) {
            log.info("[Whitelist] empty, allowing all (fail-open)");
            return true;
        }
        boolean allowed = tags.contains(businessTag);
        if (allowed) {
            log.debug("[Whitelist] tag {} hit", businessTag);
        } else {
            log.info("[Whitelist] businessTag {} not in whitelist, fallback to local", businessTag);
        }
        return allowed;
    }

    private boolean isFeatureEnabled() {
        try {
            String v = sysConfigService.getValue(CONFIG_TYPE_SWITCH, CONFIG_KEY_SWITCH);
            if ("1".equals(v)) return true;
            if (v == null) {
                log.debug("[Whitelist] switch value is null (key not configured), treating as disabled");
                return false;
            }
            if ("0".equals(v)) return false;
            log.warn("[Whitelist] unknown switch value '{}', treating as disabled", v);
            return false;
        } catch (RuntimeException e) {
            log.warn("[Whitelist] switch read failed, fail-open (treat as disabled): error={}",
                    e.getMessage());
            return false;
        }
    }

    private Set<String> loadWhitelistTags() {
        // 1. 集合缓存
        try {
            String cached = redisTemplate.opsForValue().get(CACHE_KEY_SET);
            if (cached != null) {
                List<String> list = objectMapper.readValue(cached, new TypeReference<List<String>>() {});
                return new HashSet<>(list);
            }
        } catch (Exception e) {
            log.warn("[Whitelist] cache read failed, fallback to DB: error={}", e.getMessage());
        }
        // 2. DB
        List<SysConfig> rows = sysConfigMapper.findByType(CONFIG_TYPE_WHITELIST);
        Set<String> tags = rows.stream()
                .filter(c -> c.getStatus() != null && c.getStatus() == 1)
                .map(SysConfig::getConfigKey)
                .collect(Collectors.toSet());
        // 3. 写缓存（失败静默；写前排序保证不同实例间 JSON 字节一致，便于 diff/log 比较）
        try {
            List<String> sortedTags = tags.stream().sorted().toList();
            String json = objectMapper.writeValueAsString(sortedTags);
            redisTemplate.opsForValue().set(CACHE_KEY_SET, json,
                    Duration.ofMinutes(properties.getCacheTtlMinutes()));
        } catch (Exception e) {
            log.warn("[Whitelist] cache write failed: error={}", e.getMessage());
        }
        return tags;
    }
}
