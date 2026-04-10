package com.opencode.cui.skill.service.scope;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.opencode.cui.skill.model.AssistantInfo;
import com.opencode.cui.skill.model.InvokeCommand;
import com.opencode.cui.skill.model.StreamMessage;
import com.opencode.cui.skill.service.CloudEventTranslator;
import com.opencode.cui.skill.service.cloud.CloudRequestBuilder;
import com.opencode.cui.skill.service.cloud.CloudRequestContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

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

    private final CloudRequestBuilder cloudRequestBuilder;
    private final CloudEventTranslator cloudEventTranslator;
    private final ObjectMapper objectMapper;

    public BusinessScopeStrategy(CloudRequestBuilder cloudRequestBuilder,
                                 CloudEventTranslator cloudEventTranslator,
                                 ObjectMapper objectMapper) {
        this.cloudRequestBuilder = cloudRequestBuilder;
        this.cloudEventTranslator = cloudEventTranslator;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getScope() {
        return "business";
    }

    @Override
    public String buildInvoke(InvokeCommand command, AssistantInfo info) {
        String appId = info.getAppId();

        // 从 command payload 提取 content
        String content = extractContent(command.payload());

        CloudRequestContext context = CloudRequestContext.builder()
                .content(content)
                .contentType("text")
                .build();

        ObjectNode cloudRequest = cloudRequestBuilder.buildCloudRequest(appId, context);

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
        message.set("payload", cloudRequest);

        try {
            return objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize business invoke message: ak={}, appId={}",
                    command.ak(), appId, e);
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
        return cloudEventTranslator.translate(event);
    }

    // ------------------------------------------------------------------ private

    private String extractContent(String payload) {
        if (payload == null || payload.isBlank()) {
            return "";
        }
        try {
            JsonNode node = objectMapper.readTree(payload);
            return node.path("content").asText(node.path("message").asText(""));
        } catch (JsonProcessingException e) {
            log.debug("Failed to parse payload for content extraction, using raw: {}", e.getMessage());
            return payload;
        }
    }
}
