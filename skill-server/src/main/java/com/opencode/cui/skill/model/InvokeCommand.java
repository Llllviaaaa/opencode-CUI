package com.opencode.cui.skill.model;

/**
 * Gateway Invoke 指令参数封装。
 * 将原来 5 个分散参数（ak, userId, sessionId, action, payload）封装为不可变对象，
 * 在 GatewayRelayService、DownstreamSender、RebuildCallback 之间统一传递。
 *
 * <p>{@code suppressReply}：可选的下行抑制回复标志，仅在群聊 + 个人助手 channel 命中
 * 白名单时由 InboundProcessingService 显式置 true，其它路径一律置 null（不写入 INVOKE 报文）。
 *
 * <p>{@code domain} / {@code domainType}（PR2 收口添加）：来自 SkillSession 的
 * {@code businessSessionDomain} / {@code businessSessionType}，专供
 * {@code AssistantScopeDispatcher.getStrategy(domain, domainType, info)} 用以在
 * dispatcher 内部反查默认助手规则（命中即返 {@code DefaultAssistantScopeStrategy}）。
 * 老 caller（IM / external / sessionRebuild / 测试）不传值时默认 null —— dispatcher 内
 * lookup 返 empty，路由完全等同于老 API getStrategy(info)。
 *
 * <p>{@code businessSessionId}（platformExtParam PR1 添加）：来自
 * {@code SkillSession.businessSessionId} 或入参 {@code sessionId}，用于在出向 invoke
 * 时构造 {@code extParameters.platformExtParam.businessSessionId}。与 {@code payload.imGroupId}
 * 短期同值并存（命名冗余，详见 PRD R9）。老 caller 不传值时默认 null，
 * platform helper 会序列化为 JSON {@code null}（保留 key）。
 *
 * <p>{@code domain} / {@code domainType} / {@code businessSessionId} 三者构成
 * {@code platformExtParam} 的数据源。
 *
 * @param ak                Agent 应用密钥
 * @param userId            用户 ID
 * @param sessionId         Skill 侧会话 ID
 * @param action            调用动作（chat、create_session、close_session 等）
 * @param payload           JSON 格式的载荷数据
 * @param suppressReply     是否抑制 plugin opencode 回复（null = 不携带该字段）
 * @param domain            SkillSession.businessSessionDomain，用于 dispatcher 反查默认助手规则 + platformExtParam（null = 不参与反查）
 * @param domainType        SkillSession.businessSessionType，同上
 * @param businessSessionId SkillSession.businessSessionId，用于 platformExtParam（null = JSON null）
 */
public record InvokeCommand(
                String ak,
                String userId,
                String sessionId,
                String action,
                String payload,
                Boolean suppressReply,
                String domain,
                String domainType,
                String businessSessionId) {

    /**
     * 兼容性构造：保留原 5 参数签名，{@code suppressReply} / {@code domain} / {@code domainType}
     * / {@code businessSessionId} 默认 null。
     */
    public InvokeCommand(String ak, String userId, String sessionId, String action, String payload) {
        this(ak, userId, sessionId, action, payload, null, null, null, null);
    }

    /**
     * 兼容性构造：保留原 6 参数签名（带 suppressReply），{@code domain} / {@code domainType}
     * / {@code businessSessionId} 默认 null。
     */
    public InvokeCommand(String ak, String userId, String sessionId, String action, String payload,
            Boolean suppressReply) {
        this(ak, userId, sessionId, action, payload, suppressReply, null, null, null);
    }

    /**
     * 兼容性构造：保留 8 参数签名（PR2 收口前的 caller），{@code businessSessionId} 默认 null。
     */
    public InvokeCommand(String ak, String userId, String sessionId, String action, String payload,
            Boolean suppressReply, String domain, String domainType) {
        this(ak, userId, sessionId, action, payload, suppressReply, domain, domainType, null);
    }
}
