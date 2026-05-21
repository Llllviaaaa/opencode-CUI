package com.opencode.cui.skill.telemetry.core;

import java.util.Map;

/**
 * 上报事件统一视图。Reporter 拿到本接口后直接组装 {@code data}。
 *
 * <p>实现方负责字段映射（eventId / eventLabel / userId / sessionId / extendData ...）。
 * 当前仅 chat 一个业务（PRD §9 显式不抽 TelemetryReporter 通用接口），本接口仅是
 * 事件描述结构，不构成跨业务抽象。
 */
public interface TelemetryEvent {

    /** 埋码事件 ID（如 {@code skill_chat_request}） */
    String eventId();

    /** 事件中文标签 */
    String eventLabel();

    /** 关联的会话 ID（{@code businessSessionId}），用于 request/response 配对 */
    String sessionId();

    /** 事件携带的用户标识：request 事件=senderUserAccount；reply 事件=assistantAccount */
    String userId();

    /** 扩展字段（业务侧自由组装） */
    Map<String, Object> extendData();
}
