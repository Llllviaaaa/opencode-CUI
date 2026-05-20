package com.opencode.cui.skill.service;

import com.opencode.cui.skill.logging.LogTimer;
import com.opencode.cui.skill.model.AvailabilityResult;
import com.opencode.cui.skill.model.AvailabilitySource;
import com.opencode.cui.skill.model.GatewayAvailabilityResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Slf4j
@Service
@RequiredArgsConstructor
public class AssistantAvailabilityService {

    private static final String AVAILABILITY_PREFIX = "ss:availability:";
    private static final Duration CACHE_TTL = Duration.ofSeconds(30);
    private static final String CONFIG_TYPE = "assistant_offline";
    private static final String DEFAULT_HARDCODED_MESSAGE =
            "任务下发失败，请检查助理是否离线，确保助理在线后重试";

    private final GatewayApiClient gatewayApiClient;
    private final SysConfigService sysConfigService;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public AvailabilityResult resolve(String ak) {
        if (ak == null || ak.isBlank()) {
            log.error("[BUG] availabilityService.resolve called with blank ak, fallback to FALLBACK_ERROR");
            return AvailabilityResult.ofFallbackError(DEFAULT_HARDCODED_MESSAGE);
        }

        // 1. Try Redis cache (best-effort)
        String cacheKey = availabilityKey(ak);
        try {
            String cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached != null) {
                AvailabilityResult parsed = deserialize(cached);
                if (parsed != null) {
                    return parsed;
                }
                try { redisTemplate.delete(cacheKey); } catch (RuntimeException ignored) {}
            }
        } catch (RuntimeException e) {
            log.warn("Redis read failed for availability cache key={}, falling through: {}",
                    cacheKey, e.getMessage());
        }

        // 2. Query gateway
        AvailabilityResult result = queryAndCompute(ak);

        // 3. Write cache (best-effort)
        String serialized = serialize(result);
        if (serialized != null) {
            try {
                redisTemplate.opsForValue().set(cacheKey, serialized, CACHE_TTL);
            } catch (RuntimeException e) {
                log.warn("Redis write failed for availability cache key={}: {}", cacheKey, e.getMessage());
            }
        }

        return result;
    }

    public void evict(String ak) {
        if (ak == null || ak.isBlank()) return;
        String cacheKey = availabilityKey(ak);
        try {
            redisTemplate.delete(cacheKey);
            log.debug("Availability cache evicted: ak={}", ak);
        } catch (RuntimeException e) {
            log.warn("Redis evict failed for availability cache key={}: {}", cacheKey, e.getMessage());
        }
    }

    // ------------------------------------------------------------------ private

    private AvailabilityResult queryAndCompute(String ak) {
        GatewayAvailabilityResponse gw;
        try {
            gw = LogTimer.timed(log, "GatewayAPI.getAvailability(ak=" + ak + ")",
                    () -> gatewayApiClient.getAvailability(ak));
        } catch (Exception e) {
            log.error("GatewayAPI.getAvailability exception for ak={}: {}", ak, e.getMessage());
            return AvailabilityResult.ofFallbackError(DEFAULT_HARDCODED_MESSAGE);
        }

        // Gateway 5xx / timeout / parse failure → FALLBACK_ERROR
        if (gw == null) {
            log.warn("GatewayAPI.getAvailability returned null for ak={}, fallback to FALLBACK_ERROR", ak);
            return AvailabilityResult.ofFallbackError(DEFAULT_HARDCODED_MESSAGE);
        }

        boolean exists = gw.exists();
        boolean online = gw.online();
        String toolType = normalizeToolType(gw.latestToolType());

        log.info("[Availability] ak={}, exists={}, online={}, toolType={}", ak, exists, online, toolType);

        // exists=false → NOT_CONFIGURED
        if (!exists) {
            return AvailabilityResult.ofNotConfigured(lookupMessage("not_configured"));
        }

        // exists=true, online=true → ONLINE
        if (online) {
            return new AvailabilityResult(true, null, toolType, AvailabilitySource.ONLINE);
        }

        // exists=true, online=false
        if (toolType != null) {
            String typedKey = "tool_type:" + toolType;
            String typedMsg = sysConfigService.getValue(CONFIG_TYPE, typedKey);
            if (typedMsg != null && !typedMsg.isBlank()) {
                return AvailabilityResult.ofOfflineTyped(typedMsg, toolType);
            }
            // tool_type:<x> blank/miss → fallback to message
        }

        // Default offline message
        String fallbackMsg = lookupMessage("message");
        return AvailabilityResult.ofOfflineDefault(fallbackMsg, toolType);
    }

    private String lookupMessage(String configKey) {
        String msg = sysConfigService.getValue(CONFIG_TYPE, configKey);
        if (msg != null && !msg.isBlank()) {
            return msg;
        }
        if (!"message".equals(configKey)) {
            // not_configured blank → fallback to message
            String messageConfig = sysConfigService.getValue(CONFIG_TYPE, "message");
            if (messageConfig != null && !messageConfig.isBlank()) {
                return messageConfig;
            }
        }
        return DEFAULT_HARDCODED_MESSAGE;
    }

    private String normalizeToolType(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim().toLowerCase();
        return trimmed.isBlank() ? null : trimmed;
    }

    private String availabilityKey(String ak) {
        return AVAILABILITY_PREFIX + ak;
    }

    private String serialize(AvailabilityResult r) {
        try {
            return objectMapper.writeValueAsString(r);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize AvailabilityResult for ak cache", e);
            return null;
        }
    }

    private AvailabilityResult deserialize(String cached) {
        try {
            return objectMapper.readValue(cached, AvailabilityResult.class);
        } catch (Exception e) {
            log.warn("Failed to deserialize AvailabilityResult from cache: {}", e.getMessage());
            return null;
        }
    }
}
