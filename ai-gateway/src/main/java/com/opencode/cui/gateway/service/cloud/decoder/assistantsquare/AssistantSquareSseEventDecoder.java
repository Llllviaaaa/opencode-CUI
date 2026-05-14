package com.opencode.cui.gateway.service.cloud.decoder.assistantsquare;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.gateway.model.GatewayMessage;
import com.opencode.cui.gateway.service.cloud.decoder.DecoderSession;
import com.opencode.cui.gateway.service.cloud.decoder.SseEventDecoder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 助手广场 SSE decoder（顶层）。
 *
 * <p>名称：{@code "assistant_square"}（与 cloud profile name 对齐）。</p>
 *
 * <p>按 {@code data.protocolType} 二级分派到对应 {@link AssistantSquareProtocolHandler}：</p>
 * <ul>
 *   <li>{@code "standard"}（默认）→ {@link StandardProtocolHandler}</li>
 *   <li>其他 / 未知 → {@link UnknownProtocolFallbackHandler}（丢弃）</li>
 * </ul>
 *
 * <p>事件类型来自 data JSON 中的 {@code eventType} 字段（助手广场 data 体已内嵌）；
 * SseProtocolStrategy 主循环只识别 {@code data:} 行，{@code event:} 行已被忽略，
 * 因此 eventType 通过 JSON 字段传递。</p>
 */
@Slf4j
@Component
public class AssistantSquareSseEventDecoder implements SseEventDecoder {

    public static final String DECODER_NAME = "assistant_square";

    private final ObjectMapper objectMapper;
    private final Map<String, AssistantSquareProtocolHandler> handlerMap;
    private final AssistantSquareProtocolHandler unknownHandler;
    private final StandardProtocolHandler standardHandler;

    public AssistantSquareSseEventDecoder(ObjectMapper objectMapper,
                                          List<AssistantSquareProtocolHandler> handlers,
                                          StandardProtocolHandler standardHandler,
                                          UnknownProtocolFallbackHandler unknownHandler) {
        this.objectMapper = objectMapper;
        this.standardHandler = standardHandler;
        this.unknownHandler = unknownHandler;
        this.handlerMap = handlers.stream()
                .collect(Collectors.toMap(AssistantSquareProtocolHandler::getProtocolType, Function.identity()));
        log.info("[SSE_DECODER] AssistantSquare handlers: {}", handlerMap.keySet());
    }

    @Override
    public String getName() {
        return DECODER_NAME;
    }

    @Override
    public DecoderSession createSession() {
        return new AssistantSquareDecoderSession();
    }

    @Override
    public boolean isTerminator(String dataLine) {
        return "FINISH".equals(dataLine) || "[DONE]".equals(dataLine);
    }

    @Override
    public boolean isHeartbeat(String dataLine) {
        if (dataLine == null) return false;
        // 业务心跳：data 体含 "eventType":"ping"（不解析整个 JSON，快速识别）
        return dataLine.contains("\"eventType\":\"ping\"");
    }

    @Override
    public List<GatewayMessage> decode(String dataLineJson, DecoderSession session) {
        if (!(session instanceof AssistantSquareDecoderSession s)) {
            log.warn("[SSE_DECODER] invalid session type: {}", session == null ? null : session.getClass());
            return Collections.emptyList();
        }
        if (dataLineJson == null || dataLineJson.isEmpty()) {
            return Collections.emptyList();
        }
        try {
            JsonNode data = objectMapper.readTree(dataLineJson);
            String protocolType = data.path("protocolType").asText("standard");
            if (protocolType == null || protocolType.isBlank()) {
                protocolType = "standard";
            }
            String eventType = data.path("eventType").asText(null);

            AssistantSquareProtocolHandler handler = handlerMap.getOrDefault(protocolType, unknownHandler);
            return handler.handle(eventType, data, s);
        } catch (Exception e) {
            log.warn("[SSE_DECODER] failed to parse assistant_square event: data={}, error={}",
                    dataLineJson, e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public List<GatewayMessage> flush(DecoderSession session) {
        if (!(session instanceof AssistantSquareDecoderSession s)) {
            return Collections.emptyList();
        }
        return standardHandler.flush(s);
    }
}
