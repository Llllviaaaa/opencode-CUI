package com.opencode.cui.skill.service;

import com.opencode.cui.skill.model.ImMessageRequest;
import com.opencode.cui.skill.model.SkillSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 上下文注入服务。
 * 在群聊场景下，将 chatHistory（聊天历史）拼接到当前消息中，
 * 使 AI 能理解群聊上下文后再回复。
 *
 * 单聊场景不注入上下文（AI Gateway 自行管理对话历史）。
 */
@Slf4j
@Service
public class ContextInjectionService {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.of("Asia/Shanghai")); // 固定使用北京时间

    private final ResourceLoader resourceLoader; // Spring 资源加载器
    private final boolean injectionEnabled; // 是否启用上下文注入（全局开关）
    private final String groupChatTemplateLocation; // 群聊 prompt 模板文件位置
    private final int maxHistoryMessages; // 最多拼接的历史消息条数

    public ContextInjectionService(
            ResourceLoader resourceLoader,
            @org.springframework.beans.factory.annotation.Value("${skill.context.injection-enabled:true}") boolean injectionEnabled,
            @org.springframework.beans.factory.annotation.Value("${skill.context.templates.group-chat:classpath:templates/group-chat-prompt.txt}") String groupChatTemplateLocation,
            @org.springframework.beans.factory.annotation.Value("${skill.context.max-history-messages:20}") int maxHistoryMessages) {
        this.resourceLoader = resourceLoader;
        this.injectionEnabled = injectionEnabled;
        this.groupChatTemplateLocation = groupChatTemplateLocation;
        this.maxHistoryMessages = maxHistoryMessages;
    }

    /**
     * 解析并构建最终 prompt。
     * - 群聊且启用注入：将 chatHistory 格式化后填入模板，与 currentMessage 合并
     * - 单聊或未启用：直接返回 currentMessage
     *
     * @param sessionType    会话类型（group / direct）
     * @param currentMessage 当前用户发送的消息内容
     * @param chatHistory    IM 平台传入的群聊历史消息列表
     * @return 最终发送给 AI 的 prompt
     */
    public String resolvePrompt(String sessionType, String currentMessage,
            List<ImMessageRequest.ChatMessage> chatHistory) {
        log.debug("Resolving prompt: sessionType={}, historySize={}, messageLength={}",
                sessionType,
                chatHistory != null ? chatHistory.size() : 0,
                currentMessage != null ? currentMessage.length() : 0);
        if (!injectionEnabled
                || !SkillSession.SESSION_TYPE_GROUP.equalsIgnoreCase(sessionType)
                || currentMessage == null
                || currentMessage.isBlank()) {
            log.debug("Context injection skipped: injectionEnabled={}, sessionType={}",
                    injectionEnabled, sessionType);
            return currentMessage; // 不满足注入条件，原样返回
        }

        String historyText = formatHistory(chatHistory); // 格式化历史消息
        if (historyText.isBlank()) {
            return currentMessage; // 无有效历史，原样返回
        }

        String template = loadTemplate(); // 加载 prompt 模板文件
        if (template == null || template.isBlank()) {
            return currentMessage; // 模板缺失，原样返回
        }

        // 替换模板占位符
        String result = template
                .replace("{{chatHistory}}", historyText) // 插入格式化后的历史消息
                .replace("{{currentMessage}}", currentMessage); // 插入当前用户消息
        log.info("Context injection applied: sessionType={}, historyLength={}, resultLength={}",
                sessionType, historyText.length(), result.length());
        return result;
    }

    /** 从 classpath 或文件系统加载群聊 prompt 模板 */
    private String loadTemplate() {
        try {
            Resource resource = resourceLoader.getResource(groupChatTemplateLocation);
            return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("Failed to load group chat prompt template: location={}, error={}",
                    groupChatTemplateLocation, e.getMessage());
            return null;
        }
    }

    /**
     * 将聊天历史格式化为文本。
     * 格式：[yyyy-MM-dd HH:mm:ss] 发送者名称: 消息内容
     * 只保留最近 maxHistoryMessages 条消息。
     */
    private String formatHistory(List<ImMessageRequest.ChatMessage> chatHistory) {
        if (chatHistory == null || chatHistory.isEmpty()) {
            return "";
        }

        int start = Math.max(0, chatHistory.size() - maxHistoryMessages);
        StringBuilder history = new StringBuilder();
        for (ImMessageRequest.ChatMessage item : chatHistory.subList(start, chatHistory.size())) {
            if (item == null || item.content() == null || item.content().isBlank()) {
                continue;
            }
            if (history.length() > 0) {
                history.append('\n');
            }
            history.append('[')
                    .append(formatTimestamp(item.timestamp()))
                    .append("] ")
                    .append(item.senderName() != null && !item.senderName().isBlank()
                            ? item.senderName()
                            : item.senderAccount())
                    .append(": ")
                    .append(item.content());
        }
        return history.toString();
    }

    /**
     * 格式化时间戳。
     * 自动检测秒级/毫秒级时间戳并转为北京时间字符串。
     */
    private String formatTimestamp(long rawTimestamp) {
        long millis = rawTimestamp > 0 && rawTimestamp < 10_000_000_000L
                ? rawTimestamp * 1000
                : rawTimestamp;
        if (millis <= 0) {
            return "unknown-time";
        }
        return TIME_FORMATTER.format(Instant.ofEpochMilli(millis));
    }
}
