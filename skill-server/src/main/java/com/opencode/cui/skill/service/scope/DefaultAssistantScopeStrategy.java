package com.opencode.cui.skill.service.scope;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.opencode.cui.skill.logging.MdcHelper;
import com.opencode.cui.skill.model.AssistantInfo;
import com.opencode.cui.skill.model.DefaultAssistantRule;
import com.opencode.cui.skill.model.InvokeCommand;
import com.opencode.cui.skill.model.StreamMessage;
import com.opencode.cui.skill.service.CloudEventTranslator;
import com.opencode.cui.skill.service.DefaultAssistantRuleService;
import com.opencode.cui.skill.service.GatewayActions;
import com.opencode.cui.skill.service.PlatformExtParamBuilder;
import com.opencode.cui.skill.service.SnowflakeIdGenerator;
import com.opencode.cui.skill.service.cloud.CloudRequestContext;
import com.opencode.cui.skill.service.cloud.CloudRequestStrategy;
import com.opencode.cui.skill.service.cloud.profile.CloudRequestProfile;
import com.opencode.cui.skill.service.cloud.profile.CloudRequestProfileRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 默认助手策略（PR2 新增）。
 *
 * <p>与 {@link PersonalScopeStrategy} / {@link BusinessScopeStrategy} 并列的第三种 scope，
 * 服务于"客户端不传 ak / assistantAccount，仅传 (domain, type) + cookie userId"的
 * miniapp 通道场景。dispatcher 通过
 * {@link AssistantScopeDispatcher#getStrategy(String, String, AssistantInfo)} 反查
 * {@code default_assistant_rule} 命中后返回本 strategy。</p>
 *
 * <ul>
 *   <li>{@link #getScope()} → {@code "default_assistant"}（本地 dispatcher 路由识别用）</li>
 *   <li>{@link #buildInvoke} 内部按 {@code cmd.domain()/cmd.domainType()} 反查
 *       {@link DefaultAssistantRuleService}.lookup 取规则（不走 ak 反查），
 *       从 {@code rule.businessTag()} 调 {@link CloudRequestProfileRegistry#resolve} 选 profile。
 *       wire 上 {@code assistantScope="business"}（让 GW 按业务路径处理）+
 *       {@code payload.cloudProfile=profile.name()}</li>
 *   <li>{@link #generateToolSessionId()} → Snowflake long ID 字符串（与 business 一致）</li>
 *   <li>{@link #requiresOnlineCheck()} / {@link #requiresSessionCreatedCallback()} → false</li>
 *   <li>{@link #translateEvent} → 复用 {@link CloudEventTranslator}</li>
 * </ul>
 *
 * <p><b>注</b>：PR2 中间态，本类已挂 dispatcher 的 strategy map（Spring 自动收集），
 * 但 caller 还未接入；PR3 才会让 controller / GatewayRelayService 调到本 strategy。
 * 逻辑与 {@link BusinessScopeStrategy} 大量重叠但显式独立 —— 短期重复，Known Issues
 * #future 记 long-term 抽公共基类。</p>
 */
@Slf4j
@Component
public class DefaultAssistantScopeStrategy implements AssistantScopeStrategy {

    public static final String SCOPE = "default_assistant";

    private final CloudRequestProfileRegistry profileRegistry;
    private final CloudEventTranslator cloudEventTranslator;
    private final ObjectMapper objectMapper;
    private final SnowflakeIdGenerator idGenerator;
    private final DefaultAssistantRuleService ruleService;

    public DefaultAssistantScopeStrategy(CloudRequestProfileRegistry profileRegistry,
            CloudEventTranslator cloudEventTranslator,
            ObjectMapper objectMapper,
            SnowflakeIdGenerator idGenerator,
            DefaultAssistantRuleService ruleService) {
        this.profileRegistry = profileRegistry;
        this.cloudEventTranslator = cloudEventTranslator;
        this.objectMapper = objectMapper;
        this.idGenerator = idGenerator;
        this.ruleService = ruleService;
    }

    @Override
    public String getScope() {
        return SCOPE;
    }

    /**
     * <b>注</b>：当前 AssistantScopeStrategy 接口签名仍保留 {@code (InvokeCommand, AssistantInfo)}，
     * 但本 strategy 完全不用 {@code info}（virtual ak 上游不存在，info 一般为 null）。
     * 所有需要的字段从 {@code command.domain() / command.domainType()} 反查规则得到。
     */
    @Override
    public String buildInvoke(InvokeCommand command, AssistantInfo info) {
        Optional<DefaultAssistantRule> ruleOpt = ruleService.lookup(command.domain(), command.domainType());
        if (ruleOpt.isEmpty()) {
            // 不应到达 —— dispatcher 已经按 lookup.isPresent() 选了本 strategy。
            // 但如果运维在 strategy 选定后、buildInvoke 调用前删了规则（极小窗口），
            // 抛 IAE 让上层显式失败，不静默降级。
            throw new IllegalArgumentException(
                    "DefaultAssistantScopeStrategy.buildInvoke called but rule not found: domain="
                            + command.domain() + ", domainType=" + command.domainType());
        }
        DefaultAssistantRule rule = ruleOpt.get();
        String businessTag = rule.businessTag();
        String action = command.action();

        // 从 command payload 提取 content
        String content = extractContent(command.payload());

        // 从 command payload 提取 toolSessionId 作为 topicId
        String toolSessionId = extractField(command.payload(), "toolSessionId");

        // 取业务方扩展参数（缺省 / 非 object → null，由下方兜底为 {}）
        JsonNode businessExtParam = extractObjectField(command.payload(), "businessExtParam");

        // 按 action 路由 reply 字段（chat → 全部 null）
        String replyToolCallId = null;
        List<List<String>> replyAnswers = null;
        String replyPermissionId = null;
        String replyResponse = null;
        if (GatewayActions.QUESTION_REPLY.equals(action)) {
            replyToolCallId = extractField(command.payload(), "toolCallId");
            String answerRaw = extractField(command.payload(), "answer");
            replyAnswers = parseAnswers(answerRaw);
        } else if (GatewayActions.PERMISSION_REPLY.equals(action)) {
            replyPermissionId = extractField(command.payload(), "permissionId");
            replyResponse = extractField(command.payload(), "response");
        }

        Map<String, Object> extParameters = new LinkedHashMap<>();
        extParameters.put("businessExtParam",
                businessExtParam != null ? businessExtParam : objectMapper.createObjectNode());
        extParameters.put("platformExtParam",
                PlatformExtParamBuilder.build(objectMapper,
                        command.domain(), command.domainType(), command.businessSessionId()));

        // assistantAccount：优先 payload，缺省时从 rule 注入（与 PRD D8 一致）
        String assistantAccount = extractField(command.payload(), "assistantAccount");
        if (isBlank(assistantAccount)) {
            assistantAccount = rule.assistantAccount();
        }
        // sendUserAccount：优先 payload，缺省时回退 command.userId()（miniapp 通道 cookieUserId）
        String sendUserAccount = extractField(command.payload(), "sendUserAccount");
        if (isBlank(sendUserAccount)) {
            sendUserAccount = command.userId();
        }

        CloudRequestContext context = CloudRequestContext.builder()
                .content(content)
                .contentType("text")
                .topicId(toolSessionId)
                .assistantAccount(assistantAccount)
                .sendUserAccount(sendUserAccount)
                .imGroupId(extractField(command.payload(), "imGroupId"))
                .messageId(extractField(command.payload(), "messageId"))
                .clientLang("zh")
                .extParameters(extParameters)
                .replyToolCallId(replyToolCallId)
                .replyAnswers(replyAnswers)
                .replyPermissionId(replyPermissionId)
                .replyResponse(replyResponse)
                .build();

        CloudRequestProfile profile = profileRegistry.resolve(businessTag);
        CloudRequestStrategy strategy = profile.requestStrategy();
        ObjectNode cloudRequest = strategy.build(context);

        ObjectNode payload = objectMapper.createObjectNode();
        payload.set("cloudRequest", cloudRequest);
        if (toolSessionId != null && !toolSessionId.isBlank()) {
            payload.put("toolSessionId", toolSessionId);
        }
        payload.put("cloudProfile", profile.name());

        ObjectNode message = objectMapper.createObjectNode();
        message.put("type", "invoke");
        message.put("ak", command.ak());
        message.put("source", "skill-server");
        message.put("action", action);
        // wire 上 assistantScope="business" 让 GW 按业务路径处理（PRD D4）
        message.put("assistantScope", "business");
        if (assistantAccount != null && !assistantAccount.isBlank()) {
            message.put("assistantAccount", assistantAccount);
        }
        if (businessTag != null && !businessTag.isBlank()) {
            message.put("businessTag", businessTag);
        }
        if (command.userId() != null && !command.userId().isBlank()) {
            message.put("userId", command.userId());
        }

        String traceId = MdcHelper.ensureTraceId();
        message.put("traceId", traceId);

        message.set("payload", payload);

        try {
            return objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize default-assistant invoke message: ak={}, businessTag={}",
                    command.ak(), businessTag, e);
            return null;
        }
    }

    @Override
    public String generateToolSessionId() {
        // Snowflake long ID（字符串形式），让 topicId = Long.parseLong(toolSessionId) 可成功；
        // 跨 vendor 兼容（与 BusinessScopeStrategy 一致）
        return Long.toString(idGenerator.nextId());
    }

    @Override
    public boolean requiresSessionCreatedCallback() {
        return false;
    }

    @Override
    public boolean requiresOnlineCheck() {
        return false;
    }

    @Override
    public StreamMessage translateEvent(JsonNode event, String sessionId) {
        return cloudEventTranslator.translate(event, sessionId);
    }

    // ------------------------------------------------------------------ package-private (testable)

    /**
     * 将 question_reply 的 raw answer 字符串规整为云端协议要求的 List&lt;List&lt;String&gt;&gt;。
     * 行为与 {@link BusinessScopeStrategy#parseAnswers(String)} 一致。
     */
    List<List<String>> parseAnswers(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of(List.of(""));
        }
        try {
            JsonNode node = objectMapper.readTree(raw);
            if (node.isArray() && !node.isEmpty()) {
                boolean allArray = true;
                for (JsonNode el : node) {
                    if (!el.isArray()) {
                        allArray = false;
                        break;
                    }
                }
                if (allArray) {
                    return objectMapper.convertValue(node,
                            new TypeReference<List<List<String>>>() {});
                }
                return List.of(objectMapper.convertValue(node,
                        new TypeReference<List<String>>() {}));
            }
        } catch (Exception ignored) {
            /* fall through to plain-text fallback */
        }
        return List.of(List.of(raw));
    }

    // ------------------------------------------------------------------ private

    private String extractField(String payload, String fieldName) {
        if (payload == null || payload.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(payload);
            JsonNode field = node.path(fieldName);
            return field.isMissingNode() || field.isNull() ? null : field.asText();
        } catch (JsonProcessingException e) {
            log.debug("Failed to parse payload for field extraction: field={}, error={}", fieldName,
                    e.getMessage());
            return null;
        }
    }

    private JsonNode extractObjectField(String payload, String fieldName) {
        if (payload == null || payload.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(payload);
            JsonNode field = node.path(fieldName);
            if (field.isMissingNode() || field.isNull()) {
                return null;
            }
            if (!field.isObject()) {
                log.warn("{} is not a JSON object, treating as empty: actualType={}, value={}",
                        fieldName, field.getNodeType(), field);
                return null;
            }
            return field;
        } catch (JsonProcessingException e) {
            log.debug("Failed to parse payload for object field extraction: field={}, error={}",
                    fieldName, e.getMessage());
            return null;
        }
    }

    private String extractContent(String payload) {
        if (payload == null || payload.isBlank()) {
            return "";
        }
        try {
            JsonNode node = objectMapper.readTree(payload);
            return node.path("text").asText(node.path("content").asText(node.path("message").asText("")));
        } catch (JsonProcessingException e) {
            log.debug("Failed to parse payload for content extraction, using raw: {}", e.getMessage());
            return payload;
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
