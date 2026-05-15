package com.opencode.cui.skill.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.skill.model.DefaultAssistantRule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 默认助手规则服务（薄壳）。
 *
 * <p>仅暴露一个 lookup 方法，按 {@code (domain, domainType)} 复合 key 反查 sys_config
 * {@code config_type=default_assistant_rule}，命中即解析 JSON 返
 * {@link DefaultAssistantRule}。</p>
 *
 * <p><b>缓存机制</b>：复用 {@link SysConfigService} 内部的 Redis 5 分钟 TTL 缓存
 * （由 {@code SysConfigProperties.cacheTtlMinutes} 配置）。{@code SysConfigService.update}
 * 自动 evict 缓存，运维改规则下次 lookup 自动拿新值。</p>
 *
 * <p><b>不做的事</b>：
 * <ul>
 *   <li>不自管内存 snapshot</li>
 *   <li>不周期 reload</li>
 *   <li>不启动加载 / 启动校验</li>
 *   <li>不提供 findByAk 反查（PR2 收口后，strategy 内部用 (domain, type) lookup）</li>
 * </ul>
 * </p>
 *
 * <p><b>运行时容错</b>：JSON 解析失败 / 字段缺失 → log warn + 返 empty
 * （等同于"未命中"，上层 caller 返 400）。</p>
 */
@Slf4j
@Service
public class DefaultAssistantRuleService {

    private static final String CONFIG_TYPE = "default_assistant_rule";

    private final SysConfigService sysConfigService;
    private final ObjectMapper objectMapper;

    public DefaultAssistantRuleService(SysConfigService sysConfigService, ObjectMapper objectMapper) {
        this.sysConfigService = sysConfigService;
        this.objectMapper = objectMapper;
    }

    /**
     * 按 (domain, domainType) 反查规则。
     *
     * <p>语义：</p>
     * <ul>
     *   <li>null/blank 入参（任一为空） → {@code Optional.empty()}，<b>不</b>调 SysConfigService.getValue</li>
     *   <li>sys_config 不存在 → {@code Optional.empty()}</li>
     *   <li>sys_config value 是非法 JSON / 字段缺失 → log WARN + {@code Optional.empty()}</li>
     *   <li>命中合法规则 → {@code Optional.of(rule)}</li>
     * </ul>
     */
    public Optional<DefaultAssistantRule> lookup(String domain, String domainType) {
        if (isBlank(domain) || isBlank(domainType)) {
            return Optional.empty();
        }
        String configKey = domain + ":" + domainType;
        String json = sysConfigService.getValue(CONFIG_TYPE, configKey);
        if (json == null) {
            return Optional.empty();
        }
        try {
            DefaultAssistantRule rule = objectMapper.readValue(json, DefaultAssistantRule.class);
            return Optional.of(rule);
        } catch (JsonProcessingException e) {
            log.warn("[RuleService] invalid JSON for {}:{}: {}", domain, domainType, e.getMessage());
            return Optional.empty();
        } catch (IllegalArgumentException e) {
            // record canonical constructor 抛出（字段空校验失败）
            log.warn("[RuleService] invalid rule fields for {}:{}: {}", domain, domainType, e.getMessage());
            return Optional.empty();
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
