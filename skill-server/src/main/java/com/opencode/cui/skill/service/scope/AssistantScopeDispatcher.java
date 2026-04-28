package com.opencode.cui.skill.service.scope;

import com.opencode.cui.skill.model.AssistantInfo;
import com.opencode.cui.skill.service.BusinessWhitelistService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 助手作用域调度器。
 * 根据 scope 字符串选择对应的 {@link AssistantScopeStrategy}，
 * 未匹配时默认回退到 personal 策略。
 */
@Slf4j
@Component
public class AssistantScopeDispatcher {

    private static final String DEFAULT_SCOPE = "personal";

    private final Map<String, AssistantScopeStrategy> strategyMap;
    private final BusinessWhitelistService whitelistService;

    public AssistantScopeDispatcher(List<AssistantScopeStrategy> strategies,
                                    BusinessWhitelistService whitelistService) {
        this.strategyMap = strategies.stream()
                .collect(Collectors.toMap(AssistantScopeStrategy::getScope, Function.identity()));
        this.whitelistService = whitelistService;
        log.info("AssistantScopeDispatcher initialized with scopes: {}", strategyMap.keySet());
    }

    /**
     * 根据 scope 字符串纯 lookup 获取策略（不走白名单 gate）。
     * 主要由 dispatcher 内部递归使用；外部调用应优先 {@link #getStrategy(AssistantInfo)}。
     */
    public AssistantScopeStrategy getStrategy(String scope) {
        if (scope == null) {
            return strategyMap.get(DEFAULT_SCOPE);
        }
        return strategyMap.getOrDefault(scope, strategyMap.get(DEFAULT_SCOPE));
    }

    /**
     * 根据 AssistantInfo 获取策略，**含白名单 gate**。
     *
     * <p>判断顺序：
     * <ol>
     *   <li>info == null → personal</li>
     *   <li>scope != "business" → 按 scope 直接 lookup（默认 personal）</li>
     *   <li>scope == "business" → 询问 BusinessWhitelistService.allowsCloud(businessTag)
     *       <ul><li>true → business</li><li>false → personal（白名单未命中降级）</li></ul></li>
     * </ol>
     */
    public AssistantScopeStrategy getStrategy(AssistantInfo info) {
        if (info == null) {
            return strategyMap.get(DEFAULT_SCOPE);
        }
        String scope = info.getAssistantScope();
        if (!"business".equals(scope)) {
            return getStrategy(scope);
        }
        if (whitelistService.allowsCloud(info.getBusinessTag())) {
            return strategyMap.get("business");
        }
        return strategyMap.get(DEFAULT_SCOPE);
    }
}
