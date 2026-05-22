package com.opencode.cui.skill.service.scope;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.opencode.cui.skill.model.AssistantInfo;
import com.opencode.cui.skill.model.InvokeCommand;
import com.opencode.cui.skill.model.StreamMessage;
import com.opencode.cui.skill.service.CloudEventTranslator;
import com.opencode.cui.skill.service.GatewayActions;
import com.opencode.cui.skill.service.PlatformExtParamBuilder;
import com.opencode.cui.skill.service.SnowflakeIdGenerator;
import com.opencode.cui.skill.service.cloud.CloudRequestBuilder;
import com.opencode.cui.skill.service.cloud.CloudRequestContext;
import com.opencode.cui.skill.service.cloud.CloudRequestStrategy;
import com.opencode.cui.skill.service.cloud.profile.CloudRequestProfile;
import com.opencode.cui.skill.service.cloud.profile.CloudRequestProfileRegistry;
import com.opencode.cui.skill.logging.MdcHelper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 业务助手策略。
 * <ul>
 *   <li>invoke 通过 {@link CloudRequestProfileRegistry} 选择 profile + strategy 构建云端请求体；
 *       payload 携带 {@code cloudProfile} 字段供 GW 端选择 decoder</li>
 *   <li>toolSessionId 使用 Snowflake long ID（字符串形式），让 {@code topicId = Long.parseLong(toolSessionId)} 可成功</li>
 *   <li>不需要 session_created 回调</li>
 *   <li>不需要 Agent 在线检查</li>
 *   <li>事件翻译使用 CloudEventTranslator</li>
 * </ul>
 *
 * <p>历史：{@link CloudRequestBuilder} 已 @Deprecated，作为回滚兜底保留。</p>
 */
@Slf4j
@Component
public class BusinessScopeStrategy implements AssistantScopeStrategy {

    private final CloudRequestProfileRegistry profileRegistry;
    private final CloudEventTranslator cloudEventTranslator;
    private final ObjectMapper objectMapper;
    private final SnowflakeIdGenerator idGenerator;

    public BusinessScopeStrategy(CloudRequestProfileRegistry profileRegistry,
                                 CloudEventTranslator cloudEventTranslator,
                                 ObjectMapper objectMapper,
                                 SnowflakeIdGenerator idGenerator) {
        this.profileRegistry = profileRegistry;
        this.cloudEventTranslator = cloudEventTranslator;
        this.objectMapper = objectMapper;
        this.idGenerator = idGenerator;
    }

    @Override
    public String getScope() {
        return "business";
    }

    @Override
    public String buildInvoke(InvokeCommand command, AssistantInfo info) {
        String businessTag = info.getBusinessTag();
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

        // 用 LinkedHashMap 保证 businessExtParam 序列化在 platformExtParam 之前（与协议文档示例一致）
        Map<String, Object> extParameters = new LinkedHashMap<>();
        extParameters.put("businessExtParam",
                businessExtParam != null ? businessExtParam : objectMapper.createObjectNode());
        extParameters.put("platformExtParam",
                PlatformExtParamBuilder.build(objectMapper,
                        command.domain(), command.domainType(), command.businessSessionId()));

        String assistantAccount = firstNonBlank(
                extractField(command.payload(), "assistantAccount"),
                command.assistantAccount(),
                command.partnerAccount());

        CloudRequestContext context = CloudRequestContext.builder()
                .content(content)
                .contentType("text")
                .topicId(toolSessionId)
                .assistantAccount(assistantAccount)
                .sendUserAccount(extractField(command.payload(), "sendUserAccount"))
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

        // 构建 payload：GW 的 CloudAgentService 期望 payload.cloudRequest / payload.toolSessionId / payload.cloudProfile
        ObjectNode payload = objectMapper.createObjectNode();
        payload.set("cloudRequest", cloudRequest);
        if (toolSessionId != null && !toolSessionId.isBlank()) {
            payload.put("toolSessionId", toolSessionId);
        }
        payload.put("cloudProfile", profile.name());

        // 包装为 Gateway invoke 消息格式
        ObjectNode message = objectMapper.createObjectNode();
        message.put("type", "invoke");
        message.put("ak", command.ak());
        message.put("source", "skill-server");
        message.put("action", command.action());
        message.put("assistantScope", "business");
        if (command.userId() != null && !command.userId().isBlank()) {
            message.put("userId", command.userId());
        }

        // 注入 traceId：从 MDC 获取或自动生成，确保跨服务链路可追踪
        String traceId = MdcHelper.ensureTraceId();
        message.put("traceId", traceId);

        message.set("payload", payload);

        try {
            return objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize business invoke message: ak={}, businessTag={}",
                    command.ak(), businessTag, e);
            return null;
        }
    }

    @Override
    public String generateToolSessionId() {
        // Snowflake long ID（字符串形式），让 topicId = Long.parseLong(toolSessionId) 直接成功
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
     * <ul>
     *   <li>null / blank → [[""]]（兜底单空字符串）</li>
     *   <li>stringified 嵌套数组（如 {@code [["A"],["B","C"]]}）→ 原样 List</li>
     *   <li>stringified 一维数组（如 {@code ["A","B"]}）→ 包裹外层为 [["A","B"]]</li>
     *   <li>普通文本 / 解析失败 → [[raw]]</li>
     * </ul>
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
                // 一维数组兜底
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
            log.debug("Failed to parse payload for field extraction: field={}, error={}", fieldName, e.getMessage());
            return null;
        }
    }

    /**
     * 从 payload string 中安全提取嵌套 JSON object 字段。
     * 返回 null 触发上层兜底为 {}：
     * - payload null/blank → null
     * - readTree 失败 → null + DEBUG 日志
     * - 字段缺失 / NullNode → null
     * - 字段非 object（string/array/number/bool）→ null + WARN 日志
     */
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

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
