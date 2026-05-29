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
 * Runtime registry for cloud request protocol profiles.
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
     * Resolve profile by business tag through SysConfig mapping.
     */
    public CloudRequestProfile resolve(String businessTag) {
        String cacheKey = "business:" + (businessTag == null ? "" : businessTag);
        long now = System.currentTimeMillis();
        CacheEntry cached = cache.get(cacheKey);
        if (cached != null && (now - cached.timestampMs) < cacheTtlMs) {
            return cached.profile;
        }
        CloudRequestProfile profile = loadFromSysConfig(businessTag);
        cache.put(cacheKey, new CacheEntry(profile, now));
        return profile;
    }

    /**
     * Resolve an already-known protocol profile name without SysConfig mapping.
     */
    public CloudRequestProfile resolveProfile(String profileName) {
        String name = (profileName == null || profileName.isBlank()) ? DEFAULT_PROFILE : profileName;
        String cacheKey = "profile:" + name;
        long now = System.currentTimeMillis();
        CacheEntry cached = cache.get(cacheKey);
        if (cached != null && (now - cached.timestampMs) < cacheTtlMs) {
            return cached.profile;
        }
        CloudRequestProfile profile = loadDirectProfile(name);
        cache.put(cacheKey, new CacheEntry(profile, now));
        return profile;
    }

    private CloudRequestProfile loadFromSysConfig(String businessTag) {
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

        return loadProfile(profileName);
    }

    private CloudRequestProfile loadDirectProfile(String profileName) {
        CloudRequestStrategy strategy = strategyMap.get(profileName);
        if (strategy == null) {
            log.warn("[PROFILE_REG] direct profile strategy not registered: name={}, falling back to default",
                    profileName);
            strategy = strategyMap.get(DefaultCloudRequestStrategy.STRATEGY_NAME);
        }
        return new CloudRequestProfile(profileName, strategy);
    }

    private CloudRequestProfile loadProfile(String profileName) {
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
