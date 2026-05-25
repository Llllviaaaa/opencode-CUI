package com.opencode.cui.skill.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.lang.Nullable;

import java.util.List;

/**
 * IM 入站消息在 personal 助手"首次对话" / 情况 C 路径下排队等待 toolSessionId 绑定时，
 * Redis pending list（{@code ss:pending-rebuild:{sessionId}}）里缓存的结构化条目。
 *
 * <p><strong>定位</strong>：Redis pending list 的内部传输 DTO，<em>不是</em>外部 API 契约。
 * 仅用于 {@code SessionRebuildService.appendPendingMessage} 入队 + JSON 序列化、
 * {@code SessionRebuildService.consumePendingMessages} 反序列化出队、
 * 然后由 {@code GatewayMessageRouter.retryPendingMessages} 重建完整 chat invoke payload。
 *
 * <p><strong>字段顺序固定</strong>：用于稳定 JSON 字段顺序（便于排查 / diff），与 PRD §Requirements 1 对齐。
 * 字段 10 个（PR2 platformExtParam 新增 2 个 + bizRobotTag 新增 1 个 + v3 allowed-slash 新增 1 个），逐一对应
 * {@code dispatchChatToGateway} / retryPendingMessages 构造的 chat payload 关键字段。
 *
 * <p><strong>businessSessionDomain / businessSessionType</strong>（PR2 platformExtParam 新增）：
 * 用于 {@code retryPendingMessages} 重建 chat payload 时构造 {@code extParameters.platformExtParam}
 * 的三字段之一。{@code businessSessionId} 复用现有 {@link #imGroupId} 字段语义（PRD R9），
 * 不额外新增字段；imGroupId 与 businessSessionId 短期同值并存（命名冗余约定）。
 * 缺失（老格式 JSON entry / fallback 未补齐）时各字段为 Java null，
 * Jackson 反序列化对缺字段的老 entry 自动兜底 null（{@link JsonCreator} 注解保留 default null）。
 *
 * <p><strong>allowedSlashCommands</strong>（v3 allowed-slash-commands 任务新增）：
 * personal scope 首次对话场景下，入 pending 时由 caller 通过 {@code AllowedSlashCommandsResolver}
 * 一次性 resolve 后写入，retry 阶段直接从本字段读取并通过 {@code PlatformExtParamBuilder} 5 参
 * 重载下发 —— <b>frozen 语义</b>：first 入 pending 取一次后冻结，sysconfig 期间被更新不会
 * 影响 retry 下发的值。null = 未配置 / 降级（retry 不下发该 platformExtParam key），
 * 非空 List = 下发 JSON 数组。
 *
 * <p><strong>不含 {@code assistantId}</strong>：assistantId 由
 * {@code GatewayRelayService.buildInvokeMessage} 在 CHAT / CREATE_SESSION 时自动注入，
 * 在 pending DTO 里再保存会造成"双写"语义不清，参见 PRD Codex Review Minor m1。
 *
 * <p><strong>JSON 形状示例</strong>：
 * <pre>{@code
 * {
 *   "text": "你好",
 *   "assistantAccount": "assist-01",
 *   "sendUserAccount": "user-real-sender",
 *   "imGroupId": "group-001",
 *   "messageId": "1717939200000",
 *   "businessExtParam": {"foo": "bar"},
 *   "businessSessionDomain": "im",
 *   "businessSessionType": "group",
 *   "bizRobotTag": "robot-a",
 *   "allowedSlashCommands": ["plan","ask","run"]
 * }
 * }</pre>
 *
 * <p><strong>降级路径</strong>：当上下文不全（如 {@code rebuildFromStoredUserMessage} 仅有 text + session）时，
 * 仅 direct / miniapp 可通过 {@link #fromSessionFallback(SkillSession, String)} 用 session 反查 sender；
 * IM group 不允许从 {@code session.userId} 推断发送人。fallback 之前会校验 {@code assistantAccount} /
 * {@code userId} 非空，缺失即抛 {@link IllegalArgumentException}，避免后续云端 fast-fail，参见 PRD Codex Review Major M5。
 *
 * @param text                  用户输入的纯文本 prompt
 * @param assistantAccount      助手账号（信封层必填，下游云端策略消费）
 * @param sendUserAccount       实际发送者账号（优先真实 sender；降级路径只能从 session 反查）
 * @param imGroupId             群聊业务会话 ID（== {@code SkillSession.businessSessionId}）；单聊场景为 null；
 *                              亦作为 platformExtParam.businessSessionId 来源
 * @param messageId             消息 ID（首次对话用毫秒时间戳即可，无业务幂等语义）
 * @param businessExtParam      业务扩展参数（透传给下游，可为 null）
 * @param businessSessionDomain 业务域（PR2 platformExtParam 新增；来自 {@code SkillSession.businessSessionDomain}）
 * @param businessSessionType   业务会话类型（PR2 platformExtParam 新增；来自 {@code SkillSession.businessSessionType}）
 * @param bizRobotTag           助理业务机器人标签；用于 retry 时重建 {@code platformExtParam.bizRobotTag}
 * @param allowedSlashCommands  personal scope CHAT 允许的 slash 命令清单（v3 新增；first 入 pending 时 frozen，retry 复用）
 */
