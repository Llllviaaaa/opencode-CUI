package com.opencode.cui.gateway.service.cloud.decoder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.cui.gateway.model.GatewayMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * OpenCode 标准协议 SSE decoder。
 *
 * <p>等价封装 {@code SseProtocolStrategy.handleDataLine} 原 readValue 逻辑：
 * 直接把 data 行 JSON 反序列化为 {@link GatewayMessage}，单条事件输出。</p>
 *
 * <p>保留行为：</p>
 * <ul>
 *   <li>{@code [DONE]} 与空行由 {@link #isTerminator}/调用方过滤</li>
 *   <li>反序列化失败 catch + warn，不中断流（不抛、不发 error）</li>
 *   <li>不发心跳、不需要 flush 收尾</li>
 * </ul>
 */
@Slf4j
@Component
public class DefaultSseEventDecoder implements SseEventDecoder {

    /** OpenCode 路径无 per-connection 状态，复用单例。 */
    private static final DecoderSession STATELESS_SESSION = new DecoderSession() {};

    private final ObjectMapper objectMapper;

    public DefaultSseEventDecoder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return SseEventDecoderFactory.DEFAULT_DECODER;
    }

    @Override
    public DecoderSession createSession() {
        return STATELESS_SESSION;
    }

    @Override
    public boolean isTerminator(String dataLine) {
        return "[DONE]".equals(dataLine);
    }

    @Override
    public List<GatewayMessage> decode(String dataLineJson, DecoderSession session) {
        if (dataLineJson == null || dataLineJson.isEmpty()) {
            return Collections.emptyList();
        }
        try {
            GatewayMessage message = objectMapper.readValue(dataLineJson, GatewayMessage.class);
            return List.of(message);
        } catch (Exception e) {
            // 与原 SseProtocolStrategy:166-168 容错语义一致：单行解析失败不中断流。
            log.warn("[SSE] Failed to parse event: data={}, error={}", dataLineJson, e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public List<GatewayMessage> flush(DecoderSession session) {
        return Collections.emptyList();
    }
}
