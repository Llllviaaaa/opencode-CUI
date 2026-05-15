package com.opencode.cui.skill.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 默认助手规则。
 * <p>
 * sys_config 表中 {@code config_type=default_assistant_rule} 的 value JSON 反序列化目标。
 * 通过 {@code (domain, type)} 复合 key 反查（{@code config_key="{domain}:{type}"}），
 * 命中后注入 {@link SkillSession} 的 ak / assistantAccount 字段，并提供
 * {@code businessTag} 供 {@code CloudRequestProfileRegistry.resolve(...)} 选 profile。
 * </p>
 *
 * <p>
 * {@code domain} / {@code domainType} 不在 record 内——它们是 sys_config key 的拼接成分，
 * 而非 value JSON 字段。
 * </p>
 *
 * @param ak               虚拟 ak。写回 SkillSession.ak（用于业务/路由识别，上游不识别）
 * @param assistantAccount 虚拟 assistantAccount。写回 SkillSession.assistantAccount + cloud_request.assistantAccount
 * @param businessTag      业务路由标签，SS 内部喂给 CloudRequestProfileRegistry.resolve 选 profile；wire 上以 payload.cloudProfile 传给 GW
 */
public record DefaultAssistantRule(
        @JsonProperty("ak") String ak,
        @JsonProperty("assistantAccount") String assistantAccount,
        @JsonProperty("businessTag") String businessTag) {

    @JsonCreator
    public DefaultAssistantRule {
        if (isBlank(ak) || isBlank(assistantAccount) || isBlank(businessTag)) {
            throw new IllegalArgumentException(
                    "DefaultAssistantRule fields must be non-blank: ak=" + ak
                            + ", assistantAccount=" + assistantAccount
                            + ", businessTag=" + businessTag);
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
