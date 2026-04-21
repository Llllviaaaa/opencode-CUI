package com.opencode.cui.skill.service.scope;

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

    public AssistantScopeDispatcher(List<AssistantScopeStrategy> strategies) {
        this.strategyMap = strategies.stream()
                .collect(Collectors.toMap(AssistantScopeStrategy::getScope, Function.identity()));
        log.info("AssistantScopeDispatcher initialized with scopes: {}", strategyMap.keySet());
    }

    /**
     * 根据 scope 获取对应的策略。
     * 若 scope 为 null 或无匹配策略，回退到 personal。
     *
     * @param scope 作用域标识（"business" / "personal"）
     * @return 对应的策略实例，永不返回 null
     */
    public AssistantScopeStrategy getStrategy(String scope) {
        if (scope == null) {
            return strategyMap.get(DEFAULT_SCOPE);
        }
        return strategyMap.getOrDefault(scope, strategyMap.get(DEFAULT_SCOPE));
    }
}
