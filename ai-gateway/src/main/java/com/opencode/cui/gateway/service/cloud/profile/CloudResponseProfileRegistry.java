package com.opencode.cui.gateway.service.cloud.profile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.gateway.service.SkillServerConfigClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * GW 端 CloudResponseProfile 注册表。
 *
 * <p>运行时按 SS SysConfig 拼装套餐：</p>
 * <ol>
 *   <li>查 {@code cloud_protocol_profile_def:<profileName>} 拿 JSON 定义</li>
 *   <li>从 JSON 读 {@code response_decoder} 字段</li>
 *   <li>profile_def 缺失时按"profile name == decoder name"约定 fallback</li>
 * </ol>
 *
 * <p>带 5 分钟 in-memory cache，配置项 {@code gateway.cloud-protocol-profile.cache-ttl-ms}。</p>
 */
@Slf4j
@Service
public class CloudResponseProfileRegistry {

    private static final String CONFIG_TYPE_PROFILE_DEF = "cloud_protocol_profile_def";
    private static final String DEFAULT_PROFILE = "default";

    private final SkillServerConfigClient skillServerConfigClient;
    private final ObjectMapper objectMapper;
    private final long cacheTtlMs;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public CloudResponseProfileRegistry(SkillServerConfigClient skillServerConfigClient,
                                        ObjectMapper objectMapper,
                                        @Value("${gateway.cloud-protocol-profile.cache-ttl-ms:300000}") long cacheTtlMs) {
        this.skillServerConfigClient = skillServerConfigClient;
        this.objectMapper = objectMapper;
        this.cacheTtlMs = cacheTtlMs;
    }

    /**
     * 按 profile 名解析为 {@link CloudResponseProfile}。
     *
     * @param profileName 来自 invoke payload 的 {@code cloudProfile} 字段；null/blank → "default"
     */
    public CloudResponseProfile resolve(String profileName) {
        String name = (profileName == null || profileName.isBlank()) ? DEFAULT_PROFILE : profileName;
        long now = System.currentTimeMillis();
        CacheEntry cached = cache.get(name);
        if (cached != null && (now - cached.timestampMs) < cacheTtlMs) {
            return cached.profile;
        }
        CloudResponseProfile profile = loadFromSysConfig(name);
        cache.put(name, new CacheEntry(profile, now));
        return profile;
    }

    private CloudResponseProfile loadFromSysConfig(String profileName) {
        String defJson = null;
        try {
            defJson = skillServerConfigClient.getConfigValue(CONFIG_TYPE_PROFILE_DEF, profileName);
        } catch (Exception e) {
            log.warn("[PROFILE_REG] fetch profile_def failed: name={}, error={}", profileName, e.getMessage());
        }
        String decoderName;
        if (defJson != null && !defJson.isBlank()) {
            try {
                JsonNode node = objectMapper.readTree(defJson);
                decoderName = node.path("response_decoder").asText(profileName);
            } catch (Exception e) {
                log.warn("[PROFILE_REG] parse profile_def failed: name={}, json={}, error={}",
                        profileName, defJson, e.getMessage());
                decoderName = profileName;
            }
        } else {
            // 约定 fallback：profile name == decoder name
            decoderName = profileName;
        }
        return new CloudResponseProfile(profileName, decoderName);
    }

    private record CacheEntry(CloudResponseProfile profile, long timestampMs) {
    }
}
