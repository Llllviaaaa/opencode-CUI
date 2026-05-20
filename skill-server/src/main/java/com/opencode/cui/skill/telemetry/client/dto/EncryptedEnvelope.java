package com.opencode.cui.skill.telemetry.client.dto;

/**
 * 加密信封 {@code {key, content}}：HTTP body 顶层结构。
 *
 * <p><b>内部传输 DTO</b> —— 不是外部 API 契约（外部契约是 WeLink producer 接口本身的字段名约定）；
 * 本 record 仅服务于 telemetry 内部 client/reporter 串联，序列化字段名与 WeLink 协议一致。
 */
public record EncryptedEnvelope(String key, String content) {
}
