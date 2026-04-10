package com.opencode.cui.skill.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.opencode.cui.skill.model.StreamMessage;
import com.opencode.cui.skill.model.StreamMessage.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 云端事件翻译器：使用注册表模式将云端 CloudEvent 翻译为前端 StreamMessage。
 *
 * <p>每种 event.type 对应一个 {@link CloudEventHandler}，在 {@link #init()} 中注册。
 * {@link #translate(JsonNode)} 查表调用对应 handler。</p>
 */
@Slf4j
@Component
public class CloudEventTranslator {

    /**
     * 云端事件处理函数接口。
     */
    @FunctionalInterface
    public interface CloudEventHandler {
        StreamMessage handle(JsonNode event);
    }

    private final Map<String, CloudEventHandler> handlers = new HashMap<>();

    @PostConstruct
    void init() {
        // --- text ---
        handlers.put("text.delta", this::handleTextDelta);
        handlers.put("text.done", this::handleTextDone);

        // --- thinking ---
        handlers.put("thinking.delta", this::handleThinkingDelta);
        handlers.put("thinking.done", this::handleThinkingDone);

        // --- tool ---
        handlers.put("tool.update", this::handleToolUpdate);

        // --- step ---
        handlers.put("step.start", this::handleStepStart);
        handlers.put("step.done", this::handleStepDone);

        // --- question ---
        handlers.put("question", this::handleQuestion);

        // --- permission ---
        handlers.put("permission.ask", this::handlePermissionAsk);
        handlers.put("permission.reply", this::handlePermissionReply);

        // --- session ---
        handlers.put("session.status", this::handleSessionStatus);
        handlers.put("session.title", this::handleSessionTitle);
        handlers.put("session.error", this::handleSessionError);

        // --- file ---
        handlers.put("file", this::handleFile);

        // --- planning ---
        handlers.put("planning.delta", this::handlePlanningDelta);
        handlers.put("planning.done", this::handlePlanningDone);

        // --- search ---
        handlers.put("searching", this::handleSearching);
        handlers.put("search_result", this::handleSearchResult);

        // --- reference ---
        handlers.put("reference", this::handleReference);

        // --- ask_more ---
        handlers.put("ask_more", this::handleAskMore);
    }

    /**
     * 翻译云端事件为 StreamMessage。
     *
     * @param event 云端事件 JSON
     * @return 翻译后的 StreamMessage，未知类型返回 null
     */
    public StreamMessage translate(JsonNode event) {
        if (event == null) {
            return null;
        }

        String eventType = event.path("type").asText(null);
        if (eventType == null || eventType.isEmpty()) {
            return null;
        }

        CloudEventHandler handler = handlers.get(eventType);
        if (handler == null) {
            log.debug("Unknown cloud event type: {}", eventType);
            return null;
        }

        return handler.handle(event);
    }

    // ==================== Text Handlers ====================

    private StreamMessage handleTextDelta(JsonNode event) {
        return StreamMessage.builder()
                .type(Types.TEXT_DELTA)
                .content(event.path("content").asText(null))
                .role(event.path("role").asText(null))
                .build();
    }

    private StreamMessage handleTextDone(JsonNode event) {
        return StreamMessage.builder()
                .type(Types.TEXT_DONE)
                .content(event.path("content").asText(null))
                .role(event.path("role").asText(null))
                .messageId(event.path("messageId").asText(null))
                .partId(event.path("partId").asText(null))
                .build();
    }

    // ==================== Thinking Handlers ====================

    private StreamMessage handleThinkingDelta(JsonNode event) {
        return StreamMessage.builder()
                .type(Types.THINKING_DELTA)
                .content(event.path("content").asText(null))
                .role(event.path("role").asText(null))
                .build();
    }

    private StreamMessage handleThinkingDone(JsonNode event) {
        return StreamMessage.builder()
                .type(Types.THINKING_DONE)
                .content(event.path("content").asText(null))
                .role(event.path("role").asText(null))
                .messageId(event.path("messageId").asText(null))
                .partId(event.path("partId").asText(null))
                .build();
    }

    // ==================== Tool Handler ====================

    private StreamMessage handleToolUpdate(JsonNode event) {
        return StreamMessage.builder()
                .type(Types.TOOL_UPDATE)
                .tool(ToolInfo.builder()
                        .toolName(event.path("toolName").asText(null))
                        .toolCallId(event.path("toolCallId").asText(null))
                        .input(event.path("input").asText(null))
                        .output(event.path("output").asText(null))
                        .build())
                .status(event.path("status").asText(null))
                .error(event.path("error").asText(null))
                .title(event.path("title").asText(null))
                .build();
    }

    // ==================== Step Handlers ====================

    private StreamMessage handleStepStart(JsonNode event) {
        return StreamMessage.builder()
                .type(Types.STEP_START)
                .messageId(event.path("messageId").asText(null))
                .role(event.path("role").asText(null))
                .build();
    }

    @SuppressWarnings("unchecked")
    private StreamMessage handleStepDone(JsonNode event) {
        UsageInfo.UsageInfoBuilder usageBuilder = UsageInfo.builder();

        JsonNode tokensNode = event.get("tokens");
        if (tokensNode != null && !tokensNode.isNull() && tokensNode.isObject()) {
            Map<String, Object> tokens = new HashMap<>();
            tokensNode.fields().forEachRemaining(entry ->
                    tokens.put(entry.getKey(), entry.getValue().numberValue()));
            usageBuilder.tokens(tokens);
        }

        double cost = event.path("cost").asDouble(0);
        if (cost > 0) {
            usageBuilder.cost(cost);
        }

        String reason = event.path("reason").asText(null);
        if (reason != null) {
            usageBuilder.reason(reason);
        }

        return StreamMessage.builder()
                .type(Types.STEP_DONE)
                .usage(usageBuilder.build())
                .build();
    }

    // ==================== Question Handler ====================

    private StreamMessage handleQuestion(JsonNode event) {
        return StreamMessage.builder()
                .type(Types.QUESTION)
                .tool(ToolInfo.builder()
                        .toolCallId(event.path("toolCallId").asText(null))
                        .build())
                .status(event.path("status").asText(null))
                .questionInfo(QuestionInfo.builder()
                        .question(event.path("question").asText(null))
                        .header(event.path("header").asText(null))
                        .options(toStringList(event.get("options")))
                        .build())
                .build();
    }

    // ==================== Permission Handlers ====================

    private StreamMessage handlePermissionAsk(JsonNode event) {
        return StreamMessage.builder()
                .type(Types.PERMISSION_ASK)
                .permission(PermissionInfo.builder()
                        .permissionId(event.path("permissionId").asText(null))
                        .permType(event.path("permType").asText(null))
                        .metadata(event.has("metadata") ? event.get("metadata") : null)
                        .build())
                .title(event.path("title").asText(null))
                .build();
    }

    private StreamMessage handlePermissionReply(JsonNode event) {
        return StreamMessage.builder()
                .type(Types.PERMISSION_REPLY)
                .permission(PermissionInfo.builder()
                        .permissionId(event.path("permissionId").asText(null))
                        .permType(event.path("permType").asText(null))
                        .response(event.path("response").asText(null))
                        .build())
                .build();
    }

    // ==================== Session Handlers ====================

    private StreamMessage handleSessionStatus(JsonNode event) {
        return StreamMessage.sessionStatus(event.path("sessionStatus").asText(null));
    }

    private StreamMessage handleSessionTitle(JsonNode event) {
        return StreamMessage.builder()
                .type(Types.SESSION_TITLE)
                .title(event.path("title").asText(null))
                .build();
    }

    private StreamMessage handleSessionError(JsonNode event) {
        return StreamMessage.builder()
                .type(Types.SESSION_ERROR)
                .error(event.path("error").asText(null))
                .build();
    }

    // ==================== File Handler ====================

    private StreamMessage handleFile(JsonNode event) {
        return StreamMessage.builder()
                .type(Types.FILE)
                .file(FileInfo.builder()
                        .fileName(event.path("fileName").asText(null))
                        .fileUrl(event.path("fileUrl").asText(null))
                        .fileMime(event.path("fileMime").asText(null))
                        .build())
                .build();
    }

    // ==================== Planning Handlers ====================

    private StreamMessage handlePlanningDelta(JsonNode event) {
        return StreamMessage.builder()
                .type(Types.PLANNING_DELTA)
                .content(event.path("content").asText(null))
                .build();
    }

    private StreamMessage handlePlanningDone(JsonNode event) {
        return StreamMessage.builder()
                .type(Types.PLANNING_DONE)
                .content(event.path("content").asText(null))
                .build();
    }

    // ==================== Search Handlers ====================

    private StreamMessage handleSearching(JsonNode event) {
        return StreamMessage.builder()
                .type(Types.SEARCHING)
                .keywords(toStringList(event.get("keywords")))
                .build();
    }

    private StreamMessage handleSearchResult(JsonNode event) {
        return StreamMessage.builder()
                .type(Types.SEARCH_RESULT)
                .searchResults(toSearchResultList(event.get("searchResults")))
                .build();
    }

    // ==================== Reference Handler ====================

    private StreamMessage handleReference(JsonNode event) {
        return StreamMessage.builder()
                .type(Types.REFERENCE)
                .references(toReferenceList(event.get("references")))
                .build();
    }

    // ==================== Ask More Handler ====================

    private StreamMessage handleAskMore(JsonNode event) {
        return StreamMessage.builder()
                .type(Types.ASK_MORE)
                .askMoreQuestions(toStringList(event.get("askMoreQuestions")))
                .build();
    }

    // ==================== Utility Methods ====================

    /**
     * 将 JSON 数组转换为 String 列表。
     */
    private List<String> toStringList(JsonNode arrayNode) {
        if (arrayNode == null || !arrayNode.isArray()) {
            return null;
        }
        List<String> list = new ArrayList<>();
        for (JsonNode item : arrayNode) {
            list.add(item.asText());
        }
        return list.isEmpty() ? null : list;
    }

    /**
     * 将 JSON 数组转换为 SearchResultItem 列表。
     */
    private List<SearchResultItem> toSearchResultList(JsonNode arrayNode) {
        if (arrayNode == null || !arrayNode.isArray()) {
            return null;
        }
        List<SearchResultItem> list = new ArrayList<>();
        for (JsonNode item : arrayNode) {
            list.add(SearchResultItem.builder()
                    .index(item.path("index").asText(null))
                    .title(item.path("title").asText(null))
                    .source(item.path("source").asText(null))
                    .build());
        }
        return list.isEmpty() ? null : list;
    }

    /**
     * 将 JSON 数组转换为 ReferenceItem 列表。
     */
    private List<ReferenceItem> toReferenceList(JsonNode arrayNode) {
        if (arrayNode == null || !arrayNode.isArray()) {
            return null;
        }
        List<ReferenceItem> list = new ArrayList<>();
        for (JsonNode item : arrayNode) {
            list.add(ReferenceItem.builder()
                    .index(item.path("index").asText(null))
                    .title(item.path("title").asText(null))
                    .source(item.path("source").asText(null))
                    .url(item.path("url").asText(null))
                    .content(item.path("content").asText(null))
                    .build());
        }
        return list.isEmpty() ? null : list;
    }
}
