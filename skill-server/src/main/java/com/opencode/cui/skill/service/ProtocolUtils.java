package com.opencode.cui.skill.service;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 协议相关的通用工具方法。
 * 从 OpenCodeEventTranslator / ProtocolMessageMapper / Controller 中提取的重复逻辑。
 */
public final class ProtocolUtils {

    private static final Pattern QUESTION_ANSWER_PATTERN = Pattern.compile("\"([^\"]+)\"=\"([^\"]*)\"");

    private ProtocolUtils() {
    }

    // ==================== 字符串 ====================

    /**
     * 返回第一个非空白字符串，都为空时返回 null。
     */
    public static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        return null;
    }

    /**
     * 角色名标准化：null/空白 → "assistant"，其余转小写。
     */
    public static String normalizeRole(String role) {
        if (role == null || role.isBlank()) {
            return "assistant";
        }
        return role.toLowerCase();
    }

    // ==================== Session ID 解析 ====================

    /**
     * 将 sessionId 字符串安全解析为 Long。
     * 统一替代散落在 16+ 处的 Long.parseLong / Long.valueOf 异常处理。
     *
     * @return 解析后的 Long，无法解析时返回 null
     */
    public static Long parseSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(sessionId);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ==================== Question payload 解析 ====================

    /**
     * 从 question 工具的 input JSON 中解析出第一个问题节点。
     * 如果 input 包含 questions 数组，取第一个元素；否则返回 input 本身。
     * 统一替代 OpenCodeEventTranslator 和 ProtocolMessageMapper 中的重复实现。
     */
    public static JsonNode resolveQuestionPayload(JsonNode input) {
        if (input == null || !input.isObject()) {
            return null;
        }

        JsonNode questionsNode = input.get("questions");
        if (questionsNode != null && questionsNode.isArray() && !questionsNode.isEmpty()) {
            JsonNode firstQuestion = questionsNode.get(0);
            if (firstQuestion != null && firstQuestion.isObject()) {
                return firstQuestion;
            }
        }

        return input;
    }

    /**
     * 从 question 节点中提取选项列表（含 label + 可选 description）。
     *
     * <p>对齐 OpenCode question 工具 {@code options} 入参的两种形态：</p>
     * <ul>
     *   <li>纯字符串 {@code ["A", "B"]} → 每项 {@code QuestionOption(label, null)}</li>
     *   <li>对象数组 {@code [{label, description?}, ...]} → 完整保留</li>
     * </ul>
     *
     * @return 选项列表，无选项时返回 null
     */
    public static List<com.opencode.cui.skill.model.StreamMessage.QuestionOption> extractQuestionOptions(JsonNode optionsNode) {
        if (optionsNode == null || !optionsNode.isArray()) {
            return null;
        }

        List<com.opencode.cui.skill.model.StreamMessage.QuestionOption> options = new ArrayList<>();
        for (JsonNode optionNode : optionsNode) {
            String label = null;
            String description = null;
            if (optionNode.isTextual()) {
                label = optionNode.asText();
            } else if (optionNode.isObject()) {
                JsonNode labelNode = optionNode.get("label");
                if (labelNode != null && labelNode.isTextual()) {
                    label = labelNode.asText();
                }
                JsonNode descNode = optionNode.get("description");
                if (descNode != null && descNode.isTextual()) {
                    String d = descNode.asText();
                    description = (d == null || d.isBlank()) ? null : d;
                }
            }
            if (label != null && !label.isBlank()) {
                options.add(com.opencode.cui.skill.model.StreamMessage.QuestionOption.builder()
                        .label(label)
                        .description(description)
                        .build());
            }
        }
        return options.isEmpty() ? null : options;
    }

    public static String normalizeQuestionAnswerOutput(String rawOutput, JsonNode input) {
        if (rawOutput == null || rawOutput.isBlank()) {
            return rawOutput;
        }

        Matcher matcher = QUESTION_ANSWER_PATTERN.matcher(rawOutput);
        List<String[]> pairs = new ArrayList<>();
        while (matcher.find()) {
            pairs.add(new String[] { matcher.group(1), matcher.group(2) });
        }

        if (pairs.isEmpty()) {
            return rawOutput;
        }

        JsonNode questionNode = resolveQuestionPayload(input);
        String expectedQuestion = questionNode != null ? questionNode.path("question").asText(null) : null;
        if (expectedQuestion != null) {
            for (String[] pair : pairs) {
                if (expectedQuestion.equals(pair[0])) {
                    return pair[1];
                }
            }
        }

        if (pairs.size() == 1) {
            return pairs.get(0)[1];
        }

        List<String> normalized = new ArrayList<>();
        for (String[] pair : pairs) {
            normalized.add(pair[0] + "\n" + pair[1]);
        }
        return String.join("\n\n", normalized);
    }
}
