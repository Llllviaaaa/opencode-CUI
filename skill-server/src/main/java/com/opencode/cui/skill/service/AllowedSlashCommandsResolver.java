package com.opencode.cui.skill.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 个人助手 chat 场景的 slash 命令白名单解析器。
 *
 * <p>按 {@code (businessSessionDomain, businessSessionType)} 反查 sys_config
 * {@code config_type=allowed_slash_commands}，命中即返
 * {@code List<String>}（允许的 slash 命令清单）。返 null 表示"未配置/降级"，
 * 由 caller 决定不下发 {@code platformExtParam.allowedSlashCommands} key。
 *
 * <p><b>缓存机制</b>：复用 {@link SysConfigService} 内部 Redis 5 分钟 TTL 缓存
 * （由 {@code SysConfigProperties.cacheTtlMinutes} 配置）。{@code SysConfigService.update/create}
 * 自动 evict 缓存；{@code delete} 依赖 TTL 自然过期（运营要求立即生效请走 update path）。
 *
 * <p><b>不做的事</b>（对齐 {@link DefaultAssistantRuleService} 薄壳约定）：
 * <ul>
 *   <li>不自管内存 snapshot</li>
 *   <li>不周期 reload</li>
 *   <li>不在外层加缓存（双重缓存会让 update evict 传播变慢）</li>
 * </ul>
 *
 * <p><b>严格 string[] 校验</b>（PRD v3 决策 6 + AC12）：
 * 数组含<b>任一</b>非 textual 元素（数字 / 布尔 / 对象 / null）→ 整数组拒绝 + WARN，
 * 不做"挑出 textual 元素"的混合策略，避免运营误配置静默生效。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AllowedSlashCommandsResolver {

    private static final String CONFIG_TYPE = "allowed_slash_commands";

    private final SysConfigService sysConfigService;
    private final ObjectMapper objectMapper;

    /**
     * 解析当前 (domain, type) 维度的允许 slash 命令清单。
     *
     * <p>语义：
     * <ul>
     *   <li>入参任一 null/blank → {@code null}（不查 sysconfig，不写 WARN）</li>
     *   <li>sysconfig 抛 RuntimeException → {@code null} + WARN</li>
     *   <li>sysconfig value 是 null/blank → {@code null}（未配置）</li>
     *   <li>JSON 解析失败 → {@code null} + WARN</li>
     *   <li>不是 JSON 数组 → {@code null} + WARN</li>
     *   <li>数组含任一非 textual 元素（数字 / 布尔 / 对象 / null）→ {@code null} + WARN</li>
     *   <li>数组合法但全为 blank 字符串 → {@code null}（归一为"未配置"）</li>
     *   <li>合法非空数组 → {@code List<String>}（剔除 blank 字符串后非空）</li>
     * </ul>
     *
     * @param domain 业务域，来自 {@code SkillSession.businessSessionDomain} 或入参
     * @param type   会话类型，来自 {@code SkillSession.businessSessionType} 或入参
     * @return 允许的 slash 命令清单（非空 List），未配置 / 降级 / 入参非法时返 {@code null}
     */
    @Nullable
    public List<String> resolve(@Nullable String domain, @Nullable String type) {
        if (domain == null || domain.isBlank() || type == null || type.isBlank()) {
            return null;
        }
        String key = domain + "_" + type;
        String json;
        try {
            json = sysConfigService.getValue(CONFIG_TYPE, key);
        } catch (RuntimeException e) {
            log.warn("[AllowedSlash] sysconfig read failed key={}: {}", key, e.getMessage());
            return null;
        }
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            if (!node.isArray()) {
                log.warn("[AllowedSlash] not a JSON array key={}, nodeType={}", key, node.getNodeType());
                return null;
            }
            List<String> list = new ArrayList<>(node.size());
            for (JsonNode el : node) {
                if (!el.isTextual()) {
                    log.warn("[AllowedSlash] non-textual element key={}, nodeType={}", key, el.getNodeType());
                    return null;
                }
                String s = el.asText();
                if (s != null && !s.isBlank()) {
                    list.add(s);
                }
            }
            return list.isEmpty() ? null : list;
        } catch (JsonProcessingException e) {
            log.warn("[AllowedSlash] invalid JSON key={}: {}", key, e.getMessage());
            return null;
        }
    }
}
