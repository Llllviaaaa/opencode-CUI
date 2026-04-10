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

        return node;
    }
}
