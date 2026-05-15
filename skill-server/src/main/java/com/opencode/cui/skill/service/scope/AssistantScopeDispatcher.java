package com.opencode.cui.skill.service.scope;

import com.opencode.cui.skill.model.AssistantInfo;
import com.opencode.cui.skill.model.DefaultAssistantRule;
import com.opencode.cui.skill.service.BusinessWhitelistService;
import com.opencode.cui.skill.service.DefaultAssistantRuleService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 助手作用域调度器。
 * 根据 scope 字符串选择对应的 {@link AssistantScopeStrategy}，
 * 未匹配时默认回退到 personal 策略。
 *
 * <p>PR2 收口：新增三参数 API {@link #getStrategy(String, String, AssistantInfo)}，
 * dispatcher 内部反查 {@link DefaultAssistantRuleService}，命中即返
 * {@link DefaultAssistantScopeStrategy}；未命中委托原有 {@link #getStrategy(AssistantInfo)}。
 * 老 API 保留不动，给 IM / external / sessionRebuild 等不接 domain/type 的 caller 继续使用。</p>
 */
@Slf4j
@Component
public class AssistantScopeDispatcher {

    private static final String DEFAULT_SCOPE = "personal";

    private final Map<String, AssistantScopeStrategy> strategyMap;
    private final BusinessWhitelistService whitelistService;
    private final DefaultAssistantRuleService ruleService;
    private final DefaultAssistantScopeStrategy defaultAssistantScopeStrategy;

    public AssistantScopeDispatcher(List<AssistantScopeStrategy> strategies,
                                    BusinessWhitelistService whitelistService,
                                    DefaultAssistantRuleService ruleService,
                                    DefaultAssistantScopeStrategy defaultAssistantScopeStrategy) {
        this.strategyMap = strategies.stream()
                .collect(Collectors.toMap(AssistantScopeStrategy::getScope, Function.identity()));
        this.whitelistService = whitelistService;
        this.ruleService = ruleService;
        this.defaultAssistantScopeStrategy = defaultAssistantScopeStrategy;
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

    /**
     * 按 (domain, domainType, info) 三元组选 strategy（PR2 收口入口）。
     *
     * <p>判定顺序：
     * <ol>
     *   <li>先反查 {@code default_assistant_rule(domain, domainType)}：命中即返
     *       {@link DefaultAssistantScopeStrategy}（{@code info} 完全忽略，因为 virtual ak
     *       上游不存在，info 通常为 null）</li>
     *   <li>未命中 → 委托老 API {@link #getStrategy(AssistantInfo)}（personal / business / 兜底）</li>
     * </ol>
     * </p>
     *
     * <p>设计：strategy 选择收口到 dispatcher 一处（方案 B）。caller 不应自行
     * {@code findByAk(ak)} 反查再选 strategy；同样地，本方法内部 {@code lookup} 失败也
     * <b>不</b>再递归调本方法（避免循环），直接委托单参数老 API。</p>
     *
     * <p>{@code domain} / {@code domainType} null/blank 时 {@link DefaultAssistantRuleService#lookup}
     * 内部直接返 {@code Optional.empty()}，路径与"未命中"等价。</p>
     */
    public AssistantScopeStrategy getStrategy(String domain, String domainType, AssistantInfo info) {
        Optional<DefaultAssistantRule> rule = ruleService.lookup(domain, domainType);
        if (rule.isPresent()) {
            return defaultAssistantScopeStrategy;
        }
        return getStrategy(info);
    }
}
