package com.opencode.cui.gateway.service.cloud.decoder.assistantsquare;

import com.fasterxml.jackson.databind.JsonNode;
import com.opencode.cui.gateway.model.GatewayMessage;

import java.util.List;

/**
 * 助手广场子 handler 接口（按 {@code data.protocolType} 二级分派）。
 *
 * <p>MVP 实现：</p>
 * <ul>
 *   <li>{@code StandardProtocolHandler} —— protocolType={@code "standard"} 派系，
 *       含状态机 + {@code step.start}/{@code step.done} 补齐</li>
 *   <li>{@code UnknownProtocolFallbackHandler} —— 未知派系（{@code athena} /
 *       {@code uniknow} / {@code agentmaker} 等）兜底丢弃</li>
 * </ul>
 *
 * <p>未来增加新派系时，新增 @Component 类即可，{@code AssistantSquareSseEventDecoder}
 * 顶层零改动。</p>
 */
public interface AssistantSquareProtocolHandler {

    /** 派系类型：{@code "standard"} / {@code "athena"} / {@code "uniknow"} / {@code "agentmaker"} / {@code "unknown"} 等。 */
    String getProtocolType();

    /**
     * 处理一条事件 data。
     *
     * @param eventType 上层 SSE {@code event:<eventType>} 头解析出的事件类型
     * @param data      data 行解析出的 JSON
     * @param session   per-connection 状态
     * @return 零或多条标准 {@link GatewayMessage}
     */
    List<GatewayMessage> handle(String eventType, JsonNode data, AssistantSquareDecoderSession session);

    /**
     * 流终止时由 decoder 委托调用，补未关闭 part 的 {@code .done} 与 {@code step.done}。
     * 默认空实现（多数派系无状态）。
     */
    default List<GatewayMessage> flush(AssistantSquareDecoderSession session) {
        return List.of();
    }
}
