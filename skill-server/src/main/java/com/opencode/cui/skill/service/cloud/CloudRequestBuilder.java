package com.opencode.cui.skill.service.cloud;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.opencode.cui.skill.service.SysConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 云端请求调度器（已废弃）。
 *
 * <p>原职责：根据 appId 查询 SysConfig {@code cloud_request_strategy} 获取策略名，
 * 选择对应的 {@link CloudRequestStrategy} 构建请求体。</p>
 *
 * <p><b>已由 {@code CloudRequestProfileRegistry} 替代</b>（基于 {@code cloud_protocol_profile}
 * + {@code cloud_protocol_profile_def} 两段 SysConfig 拼装套餐）。本类仅作为回滚兜底保留，
 * 新逻辑不再读取旧 {@code cloud_request_strategy} 配置。</p>
 *
 * @deprecated 使用 {@code com.opencode.cui.skill.service.cloud.profile.CloudRequestProfileRegistry} 代替。
 */
@Deprecated
@Slf4j
@Service
public class CloudRequestBuilder {

    static final String CONFIG_TYPE = "cloud_request_strategy";

    private final Map<String, CloudRequestStrategy> strategyMap;
    private final SysConfigService sysConfigService;

    public CloudRequestBuilder(List<CloudRequestStrategy> strategies, SysConfigService sysConfigService) {
        this.strategyMap = strategies.stream()
                .collect(Collectors.toMap(CloudRequestStrategy::getName, Function.identity()));
        this.sysConfigService = sysConfigService;
        log.info("CloudRequestBuilder initialized with strategies: {}", strategyMap.keySet());
    }

    /**
     * 根据 appId 选择策略，构建云端请求体。
     *
     * @param appId   应用 ID，用于查询 SysConfig 中配置的策略名
     * @param context 请求上下文
     * @return 请求体 JSON 节点
     */
    public ObjectNode buildCloudRequest(String appId, CloudRequestContext context) {
        String strategyName = sysConfigService.getValue(CONFIG_TYPE, appId);
        CloudRequestStrategy strategy = resolveStrategy(strategyName, appId);
        log.debug("Building cloud request for appId={} using strategy={}", appId, strategy.getName());
        return strategy.build(context);
    }

    // ------------------------------------------------------------------ private

    private CloudRequestStrategy resolveStrategy(String strategyName, String appId) {
        if (strategyName == null) {
            log.debug("No strategy configured for appId={}, using default", appId);
            return strategyMap.get(DefaultCloudRequestStrategy.STRATEGY_NAME);
        }

        CloudRequestStrategy strategy = strategyMap.get(strategyName);
        if (strategy == null) {
            log.warn("Strategy '{}' not found for appId={}, falling back to default", strategyName, appId);
            return strategyMap.get(DefaultCloudRequestStrategy.STRATEGY_NAME);
        }

        return strategy;
    }
}
