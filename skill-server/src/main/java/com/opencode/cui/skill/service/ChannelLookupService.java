package com.opencode.cui.skill.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.opencode.cui.skill.model.AgentSummary;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * 按 ak 解析 plugin 上报的 channel（即 toolType）。
 *
 * <p>数据源：{@link GatewayApiClient#getAgentByAk(String)}（远端在线 Agent 列表）；
 * 本地 Caffeine 缓存（5min, max 500），命中后避免重复打到 Gateway。
 *
 * <p>ABSENT 占位符模式：若 Gateway 返回 null 或 toolType 为空，仍写入占位符，
 * 防止重复打空到上游；与 {@link AssistantIdResolverService#toolTypeCache} 行为一致，
 * 但语义不同 —— 本服务返回的是原始 toolType，不做 targetToolType 比较。
 */
@Slf4j
@Service
public class ChannelLookupService {

    /** 标记"查过但 ak 不存在或 toolType 缺失"的占位符，避免缓存 null。 */
    private static final String ABSENT = "__ABSENT__";

    private final GatewayApiClient gatewayApiClient;

    /** ak → toolType（或 ABSENT 占位）。 */
    private final Cache<String, String> cache = Caffeine.newBuilder()
            .expireAfterAccess(5, TimeUnit.MINUTES)
            .maximumSize(500)
            .build();

    public ChannelLookupService(GatewayApiClient gatewayApiClient) {
        this.gatewayApiClient = gatewayApiClient;
    }

    /**
     * 解析 ak 对应 plugin 注册的 toolType。
     *
     * @param ak Agent 应用密钥
     * @return Optional&lt;String&gt;；ak 为空、上游异常、未在线时返回 {@code Optional.empty()}
     */
    public Optional<String> getToolType(String ak) {
        if (ak == null || ak.isBlank()) {
            return Optional.empty();
        }

        String cached = cache.getIfPresent(ak);
        if (ABSENT.equals(cached)) {
            return Optional.empty();
        }
        if (cached != null) {
            return Optional.of(cached);
        }

        try {
            AgentSummary agent = gatewayApiClient.getAgentByAk(ak);
            if (agent == null || agent.getToolType() == null || agent.getToolType().isBlank()) {
                cache.put(ak, ABSENT);
                return Optional.empty();
            }
            String toolType = agent.getToolType();
            cache.put(ak, toolType);
            return Optional.of(toolType);
        } catch (RuntimeException e) {
            log.warn("[ChannelLookup] getAgentByAk failed: ak={}, error={}", ak, e.getMessage());
            return Optional.empty();
        }
    }
}
