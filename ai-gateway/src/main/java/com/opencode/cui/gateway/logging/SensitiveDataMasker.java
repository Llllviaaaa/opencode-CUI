package com.opencode.cui.gateway.logging;

/**
 * 敏感数据脱敏工具。
 * 用于日志输出中对 MAC 地址、Token 等敏感信息进行脱敏。
 */
public final class SensitiveDataMasker {

    private SensitiveDataMasker() {
    }

    /**
     * 脱敏 MAC 地址，仅保留后 4 位。
     * 例: "AA:BB:CC:DD:EE:FF" → "****:EE:FF"
     */
    public static String maskMac(String mac) {
        if (mac == null || mac.length() <= 5) {
            return "****";
        }
        return "****" + mac.substring(mac.length() - 6);
    }

    /**
     * 脱敏 Token，显示前 4 后 4 位。
     * 例: "abcdefghijklmnop" → "abcd****mnop"
     */
    public static String maskToken(String token) {
        if (token == null || token.length() <= 8) {
            return "****";
        }
        return token.substring(0, 4) + "****" + token.substring(token.length() - 4);
    }
}
