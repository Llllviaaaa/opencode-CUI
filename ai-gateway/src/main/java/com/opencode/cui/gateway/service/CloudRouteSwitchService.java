package com.opencode.cui.gateway.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Reads cloud-route feature switches from skill-server SysConfig.
 */
@Slf4j
@Service
public class CloudRouteSwitchService {

    static final String CONFIG_TYPE = "cloud_route";
    static final String REMOTE_PROPERTY_ENABLED_KEY = "remote_property_enabled";

    private final SkillServerConfigClient skillServerConfigClient;
    private final long cacheTtlMs;
    private final AtomicReference<CachedSwitch> remotePropertyEnabledCache = new AtomicReference<>();

    public CloudRouteSwitchService(SkillServerConfigClient skillServerConfigClient,
                                   @Value("${gateway.cloud-route.sysconfig-cache-ttl-ms:300000}") long cacheTtlMs) {
        this.skillServerConfigClient = skillServerConfigClient;
        this.cacheTtlMs = cacheTtlMs;
    }

    /**
     * Defaults to enabled when SysConfig is absent or unreadable, preserving remote-first behavior.
     */
    public boolean remotePropertyEnabled() {
        long now = System.currentTimeMillis();
        CachedSwitch cached = remotePropertyEnabledCache.get();
        if (cached != null && now - cached.fetchedAtMs < cacheTtlMs) {
            return cached.enabled;
        }

        String value = skillServerConfigClient.getConfigValue(CONFIG_TYPE, REMOTE_PROPERTY_ENABLED_KEY);
        boolean enabled = parseEnabled(value);
        remotePropertyEnabledCache.set(new CachedSwitch(enabled, now));
        log.debug("[CLOUD_ROUTE_SWITCH] refreshed remotePropertyEnabled={} (configValue={})", enabled, value);
        return enabled;
    }

    private boolean parseEnabled(String value) {
        if (value == null || value.isBlank()) {
            return true;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return !("0".equals(normalized)
                || "false".equals(normalized)
                || "off".equals(normalized)
                || "no".equals(normalized));
    }

    private record CachedSwitch(boolean enabled, long fetchedAtMs) {}
}
