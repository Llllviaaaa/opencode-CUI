package com.opencode.cui.gateway.service.cloud.decoder.assistantsquare;

import com.fasterxml.jackson.databind.JsonNode;
import com.opencode.cui.gateway.model.GatewayMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 未知协议派系兜底 handler。
 *
 * <p>protocolType 为 {@code "athena"} / {@code "uniknow"} / {@code "agentmaker"}
 * 等 MVP 未支持的派系时，由顶层 decoder 路由到本 handler，
 * 直接丢弃事件（返回空列表），不报错。</p>
 */
@Slf4j
@Component
public class UnknownProtocolFallbackHandler implements AssistantSquareProtocolHandler {

    public static final String PROTOCOL_TYPE = "unknown";

    @Override
    public String getProtocolType() {
        return PROTOCOL_TYPE;
    }

    @Override
    public List<GatewayMessage> handle(String eventType, JsonNode data, AssistantSquareDecoderSession session) {
        log.debug("[SSE_DECODER] unknown protocolType, dropping event: eventType={}", eventType);
        return List.of();
    }
}
