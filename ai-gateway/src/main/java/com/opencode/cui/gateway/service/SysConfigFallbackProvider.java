package com.opencode.cui.gateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * SysConfig 兜底配置 Provider。
 *
 * <p>当 v2 cloud-route resolver 返回 null（HTTP 非 200 / data 缺失 / 业务 code != 200）或
 * 总开关 OFF 时，由 {@link CallbackConfigService} 调用本 Provider，从 skill-server
 * SysConfig（{@code type=cloud_route_fallback, key=chat|question|permission}）拉取
 * 静态配置作为兜底，构造 {@link CallbackConfig}。</p>
 *
 * <p>SysConfig value 形态：JSON {@code {"channelAddress":..., "channelType":..., "authType":...}}。
 * 与 v2 一致，不返回 appId（保持 null）。</p>
 *
 * <h3>缓存策略</h3>
 * <p>本地 in-memory 缓存按 scope 短名（chat/question/permission）独立维护，
 * TTL = {@link #FALLBACK_CACHE_TTL_MS}（30s），与
 * {@link CallbackConfigService} 的 {@code versionCache} 对齐，避免每次失败都打 skill-server。</p>
 */
@Slf4j
@Component
public class SysConfigFallbackProvider {

    private static final String SS_CONFIG_TYPE = "cloud_route_fallback";

    /** scope 字面量 → SysConfig 短名（与 {@code CloudAgentService.SCOPE_MAP} 反向对齐）。 */
    private static final Map<String, String> SCOPE_TO_SHORT_NAME = Map.of(
            "callback:weagent:chat", "chat",
            "callback:weagent:question_reply", "question",
            "callback:weagent:permission_reply", "permission"
    );

    /** 与 {@code CallbackConfigService.VERSION_CACHE_TTL_MS} 对齐（30s）。 */
    private static final long FALLBACK_CACHE_TTL_MS = 30_000L;

    private final SkillServerConfigClient skillServerConfigClient;
    private final ObjectMapper objectMapper;

    /** 按 scope 短名缓存兜底配置；value 为 (CallbackConfig, fetchedAtMs)。 */
    private final ConcurrentHashMap<String, AtomicReference<CachedFallback>> cacheByShortName =
            new ConcurrentHashMap<>();

    public SysConfigFallbackProvider(SkillServerConfigClient skillServerConfigClient,
                                     ObjectMapper objectMapper) {
        this.skillServerConfigClient = skillServerConfigClient;
        this.objectMapper = objectMapper;
    }

    /**
     * 加载 (ak, scope) 对应的兜底配置。
     *
     * @return 解析成功的 {@link CallbackConfig}；scope 不在白名单 / SysConfig 缺失 / JSON 解析失败一律返回 null
     */
    public CallbackConfig load(String ak, String scope) {
        String shortName = SCOPE_TO_SHORT_NAME.get(scope);
        if (shortName == null) {
            log.warn("[FALLBACK_PROVIDER] scope not in fallback whitelist: ak={}, scope={}", ak, scope);
            return null;
        }

        long now = System.currentTimeMillis();
        AtomicReference<CachedFallback> ref = cacheByShortName.computeIfAbsent(
                shortName, k -> new AtomicReference<>());
        CachedFallback cached = ref.get();
        CallbackConfig template;
        if (cached != null && now - cached.fetchedAtMs < FALLBACK_CACHE_TTL_MS) {
            template = cached.config;
        } else {
            template = fetchFromSysConfig(shortName);
            ref.set(new CachedFallback(template, now));
        }

        if (template == null) {
            return null;
        }
        // 拷贝模板并按本次请求填充 ak / scope（缓存的 template 本身不带 ak/scope，避免跨请求串号）
        CallbackConfig cfg = new CallbackConfig();
        cfg.setAk(ak);
        cfg.setScope(scope);
        cfg.setChannelAddress(template.getChannelAddress());
        cfg.setChannelType(template.getChannelType());
        cfg.setAuthType(template.getAuthType());
        cfg.setAppId(null);
        log.info("[FALLBACK_PROVIDER] loaded fallback: ak={}, scope={}, shortName={}, channelType={}, authType={}",
                ak, scope, shortName, cfg.getChannelType(), cfg.getAuthType());
        return cfg;
    }

    /**
     * 从 skill-server SysConfig 拉取并解析 JSON；任何失败返回 null。
     */
    private CallbackConfig fetchFromSysConfig(String shortName) {
        String json;
        try {
            json = skillServerConfigClient.getConfigValue(SS_CONFIG_TYPE, shortName);
        } catch (Exception e) {
            log.warn("[FALLBACK_PROVIDER] SS config read failed: shortName={}, error={}",
                    shortName, e.getMessage());
            return null;
        }
        if (json == null || json.isBlank()) {
            log.warn("[FALLBACK_PROVIDER] SS config missing: type={}, key={}", SS_CONFIG_TYPE, shortName);
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            String channelAddress = textOrNull(root.path("channelAddress"));
            String channelType = textOrNull(root.path("channelType"));
            String authType = textOrNull(root.path("authType"));
            if (channelAddress == null || channelType == null || authType == null) {
                log.warn("[FALLBACK_PROVIDER] SS config incomplete: shortName={}, channelAddress={}, channelType={}, authType={}",
                        shortName, channelAddress, channelType, authType);
                return null;
            }
            CallbackConfig cfg = new CallbackConfig();
            cfg.setChannelAddress(channelAddress);
            cfg.setChannelType(channelType);
            cfg.setAuthType(authType);
            return cfg;
        } catch (Exception e) {
            log.warn("[FALLBACK_PROVIDER] SS config json parse failed: shortName={}, error={}",
                    shortName, e.getMessage());
            return null;
        }
    }

    private static String textOrNull(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        String s = node.asText(null);
        return (s == null || s.isBlank()) ? null : s;
    }

    /** in-memory 兜底缓存 entry；config 可能为 null（表示上次拉取就缺失）。 */
    private record CachedFallback(CallbackConfig config, long fetchedAtMs) {}
}
