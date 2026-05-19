package com.opencode.cui.skill.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

/**
 * 构造 {@code extParameters.platformExtParam} JSON 对象的公共 helper。
 *
 * <p>三字段 key 始终出现；任一字段为 null 时，序列化为 JSON {@code null}（{@link NullNode}），
 * 不省略 key。三处构造点（{@code BusinessScopeStrategy} / {@code DefaultAssistantScopeStrategy}
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
 * </ul>
 */
public final class PlatformExtParamBuilder {

    private PlatformExtParamBuilder() {
    }

    /**
     * 构造 platformExtParam JSON 对象，三字段 key 始终出现，null 值序列化为 JSON {@code null}。
     *
     * @param objectMapper         Jackson ObjectMapper（用于创建 ObjectNode）
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
}
