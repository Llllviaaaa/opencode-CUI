package com.opencode.cui.skill.telemetry.client.dto;

import java.util.Map;

/**
 * 加密前的业务明文 payload。
 *
 * <p>{@code data} 即业务事件字段（policyName 之外的所有字段），
 * {@code policyName} 在外部协议中既出现在 envelope 之外又重复在明文里。
 *
 * <p><b>内部传输 DTO</b> —— 仅服务于 telemetry 内部 reporter/client 串联，
 * 字段名按 WeLink producer 协议固定。
 */
public record TelemetryPayload(Map<String, Object> data, String policyName) {
}
