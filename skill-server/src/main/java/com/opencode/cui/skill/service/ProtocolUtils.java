package com.opencode.cui.skill.service;

/**
 * 协议相关的通用工具方法。
 * 从 OpenCodeEventTranslator 和 MessagePersistenceService 中提取的重复逻辑。
 */
public final class ProtocolUtils {

    private ProtocolUtils() {
    }

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
}
