package com.opencode.cui.skill.service.cloud;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 默认云端请求构建策略。
 * 将 {@link CloudRequestContext} 中的字段直接映射到标准协议请求体。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultCloudRequestStrategy implements CloudRequestStrategy {

    static final String STRATEGY_NAME = "default";

    private final ObjectMapper objectMapper;

    @Override
    public String getName() {
        return STRATEGY_NAME;
    }

    /**
     * 构建标准协议请求体，字段直接映射：
     * <ul>
     *   <li>type = contentType（默认 "text"）</li>
     *   <li>content, assistantAccount, sendUserAccount, imGroupId</li>
     *   <li>clientLang（默认 "zh"）, clientType, topicId, messageId</li>
     *   <li>extParameters（null 时设为空对象）</li>
     * </ul>
     */
    @Override
    public ObjectNode build(CloudRequestContext context) {
        // Fast-fail：vendor SDK 内部对关键账号字段不防御 null（List.of(null) 会 NPE），
        // 在仓内入口先校验，提供明确日志便于定位 rebuild 路径上的数据缺失。
        validateAccountFields(context);

        ObjectNode node = objectMapper.createObjectNode();

        node.put("type", context.getContentType() != null ? context.getContentType() : "text");
        node.put("content", context.getContent());
        node.put("assistantAccount", context.getAssistantAccount());
        node.put("sendUserAccount", context.getSendUserAccount());
        node.put("imGroupId", context.getImGroupId());
        node.put("clientLang", context.getClientLang() != null ? context.getClientLang() : "zh");
        node.put("clientType", context.getClientType());
        node.put("topicId", context.getTopicId());
        node.put("messageId", context.getMessageId());

        Map<String, Object> ext = context.getExtParameters();
        if (ext == null || ext.isEmpty()) {
            node.set("extParameters", objectMapper.createObjectNode());
        } else {
            node.set("extParameters", objectMapper.valueToTree(ext));
        }

        // 仅在 question_reply / permission_reply 时写入 replyContext 嵌套对象；chat 不写
        boolean isQR = context.getReplyToolCallId() != null;
        boolean isPR = context.getReplyPermissionId() != null;
        if (isQR || isPR) {
            ObjectNode replyContext = objectMapper.createObjectNode();
            if (isQR) {
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

    /**
     * 关键账号字段 fast-fail 校验。
     *
     * <p>vendor SDK 在 {@code convertToW3Account} 中调用 {@code List.of(<null>)} 不防御 null，
     * 此前因 rebuild 路径的 context 缺失 assistantAccount/sendUserAccount 导致进入 vendor 后 NPE，
     * 用户收到黑屏错误。这里在仓内入口主动校验，让缺失字段在 SS 层显式失败。</p>
     */
    private void validateAccountFields(CloudRequestContext context) {
        if (isBlank(context.getAssistantAccount())) {
            log.error("[ERROR] DefaultCloudRequestStrategy.build: blank assistantAccount, topicId={}, messageId={}",
                    context.getTopicId(), context.getMessageId());
            throw new IllegalArgumentException("assistantAccount must not be blank");
        }
        if (isBlank(context.getSendUserAccount())) {
            log.error("[ERROR] DefaultCloudRequestStrategy.build: blank sendUserAccount, topicId={}, messageId={}, assistantAccount={}",
                    context.getTopicId(), context.getMessageId(), context.getAssistantAccount());
            throw new IllegalArgumentException("sendUserAccount must not be blank");
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
