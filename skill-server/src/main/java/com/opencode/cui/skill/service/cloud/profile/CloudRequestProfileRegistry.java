package com.opencode.cui.skill.service.cloud.profile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.service.SysConfigService;
import com.opencode.cui.skill.service.cloud.CloudRequestStrategy;
import com.opencode.cui.skill.service.cloud.DefaultCloudRequestStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * SS 端 CloudRequestProfile 注册表。
 *
 * <p>运行时按 SysConfig 拼装套餐：</p>
 * <ol>
 *   <li>{@code cloud_protocol_profile:<businessTag>} → profile name（缺失 → {@code "default"}）</li>
 *   <li>{@code cloud_protocol_profile_def:<profileName>} → JSON 定义；
 *       从中读 {@code request_strategy} 字段</li>
 *   <li>profile_def 缺失时按"profile name == strategy name"约定 fallback</li>
 *   <li>strategy 未注册 → fallback "default" strategy</li>
 * </ol>
 *
 * <p>带 in-memory cache（配置项 {@code skill-server.cloud-protocol-profile.cache-ttl-ms}，
 * 默认 300000ms / 5 分钟）。</p>
 */
@Slf4j
@Service
public class CloudRequestProfileRegistry {

    private static final String CONFIG_TYPE_PROFILE = "cloud_protocol_profile";
    private static final String CONFIG_TYPE_PROFILE_DEF = "cloud_protocol_profile_def";
    static final String DEFAULT_PROFILE = "default";

    private final Map<String, CloudRequestStrategy> strategyMap;
    private final SysConfigService sysConfigService;
    private final ObjectMapper objectMapper;
    private final long cacheTtlMs;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public CloudRequestProfileRegistry(List<CloudRequestStrategy> strategies,
                                       SysConfigService sysConfigService,
                                       ObjectMapper objectMapper,
                                       @Value("${skill-server.cloud-protocol-profile.cache-ttl-ms:300000}") long cacheTtlMs) {
        this.strategyMap = strategies.stream()
                .collect(Collectors.toMap(CloudRequestStrategy::getName, Function.identity()));
        this.sysConfigService = sysConfigService;
        this.objectMapper = objectMapper;
        this.cacheTtlMs = cacheTtlMs;
        log.info("[PROFILE_REG] strategies registered: {}", strategyMap.keySet());
    }

    /**
     * 按 businessTag 解析 profile（profile name + strategy 实例）。
     */
    public CloudRequestProfile resolve(String businessTag) {
        String cacheKey = businessTag == null ? "" : businessTag;
        long now = System.currentTimeMillis();
        CacheEntry cached = cache.get(cacheKey);
        if (cached != null && (now - cached.timestampMs) < cacheTtlMs) {
            return cached.profile;
        }
        CloudRequestProfile profile = loadFromSysConfig(businessTag);
        cache.put(cacheKey, new CacheEntry(profile, now));
        return profile;
    }

    private CloudRequestProfile loadFromSysConfig(String businessTag) {
        // 1. businessTag → profile name
        String profileName = null;
        if (businessTag != null && !businessTag.isBlank()) {
            try {
                profileName = sysConfigService.getValue(CONFIG_TYPE_PROFILE, businessTag);
            } catch (Exception e) {
                log.warn("[PROFILE_REG] read cloud_protocol_profile failed: businessTag={}, error={}",
                        businessTag, e.getMessage());
            }
        }
        if (profileName == null || profileName.isBlank()) {
            profileName = DEFAULT_PROFILE;
        }

        // 2. profile name → strategy name（按 profile_def JSON 或约定 fallback）
        String strategyName = profileName;
        try {
            String defJson = sysConfigService.getValue(CONFIG_TYPE_PROFILE_DEF, profileName);
            if (defJson != null && !defJson.isBlank()) {
                JsonNode node = objectMapper.readTree(defJson);
                strategyName = node.path("request_strategy").asText(profileName);
            }
        } catch (Exception e) {
            log.warn("[PROFILE_REG] read/parse profile_def failed: profile={}, error={}",
                    profileName, e.getMessage());
        }

        // 3. strategy name → strategy bean（缺失 fallback default）
        CloudRequestStrategy strategy = strategyMap.get(strategyName);
        if (strategy == null) {
            log.warn("[PROFILE_REG] strategy not registered: name={}, falling back to default", strategyName);
            strategy = strategyMap.get(DefaultCloudRequestStrategy.STRATEGY_NAME);
        }
        return new CloudRequestProfile(profileName, strategy);
    }

    private record CacheEntry(CloudRequestProfile profile, long timestampMs) {
    }
}
