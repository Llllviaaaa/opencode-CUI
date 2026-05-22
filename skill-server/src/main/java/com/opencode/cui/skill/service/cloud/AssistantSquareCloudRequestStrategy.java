package com.opencode.cui.skill.service.cloud;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 助手广场云端入参构建策略。
 *
 * <p>映射 {@link CloudRequestContext} 到助手广场 {@code POST /integration/v4-1/gateway/chat}
 * 入参 JSON：</p>
 * <pre>
 * {
 *   "assistantAccount": "dig_30051824",     // String 直传
 *   "sendW3Account":   "u3-xxx",
 *   "msgBody":         "...",
 *   "clientLang":      "zh",
 *   "imGroupId":       "...",                 // 可选
 *   "topicId":         123456789,              // Long.parseLong(ctx.topicId)
 *   "extParameters":   { ... }                 // 透传
 * }
 * </pre>
 *
 * <p>Fast-fail：{@code assistantAccount} / {@code sendUserAccount} 空或
 * {@code topicId} 非数字时抛 {@link IllegalArgumentException}。</p>
 */
@Slf4j
@Component
public class AssistantSquareCloudRequestStrategy implements CloudRequestStrategy {

    public static final String STRATEGY_NAME = "assistant_square";

    private final ObjectMapper objectMapper;

    public AssistantSquareCloudRequestStrategy(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return STRATEGY_NAME;
    }

    @Override
    public ObjectNode build(CloudRequestContext context) {
        validateAccountFields(context);

        ObjectNode node = objectMapper.createObjectNode();

        // assistantAccount 是 String 直传（协议文档"long"是笔误）
        node.put("assistantAccount", context.getAssistantAccount());
        node.put("sendW3Account", context.getSendUserAccount());
        node.put("msgBody", context.getContent() != null ? context.getContent() : "");
        node.put("clientLang", context.getClientLang() != null ? context.getClientLang() : "zh");

        if (context.getImGroupId() != null) {
            node.put("imGroupId", context.getImGroupId());
        }

        // topicId = Long.parseLong(toolSessionId)；toolSessionId 已改 Snowflake 后纯数字
        String topicIdStr = context.getTopicId();
        if (topicIdStr != null && !topicIdStr.isBlank()) {
            try {
                node.put("topicId", Long.parseLong(topicIdStr));
            } catch (NumberFormatException e) {
                log.error("[ERROR] AssistantSquareCloudRequestStrategy.build: invalid topicId (not a long), "
                                + "topicId={}, assistantAccount={}",
                        topicIdStr, context.getAssistantAccount());
                throw new IllegalArgumentException(
                        "topicId must be a numeric (Long) string: " + topicIdStr, e);
            }
        }

        // extParameters 透传：null/empty 时填空对象；非空时序列化为 JsonNode
        Map<String, Object> ext = context.getExtParameters();
        if (ext == null || ext.isEmpty()) {
            node.set("extParameters", objectMapper.createObjectNode());
        } else {
            node.set("extParameters", objectMapper.valueToTree(ext));
        }

        boolean isQuestionReply = context.getReplyToolCallId() != null;
        boolean isPermissionReply = context.getReplyPermissionId() != null;
        if (isQuestionReply || isPermissionReply) {
            ObjectNode replyContext = objectMapper.createObjectNode();
            if (isQuestionReply) {
                replyContext.put("type", "question_reply");
                replyContext.put("toolCallId", context.getReplyToolCallId());
                replyContext.set("answers", objectMapper.valueToTree(context.getReplyAnswers()));
            } else {
                replyContext.put("type", "permission_reply");
                replyContext.put("permissionId", context.getReplyPermissionId());
                replyContext.put("response", context.getReplyResponse());
            }
            node.set("replyContext", replyContext);
        }

        return node;
    }

    private void validateAccountFields(CloudRequestContext context) {
        if (isBlank(context.getAssistantAccount())) {
            log.error("[ERROR] AssistantSquareCloudRequestStrategy.build: blank assistantAccount, "
                            + "topicId={}, messageId={}",
                    context.getTopicId(), context.getMessageId());
            throw new IllegalArgumentException("assistantAccount must not be blank");
        }
        if (isBlank(context.getSendUserAccount())) {
            log.error("[ERROR] AssistantSquareCloudRequestStrategy.build: blank sendUserAccount, "
                            + "topicId={}, assistantAccount={}",
                    context.getTopicId(), context.getAssistantAccount());
            throw new IllegalArgumentException("sendUserAccount must not be blank");
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
