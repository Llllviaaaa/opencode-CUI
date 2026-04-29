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
import com.opencode.cui.skill.service.cloud.CloudRequestBuilder;
import com.opencode.cui.skill.service.cloud.CloudRequestContext;
import com.opencode.cui.skill.logging.MdcHelper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 业务助手策略。
 * <ul>
 *   <li>invoke 通过 CloudRequestBuilder 构建云端请求体</li>
 *   <li>toolSessionId 使用 "cloud-" + UUID 预生成</li>
 *   <li>不需要 session_created 回调</li>
 *   <li>不需要 Agent 在线检查</li>
 *   <li>事件翻译使用 CloudEventTranslator</li>
 * </ul>
 */
@Slf4j
@Component
public class BusinessScopeStrategy implements AssistantScopeStrategy {

    /** SysConfig: 控制 GW 调云端路由用 v1（旧）还是 v2（新 callback config） */
    private static final String CONFIG_TYPE_CLOUD_ROUTE = "cloud_route";
    private static final String CONFIG_KEY_V2_ENABLED = "v2_enabled";

    private final CloudRequestBuilder cloudRequestBuilder;
    private final CloudEventTranslator cloudEventTranslator;
    private final ObjectMapper objectMapper;
    private final com.opencode.cui.skill.service.SysConfigService sysConfigService;

    public BusinessScopeStrategy(CloudRequestBuilder cloudRequestBuilder,
                                 CloudEventTranslator cloudEventTranslator,
                                 ObjectMapper objectMapper,
                                 com.opencode.cui.skill.service.SysConfigService sysConfigService) {
        this.cloudRequestBuilder = cloudRequestBuilder;
        this.cloudEventTranslator = cloudEventTranslator;
        this.objectMapper = objectMapper;
        this.sysConfigService = sysConfigService;
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
        extParameters.put("platformExtParam", objectMapper.createObjectNode());

        CloudRequestContext context = CloudRequestContext.builder()
                .content(content)
                .contentType("text")
                .topicId(toolSessionId)
                .assistantAccount(extractField(command.payload(), "assistantAccount"))
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

        ObjectNode cloudRequest = cloudRequestBuilder.buildCloudRequest(businessTag, context);

        // 构建 payload：GW 的 CloudAgentService 期望 payload.cloudRequest 和 payload.toolSessionId
        ObjectNode payload = objectMapper.createObjectNode();
        payload.set("cloudRequest", cloudRequest);
        if (toolSessionId != null && !toolSessionId.isBlank()) {
            payload.put("toolSessionId", toolSessionId);
        }

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
        // SysConfig 开关：configValue="1" 时 GW 走 v2 callback config 接口；其它情况走 v1
        if ("1".equals(sysConfigService.getValue(CONFIG_TYPE_CLOUD_ROUTE, CONFIG_KEY_V2_ENABLED))) {
            message.put("apiVersion", "v2");
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
        return "cloud-" + UUID.randomUUID().toString().replace("-", "");
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
}
