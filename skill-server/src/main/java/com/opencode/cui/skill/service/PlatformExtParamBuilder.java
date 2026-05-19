package com.opencode.cui.skill.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.springframework.lang.Nullable;

import java.util.List;

/**
 * 构造 {@code extParameters.platformExtParam} JSON 对象的公共 helper。
 *
 * <p>三字段 {@code businessSessionDomain / businessSessionType / businessSessionId} 始终出现；
 * 任一字段为 null 时，序列化为 JSON {@code null}（{@link NullNode}），不省略 key。
 * 三处构造点（{@code BusinessScopeStrategy} / {@code DefaultAssistantScopeStrategy}
 * / {@code GatewayRelayService} + {@code retryPendingMessages}）共用本 helper，避免行为漂移。
 *
 * <p>语义：
 * <ul>
 *   <li>{@code businessSessionDomain}：业务域，来自 {@code SkillSession.businessSessionDomain} /
 *       入参 {@code businessDomain}</li>
 *   <li>{@code businessSessionType}：会话类型（direct / group / ...），来自
 *       {@code SkillSession.businessSessionType} / 入参 {@code sessionType}</li>
 *   <li>{@code businessSessionId}：业务侧会话 ID，来自 {@code SkillSession.businessSessionId} /
 *       入参 {@code sessionId}。<b>注意</b>：与 {@code payload.imGroupId} 短期同值并存，
 *       命名冗余约定见 PRD R9。</li>
 *   <li>{@code allowedSlashCommands}（v3 新增）：personal scope CHAT 允许的 slash 命令清单。
 *       <b>contract 与三字段不同</b>：list == null 时<b>不出现</b> key；非空 list 时出现 JSON 数组。
 *       仅 personal scope 5 参重载（含 caller 显式 resolve）写入；business / default_assistant 4 参
 *       重载不写入。</li>
 * </ul>
 */
public final class PlatformExtParamBuilder {

    private PlatformExtParamBuilder() {
    }

    /**
     * 构造 platformExtParam JSON 对象（4 参重载，business / default_assistant 用）。
     *
     * <p>三字段 key 始终出现，null 值序列化为 JSON {@code null}；
     * {@code allowedSlashCommands} key 不出现。
     *
     * @param objectMapper          Jackson ObjectMapper（用于创建 ObjectNode）
     * @param businessSessionDomain 业务域，null → JSON null
     * @param businessSessionType   会话类型，null → JSON null
     * @param businessSessionId     业务侧会话 ID，null → JSON null
     * @return 含三字段的 {@link ObjectNode}（key 始终存在，值为 TextNode 或 NullNode）
     */
    public static ObjectNode build(ObjectMapper objectMapper,
                                   String businessSessionDomain,
                                   String businessSessionType,
                                   String businessSessionId) {
        ObjectNode node = objectMapper.createObjectNode();
        node.set("businessSessionDomain",
                businessSessionDomain != null ? new TextNode(businessSessionDomain) : NullNode.instance);
        node.set("businessSessionType",
                businessSessionType != null ? new TextNode(businessSessionType) : NullNode.instance);
        node.set("businessSessionId",
                businessSessionId != null ? new TextNode(businessSessionId) : NullNode.instance);
        return node;
    }

    /**
     * 构造 platformExtParam JSON 对象（5 参重载，personal scope 用）。
     *
     * <p>三字段语义同 4 参重载；额外接收 {@code allowedSlashCommands}：
     * <ul>
     *   <li>{@code null} → 不出现该 key（与决策 6 一致：未配置 / 降级 / 非 CHAT / 非 personal 一律不下发）</li>
     *   <li>非空 List → JSON 数组（如 {@code ["plan","ask","run"]}）</li>
     *   <li>空 List 分支理论不存在（{@link AllowedSlashCommandsResolver} 已归一为 null）；
     *       防御性地，空 List 也<b>不</b>出现 key（保持与 null 同语义）</li>
     * </ul>
     *
     * @param objectMapper          Jackson ObjectMapper
     * @param businessSessionDomain 业务域，null → JSON null
     * @param businessSessionType   会话类型，null → JSON null
     * @param businessSessionId     业务侧会话 ID，null → JSON null
     * @param allowedSlashCommands  允许的 slash 命令清单，null / 空 → 不出现该 key
     * @return 含三字段（始终）+ allowedSlashCommands（条件出现）的 {@link ObjectNode}
     */
    public static ObjectNode build(ObjectMapper objectMapper,
                                   String businessSessionDomain,
                                   String businessSessionType,
                                   String businessSessionId,
                                   @Nullable List<String> allowedSlashCommands) {
        ObjectNode node = build(objectMapper, businessSessionDomain, businessSessionType, businessSessionId);
        if (allowedSlashCommands != null && !allowedSlashCommands.isEmpty()) {
            ArrayNode arr = node.putArray("allowedSlashCommands");
            for (String cmd : allowedSlashCommands) {
                arr.add(cmd);
            }
        }
        return node;
    }
}