public record PendingChatRequest(
        @JsonProperty("text") String text,
        @JsonProperty("assistantAccount") String assistantAccount,
        @JsonProperty("sendUserAccount") String sendUserAccount,
        @JsonProperty("imGroupId") String imGroupId,
        @JsonProperty("messageId") String messageId,
        @JsonProperty("businessExtParam") JsonNode businessExtParam,
        @JsonProperty("businessSessionDomain") String businessSessionDomain,
        @JsonProperty("businessSessionType") String businessSessionType,
        @JsonProperty("bizRobotTag") String bizRobotTag,
        @JsonProperty("allowedSlashCommands") @Nullable List<String> allowedSlashCommands) {

    /**
     * 显式 {@link JsonCreator} 注解 + {@link JsonProperty} 让 Jackson 反序列化稳定工作，
     * 无需依赖 {@code -parameters} 编译标志或 {@code ParameterNamesModule} 自动注册。
     * 与同包的 {@link DefaultAssistantRule} 保持一致风格。
     *
     * <p>老格式 JSON entry（缺 businessSessionDomain / businessSessionType / allowedSlashCommands）
     * 反序列化时，Jackson 自动把缺失字段映射为 Java null，record canonical constructor 不做强校验。
     */
    @JsonCreator
    public PendingChatRequest {
        // 注：本任务不在 record canonical constructor 中对字段做非空校验：
        //   - retryPendingMessages 路径允许 imGroupId / businessExtParam / businessSessionDomain / businessSessionType / allowedSlashCommands 为 null（单聊 / 老 entry 场景）。
        //   - text / sender / assistantAccount 由上游调用方保证（dispatchChatToGateway 或 fromSessionFallback）。
        //   - 仅 fromSessionFallback 工厂方法对 session.assistantAccount / session.userId 强制校验。
    }

    /**
     * 兼容性构造：保留原 8 参签名（v3 前的 canonical），{@code bizRobotTag} /
     * {@code allowedSlashCommands} 默认 null。
     *
     * <p>覆盖 PRD B 表中 B4/B5/B6 三处生产代码非升级 callsite（IAE 兜底 + plain text fallback）
     * + ~20 处 test callsite + business self-heal fallback。
     */
    public PendingChatRequest(String text, String assistantAccount, String sendUserAccount,
                              String imGroupId, String messageId, JsonNode businessExtParam,
                              String businessSessionDomain, String businessSessionType) {
        this(text, assistantAccount, sendUserAccount, imGroupId, messageId, businessExtParam,
                businessSessionDomain, businessSessionType, null, null);
    }

    /**
     * 兼容性构造：保留 allowed-slash 任务后的 9 参签名，{@code bizRobotTag} 默认 null。
     */
    public PendingChatRequest(String text, String assistantAccount, String sendUserAccount,
                              String imGroupId, String messageId, JsonNode businessExtParam,
                              String businessSessionDomain, String businessSessionType,
                              @Nullable List<String> allowedSlashCommands) {
        this(text, assistantAccount, sendUserAccount, imGroupId, messageId, businessExtParam,
                businessSessionDomain, businessSessionType, null, allowedSlashCommands);
    }

    /**
     * 从 {@link SkillSession} 反查构造降级 {@code PendingChatRequest}（2 参向后兼容签名，默认
     * {@code allowedSlashCommands = null}）。
     *
     * <p>用途与契约见 {@link #fromSessionFallback(SkillSession, String, List)}。
     *
     * <p>覆盖现网 ~9 处 callsite（{@code consumePendingRequests} 路径 / {@code rebuildFromStoredUserMessage}
     * DB plain text path 等不知 allowed list 的场景），传 null 表示"retry 时不下发该 platformExtParam key"。
     *
     * @param session 已就绪的 {@link SkillSession}（非 null）
     * @param text    用户原始文本 prompt（可为 null / 空，调用方自行决定是否入队）
     * @return 反查降级后的 {@link PendingChatRequest}（allowedSlashCommands = null）
     * @throws IllegalArgumentException session 为 null，或 assistantAccount / userId 空白
     */
    public static PendingChatRequest fromSessionFallback(SkillSession session, String text) {
        return fromSessionFallback(session, text, null);
    }

    /**
     * 从 {@link SkillSession} 反查构造降级 {@code PendingChatRequest}（v3 新增 3 参重载，
     * caller 显式传入 {@code allowedSlashCommands}）。
     * <p>
     * 用途：
     * <ol>
     *   <li>{@code consumePendingMessages} 反序列化检测到老格式 plain-string entry，
     *       原文当 text，其他字段用 session 反查兜底。</li>
     *   <li>{@code SessionRebuildService.rebuildFromStoredUserMessage} 从 DB 拉取
     *       lastUserMessage 触发 rebuild，DB 里只有 text，sender/ext 不可知 → 降级到 owner + null。</li>
     *   <li>{@code SessionRebuildService.rebuildToolSession} legacy String overload（miniapp first
     *       / IM case B personal / business self-heal fallback）— v3 在此处做 personal scope gating，
     *       命中后通过本 3 参重载传入 resolved list。</li>
     * </ol>
     *
     * <p><strong>规则</strong>：
     * <ul>
     *   <li>{@code assistantAccount} = {@code session.getAssistantAccount()}</li>
     *   <li>{@code sendUserAccount}  = {@code session.getUserId()}（仅 direct / miniapp 降级可用）</li>
     *   <li>{@code imGroupId}        = {@code session.isImGroupSession()} ?
     *       <strong>{@code session.getBusinessSessionId()}</strong> : null
     *       （绝对不能误用 {@code session.getId()}：那是 skill 主键，不是 IM 业务 ID。
     *        参见 PRD Codex Review Major M3）</li>
     *   <li>{@code messageId}        = {@code System.currentTimeMillis()}（重发时刻时间戳）</li>
     *   <li>{@code businessExtParam} = {@code null}</li>
     *   <li>{@code businessSessionDomain} = {@code session.getBusinessSessionDomain()}</li>
     *   <li>{@code businessSessionType}   = {@code session.getBusinessSessionType()}</li>
     *   <li>{@code allowedSlashCommands}  = caller 显式传入（personal scope 命中时 resolved list，
     *       其它路径 null）</li>
     * </ul>
     *
     * <p><strong>校验</strong>：IM group 直接拒绝；{@code session.getAssistantAccount()} 或
     * {@code session.getUserId()} 任一空白即抛
     * {@link IllegalArgumentException}，调用方应 catch 并记 ERROR + 保留 pending 供人工排查，
     * 不要静默 fallback 后让下游云端策略 fast-fail。参见 PRD Codex Review Major M5。
     *
     * @param session              已就绪的 {@link SkillSession}（非 null）
     * @param text                 用户原始文本 prompt（可为 null / 空，调用方自行决定是否入队）
     * @param allowedSlashCommands personal scope 命中时 resolved list；其它路径传 null
     * @return 反查降级后的 {@link PendingChatRequest}
     * @throws IllegalArgumentException session 为 null，或 assistantAccount / userId 空白
     */
    public static PendingChatRequest fromSessionFallback(SkillSession session, String text,
            @Nullable List<String> allowedSlashCommands) {
        if (session == null) {
            throw new IllegalArgumentException("PendingChatRequest.fromSessionFallback: session must not be null");
        }
        String assistantAccount = session.getAssistantAccount();
        if (session.isImGroupSession()) {
            throw new IllegalArgumentException(
                    "PendingChatRequest.fromSessionFallback: group session cannot infer sender from session.userId,"
                            + " assistantAccount=" + assistantAccount
                            + ", skillSessionId=" + session.getId());
        }
        String userId = session.getUserId();
        if (isBlank(assistantAccount) || isBlank(userId)) {
            throw new IllegalArgumentException(
                    "PendingChatRequest.fromSessionFallback: session fields must be non-blank,"
                            + " assistantAccount=" + assistantAccount
                            + ", userId=" + userId
                            + ", skillSessionId=" + session.getId());
        }
        String imGroupId = session.isImGroupSession() ? session.getBusinessSessionId() : null;
        String messageId = String.valueOf(System.currentTimeMillis());
        return new PendingChatRequest(
                text,
                assistantAccount,
                userId,
                imGroupId,
                messageId,
                null,
                session.getBusinessSessionDomain(),
                session.getBusinessSessionType(),
                null,
                allowedSlashCommands);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
